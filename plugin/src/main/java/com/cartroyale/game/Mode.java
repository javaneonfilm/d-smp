package com.cartroyale.game;

/**
 * There is one game mode now (no carts): every game uses the kit and loot that used to be "LT".
 * HT stays in the enum only so old kits.yml / loot.yml / database rows still load; anything that
 * asks for a mode gets LT.
 */
public enum Mode {
    LT, HT;

    public Mode next() {
        return LT;
    }

    public static Mode parse(String s) {
        return LT;
    }
}
