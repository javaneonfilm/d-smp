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

    private long testKey = Long.MIN_VALUE;

    /** /cart selftest: treat one block as a loot chest for a moment. */
    public void protectForTest(Block b) {
        testKey = key(b.getX(), b.getY(), b.getZ());
        chestKeys.add(testKey);
    }

    public void clearTestProtection() {
        chestKeys.remove(testKey);
        testKey = Long.MIN_VALUE;
    }

    public boolean isLootChest(Block b) {
        return chestKeys.contains(key(b.getX(), b.getY(), b.getZ()));
    }

    /** Finds spots (cached or scanned), then places and fills chests over the next ticks. */
    public CompletableFuture<Integer> spawn(World w, MapConfig mc, int players, boolean luckier, boolean pearls, String gameTag) {
        clear(w);
        var cfg = plugin.getConfig();
        // Config is read here on the main thread; the picking (hundreds of thousands of candidates)
        // happens on the async thread that loaded the spots, never on the main thread (TECH-1).
        LootTables lt = plugin.loot();
        int want = ChestSpots.countFor(players, lt.chestBase(), lt.chestPerPlayer(), lt.chestMax());
        double minDist = lt.chestSpacing();
        int[] sh = lt.tierShares();
        double[] shares = {sh[0], sh[1], sh[2]};
        Random rng = new Random();
        return plugin.scanner().spots(w, mc, plugin.arenas().spotsFile(mc.name)).thenApplyAsync(spots -> {
            List<ChestSpots.Spot> picked = ChestSpots.pick(spots, minDist, want, rng);
            return new Object[]{spots.size(), ChestSpots.assignTiers(picked, shares, rng)};
        }).thenCompose(res -> {
            CompletableFuture<Integer> done = new CompletableFuture<>();
            int candidates = (Integer) res[0];
            @SuppressWarnings("unchecked") List<ChestSpots.Placed> placed = (List<ChestSpots.Placed>) res[1];
            Bukkit.getScheduler().runTask(plugin, () -> {
                lastReport = candidates + " candidate spots, " + want + " wanted, " + placed.size() + " placed";
                if (candidates == 0) plugin.adminAlert("chests.no-spots", "map", mc.name);
                // Each chest's chunk is loaded asynchronously first; the block is placed in the
                // callback on the main thread, so no chunk is ever loaded synchronously (TECH-1).
                java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(placed.size());
                if (placed.isEmpty()) done.complete(0);
                for (ChestSpots.Placed p : placed) {
                    w.getChunkAtAsync(p.spot().x() >> 4, p.spot().z() >> 4, true).whenComplete((c, t) -> {
                        if (t == null) place(w, p, luckier, pearls, rng, gameTag);
                        if (left.decrementAndGet() == 0) done.complete(chests.size());
                    });
                }
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
