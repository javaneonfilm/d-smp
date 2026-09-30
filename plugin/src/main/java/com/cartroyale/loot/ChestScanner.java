package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LOOT-1 candidate scan. Loads the arena's chunks a few per tick, snapshots them on the main
 * thread and scans the snapshots off it. A spot needs a full solid (occluding) floor, two air
 * blocks above and no liquid next to it. The result depends only on the template, so it is cached
 * in maps/&lt;name&gt;.spots and reused every game.
 */
public final class ChestScanner {

    private final CartRoyale plugin;
    private volatile String progress = "idle";

    public ChestScanner(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public String progress() {
        return progress;
    }

    public CompletableFuture<List<ChestSpots.Spot>> spots(World w, MapConfig mc, File cache) {
        if (cache.exists()) {
            return CompletableFuture.supplyAsync(() -> read(cache));
        }
        return scan(w, mc).thenApply(list -> {
            write(cache, list);
            return list;
        });
    }

    public CompletableFuture<List<ChestSpots.Spot>> scan(World w, MapConfig mc) {
        CompletableFuture<List<ChestSpots.Spot>> done = new CompletableFuture<>();
        int minCx = (int) Math.floor((mc.centerX - mc.borderRadius) / 16), maxCx = (int) Math.floor((mc.centerX + mc.borderRadius) / 16);
        int minCz = (int) Math.floor((mc.centerZ - mc.borderRadius) / 16), maxCz = (int) Math.floor((mc.centerZ + mc.borderRadius) / 16);
        List<int[]> chunks = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) for (int cz = minCz; cz <= maxCz; cz++) chunks.add(new int[]{cx, cz});
        List<ChestSpots.Spot> out = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger finished = new AtomicInteger();
        int total = chunks.size();
        int minY = Math.max(w.getMinHeight(), mc.chestMinY), maxY = Math.min(w.getMaxHeight() - 3, mc.chestMaxY);
        int perTick = Math.max(1, plugin.getConfig().getInt("chests.scan-chunks-per-tick", 8));
        progress = "0/" + total + " chunks";
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                for (int n = 0; n < perTick && i < total; n++, i++) {
                    int[] c = chunks.get(i);
                    w.getChunkAtAsync(c[0], c[1], true).thenAccept(chunk -> {
                        ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            try {
                                scanChunk(snap, mc, minY, maxY, out);
                            } catch (RuntimeException ex) {
                                plugin.getLogger().warning("Chest scan skipped chunk " + snap.getX() + "," + snap.getZ() + ": " + ex.getMessage());
                            }
                            int f = finished.incrementAndGet();
                            progress = f + "/" + total + " chunks, " + out.size() + " spots";
                            if (f == total) done.complete(new ArrayList<>(out));
                        });
                    });
                }
                if (i >= total) cancel();
            }
        }.runTaskTimer(plugin, 1, 1);
        return done;
    }

    private static void scanChunk(ChunkSnapshot s, MapConfig mc, int minY, int maxY, List<ChestSpots.Spot> out) {
        int bx = s.getX() << 4, bz = s.getZ() << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = bx + x, wz = bz + z;
                if (!mc.chestAllowed(wx + 0.5, wz + 0.5)) continue;
                int top = Math.min(maxY, s.getHighestBlockYAt(x, z));
                for (int y = minY; y <= top; y++) {
                    Material floor = s.getBlockType(x, y, z);
                    if (!solidFloor(floor)) continue;
                    if (!s.getBlockType(x, y + 1, z).isAir() || !s.getBlockType(x, y + 2, z).isAir()) continue;
                    if (nearLiquid(s, x, y + 1, z) || nearLiquid(s, x, y, z)) continue;
                    out.add(new ChestSpots.Spot(wx, y + 1, wz));
                }
            }
        }
    }

    static boolean solidFloor(Material m) {
        if (!m.isSolid() || !m.isOccluding()) return false;
        String n = m.name();
        return !(n.contains("LEAVES") || n.contains("ICE") || m == Material.BARRIER || m == Material.SLIME_BLOCK
            || m == Material.HONEY_BLOCK || m == Material.SOUL_SAND || m == Material.MAGMA_BLOCK || n.contains("SHULKER")
            || m == Material.CHEST || m == Material.TNT || m == Material.BEDROCK);
    }

    private static boolean nearLiquid(ChunkSnapshot s, int x, int y, int z) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], nz = z + o[1];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            Material m = s.getBlockType(nx, y, nz);
            if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return true;
        }
        return false;
    }

    private static List<ChestSpots.Spot> read(File f) {
        List<ChestSpots.Spot> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split(",");
                if (p.length == 3) out.add(new ChestSpots.Spot(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            }
        } catch (IOException | NumberFormatException e) {
            return out;
        }
        return out;
    }

    private void write(File f, List<ChestSpots.Spot> spots) {
        StringBuilder sb = new StringBuilder();
        for (ChestSpots.Spot s : spots) sb.append(s.x()).append(',').append(s.y()).append(',').append(s.z()).append('\n');
        try {
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not cache chest spots: " + e.getMessage());
        }
    }
}
