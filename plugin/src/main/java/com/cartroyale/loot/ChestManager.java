package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import com.cartroyale.util.Keys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * LOOT-1/4/5/6/7: places tiered chests in small batches, fills them, floats a star hologram above
 * each, and removes every chest and hologram after the round.
 */
public final class ChestManager {

    private static final BlockFace[] FACES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final CartRoyale plugin;
    private final Set<Long> chestKeys = new HashSet<>();
    private final List<Location> chests = new ArrayList<>();
    private final List<UUID> holograms = new ArrayList<>();
    private String lastReport = "no chests placed yet";

    public ChestManager(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public String report() {
        return lastReport;
    }

    public int count() {
        return chests.size();
    }

    public boolean isLootChest(Block b) {
        return chestKeys.contains(key(b.getX(), b.getY(), b.getZ()));
    }

    /** Finds spots (cached or scanned), then places and fills chests over the next ticks. */
    public CompletableFuture<Integer> spawn(World w, MapConfig mc, int players, boolean luckier, boolean pearls, String gameTag) {
        clear(w);
        var cfg = plugin.getConfig();
        return plugin.scanner().spots(w, mc, plugin.arenas().spotsFile(mc.name)).thenCompose(spots -> {
            CompletableFuture<Integer> done = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Random rng = new Random();
                int want = ChestSpots.countFor(players, cfg.getInt("chests.base", 20), cfg.getDouble("chests.per-player", 2.0), cfg.getInt("chests.max", 300));
                List<ChestSpots.Spot> picked = ChestSpots.pick(spots, cfg.getDouble("chests.min-distance", 24), want, rng);
                List<Double> sh = cfg.getDoubleList("chests.tier-shares");
                double[] shares = sh.size() == 3 ? new double[]{sh.get(0), sh.get(1), sh.get(2)} : new double[]{0.6, 0.3, 0.1};
                List<ChestSpots.Placed> placed = ChestSpots.assignTiers(picked, shares, rng);
                lastReport = spots.size() + " candidate spots, " + want + " wanted, " + placed.size() + " placed";
                if (spots.isEmpty()) plugin.adminAlert("chests.no-spots", "map", mc.name);
                int perTick = Math.max(1, cfg.getInt("chests.place-per-tick", 20));
                new BukkitRunnable() {
                    int i = 0;

                    @Override
                    public void run() {
                        for (int n = 0; n < perTick && i < placed.size(); n++, i++) {
                            place(w, placed.get(i), luckier, pearls, rng, gameTag);
                        }
                        if (i >= placed.size()) {
                            cancel();
                            done.complete(placed.size());
                        }
                    }
                }.runTaskTimer(plugin, 1, 1);
            });
            return done;
        });
    }

    private void place(World w, ChestSpots.Placed p, boolean luckier, boolean pearls, Random rng, String gameTag) {
        Block b = w.getBlockAt(p.spot().x(), p.spot().y(), p.spot().z());
        if (!b.getType().isAir()) return;
        b.setType(Material.CHEST, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Chest data) {
            data.setFacing(FACES[rng.nextInt(4)]);
            data.setType(Type.SINGLE);
            b.setBlockData(data, false);
        }
        if (b.getState() instanceof Chest chest) {
            chest.customName(plugin.msg().get("chests.name-" + p.tier()));
            chest.update(true, false);
            var inv = ((Chest) b.getState()).getBlockInventory();
            for (var slot : plugin.loot().roll(p.tier(), luckier, pearls, rng)) {
                ItemStack it = slot.item().clone();
                it.setAmount(slot.amount());
                inv.setItem(slot.slot(), it);
            }
        }
        chestKeys.add(key(b.getX(), b.getY(), b.getZ()));
        chests.add(b.getLocation());
        Location holo = b.getLocation().add(0.5, plugin.getConfig().getDouble("chests.hologram-height", 1.35), 0.5);
        TextDisplay td = w.spawn(holo, TextDisplay.class, d -> {
            d.text(stars(p.tier()));
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setPersistent(false);
            d.setViewRange(0.5f);
            d.getPersistentDataContainer().set(Keys.ARENA_OBJECT, PersistentDataType.STRING, gameTag);
        });
        holograms.add(td.getUniqueId());
    }

    private Component stars(int tier) {
        return plugin.msg().get("chests.hologram-" + tier);
    }

    /** Removes every chest and hologram this manager placed, plus any tagged leftovers. */
    public void clear(World w) {
        if (w == null) return;
        for (Location l : chests) {
            Block b = l.getBlock();
            if (b.getType() == Material.CHEST && b.getState() instanceof Chest c) c.getBlockInventory().clear();
            if (b.getType() == Material.CHEST) b.setType(Material.AIR, false);
        }
        for (UUID id : holograms) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        sweep(w);
        chests.clear();
        chestKeys.clear();
        holograms.clear();
    }

    /** TECH-2: removes any plugin-tagged entity in the arena (holograms, ghasts) left by a crash. */
    public int sweep(World w) {
        int n = 0;
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(Keys.ARENA_OBJECT)) {
                e.remove();
                n++;
            }
        }
        return n;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
