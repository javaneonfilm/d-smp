package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameState;
import com.cartroyale.game.Match;
import com.cartroyale.util.Items;
import com.cartroyale.util.Keys;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-match player rules: kill credit (including TNT carts, WORLD-4), friendly fire (TEAM-2),
 * unbreakable armor that vanishes on death (KIT-4), death to spectator (DEATH-1) and
 * disconnects counting as eliminations.
 */
public final class MatchListener implements Listener {

    private final CartRoyale plugin;
    private final Map<UUID, Location> deathSpot = new HashMap<>();

    public MatchListener(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private Match live() {
        Match m = plugin.games().match();
        if (m == null) return null;
        GameState s = m.state;
        return s == GameState.DEPLOY || s == GameState.PLAYING || s == GameState.BOARDING ? m : null;
    }

    /** Resolves who is responsible for a damaging entity: a player, a projectile's shooter, a cart's owner. */
    public static UUID responsible(Entity damager) {
        if (damager instanceof Player p) return p.getUniqueId();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p.getUniqueId();
        if (damager instanceof ExplosiveMinecart || damager instanceof TNTPrimed) {
            String s = damager.getPersistentDataContainer().get(Keys.CART_OWNER, PersistentDataType.STRING);
            if (s != null) return UUID.fromString(s);
            if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p.getUniqueId();
        }
        return null;
    }

    // ---------------- cart ownership (WORLD-4) ----------------

    @EventHandler(ignoreCancelled = true)
    public void onPlaceCart(EntityPlaceEvent e) {
        if (e.getEntity() instanceof ExplosiveMinecart && e.getPlayer() != null) {
            e.getEntity().getPersistentDataContainer().set(Keys.CART_OWNER, PersistentDataType.STRING, e.getPlayer().getUniqueId().toString());
        }
    }

    /** Whoever hits a TNT cart (and so ignites it) becomes its owner. */
    @EventHandler(ignoreCancelled = true)
    public void onHitCart(VehicleDamageEvent e) {
        if (!(e.getVehicle() instanceof ExplosiveMinecart cart) || e.getAttacker() == null) return;
        UUID who = responsible(e.getAttacker());
        if (who != null) cart.getPersistentDataContainer().set(Keys.CART_OWNER, PersistentDataType.STRING, who.toString());
    }

    // ---------------- damage ----------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Match m = live();
        if (m == null || !m.isAlive(victim.getUniqueId())) return;
        UUID attacker = responsible(e.getDamager());
        if (attacker == null || attacker.equals(victim.getUniqueId())) return;
        if (m.state != GameState.PLAYING) {
            // No fighting while people are still on the ghasts or gliding in.
            if (!plugin.getConfig().getBoolean("match.pvp-during-deploy", false)) {
                e.setCancelled(true);
                return;
            }
        }
        if (m.sameTeam(attacker, victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        Match.MP mp = m.get(victim.getUniqueId());
        mp.lastDamager = attacker;
        mp.lastDamageAt = System.currentTimeMillis();
    }

    /** KIT-4: armor never loses durability in a match. */
    @EventHandler(ignoreCancelled = true)
    public void onArmorWear(PlayerItemDamageEvent e) {
        if (plugin.arenas().isArena(e.getPlayer().getWorld()) && Items.isArmor(e.getItem())) e.setCancelled(true);
    }

    // ---------------- death ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!plugin.arenas().isArena(p.getWorld())) return;
        // KIT-4 / D7: armor vanishes, the deploy elytra never drops, everything else drops.
        e.getDrops().removeIf(it -> Items.isArmor(it) || Items.hasFlag(it, Keys.TEMP_ELYTRA));
        e.setKeepInventory(false);
        e.setDroppedExp(0);
        e.deathMessage(null);
        deathSpot.put(p.getUniqueId(), p.getLocation());
        Match m = live();
        if (m == null || !m.isAlive(p.getUniqueId())) return;
        plugin.deploy().release(p);
        UUID killer = null;
        var last = p.getLastDamageCause();
        boolean zone = last != null && last.getDamageSource().getDamageType() == DamageType.OUTSIDE_BORDER;
        if (!zone) {
            if (p.getKiller() != null) killer = p.getKiller().getUniqueId();
            if (last instanceof EntityDamageByEntityEvent be) {
                UUID r = responsible(be.getDamager());
                if (r != null) killer = r;
            }
        }
        Match.MP mp = m.get(p.getUniqueId());
        long credit = plugin.getConfig().getInt("match.kill-credit-seconds", 15) * 1000L;
        if (killer == null && !zone && mp.lastDamager != null && System.currentTimeMillis() - mp.lastDamageAt <= credit) killer = mp.lastDamager;
        if (killer != null && killer.equals(p.getUniqueId())) killer = null;
        plugin.getLogger().info("Game #" + m.id() + ": " + p.getName() + " died (" + (last == null ? "unknown" : last.getCause() + "/"
            + last.getDamageSource().getDamageType().getKey().getKey()) + ") at " + com.cartroyale.util.Locs.pretty(p.getLocation())
            + (killer == null || m.get(killer) == null ? "" : ", credited to " + m.get(killer).name));
        if (killer != null) {
            Player kp = Bukkit.getPlayer(killer);
            if (kp != null && !m.sameTeam(killer, p.getUniqueId())) plugin.cosmetics().playKill(kp, p.getLocation());
        }
        plugin.games().eliminate(p.getUniqueId(), killer, false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Location at = deathSpot.get(e.getPlayer().getUniqueId());
        if (at == null) return;
        Match m = plugin.games().match();
        if (m != null && m.state.inArena()) e.setRespawnLocation(at.clone().add(0, 1, 0));
        else e.setRespawnLocation(plugin.lobby().spawn());
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent e) {
        Player p = e.getPlayer();
        Location at = deathSpot.remove(p.getUniqueId());
        if (at == null) return;
        Match m = plugin.games().match();
        if (m != null && m.state.inArena()) plugin.spectators().make(p, at);
        else plugin.lobby().send(p);
    }

    // ---------------- disconnects ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.teams().leave(p, false);
        Match m = plugin.games().match();
        if (m == null) return;
        if (m.state == GameState.TEAMING || m.state == GameState.PURGE) {
            // Selected but left before boarding: out of this game.
            if (m.players.remove(p.getUniqueId()) != null) m.removed.add(p.getUniqueId());
            return;
        }
        Match live = live();
        if (live == null || !live.isAlive(p.getUniqueId())) return;
        plugin.deploy().release(p);
        // DEP-5 / D13: leaving counts as elimination. Items drop where they stood (armor vanishes).
        if (plugin.arenas().isArena(p.getWorld())) {
            Location l = p.getLocation();
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null || it.getType().isAir() || Items.isArmor(it) || Items.hasFlag(it, Keys.TEMP_ELYTRA)) continue;
                l.getWorld().dropItemNaturally(l, it);
            }
            p.getInventory().clear();
        }
        Match.MP mp = live.get(p.getUniqueId());
        UUID credit = mp.lastDamager != null && System.currentTimeMillis() - mp.lastDamageAt <= 15_000 ? mp.lastDamager : null;
        live.removed.add(p.getUniqueId());
        plugin.games().eliminate(p.getUniqueId(), credit, true);
    }

    /** No regular damage in the lobby and none to spectators. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (plugin.lobby().isLobby(p.getWorld())) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) Bukkit.getScheduler().runTask(plugin, () -> plugin.lobby().send(p));
            return;
        }
        Match m = plugin.games().match();
        if (plugin.arenas().isArena(p.getWorld()) && (m == null || !m.isAlive(p.getUniqueId()))) {
            if (!p.isOp() || e.getCause() != EntityDamageEvent.DamageCause.VOID) e.setCancelled(true);
        }
    }
}
