package com.cartroyale.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Every PersistentDataContainer key the plugin uses. */
public final class Keys {

    public static NamespacedKey ITEM;          // string: id of a plugin item (grapple, cosmetics, shield...)
    public static NamespacedKey TEMP_ELYTRA;   // byte: the deploy elytra
    public static NamespacedKey STORED_CHEST;  // bytes: serialized chestplate kept while gliding
    public static NamespacedKey CART_OWNER;    // string uuid: who placed/ignited a TNT minecart
    public static NamespacedKey ARENA_OBJECT;  // string game id: chests' holograms, ghasts, anything to sweep
    public static NamespacedKey LOBBY_ITEM;    // byte: lobby hotbar item, never droppable

    private Keys() {
    }

    public static void init(Plugin p) {
        ITEM = new NamespacedKey(p, "item");
        TEMP_ELYTRA = new NamespacedKey(p, "temp_elytra");
        STORED_CHEST = new NamespacedKey(p, "stored_chest");
        CART_OWNER = new NamespacedKey(p, "cart_owner");
        ARENA_OBJECT = new NamespacedKey(p, "arena_object");
        LOBBY_ITEM = new NamespacedKey(p, "lobby_item");
    }
}
