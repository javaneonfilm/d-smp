package com.cartroyale.game;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Everything a hoster picks in the Host GUI. Mutable while the GUI is open. */
public final class GameSettings {

    public Mode mode = Mode.LT;
    public final Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
    public int squad = 1;
    public int purgeCap = 100;
    /** Match length the zone is stretched to (host picks 20-40). */
    public int minutes = 30;
    /** null means a random map. */
    public String map;
    public long startAt;
    public UUID hosterUuid;
    public String hosterName = "CartZone";

    public GameSettings copy() {
        GameSettings g = new GameSettings();
        g.mode = mode;
        g.modifiers.addAll(modifiers);
        g.squad = squad;
        g.purgeCap = purgeCap;
        g.minutes = minutes;
        g.map = map;
        g.startAt = startAt;
        g.hosterUuid = hosterUuid;
        g.hosterName = hosterName;
        return g;
    }

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }
}
