package com.cartroyale.arena;

import com.cartroyale.core.Yml;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-map setup, stored in maps/&lt;name&gt;.yml: the arena centre and border, the spectator
 * spawn, the ghast route, the zone's start circle and final centre, and chest include/exclude
 * circles. Everything is set in game with /cart arena, /cart zone and /cart chests.
 */
public final class MapConfig {

    public record Circle(double x, double z, double r) {
        public boolean contains(double px, double pz) {
            double dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= r * r;
        }

        String write() {
            return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", x, z, r);
        }

        static Circle read(String s) {
            String[] p = s.split(",");
            try {
                return new Circle(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public final String name;
    private final Yml yml;

    public double centerX, centerZ, borderRadius = 300;
    public double spawnX, spawnY = 100, spawnZ;
    public float spawnYaw;
    public double deployX = -250, deployZ, deployY = 140;
    public float deployYaw = -90;
    public double zoneX, zoneZ, zoneR = 300;
    public double finalX = 40, finalZ = 30;
    public int chestMinY = -64, chestMaxY = 320;
    public String generator = "";
    public final List<Circle> include = new ArrayList<>();
    public final List<Circle> exclude = new ArrayList<>();

    public MapConfig(Yml yml, String name) {
        this.yml = yml;
        this.name = name;
        load();
    }

    private void load() {
        YamlConfiguration c = yml.get();
        centerX = c.getDouble("center.x", centerX);
        centerZ = c.getDouble("center.z", centerZ);
        borderRadius = c.getDouble("border-radius", borderRadius);
        spawnX = c.getDouble("spawn.x", centerX);
        spawnY = c.getDouble("spawn.y", spawnY);
        spawnZ = c.getDouble("spawn.z", centerZ);
        spawnYaw = (float) c.getDouble("spawn.yaw", 0);
        deployX = c.getDouble("deploy.x", deployX);
        deployZ = c.getDouble("deploy.z", deployZ);
        deployY = c.getDouble("deploy.y", deployY);
        deployYaw = (float) c.getDouble("deploy.yaw", deployYaw);
        zoneX = c.getDouble("zone.start.x", centerX);
        zoneZ = c.getDouble("zone.start.z", centerZ);
        zoneR = c.getDouble("zone.start.r", borderRadius);
        finalX = c.getDouble("zone.final.x", finalX);
        finalZ = c.getDouble("zone.final.z", finalZ);
        chestMinY = c.getInt("chests.min-y", chestMinY);
        chestMaxY = c.getInt("chests.max-y", chestMaxY);
        generator = c.getString("generator", "");
        include.clear();
        exclude.clear();
        for (String s : c.getStringList("chests.include")) {
            Circle ci = Circle.read(s);
            if (ci != null) include.add(ci);
        }
        for (String s : c.getStringList("chests.exclude")) {
            Circle ci = Circle.read(s);
            if (ci != null) exclude.add(ci);
        }
    }

    public void save() {
        YamlConfiguration c = yml.get();
        c.options().setHeader(List.of(
            "CartZone map setup for '" + name + "'. Set these in game:",
            "/cart arena setcenter, setborder <radius>, setspawn, setdeploy",
            "/cart zone start <radius>, /cart zone final, /cart chests include|exclude <radius>"));
        c.set("center.x", centerX);
        c.set("center.z", centerZ);
        c.set("border-radius", borderRadius);
        c.set("spawn.x", spawnX);
        c.set("spawn.y", spawnY);
        c.set("spawn.z", spawnZ);
        c.set("spawn.yaw", spawnYaw);
        c.set("deploy.x", deployX);
        c.set("deploy.z", deployZ);
        c.set("deploy.y", deployY);
        c.set("deploy.yaw", deployYaw);
        c.set("zone.start.x", zoneX);
        c.set("zone.start.z", zoneZ);
        c.set("zone.start.r", zoneR);
        c.set("zone.final.x", finalX);
        c.set("zone.final.z", finalZ);
        c.set("chests.min-y", chestMinY);
        c.set("chests.max-y", chestMaxY);
        c.set("generator", generator);
        c.set("chests.include", include.stream().map(Circle::write).toList());
        c.set("chests.exclude", exclude.stream().map(Circle::write).toList());
        yml.save();
    }

    public Location spawn(World w) {
        return new Location(w, spawnX, spawnY, spawnZ, spawnYaw, 0);
    }

    /** Whether a column may hold a chest: inside the border, inside an include circle (if any), outside every exclude. */
    public boolean chestAllowed(double x, double z) {
        if (!insideBorder(x, z, -4)) return false;
        if (!include.isEmpty() && include.stream().noneMatch(c -> c.contains(x, z))) return false;
        return exclude.stream().noneMatch(c -> c.contains(x, z));
    }

    /** The vanilla world border is a square, so this is too. */
    public boolean insideBorder(double x, double z, double margin) {
        double r = borderRadius + margin;
        return Math.abs(x - centerX) <= r && Math.abs(z - centerZ) <= r;
    }

    public ConfigurationSection raw() {
        return yml.get();
    }
}
