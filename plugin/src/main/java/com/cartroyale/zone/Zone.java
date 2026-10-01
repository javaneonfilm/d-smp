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
    private boolean wasGrace;
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

    /**
     * The phases from zone.yml, timed so the zone is fully closed {@code minutes} after the game
     * starts (deploy included). zone.yml's seconds only set the proportions. With close-fully the
     * last circle shrinks to nothing. No shrink is faster than max-speed blocks/s: a phase that
     * would be takes the time out of its own wait instead.
     */
    public List<ZoneMath.Phase> phasesFor(int minutes, double startR) {
        List<ZoneMath.Phase> base = phases();
        double written = plannedSeconds();
        double want = Math.max(60, minutes * 60.0 - plugin.getConfig().getInt("deploy.planned-seconds", 60));
        if (written <= 0 || base.isEmpty()) return base;
        double f = want / written;
        boolean closeFully = yml.get().getBoolean("close-fully", true);
        double maxSpeed = Math.max(0.1, yml.get().getDouble("max-speed", 1.5));
        double minWait = 10;
        int n = base.size();
        double[] w = new double[n], sh = new double[n], r = new double[n];
        double prev = startR;
        for (int i = 0; i < n; i++) {
            ZoneMath.Phase p = base.get(i);
            r[i] = closeFully && i == n - 1 ? 0 : Math.min(p.endRadius(), prev);
            w[i] = p.waitSeconds() * f;
            sh[i] = p.shrinkSeconds() * f;
            double need = (prev - r[i]) / maxSpeed;
            if (sh[i] < need) {
                double extra = need - sh[i];
                sh[i] = need;
                w[i] = Math.max(minWait, w[i] - extra);
            }
            prev = r[i];
        }
        // Taking time from waits can't always cover it; squeeze the waits so the total still fits.
        double total = 0, waits = 0;
        for (int i = 0; i < n; i++) {
            total += w[i] + sh[i];
            waits += w[i];
        }
        if (total > want && waits > 0) {
            double k = Math.max(0, (waits - (total - want)) / waits);
            for (int i = 0; i < n; i++) w[i] *= k;
        }
        List<ZoneMath.Phase> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ZoneMath.Phase p = base.get(i);
            out.add(new ZoneMath.Phase((int) Math.round(w[i]), (int) Math.round(sh[i]), r[i], p.damageHp(), p.damageIntervalTicks()));
        }
        return out;
    }

    public ZoneMath.Plan planFor(MapConfig mc) {
        return planFor(mc, plugin.defaults().minutes);
    }

    public ZoneMath.Plan planFor(MapConfig mc, int minutes) {
        boolean closeFully = yml.get().getBoolean("close-fully", true);
        // A circle of nothing has nowhere to orbit to.
        double orbitR = closeFully ? 0 : yml.get().getDouble("orbit.radius", 100), speed = yml.get().getDouble("orbit.speed", 4.3);
        double[] oc = ZoneMath.orbitCenter(mc.finalX, mc.finalZ, mc.centerX, mc.centerZ, Math.max(1, orbitR));
        return new ZoneMath.Plan(new ZoneMath.Circle(mc.zoneX, mc.zoneZ, mc.zoneR), mc.finalX, mc.finalZ, phasesFor(minutes, mc.zoneR), orbitR, speed, oc[0], oc[1]);
    }

    public boolean running() {
        return task != null;
    }

    /** Grace period at the start (match.grace-minutes): no PvP and the zone holds still. Added on top of the zone time. */
    public long graceMs() {
        return Math.max(0, plugin.getConfig().getInt("match.grace-minutes", 5)) * 60_000L;
    }

    public boolean inGrace() {
        return task != null && System.currentTimeMillis() - startedAt < graceMs();
    }

    public int graceSecondsLeft() {
        return (int) Math.max(0, Math.ceil((graceMs() - (System.currentTimeMillis() - startedAt)) / 1000.0));
    }

    public ZoneMath.State now() {
        if (plan == null) return null;
        // During the grace period the clock is negative, which ZoneMath reads as a longer first wait.
        return ZoneMath.at(plan, (System.currentTimeMillis() - startedAt - graceMs()) / 1000.0);
    }

    public void start(Match m, World w, MapConfig mc) {
        stop();
        match = m;
        world = w;
        plan = planFor(mc, m.row.s.minutes);
        startedAt = System.currentTimeMillis();
        tick = 0;
        wall.model(yml.get().getString("wall.item-model", "lodestone:border"));
        wasGrace = graceMs() > 0;
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
        boolean grace = inGrace();
        if (wasGrace && !grace && match != null) {
            for (Player p : world.getPlayers()) {
                plugin.msg().send(p, "zone.grace-over");
                p.showTitle(net.kyori.adventure.title.Title.title(plugin.msg().get("zone.grace-over-title"), plugin.msg().get("zone.grace-over-sub")));
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
            }
        }
        wasGrace = grace;
        // Local copy: a zone kill can end the match (and null the field) halfway through the loop.
        Match match = this.match;
        if (world == null || match == null) return;
        ZoneMath.State s = now();
        ZoneMath.Circle c = s.circle();
        int interval = Math.max(1, s.damageIntervalTicks());
        boolean drawTick = tick % Math.max(1, yml.get().getInt("particles.every-ticks", 4)) == 0;
        String mode = yml.get().getString("wall.mode", "shader");
        boolean shader = !"particles".equalsIgnoreCase(mode), all = "shader-all".equalsIgnoreCase(mode);
        double baseY = yml.get().getDouble("wall.base-y", 0), reanchor = yml.get().getDouble("wall.reanchor-distance", 48);
        wall.beginTick();
        Set<UUID> walled = new HashSet<>();
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            boolean playing = match.isAlive(p.getUniqueId()) && p.getGameMode() != GameMode.SPECTATOR;
            boolean outside = !c.contains(l.getX(), l.getZ());
            if (shader && wall.update(p, c, baseY, reanchor, all)) walled.add(p.getUniqueId());
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
        String key = inGrace() ? "zone.bar-grace" : s.orbiting() ? "zone.bar-orbit" : s.shrinking() ? "zone.bar-shrinking" : "zone.bar-waiting";
        int secs = inGrace() ? graceSecondsLeft() : (int) Math.ceil(s.secondsLeftInStep());
        bar.name(plugin.msg().get(key, "seconds", secs, "time", String.format("%d:%02d", secs / 60, secs % 60), "phase", s.phase() + 1,
            "phases", plan.phases().size(), "radius", (int) Math.round(s.circle().r())));
        double total = ZoneMath.totalSeconds(plan) + graceMs() / 1000.0;
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
