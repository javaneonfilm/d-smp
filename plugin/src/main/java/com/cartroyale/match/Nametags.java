package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Match;
import com.cartroyale.rank.Ranks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One scoreboard team per player on the main scoreboard carries the rank badge (prefix), the
 * equipped suffix with the team number in team games, and the rank's name colour, so
 * every name shows the same thing in chat, the tab list and above the head. When the TAB plugin
 * is installed it draws the tab list and nametags instead, from the cartzone_* placeholders.
 */
public final class Nametags {


    private final CartRoyale plugin;
    private final Map<UUID, Integer> matchTeam = new HashMap<>();

    public Nametags(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private static String teamName(UUID u) {
        return "cr" + u.toString().replace("-", "").substring(0, 14);
    }

    /** A rank from config.yml rank-tags: the badge glyph and the name colour that goes with it. */
    public record RankTag(String id, String tag, TextColor color) {
    }

    private static final RankTag NONE = new RankTag("default", "", null);

    /** The first entry of config.yml `rank-tags` whose permission the player has (top = highest rank). */
    public RankTag rank(Player p) {
        for (java.util.Map<?, ?> m : plugin.getConfig().getMapList("rank-tags")) {
            Object perm = m.get("permission");
            if (perm == null || !Ranks.has(p, String.valueOf(perm))) continue;
            Object id = m.get("id"), tag = m.get("tag"), col = m.get("name-color");
            TextColor c = col == null ? null : TextColor.fromHexString(String.valueOf(col));
            return new RankTag(id == null ? String.valueOf(perm) : String.valueOf(id), tag == null ? "" : String.valueOf(tag), c);
        }
        return NONE;
    }

    public Component tag(Player p) {
        String t = rank(p).tag();
        return t.isEmpty() ? Component.empty() : plugin.msg().parse(t);
    }

    /** Name colour: the rank's colour, otherwise white. Teams show as a number instead (teamTag). */
    public TextColor color(Player p) {
        TextColor c = rank(p).color();
        return c == null ? NamedTextColor.WHITE : c;
    }

    public TextColor color(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p != null ? color(p) : NamedTextColor.WHITE;
    }

    /** In a team game, " [13]" after the name: the player's team number. Empty otherwise. */
    public Component teamTag(UUID u) {
        Integer t = matchTeam.get(u);
        return t == null ? Component.empty() : Component.text(" [" + (t + 1) + "]", TextColor.color(0xA9AEB8));
    }

    /** The TAB plugin draws the tab list and nametags itself (from our PlaceholderAPI placeholders). */
    public static boolean tabPlugin(CartRoyale plugin) {
        String mode = plugin.getConfig().getString("tab-plugin", "auto");
        if ("off".equalsIgnoreCase(mode)) return false;
        return "on".equalsIgnoreCase(mode) || Bukkit.getPluginManager().isPluginEnabled("TAB");
    }

    /** Rebuilds a player's tag, colour and suffix in the tab list and above the head. */
    public void refresh(Player p) {
        if (tabPlugin(plugin)) {
            remove(p);
            return;
        }
        Component prefix = tag(p);
        Component pre = prefix.equals(Component.empty()) ? Component.empty() : prefix.append(Component.space());
        Component suffix = plugin.cosmetics().suffixComponent(p.getUniqueId());
        Component suf = teamTag(p.getUniqueId()).append(suffix == null ? Component.empty() : Component.space().append(suffix));
        TextColor c = color(p);
        // Every player has their own scoreboard (Sidebar), so the team goes on all of them.
        for (Scoreboard b : boards()) apply(b, p.getName(), teamName(p.getUniqueId()), pre, suf, c);
        Component list = Component.empty().append(pre).append(Component.text(p.getName(), c)).append(suf);
        p.playerListName(list);
    }

    private static void apply(Scoreboard b, String player, String name, Component pre, Component suf, TextColor c) {
        Team t = b.getTeam(name);
        if (t == null) t = b.registerNewTeam(name);
        if (!t.hasEntry(player)) t.addEntry(player);
        t.prefix(pre);
        t.suffix(suf);
        // Names above the head can only take the 16 team colours; the tab list and chat get the exact one.
        t.color(NamedTextColor.nearestTo(c));
        t.setAllowFriendlyFire(true);
    }

    private java.util.List<Scoreboard> boards() {
        java.util.List<Scoreboard> all = new java.util.ArrayList<>(plugin.sidebar().all());
        all.add(board());
        return all;
    }

    /** A new player board gets everyone's current nametag team. */
    public void copyTo(Scoreboard target) {
        if (tabPlugin(plugin)) return;
        for (Team t : board().getTeams()) {
            if (!t.getName().startsWith("cr")) continue;
            for (String entry : t.getEntries()) {
                Player p = Bukkit.getPlayerExact(entry);
                apply(target, entry, t.getName(), t.prefix(), t.suffix(), p == null ? NamedTextColor.WHITE : color(p));
            }
        }
    }

    public void remove(Player p) {
        for (Scoreboard b : boards()) {
            Team t = b.getTeam(teamName(p.getUniqueId()));
            if (t != null) t.unregister();
        }
    }

    public void applyMatch(Match m) {
        matchTeam.clear();
        // Numbers only mean something with squads; in solos everyone would just get their own.
        if (m.s().squad > 1) for (Match.MP mp : m.players.values()) if (mp.team >= 0) matchTeam.put(mp.uuid, mp.team);
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void clearMatch() {
        matchTeam.clear();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }
}
