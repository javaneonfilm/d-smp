package com.cartroyale.game;

import java.util.EnumSet;
import java.util.Set;

/** Optional rules a hoster can switch on. */
public enum Modifier {
    NO_FALL_DAMAGE, LUCKIER_LOOT, PEARLS;

    public static Set<Modifier> parseSet(String csv) {
        Set<Modifier> out = EnumSet.noneOf(Modifier.class);
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            try {
                out.add(valueOf(p.trim().toUpperCase().replace(' ', '_')));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public static String toCsv(Set<Modifier> set) {
        StringBuilder sb = new StringBuilder();
        for (Modifier m : values()) {
            if (!set.contains(m)) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(m.name());
        }
        return sb.toString();
    }
}
