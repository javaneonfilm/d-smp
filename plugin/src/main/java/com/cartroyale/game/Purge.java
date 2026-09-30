package com.cartroyale.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Picks which players are removed when the lobby is over the purge cap. Weighted random
 * sampling without replacement (Efraimidis-Spirakis): every candidate gets the key
 * {@code -ln(u) / weight} and the smallest keys are removed first, so a weight of 0.15 is
 * removed much less often than a weight of 1.0. Immune candidates are only touched when the
 * immune players alone exceed the cap.
 */
public final class Purge {

    public record Candidate<T>(T id, double weight, boolean immune) {
    }

    public record Result<T>(List<T> kept, List<T> purged, boolean immuneOverflow) {
    }

    private Purge() {
    }

    public static <T> Result<T> run(List<Candidate<T>> all, int cap, Random rng) {
        if (cap < 0) cap = 0;
        if (all.size() <= cap) {
            List<T> kept = new ArrayList<>();
            for (Candidate<T> c : all) kept.add(c.id());
            return new Result<>(kept, List.of(), false);
        }
        List<Candidate<T>> immune = new ArrayList<>();
        List<Candidate<T>> normal = new ArrayList<>();
        for (Candidate<T> c : all) (c.immune() ? immune : normal).add(c);

        List<T> kept = new ArrayList<>();
        List<T> purged = new ArrayList<>();
        boolean overflow = false;

        if (immune.size() >= cap) {
            // Edge case from D3: immune players alone exceed the cap. Pick among them uniformly.
            overflow = immune.size() > cap;
            List<Candidate<T>> shuffled = new ArrayList<>(immune);
            java.util.Collections.shuffle(shuffled, rng);
            for (int i = 0; i < shuffled.size(); i++) (i < cap ? kept : purged).add(shuffled.get(i).id());
            for (Candidate<T> c : normal) purged.add(c.id());
            return new Result<>(kept, purged, overflow);
        }

        for (Candidate<T> c : immune) kept.add(c.id());
        int remove = all.size() - cap;
        record Keyed<T>(Candidate<T> c, double key) {
        }
        List<Keyed<T>> keyed = new ArrayList<>(normal.size());
        for (Candidate<T> c : normal) {
            double w = Math.max(c.weight(), 1e-9);
            double u = rng.nextDouble();
            if (u <= 0) u = Double.MIN_VALUE;
            keyed.add(new Keyed<>(c, -Math.log(u) / w));
        }
        // The smallest keys form a weighted sample of size `remove`: a high weight gives a small key,
        // so a default player (1.0) is removed far more often than a VIP (0.15).
        keyed.sort(Comparator.comparingDouble(Keyed::key));
        for (int i = 0; i < keyed.size(); i++) (i < remove ? purged : kept).add(keyed.get(i).c().id());
        return new Result<>(kept, purged, false);
    }
}
