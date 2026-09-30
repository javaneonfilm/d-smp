package com.cartroyale.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Stores locations in YAML as "world,x,y,z,yaw,pitch". */
public final class Locs {

    private Locs() {
    }

    public static String write(Location l) {
        return String.format(java.util.Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f",
            l.getWorld() == null ? "" : l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    public static Location read(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            float yaw = p.length > 4 ? Float.parseFloat(p[4]) : 0, pitch = p.length > 5 ? Float.parseFloat(p[5]) : 0;
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Location read(ConfigurationSection s, String path) {
        return read(s.getString(path));
    }

    public static String pretty(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }
}
