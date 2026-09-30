package com.cartroyale.arena;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;

/**
 * Terrain for the stand-in test arena used until the real map is installed: gentle hills with
 * a few flat-topped stone pillars, so chest placement, gliding and the zone can be tested.
 */
public final class StandInGenerator extends ChunkGenerator {

    @Override
    public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
        SimplexOctaveGenerator hills = new SimplexOctaveGenerator(info.getSeed(), 4);
        hills.setScale(1 / 90.0);
        int min = info.getMinHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = cx * 16 + x, wz = cz * 16 + z;
                int h = 64 + (int) Math.round(hills.noise(wx, wz, 0.5, 0.5, true) * 7);
                data.setBlock(x, min, z, Material.BEDROCK);
                data.setRegion(x, min + 1, z, x + 1, h - 3, z + 1, Material.STONE);
                data.setRegion(x, h - 3, z, x + 1, h, z + 1, Material.DIRT);
                data.setBlock(x, h, z, Material.GRASS_BLOCK);
                // Pillars every 48 blocks give the scan some elevated spots.
                if (Math.floorMod(wx, 48) < 3 && Math.floorMod(wz, 48) < 3) {
                    data.setRegion(x, h + 1, z, x + 1, h + 9, z + 1, Material.STONE_BRICKS);
                }
            }
        }
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
