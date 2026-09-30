package com.cartroyale.loot;

import java.util.Locale;

/** Shared by loot entries and cosmetics. */
public enum Rarity {
    COMMON("<gray>", "Common"),
    RARE("<blue>", "Rare"),
    EPIC("<light_purple>", "Epic"),
    LEGENDARY("<#FFD700>", "Legendary");

    public final String tag;
    public final String display;

    Rarity(String tag, String display) {
        this.tag = tag;
        this.display = display;
    }

    public Rarity next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String colored() {
        return tag + display;
    }

    public static Rarity parse(String s, Rarity def) {
        if (s == null) return def;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
