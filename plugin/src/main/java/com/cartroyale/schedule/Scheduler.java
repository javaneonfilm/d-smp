package com.cartroyale.schedule;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import com.cartroyale.game.GameRow;
import com.cartroyale.game.GameSettings;
import com.cartroyale.game.Mode;
import com.cartroyale.game.Modifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HOST-3. Recurring games from schedule.yml. A game is created (and announced) a set time ahead
 * of each occurrence; the source key "schedule:&lt;id&gt;:&lt;epoch&gt;" is unique in the database,
 * so restarts never create the same game twice. Also posts the daily "today's games" list.
 */
public final class Scheduler {

    private final CartRoyale plugin;
    private final Yml yml;
    private final Map<String, ScheduleEntry> entries = new LinkedHashMap<>();
    /** Occurrences already created (or found in the database) this run, so they are only tried once. */
    private final java.util.Set<String> tried = new java.util.HashSet<>();
    private long lastCheck;
    private String lastDailyPost;

    public Scheduler(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "schedule.yml", true);
        load();
        plugin.db().kvGet("daily-post.last").thenAccept(v -> lastDailyPost = v);
    }

    public void reload() {
        yml.reload();
        load();
    }

    private void load() {
        entries.clear();
        ConfigurationSection sec = yml.get().getConfigurationSection("games");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) continue;
            ScheduleEntry e = new ScheduleEntry();
            e.id = id;
            e.days.clear();
            e.days.addAll(ScheduleEntry.parseDays(c.getString("days", "daily")));
            LocalTime t = ScheduleEntry.parseTime(c.getString("time", "16:00"));
            if (t == null) {
                plugin.getLogger().warning("schedule.yml: game '" + id + "' has an unreadable time, skipped");
                continue;
            }
            e.time = t;
            Mode m = Mode.parse(c.getString("mode", "LT"));
            e.mode = m == null ? Mode.LT : m;
            e.modifiers.addAll(Modifier.parseSet(String.join(",", c.getStringList("modifiers"))));
            e.squad = c.getInt("squad", 1);
            e.purgeCap = c.getInt("purge-cap", plugin.getConfig().getInt("host.default-cap", 100));
            String map = c.getString("map", "random");
            e.map = map == null || map.equalsIgnoreCase("random") ? null : map;
            e.enabled = c.getBoolean("enabled", true);
            entries.put(id, e);
        }
    }

    public void save() {
        YamlConfiguration c = yml.get();
        c.set("games", null);
        for (ScheduleEntry e : entries.values()) {
            String p = "games." + e.id + ".";
            c.set(p + "enabled", e.enabled);
            c.set(p + "days", ScheduleEntry.daysToString(e.days));
            c.set(p + "time", e.time.toString());
            c.set(p + "mode", e.mode.name());
            c.set(p + "squad", e.squad);
            c.set(p + "purge-cap", e.purgeCap);
            c.set(p + "modifiers", e.modifiers.stream().map(Enum::name).toList());
            c.set(p + "map", e.map == null ? "random" : e.map);
        }
        yml.save();
    }

    public Map<String, ScheduleEntry> entries() {
        return entries;
    }

    public ScheduleEntry add() {
        int n = 1;
        while (entries.containsKey("game" + n)) n++;
        ScheduleEntry e = new ScheduleEntry();
        e.id = "game" + n;
        e.purgeCap = plugin.getConfig().getInt("host.default-cap", 100);
        entries.put(e.id, e);
        return e;
    }

    public void remove(String id) {
        entries.remove(id);
    }

    public void tick(long now) {
        if (now - lastCheck < 15_000) return;
        lastCheck = now;
        ZoneId zone = plugin.zoneId();
        long ahead = plugin.getConfig().getInt("schedule.create-ahead-minutes", 60) * 60_000L;
        ZonedDateTime base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        for (ScheduleEntry e : entries.values()) {
            if (!e.enabled) continue;
            ZonedDateTime next = e.nextAfter(base, zone);
            if (next == null) continue;
            long at = next.toInstant().toEpochMilli();
            if (at - now > ahead) continue;
            String source = "schedule:" + e.id + ":" + at;
            if (tried.contains(source)) continue;
            boolean exists = plugin.games().queue().stream().anyMatch(g -> source.equals(g.source));
            if (exists) continue;
            if (!plugin.games().slotFree(at, -1)) continue;
            GameSettings s = new GameSettings();
            s.mode = e.mode;
            s.modifiers.addAll(e.modifiers);
            s.squad = e.squad;
            s.purgeCap = e.purgeCap;
            s.map = e.map;
            s.startAt = at;
            s.hosterName = plugin.getConfig().getString("schedule.hoster-name", "CartZone");
            tried.add(source);
            plugin.games().create(s, source);
        }
        dailyPost(now, zone);
    }

    /** The owner's "Game 1: 4:00 PM Duos" list, once a day at the configured time. */
    private void dailyPost(long now, ZoneId zone) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("webhooks.daily-post.enabled", true)) return;
        LocalTime at = ScheduleEntry.parseTime(cfg.getString("webhooks.daily-post.time", "12:00"));
        if (at == null) return;
        ZonedDateTime zNow = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        String today = zNow.toLocalDate().toString();
        if (today.equals(lastDailyPost) || zNow.toLocalTime().isBefore(at)) return;
        lastDailyPost = today;
        plugin.db().kvSet("daily-post.last", today);
        plugin.announcer().daily(gamesOn(zNow.toLocalDate(), zone), zone);
    }

    /** Every game on a date: queued ones plus schedule occurrences not yet created. */
    public List<GameRow> gamesOn(LocalDate day, ZoneId zone) {
        List<GameRow> out = new ArrayList<>();
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli(), to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (GameRow g : plugin.games().queue()) if (g.s.startAt >= from && g.s.startAt < to) out.add(g);
        for (ScheduleEntry e : entries.values()) {
            if (!e.enabled || !e.days.contains(day.getDayOfWeek())) continue;
            long at = ZonedDateTime.of(day, e.time, zone).toInstant().toEpochMilli();
            if (at < System.currentTimeMillis()) continue;
            boolean exists = out.stream().anyMatch(g -> Math.abs(g.s.startAt - at) < 60_000);
            if (exists) continue;
            GameSettings s = new GameSettings();
            s.mode = e.mode;
            s.squad = e.squad;
            s.startAt = at;
            out.add(new GameRow(0, s, "schedule:" + e.id));
        }
        out.sort((a, b) -> Long.compare(a.s.startAt, b.s.startAt));
        return out;
    }
}
