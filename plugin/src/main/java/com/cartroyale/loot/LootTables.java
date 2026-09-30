package com.cartroyale.loot;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
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
    private final Map<Integer, List<Entry>> tiers = new LinkedHashMap<>();
    private final Map<Rarity, Double> weights = new EnumMap<>(Rarity.class);
    private final Map<Rarity, Double> lucky = new EnumMap<>(Rarity.class);
    private int minSlots = 3, maxSlots = 8;

    public LootTables(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "loot.yml", true);
        load();
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
        for (int t = 1; t <= 3; t++) {
            List<Entry> list = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("tiers." + t)) {
                Entry e = parse(m);
                if (e != null) list.add(e);
            }
            tiers.put(t, list);
        }
    }

    private Entry parse(Map<?, ?> m) {
        Rarity rarity = Rarity.parse(str(m.get("rarity")), Rarity.COMMON);
        int min = num(m.get("min"), 1), max = num(m.get("max"), min);
        String special = str(m.get("special"));
        ItemStack item = null;
        if ("one_time_shield".equalsIgnoreCase(special)) {
            item = OneTimeShield.create(plugin, 1);
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
            c.set("tiers." + t.getKey(), out);
        }
        yml.save();
    }

    private static boolean isPlain(ItemStack it) {
        return it.isSimilar(new ItemStack(it.getType()));
    }

    public List<Entry> tier(int t) {
        return tiers.computeIfAbsent(t, k -> new ArrayList<>());
    }

    /** Rolls one chest of the given tier into slot/item pairs. */
    public List<LootRoller.Slot<ItemStack>> roll(int tier, boolean luckier, boolean pearls, Random rng) {
        List<LootRoller.Entry<ItemStack>> table = new ArrayList<>();
        for (Entry e : tier(tier)) table.add(new LootRoller.Entry<>(e.item, e.rarity, e.min, e.max, e.pearl));
        var opts = new LootRoller.Options(minSlots, maxSlots, 27, weights, luckier, lucky, pearls);
        return LootRoller.roll(table, opts, rng);
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
