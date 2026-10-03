package com.cartroyale.kit;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import com.cartroyale.game.Mode;
import com.cartroyale.util.Items;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * KIT-1..4. One kit per mode, stored serialized in kits.yml. /kitready empties an admin's
 * inventory (after a confirm) so a kit can be built from scratch; /cart kit set saves it.
 */
public final class KitManager {

    private final CartRoyale plugin;
    private final Yml yml;
    private final Map<UUID, Long> confirm = new HashMap<>();

    public KitManager(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "kits.yml", false);
    }

    public void reload() {
        yml.reload();
    }

    public boolean has(Mode m) {
        return yml.get().contains(key(m) + ".contents");
    }

    private static String key(Mode m) {
        return "kits." + m.name().toLowerCase();
    }

    public void save(Mode m, Player from) {
        PlayerInventory inv = from.getInventory();
        yml.get().set(key(m) + ".contents", Items.arrToB64(inv.getStorageContents()));
        yml.get().set(key(m) + ".armor", Items.arrToB64(inv.getArmorContents()));
        yml.get().set(key(m) + ".offhand", Items.toB64(inv.getItemInOffHand()));
        yml.get().set(key(m) + ".saved-by", from.getName());
        yml.get().set(key(m) + ".saved-at", System.currentTimeMillis());
        yml.save();
    }

    /** Clears the player and gives the kit. Armor and tools can wear out and break. */
    public void apply(Player p, Mode m) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        if (!has(m)) {
            plugin.adminAlert("kits.missing", "mode", m.name());
            return;
        }
        ItemStack[] storage = Items.arrFromB64(yml.get().getString(key(m) + ".contents"), inv.getStorageContents().length);
        ItemStack[] armor = Items.arrFromB64(yml.get().getString(key(m) + ".armor"), 4);
        for (int i = 0; i < storage.length; i++) storage[i] = prep(storage[i]);
        for (int i = 0; i < armor.length; i++) armor[i] = prep(armor[i]);
        inv.setStorageContents(storage);
        inv.setArmorContents(armor);
        inv.setItemInOffHand(prep(Items.fromB64(yml.get().getString(key(m) + ".offhand"))));
    }

    @SuppressWarnings("UnstableApiUsage")
    public static ItemStack prep(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return null;
        return Items.breakable(it);
    }

    public ItemStack[] preview(Mode m) {
        ItemStack[] out = new ItemStack[41];
        ItemStack[] storage = Items.arrFromB64(yml.get().getString(key(m) + ".contents"), 36);
        ItemStack[] armor = Items.arrFromB64(yml.get().getString(key(m) + ".armor"), 4);
        System.arraycopy(storage, 0, out, 0, 36);
        System.arraycopy(armor, 0, out, 36, 4);
        out[40] = Items.fromB64(yml.get().getString(key(m) + ".offhand"));
        return out;
    }

    /** /kitready: first call asks, a second call within 10 seconds clears. */
    public void kitReady(Player p) {
        Long at = confirm.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (at == null || now - at > 10_000) {
            confirm.put(p.getUniqueId(), now);
            plugin.msg().send(p, "kits.ready-confirm");
            return;
        }
        confirm.remove(p.getUniqueId());
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        p.setItemOnCursor(null);
        plugin.lobby().suspendHotbar(p.getUniqueId(), true);
        plugin.msg().send(p, "kits.ready-done");
    }
}
