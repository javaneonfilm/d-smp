package com.cartroyale.hooks;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameRow;
import com.cartroyale.game.GameSettings;
import com.cartroyale.game.Modifier;
import com.cartroyale.util.Times;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** HOOK-1..4 plus the daily "today's games" post. All wording comes from messages.yml (discord.*). */
public final class Announcer {

    private static final int RED = 0xFF0000;

    private final CartRoyale plugin;

    public Announcer(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private String ip() {
        return plugin.getConfig().getString("server-ip", "play.cartroyale.com");
    }

    private String title(long id) {
        String flag = plugin.getConfig().getString("webhooks.flag", "").trim();
        String t = plugin.msg().plain("discord.title", "id", id, "region", plugin.getConfig().getString("region", "EU"));
        return flag.isEmpty() ? t : flag + " " + t;
    }

    public String teamSize(int squad) {
        return plugin.msg().plain("team-size." + squad);
    }

    public String modeName(GameSettings s) {
        return plugin.msg().plain("modes." + s.mode.name().toLowerCase() + ".name");
    }

    public String modifiers(GameSettings s) {
        if (s.modifiers.isEmpty()) return plugin.msg().plain("discord.no-modifiers");
        List<String> tags = new ArrayList<>();
        for (Modifier m : Modifier.values()) {
            if (s.has(m)) tags.add("`" + plugin.msg().plain("modifiers." + m.name().toLowerCase() + ".name") + "`");
        }
        return String.join(" ", tags);
    }

    public int durationMinutes() {
        return (int) Math.ceil((plugin.zone().plannedSeconds() + plugin.getConfig().getInt("deploy.planned-seconds", 60)) / 60.0);
    }

    /** HOOK-1: pings the events role. */
    public void hosted(GameRow g) {
        GameSettings s = g.s;
        String map = s.map == null ? plugin.msg().plain("discord.random-map") : s.map;
        String desc = plugin.msg().plain("discord.hosted",
            "map", map, "team_size", teamSize(s.squad), "duration", durationMinutes(), "cap", s.purgeCap,
            "mode", modeName(s), "modifiers", modifiers(s), "start_unix", s.startAt / 1000, "ip", ip());
        plugin.hooks().send(new Webhooks.Embed(title(g.id), desc, RED, plugin.msg().plain("discord.hosted-by", "hoster", s.hosterName)), true);
    }

    /** HOOK-2: pings the events role. */
    public void warning(GameRow g) {
        String desc = plugin.msg().plain("discord.warning", "ip", ip(), "start_unix", g.s.startAt / 1000);
        plugin.hooks().send(new Webhooks.Embed(plugin.msg().plain("discord.warning-title"), desc, RED,
            plugin.msg().plain("discord.hosted-by", "hoster", g.s.hosterName)), true);
    }

    /** HOOK-3: no ping. */
    public void purge(GameRow g, int online) {
        String desc = plugin.msg().plain("discord.purge", "online", online);
        plugin.hooks().send(new Webhooks.Embed(plugin.msg().plain("discord.purge-title"), desc, RED,
            plugin.msg().plain("discord.hosted-by", "hoster", g.s.hosterName)), false);
    }

    /** HOOK-4: no ping. */
    public void results(GameRow g, List<String> winners, int winnerKills, String mvp, int mvpKills) {
        StringBuilder w = new StringBuilder();
        for (String n : winners) w.append('`').append(n).append("` ");
        String desc = plugin.msg().plain(winners.isEmpty() ? "discord.results-none" : "discord.results",
            "winners", w.toString().trim(), "winner_kills", winnerKills, "mvp", mvp == null ? "-" : mvp, "mvp_kills", mvpKills);
        plugin.hooks().send(new Webhooks.Embed(plugin.msg().plain("discord.results-title", "id", g.id), desc, RED, null), false);
    }

    /** The owner's daily post: "Game 1: 16:00 Duos". Times use Discord timestamps so each reader sees local time. */
    public void daily(List<GameRow> today, ZoneId zone) {
        if (today.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        int n = 1;
        for (GameRow g : today) {
            String emoji = plugin.getConfig().getString("webhooks.daily-post.emoji." + g.s.squad, "");
            lines.add(plugin.msg().plain("discord.daily-line", "n", n++, "start_unix", g.s.startAt / 1000,
                "team_size", teamSize(g.s.squad), "mode", modeName(g.s), "emoji", emoji == null ? "" : emoji).stripTrailing());
        }
        String date = LocalDate.ofInstant(Instant.now(), zone).toString();
        plugin.hooks().send(new Webhooks.Embed(plugin.msg().plain("discord.daily-title", "date", date), String.join("\n", lines), RED,
            plugin.msg().plain("discord.daily-footer", "ip", ip())), plugin.getConfig().getBoolean("webhooks.daily-post.ping", false));
    }

    public String startLine(GameRow g) {
        ZoneId zone = plugin.zoneId();
        return Times.clock(g.s.startAt, zone) + " " + zone.getId() + " (" + Times.relative(g.s.startAt) + ")";
    }
}
