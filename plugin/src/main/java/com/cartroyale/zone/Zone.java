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
    private int lastTickSound = -1;
    private final ZoneWall wall;
    /** Without the pack: a plain red bar. With it: the PINK bar (invisible in the pack) carries the hexagon row. */
    private final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
    private final BossBar hexBar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.PINK, BossBar.Overlay.PROGRESS);

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
            // With close-fully the last phase ends at final.radius; the circle then roams and closes.
            r[i] = closeFully && i == n - 1 ? Math.min(prev, Math.max(0, yml.get().getDouble("final.radius", 6))) : Math.min(p.endRadius(), prev);
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
        if (closeFully) {
            // The final circle (final.radius) roams on a ring for final.roam-minutes, the last
            // final.close-seconds of which it spends shrinking to nothing.
            double roamR = yml.get().getDouble("final.roam-radius", 25), speed = yml.get().getDouble("final.roam-speed", 1.5);
            double close = Math.max(0, yml.get().getDouble("final.close-seconds", 30));
            double roam = Math.max(0, yml.get().getDouble("final.roam-minutes", 5) * 60 - close);
            double[] oc = ZoneMath.orbitCenter(mc.finalX, mc.finalZ, mc.centerX, mc.centerZ, Math.max(1, roamR));
            return new ZoneMath.Plan(new ZoneMath.Circle(mc.zoneX, mc.zoneZ, mc.zoneR), mc.finalX, mc.finalZ, phasesFor(minutes, mc.zoneR),
                roamR, speed, oc[0], oc[1], roam, close);
        }
        double orbitR = yml.get().getDouble("orbit.radius", 100), speed = yml.get().getDouble("orbit.speed", 4.3);
        double[] oc = ZoneMath.orbitCenter(mc.finalX, mc.finalZ, mc.centerX, mc.centerZ, Math.max(1, orbitR));
        return new ZoneMath.Plan(new ZoneMath.Circle(mc.zoneX, mc.zoneZ, mc.zoneR), mc.finalX, mc.finalZ, phasesFor(minutes, mc.zoneR), orbitR, speed, oc[0], oc[1]);
    }

    public boolean running() {
        return task != null;
    }

    /** Grace period at the start (match.grace-seconds): no PvP and the zone holds still. Added on top of the zone time. */
    public long graceMs() {
        return Math.max(0, plugin.getConfig().getInt("match.grace-seconds", 120)) * 1000L;
    }

    public boolean inGrace() {
        return task != null && System.currentTimeMillis() - startedAt < graceMs();
    }

    public int graceSecondsLeft() {
        return (int) Math.max(0, Math.ceil((graceMs() - (System.currentTimeMillis() - startedAt)) / 1000.0));
    }

    /**
     * zone.yml no-healing-outside (off by default): true while a living player stands outside the
     * zone, so health can't come back there. Healing works normally unless that is turned on.
     */
    public boolean blocksHealing(Player p) {
        if (world == null || match == null || !p.getWorld().equals(world) || !match.isAlive(p.getUniqueId())) return false;
        if (!yml.get().getBoolean("no-healing-outside", false) || inGrace()) return false;
        ZoneMath.State s = now();
        return s != null && !s.circle().contains(p.getLocation().getX(), p.getLocation().getZ());
    }

    /** Minutes the final circle roams before it is fully closed (0 when it doesn't close). */
    public int roamMinutes() {
        return yml.get().getBoolean("close-fully", true) ? (int) Math.ceil(yml.get().getDouble("final.roam-minutes", 5)) : 0;
    }

    /** True once the phases are done: the small final circle roaming before it closes (or closed). */
    public boolean inFinalStage(ZoneMath.State s) {
        return plan != null && s != null && s.phase() >= plan.phases().size();
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
        String model = yml.get().getString("wall.item-model", "cartzone:zone_wall");
        // Older zone.yml files still name the borrowed wall, which the pack no longer has.
        wall.model("lodestone:border".equals(model) ? "cartzone:zone_wall" : model);
        wasGrace = graceMs() > 0;
        lastTickSound = -1;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        wall.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bar);
            p.hideBossBar(hexBar);
        }
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
            if (shader && wall.update(p, s, baseY, reanchor, all)) walled.add(p.getUniqueId());
            else if (drawTick) draw(p, c);
            if (tick % 10 == 0) updateBar(p, s);
            if (!playing) continue;
            if (outside) {
                if (tick % 20 == 0) plugin.msg().actionbar(p, "zone.outside", "distance", (int) Math.ceil(c.distanceOutside(l.getX(), l.getZ())));
                if (tick % interval == 0) zoneDamage(p, s.damageHp(), c.distanceOutside(l.getX(), l.getZ()));
            }
        }
        wall.retain(walled);
    }

    /**
     * Zone damage grows with distance outside (zone.yml damage-per-block, capped at max-damage)
     * and goes straight through armor, Protection and Resistance. Absorption hearts (golden apples)
     * don't soak it either unless zone.yml damage-ignores-absorption is false. A hit that would kill
     * goes through the normal damage path, so the death is still credited to the zone (and any
     * last attacker) like before.
     */
    private void zoneDamage(Player p, double base, double outside) {
        // damage-min / damage-max in health points (2 = one heart): half a heart at the edge,
        // rising with every block outside, never more than a heart and a half per hit.
        double perBlock = yml.get().getDouble("damage-per-block", 0.15);
        double min = yml.get().getDouble("damage-min", 1.0), max = Math.max(min, yml.get().getDouble("damage-max", 3.0));
        double dmg = ZoneMath.damage(outside, min, max, perBlock);
        double abs = yml.get().getBoolean("damage-ignores-absorption", true) ? 0 : p.getAbsorptionAmount();
        if (abs > 0) {
            double used = Math.min(abs, dmg);
            p.setAbsorptionAmount(abs - used);
            dmg -= used;
        }
        if (dmg <= 0) {
            p.playHurtAnimation(0);
            return;
        }
        if (p.getHealth() - dmg <= 0) {
            p.damage(1_000_000, DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
            return;
        }
        p.setHealth(p.getHealth() - dmg);
        p.playHurtAnimation(0);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        // Keeps the last-damage cause on the zone, which is how deaths get attributed.
        p.setLastDamageCause(new org.bukkit.event.entity.EntityDamageEvent(p, org.bukkit.event.entity.EntityDamageEvent.DamageCause.WORLD_BORDER,
            DamageSource.builder(DamageType.OUTSIDE_BORDER).build(), dmg));
    }

    /** For /cart diag: how many players are seeing the shader wall right now. */
    public int wallViewers() {
        return wall.count();
    }

    private long barBuiltAt = -1;

    /** Builds both bars at most once per tick, then shows each player the one that fits their pack. */
    private void updateBar(Player p, ZoneMath.State s) {
        if (barBuiltAt != tick) {
            barBuiltAt = tick;
            buildBars(s);
        }
        boolean hex = plugin.presence().hasPack(p.getUniqueId());
        p.showBossBar(hex ? hexBar : bar);
        p.hideBossBar(hex ? bar : hexBar);
    }

    private void buildBars(ZoneMath.State s) {
        boolean grace = inGrace();
        boolean last = !grace && s.phase() >= plan.phases().size();
        boolean closed = last && s.secondsLeftInStep() <= 0;
        boolean roaming = last && !closed;
        int secs = grace ? graceSecondsLeft() : (int) Math.ceil(s.secondsLeftInStep());
        // Grace counts down to PvP: green, yellow for the last 30 s, blinking red for the last 10.
        String key = grace ? (secs <= 10 ? "zone.hud-pvp-in-now" : secs <= 30 ? "zone.hud-pvp-in-soon" : "zone.hud-pvp-in")
            : closed ? "zone.hud-closed" : roaming ? "zone.hud-final" : s.shrinking() ? "zone.hud-shrinking" : "zone.hud-waiting";
        net.kyori.adventure.text.Component title = plugin.msg().get(key, "time", String.format("%d:%02d", secs / 60, secs % 60),
            "phase", s.phase() + 1, "phases", plan.phases().size(),
            "radius", (int) Math.round(s.circle().r()), "target", (int) Math.round(s.target().r()));
        double zoneTotal = ZoneMath.endSeconds(plan);
        double elapsed = (System.currentTimeMillis() - startedAt) / 1000.0 - graceMs() / 1000.0;
        double left = grace ? graceSecondsLeft() / Math.max(1.0, graceMs() / 1000.0)
            : zoneTotal <= 0 ? 0 : Math.max(0, Math.min(1, 1 - elapsed / zoneTotal));
        // Grace counts down in green (yellow for the last 30 s, blinking red for the last 10);
        // after that the bar is the zone's time left, red while it is shrinking.
        int color = !grace ? (s.shrinking() || closed ? 0xFF5D5D : 0x8CC8DC)
            : secs <= 10 ? ((tick / 10) % 2 == 0 ? 0xFF4D4D : 0x626875) : secs <= 30 ? 0xFACC15 : 0x4ADE80;
        if (grace && secs <= 5 && secs != lastTickSound) {
            lastTickSound = secs;
            for (Player p : world.getPlayers()) p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.4f);
        }
        hexBar.name(com.cartroyale.lobby.HudText.zoneBar(title, left, net.kyori.adventure.text.format.TextColor.color(color)));
        bar.name(title);
        bar.color(grace ? (secs <= 30 ? BossBar.Color.YELLOW : BossBar.Color.GREEN) : BossBar.Color.RED);
        bar.progress((float) left);
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
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0x8CC8DC), 1.6f);
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
