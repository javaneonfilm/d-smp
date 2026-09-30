package com.cartroyale.game;

/** The two game modes. Each has its own kit. */
public enum Mode {
    LT, HT;

    public Mode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Mode parse(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase();
        if (t.endsWith("_CART")) t = t.substring(0, t.length() - 5);
        try {
            return valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
