package com.cartroyale.zone;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure zone timeline. Each phase waits, then shrinks linearly from the previous circle to the
 * next one. Every next circle sits fully inside the previous one, its centre drifting toward the
 * final centre. After the last phase the final circle's centre orbits a point on a ring.
 */
public final class ZoneMath {

    public record Phase(int waitSeconds, int shrinkSeconds, double endRadius, double damageHp, int damageIntervalTicks) {
    }

    public record Circle(double x, double z, double r) {
        public boolean contains(double px, double pz) {
            double dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= r * r;
        }

        public double distanceOutside(double px, double pz) {
            return Math.max(0, Math.hypot(px - x, pz - z) - r);
        }
    }

    public record Plan(Circle start, double finalX, double finalZ, List<Phase> phases,
                       double orbitRadius, double orbitSpeed, double orbitCenterX, double orbitCenterZ) {
    }

    /** What the zone looks like at a moment in time. */
    public record State(Circle circle, Circle target, int phase, boolean shrinking, boolean orbiting,
                        double secondsLeftInStep, double damageHp, int damageIntervalTicks) {
    }

    private ZoneMath() {
    }

    /** The circle at the end of every phase (index 0 = start circle). */
    public static List<Circle> circles(Plan p) {
        List<Circle> out = new ArrayList<>();
        Circle prev = p.start();
        out.add(prev);
        for (Phase ph : p.phases()) {
            double nr = Math.min(ph.endRadius(), prev.r());
            double dx = p.finalX() - prev.x(), dz = p.finalZ() - prev.z();
            double dist = Math.hypot(dx, dz);
            double room = prev.r() - nr;
            double move = Math.min(dist, room);
            double nx = prev.x(), nz = prev.z();
            if (dist > 1e-9) {
                nx += dx / dist * move;
                nz += dz / dist * move;
            }
            prev = new Circle(nx, nz, nr);
            out.add(prev);
        }
        return out;
    }

    /** Seconds from zone start until the last phase has finished shrinking. */
    public static double totalSeconds(Plan p) {
        double t = 0;
        for (Phase ph : p.phases()) t += ph.waitSeconds() + ph.shrinkSeconds();
        return t;
    }

    public static State at(Plan p, double t) {
        List<Circle> cs = circles(p);
        double clock = 0;
        for (int i = 0; i < p.phases().size(); i++) {
            Phase ph = p.phases().get(i);
            Circle from = cs.get(i), to = cs.get(i + 1);
            if (t < clock + ph.waitSeconds()) {
                return new State(from, to, i, false, false, clock + ph.waitSeconds() - t, ph.damageHp(), ph.damageIntervalTicks());
            }
            clock += ph.waitSeconds();
            if (t < clock + ph.shrinkSeconds()) {
                double f = ph.shrinkSeconds() <= 0 ? 1 : (t - clock) / ph.shrinkSeconds();
                Circle now = new Circle(lerp(from.x(), to.x(), f), lerp(from.z(), to.z(), f), lerp(from.r(), to.r(), f));
                return new State(now, to, i, true, false, clock + ph.shrinkSeconds() - t, ph.damageHp(), ph.damageIntervalTicks());
            }
            clock += ph.shrinkSeconds();
        }
        Circle last = cs.get(cs.size() - 1);
        Phase lastPhase = p.phases().isEmpty() ? new Phase(0, 0, last.r(), 2, 20) : p.phases().get(p.phases().size() - 1);
        if (p.orbitRadius() <= 0 || p.orbitSpeed() <= 0) {
            return new State(last, last, p.phases().size(), false, false, 0, lastPhase.damageHp(), lastPhase.damageIntervalTicks());
        }
        double a0 = Math.atan2(last.z() - p.orbitCenterZ(), last.x() - p.orbitCenterX());
        double omega = p.orbitSpeed() / p.orbitRadius();
        double a = a0 + omega * (t - clock);
        Circle orb = new Circle(p.orbitCenterX() + Math.cos(a) * p.orbitRadius(), p.orbitCenterZ() + Math.sin(a) * p.orbitRadius(), last.r());
        return new State(orb, orb, p.phases().size(), false, true, 0, lastPhase.damageHp(), lastPhase.damageIntervalTicks());
    }

    /**
     * The orbit centre: a point {@code radius} away from the final centre, toward the map centre,
     * so the final circle starts orbiting exactly where it stopped and the ring stays in the map.
     */
    public static double[] orbitCenter(double finalX, double finalZ, double mapX, double mapZ, double radius) {
        double dx = mapX - finalX, dz = mapZ - finalZ;
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) return new double[]{finalX + radius, finalZ};
        return new double[]{finalX + dx / d * radius, finalZ + dz / d * radius};
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
