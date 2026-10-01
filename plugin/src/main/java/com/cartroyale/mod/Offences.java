package com.cartroyale.mod;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offence ladders from offences.yml: each type is a MUTE or a BAN whose length steps up with the
 * player's earlier offences of that type (undone ones don't count). Mutes are the plugin's own
 * (chat and private messages); bans go on the server's normal ban list, so /pardon still works.
 * Console can run it too, which is how an anticheat (Vulcan) hands out the cheating bans.
 */
public final class Offences implements Listener {

    public enum Action { MUTE, BAN }

    public record Type(String id, String name, Action action, List<Long> ladder, Material icon) {
        /** Length in ms for the n-th offence (0-based); -1 = permanent. */
        public long lengthFor(int priorCount) {
            if (ladder.isEmpty()) return -1;
            return ladder.get(Math.min(priorCount, ladder.size() - 1));
        }
    }

    public record Record(long id, UUID uuid, String name, String type, Action action, long lengthMs, long at, long until,
                         String by, String reason, boolean revoked) {
        public boolean active() {
            return !revoked && (until < 0 || until > System.currentTimeMillis());
        }
    }

    private final CartRoyale plugin;
    private final Yml yml;
    private final Map<String, Type> types = new LinkedHashMap<>();
    /** Active mute end per player (Long.MAX_VALUE = permanent). Read from the async chat thread. */
    private final Map<UUID, Long> muted = new ConcurrentHashMap<>();

    public Offences(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "offences.yml", true);
        load();
        for (Player p : Bukkit.getOnlinePlayers()) loadMute(p.getUniqueId());
    }

    public void reload() {
        yml.reload();
        load();
    }

    private void load() {
        types.clear();
        var sec = yml.get().getConfigurationSection("types");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            var t = sec.getConfigurationSection(id);
            if (t == null) continue;
            List<Long> ladder = new ArrayList<>();
            for (String step : t.getStringList("ladder")) ladder.add(parseLength(step));
            Action a = "BAN".equalsIgnoreCase(t.getString("action")) ? Action.BAN : Action.MUTE;
            Material icon = Material.matchMaterial(t.getString("icon", "PAPER"));
            types.put(id, new Type(id, t.getString("name", id), a, ladder, icon == null || !icon.isItem() ? Material.PAPER : icon));
        }
    }

    public List<Type> types() {
        return new ArrayList<>(types.values());
    }

    public Type type(String id) {
        return id == null ? null : types.get(id.toLowerCase(Locale.ROOT));
    }

    /** 30m, 12h, 3d, 2w, perm -> ms (-1 = permanent). */
    public static long parseLength(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("perm") || t.isEmpty()) return -1;
        long unit = switch (t.charAt(t.length() - 1)) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'w' -> 7 * 86_400_000L;
            default -> 86_400_000L;
        };
        String num = Character.isDigit(t.charAt(t.length() - 1)) ? t : t.substring(0, t.length() - 1);
        try {
            return Long.parseLong(num.trim()) * unit;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "3 days", "permanent", "12 hours". */
    public static String pretty(long ms) {
        if (ms < 0) return "permanent";
        long d = ms / 86_400_000L, h = ms / 3_600_000L, m = ms / 60_000L;
        if (d >= 1 && ms % 86_400_000L == 0) return d + (d == 1 ? " day" : " days");
        if (h >= 1 && ms % 3_600_000L == 0) return h + (h == 1 ? " hour" : " hours");
        return m + (m == 1 ? " minute" : " minutes");
    }

    // ------------------------------------------------------------------ queries

    public CompletableFuture<List<Record>> history(UUID u) {
        return plugin.db().submit(c -> {
            List<Record> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM offences WHERE uuid=? ORDER BY at DESC")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(row(rs));
                }
            }
            return out;
        });
    }

    private static Record row(ResultSet rs) throws java.sql.SQLException {
        return new Record(rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getString("type"),
            Action.valueOf(rs.getString("action")), rs.getLong("length_ms"), rs.getLong("at"), rs.getLong("until"),
            rs.getString("by_name"), rs.getString("reason"), rs.getInt("revoked") != 0);
    }

    /** Earlier offences of one type that still count (not undone). */
    public static int count(List<Record> history, String type) {
        int n = 0;
        for (Record r : history) if (!r.revoked() && r.type().equals(type)) n++;
        return n;
    }

    // ------------------------------------------------------------------ apply / undo

    /**
     * Gives {@code target} the next step of {@code type}. Runs the DB part off-thread and the
     * mute/ban on the main thread; {@code done} gets the record (or null if skipped as a duplicate).
     */
    public void apply(OfflinePlayer target, String targetName, Type type, String by, String reason, CommandSender feedback) {
        UUID u = target.getUniqueId();
        long now = System.currentTimeMillis();
        long dupMs = yml.get().getLong("duplicate-seconds", 60) * 1000L;
        plugin.db().submit(c -> {
            int prior = 0;
            long lastSame = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT at FROM offences WHERE uuid=? AND type=? AND revoked=0")) {
                ps.setString(1, u.toString());
                ps.setString(2, type.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        prior++;
                        lastSame = Math.max(lastSame, rs.getLong(1));
                    }
                }
            }
            if (now - lastSame < dupMs) return null;
            long len = type.lengthFor(prior);
            long until = len < 0 ? -1 : now + len;
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO offences(uuid,name,type,action,length_ms,at,until,by_name,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, u.toString());
                ps.setString(2, targetName);
                ps.setString(3, type.id());
                ps.setString(4, type.action().name());
                ps.setLong(5, len);
                ps.setLong(6, now);
                ps.setLong(7, until);
                ps.setString(8, by);
                ps.setString(9, reason);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    long id = k.next() ? k.getLong(1) : -1;
                    return new Record(id, u, targetName, type.id(), type.action(), len, now, until, by, reason, false);
                }
            }
        }).whenComplete((rec, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                if (feedback != null) plugin.msg().send(feedback, "offence.failed", "error", String.valueOf(err.getMessage()));
                return;
            }
            if (rec == null) {
                if (feedback != null) plugin.msg().send(feedback, "offence.duplicate", "player", targetName, "type", type.name());
                return;
            }
            enforce(rec, type);
            staff("offence.staff-applied", "player", targetName, "type", type.name(), "length", pretty(rec.lengthMs()),
                "action", type.action() == Action.MUTE ? "mute" : "ban", "by", by, "reason", reason == null ? "-" : reason, "id", rec.id());
            if (feedback instanceof org.bukkit.command.ConsoleCommandSender) {
                plugin.getLogger().info("Offence #" + rec.id() + ": " + targetName + " " + type.id() + " -> " + pretty(rec.lengthMs()) + " " + type.action() + " by " + by);
            }
        }));
    }

    private void enforce(Record r, Type type) {
        Player online = Bukkit.getPlayer(r.uuid());
        String until = r.until() < 0 ? "never" : date(r.until());
        if (r.action() == Action.MUTE) {
            muted.merge(r.uuid(), r.until() < 0 ? Long.MAX_VALUE : r.until(), Math::max);
            if (online != null) plugin.msg().send(online, "offence.muted", "type", type.name(), "length", pretty(r.lengthMs()), "until", until);
            return;
        }
        var bans = Bukkit.getBanList(BanListType.PROFILE);
        var profile = Bukkit.createProfile(r.uuid(), r.name());
        String reason = plugin.msg().plain("offence.ban-reason", "type", type.name(), "length", pretty(r.lengthMs()), "id", r.id());
        bans.addBan(profile, reason, r.until() < 0 ? null : new Date(r.until()), r.by());
        if (online != null) online.kick(plugin.msg().get("offence.ban-screen", "type", type.name(), "length", pretty(r.lengthMs()), "until", until, "id", r.id()));
    }

    /** "15 Oct 2026 19:08 UTC" in the server's configured time zone (config.yml timezone). */
    public String date(long ms) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH).withZone(plugin.zoneId())
            .format(java.time.Instant.ofEpochMilli(ms)) + " " + plugin.zoneId().getId();
    }

    /** Undoes one offence: it stops counting and, if it is still running, the mute/ban is lifted. */
    public void undo(long id, CommandSender by) {
        plugin.db().submit(c -> {
            Record r = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM offences WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) r = row(rs);
                }
            }
            if (r == null || r.revoked()) return r;
            try (PreparedStatement ps = c.prepareStatement("UPDATE offences SET revoked=1 WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return r;
        }).thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (r == null || r.revoked()) {
                plugin.msg().send(by, "offence.undo-missing", "id", id);
                return;
            }
            if (r.action() == Action.BAN) {
                Bukkit.getBanList(BanListType.PROFILE).pardon(Bukkit.createProfile(r.uuid(), r.name()));
            }
            loadMute(r.uuid());
            Type t = type(r.type());
            staff("offence.staff-undone", "id", id, "player", r.name(), "type", t == null ? r.type() : t.name(), "by", by.getName());
        }));
    }

    private void staff(String key, Object... kv) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("cartzone.offence")) plugin.msg().send(p, key, kv);
        plugin.getLogger().info(plugin.msg().plain(key, kv));
    }

    // ------------------------------------------------------------------ mutes

    public boolean isMuted(UUID u) {
        Long until = muted.get(u);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            muted.remove(u);
            return false;
        }
        return true;
    }

    public long mutedUntil(UUID u) {
        return muted.getOrDefault(u, 0L);
    }

    /** Re-reads a player's active mute from the database (join, undo). */
    public void loadMute(UUID u) {
        long now = System.currentTimeMillis();
        plugin.db().submit(c -> {
            long best = 0;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT until FROM offences WHERE uuid=? AND action='MUTE' AND revoked=0 AND (until<0 OR until>?)")) {
                ps.setString(1, u.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long t = rs.getLong(1);
                        best = Math.max(best, t < 0 ? Long.MAX_VALUE : t);
                    }
                }
            }
            return best;
        }).thenAccept(best -> {
            if (best > 0) muted.put(u, best);
            else muted.remove(u);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        loadMute(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        muted.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!isMuted(p.getUniqueId())) return;
        e.setCancelled(true);
        plugin.msg().send(p, "offence.still-muted", "left", left(mutedUntil(p.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isMuted(e.getPlayer().getUniqueId())) return;
        String msg = e.getMessage().toLowerCase(Locale.ROOT).substring(1);
        for (String blocked : yml.get().getStringList("muted-blocked-commands")) {
            String b = blocked.toLowerCase(Locale.ROOT);
            if (msg.equals(b) || msg.startsWith(b + " ") || msg.contains(":" + b + " ")) {
                e.setCancelled(true);
                plugin.msg().send(e.getPlayer(), "offence.still-muted", "left", left(mutedUntil(e.getPlayer().getUniqueId())));
                return;
            }
        }
    }

    private static String left(long until) {
        if (until == Long.MAX_VALUE) return "permanently";
        long ms = until - System.currentTimeMillis();
        long d = ms / 86_400_000L, h = (ms / 3_600_000L) % 24, m = (ms / 60_000L) % 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : Math.max(1, m) + "m";
    }
}
