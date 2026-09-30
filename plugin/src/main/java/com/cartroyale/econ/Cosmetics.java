package com.cartroyale.econ;

import com.cartroyale.CartRoyale;
import com.cartroyale.core.Yml;
import com.cartroyale.db.Store;
import com.cartroyale.loot.Rarity;
import com.cartroyale.util.Keys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * ECON-2/3/7. Data-driven cosmetics from cosmetics.yml: kill effects, cart explosion effects and
 * suffixes. Effects are lists of particle/sound steps with delays. Purely visual: they never
 * touch explosion power or damage.
 */
public final class Cosmetics implements Listener {

    public static final List<String> CATEGORIES = List.of("kill", "explosion", "suffix");

    public record Step(int delay, String shape, Particle particle, Color color, int count, double spread, double speed,
                       double y, double radius, String sound, float volume, float pitch) {
    }

    public record Cosmetic(String id, String category, Rarity rarity, int price, String name, String description,
                           Material icon, String text, List<Step> steps) {
    }

    private final CartRoyale plugin;
    private final Yml yml;
    private final Map<String, Cosmetic> all = new LinkedHashMap<>();
    private final Map<UUID, Store.Equipped> equipped = new HashMap<>();
    private final Map<UUID, Set<String>> owned = new HashMap<>();
    /** ECON-7: particles spawned this tick per source player. */
    private final Map<UUID, Integer> budget = new HashMap<>();
    private long budgetTick;
    private String shopDate;
    private List<String> shopItems = List.of();

    public Cosmetics(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "cosmetics.yml", true);
        load();
    }

    public void reload() {
        yml.reload();
        load();
        shopDate = null;
    }

    private void load() {
        all.clear();
        for (Map<?, ?> m : yml.get().getMapList("cosmetics")) {
            String id = str(m.get("id"));
            String cat = str(m.get("category"));
            if (id == null || cat == null || !CATEGORIES.contains(cat)) {
                plugin.getLogger().warning("cosmetics.yml: skipped an entry without a valid id/category: " + m);
                continue;
            }
            Rarity r = Rarity.parse(str(m.get("rarity")), Rarity.COMMON);
            int price = num(m.get("price"), defaultPrice(r));
            Material icon = Material.matchMaterial(String.valueOf(m.get("icon") == null ? "PAPER" : m.get("icon")));
            List<Step> steps = new ArrayList<>();
            Object rawSteps = m.get("steps");
            if (rawSteps instanceof List<?> l) {
                for (Object o : l) if (o instanceof Map<?, ?> sm) {
                    Step s = step(id, sm);
                    if (s != null) steps.add(s);
                }
            }
            all.put(id, new Cosmetic(id, cat, r, price, str(m.get("name")) == null ? id : str(m.get("name")),
                str(m.get("description")) == null ? "" : str(m.get("description")), icon == null ? Material.PAPER : icon, str(m.get("text")), steps));
        }
    }

    private int defaultPrice(Rarity r) {
        return switch (r) {
            case COMMON -> 75;
            case RARE -> 300;
            case EPIC -> 800;
            case LEGENDARY -> 2000;
        };
    }

    private Step step(String id, Map<?, ?> m) {
        Particle particle = null;
        String pn = str(m.get("particle"));
        if (pn != null) {
            particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(pn.toLowerCase(Locale.ROOT)));
            if (particle == null) {
                plugin.getLogger().warning("cosmetics.yml: " + id + " uses unknown particle " + pn);
                return null;
            }
            Class<?> data = particle.getDataType();
            if (data != Void.class && data != Particle.DustOptions.class && data != Color.class) {
                plugin.getLogger().warning("cosmetics.yml: " + id + " uses particle " + pn + " that needs data this plugin can't supply");
                return null;
            }
        }
        Color color = null;
        String c = str(m.get("color"));
        if (c != null) {
            try {
                color = Color.fromRGB(Integer.parseInt(c.replace("#", ""), 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return new Step(num(m.get("delay"), 0), str(m.get("shape")) == null ? "burst" : str(m.get("shape")), particle, color,
            Math.min(num(m.get("count"), 20), plugin.getConfig().getInt("cosmetics.max-particles-per-step", 120)),
            dbl(m.get("spread"), 0.4), dbl(m.get("speed"), 0.02), dbl(m.get("y"), 1.0), dbl(m.get("radius"), 1.2),
            str(m.get("sound")), (float) dbl(m.get("volume"), 1.0), (float) dbl(m.get("pitch"), 1.0));
    }

    public Map<String, Cosmetic> all() {
        return all;
    }

    public Cosmetic get(String id) {
        return id == null ? null : all.get(id);
    }

    public List<Cosmetic> byCategory(String cat) {
        List<Cosmetic> out = new ArrayList<>();
        for (Cosmetic c : all.values()) if (c.category().equals(cat)) out.add(c);
        out.sort((a, b) -> a.rarity() != b.rarity() ? a.rarity().compareTo(b.rarity()) : a.name().compareTo(b.name()));
        return out;
    }

    // ---------------- per-player cache ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        equipped.remove(e.getPlayer().getUniqueId());
        owned.remove(e.getPlayer().getUniqueId());
    }

    public CompletableFuture<Void> load(UUID u) {
        return plugin.store().owned(u).thenCombine(plugin.store().equipped(u), (o, eq) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                owned.put(u, new HashSet<>(o));
                equipped.put(u, eq);
                Player p = Bukkit.getPlayer(u);
                if (p != null) plugin.nametags().refresh(p);
            });
            return null;
        });
    }

    public boolean owns(UUID u, String id) {
        return owned.getOrDefault(u, Set.of()).contains(id);
    }

    public Set<String> owned(UUID u) {
        return owned.getOrDefault(u, Set.of());
    }

    public void markOwned(UUID u, String id) {
        owned.computeIfAbsent(u, k -> new HashSet<>()).add(id);
    }

    public String equippedId(UUID u, String category) {
        Store.Equipped e = equipped.get(u);
        if (e == null) return null;
        return switch (category) {
            case "kill" -> e.kill();
            case "explosion" -> e.explosion();
            default -> e.suffix();
        };
    }

    public void setEquipped(UUID u, String category, String id) {
        Store.Equipped e = equipped.getOrDefault(u, new Store.Equipped(null, null, null));
        equipped.put(u, switch (category) {
            case "kill" -> new Store.Equipped(id, e.explosion(), e.suffix());
            case "explosion" -> new Store.Equipped(e.kill(), id, e.suffix());
            default -> new Store.Equipped(e.kill(), e.explosion(), id);
        });
        String col = switch (category) {
            case "kill" -> "kill_effect";
            case "explosion" -> "explosion_effect";
            default -> "suffix";
        };
        plugin.store().equip(u, col, id);
        Player p = Bukkit.getPlayer(u);
        if (p != null) plugin.nametags().refresh(p);
    }

    public Component suffixComponent(UUID u) {
        Cosmetic c = get(equippedId(u, "suffix"));
        if (c == null || c.text() == null) return null;
        return plugin.msg().parse(c.text());
    }

    // ---------------- effects ----------------

    public void playKill(Player killer, Location at) {
        Cosmetic c = get(equippedId(killer.getUniqueId(), "kill"));
        if (c != null) play(killer.getUniqueId(), c, at);
    }

    /** Plays the owner's cart explosion effect on top of the vanilla explosion (cosmetic only). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCartExplode(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof ExplosiveMinecart cart)) return;
        String s = cart.getPersistentDataContainer().get(Keys.CART_OWNER, PersistentDataType.STRING);
        if (s == null) return;
        UUID owner = UUID.fromString(s);
        Cosmetic c = get(equippedId(owner, "explosion"));
        if (c != null) play(owner, c, e.getLocation());
    }

    public void play(UUID source, Cosmetic c, Location at) {
        for (Step s : c.steps()) {
            if (s.delay() <= 0) runStep(source, s, at);
            else Bukkit.getScheduler().runTaskLater(plugin, () -> runStep(source, s, at), s.delay());
        }
    }

    private void runStep(UUID source, Step s, Location at) {
        World w = at.getWorld();
        if (w == null) return;
        long now = Bukkit.getCurrentTick(); // server ticks: the arena clock is frozen
        if (now != budgetTick) {
            budget.clear();
            budgetTick = now;
        }
        int cap = plugin.getConfig().getInt("cosmetics.max-particles-per-tick", 200);
        int used = budget.getOrDefault(source, 0);
        int count = Math.max(0, Math.min(s.count(), cap - used));
        budget.put(source, used + count);
        if (s.sound() != null) w.playSound(at, s.sound(), s.volume(), s.pitch());
        if (s.particle() == null || count == 0) return;
        Object data = null;
        if (s.particle().getDataType() == Particle.DustOptions.class) data = new Particle.DustOptions(s.color() == null ? Color.RED : s.color(), 1.3f);
        else if (s.particle().getDataType() == Color.class) data = s.color() == null ? Color.RED : s.color();
        Location base = at.clone().add(0, s.y(), 0);
        switch (s.shape()) {
            case "ring" -> {
                for (int i = 0; i < count; i++) {
                    double a = Math.PI * 2 * i / count;
                    spawn(w, s, base.clone().add(Math.cos(a) * s.radius(), 0, Math.sin(a) * s.radius()), 1, 0, data);
                }
            }
            case "column" -> {
                for (int i = 0; i < count; i++) spawn(w, s, base.clone().add(0, i * 0.15, 0), 1, s.spread(), data);
            }
            case "spiral" -> {
                for (int i = 0; i < count; i++) {
                    double a = i * 0.45;
                    spawn(w, s, base.clone().add(Math.cos(a) * s.radius(), i * 0.08, Math.sin(a) * s.radius()), 1, 0, data);
                }
            }
            default -> spawn(w, s, base, count, s.spread(), data);
        }
    }

    private static void spawn(World w, Step s, Location l, int count, double spread, Object data) {
        if (data == null) w.spawnParticle(s.particle(), l, count, spread, spread, spread, s.speed());
        else w.spawnParticle(s.particle(), l, count, spread, spread, spread, s.speed(), data);
    }

    // ---------------- daily shop (ECON-4) ----------------

    /** Today's six items, from the database or freshly picked (seeded by date) and stored. */
    public List<String> shop() {
        String today = LocalDate.now(plugin.zoneId()).toString();
        if (today.equals(shopDate)) return shopItems;
        String stored = null;
        try {
            stored = plugin.store().dailyShop(today).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        List<String> items;
        if (stored != null) {
            items = new ArrayList<>(List.of(stored.split(",")));
            items.removeIf(id -> !all.containsKey(id));
        } else {
            List<ShopMath.Item> pool = new ArrayList<>();
            for (Cosmetic c : all.values()) pool.add(new ShopMath.Item(c.id(), c.category(), c.rarity()));
            Map<Rarity, Double> odds = new EnumMap<>(Rarity.class);
            double[] def = {60, 28, 10, 2};
            for (Rarity r : Rarity.values()) odds.put(r, plugin.getConfig().getDouble("economy.shop-odds." + r.name(), def[r.ordinal()]));
            int per = plugin.getConfig().getInt("economy.shop-per-category", 2);
            long salt = plugin.getConfig().getLong("economy.shop-salt", 0);
            items = ShopMath.pick(pool, CATEGORIES, per, odds, new Random(ShopMath.seed(LocalDate.parse(today), salt)));
            plugin.store().saveDailyShop(today, String.join(",", items));
        }
        shopDate = today;
        shopItems = items;
        return items;
    }

    public long secondsUntilReset() {
        var now = java.time.ZonedDateTime.now(plugin.zoneId());
        var next = now.toLocalDate().plusDays(1).atStartOfDay(plugin.zoneId());
        return java.time.Duration.between(now, next).getSeconds();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int num(Object o, int d) {
        return o instanceof Number n ? n.intValue() : d;
    }

    private static double dbl(Object o, double d) {
        return o instanceof Number n ? n.doubleValue() : d;
    }
}
