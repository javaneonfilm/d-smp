package com.cartroyale.hooks;

import com.cartroyale.CartRoyale;
import com.cartroyale.util.Items;
import com.cartroyale.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps Vulcan (AntiGamingChair) quiet where CartZone moves people itself: the whole lobby
 * (elytra perk, grapple, wind charges, void rescue), the deploy glide, and a few seconds after any
 * plugin launch. Vulcan's flag, setback and punish events are cancelled for those players.
 * Hooked by reflection so the plugin needs no Vulcan jar to build or run.
 */
public final class VulcanHook implements Listener {

    private static final String[] EVENTS = {
        "me.frep.vulcan.api.event.VulcanFlagEvent",
        "me.frep.vulcan.api.event.VulcanSetbackEvent",
        "me.frep.vulcan.api.event.VulcanPunishEvent",
    };

    private final CartRoyale plugin;
    private final Map<UUID, Long> launched = new ConcurrentHashMap<>();
    private int hooked;

    public VulcanHook(CartRoyale plugin) {
        this.plugin = plugin;
    }

    /** Registers on whatever Vulcan events exist; returns how many. */
    public int register() {
        Plugin vulcan = Bukkit.getPluginManager().getPlugin("Vulcan");
        if (vulcan == null || !vulcan.isEnabled()) return 0;
        for (String name : EVENTS) {
            try {
                Class<?> c = Class.forName(name, true, vulcan.getClass().getClassLoader());
                if (!Event.class.isAssignableFrom(c)) continue;
                Method getPlayer = c.getMethod("getPlayer");
                Method setCancelled = c.getMethod("setCancelled", boolean.class);
                @SuppressWarnings("unchecked")
                Class<? extends Event> ec = (Class<? extends Event>) c;
                Bukkit.getPluginManager().registerEvent(ec, this, EventPriority.HIGHEST, (l, e) -> {
                    if (!ec.isInstance(e)) return;
                    try {
                        if (getPlayer.invoke(e) instanceof Player p && exempt(p)) setCancelled.invoke(e, true);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }, plugin);
                hooked++;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // This Vulcan version doesn't have that event.
            }
        }
        return hooked;
    }

    /** Call after the plugin flings a player (grapple, wind burst, drop). */
    public void launched(Player p) {
        launched.put(p.getUniqueId(), System.currentTimeMillis());
    }

    public void quit(Player p) {
        launched.remove(p.getUniqueId());
    }

    public boolean exempt(Player p) {
        var cfg = plugin.getConfig();
        if (cfg.getBoolean("anticheat.exempt-lobby", true) && plugin.lobby().isLobby(p.getWorld())) return true;
        if (Items.hasFlag(p.getInventory().getChestplate(), Keys.TEMP_ELYTRA)) return true;
        Long at = launched.get(p.getUniqueId());
        return at != null && System.currentTimeMillis() - at < cfg.getLong("anticheat.launch-exempt-ms", 4000);
    }
}
