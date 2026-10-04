package com.cartroyale.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A live game: from the join window to the reset. */
public final class Match {

    public static final class MP {
        public final UUID uuid;
        public final String name;
        public int team = -1;
        public boolean alive = true;
        public int kills;
        public int placement;
        public UUID lastDamager;
        public long lastDamageAt;

        MP(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    public final GameRow row;
    public GameState state = GameState.JOIN_OPEN;
    public long stateAt = System.currentTimeMillis();
    public final Map<UUID, MP> players = new LinkedHashMap<>();
    public final List<List<UUID>> teams = new ArrayList<>();
    /** Players who were purged or disconnected and may not rejoin as a player. */
    public final Set<UUID> removed = new HashSet<>();
    public String map;
    public long startedAt;
    public long playingAt;
    /** The second set of chests (chests.second-wave) has been placed. */
    public boolean lootWave;
    public int onlineAtPurge;
    public int placementCounter;
    public boolean purged;

    public Match(GameRow row) {
        this.row = row;
    }

    public long id() {
        return row.id;
    }

    public GameSettings s() {
        return row.s;
    }

    public MP add(UUID uuid, String name) {
        return players.computeIfAbsent(uuid, u -> new MP(u, name));
    }

    public MP get(UUID uuid) {
        return players.get(uuid);
    }

    public boolean isAlive(UUID uuid) {
        MP mp = players.get(uuid);
        return mp != null && mp.alive;
    }

    public boolean sameTeam(UUID a, UUID b) {
        MP x = players.get(a), y = players.get(b);
        return x != null && y != null && x.team >= 0 && x.team == y.team;
    }

    public List<Integer> aliveTeams() {
        List<Integer> out = new ArrayList<>();
        for (int t = 0; t < teams.size(); t++) {
            for (UUID u : teams.get(t)) {
                if (isAlive(u)) {
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    public int aliveCount() {
        int n = 0;
        for (MP mp : players.values()) if (mp.alive) n++;
        return n;
    }

    public void enter(GameState s) {
        state = s;
        stateAt = System.currentTimeMillis();
    }

    public long inStateMs() {
        return System.currentTimeMillis() - stateAt;
    }
}
