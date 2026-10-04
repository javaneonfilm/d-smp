package com.cartroyale.rank;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * Every perk is its own permission node, so ranks are built in LuckPerms (or any permissions
 * plugin) by giving a group the nodes it should have. cartzone.rank.vip / vipplus in plugin.yml
 * are only ready-made bundles of these nodes. The plugin never links against the LuckPerms API:
 * temporary groups are granted by dispatching LuckPerms' own console command, so the jar also
 * starts on a server without LuckPerms.
 */
public final class Ranks {

    public static final String ADMIN = "cartzone.admin";
    public static final String PURGE_IMMUNE = "cartzone.perk.purge-immune";
    public static final String LOBBY_WIND = "cartzone.perk.lobby-wind";
    public static final String LOBBY_ELYTRA = "cartzone.perk.lobby-elytra";

    private Ranks() {
    }

    public static boolean isAdmin(Player p) {
        return p.hasPermission(ADMIN);
    }

    /** Perk nodes default to false, so an op only has them when a group grants them. */
    public static boolean has(Player p, String perk) {
        return p.isPermissionSet(perk) && p.hasPermission(perk);
    }

    /**
     * Purge weight: the lowest weight among the purge.weight-perms nodes the player has, else
     * purge.weights.default. Lower = less likely to be purged.
     */
    public static double purgeWeight(Player p, FileConfiguration cfg) {
        double w = cfg.getDouble("purge.weights.default", 1.0);
        var sec = cfg.getConfigurationSection("purge.weight-perms");
        if (sec == null) return w;
        for (Map.Entry<String, Object> e : sec.getValues(false).entrySet()) {
            if (e.getValue() instanceof Number n && has(p, e.getKey())) w = Math.min(w, n.doubleValue());
        }
        return w;
    }

    public static boolean luckPerms() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    /** Grants a temporary LuckPerms group, if LuckPerms is installed. Returns whether it ran. */
    public static boolean addTempGroup(UUID uuid, String group, int hours) {
        if (!luckPerms() || group == null || group.isBlank()) return false;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "lp user " + uuid + " parent addtemp " + group + " " + hours + "h accumulate");
    }

    /** Temporary group for a set number of minutes, replacing any time left on it. */
    public static boolean addTempGroupMinutes(UUID uuid, String group, int minutes) {
        if (!luckPerms() || group == null || group.isBlank()) return false;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "lp user " + uuid + " parent addtemp " + group + " " + Math.max(1, minutes) + "m replace");
    }

    public static boolean removeGroup(UUID uuid, String group) {
        if (!luckPerms() || group == null || group.isBlank()) return false;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + uuid + " parent removetemp " + group)
            | Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + uuid + " parent remove " + group);
    }
}
