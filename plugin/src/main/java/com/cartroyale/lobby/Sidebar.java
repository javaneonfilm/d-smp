package com.cartroyale.lobby;

import com.cartroyale.CartRoyale;
import com.cartroyale.db.Store;
import com.cartroyale.game.GameRow;
import com.cartroyale.game.Match;
import com.cartroyale.util.Times;
import com.cartroyale.zone.ZoneMath;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LOBBY-4. Every player has their own scoreboard so the sidebar can show their own numbers and,
 * in a team game, their squad with health colours. Nametag teams are mirrored onto every board by
 * {@link com.cartroyale.match.Nametags}, so names above heads look the same for everyone.
 */
public final class Sidebar {

    private final CartRoyale plugin;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, Store.Profile> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Long> statsAt = new ConcurrentHashMap<>();

    public Sidebar(CartRoyale plugin) {
        this.plugin = plugin;
    }

    /** The player's own board (made on first use). */
    public Scoreboard board(Player p) {
        return boards.computeIfAbsent(p.getUniqueId(), u -> {
            Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
            plugin.nametags().copyTo(b);
            return b;
        });
    }

    public Collection<Scoreboard> all() {
        return boards.values();
    }

    public void join(Player p) {
        p.setScoreboard(board(p));
        update(p);
    }

    public void quit(Player p) {
        boards.remove(p.getUniqueId());
        stats.remove(p.getUniqueId());
        statsAt.remove(p.getUniqueId());
    }

    public void updateAll() {
        boolean on = plugin.getConfig().getBoolean("sidebar.enabled", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (on) update(p);
            else {
                Objective o = board(p).getObjective("cz_side");
                if (o != null) o.unregister();
            }
        }
    }

    private void update(Player p) {
        Scoreboard b = board(p);
        if (p.getScoreboard() != b) p.setScoreboard(b);
        Objective o = b.getObjective("cz_side");
        if (o == null) {
            o = b.registerNewObjective("cz_side", Criteria.DUMMY, plugin.msg().get("board.title"));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        }
        refreshStats(p);
        List<Component> lines = lines(p);
        // Entries are only keys; the client shows each score's custom name.
        for (String entry : b.getEntries()) {
            if (entry.startsWith("czl") && Integer.parseInt(entry.substring(3)) >= lines.size()) b.resetScores(entry);
        }
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            var sc = o.getScore("czl" + i);
            sc.setScore(score--);
            sc.customName(lines.get(i));
        }
    }

    private void refreshStats(Player p) {
        long now = System.currentTimeMillis();
        if (now - statsAt.getOrDefault(p.getUniqueId(), 0L) < 30_000) return;
        statsAt.put(p.getUniqueId(), now);
        plugin.store().profile(p.getUniqueId()).thenAccept(pr -> {
            if (pr != null) stats.put(p.getUniqueId(), pr);
        });
    }

    private List<Component> lines(Player p) {
        Match m = plugin.games().match();
        boolean inGame = m != null && m.state.inArena();
        Store.Profile st = stats.getOrDefault(p.getUniqueId(), new Store.Profile(0, 0, 0, 0));
        String ip = plugin.getConfig().getString("server-ip", "cartzone.vip");
        List<Component> out = new ArrayList<>();
        if (!inGame) {
            GameRow next = plugin.games().next();
            for (String line : plugin.msg().rawList("board.lobby")) {
                out.add(plugin.msg().parse(line, "online", Bukkit.getOnlinePlayers().size(), "wins", st.wins(), "kills", st.kills(),
                    "coins", st.coins(), "ip", ip,
                    "next", next == null ? plugin.msg().plain("board.no-game") : "#" + next.id + "  " + Times.clock(next.s.startAt, plugin.zoneId()),
                    "next_rel", next == null ? "" : Times.relative(next.s.startAt)));
            }
            return out;
        }
        Match.MP me = m.get(p.getUniqueId());
        for (String line : plugin.msg().rawList("board.game")) {
            if (line.trim().equals("<squad>")) {
                // Pack players see their squad as cards in the top-left corner (SquadHud).
                if (plugin.squadHud() == null || !plugin.squadHud().active(p)) out.addAll(squad(m, p));
                continue;
            }
            out.add(plugin.msg().parse(line, "id", m.id(), "phase", plugin.msg().plain("phases." + m.state.name().toLowerCase()),
                "alive", m.aliveCount(), "kills", me == null ? 0 : me.kills, "zone", zoneLine(), "ip", ip));
        }
        return out;
    }

    private String zoneLine() {
        var z = plugin.zone();
        if (!z.running()) return plugin.msg().plain("board.zone-off");
        if (z.inGrace()) return plugin.msg().plain("board.zone-grace", "time", Times.mmss(z.graceSecondsLeft()));
        ZoneMath.State s = z.now();
        if (s == null) return plugin.msg().plain("board.zone-off");
        int secs = (int) Math.ceil(s.secondsLeftInStep());
        if (s.shrinking()) return plugin.msg().plain("board.zone-shrinking", "time", Times.mmss(secs));
        if (secs <= 0) return plugin.msg().plain("board.zone-closed");
        return plugin.msg().plain("board.zone-waiting", "time", Times.mmss(secs));
    }

    /** The viewer's squad (team games only): name coloured by health, struck through when dead. */
    private List<Component> squad(Match m, Player viewer) {
        List<UUID> team = null;
        for (List<UUID> t : m.teams) if (t.contains(viewer.getUniqueId())) team = t;
        if (team == null || team.size() < 2) return List.of();
        List<Component> out = new ArrayList<>();
        out.add(plugin.msg().get("board.squad-title"));
        for (UUID u : team) {
            Match.MP mp = m.get(u);
            if (mp == null) continue;
            Player tp = Bukkit.getPlayer(u);
            Component name;
            if (!mp.alive) {
                name = Component.text(mp.name, NamedTextColor.RED).decorate(TextDecoration.STRIKETHROUGH);
            } else if (tp == null) {
                name = Component.text(mp.name, NamedTextColor.DARK_GRAY);
            } else {
                double max = tp.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : tp.getAttribute(Attribute.MAX_HEALTH).getValue();
                double f = tp.getHealth() / Math.max(1, max);
                TextColor c = f > 0.75 ? TextColor.color(0x55FF55) : f > 0.5 ? TextColor.color(0xFFEE44)
                    : f > 0.25 ? TextColor.color(0xFF9922) : TextColor.color(0xFF3333);
                name = Component.text(mp.name, c).append(Component.text(" " + (int) Math.ceil(tp.getHealth()), NamedTextColor.DARK_GRAY));
            }
            out.add(Component.text(" ").append(name));
        }
        return out;
    }
}
