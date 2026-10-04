package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import com.cartroyale.game.Mode;
import com.cartroyale.shield.OneTimeShield;
import com.cartroyale.util.Items;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The three chest tiers and their entries, from loot.yml. An entry is written as a plain
 * material, as {@code special: one_time_shield}, or as a serialized item for anything with
 * custom data (enchants, names). The GUI editor writes back through {@link #save()}.
 */
public final class LootTables {

    public static final class Entry {
        public ItemStack item;
        public Rarity rarity;
        public int min, max;
        public boolean pearl;
        public String special;

        public Entry(ItemStack item, Rarity rarity, int min, int max, boolean pearl, String special) {
            this.item = item;
            this.rarity = rarity;
            this.min = min;
            this.max = max;
            this.pearl = pearl;
            this.special = special;
        }

        public int maxStack() {
            return Math.max(1, item.getMaxStackSize());
        }
    }

    private final CartRoyale plugin;
    private final Yml yml;
    /** Keyed "lt.1" .. "ht.3": one pool per game mode, three tiers each. */
    private final Map<String, List<Entry>> tiers = new LinkedHashMap<>();
    private final Map<Rarity, Double> weights = new EnumMap<>(Rarity.class);
    private final Map<Rarity, Double> lucky = new EnumMap<>(Rarity.class);
    private int minSlots = 3, maxSlots = 8;

    public LootTables(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "loot.yml", true);
        load();
        addBooksOnce();
    }

    /**
     * loot.yml files written before the upgrade books existed have tiers of their own, so the new
     * default entries never show up there. Adds them once, then remembers it did.
     */
    private void addBooksOnce() {
        YamlConfiguration c = yml.get();
        if (c.getBoolean("migrations.books", false)) return;
        boolean any = false;
        for (List<Entry> list : tiers.values()) for (Entry e : list) if (e.special != null && UpgradeBooks.Kind.of(e.special) != null) any = true;
        if (!any) {
            Rarity[][] r = {{Rarity.RARE, Rarity.RARE, Rarity.EPIC}, {Rarity.RARE, Rarity.RARE, Rarity.RARE}, {Rarity.COMMON, Rarity.RARE, Rarity.RARE}};
            for (int t = 1; t <= 3; t++) {
                int i = 0;
                for (UpgradeBooks.Kind k : UpgradeBooks.Kind.values()) {
                    for (Mode md : Mode.values()) tier(md, t).add(new Entry(UpgradeBooks.create(plugin, k, 1), r[t - 1][i], 1, 1, false, k.id));
                    i++;
                }
            }
        }
        c.set("migrations.books", true);
        save();
    }

    public void reload() {
        yml.reload();
        load();
    }

    private void load() {
        YamlConfiguration c = yml.get();
        weights.clear();
        lucky.clear();
        double[] defW = {60, 25, 12, 3};
        double[] defL = {1, 1.5, 2, 3};
        for (Rarity r : Rarity.values()) {
            weights.put(r, c.getDouble("rarity-weights." + r.name(), defW[r.ordinal()]));
            lucky.put(r, c.getDouble("luckier-loot-multipliers." + r.name(), defL[r.ordinal()]));
        }
        minSlots = c.getInt("slots.min", 3);
        maxSlots = c.getInt("slots.max", 8);
        tiers.clear();
        // Older loot.yml files have one set of tiers; both pools start as a copy of it.
        boolean legacy = !c.contains("pools");
        for (Mode md : Mode.values()) {
            for (int t = 1; t <= 3; t++) {
                List<Entry> list = new ArrayList<>();
                for (Map<?, ?> m : c.getMapList(legacy ? "tiers." + t : "pools." + key(md, t))) {
                    Entry e = parse(m);
                    if (e != null) list.add(e);
                }
                tiers.put(key(md, t), list);
            }
        }
        if (legacy) {
            c.set("tiers", null);
            save();
        }
    }

    private static String key(Mode m, int t) {
        return m.name().toLowerCase() + "." + t;
    }

    private Entry parse(Map<?, ?> m) {
        Rarity rarity = Rarity.parse(str(m.get("rarity")), Rarity.COMMON);
        int min = num(m.get("min"), 1), max = num(m.get("max"), min);
        String special = str(m.get("special"));
        ItemStack item = null;
        if ("one_time_shield".equalsIgnoreCase(special)) {
            item = OneTimeShield.create(plugin, 1);
        } else if (special != null && UpgradeBooks.Kind.of(special) != null) {
            item = UpgradeBooks.create(plugin, UpgradeBooks.Kind.of(special), 1);
            special = UpgradeBooks.Kind.of(special).id;
        } else if (m.get("item") != null) {
            item = Items.fromB64(str(m.get("item")));
            special = null;
        } else if (m.get("material") != null) {
            Material mat = Material.matchMaterial(str(m.get("material")));
            if (mat != null && mat.isItem()) item = new ItemStack(mat);
            special = null;
        }
        if (item == null) {
            plugin.getLogger().warning("loot.yml: skipped an entry that has no valid item: " + m);
            return null;
        }
        item.setAmount(1);
        boolean pearl = m.get("pearl") instanceof Boolean b ? b : item.getType() == Material.ENDER_PEARL;
        int cap = Math.max(1, item.getMaxStackSize());
        min = Math.max(1, Math.min(min, cap));
        max = Math.max(min, Math.min(max, cap));
        return new Entry(item, rarity, min, max, pearl, special);
    }

    public void save() {
        YamlConfiguration c = yml.get();
        for (var t : tiers.entrySet()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Entry e : t.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (e.special != null) m.put("special", e.special);
                else if (isPlain(e.item)) m.put("material", e.item.getType().name());
                else m.put("item", Items.toB64(e.item));
                m.put("rarity", e.rarity.name());
                m.put("min", e.min);
                m.put("max", e.max);
                if (e.pearl != (e.item.getType() == Material.ENDER_PEARL)) m.put("pearl", e.pearl);
                out.add(m);
            }
            c.set("pools." + t.getKey(), out);
        }
        yml.save();
    }

    private static boolean isPlain(ItemStack it) {
        return it.isSimilar(new ItemStack(it.getType()));
    }

    public List<Entry> tier(Mode m, int t) {
        return tiers.computeIfAbsent(key(m, t), k -> new ArrayList<>());
    }

    /** Rolls one chest of the given pool (game mode) and tier into slot/item pairs. */
    public List<LootRoller.Slot<ItemStack>> roll(Mode mode, int tier, boolean luckier, boolean pearls, Random rng) {
        List<LootRoller.Entry<ItemStack>> table = new ArrayList<>();
        // Gear from chests wears out like normal, even if it was saved unbreakable.
        for (Entry e : tier(mode, tier)) table.add(new LootRoller.Entry<>(com.cartroyale.util.Items.stackablePotion(com.cartroyale.util.Items.breakable(e.item.clone())), e.rarity, e.min, e.max, e.pearl));
        var opts = new LootRoller.Options(minSlots, maxSlots, 27, weights, luckier, lucky, pearls);
        // Same item type = same plugin item id (the books are all BOOK), otherwise the same material.
        int maxSame = plugin.getConfig().getInt("chests.max-same-item", 2);
        return LootRoller.roll(table, opts, rng, en -> {
            String id = com.cartroyale.util.Items.id(en.item());
            return id != null ? id : en.item().getType();
        }, maxSame);
    }

    // ---- chest amount / spacing / fill, edited in the loot GUI (stored in loot.yml) ----

    public int chestBase() { return yml.get().getInt("chests.base", 200); }
    public double chestPerPlayer() { return yml.get().getDouble("chests.per-player", 3.0); }
    public int chestMax() { return yml.get().getInt("chests.max", 600); }
    public double chestSpacing() { return yml.get().getDouble("chests.min-distance", 10); }
    public int minSlots() { return minSlots; }
    public int maxSlots() { return maxSlots; }

    /** Tier shares as whole percentages (bronze, silver, gold). */
    public int[] tierShares() {
        List<Double> sh = yml.get().getDoubleList("chests.tier-shares");
        if (sh.size() != 3) return new int[]{60, 30, 10};
        double sum = sh.get(0) + sh.get(1) + sh.get(2);
        if (sum <= 0) return new int[]{60, 30, 10};
        return new int[]{(int) Math.round(sh.get(0) * 100 / sum), (int) Math.round(sh.get(1) * 100 / sum), (int) Math.round(sh.get(2) * 100 / sum)};
    }

    public void setSetting(String path, Object value) {
        yml.get().set(path, value);
        if (path.equals("slots.min")) minSlots = ((Number) value).intValue();
        if (path.equals("slots.max")) maxSlots = ((Number) value).intValue();
        yml.save();
    }

    public Map<Rarity, Double> weights() {
        return weights;
    }

    public ConfigurationSection raw() {
        return yml.get();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int num(Object o, int d) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? d : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }
}
