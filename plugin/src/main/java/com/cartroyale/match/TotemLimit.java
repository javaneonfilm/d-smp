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
 * In the arena a player can carry at most match.max-totems Totems of Undying (default 1), offhand
 * included. Extra totems aren't picked up off the ground; one taken out of a chest goes back in
 * (or is dropped at their feet when the chest has no room).
 */
public final class TotemLimit implements Listener {

    private final CartRoyale plugin;
    private final Set<UUID> queued = new HashSet<>();

    public TotemLimit(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private int max() {
        return Math.max(0, plugin.getConfig().getInt("match.max-totems", 1));
    }

    private boolean applies(Player p) {
        return plugin.arenas().isArena(p.getWorld()) && !plugin.rank().canBuild(p);
    }

    static int count(Player p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) if (it != null && it.getType() == Material.TOTEM_OF_UNDYING) n += it.getAmount();
        ItemStack cursor = p.getItemOnCursor();
        if (cursor.getType() == Material.TOTEM_OF_UNDYING) n += cursor.getAmount();
        return n;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getItem().getItemStack().getType() != Material.TOTEM_OF_UNDYING || !applies(p)) return;
        int room = max() - count(p);
        if (room <= 0) {
            e.setCancelled(true);
            warn(p);
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
            warn(p);
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
            if (p.isOnline() && applies(p)) enforce(p);
        });
    }

    private void enforce(Player p) {
        int extra = count(p) - max();
        if (extra <= 0) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        boolean container = top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.PLAYER;
        // Take the extras back: cursor first, then the inventory (offhand last, so a held one stays).
        ItemStack cursor = p.getItemOnCursor();
        if (cursor.getType() == Material.TOTEM_OF_UNDYING) {
            int take = Math.min(extra, cursor.getAmount());
            cursor.setAmount(cursor.getAmount() - take);
            p.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            giveBack(p, top, container, take);
            extra -= take;
        }
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < 36 && extra > 0; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType() != Material.TOTEM_OF_UNDYING) continue;
            int take = Math.min(extra, it.getAmount());
            it.setAmount(it.getAmount() - take);
            inv.setItem(slot, it.getAmount() > 0 ? it : null);
            giveBack(p, top, container, take);
            extra -= take;
        }
        if (extra > 0) {
            ItemStack off = inv.getItemInOffHand();
            if (off.getType() == Material.TOTEM_OF_UNDYING) {
                int take = Math.min(extra, off.getAmount());
                off.setAmount(off.getAmount() - take);
                inv.setItemInOffHand(off.getAmount() > 0 ? off : null);
                giveBack(p, top, container, take);
            }
        }
        p.updateInventory();
        warn(p);
    }

    private void giveBack(Player p, Inventory top, boolean container, int n) {
        ItemStack back = new ItemStack(Material.TOTEM_OF_UNDYING, n);
        Map<Integer, ItemStack> left = container ? top.addItem(back) : Map.of(0, back);
        for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    private void warn(Player p) {
        plugin.msg().actionbar(p, "match.totem-limit", "max", max());
    }
}
