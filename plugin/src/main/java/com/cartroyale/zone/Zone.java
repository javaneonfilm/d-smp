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
import java.util.List;
import java.util.Map;

/**
 * ZONE-1..6. A custom circle (the vanilla border only marks the map edge). Players can always
 * walk through the edge; outside it they take damage attributed to the zone. The wall is drawn
 * with particles only near each player.
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
    private final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);

    public Zone(CartRoyale plugin) {
        this.plugin = plugin;
        this.yml = new Yml(plugin, "zone.yml", true);
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

    public ZoneMath.Plan planFor(MapConfig mc) {
        double orbitR = yml.get().getDouble("orbit.radius", 100), speed = yml.get().getDouble("orbit.speed", 4.3);
        double[] oc = ZoneMath.orbitCenter(mc.finalX, mc.finalZ, mc.centerX, mc.centerZ, orbitR);
        return new ZoneMath.Plan(new ZoneMath.Circle(mc.zoneX, mc.zoneZ, mc.zoneR), mc.finalX, mc.finalZ, phases(), orbitR, speed, oc[0], oc[1]);
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
        plan = planFor(mc);
        startedAt = System.currentTimeMillis();
        tick = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
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
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            boolean playing = match.isAlive(p.getUniqueId()) && p.getGameMode() != GameMode.SPECTATOR;
            boolean outside = !c.contains(l.getX(), l.getZ());
            if (drawTick) draw(p, c);
            if (tick % 10 == 0) updateBar(p, s);
            if (!playing) continue;
            if (outside) {
                if (tick % 20 == 0) plugin.msg().actionbar(p, "zone.outside", "distance", (int) Math.ceil(c.distanceOutside(l.getX(), l.getZ())));
                if (tick % interval == 0) {
                    p.damage(s.damageHp(), DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
                }
            }
        }
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
