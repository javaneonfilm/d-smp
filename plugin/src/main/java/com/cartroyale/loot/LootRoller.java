package com.cartroyale.loot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Fills one chest. The slot count is uniform between min and max slots and the slots are random.
 * Each slot picks a rarity by weight, then an entry of that rarity. To keep chests varied:
 * one item type fills at most {@code maxSame} slots (default 2), an item already in the chest is
 * only a third as likely to come up again, and rarities with nothing left drop out of the draw.
 * The amount is uniform between the entry's min and max.
 */
public final class LootRoller {

    /** How much less likely an item already in the chest is to be picked again. */
    private static final double REPEAT_WEIGHT = 0.3;

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
        return roll(table, o, rng, Entry::item, 2);
    }

    /** {@code keyOf} says which entries count as the same item type (e.g. the material). */
    public static <T> List<Slot<T>> roll(List<Entry<T>> table, Options o, Random rng, Function<Entry<T>, Object> keyOf, int maxSame) {
        Map<Rarity, List<Entry<T>>> byRarity = new EnumMap<>(Rarity.class);
        for (Entry<T> e : table) {
            if (!o.pearls() && e.pearl()) continue;
            byRarity.computeIfAbsent(e.rarity(), k -> new ArrayList<>()).add(e);
        }
        List<Slot<T>> out = new ArrayList<>();
        if (byRarity.isEmpty()) return out;
        Map<Rarity, Double> w = effectiveWeights(o);

        int lo = Math.max(0, Math.min(o.minSlots(), o.chestSize()));
        int hi = Math.max(lo, Math.min(o.maxSlots(), o.chestSize()));
        int count = lo + rng.nextInt(hi - lo + 1);

        List<Integer> slots = new ArrayList<>(o.chestSize());
        for (int i = 0; i < o.chestSize(); i++) slots.add(i);
        java.util.Collections.shuffle(slots, rng);

        int cap = Math.max(1, maxSame);
        Map<Object, Integer> used = new HashMap<>();
        for (int i = 0; i < count; i++) {
            // Rarities that still have something under the cap.
            Map<Rarity, List<Entry<T>>> open = new EnumMap<>(Rarity.class);
            double total = 0;
            for (var en : byRarity.entrySet()) {
                double rw = w.getOrDefault(en.getKey(), 0.0);
                if (rw <= 0) continue;
                List<Entry<T>> left = new ArrayList<>();
                for (Entry<T> e : en.getValue()) if (used.getOrDefault(keyOf.apply(e), 0) < cap) left.add(e);
                if (left.isEmpty()) continue;
                open.put(en.getKey(), left);
                total += rw;
            }
            if (open.isEmpty()) break; // everything is at its cap: a smaller chest beats a samey one

            double pick = rng.nextDouble() * total;
            Rarity chosen = null;
            for (Rarity r : open.keySet()) {
                pick -= w.getOrDefault(r, 0.0);
                if (pick < 0) {
                    chosen = r;
                    break;
                }
            }
            if (chosen == null) chosen = open.keySet().iterator().next();

            List<Entry<T>> pool = open.get(chosen);
            double sum = 0;
            for (Entry<T> e : pool) sum += used.containsKey(keyOf.apply(e)) ? REPEAT_WEIGHT : 1.0;
            double r = rng.nextDouble() * sum;
            Entry<T> e = pool.get(pool.size() - 1);
            for (Entry<T> c : pool) {
                r -= used.containsKey(keyOf.apply(c)) ? REPEAT_WEIGHT : 1.0;
                if (r < 0) {
                    e = c;
                    break;
                }
            }
            used.merge(keyOf.apply(e), 1, Integer::sum);
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
