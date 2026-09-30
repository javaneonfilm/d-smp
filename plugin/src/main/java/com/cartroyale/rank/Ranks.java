package com.cartroyale.rank;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Ranks come from permission nodes, which LuckPerms grants through groups. The plugin never
 * links against the LuckPerms API: temporary groups are granted by dispatching LuckPerms' own
 * console command, so the jar also starts on a server without LuckPerms.
 */
public final class Ranks {

    public enum Rank { DEFAULT, VIP, VIPPLUS, ADMIN }

    public static final String ADMIN = "cartzone.admin";
    public static final String VIP = "cartzone.rank.vip";
    public static final String VIPPLUS = "cartzone.rank.vipplus";

    private Ranks() {
    }

    public static Rank of(Player p) {
        if (p.hasPermission(ADMIN)) return Rank.ADMIN;
        if (p.hasPermission(VIPPLUS)) return Rank.VIPPLUS;
        if (p.hasPermission(VIP)) return Rank.VIP;
        return Rank.DEFAULT;
    }

    /**
     * Bought rank ignoring admin, for tags and lobby perks. The rank nodes default to false in
     * plugin.yml, so an op only has them when a group grants them.
     */
    public static Rank bought(Player p) {
        if (p.hasPermission(VIPPLUS)) return Rank.VIPPLUS;
        if (p.hasPermission(VIP)) return Rank.VIP;
        return Rank.DEFAULT;
    }

    public static boolean isAdmin(Player p) {
        return p.hasPermission(ADMIN);
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

    public static boolean removeGroup(UUID uuid, String group) {
        if (!luckPerms() || group == null || group.isBlank()) return false;
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + uuid + " parent removetemp " + group)
            | Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + uuid + " parent remove " + group);
    }
}
