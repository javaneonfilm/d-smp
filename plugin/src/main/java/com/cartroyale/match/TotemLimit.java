package com.cartroyale.match;

import com.cartroyale.CartRoyale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Carry limits in the arena: at most match.max-totems Totems of Undying (default 1, offhand
 * included) and match.max-enchanted-apples enchanted golden apples (default 1). Extras aren't
 * picked up off the ground; ones taken out of a chest go back in (or drop at the player's feet
 * when the chest has no room).
 */
public final class TotemLimit implements Listener {

    private record Limit(Material item, String configKey, String message) {
    }

    private static final Limit[] LIMITS = {
        new Limit(Material.TOTEM_OF_UNDYING, "match.max-totems", "match.totem-limit"),
        new Limit(Material.ENCHANTED_GOLDEN_APPLE, "match.max-enchanted-apples", "match.apple-limit"),
    };

    private final CartRoyale plugin;
    private final Set<UUID> queued = new HashSet<>();

    public TotemLimit(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private int max(Limit l) {
        return Math.max(0, plugin.getConfig().getInt(l.configKey(), 1));
    }

    private static Limit limitFor(Material m) {
        for (Limit l : LIMITS) if (l.item() == m) return l;
        return null;
    }

    private boolean applies(Player p) {
        return plugin.arenas().isArena(p.getWorld()) && !plugin.rank().canBuild(p);
    }

    static int count(Player p, Material m) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) if (it != null && it.getType() == m) n += it.getAmount();
        ItemStack cursor = p.getItemOnCursor();
        if (cursor.getType() == m) n += cursor.getAmount();
        return n;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !applies(p)) return;
        Limit l = limitFor(e.getItem().getItemStack().getType());
        if (l == null) return;
        int room = max(l) - count(p, l.item());
        if (room <= 0) {
            e.setCancelled(true);
            warn(p, l);
            return;
        }
        ItemStack stack = e.getItem().getItemStack();
        if (stack.getAmount() > room) {
            // Take only what fits; the rest stays on the ground.
            e.setCancelled(true);
            ItemStack take = stack.clone();
            take.setAmount(room);
            p.getInventory().addItem(take);
            stack.setAmount(stack.getAmount() - room);
            e.getItem().setItemStack(stack);
            warn(p, l);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) queue(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) queue(p);
    }

    /** Checks next tick, once the click has landed. */
    private void queue(Player p) {
        if (!applies(p) || !queued.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            queued.remove(p.getUniqueId());
            if (p.isOnline() && applies(p)) for (Limit l : LIMITS) enforce(p, l);
        });
    }

    private void enforce(Player p, Limit l) {
        Material m = l.item();
        int extra = count(p, m) - max(l);
        if (extra <= 0) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        boolean container = top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.PLAYER;
        // Take the extras back: cursor first, then the inventory (offhand last, so a held one stays).
        ItemStack cursor = p.getItemOnCursor();
        if (cursor.getType() == m) {
            int take = Math.min(extra, cursor.getAmount());
            cursor.setAmount(cursor.getAmount() - take);
            p.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            giveBack(p, top, container, m, take);
            extra -= take;
        }
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < 36 && extra > 0; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType() != m) continue;
            int take = Math.min(extra, it.getAmount());
            it.setAmount(it.getAmount() - take);
            inv.setItem(slot, it.getAmount() > 0 ? it : null);
            giveBack(p, top, container, m, take);
            extra -= take;
        }
        if (extra > 0) {
            ItemStack off = inv.getItemInOffHand();
            if (off.getType() == m) {
                int take = Math.min(extra, off.getAmount());
                off.setAmount(off.getAmount() - take);
                inv.setItemInOffHand(off.getAmount() > 0 ? off : null);
                giveBack(p, top, container, m, take);
            }
        }
        p.updateInventory();
        warn(p, l);
    }

    private void giveBack(Player p, Inventory top, boolean container, Material m, int n) {
        ItemStack back = new ItemStack(m, n);
        Map<Integer, ItemStack> left = container ? top.addItem(back) : Map.of(0, back);
        for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    private void warn(Player p, Limit l) {
        plugin.msg().actionbar(p, l.message(), "max", max(l));
    }
}
