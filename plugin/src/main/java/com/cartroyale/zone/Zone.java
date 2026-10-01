package com.cartroyale.zone;

import com.cartroyale.CartRoyale;
import com.cartroyale.arena.MapConfig;
import com.cartroyale.core.Yml;
import com.cartroyale.game.Match;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ZONE-1..6. A custom circle (the vanilla border only marks the map edge). Players can always
 * walk through the edge; outside it they take damage attributed to the zone. The wall is the
 * resource pack's hexagon shader (ZoneWall); players without the pack get particles near them.
 */
public final class Zone {

    private final CartRoyale plugin;
    private final Yml yml;
    private ZoneMath.Plan plan;
    private BukkitTask task;
    private long startedAt;
    private World world;
    private Match match;
    private long tick;
    private final ZoneWall wall;
    private final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);

    public Zone(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "zone.yml", true);
        this.wall = new ZoneWall(plugin);
    }

    public void reload() {
        yml.reload();
    }

    public List<ZoneMath.Phase> phases() {
        List<ZoneMath.Phase> out = new ArrayList<>();
        for (Map<?, ?> m : yml.get().getMapList("phases")) {
            out.add(new ZoneMath.Phase(num(m.get("wait"), 60), num(m.get("shrink"), 60), dbl(m.get("radius"), 50),
                dbl(m.get("damage"), 2), num(m.get("interval"), 20)));
        }
        return out;
    }

    public double plannedSeconds() {
        double t = 0;
        for (ZoneMath.Phase p : phases()) t += p.waitSeconds() + p.shrinkSeconds();
        return t;
    }

    /** /cart test: about 3 minutes, so a practice game is quick. */
    private static final List<ZoneMath.Phase> TEST_PHASES = List.of(
        new ZoneMath.Phase(30, 30, 300, 2, 20), new ZoneMath.Phase(20, 25, 120, 2, 20),
        new ZoneMath.Phase(20, 20, 40, 3, 20), new ZoneMath.Phase(15, 15, 5, 4, 20));

    public int plannedMinutes(boolean test) {
        double t = 0;
        for (ZoneMath.Phase p : test ? TEST_PHASES : phases()) t += p.waitSeconds() + p.shrinkSeconds();
        return (int) Math.ceil((t + plugin.getConfig().getInt("deploy.planned-seconds", 60)) / 60.0);
    }

    public ZoneMath.Plan planFor(MapConfig mc) {
        return planFor(mc, false);
    }

    public ZoneMath.Plan planFor(MapConfig mc, boolean test) {
        double orbitR = yml.get().getDouble("orbit.radius", 100), speed = yml.get().getDouble("orbit.speed", 4.3);
        double[] oc = ZoneMath.orbitCenter(mc.finalX, mc.finalZ, mc.centerX, mc.centerZ, orbitR);
        return new ZoneMath.Plan(new ZoneMath.Circle(mc.zoneX, mc.zoneZ, mc.zoneR), mc.finalX, mc.finalZ, test ? TEST_PHASES : phases(), orbitR, speed, oc[0], oc[1]);
    }

    public boolean running() {
        return task != null;
    }

    public ZoneMath.State now() {
        if (plan == null) return null;
        return ZoneMath.at(plan, (System.currentTimeMillis() - startedAt) / 1000.0);
    }

    public void start(Match m, World w, MapConfig mc) {
        stop();
        match = m;
        world = w;
        plan = planFor(mc, m.row.test());
        startedAt = System.currentTimeMillis();
        tick = 0;
        wall.model(yml.get().getString("wall.item-model", "lodestone:border"));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        wall.clear();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        match = null;
    }

    private void tick() {
        tick++;
        // Local copy: a zone kill can end the match (and null the field) halfway through the loop.
        Match match = this.match;
        if (world == null || match == null) return;
        ZoneMath.State s = now();
        ZoneMath.Circle c = s.circle();
        int interval = Math.max(1, s.damageIntervalTicks());
        boolean drawTick = tick % Math.max(1, yml.get().getInt("particles.every-ticks", 4)) == 0;
        boolean shader = !"particles".equalsIgnoreCase(yml.get().getString("wall.mode", "shader"));
        double baseY = yml.get().getDouble("wall.base-y", 0), reanchor = yml.get().getDouble("wall.reanchor-distance", 48);
        wall.beginTick();
        Set<UUID> walled = new HashSet<>();
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            boolean playing = match.isAlive(p.getUniqueId()) && p.getGameMode() != GameMode.SPECTATOR;
            boolean outside = !c.contains(l.getX(), l.getZ());
            if (shader && wall.update(p, c, baseY, reanchor)) walled.add(p.getUniqueId());
            else if (drawTick) draw(p, c);
            if (tick % 10 == 0) updateBar(p, s);
            if (!playing) continue;
            if (outside) {
                if (tick % 20 == 0) plugin.msg().actionbar(p, "zone.outside", "distance", (int) Math.ceil(c.distanceOutside(l.getX(), l.getZ())));
                if (tick % interval == 0) {
                    p.damage(s.damageHp(), DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
                }
            }
        }
        wall.retain(walled);
    }

    /** For /cart diag: how many players are seeing the shader wall right now. */
    public int wallViewers() {
        return wall.count();
    }

    private void updateBar(Player p, ZoneMath.State s) {
        String key = s.orbiting() ? "zone.bar-orbit" : s.shrinking() ? "zone.bar-shrinking" : "zone.bar-waiting";
        bar.name(plugin.msg().get(key, "seconds", (int) Math.ceil(s.secondsLeftInStep()), "phase", s.phase() + 1,
            "phases", plan.phases().size(), "radius", (int) Math.round(s.circle().r())));
        double total = ZoneMath.totalSeconds(plan);
        float prog = total <= 0 ? 0 : (float) Math.max(0, Math.min(1, 1 - ((System.currentTimeMillis() - startedAt) / 1000.0) / total));
        bar.progress(prog);
        p.showBossBar(bar);
    }

    /** ZONE-6: a band of particles on the circle, only within view distance of this player. */
    private void draw(Player p, ZoneMath.Circle c) {
        double view = yml.get().getDouble("particles.view-distance", 40);
        double spacing = Math.max(0.5, yml.get().getDouble("particles.spacing", 2.0));
        int height = yml.get().getInt("particles.height", 10);
        Location l = p.getLocation();
        double dx = l.getX() - c.x(), dz = l.getZ() - c.z();
        double dist = Math.hypot(dx, dz);
        if (Math.abs(dist - c.r()) > view) return;
        double base = Math.atan2(dz, dx);
        double arc = c.r() <= 0 ? Math.PI : Math.min(Math.PI, view / Math.max(1, c.r()));
        double step = spacing / Math.max(1, c.r());
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0xFF0000), 1.6f);
        int drawn = 0, cap = yml.get().getInt("particles.max-per-player", 180);
        for (double a = base - arc; a <= base + arc && drawn < cap; a += step) {
            double x = c.x() + Math.cos(a) * c.r(), z = c.z() + Math.sin(a) * c.r();
            if (Math.hypot(x - l.getX(), z - l.getZ()) > view) continue;
            for (int y = -2; y <= height && drawn < cap; y += 3) {
                p.spawnParticle(Particle.DUST, x, l.getY() + y, z, 1, 0, 0, 0, 0, dust);
                drawn++;
            }
        }
    }

    public void hide(Player p) {
        p.hideBossBar(bar);
    }

    private static int num(Object o, int d) {
        return o instanceof Number n ? n.intValue() : d;
    }

    private static double dbl(Object o, double d) {
        return o instanceof Number n ? n.doubleValue() : d;
    }
}
