package com.cartroyale.loot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks well-spaced chest positions from a list of valid candidates (Poisson-disc style: shuffle,
 * then accept a point only if no accepted point is within the minimum distance, checked through a
 * grid so it stays fast with tens of thousands of candidates), then deals out tiers.
 */
public final class ChestSpots {

    public record Spot(int x, int y, int z) {
    }

    public record Placed(Spot spot, int tier) {
    }

    private ChestSpots() {
    }

    public static List<Spot> pick(List<Spot> candidates, double minDist, int want, Random rng) {
        List<Spot> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, rng);
        double cell = Math.max(1, minDist);
        double min2 = minDist * minDist;
        Map<Long, List<Spot>> grid = new HashMap<>();
        List<Spot> out = new ArrayList<>();
        for (Spot s : shuffled) {
            if (out.size() >= want) break;
            int gx = (int) Math.floor(s.x() / cell), gz = (int) Math.floor(s.z() / cell);
            boolean ok = true;
            outer:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<Spot> near = grid.get(key(gx + dx, gz + dz));
                    if (near == null) continue;
                    for (Spot n : near) {
                        double ddx = n.x() - s.x(), ddz = n.z() - s.z(), ddy = n.y() - s.y();
                        if (ddx * ddx + ddz * ddz + ddy * ddy < min2) {
                            ok = false;
                            break outer;
                        }
                    }
                }
            }
            if (!ok) continue;
            grid.computeIfAbsent(key(gx, gz), k -> new ArrayList<>()).add(s);
            out.add(s);
        }
        return out;
    }

    /** Assigns tiers 1..3 by share (e.g. 0.6/0.3/0.1), rounding so the counts add up exactly. */
    public static List<Placed> assignTiers(List<Spot> spots, double[] shares, Random rng) {
        int n = spots.size();
        double sum = 0;
        for (double s : shares) sum += s;
        int[] counts = new int[shares.length];
        int assigned = 0;
        for (int i = 0; i < shares.length; i++) {
            counts[i] = (int) Math.floor(n * shares[i] / sum);
            assigned += counts[i];
        }
        // Hand out the rounding remainder to the lowest tiers first.
        for (int i = 0; assigned < n; i = (i + 1) % shares.length) {
            counts[i]++;
            assigned++;
        }
        List<Integer> tiers = new ArrayList<>(n);
        for (int t = 0; t < counts.length; t++) for (int k = 0; k < counts[t]; k++) tiers.add(t + 1);
        Collections.shuffle(tiers, rng);
        List<Placed> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Placed(spots.get(i), tiers.get(i)));
        return out;
    }

    /** Chest count for a match: base + per-player, clamped. */
    public static int countFor(int players, int base, double perPlayer, int max) {
        return (int) Math.max(1, Math.min(max, Math.round(base + perPlayer * players)));
    }

    private static long key(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xffffffffL);
    }
}
