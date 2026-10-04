package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.game.Mode;
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
 * LOOT-2/3. /cart loot: pick a pool (LT or HT, the game mode) and a tier, then edit its entries. Click an item in your own inventory
 * to add it. On an entry: left/right click raise min/max (shift lowers), Q cycles the rarity,
 * F toggles the pearl flag, Ctrl+Q removes it. Every change is saved to loot.yml at once. Items
 * are copied, never moved, so nothing can be duplicated through the editor.
 */
public final class LootEditorGui {

    private final CartRoyale plugin;

    public LootEditorGui(CartRoyale plugin) {
        this.plugin = plugin;
    }

    /** The loot pool (bronze/silver/gold) plus chest settings. Only the LT pool is used now. */
    public void openTiers(Player p) {
        Gui gui = new Gui(4, plugin.msg().get("gui.loot.title"));
        Material[] icons = {Material.COPPER_BLOCK, Material.IRON_BLOCK, Material.GOLD_BLOCK};
        Mode[] modes = {Mode.LT};
        for (int r = 0; r < modes.length; r++) {
            Mode md = modes[r];
            int row = 9 + r * 9;
            gui.set(row + 1, Items.make(Material.CHEST,
                plugin.msg().item("gui.loot.pool", "pool", md.name()), plugin.msg().lore("gui.loot.pool-lore", "pool", md.name())));
            for (int t = 1; t <= 3; t++) {
                int tier = t;
                gui.set(row + 2 + t, Items.make(icons[t - 1], plugin.msg().item("gui.loot.tier-" + t),
                    plugin.msg().lore("gui.loot.tier-lore", "entries", plugin.loot().tier(md, t).size())), e -> openTier(p, md, tier, 0));
            }
        }
        gui.set(16, Items.make(Material.CHEST, plugin.msg().item("gui.loot.settings"), plugin.msg().lore("gui.loot.settings-lore")), e -> openSettings(p));
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(p);
    }

    public void openTier(Player p, Mode mode, int tier, int page) {
        List<LootTables.Entry> list = plugin.loot().tier(mode, tier);
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
                openTier(p, mode, tier, pg);
            });
        }
        gui.onBottomClick(e -> {
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            ItemStack copy = clicked.clone();
            int amount = copy.getAmount();
            copy.setAmount(1);
            boolean shield = OneTimeShield.is(copy);
            UpgradeBooks.Kind book = UpgradeBooks.kind(copy);
            String special = shield ? OneTimeShield.ID : book != null ? book.id : null;
            ItemStack item = shield ? OneTimeShield.create(plugin, 1) : book != null ? UpgradeBooks.create(plugin, book, 1) : copy;
            list.add(new LootTables.Entry(item, Rarity.COMMON, 1, Math.max(1, Math.min(amount, copy.getMaxStackSize())),
                copy.getType() == Material.ENDER_PEARL, special));
            plugin.loot().save();
            plugin.msg().send(p, "loot.added", "item", copy.getType().name().toLowerCase(), "tier", plugin.msg().plain("gui.loot.tier-name-" + tier));
            openTier(p, mode, tier, (list.size() - 1) / per);
        });
        gui.set(45, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), e -> openTiers(p));
        if (pg > 0) gui.set(48, Items.make(Material.ARROW, plugin.msg().item("gui.prev"), List.of()), e -> openTier(p, mode, tier, pg - 1));
        gui.set(49, Items.make(Material.BOOK, plugin.msg().item("gui.loot.help"), plugin.msg().lore("gui.loot.help-lore")));
        if (pg < pages - 1) gui.set(50, Items.make(Material.ARROW, plugin.msg().item("gui.next"), List.of()), e -> openTier(p, mode, tier, pg + 1));
        gui.set(53, Items.make(Material.ENCHANTED_BOOK, plugin.msg().item("gui.loot.specials"), plugin.msg().lore("gui.loot.specials-lore")), e -> openSpecials(p, mode, tier));
        gui.open(p);
    }

    /** The plugin's own items (shield, upgrade books): click one to add it to the tier. */
    private void openSpecials(Player p, Mode mode, int tier) {
        Gui gui = new Gui(3, plugin.msg().get("gui.loot.specials-title", "tier", plugin.msg().plain("gui.loot.tier-name-" + tier)));
        List<LootTables.Entry> list = plugin.loot().tier(mode, tier);
        int slot = 10;
        ItemStack shield = OneTimeShield.create(plugin, 1);
        gui.set(slot++, shield, e -> {
            list.add(new LootTables.Entry(OneTimeShield.create(plugin, 1), Rarity.EPIC, 1, 1, false, OneTimeShield.ID));
            added(p, mode, tier, list, "One Time Shield");
        });
        for (UpgradeBooks.Kind k : UpgradeBooks.Kind.values()) {
            slot++;
            gui.set(slot, UpgradeBooks.create(plugin, k, 1), e -> {
                list.add(new LootTables.Entry(UpgradeBooks.create(plugin, k, 1), Rarity.RARE, 1, 1, false, k.id));
                added(p, mode, tier, list, k.id);
            });
        }
        gui.set(18, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), e -> openTier(p, mode, tier, 0));
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(p);
    }

    private void added(Player p, Mode mode, int tier, List<LootTables.Entry> list, String what) {
        plugin.loot().save();
        plugin.msg().send(p, "loot.added", "item", what, "tier", plugin.msg().plain("gui.loot.tier-name-" + tier));
        openTier(p, mode, tier, (list.size() - 1) / 45);
    }

    /** Chest amount, spacing, items per chest and the bronze/silver/gold mix. */
    public void openSettings(Player p) {
        LootTables lt = plugin.loot();
        Gui gui = new Gui(4, plugin.msg().get("gui.loot.settings-title"));
        gui.set(10, setting(Material.CHEST, "base", lt.chestBase(), "left +10, right -10, shift x5"), e -> {
            int step = e.isShiftClick() ? 50 : 10;
            lt.setSetting("chests.base", Math.max(0, lt.chestBase() + (e.isRightClick() ? -step : step)));
            openSettings(p);
        });
        gui.set(11, setting(Material.PLAYER_HEAD, "per-player", lt.chestPerPlayer(), "left +0.5, right -0.5"), e -> {
            double v = Math.max(0, lt.chestPerPlayer() + (e.isRightClick() ? -0.5 : 0.5));
            lt.setSetting("chests.per-player", v);
            openSettings(p);
        });
        gui.set(12, setting(Material.BARREL, "max", lt.chestMax(), "left +50, right -50"), e -> {
            lt.setSetting("chests.max", Math.max(1, lt.chestMax() + (e.isRightClick() ? -50 : 50)));
            openSettings(p);
        });
        gui.set(13, setting(Material.COMPASS, "spacing", lt.chestSpacing(), "left +1, right -1, shift x5"), e -> {
            int step = e.isShiftClick() ? 5 : 1;
            lt.setSetting("chests.min-distance", Math.max(3.0, lt.chestSpacing() + (e.isRightClick() ? -step : step)));
            openSettings(p);
        });
        gui.set(15, setting(Material.HOPPER, "slots-min", lt.minSlots(), "left +1, right -1"), e -> {
            int v = Math.max(1, Math.min(lt.maxSlots(), lt.minSlots() + (e.isRightClick() ? -1 : 1)));
            lt.setSetting("slots.min", v);
            openSettings(p);
        });
        gui.set(16, setting(Material.DROPPER, "slots-max", lt.maxSlots(), "left +1, right -1"), e -> {
            int v = Math.max(lt.minSlots(), Math.min(27, lt.maxSlots() + (e.isRightClick() ? -1 : 1)));
            lt.setSetting("slots.max", v);
            openSettings(p);
        });
        int[] sh = lt.tierShares();
        Material[] icons = {Material.COPPER_BLOCK, Material.IRON_BLOCK, Material.GOLD_BLOCK};
        for (int t = 0; t < 3; t++) {
            int idx = t;
            gui.set(20 + t * 2, Items.make(icons[t], plugin.msg().item("gui.loot.share", "tier", plugin.msg().plain("gui.loot.tier-name-" + (t + 1)), "value", sh[t]),
                plugin.msg().lore("gui.loot.share-lore")), e -> {
                int[] now = lt.tierShares();
                now[idx] = Math.max(0, Math.min(100, now[idx] + (e.isRightClick() ? -5 : 5)));
                if (now[0] + now[1] + now[2] == 0) now[idx] = 5;
                lt.setSetting("chests.tier-shares", List.of(now[0], now[1], now[2]));
                openSettings(p);
            });
        }
        gui.set(31, Items.make(Material.PAPER, plugin.msg().item("gui.loot.preview"), plugin.msg().lore("gui.loot.preview-lore",
            "solo", ChestSpots.countFor(1, lt.chestBase(), lt.chestPerPlayer(), lt.chestMax()),
            "p25", ChestSpots.countFor(25, lt.chestBase(), lt.chestPerPlayer(), lt.chestMax()),
            "p50", ChestSpots.countFor(50, lt.chestBase(), lt.chestPerPlayer(), lt.chestMax()),
            "p100", ChestSpots.countFor(100, lt.chestBase(), lt.chestPerPlayer(), lt.chestMax()))));
        gui.set(27, Items.make(Material.OAK_DOOR, plugin.msg().item("gui.back"), List.of()), e -> openTiers(p));
        gui.fill(Items.make(Material.GRAY_STAINED_GLASS_PANE, plugin.msg().item("gui.filler"), List.of()));
        gui.open(p);
    }

    private ItemStack setting(Material icon, String key, Object value, String controls) {
        String v = value instanceof Double d && d == Math.floor(d) ? String.valueOf(d.intValue()) : String.valueOf(value);
        return Items.make(icon, plugin.msg().item("gui.loot.set-" + key, "value", v),
            plugin.msg().lore("gui.loot.set-" + key + "-lore", "controls", controls));
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
