package com.cartroyale.game;

/** A game in the database: queued, running, finished or cancelled. */
public final class GameRow {

    public enum Status { QUEUED, RUNNING, DONE, CANCELLED, MISSED }

    public long id;
    public final GameSettings s;
    public Status status = Status.QUEUED;
    public boolean warned;
    /** "schedule:&lt;entry&gt;:&lt;epoch&gt;" for scheduled games, "ticket" for bought ones, "admin" otherwise. */
    public String source;

    public GameRow(long id, GameSettings s, String source) {
        this.id = id;
        this.s = s;
        this.source = source;
    }

    public boolean bought() {
        return "ticket".equals(source);
    }
}
