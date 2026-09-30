package com.cartroyale;

import com.cartroyale.econ.ShopMath;
import com.cartroyale.game.Purge;
import com.cartroyale.game.TeamMath;
import com.cartroyale.loot.ChestSpots;
import com.cartroyale.loot.LootRoller;
import com.cartroyale.loot.Rarity;
import com.cartroyale.schedule.ScheduleEntry;
import com.cartroyale.zone.ZoneMath;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LogicTest {

    // ---------------- PURGE ----------------

    private static List<Purge.Candidate<String>> lobby200() {
        List<Purge.Candidate<String>> l = new ArrayList<>();
        for (int i = 0; i < 150; i++) l.add(new Purge.Candidate<>("d" + i, 1.0, false));
        for (int i = 0; i < 30; i++) l.add(new Purge.Candidate<>("v" + i, 0.15, false));
        for (int i = 0; i < 19; i++) l.add(new Purge.Candidate<>("p" + i, 0, true));
        l.add(new Purge.Candidate<>("host", 0, true));
        return l;
    }

    @Test
    void purgeRemovesExactlyTheSurplusAndNeverImmune() {
        Purge.Result<String> r = Purge.run(lobby200(), 120, new Random(1));
        assertEquals(80, r.purged().size());
        assertEquals(120, r.kept().size());
        for (String id : r.purged()) assertFalse(id.startsWith("p") || id.equals("host"), id);
        assertEquals(200, new HashSet<String>() {{ addAll(r.kept()); addAll(r.purged()); }}.size());
    }

    @Test
    void vipsArePurgedFarLessOften() {
        Random rng = new Random(42);
        long vip = 0, def = 0;
        for (int run = 0; run < 10_000; run++) {
            for (String id : Purge.run(lobby200(), 120, rng).purged()) {
                if (id.startsWith("v")) vip++;
                else def++;
            }
        }
        double vipRate = vip / (30.0 * 10_000), defRate = def / (150.0 * 10_000);
        assertTrue(vipRate < defRate / 3, "vip " + vipRate + " default " + defRate);
    }

    @Test
    void underCapPurgesNobody() {
        assertTrue(Purge.run(lobby200().subList(0, 50), 120, new Random()).purged().isEmpty());
    }

    @Test
    void immuneOverflowPicksAmongImmune() {
        List<Purge.Candidate<String>> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) l.add(new Purge.Candidate<>("p" + i, 0, true));
        for (int i = 0; i < 5; i++) l.add(new Purge.Candidate<>("d" + i, 1, false));
        Purge.Result<String> r = Purge.run(l, 8, new Random(3));
        assertEquals(8, r.kept().size());
        assertTrue(r.immuneOverflow());
        for (String k : r.kept()) assertTrue(k.startsWith("p"));
    }

    // ---------------- TEAMS ----------------

    @Test
    void duosAutofillWithOddCount() {
        List<List<String>> formed = List.of(List.of("a", "b"), List.of("c"));
        List<String> solo = List.of("d", "e", "f", "g");
        List<List<String>> t = TeamMath.autofill(formed, solo, 2, new Random(5));
        int total = 0, small = 0;
        for (List<String> team : t) {
            total += team.size();
            assertTrue(team.size() <= 2);
            if (team.size() < 2) small++;
        }
        assertEquals(7, total);
        assertEquals(1, small);
        assertTrue(t.stream().anyMatch(x -> x.containsAll(List.of("a", "b"))));
    }

    @Test
    void squadsKeepPartialTeamsTogether() {
        List<List<String>> t = TeamMath.autofill(List.of(List.of("a", "b", "c")), List.of("x", "y", "z", "w", "q"), 4, new Random(9));
        assertTrue(t.stream().anyMatch(x -> x.size() == 4 && x.containsAll(List.of("a", "b", "c"))));
        assertEquals(8, t.stream().mapToInt(List::size).sum());
    }

    // ---------------- LOOT ----------------

    private static Map<Rarity, Double> weights() {
        Map<Rarity, Double> w = new EnumMap<>(Rarity.class);
        w.put(Rarity.COMMON, 60.0);
        w.put(Rarity.RARE, 25.0);
        w.put(Rarity.EPIC, 12.0);
        w.put(Rarity.LEGENDARY, 3.0);
        return w;
    }

    private static Map<Rarity, Double> lucky() {
        Map<Rarity, Double> m = new EnumMap<>(Rarity.class);
        m.put(Rarity.RARE, 1.5);
        m.put(Rarity.EPIC, 2.0);
        m.put(Rarity.LEGENDARY, 3.0);
        return m;
    }

    private static List<LootRoller.Entry<String>> table() {
        return List.of(
            new LootRoller.Entry<>("rail", Rarity.COMMON, 12, 46, false),
            new LootRoller.Entry<>("bread", Rarity.COMMON, 2, 8, false),
            new LootRoller.Entry<>("pearl", Rarity.RARE, 1, 3, true),
            new LootRoller.Entry<>("tnt_cart", Rarity.RARE, 1, 2, false),
            new LootRoller.Entry<>("shield", Rarity.EPIC, 1, 2, false),
            new LootRoller.Entry<>("gapple", Rarity.LEGENDARY, 1, 1, false));
    }

    @Test
    void chestSlotsAndAmountsStayInBounds() {
        Random rng = new Random(7);
        var o = new LootRoller.Options(3, 8, 27, weights(), false, lucky(), true);
        for (int i = 0; i < 5000; i++) {
            var slots = LootRoller.roll(table(), o, rng);
            assertTrue(slots.size() >= 3 && slots.size() <= 8, "slots " + slots.size());
            Set<Integer> used = new HashSet<>();
            for (var s : slots) {
                assertTrue(used.add(s.slot()), "duplicate slot");
                assertTrue(s.slot() >= 0 && s.slot() < 27);
                var e = table().stream().filter(t -> t.item().equals(s.item())).findFirst().orElseThrow();
                assertTrue(s.amount() >= e.min() && s.amount() <= e.max(), s.item() + " " + s.amount());
            }
        }
    }

    @Test
    void luckierLootRaisesRareDrops() {
        int normal = rareish(false), luckier = rareish(true);
        assertTrue(luckier > normal * 1.2, "normal " + normal + " luckier " + luckier);
    }

    private int rareish(boolean luck) {
        Random rng = new Random(123);
        var o = new LootRoller.Options(3, 8, 27, weights(), luck, lucky(), true);
        int n = 0;
        for (int i = 0; i < 5000; i++) {
            for (var s : LootRoller.roll(table(), o, rng)) if (!s.item().equals("rail") && !s.item().equals("bread")) n++;
        }
        return n;
    }

    @Test
    void pearlsOffRemovesPearls() {
        Random rng = new Random(8);
        var o = new LootRoller.Options(3, 8, 27, weights(), false, lucky(), false);
        for (int i = 0; i < 3000; i++) for (var s : LootRoller.roll(table(), o, rng)) assertNotEquals("pearl", s.item());
    }

    // ---------------- CHEST SPOTS ----------------

    @Test
    void chestSpotsRespectSpacingAndTierSplit() {
        List<ChestSpots.Spot> cand = new ArrayList<>();
        for (int x = -400; x <= 400; x += 2) for (int z = -400; z <= 400; z += 2) cand.add(new ChestSpots.Spot(x, 64, z));
        List<ChestSpots.Spot> picked = ChestSpots.pick(cand, 24, 300, new Random(11));
        assertEquals(300, picked.size());
        for (int i = 0; i < picked.size(); i++) {
            for (int j = i + 1; j < picked.size(); j++) {
                var a = picked.get(i);
                var b = picked.get(j);
                double d = Math.hypot(a.x() - b.x(), a.z() - b.z());
                assertTrue(d >= 24, "too close " + d);
            }
        }
        var placed = ChestSpots.assignTiers(picked, new double[]{0.6, 0.3, 0.1}, new Random(2));
        int[] c = new int[4];
        for (var p : placed) c[p.tier()]++;
        assertEquals(180, c[1]);
        assertEquals(90, c[2]);
        assertEquals(30, c[3]);
    }

    // ---------------- ZONE ----------------

    private static ZoneMath.Plan plan() {
        List<ZoneMath.Phase> ph = List.of(
            new ZoneMath.Phase(60, 60, 200, 2, 20),
            new ZoneMath.Phase(45, 45, 100, 2, 20),
            new ZoneMath.Phase(30, 30, 40, 3, 20),
            new ZoneMath.Phase(20, 20, 5, 4, 20));
        double[] oc = ZoneMath.orbitCenter(150, 80, 0, 0, 100);
        return new ZoneMath.Plan(new ZoneMath.Circle(0, 0, 350), 150, 80, ph, 100, 4.3, oc[0], oc[1]);
    }

    @Test
    void everyCircleSitsInsideThePreviousOne() {
        var cs = ZoneMath.circles(plan());
        for (int i = 1; i < cs.size(); i++) {
            var a = cs.get(i - 1);
            var b = cs.get(i);
            assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) + b.r() <= a.r() + 1e-6, "phase " + i);
        }
        assertEquals(5, cs.get(cs.size() - 1).r(), 1e-9);
    }

    @Test
    void zonePausesThenShrinks() {
        var p = plan();
        var s = ZoneMath.at(p, 30);
        assertFalse(s.shrinking());
        assertEquals(350, s.circle().r(), 1e-9);
        var mid = ZoneMath.at(p, 90);
        assertTrue(mid.shrinking());
        assertEquals(275, mid.circle().r(), 1e-6);
    }

    @Test
    void finalCircleOrbitsAtWalkingSpeedOnRadius100() {
        var p = plan();
        double end = ZoneMath.totalSeconds(p);
        var last = ZoneMath.circles(p).get(4);
        var at0 = ZoneMath.at(p, end);
        assertTrue(at0.orbiting());
        assertEquals(last.x(), at0.circle().x(), 1e-6);
        assertEquals(last.z(), at0.circle().z(), 1e-6);
        var a = ZoneMath.at(p, end + 10);
        var b = ZoneMath.at(p, end + 11);
        double step = Math.hypot(a.circle().x() - b.circle().x(), a.circle().z() - b.circle().z());
        assertEquals(4.3, step, 0.01);
        assertEquals(100, Math.hypot(a.circle().x() - p.orbitCenterX(), a.circle().z() - p.orbitCenterZ()), 1e-6);
    }

    // ---------------- DAILY SHOP ----------------

    private static List<ShopMath.Item> cosmetics() {
        List<ShopMath.Item> l = new ArrayList<>();
        for (String cat : List.of("kill", "explosion", "suffix")) {
            for (Rarity r : Rarity.values()) for (int i = 0; i < 3; i++) l.add(new ShopMath.Item(cat + "_" + r + i, cat, r));
        }
        return l;
    }

    private static Map<Rarity, Double> odds() {
        Map<Rarity, Double> m = new EnumMap<>(Rarity.class);
        m.put(Rarity.COMMON, 60.0);
        m.put(Rarity.RARE, 28.0);
        m.put(Rarity.EPIC, 10.0);
        m.put(Rarity.LEGENDARY, 2.0);
        return m;
    }

    @Test
    void shopIsTheSameAllDayAndHasTwoPerCategory() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        var a = ShopMath.pick(cosmetics(), List.of("kill", "explosion", "suffix"), 2, odds(), new Random(ShopMath.seed(d, 0)));
        var b = ShopMath.pick(cosmetics(), List.of("kill", "explosion", "suffix"), 2, odds(), new Random(ShopMath.seed(d, 0)));
        assertEquals(a, b);
        assertEquals(6, a.size());
        assertEquals(6, new HashSet<>(a).size());
        var c = ShopMath.pick(cosmetics(), List.of("kill", "explosion", "suffix"), 2, odds(), new Random(ShopMath.seed(d.plusDays(1), 0)));
        assertNotEquals(a, c);
    }

    @Test
    void legendaryIsRare() {
        Random rng = new Random(77);
        Map<Rarity, Integer> n = new HashMap<>();
        for (int i = 0; i < 10_000; i++) n.merge(ShopMath.rollRarity(odds(), rng), 1, Integer::sum);
        int leg = n.getOrDefault(Rarity.LEGENDARY, 0);
        assertTrue(leg > 100 && leg < 350, "legendary " + leg);
        assertTrue(n.get(Rarity.COMMON) > 5500);
    }

    // ---------------- HUD ----------------

    @Test
    void hudShiftsAddUpExactly() throws Exception {
        var m = Class.forName("com.cartroyale.lobby.HudText").getDeclaredMethod("shift", int.class);
        m.setAccessible(true);
        for (int px : new int[]{-151, -83, -1, 0, 1, 37, 255}) {
            String s = (String) m.invoke(null, px);
            int sum = 0;
            for (char c : s.toCharArray()) sum += c >= '' ? 1 << (c - '') : -(1 << (c - ''));
            assertEquals(px, sum, "shift " + px);
        }
    }

    // ---------------- SCHEDULE ----------------

    @Test
    void scheduleParsesAndFindsNextStart() {
        assertEquals(LocalTime.of(16, 0), ScheduleEntry.parseTime("4:00 PM"));
        assertEquals(LocalTime.of(20, 30), ScheduleEntry.parseTime("20:30"));
        assertEquals(LocalTime.of(0, 15), ScheduleEntry.parseTime("12:15am"));
        assertNull(ScheduleEntry.parseTime("25:00"));
        assertEquals(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), ScheduleEntry.parseDays("weekends"));
        assertEquals(7, ScheduleEntry.parseDays("daily").size());
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), ScheduleEntry.parseDays("mon, fri"));

        ScheduleEntry e = new ScheduleEntry();
        e.time = LocalTime.of(16, 0);
        e.days.clear();
        e.days.add(DayOfWeek.FRIDAY);
        ZoneId utc = ZoneId.of("UTC");
        // 2026-10-01 is a Thursday
        ZonedDateTime thu = ZonedDateTime.of(2026, 10, 1, 17, 0, 0, 0, utc);
        assertEquals(ZonedDateTime.of(2026, 10, 2, 16, 0, 0, 0, utc), e.nextAfter(thu, utc));
        ZonedDateTime fri = ZonedDateTime.of(2026, 10, 2, 16, 0, 0, 0, utc);
        assertEquals(ZonedDateTime.of(2026, 10, 9, 16, 0, 0, 0, utc), e.nextAfter(fri, utc));
    }
}
