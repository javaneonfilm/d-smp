package com.cartroyale.zone;

import com.cartroyale.CartRoyale;
import com.cartroyale.util.Keys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The hexagon zone wall from the resource pack (resourcepack/zone-wall). The pack's shader turns one
 * flat quad into a cylinder: quad centre = circle centre, quad side = radius, wall runs 350 up.
 *
 * Each player gets their own display, seen only by them, parked near them so it stays inside
 * entity tracking range on a big map; a translation in the transformation puts the quad on the
 * real circle. When the player wanders off, a fresh display replaces it (the old one goes a tick
 * later so the wall never blinks).
 */
final class ZoneWall {

    private static final String TAG = "zone-wall";

    private final CartRoyale plugin;
    private final Map<UUID, ItemDisplay> walls = new HashMap<>();
    private final Map<UUID, ItemDisplay> retiring = new HashMap<>();
    private final Map<UUID, float[]> sent = new HashMap<>();
    private String model = "lodestone:border";

    ZoneWall(CartRoyale plugin) {
        this.plugin = plugin;
    }

    void model(String model) {
        this.model = model;
    }

    /** Called once per tick before update(): removes displays replaced last tick. */
    void beginTick() {
        for (ItemDisplay d : retiring.values()) d.remove();
        retiring.clear();
    }

    /** Draws the wall for one player. False when they can't see shader walls (no pack), so the caller falls back to particles. */
    boolean update(Player p, ZoneMath.Circle c, double baseY, double reanchor) {
        if (!plugin.presence().packLoaded(p.getUniqueId())) {
            remove(p.getUniqueId());
            return false;
        }
        Location l = p.getLocation();
        ItemDisplay d = walls.get(p.getUniqueId());
        if (d == null || !d.isValid() || !d.getWorld().equals(l.getWorld())
            || Math.hypot(d.getX() - l.getX(), d.getZ() - l.getZ()) > reanchor || Math.abs(d.getY() - l.getY()) > reanchor) {
            if (d != null) retiring.put(p.getUniqueId(), d);
            d = spawn(p, l.getWorld(), new Location(l.getWorld(), l.getX(), l.getY(), l.getZ(), 0, 0), c, baseY);
            walls.put(p.getUniqueId(), d);
            return true;
        }
        float[] t = shape(d, c, baseY);
        float[] last = sent.get(p.getUniqueId());
        if (last == null || !java.util.Arrays.equals(last, t)) {
            d.setTransformation(transformation(t));
            d.setInterpolationDelay(0); // glide to the new size over one tick instead of stepping
            sent.put(p.getUniqueId(), t);
        }
        return true;
    }

    /** Drops displays for players that weren't drawn this tick (left the world, died to spectator elsewhere...). */
    void retain(Set<UUID> seen) {
        for (Iterator<Map.Entry<UUID, ItemDisplay>> it = walls.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, ItemDisplay> e = it.next();
            if (!seen.contains(e.getKey())) {
                e.getValue().remove();
                sent.remove(e.getKey());
                it.remove();
            }
        }
    }

    void remove(UUID u) {
        ItemDisplay d = walls.remove(u);
        if (d != null) d.remove();
        sent.remove(u);
    }

    void clear() {
        for (ItemDisplay d : walls.values()) d.remove();
        for (ItemDisplay d : retiring.values()) d.remove();
        walls.clear();
        retiring.clear();
        sent.clear();
    }

    int count() {
        return walls.size();
    }

    private ItemDisplay spawn(Player p, World w, Location at, ZoneMath.Circle c, double baseY) {
        ItemDisplay d = w.spawn(at, ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(Keys.ARENA_OBJECT, PersistentDataType.STRING, TAG);
            e.setItemStack(item());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(8f);
            e.setShadowRadius(0f);
            e.setTeleportDuration(0);
            e.setInterpolationDuration(1);
            float[] t = new float[]{(float) (c.x() - at.getX()), (float) (baseY - at.getY()), (float) (c.z() - at.getZ()), (float) Math.max(0.01, c.r())};
            e.setTransformation(transformation(t));
            sent.put(p.getUniqueId(), t);
        });
        p.showEntity(plugin, d);
        return d;
    }

    private static float[] shape(ItemDisplay d, ZoneMath.Circle c, double baseY) {
        return new float[]{(float) (c.x() - d.getX()), (float) (baseY - d.getY()), (float) (c.z() - d.getZ()), (float) Math.max(0.01, c.r())};
    }

    private static Transformation transformation(float[] t) {
        return new Transformation(new Vector3f(t[0], t[1], t[2]), new AxisAngle4f(), new Vector3f(t[3], 1f, t[3]), new AxisAngle4f());
    }

    private ItemStack item() {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            NamespacedKey key = NamespacedKey.fromString(model);
            if (key != null) m.setItemModel(key);
        });
        return it;
    }
}
