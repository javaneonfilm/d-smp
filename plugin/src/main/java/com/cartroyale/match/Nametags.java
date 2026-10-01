package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Match;
import com.cartroyale.rank.Ranks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One scoreboard team per player on the main scoreboard carries the rank tag (prefix), the
 * equipped suffix, and in a match the team colour (TEAM-2), so every name shows the same thing
 * in chat, the tab list and above the head.
 */
public final class Nametags {

    private static final NamedTextColor[] COLORS = {
        NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.GOLD, NamedTextColor.AQUA,
        NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_PURPLE, NamedTextColor.DARK_RED, NamedTextColor.DARK_BLUE, NamedTextColor.GRAY, NamedTextColor.WHITE};

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

    /** The first entry of config.yml `tags` whose permission the player has (top = highest rank). */
    public Component tag(Player p) {
        for (java.util.Map<?, ?> m : plugin.getConfig().getMapList("tags")) {
            Object perm = m.get("permission"), tag = m.get("tag");
            if (perm == null || tag == null) continue;
            if (Ranks.has(p, String.valueOf(perm))) return plugin.msg().parse(String.valueOf(tag));
        }
        return Component.empty();
    }

    public NamedTextColor color(UUID u) {
        Integer t = matchTeam.get(u);
        return t == null ? NamedTextColor.WHITE : COLORS[t % COLORS.length];
    }

    /** Rebuilds a player's tag, suffix and colour everywhere. */
    public void refresh(Player p) {
        Scoreboard b = board();
        String name = teamName(p.getUniqueId());
        Team t = b.getTeam(name);
        if (t == null) t = b.registerNewTeam(name);
        if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
        Component prefix = tag(p);
        t.prefix(prefix.equals(Component.empty()) ? Component.empty() : prefix.append(Component.space()));
        Component suffix = plugin.cosmetics().suffixComponent(p.getUniqueId());
        t.suffix(suffix == null ? Component.empty() : Component.space().append(suffix));
        t.color(color(p.getUniqueId()));
        t.setAllowFriendlyFire(true);
        Component list = Component.empty().append(t.prefix()).append(Component.text(p.getName(), color(p.getUniqueId()))).append(t.suffix());
        p.playerListName(list);
    }

    public void remove(Player p) {
        Team t = board().getTeam(teamName(p.getUniqueId()));
        if (t != null) t.unregister();
    }

    public void applyMatch(Match m) {
        matchTeam.clear();
        for (Match.MP mp : m.players.values()) if (mp.team >= 0) matchTeam.put(mp.uuid, mp.team);
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void clearMatch() {
        matchTeam.clear();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }
}
