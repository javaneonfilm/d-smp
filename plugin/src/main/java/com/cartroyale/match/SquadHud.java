package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Match;
import com.cartroyale.lobby.HudText;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The squad cards in the top-left corner during a team game: head, name, kills and a health bar
 * for everyone in your squad. Each card is an invisible PINK boss bar whose title the pack's text
 * shader (cz_hud.glsl) moves from the top centre to the left edge. The shader only touches text
 * whose colour carries the marker from {@link #mark}, so every colour on a card goes through it.
 * Players without the pack keep the squad on the sidebar instead.
 */
public final class SquadHud {

    private static final int MAX_CARDS = 4;

    private final CartRoyale plugin;
    private final Map<UUID, List<BossBar>> cards = new HashMap<>();
    /** The other boss bars the player had when their cards were last (re)added. */
    private final Map<UUID, Set<BossBar>> othersWhenShown = new HashMap<>();
    /** Skin textures seen while a player was online, so their head still shows after they leave. */
    private final Map<UUID, List<PlayerHeadObjectContents.ProfileProperty>> skins = new HashMap<>();

    public SquadHud(CartRoyale plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 4);
    }

    /**
     * Puts the HUD marker into a colour: red % 8 = 5 and green % 8 = 3 say "move me", blue % 8
     * carries the rows to lift (boss bars above the cards) and whether this is the head.
     * Changes each channel by at most 7 of 255, which nobody can see.
     */
    static int mark(int rgb, int above, boolean head) {
        int r = (rgb >> 16 & 0xF8) | 5, g = (rgb >> 8 & 0xF8) | 3, b = (rgb & 0xF8) | (Math.min(above, 3) * 2 + (head ? 1 : 0));
        return r << 16 | g << 8 | b;
    }

    static TextColor marked(int rgb, int above, boolean head) {
        return TextColor.color(mark(rgb, above, head));
    }

    /** True when this player sees the squad as cards rather than on the sidebar. */
    public boolean active(Player p) {
        return cards.containsKey(p.getUniqueId());
    }

    private boolean wanted(Player p) {
        return plugin.getConfig().getBoolean("squad-hud.enabled", true) && plugin.presence().hasPack(p.getUniqueId());
    }

    private void tick() {
        Match m = plugin.games().match();
        boolean inGame = m != null && m.state.inArena();
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            var props = p.getPlayerProfile().getProperties();
            for (var prop : props) {
                if (prop.getName().equals("textures")) {
                    skins.put(p.getUniqueId(), List.of(PlayerHeadObjectContents.property(prop.getName(), prop.getValue(), prop.getSignature())));
                }
            }
            List<UUID> team = inGame ? teamOf(m, p.getUniqueId()) : null;
            if (team == null || team.size() < 2 || !wanted(p)) hide(p);
            else show(p, m, team);
        }
        cards.keySet().retainAll(online);
        othersWhenShown.keySet().retainAll(online);
        if (!inGame) skins.keySet().retainAll(online);
    }

    private static List<UUID> teamOf(Match m, UUID u) {
        for (List<UUID> t : m.teams) if (t.contains(u)) return t;
        return null;
    }

    private void hide(Player p) {
        List<BossBar> mine = cards.remove(p.getUniqueId());
        othersWhenShown.remove(p.getUniqueId());
        if (mine != null) for (BossBar b : mine) p.hideBossBar(b);
    }

    private void show(Player p, Match m, List<UUID> team) {
        List<BossBar> mine = cards.computeIfAbsent(p.getUniqueId(), u -> new ArrayList<>());
        int n = Math.min(MAX_CARDS, team.size());
        while (mine.size() < n) mine.add(BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS));
        while (mine.size() > n) p.hideBossBar(mine.remove(mine.size() - 1));

        // The cards are lifted past every other boss bar (IP bar, zone bar), so those must come
        // first on the client. Whenever a bar shows up that wasn't there when the cards were
        // added, the cards are taken off and added again so they end up after it.
        Set<BossBar> own = new HashSet<>(mine);
        Set<BossBar> others = new HashSet<>();
        for (BossBar b : p.activeBossBars()) if (!own.contains(b)) others.add(b);
        Set<BossBar> before = othersWhenShown.get(p.getUniqueId());
        if (before != null && !before.containsAll(others)) for (BossBar b : mine) p.hideBossBar(b);
        // Bars that went away leave the order alone, so only what is there now counts next time.
        othersWhenShown.put(p.getUniqueId(), others);
        int above = Math.min(others.size(), 3);

        for (int i = 0; i < n; i++) mine.get(i).name(card(m, team.get(i), above));
        for (BossBar b : mine) p.showBossBar(b);
    }

    private Component card(Match m, UUID u, int above) {
        Match.MP mp = m.get(u);
        Player tp = Bukkit.getPlayer(u);
        String name = mp != null ? mp.name : tp != null ? tp.getName() : "?";
        boolean alive = mp != null && mp.alive;
        int kills = mp == null ? 0 : mp.kills;

        int nameRgb;
        int hpOn = 0, hpRgb = 0x55FF55;
        if (!alive) {
            nameRgb = 0xFF3333;
        } else if (tp == null) {
            nameRgb = 0x777777;
        } else {
            TextColor rc = plugin.nametags().rank(tp).color();
            nameRgb = rc == null ? 0xFFFFFF : rc.value();
            var attr = tp.getAttribute(Attribute.MAX_HEALTH);
            double max = attr == null ? 20 : attr.getValue();
            double f = Math.max(0, Math.min(1, tp.getHealth() / Math.max(1, max)));
            hpOn = tp.getHealth() > 0 ? Math.max(1, (int) Math.ceil(f * HudText.HP_SEGMENTS)) : 0;
            hpRgb = f > 0.75 ? 0x55FF55 : f > 0.5 ? 0xFFEE44 : f > 0.25 ? 0xFF9922 : 0xFF3333;
        }

        PlayerHeadObjectContents.Builder hb = ObjectContents.playerHead().name(name);
        List<PlayerHeadObjectContents.ProfileProperty> skin = skins.get(u);
        if (skin != null) hb.id(u).profileProperties(skin);
        // Dead or gone: the head is darkened by its colour.
        Component head = Component.object(hb.build())
            .color(marked(alive && tp != null ? 0xFFFFFF : 0x666666, above, true))
            .shadowColor(ShadowColor.none());

        ShadowColor textShadow = ShadowColor.shadowColor(0xFF000000 | mark(0x202020, above, false));
        return HudText.squadCard(head, name, marked(nameRgb, above, false), !alive, textShadow,
            marked(0xBBBBBB, above, false), kills, hpOn, marked(hpRgb, above, false), marked(0xFFFFFF, above, false));
    }
}
