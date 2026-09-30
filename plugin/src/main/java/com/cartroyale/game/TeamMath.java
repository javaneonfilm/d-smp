package com.cartroyale.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Fills partial teams and groups leftover players at the end of the team-up phase. */
public final class TeamMath {

    private TeamMath() {
    }

    /**
     * @param formed teams the players built themselves (each list at most {@code size})
     * @param solo   players without a team
     * @return every team, full where possible; only the last one may be smaller
     */
    public static <T> List<List<T>> autofill(List<List<T>> formed, List<T> solo, int size, Random rng) {
        List<List<T>> teams = new ArrayList<>();
        for (List<T> t : formed) if (!t.isEmpty()) teams.add(new ArrayList<>(t));
        List<T> pool = new ArrayList<>(solo);
        java.util.Collections.shuffle(pool, rng);
        if (size <= 1) {
            for (List<T> t : teams) for (T p : t) pool.add(p);
            List<List<T>> out = new ArrayList<>();
            for (T p : pool) out.add(new ArrayList<>(List.of(p)));
            return out;
        }
        // Top up the fullest partial teams first so friends who teamed up stay together.
        teams.sort(Comparator.comparingInt((List<T> t) -> t.size()).reversed());
        for (List<T> t : teams) {
            while (t.size() < size && !pool.isEmpty()) t.add(pool.remove(pool.size() - 1));
        }
        while (!pool.isEmpty()) {
            List<T> t = new ArrayList<>();
            while (t.size() < size && !pool.isEmpty()) t.add(pool.remove(pool.size() - 1));
            teams.add(t);
        }
        return teams;
    }
}
