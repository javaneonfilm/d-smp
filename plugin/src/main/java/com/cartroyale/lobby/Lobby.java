package com.cartroyale.lobby;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.GameState;
import com.cartroyale.game.Match;
import com.cartroyale.rank.Ranks;
import com.cartroyale.util.Items;
import com.cartroyale.util.Keys;
import com.cartroyale.util.Locs;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * LOBBY-1..3 and RANK-2/3. The lobby is fully protected; everything opens from hotbar items.
 * Slot 1 grappling hook, slot 5 cosmetics, slot 9 stats, plus a host item for ticket holders and
 * admins. VIPs get wind charges (they only push the thrower) and VIP+ an elytra, lobby only.
 */
public final class Lobby implements Listener {

    public static final String GRAPPLE = "grapple", COSMETICS = "cosmetics", STATS = "stats", HOST = "host", WIND = "lobby_wind", ELYTRA = "lobby_elytra";

    private final CartRoyale plugin;
    private final Set<UUID> suspended = new HashSet<>();
    /** Wind charge bursts this tick: who threw it and where it popped (RANK-3). */
    private final List<Object[]> bursts = new ArrayList<>();
    private long burstTick;

    public Lobby(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public World world() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("worlds.lobby", "lobby"));
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    /** LOBBY-1: a calm lobby whatever the imported world's own settings are. */
    public void applyRules() {
        World w = world();
        w.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(org.bukkit.GameRule.DO_PATROL_SPAWNING, false);
        w.setGameRule(org.bukkit.GameRule.DO_TRADER_SPAWNING, false);
        w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(org.bukkit.GameRule.DO_FIRE_TICK, false);
        w.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false);
        w.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(org.bukkit.GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(org.bukkit.GameRule.SPAWN_RADIUS, 0);
        w.setStorm(false);
        w.setThundering(false);
    }

    public boolean isLobby(World w) {
        return w != null && w.equals(world());
    }

    public Location spawn() {
        Location l = Locs.read(plugin.getConfig().getString("lobby.spawn", ""));
        return l != null ? l : world().getSpawnLocation().add(0.5, 0, 0.5);
    }

    public void setSpawn(Location l) {
        plugin.getConfig().set("lobby.spawn", Locs.write(l));
        plugin.saveConfig();
    }

    /** Sends a player to the lobby with a clean state and the lobby hotbar. */
    public void send(Player p) {
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.teleport(spawn());
        reset(p);
    }

    public void reset(Player p) {
        suspended.remove(p.getUniqueId());
        if (!p.getGameMode().equals(GameMode.CREATIVE) || !Ranks.isAdmin(p)) p.setGameMode(GameMode.ADVENTURE);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setGliding(false);
        p.setExp(0);
        p.setLevel(0);
        giveHotbar(p);
        plugin.zone().hide(p);
    }

    /** /kitready turns the hotbar off so an admin can build a kit without it coming back. */
    public void suspendHotbar(UUID u, boolean on) {
        if (on) suspended.add(u);
        else suspended.remove(u);
    }

    public void giveHotbar(Player p) {
        if (suspended.contains(p.getUniqueId())) return;
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        var cfg = plugin.getConfig();
        inv.setItem(cfg.getInt("lobby-items.grapple.slot", 0), lobbyItem(grapple(), GRAPPLE));
        inv.setItem(cfg.getInt("lobby-items.cosmetics.slot", 4), lobbyItem(Items.make(Material.CHEST, plugin.msg().item("items.cosmetics.name"), plugin.msg().lore("items.cosmetics.lore")), COSMETICS));
        if (cfg.getBoolean("lobby-items.stats.enabled", true)) {
            inv.setItem(cfg.getInt("lobby-items.stats.slot", 8), lobbyItem(Items.make(Material.BOOK, plugin.msg().item("items.stats.name"), plugin.msg().lore("items.stats.lore")), STATS));
        }
        plugin.store().tickets(p.getUniqueId()).thenAccept(n -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !isLobby(p.getWorld()) || suspended.contains(p.getUniqueId())) return;
            if (n > 0 || Ranks.isAdmin(p)) {
                p.getInventory().setItem(cfg.getInt("lobby-items.host.slot", 7),
                    lobbyItem(Items.make(Material.NETHER_STAR, plugin.msg().item("items.host.name"), plugin.msg().lore("items.host.lore", "tickets", n)), HOST));
            }
        }));
        Match m = plugin.games().match();
        if (m != null && m.state == GameState.TEAMING && m.get(p.getUniqueId()) != null) {
            inv.setItem(cfg.getInt("lobby-items.team.slot", 2), plugin.teams().item());
        }
        Ranks.Rank r = Ranks.bought(p);
        if (r == Ranks.Rank.VIP || r == Ranks.Rank.VIPPLUS) {
            ItemStack wind = lobbyItem(Items.make(Material.WIND_CHARGE, plugin.msg().item("items.lobby-wind.name"), plugin.msg().lore("items.lobby-wind.lore")), WIND);
            wind.setAmount(cfg.getInt("ranks.wind-charges", 16));
            inv.setItemInOffHand(wind);
        }
        if (r == Ranks.Rank.VIPPLUS) {
            ItemStack ely = lobbyItem(Items.make(Material.ELYTRA, plugin.msg().item("items.lobby-elytra.name"), plugin.msg().lore("items.lobby-elytra.lore")), ELYTRA);
            ely.editMeta(m2 -> m2.setUnbreakable(true));
            inv.setChestplate(ely);
        }
        inv.setHeldItemSlot(cfg.getInt("lobby-items.cosmetics.slot", 4));
    }

    private ItemStack grapple() {
        ItemStack it = Items.make(Material.FISHING_ROD, plugin.msg().item("items.grapple.name"), plugin.msg().lore("items.grapple.lore"));
        it.editMeta(m -> m.setUnbreakable(true));
        return it;
    }

    private static ItemStack lobbyItem(ItemStack it, String id) {
        Items.tagged(it, id);
        return Items.flag(it, Keys.LOBBY_ITEM);
    }

    // ---------------- hotbar actions ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL) {
            if (isLobby(p.getWorld())) e.setCancelled(true);
            return;
        }
        ItemStack it = e.getItem();
        String id = Items.id(it);
        if (id == null) {
            if (isLobby(p.getWorld()) && !plugin.rank().canBuild(p) && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) e.setCancelled(true);
            return;
        }
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        switch (id) {
            case COSMETICS -> {
                e.setCancelled(true);
                if (right) plugin.cosmeticsGui().openMain(p);
            }
            case STATS -> {
                e.setCancelled(true);
                if (right) plugin.stats().show(p);
            }
            case HOST -> {
                e.setCancelled(true);
                if (right) plugin.hostGui().open(p);
            }
            case com.cartroyale.team.TeamManager.ITEM -> {
                e.setCancelled(true);
                if (right) plugin.teams().openMenu(p);
            }
            case WIND -> {
                if (!isLobby(p.getWorld())) e.setCancelled(true);
                else Bukkit.getScheduler().runTaskLater(plugin, () -> refillWind(p), 10);
            }
            default -> {
            }
        }
    }

    private void refillWind(Player p) {
        if (!p.isOnline() || !isLobby(p.getWorld())) return;
        ItemStack off = p.getInventory().getItemInOffHand();
        int want = plugin.getConfig().getInt("ranks.wind-charges", 16);
        if (Items.is(off, WIND)) off.setAmount(want);
        else if (off.getType().isAir()) giveHotbar(p);
    }

    // ---------------- grappling hook (LOBBY-3) ----------------

    /** Where each grapple bobber first struck a block; vanilla bobbers slide off walls, so this is the real latch point. */
    private final java.util.Map<UUID, Location> latched = new java.util.HashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        ItemStack rod = p.getInventory().getItemInMainHand();
        if (!Items.is(rod, GRAPPLE)) rod = p.getInventory().getItemInOffHand();
        if (!Items.is(rod, GRAPPLE)) return;
        if (!isLobby(p.getWorld())) {
            e.setCancelled(true);
            return;
        }
        if (e.getState() == PlayerFishEvent.State.FISHING) {
            latched.remove(p.getUniqueId());
            if (p.hasCooldown(Material.FISHING_ROD)) e.setCancelled(true);
            return;
        }
        if (e.getState() == PlayerFishEvent.State.BITE || e.getState() == PlayerFishEvent.State.LURED) return;
        // Every way of reeling in pulls: latched on a wall, lying on the ground, in water, or still
        // in the air. It used to need the bobber on the ground, so walls and long casts did nothing.
        FishHook hook = e.getHook();
        Location to = latched.remove(p.getUniqueId());
        if (to == null || !to.getWorld().equals(p.getWorld())) to = hook.getLocation();
        Location from = p.getLocation();
        double range = plugin.getConfig().getDouble("lobby-items.grapple.pull-range", 50);
        double dist = from.distance(to);
        if (dist > range || dist < 1.5) return;
        // Speed grows with distance so short and long pulls both reach the hook; the lift clears
        // the ground and arcs over ledges. Clamped so it can't fling anyone across the map.
        double max = plugin.getConfig().getDouble("lobby-items.grapple.pull-max-velocity", 5.0);
        double speed = Math.min(max, plugin.getConfig().getDouble("lobby-items.grapple.pull-base", 0.5)
            + dist * plugin.getConfig().getDouble("lobby-items.grapple.pull-per-block", 0.11));
        Vector v = to.toVector().subtract(from.toVector()).normalize().multiply(speed);
        v.setY(v.getY() + plugin.getConfig().getDouble("lobby-items.grapple.pull-lift", 0.7));
        if (v.length() > max) v.normalize().multiply(max);
        p.setVelocity(v);
        p.setFallDistance(0);
        p.getWorld().playSound(from, org.bukkit.Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.7f);
        int cd = (int) Math.round(plugin.getConfig().getInt("lobby-items.grapple.cooldown-ms", 500) / 50.0);
        p.setCooldown(Material.FISHING_ROD, cd);
    }

    @EventHandler
    public void onHookHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof FishHook hook) || !(hook.getShooter() instanceof Player p) || e.getHitBlock() == null) return;
        if (!isLobby(hook.getWorld()) || latched.containsKey(p.getUniqueId())) return;
        if (!Items.is(p.getInventory().getItemInMainHand(), GRAPPLE) && !Items.is(p.getInventory().getItemInOffHand(), GRAPPLE)) return;
        // The event fires a step before the hook touches the block; trace the rest of the way.
        Vector vel = hook.getVelocity();
        Location at = hook.getLocation();
        if (vel.lengthSquared() > 1.0E-4) {
            var hit = hook.getWorld().rayTraceBlocks(at, vel.clone().normalize(), vel.length() + 1.5, org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit != null) at = hit.getHitPosition().toLocation(hook.getWorld());
        }
        latched.put(p.getUniqueId(), at);
        // Freeze the bobber where it struck so what the player sees matches where they'll fly.
        hook.setVelocity(new Vector());
        hook.setGravity(false);
    }

    // ---------------- wind charges push only the thrower (RANK-3) ----------------

    @EventHandler
    public void onWindHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof WindCharge wc) || !isLobby(wc.getWorld())) return;
        long tick = Bukkit.getCurrentTick();
        if (tick != burstTick) {
            bursts.clear();
            burstTick = tick;
        }
        bursts.add(new Object[]{wc.getShooter() instanceof Player p ? p.getUniqueId() : null, wc.getLocation()});
    }

    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isLobby(p.getWorld())) return;
        if (e.getCause() != EntityKnockbackEvent.Cause.EXPLOSION) return;
        long tick = org.bukkit.Bukkit.getCurrentTick(); // the arena clock is frozen, so never use world time here
        if (tick == burstTick) {
            for (Object[] b : bursts) {
                if (p.getUniqueId().equals(b[0]) && ((Location) b[1]).distanceSquared(p.getLocation()) < 36) return;
            }
        }
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLobbyExplode(EntityExplodeEvent e) {
        if (isLobby(e.getLocation().getWorld())) e.blockList().clear();
    }

    // ---------------- protection (LOBBY-1) ----------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isLobby(e.getBlock().getWorld()) && !plugin.rank().canBuild(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isLobby(e.getBlock().getWorld()) && !plugin.rank().canBuild(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && isLobby(p.getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isLobby(e.getPlayer().getWorld()) && !plugin.rank().canBuild(e.getPlayer())) e.setCancelled(true);
        else if (Items.hasFlag(e.getItemDrop().getItemStack(), Keys.LOBBY_ITEM)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemWear(PlayerItemDamageEvent e) {
        if (isLobby(e.getPlayer().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isLobby(e.getPlayer().getWorld()) && !plugin.rank().canBuild(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isLobby(p.getWorld()) || plugin.rank().canBuild(p) || suspended.contains(p.getUniqueId())) return;
        if (e.getView().getTopInventory().getHolder(false) instanceof com.cartroyale.gui.Gui) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && isLobby(p.getWorld()) && !plugin.rank().canBuild(p) && !suspended.contains(p.getUniqueId())) e.setCancelled(true);
    }

    /** Lobby items never leave the lobby. */
    public static void stripLobbyItems(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (Items.hasFlag(inv.getItem(i), Keys.LOBBY_ITEM)) inv.setItem(i, null);
        if (Items.hasFlag(inv.getItem(EquipmentSlot.OFF_HAND), Keys.LOBBY_ITEM)) inv.setItemInOffHand(null);
        if (Items.hasFlag(inv.getChestplate(), Keys.LOBBY_ITEM)) inv.setChestplate(null);
    }
}
