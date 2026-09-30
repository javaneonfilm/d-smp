package com.cartroyale.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest menu. Every click and drag in it is cancelled by {@link GuiListener} before the
 * handler runs, so no item can ever be taken out or put in.
 */
public class Gui implements InventoryHolder {

    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    /** Called when the player clicks an item in their own inventory while this menu is open. */
    private Consumer<InventoryClickEvent> bottomHandler;
    private Runnable onClose;

    public void onBottomClick(Consumer<InventoryClickEvent> h) {
        this.bottomHandler = h;
    }

    public void onClose(Runnable r) {
        this.onClose = r;
    }

    public Gui(int rows, Component title) {
        this.inv = Bukkit.createInventory(this, rows * 9, title);
    }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inv.setItem(slot, item);
        if (onClick == null) handlers.remove(slot);
        else handlers.put(slot, onClick);
    }

    public void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    public void clear() {
        inv.clear();
        handlers.clear();
    }

    public void fill(ItemStack filler) {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    void click(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> h = handlers.get(e.getRawSlot());
        if (h != null) h.accept(e);
    }

    void bottomClick(InventoryClickEvent e) {
        if (bottomHandler != null) bottomHandler.accept(e);
    }

    void closed() {
        if (onClose != null) onClose.run();
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
