package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import com.cartroyale.game.Match;
import com.cartroyale.gui.Gui;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DEATH-1..3. Dead players become spectators inside the arena, can't leave its bounds or teleport
 * out, follow players through /spectate (spectator mode can't use hotbar items, see
 * docs/DEVIATIONS.md), and their chat only reaches other spectators.
 */
public final class Spectators implements Listener {

    private final CartRoyale plugin;

    public Spectators(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public boolean isWatcher(Player p) {
        return plugin.arenas().isArena(p.getWorld()) && p.getGameMode() == GameMode.SPECTATOR;
    }

    public List<Player> watchers() {
        World w = plugin.arenas().world();
        List<Player> out = new ArrayList<>();
        if (w == null) return out;
        for (Player p : w.getPlayers()) if (p.getGameMode() == GameMode.SPECTATOR) out.add(p);
        return out;
    }

    /** Puts a player into spectator mode at a location inside the arena. */
    public void make(Player p, Location at) {
        World w = plugin.arenas().world();
        MapConfig mc = plugin.arenas().map();
        if (w == null || mc == null) return;
        Location l = at != null && plugin.arenas().isArena(at.getWorld()) ? clamp(at, mc) : mc.spawn(w);
        p.setGameMode(GameMode.SPECTATOR);
        if (!plugin.arenas().isArena(p.getWorld()) || p.getLocation().distanceSquared(l) > 4) p.teleport(l);
        plugin.zone().hide(p);
        plugin.msg().send(p, "spectate.now");
    }

    private static Location clamp(Location l, MapConfig mc) {
        Location c = l.clone();
        double r = mc.borderRadius - 1;
        c.setX(Math.max(mc.centerX - r, Math.min(mc.centerX + r, c.getX())));
        c.setZ(Math.max(mc.centerZ - r, Math.min(mc.centerZ + r, c.getZ())));
        if (c.getY() < c.getWorld().getMinHeight() + 2) c.setY(c.getWorld().getHighestBlockYAt(c) + 2);
        return c;
    }

    /** DEATH-2: a player picker. Clicking a head spectates that player. */
    public void openPicker(Player p) {
        Match m = plugin.games().match();
        if (m == null || !isWatcher(p)) {
            plugin.msg().send(p, "spectate.not-spectating");
            return;
        }
        Gui gui = new Gui(6, plugin.msg().get("gui.spectate.title"));
        int slot = 0;
        for (Match.MP mp : m.players.values()) {
            if (!mp.alive || slot >= 54) continue;
            Player t = Bukkit.getPlayer(mp.uuid);
            if (t == null) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, sm -> {
                sm.setOwningPlayer(t);
                sm.displayName(plugin.msg().item("gui.spectate.head", "player", t.getName()));
                sm.lore(plugin.msg().lore("gui.spectate.lore", "kills", mp.kills, "health", (int) Math.ceil(t.getHealth())));
            });
            gui.set(slot++, head, e -> {
                p.closeInventory();
                if (t.isOnline() && plugin.arenas().isArena(t.getWorld())) {
                    p.teleport(t);
                    p.setSpectatorTarget(t);
                }
            });
        }
        gui.open(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isWatcher(p) || plugin.rank().isAdminFree(p)) return;
        MapConfig mc = plugin.arenas().map();
        Location to = e.getTo();
        if (mc != null && !mc.insideBorder(to.getX(), to.getZ(), 0)) e.setTo(clamp(to, mc));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!isWatcher(p) || plugin.rank().isAdminFree(p)) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        MapConfig mc = plugin.arenas().map();
        Location to = e.getTo();
        if (!plugin.arenas().isArena(to.getWorld()) || mc == null || !mc.insideBorder(to.getX(), to.getZ(), 0)) {
            e.setCancelled(true);
            plugin.msg().actionbar(p, "spectate.cant-leave");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpectateTarget(PlayerStartSpectatingEntityEvent e) {
        if (!isWatcher(e.getPlayer()) || plugin.rank().isAdminFree(e.getPlayer())) return;
        if (!plugin.arenas().isArena(e.getNewSpectatorTarget().getWorld())) e.setCancelled(true);
    }

    /** DEATH-3: spectator chat only reaches spectators (and admins). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.games().match();
        if (m == null || !isWatcher(p)) return;
        e.viewers().removeIf(v -> v instanceof Player vp && !isWatcher(vp) && !vp.hasPermission("cartzone.admin"));
    }

    public boolean isDeadInMatch(UUID u) {
        Match m = plugin.games().match();
        return m != null && m.get(u) != null && !m.isAlive(u);
    }
}
