package com.cartroyale.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Base64;
import java.util.List;

public final class Items {

    private Items() {
    }

    public static ItemStack make(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static ItemStack tagged(ItemStack it, String id) {
        it.editMeta(meta -> meta.getPersistentDataContainer().set(Keys.ITEM, PersistentDataType.STRING, id));
        return it;
    }

    public static String id(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.ITEM, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack it, String id) {
        return id.equals(id(it));
    }

    public static boolean hasFlag(ItemStack it, org.bukkit.NamespacedKey key) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key);
    }

    public static ItemStack flag(ItemStack it, org.bukkit.NamespacedKey key) {
        it.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    public static boolean isArmor(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (it.getType() == Material.ELYTRA) return false;
        EquipmentSlot s = it.getType().getEquipmentSlot();
        return (s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST || s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET)
            && it.getType() != Material.CARVED_PUMPKIN && !it.getType().name().endsWith("_HEAD") && !it.getType().name().endsWith("_SKULL");
    }

    public static String toB64(ItemStack it) {
        if (it == null || it.getType().isAir()) return "";
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    public static ItemStack fromB64(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String arrToB64(ItemStack[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(toB64(arr[i]));
        }
        return sb.toString();
    }

    public static ItemStack[] arrFromB64(String s, int len) {
        ItemStack[] out = new ItemStack[len];
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split(";", -1);
        for (int i = 0; i < Math.min(len, parts.length); i++) out[i] = fromB64(parts[i]);
        return out;
    }
}
