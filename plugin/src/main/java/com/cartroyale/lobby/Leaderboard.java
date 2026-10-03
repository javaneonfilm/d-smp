package com.cartroyale.lobby;

import com.cartroyale.CartRoyale;
import com.cartroyale.db.Store;
import com.cartroyale.util.Keys;
import com.cartroyale.util.Locs;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three player statues in the lobby for the top three by wins: their skin, scaled 1.75 / 1.5 /
 * 1.25, with rank, name and wins floating above. Spots are set with /cart npc set <1-3>. The
 * entities are not saved with the world; they are checked every few seconds and put back when
 * missing (chunk unloaded, /kill, restart), and the names refresh every few minutes.
 */
public final class Leaderboard {

    private static final double[] SCALE = {1.75, 1.5, 1.25};
    private static final String TAG = "leaderboard";

    private final CartRoyale plugin;
    private final UUID[] statue = new UUID[3], label = new UUID[3];
    private List<Store.Winner> top = new ArrayList<>();
    /** Profiles with skin textures, looked up from Mojang once per player. */
    private final Map<UUID, com.destroystokyo.paper.profile.PlayerProfile> skins = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> looking = ConcurrentHashMap.newKeySet();

    public Leaderboard(CartRoyale plugin) {
        this.plugin = plugin;
        // Leftovers from a crash or an older build.
        for (var w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (TAG.equals(e.getPersistentDataContainer().get(Keys.ARENA_OBJECT, PersistentDataType.STRING))) e.remove();
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensure, 60, 100);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20, 20L * 60 * 5);
    }

    public Location spot(int n) {
        return Locs.read(plugin.getConfig().getString("leaderboard.spot-" + n, ""));
    }

    public void setSpot(int n, Location l) {
        plugin.getConfig().set("leaderboard.spot-" + n, Locs.write(l));
        plugin.saveConfig();
        clear(n - 1);
        ensure();
    }

    public void removeSpot(int n) {
        plugin.getConfig().set("leaderboard.spot-" + n, "");
        plugin.saveConfig();
        clear(n - 1);
    }

    /** Re-reads the top three from the database, then rebuilds the statues. Call after a game. */
    public void refresh() {
        plugin.store().topWins(3).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            top = list;
            for (int i = 0; i < 3; i++) clear(i);
            ensure();
            for (Store.Winner w : list) lookUp(w);
        }));
    }

    private void clear(int i) {
        for (UUID id : new UUID[]{statue[i], label[i]}) {
            if (id == null) continue;
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        statue[i] = null;
        label[i] = null;
    }

    /** Spawns whatever is missing, but only where the chunk is loaded (players nearby). */
    private void ensure() {
        for (int i = 0; i < 3; i++) {
            Location at = spot(i + 1);
            if (at == null || at.getWorld() == null || !at.isChunkLoaded()) continue;
            Entity s = statue[i] == null ? null : Bukkit.getEntity(statue[i]);
            Entity t = label[i] == null ? null : Bukkit.getEntity(label[i]);
            if (s != null && s.isValid() && t != null && t.isValid()) continue;
            clear(i);
            spawn(i, at);
        }
    }

    private void spawn(int i, Location at) {
        Store.Winner w = i < top.size() ? top.get(i) : null;
        double scale = SCALE[i];
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            e.setPersistent(false);
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setGravity(false);
            e.setCustomNameVisible(false);
            e.setDescription(null);
            e.getPersistentDataContainer().set(Keys.ARENA_OBJECT, PersistentDataType.STRING, TAG);
            var attr = e.getAttribute(Attribute.SCALE);
            if (attr != null) attr.setBaseValue(scale);
            if (w != null) e.setProfile(profile(w));
        });
        statue[i] = m.getUniqueId();
        // Names float a little above the head, which grows with the scale (a player is 1.8 tall).
        Location top = at.clone().add(0, 1.8 * scale + 0.45, 0);
        Component text = w == null
            ? plugin.msg().get("leaderboard.empty", "place", i + 1)
            : plugin.msg().get("leaderboard.line", "place", i + 1, "player", w.name(), "wins", w.wins(), "s", w.wins() == 1 ? "" : "s");
        TextDisplay td = at.getWorld().spawn(top, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.text(text);
            e.setBillboard(Display.Billboard.CENTER);
            e.setShadowed(true);
            e.setDefaultBackground(false);
            e.setBackgroundColor(org.bukkit.Color.fromARGB(90, 0, 0, 0));
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.getPersistentDataContainer().set(Keys.ARENA_OBJECT, PersistentDataType.STRING, TAG);
        });
        label[i] = td.getUniqueId();
    }

    /**
     * Fetches the winner's skin from Mojang off the main thread. A profile with only uuid + name
     * counts as complete to the client, which then shows Steve/Alex, so the textures have to be on it.
     */
    private void lookUp(Store.Winner w) {
        if (skins.containsKey(w.uuid()) || !looking.add(w.uuid())) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var pp = Bukkit.createProfile(w.uuid(), w.name());
                if (!pp.complete(true) || !pp.hasTextures()) {
                    // Offline-mode uuids aren't real accounts; the name usually is.
                    pp = Bukkit.createProfile(w.name());
                    pp.complete(true);
                }
                if (pp.hasTextures()) {
                    var found = pp;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        skins.put(w.uuid(), found);
                        for (int i = 0; i < 3; i++) {
                            if (i < top.size() && top.get(i).uuid().equals(w.uuid())) clear(i);
                        }
                        ensure();
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Skin lookup for " + w.name() + " failed: " + e.getMessage());
            } finally {
                looking.remove(w.uuid());
            }
        });
    }

    /** The skin: the fetched profile, the live one if they're online, else just the name for the client to look up. */
    private ResolvableProfile profile(Store.Winner w) {
        var pp = skins.get(w.uuid());
        if (pp == null) {
            Player p = Bukkit.getPlayer(w.uuid());
            if (p != null && p.getPlayerProfile().hasTextures()) pp = p.getPlayerProfile();
        }
        if (pp != null) return ResolvableProfile.resolvableProfile(pp);
        return ResolvableProfile.resolvableProfile().name(w.name()).build();
    }
}
