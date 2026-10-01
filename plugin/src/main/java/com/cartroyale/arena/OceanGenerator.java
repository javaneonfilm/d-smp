package com.cartroyale.arena;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * Plain sea for every chunk the map template doesn't have. The template's own chunks load from
 * disk untouched; this only fills the space past them, so the border can sit far out at sea
 * instead of vanilla terrain appearing there. Water top and sand floor match the map (map yml
 * ocean.water-top / ocean.floor), so there is no seam where the saved chunks end.
 */
public final class OceanGenerator extends ChunkGenerator {

    private final int waterTop, floor;

    public OceanGenerator(int waterTop, int floor) {
        this.waterTop = waterTop;
        this.floor = floor;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
        int min = info.getMinHeight();
        data.setRegion(0, min, 0, 16, min + 1, 16, Material.BEDROCK);
        data.setRegion(0, min + 1, 0, 16, floor - 3, 16, Material.STONE);
        data.setRegion(0, floor - 3, 0, 16, floor + 1, 16, Material.SAND);
        data.setRegion(0, floor + 1, 0, 16, waterTop + 1, 16, Material.WATER);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo w, int x, int y, int z) {
                return Biome.OCEAN;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo w) {
                return List.of(Biome.OCEAN);
            }
        };
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
