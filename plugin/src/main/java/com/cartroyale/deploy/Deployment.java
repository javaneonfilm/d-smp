package com.cartroyale.deploy;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import com.cartroyale.game.Match;
import com.cartroyale.util.Items;
import com.cartroyale.util.Keys;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DEP-1..5 and D6. Kits are given on boarding, players ride Happy Ghasts along a straight route,
 * sneak to drop early (or are dropped at the end), glide down on a locked temporary elytra and get
 * their chestplate back on landing, with that landing's fall damage cancelled.
 */
public final class Deployment implements Listener {

    private static final class Ride {
        final HappyGhast ghast;
        final Location start;
        final List<UUID> riders = new ArrayList<>();
        Location last;
        int stuckTicks;

        Ride(HappyGhast ghast, Location start) {
            this.ghast = ghast;
            this.start = start;
            this.last = start.clone();
        }
    }

    private static final class Glider {
        final ItemStack chest;
        boolean riding = true;
        long droppedAt;

        Glider(ItemStack chest) {
            this.chest = chest;
        }
    }

    private final CartRoyale plugin;
    private final List<Ride> rides = new ArrayList<>();
    private final Map<UUID, Glider> gliders = new HashMap<>();
    private final Map<UUID, Long> safeLanding = new HashMap<>();
    private final Set<UUID> ejecting = new HashSet<>();
    private BukkitTask task;
    private Runnable onDone;
    private boolean routeDone;
    private boolean teleportMode;
    private long tick;
    private Vector dir;
    private double speed, distance;
    private String lastReport = "no deployment yet";

    public Deployment(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public String report() {
        return lastReport + ", " + gliders.size() + " still gliding/riding, " + rides.size() + " ghasts";
    }

    public boolean isGlider(UUID u) {
        return gliders.containsKey(u);
    }

    // ------------------------------------------------------------------ preload

    private final List<long[]> tickets = new ArrayList<>();
    private World ticketWorld;

    /**
     * TECH-1: loads every chunk along the ghast route asynchronously (during the purge and team-up)
     * and holds them with plugin tickets, so boarding never generates or loads chunks on the
     * main thread. Released when the deployment ends.
     */
    public void preload(World w, MapConfig mc, int players) {
        releaseTickets();
        var cfg = plugin.getConfig();
        double yaw = Math.toRadians(mc.deployYaw);
        Vector d = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)).normalize();
        Vector side = new Vector(-d.getZ(), 0, d.getX());
        int seats = Math.max(1, Math.min(4, cfg.getInt("deploy.riders-per-ghast", 4)));
        int ghasts = Math.max(1, (players + seats - 1) / seats + 2);
        int perRow = Math.max(1, cfg.getInt("deploy.per-row", 8));
        double width = (Math.min(perRow, ghasts) - 1) * cfg.getDouble("deploy.spacing", 9) + 32;
        double depth = ((ghasts + perRow - 1) / perRow) * cfg.getDouble("deploy.row-gap", 12) + 16;
        double dist = (mc.deployDistance > 0 ? mc.deployDistance : cfg.getDouble("deploy.distance", 300)) + 32;
        java.util.Set<Long> keys = new java.util.HashSet<>();
        for (double t = -depth; t <= dist; t += 8) {
            for (double s = -width / 2; s <= width / 2; s += 8) {
                int cx = (int) Math.floor((mc.deployX + d.getX() * t + side.getX() * s) / 16);
                int cz = (int) Math.floor((mc.deployZ + d.getZ() * t + side.getZ() * s) / 16);
                keys.add(((long) cx << 32) ^ (cz & 0xffffffffL));
            }
        }
        ticketWorld = w;
        for (long k : keys) {
            int cx = (int) (k >> 32), cz = (int) k;
            w.getChunkAtAsync(cx, cz, true).thenAccept(c -> {
                if (ticketWorld == w && c.addPluginChunkTicket(plugin)) tickets.add(new long[]{cx, cz});
            });
        }
        plugin.debug("preloading " + keys.size() + " route chunks");
    }

    private void releaseTickets() {
        if (ticketWorld != null) {
            for (long[] t : tickets) ticketWorld.removePluginChunkTicket((int) t[0], (int) t[1], plugin);
        }
        tickets.clear();
        ticketWorld = null;
    }

    // ------------------------------------------------------------------ start

    public void start(Match m, World w, MapConfig mc, Runnable done) {
        List<long[]> keep = new ArrayList<>(tickets);
        World keepWorld = ticketWorld;
        tickets.clear();
        stop(true);
        tickets.addAll(keep);
        ticketWorld = keepWorld;
        this.onDone = done;
        this.routeDone = false;
        this.tick = 0;
        var cfg = plugin.getConfig();
        this.teleportMode = "TELEPORT".equalsIgnoreCase(cfg.getString("deploy.move-mode", "TELEPORT"));
        double yaw = Math.toRadians(mc.deployYaw);
        dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)).normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        speed = cfg.getDouble("deploy.speed", 0.6);
        distance = mc.deployDistance > 0 ? mc.deployDistance : cfg.getDouble("deploy.distance", 300);
        int seats = Math.max(1, Math.min(4, cfg.getInt("deploy.riders-per-ghast", 4)));
        double spacing = cfg.getDouble("deploy.spacing", 9), rowGap = cfg.getDouble("deploy.row-gap", 12);
        int perRow = Math.max(1, cfg.getInt("deploy.per-row", 8));

        List<List<UUID>> loads = pack(m, seats);
        int used = Math.min(perRow, loads.size());
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < loads.size(); i++) {
            int row = i / perRow, col = i % perRow;
            int inRow = Math.min(perRow, loads.size() - row * perRow);
            double off = (col - (inRow - 1) / 2.0) * spacing;
            spots.add(new Location(w, mc.deployX, mc.deployY, mc.deployZ, mc.deployYaw, 0)
                .add(side.clone().multiply(off)).subtract(dir.clone().multiply(row * rowGap)));
        }
        lastReport = loads.size() + " ghasts for " + m.players.size() + " players (" + used + " per row max), mode " + (teleportMode ? "teleport" : "velocity");
        plugin.getLogger().info("Deploy: " + lastReport);
        // Boarding is spread over a few ticks (each cross-world teleport costs a few ms), then the
        // whole fleet starts moving together.
        int perTick = Math.max(1, cfg.getInt("deploy.ghasts-per-tick", 2));
        long[] ns = new long[2];
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int next = 0;

            @Override
            public void run() {
                for (int n = 0; n < perTick && next < spots.size(); n++, next++) {
                    spawnRide(m, w, spots.get(next), loads.get(next), cfg.getBoolean("deploy.harness", false), ns);
                }
                if (next >= spots.size()) {
                    task.cancel();
                    plugin.debug(String.format("deploy start: ghast spawns %.1f ms, boarding players %.1f ms (spread over %d ticks)",
                        ns[0] / 1e6, ns[1] / 1e6, (spots.size() + perTick - 1) / perTick));
                    task = Bukkit.getScheduler().runTaskTimer(plugin, Deployment.this::tickAll, 1, 1);
                }
            }
        }, 0, 1);
    }

    private void spawnRide(Match m, World w, Location loc, List<UUID> riders, boolean harness, long[] ns) {
        long a = System.nanoTime();
        w.getChunkAt(loc);
        HappyGhast g = w.spawn(loc, HappyGhast.class, gh -> {
            gh.setAI(false);
            gh.setInvulnerable(true);
            gh.setGravity(false);
            gh.setSilent(true);
            gh.setPersistent(false);
            gh.setRemoveWhenFarAway(false);
            gh.getPersistentDataContainer().set(Keys.ARENA_OBJECT, PersistentDataType.STRING, "game-" + m.id());
            if (harness) gh.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(Material.RED_HARNESS));
        });
        Ride r = new Ride(g, loc.clone());
        long b = System.nanoTime();
        ns[0] += b - a;
        for (UUID u : riders) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            board(p, m, g, loc);
            r.riders.add(u);
        }
        ns[1] += System.nanoTime() - b;
        rides.add(r);
    }

    /** First-fit: whole teams stay together on one ghast; spare seats take solo players. */
    private static List<List<UUID>> pack(Match m, int seats) {
        List<List<UUID>> teams = new ArrayList<>();
        for (List<UUID> t : m.teams) {
            List<UUID> online = new ArrayList<>();
            for (UUID u : t) if (Bukkit.getPlayer(u) != null && m.isAlive(u)) online.add(u);
            if (!online.isEmpty()) teams.add(online);
        }
        teams.sort(Comparator.comparingInt((List<UUID> t) -> t.size()).reversed());
        List<List<UUID>> ghasts = new ArrayList<>();
        for (List<UUID> t : teams) {
            List<UUID> rest = new ArrayList<>(t);
            while (!rest.isEmpty()) {
                List<UUID> target = null;
                for (List<UUID> g : ghasts) if (seats - g.size() >= Math.min(rest.size(), seats)) {
                    target = g;
                    break;
                }
                if (target == null) {
                    target = new ArrayList<>();
                    ghasts.add(target);
                }
                while (target.size() < seats && !rest.isEmpty()) target.add(rest.remove(0));
            }
        }
        return ghasts;
    }

    private void board(Player p, Match m, HappyGhast g, Location loc) {
        p.closeInventory();
        p.setGameMode(GameMode.SURVIVAL);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setExp(0);
        p.setLevel(0);
        plugin.lobby().suspendHotbar(p.getUniqueId(), false);
        plugin.kits().apply(p, m.s().mode);
        ItemStack chest = p.getInventory().getChestplate();
        p.getInventory().setChestplate(tempElytra());
        gliders.put(p.getUniqueId(), new Glider(chest == null ? null : chest.clone()));
        p.teleport(loc.clone().add(0, 4.5, 0));
        if (!g.addPassenger(p)) plugin.adminAlert("deploy.mount-failed", "player", p.getName());
        plugin.msg().send(p, "deploy.boarded", "mode", plugin.announcer().modeName(m.s()));
    }

    private ItemStack tempElytra() {
        ItemStack it = Items.make(Material.ELYTRA, plugin.msg().item("items.temp-elytra.name"), plugin.msg().lore("items.temp-elytra.lore"));
        it.editMeta(meta -> meta.setUnbreakable(true));
        return Items.flag(it, Keys.TEMP_ELYTRA);
    }

    // ------------------------------------------------------------------ tick

    private void tickAll() {
        tick++;
        double travelled = tick * speed;
        if (!routeDone) {
            for (Ride r : rides) move(r, travelled);
            if (travelled >= distance) dropAll();
            if (tick % 20 == 0) {
                int left = (int) Math.max(0, (distance - travelled) / speed / 20);
                for (Ride r : rides) for (UUID u : r.riders) {
                    Player p = Bukkit.getPlayer(u);
                    Glider gl = gliders.get(u);
                    if (p != null && gl != null && gl.riding) plugin.msg().actionbar(p, "deploy.riding-bar", "seconds", left);
                }
            }
        }
        long now = System.currentTimeMillis();
        long maxGlide = plugin.getConfig().getInt("elytra.max-glide-seconds", 60) * 1000L;
        for (var e : List.copyOf(gliders.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Glider gl = e.getValue();
            if (p == null) {
                gliders.remove(e.getKey());
                continue;
            }
            if (gl.riding) {
                if (!p.isInsideVehicle() && !ejecting.contains(p.getUniqueId())) dropped(p, gl);
                continue;
            }
            long airborne = now - gl.droppedAt;
            if (airborne > 300 && landed(p)) {
                land(p, gl, false);
            } else if (airborne > maxGlide) {
                land(p, gl, true);
            } else if (!p.isGliding() && airborne < maxGlide && !p.isOnGround()) {
                p.setGliding(true);
            }
        }
        if (routeDone && rides.isEmpty() && gliders.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void move(Ride r, double travelled) {
        HappyGhast g = r.ghast;
        if (!g.isValid()) return;
        Location target = r.start.clone().add(dir.clone().multiply(Math.min(travelled, distance)));
        Location now = g.getLocation();
        if (teleportMode) {
            target.setYaw(r.start.getYaw());
            g.teleport(target, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            return;
        }
        Vector v = target.toVector().subtract(now.toVector());
        if (v.length() > 3) v.normalize().multiply(3);
        g.setVelocity(v);
        // Fallback: if the ghast won't move by velocity on this version, switch to teleporting.
        if (tick > 20 && now.distanceSquared(r.last) < 0.0001) {
            if (++r.stuckTicks > 10) {
                teleportMode = true;
                lastReport += " (switched to teleport: ghasts ignored velocity)";
                plugin.getLogger().warning("Happy Ghasts did not move by velocity; deployment switched to teleport mode.");
            }
        } else {
            r.stuckTicks = 0;
        }
        r.last = now;
    }

    private void dropAll() {
        routeDone = true;
        for (Ride r : rides) {
            for (UUID u : r.riders) {
                Player p = Bukkit.getPlayer(u);
                Glider gl = gliders.get(u);
                if (p != null && gl != null && gl.riding) {
                    ejecting.add(u);
                    r.ghast.removePassenger(p);
                    ejecting.remove(u);
                    dropped(p, gl);
                }
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::removeGhasts, 20);
        Runnable d = onDone;
        onDone = null;
        if (d != null) d.run();
    }

    private void dropped(Player p, Glider gl) {
        gl.riding = false;
        gl.droppedAt = System.currentTimeMillis();
        plugin.debug("dropped " + p.getName() + " at " + com.cartroyale.util.Locs.pretty(p.getLocation()));
        p.setFallDistance(0);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && !p.isOnGround()) p.setGliding(true);
        });
        plugin.msg().actionbar(p, "deploy.dropped");
    }

    /**
     * Server-side landing check. The client's on-ground flag alone is not trusted (a client can
     * report it mid-air), so a solid block has to be right under the feet.
     */
    private static boolean landed(Player p) {
        if (p.isInWater() || p.isInLava() || p.isInsideVehicle() || p.isClimbing()) return true;
        Location feet = p.getLocation();
        Material below = feet.clone().subtract(0, 0.2, 0).getBlock().getType();
        if (below == Material.POWDER_SNOW || below == Material.COBWEB) return true;
        if (!p.isOnGround()) return false;
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                if (!feet.clone().add(dx, -0.2, dz).getBlock().isPassable()) return true;
            }
        }
        return false;
    }

    private void land(Player p, Glider gl, boolean forced) {
        gliders.remove(p.getUniqueId());
        p.setGliding(false);
        ItemStack chest = p.getInventory().getChestplate();
        if (Items.hasFlag(chest, Keys.TEMP_ELYTRA)) p.getInventory().setChestplate(gl.chest);
        stripTempElytra(p);
        p.setFallDistance(0);
        // The next fall-damage hit is cancelled whenever it comes (within 30 s), so a landing
        // registered a moment early can never turn into a lethal fall.
        safeLanding.put(p.getUniqueId(), System.currentTimeMillis() + 30_000);
        plugin.debug("landed " + p.getName() + (forced ? " (forced)" : "") + " at " + com.cartroyale.util.Locs.pretty(p.getLocation())
            + ", chest now " + (p.getInventory().getChestplate() == null ? "empty" : p.getInventory().getChestplate().getType()));
        plugin.msg().actionbar(p, forced ? "deploy.glide-timeout" : "deploy.landed");
    }

    private static void stripTempElytra(Player p) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (Items.hasFlag(c[i], Keys.TEMP_ELYTRA)) p.getInventory().setItem(i, null);
        if (Items.hasFlag(p.getItemOnCursor(), Keys.TEMP_ELYTRA)) p.setItemOnCursor(null);
    }

    private void removeGhasts() {
        for (Ride r : rides) {
            r.ghast.eject();
            r.ghast.remove();
        }
        rides.clear();
        releaseTickets();
    }

    /** Ends deployment. With {@code cleanup} every rider/glider is landed safely and ghasts removed. */
    public void stop(boolean cleanup) {
        routeDone = true;
        onDone = null;
        if (!cleanup) return;
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (var e : List.copyOf(gliders.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) land(p, e.getValue(), true);
        }
        gliders.clear();
        removeGhasts();
    }

    /** A player leaving mid-deploy (DEP-5): restore their chestplate so nothing is orphaned. */
    public void release(Player p) {
        Glider gl = gliders.get(p.getUniqueId());
        if (gl == null) return;
        if (p.isInsideVehicle()) p.leaveVehicle();
        land(p, gl, true);
    }

    // ------------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Glider gl = gliders.get(p.getUniqueId());
        if (gl == null || !gl.riding) return;
        if (ejecting.contains(p.getUniqueId())) return;
        // Sneaking is the only way a rider can dismount, so any dismount is an early drop (DEP-3).
        Bukkit.getScheduler().runTask(plugin, () -> {
            Glider g2 = gliders.get(p.getUniqueId());
            if (g2 != null && g2.riding && !p.isInsideVehicle()) dropped(p, g2);
        });
    }

    @EventHandler
    public void onInteractGhast(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (t instanceof HappyGhast && t.getPersistentDataContainer().has(Keys.ARENA_OBJECT)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL && e.getCause() != EntityDamageEvent.DamageCause.FLY_INTO_WALL) return;
        if (gliders.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        Long until = safeLanding.get(p.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            e.setCancelled(true);
            safeLanding.remove(p.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoost(PlayerElytraBoostEvent e) {
        if (gliders.containsKey(e.getPlayer().getUniqueId()) && !plugin.getConfig().getBoolean("elytra.allow-fireworks", false)) e.setCancelled(true);
    }

    private boolean wearing(Player p) {
        return Items.hasFlag(p.getInventory().getChestplate(), Keys.TEMP_ELYTRA);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean involved = Items.hasFlag(e.getCurrentItem(), Keys.TEMP_ELYTRA) || Items.hasFlag(e.getCursor(), Keys.TEMP_ELYTRA);
        if (e.getHotbarButton() >= 0) involved |= Items.hasFlag(p.getInventory().getItem(e.getHotbarButton()), Keys.TEMP_ELYTRA);
        if (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) involved |= Items.hasFlag(p.getInventory().getItemInOffHand(), Keys.TEMP_ELYTRA);
        if (wearing(p) && (e.getSlotType() == InventoryType.SlotType.ARMOR || e.isShiftClick())) involved = true;
        if (involved) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (Items.hasFlag(e.getOldCursor(), Keys.TEMP_ELYTRA)) {
            e.setCancelled(true);
            return;
        }
        if (wearing(p)) {
            for (int raw : e.getRawSlots()) {
                if (e.getView().getSlotType(raw) == InventoryType.SlotType.ARMOR) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreative(InventoryCreativeEvent e) {
        if (e.getWhoClicked() instanceof Player p && (wearing(p) || Items.hasFlag(e.getCursor(), Keys.TEMP_ELYTRA))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (Items.hasFlag(e.getItemDrop().getItemStack(), Keys.TEMP_ELYTRA)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (Items.hasFlag(e.getMainHandItem(), Keys.TEMP_ELYTRA) || Items.hasFlag(e.getOffHandItem(), Keys.TEMP_ELYTRA)) e.setCancelled(true);
    }

    /** Right-clicking a chestplate would hot-swap it with the worn elytra. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEquipFromHand(PlayerInteractEvent e) {
        if (!wearing(e.getPlayer()) || e.getItem() == null) return;
        EquipmentSlot s = e.getItem().getType().getEquipmentSlot();
        if (s == EquipmentSlot.CHEST || e.getItem().getType() == Material.ELYTRA) e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDispense(BlockDispenseArmorEvent e) {
        if (e.getTargetEntity() instanceof Player p && wearing(p)) e.setCancelled(true);
    }
}
