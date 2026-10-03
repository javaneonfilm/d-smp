package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.util.Items;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Loot books that use themselves up the moment they land in a player's inventory in the arena:
 * the Protection Book adds a Protection level to every worn armor piece (none, then I up to V,
 * books.protection-max), Sharpness and Quick Charge add a level to a sword/axe/spear or crossbow.
 * A book that has nothing to work on yet (no crossbow, armor maxed) waits in the inventory and
 * fires as soon as it can. Kills upgrade the killer's armor the same way (upgrades.armor-on-kill).
 * The ARMOR kind keeps its old id so existing loot tables still hand it out.
 * In the lobby books stay plain items, so admins can carry them into the loot editor.
 */
public final class UpgradeBooks implements Listener {

    public enum Kind {
        ARMOR("armor_upgrade_book", "armor"), SHARPNESS("sharpness_book", "sharpness"), QUICKCHARGE("quickcharge_book", "quickcharge");

        public final String id, msg;

        Kind(String id, String msg) {
            this.id = id;
            this.msg = msg;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equalsIgnoreCase(id)) return k;
            return null;
        }
    }

    private final CartRoyale plugin;
    private final Set<UUID> queued = new HashSet<>();
    private final Set<String> warned = new HashSet<>();

    public UpgradeBooks(CartRoyale plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ items

    public static ItemStack create(CartRoyale plugin, Kind k, int amount) {
        ItemStack it = new ItemStack(Material.BOOK);
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        if (plugin.packOn()) it.setData(DataComponentTypes.ITEM_MODEL, Key.key("cartzone", k.id));
        it.setData(DataComponentTypes.ITEM_NAME, plugin.msg().item("items.books." + k.msg + ".name"));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(plugin.msg().lore("items.books." + k.msg + ".lore")));
        it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        Items.tagged(it, k.id);
        it.setAmount(Math.max(1, Math.min(16, amount)));
        return it;
    }

    public static Kind kind(ItemStack it) {
        String id = Items.id(it);
        return id == null ? null : Kind.of(id);
    }

    // ------------------------------------------------------------------ triggers

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) queue(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) queue(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) queue(p);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) queue(p);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        queue(e.getPlayer());
    }

    /** Checks the inventory next tick (after the click/pickup has landed), once per tick per player. */
    public void queue(Player p) {
        if (!queued.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            queued.remove(p.getUniqueId());
            if (p.isOnline()) apply(p);
        });
    }

    private void apply(Player p) {
        if (!plugin.arenas().isArena(p.getWorld())) return;
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            Kind k = kind(it);
            if (k == null) continue;
            while (it.getAmount() > 0) {
                String result = use(p, k);
                if (result == null) {
                    if (warned.add(p.getUniqueId() + k.id)) plugin.msg().send(p, "books." + k.msg + "-waiting");
                    break;
                }
                warned.remove(p.getUniqueId() + k.id);
                it.setAmount(it.getAmount() - 1);
                plugin.msg().send(p, "books." + k.msg + "-applied", "item", result);
                p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
            }
            inv.setItem(slot, it.getAmount() > 0 ? it : null);
        }
    }

    /** Applies one book. Returns a short description of what changed, or null if nothing could take it. */
    private String use(Player p, Kind k) {
        var cfg = plugin.getConfig();
        return switch (k) {
            case ARMOR -> upgradeArmor(p);
            case SHARPNESS -> enchant(p, Enchantment.SHARPNESS, cfg.getInt("books.sharpness-max", 5),
                m -> m.endsWith("_SWORD") || m.endsWith("_AXE") || m.endsWith("_SPEAR"));
            case QUICKCHARGE -> enchant(p, Enchantment.QUICK_CHARGE, cfg.getInt("books.quickcharge-max", 3), m -> m.equals("CROSSBOW"));
        };
    }

    /** Held item first, then the hotbar, then the rest; the first one below the cap gets +1. */
    private String enchant(Player p, Enchantment ench, int cap, java.util.function.Predicate<String> fits) {
        PlayerInventory inv = p.getInventory();
        List<Integer> order = new ArrayList<>();
        order.add(inv.getHeldItemSlot());
        for (int i = 0; i < 36; i++) if (i != inv.getHeldItemSlot()) order.add(i);
        order.add(40); // offhand
        for (int slot : order) {
            ItemStack it = inv.getItem(slot);
            if (it == null || !fits.test(it.getType().name())) continue;
            int lvl = it.getEnchantmentLevel(ench);
            if (lvl >= cap) continue;
            it.addUnsafeEnchantment(ench, lvl + 1);
            inv.setItem(slot, it);
            return plugin.msg().plain("books.level", "item", pretty(it.getType()), "enchant", ench == Enchantment.SHARPNESS ? "Sharpness" : "Quick Charge", "level", roman(lvl + 1));
        }
        return null;
    }

    /** +1 Protection on every worn armor piece below the cap. Returns e.g. "Protection III", or null if nothing changed. */
    public String upgradeArmor(Player p) {
        int cap = plugin.getConfig().getInt("books.protection-max", 5);
        PlayerInventory inv = p.getInventory();
        int best = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            int lvl = it.getEnchantmentLevel(Enchantment.PROTECTION);
            if (lvl >= cap) continue;
            it.addUnsafeEnchantment(Enchantment.PROTECTION, lvl + 1);
            inv.setItem(slot, it);
            best = Math.max(best, lvl + 1);
        }
        return best == 0 ? null : plugin.msg().plain("books.armor-tier", "tier", "Protection " + roman(best));
    }

    /** upgrades.armor-on-kill: a kill gives the killer's armor +1 Protection. */
    public void onKill(Player killer) {
        if (!plugin.getConfig().getBoolean("upgrades.armor-on-kill", true)) return;
        String tier = upgradeArmor(killer);
        if (tier == null) return;
        plugin.msg().send(killer, "books.kill-upgrade", "item", tier);
        killer.playSound(killer.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 1.1f);
    }

    /**
     * upgrades.repair-on-kill: the killer's armor soaks up what was left of the victim's, slot by
     * slot. A victim piece at 60% repairs the killer's piece in the same slot by 60% of its max.
     */
    public void repairFrom(Player killer, String victim, ItemStack[] victimArmor) {
        if (!plugin.getConfig().getBoolean("upgrades.repair-on-kill", true) || victimArmor == null) return;
        ItemStack[] mine = killer.getInventory().getArmorContents();
        int restored = 0, total = 0;
        for (int i = 0; i < Math.min(mine.length, victimArmor.length); i++) {
            ItemStack k = mine[i], v = victimArmor[i];
            if (k == null || v == null || !(k.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable km)) continue;
            int kMax = km.hasMaxDamage() ? km.getMaxDamage() : k.getType().getMaxDurability();
            if (kMax <= 0 || km.getDamage() <= 0) continue;
            double left = 1.0;
            if (v.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable vm) {
                int vMax = vm.hasMaxDamage() ? vm.getMaxDamage() : v.getType().getMaxDurability();
                if (vMax > 0) left = Math.max(0, vMax - vm.getDamage()) / (double) vMax;
            }
            int add = Math.min(km.getDamage(), (int) Math.round(left * kMax));
            if (add <= 0) continue;
            km.setDamage(km.getDamage() - add);
            k.setItemMeta(km);
            restored += add;
            total += kMax;
        }
        if (restored == 0) return;
        killer.getInventory().setArmorContents(mine);
        plugin.msg().send(killer, "books.repaired", "victim", victim, "percent", Math.max(1, Math.round(restored * 100f / total)));
        killer.playSound(killer.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 1.6f);
    }

    private static String pretty(Material m) {
        String[] w = m.name().toLowerCase().split("_");
        StringBuilder b = new StringBuilder();
        for (String s : w) b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        return b.toString();
    }

    private static String roman(int n) {
        String[] r = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}
