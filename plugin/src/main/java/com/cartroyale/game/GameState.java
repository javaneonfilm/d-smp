package com.cartroyale.game;

/** Section 4 state machine. IDLE and ANNOUNCED are the queue; the rest belong to a live match. */
public enum GameState {
    IDLE, ANNOUNCED, JOIN_OPEN, COUNTDOWN, PURGE, TEAMING, BOARDING, DEPLOY, PLAYING, ENDING, RESETTING;

    public boolean joinOpen() {
        return this == JOIN_OPEN || this == COUNTDOWN;
    }

    /** True once players are in the arena. */
    public boolean inArena() {
        return this == BOARDING || this == DEPLOY || this == PLAYING || this == ENDING;
    }
}
