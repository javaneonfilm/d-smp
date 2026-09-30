package com.cartroyale.econ;

import com.cartroyale.CartRoyale;
import com.cartroyale.db.Store;
import com.cartroyale.gui.Gui;
import com.cartroyale.util.Items;
import com.cartroyale.util.Times;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** ECON-4/5/6: the slot-5 menu (Locker, Daily Shop, balance), the daily shop and the locker. */
public final class CosmeticsGui {

    private final CartRoyale plugin;

    public CosmeticsGui(CartRoyale plugin) {
        this.plugin = plugin;
    }

    private Cosmetics cos() {
        return plugin.cosmetics();
    }

    private ItemStack filler() {
        return Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of());
    }

    public void openMain(Player p) {
        plugin.store().coins(p.getUniqueId()).thenAccept(coins -> Bukkit.getScheduler().runTask(plugin, () -> {
            Gui gui = new Gui(3, plugin.msg().get("gui.cosmetics.title"));
            gui.set(11, Items.make(Material.ENDER_CHEST, plugin.msg().item("gui.cosmetics.locker"), plugin.msg().lore("gui.cosmetics.locker-lore",
                "owned", cos().owned(p.getUniqueId()).size(), "total", cos().all().size())), e -> openLocker(p, "kill", 0));
            gui.set(13, Items.make(Material.SUNFLOWER, plugin.msg().item("gui.cosmetics.balance", "coins", coins),
                plugin.msg().lore("gui.cosmetics.balance-lore", "per_kill", plugin.econ().perKill())));
            gui.set(15, Items.make(Material.EMERALD, plugin.msg().item("gui.cosmetics.shop"), plugin.msg().lore("gui.cosmetics.shop-lore",
                "reset", resetText())), e -> openShop(p));
            gui.fill(filler());
            gui.open(p);
        }));
    }

    public void openShop(Player p) {
        List<String> items = cos().shop();
        plugin.store().coins(p.getUniqueId()).thenAccept(coins -> Bukkit.getScheduler().runTask(plugin, () -> {
            Gui gui = new Gui(4, plugin.msg().get("gui.shop.title"));
            // 2 kill effects, 2 explosion effects, 2 suffixes, laid out in pairs.
            int[] layout = {10, 11, 13, 14, 15, 16};
            for (int i = 0; i < items.size() && i < layout.length; i++) {
                Cosmetics.Cosmetic c = cos().get(items.get(i));
                if (c == null) continue;
                boolean own = cos().owns(p.getUniqueId(), c.id());
                gui.set(layout[i], icon(c, own ? "gui.shop.lore-owned" : coins >= c.price() ? "gui.shop.lore-buy" : "gui.shop.lore-poor", coins),
                    e -> buy(p, c));
            }
            gui.set(31, Items.make(Material.SUNFLOWER, plugin.msg().item("gui.cosmetics.balance", "coins", coins), List.of()));
            gui.set(27, Items.make(Material.ARROW, plugin.msg().item("gui.back"), List.of()), e -> openMain(p));
            gui.set(35, Items.make(Material.CLOCK, plugin.msg().item("gui.shop.resets", "time", resetText()), List.of()));
            gui.fill(filler());
            gui.open(p);
        }));
    }

    private String resetText() {
        long s = cos().secondsUntilReset();
        return (s / 3600) + "h " + String.format(java.util.Locale.ROOT, "%02d", (s % 3600) / 60) + "m";
    }

    private ItemStack icon(Cosmetics.Cosmetic c, String loreKey, int coins) {
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(plugin.msg().item("gui.cosmetic.rarity", "rarity", plugin.msg().parse(c.rarity().colored())));
        lore.add(plugin.msg().item("gui.cosmetic.category", "category", plugin.msg().plain("categories." + c.category())));
        if (!c.description().isEmpty()) lore.add(plugin.msg().item("gui.cosmetic.description", "text", c.description()));
        if (c.text() != null) lore.add(plugin.msg().item("gui.cosmetic.suffix-preview", "suffix", plugin.msg().parse(c.text())));
        lore.addAll(plugin.msg().lore(loreKey, "price", c.price(), "coins", coins));
        net.kyori.adventure.text.Component name = plugin.msg().parse(c.rarity().tag + "<name>", "name", c.name())
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        return Items.make(c.icon(), name, lore);
    }

    private void buy(Player p, Cosmetics.Cosmetic c) {
        if (!cos().shop().contains(c.id())) {
            openShop(p);
            return;
        }
        if (cos().owns(p.getUniqueId(), c.id())) {
            plugin.msg().send(p, "shop.already-owned", "name", c.name());
            return;
        }
        plugin.store().buy(p.getUniqueId(), c.id(), c.price()).thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (r.result()) {
                case OK -> {
                    cos().markOwned(p.getUniqueId(), c.id());
                    plugin.msg().send(p, "shop.bought", "name", c.name(), "price", c.price(), "balance", r.balance());
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
                }
                case OWNED -> {
                    cos().markOwned(p.getUniqueId(), c.id());
                    plugin.msg().send(p, "shop.already-owned", "name", c.name());
                }
                case POOR -> plugin.msg().send(p, "shop.too-poor", "name", c.name(), "price", c.price(), "balance", r.balance());
            }
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Gui) openShop(p);
        }));
    }

    public void openLocker(Player p, String category, int page) {
        List<Cosmetics.Cosmetic> list = cos().byCategory(category);
        list.removeIf(c -> !cos().owns(p.getUniqueId(), c.id()));
        int per = 28;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Gui gui = new Gui(6, plugin.msg().get("gui.locker.title", "category", plugin.msg().plain("categories." + category), "page", pg + 1, "pages", pages));
        String equipped = cos().equippedId(p.getUniqueId(), category);
        int[] inner = new int[per];
        for (int i = 0, k = 0; i < 54 && k < per; i++) {
            int row = i / 9, col = i % 9;
            if (row >= 1 && row <= 4 && col >= 1 && col <= 7) inner[k++] = i;
        }
        for (int i = 0; i < per && pg * per + i < list.size(); i++) {
            Cosmetics.Cosmetic c = list.get(pg * per + i);
            boolean on = c.id().equals(equipped);
            ItemStack icon = icon(c, on ? "gui.locker.lore-equipped" : "gui.locker.lore-equip", 0);
            if (on) icon.editMeta(m -> m.setEnchantmentGlintOverride(true));
            gui.set(inner[i], icon, e -> {
                if (e.isRightClick()) {
                    preview(p, c);
                    return;
                }
                cos().setEquipped(p.getUniqueId(), category, on ? null : c.id());
                plugin.msg().send(p, on ? "locker.unequipped" : "locker.equipped", "name", c.name());
                openLocker(p, category, pg);
            });
        }
        if (list.isEmpty()) gui.set(22, Items.make(Material.BARRIER, plugin.msg().item("gui.locker.empty"), plugin.msg().lore("gui.locker.empty-lore")));
        String[] cats = Cosmetics.CATEGORIES.toArray(new String[0]);
        Material[] icons = {Material.IRON_SWORD, Material.TNT_MINECART, Material.NAME_TAG};
        for (int i = 0; i < cats.length; i++) {
            String cat = cats[i];
            ItemStack tab = Items.make(icons[i], plugin.msg().item(cat.equals(category) ? "gui.locker.tab-on" : "gui.locker.tab",
                "category", plugin.msg().plain("categories." + cat)), List.of());
            gui.set(2 + i * 2, tab, e -> openLocker(p, cat, 0));
        }
        if (pg > 0) gui.set(45, Items.make(Material.ARROW, plugin.msg().item("gui.prev"), List.of()), e -> openLocker(p, category, pg - 1));
        if (pg < pages - 1) gui.set(53, Items.make(Material.ARROW, plugin.msg().item("gui.next"), List.of()), e -> openLocker(p, category, pg + 1));
        gui.set(49, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), e -> openMain(p));
        gui.fill(filler());
        gui.open(p);
    }

    private void preview(Player p, Cosmetics.Cosmetic c) {
        if (c.category().equals("suffix")) {
            plugin.msg().send(p, "locker.preview-suffix", "player", p.getName());
            p.sendMessage(plugin.msg().parse("<white>" + p.getName() + " ").append(plugin.msg().parse(c.text() == null ? "" : c.text())));
            return;
        }
        p.closeInventory();
        cos().play(p.getUniqueId(), c, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)));
    }
}
