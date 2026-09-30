package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.gui.Gui;
import com.cartroyale.shield.OneTimeShield;
import com.cartroyale.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * LOOT-2/3. /cart loot: pick a tier, then edit its entries. Click an item in your own inventory
 * to add it. On an entry: left/right click raise min/max (shift lowers), Q cycles the rarity,
 * F toggles the pearl flag, Ctrl+Q removes it. Every change is saved to loot.yml at once. Items
 * are copied, never moved, so nothing can be duplicated through the editor.
 */
public final class LootEditorGui {

    private final CartRoyale plugin;

    public LootEditorGui(CartRoyale plugin) {
        this.plugin = plugin;
    }

    public void openTiers(Player p) {
        Gui gui = new Gui(3, plugin.msg().get("gui.loot.title"));
        Material[] icons = {Material.COPPER_BLOCK, Material.IRON_BLOCK, Material.GOLD_BLOCK};
        for (int t = 1; t <= 3; t++) {
            int tier = t;
            gui.set(9 + t * 2, Items.make(icons[t - 1], plugin.msg().item("gui.loot.tier-" + t),
                plugin.msg().lore("gui.loot.tier-lore", "entries", plugin.loot().tier(t).size())), e -> openTier(p, tier, 0));
        }
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(p);
    }

    public void openTier(Player p, int tier, int page) {
        List<LootTables.Entry> list = plugin.loot().tier(tier);
        int per = 45;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Gui gui = new Gui(6, plugin.msg().get("gui.loot.tier-title", "tier", plugin.msg().plain("gui.loot.tier-name-" + tier), "page", pg + 1, "pages", pages));
        for (int i = 0; i < per && pg * per + i < list.size(); i++) {
            LootTables.Entry en = list.get(pg * per + i);
            gui.set(i, icon(en), e -> {
                ClickType c = e.getClick();
                switch (c) {
                    case LEFT -> en.min = Math.min(en.min + 1, en.max);
                    case SHIFT_LEFT -> en.min = Math.max(1, en.min - 1);
                    case RIGHT -> en.max = Math.min(en.max + 1, en.maxStack());
                    case SHIFT_RIGHT -> en.max = Math.max(en.min, en.max - 1);
                    case DROP -> en.rarity = en.rarity.next();
                    case SWAP_OFFHAND -> en.pearl = !en.pearl;
                    case CONTROL_DROP -> list.remove(en);
                    default -> {
                        return;
                    }
                }
                plugin.loot().save();
                openTier(p, tier, pg);
            });
        }
        gui.onBottomClick(e -> {
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            ItemStack copy = clicked.clone();
            int amount = copy.getAmount();
            copy.setAmount(1);
            boolean shield = OneTimeShield.is(copy);
            list.add(new LootTables.Entry(shield ? OneTimeShield.create(plugin, 1) : copy, Rarity.COMMON, 1, Math.max(1, Math.min(amount, copy.getMaxStackSize())),
                copy.getType() == Material.ENDER_PEARL, shield ? OneTimeShield.ID : null));
            plugin.loot().save();
            plugin.msg().send(p, "loot.added", "item", copy.getType().name().toLowerCase(), "tier", tier);
            openTier(p, tier, (list.size() - 1) / per);
        });
        gui.set(45, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), e -> openTiers(p));
        if (pg > 0) gui.set(48, Items.make(Material.ARROW, plugin.msg().item("gui.prev"), List.of()), e -> openTier(p, tier, pg - 1));
        gui.set(49, Items.make(Material.BOOK, plugin.msg().item("gui.loot.help"), plugin.msg().lore("gui.loot.help-lore")));
        if (pg < pages - 1) gui.set(50, Items.make(Material.ARROW, plugin.msg().item("gui.next"), List.of()), e -> openTier(p, tier, pg + 1));
        gui.set(53, Items.make(Material.SHIELD,plugin.msg().item("gui.loot.shield"), plugin.msg().lore("gui.loot.shield-lore")), e -> {
            list.add(new LootTables.Entry(OneTimeShield.create(plugin, 1), Rarity.EPIC, 1, 1, false, OneTimeShield.ID));
            plugin.loot().save();
            openTier(p, tier, (list.size() - 1) / per);
        });
        gui.open(p);
    }

    private ItemStack icon(LootTables.Entry en) {
        ItemStack it = en.item.clone();
        it.setAmount(Math.max(1, Math.min(en.max, it.getMaxStackSize())));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.msg().item("gui.loot.entry-rarity", "rarity", plugin.msg().parse(en.rarity.colored())));
        lore.add(plugin.msg().item("gui.loot.entry-amount", "min", en.min, "max", en.max, "cap", en.maxStack()));
        if (en.pearl) lore.add(plugin.msg().item("gui.loot.entry-pearl"));
        lore.addAll(plugin.msg().lore("gui.loot.entry-controls"));
        it.editMeta(m -> {
            List<Component> old = m.hasLore() ? m.lore() : List.of();
            List<Component> all = new ArrayList<>(old == null ? List.of() : old);
            all.addAll(lore);
            m.lore(all);
        });
        return it;
    }
}
