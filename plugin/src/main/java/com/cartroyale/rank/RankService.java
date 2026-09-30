package com.cartroyale.rank;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Admin toggles that only live while the server runs: build mode and free roam. */
public final class RankService {

    private final Set<UUID> build = new HashSet<>();
    private final Set<UUID> free = new HashSet<>();

    public boolean toggleBuild(Player p) {
        if (!build.remove(p.getUniqueId())) {
            build.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    public boolean canBuild(Player p) {
        return build.contains(p.getUniqueId()) && Ranks.isAdmin(p);
    }

    /** Admin spectators who toggled free roam are not confined to the arena. */
    public boolean isAdminFree(Player p) {
        return free.contains(p.getUniqueId()) && Ranks.isAdmin(p);
    }

    public boolean toggleFree(Player p) {
        if (!free.remove(p.getUniqueId())) {
            free.add(p.getUniqueId());
            return true;
        }
        return false;
    }
}
