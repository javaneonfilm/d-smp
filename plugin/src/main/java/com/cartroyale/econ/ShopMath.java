package com.cartroyale.econ;

import com.cartroyale.loot.Rarity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks the global daily shop. Seeded by the date, so every server restart on the same day gives
 * the same result even before it is stored. For each category it rolls a rarity by the odds and
 * takes a random unpicked cosmetic of that rarity, falling back to the nearest rarity that has
 * one left.
 */
public final class ShopMath {

    public record Item(String id, String category, Rarity rarity) {
    }

    private ShopMath() {
    }

    public static long seed(LocalDate date, long salt) {
        return date.toEpochDay() * 0x9E3779B97F4A7C15L ^ salt;
    }

    public static List<String> pick(List<Item> all, List<String> categories, int perCategory,
                                    Map<Rarity, Double> odds, Random rng) {
        List<String> out = new ArrayList<>();
        for (String cat : categories) {
            Map<Rarity, List<Item>> pool = new EnumMap<>(Rarity.class);
            for (Item i : all) if (i.category().equals(cat)) pool.computeIfAbsent(i.rarity(), k -> new ArrayList<>()).add(i);
            for (int n = 0; n < perCategory; n++) {
                Rarity want = rollRarity(odds, rng);
                Item got = take(pool, want, rng);
                if (got == null) break;
                out.add(got.id());
            }
        }
        return out;
    }

    public static Rarity rollRarity(Map<Rarity, Double> odds, Random rng) {
        double total = 0;
        for (Rarity r : Rarity.values()) total += odds.getOrDefault(r, 0.0);
        double x = rng.nextDouble() * total;
        for (Rarity r : Rarity.values()) {
            x -= odds.getOrDefault(r, 0.0);
            if (x < 0) return r;
        }
        return Rarity.COMMON;
    }

    private static Item take(Map<Rarity, List<Item>> pool, Rarity want, Random rng) {
        Rarity[] all = Rarity.values();
        for (int d = 0; d < all.length; d++) {
            for (int sign : new int[]{-1, 1}) {
                int idx = want.ordinal() + sign * d;
                if (idx < 0 || idx >= all.length) continue;
                List<Item> l = pool.get(all[idx]);
                if (l != null && !l.isEmpty()) return l.remove(rng.nextInt(l.size()));
                if (d == 0) break;
            }
        }
        return null;
    }
}
