package com.cartroyale.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class GuiListener implements Listener {

    /** Last click time per player, so a macro can't fire a handler (and a purchase) twice. */
    private final Map<UUID, Long> lastClick = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Gui gui)) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long prev = lastClick.put(e.getWhoClicked().getUniqueId(), now);
        if (prev != null && now - prev < 120) return;
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory() == e.getView().getTopInventory()) gui.click(e);
        else gui.bottomClick(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Gui) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder(false) instanceof Gui || e.getDestination().getHolder(false) instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Gui gui) gui.closed();
    }
}
