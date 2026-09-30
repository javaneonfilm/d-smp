package com.cartroyale.loot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fills one chest. The slot count is uniform between min and max slots, the slots are distinct
 * and random, and each slot first picks a rarity by weight (only rarities that have entries take
 * part) and then an entry of that rarity uniformly. The amount is uniform between the entry's
 * min and max.
 */
public final class LootRoller {

    public record Entry<T>(T item, Rarity rarity, int min, int max, boolean pearl) {
    }

    public record Slot<T>(int slot, T item, int amount) {
    }

    public record Options(int minSlots, int maxSlots, int chestSize, Map<Rarity, Double> weights,
                          boolean luckier, Map<Rarity, Double> luckyMultipliers, boolean pearls) {
    }

    private LootRoller() {
    }

    public static <T> List<Slot<T>> roll(List<Entry<T>> table, Options o, Random rng) {
        Map<Rarity, List<Entry<T>>> byRarity = new EnumMap<>(Rarity.class);
        for (Entry<T> e : table) {
            if (!o.pearls() && e.pearl()) continue;
            byRarity.computeIfAbsent(e.rarity(), k -> new ArrayList<>()).add(e);
        }
        List<Slot<T>> out = new ArrayList<>();
        if (byRarity.isEmpty()) return out;

        Map<Rarity, Double> w = effectiveWeights(o);
        double total = 0;
        for (Rarity r : byRarity.keySet()) total += w.getOrDefault(r, 0.0);
        if (total <= 0) return out;

        int lo = Math.max(0, Math.min(o.minSlots(), o.chestSize()));
        int hi = Math.max(lo, Math.min(o.maxSlots(), o.chestSize()));
        int count = lo + rng.nextInt(hi - lo + 1);

        List<Integer> slots = new ArrayList<>(o.chestSize());
        for (int i = 0; i < o.chestSize(); i++) slots.add(i);
        java.util.Collections.shuffle(slots, rng);

        for (int i = 0; i < count; i++) {
            double pick = rng.nextDouble() * total;
            Rarity chosen = null;
            for (Rarity r : byRarity.keySet()) {
                pick -= w.getOrDefault(r, 0.0);
                if (pick < 0) {
                    chosen = r;
                    break;
                }
            }
            if (chosen == null) chosen = byRarity.keySet().iterator().next();
            List<Entry<T>> pool = byRarity.get(chosen);
            Entry<T> e = pool.get(rng.nextInt(pool.size()));
            int min = Math.max(1, Math.min(e.min(), e.max()));
            int max = Math.max(min, e.max());
            out.add(new Slot<>(slots.get(i), e.item(), min + rng.nextInt(max - min + 1)));
        }
        return out;
    }

    public static Map<Rarity, Double> effectiveWeights(Options o) {
        Map<Rarity, Double> w = new EnumMap<>(Rarity.class);
        for (Rarity r : Rarity.values()) {
            double base = o.weights().getOrDefault(r, 0.0);
            if (o.luckier()) base *= o.luckyMultipliers().getOrDefault(r, 1.0);
            w.put(r, base);
        }
        return w;
    }
}
