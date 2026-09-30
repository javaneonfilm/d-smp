package com.cartroyale.schedule;

import com.cartroyale.game.Mode;
import com.cartroyale.game.Modifier;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** One recurring game: days of the week, a time, and the game settings. */
public final class ScheduleEntry {

    public String id;
    public final Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
    public LocalTime time = LocalTime.of(16, 0);
    public Mode mode = Mode.LT;
    public final Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
    public int squad = 1;
    public int purgeCap = 100;
    public String map;
    public boolean enabled = true;

    /** The next start strictly after {@code after}, in the given zone, or null if no days are set. */
    public ZonedDateTime nextAfter(ZonedDateTime after, ZoneId zone) {
        if (days.isEmpty()) return null;
        ZonedDateTime base = after.withZoneSameInstant(zone);
        LocalDate d = base.toLocalDate();
        for (int i = 0; i < 8; i++) {
            LocalDate day = d.plusDays(i);
            if (!days.contains(day.getDayOfWeek())) continue;
            ZonedDateTime cand = ZonedDateTime.of(day, time, zone);
            if (cand.isAfter(base)) return cand;
        }
        return null;
    }

    /** Parses "MON,TUE" / "daily" / "weekdays" / "weekends". Unknown tokens are ignored. */
    public static Set<DayOfWeek> parseDays(String s) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (s == null) return out;
        for (String raw : s.split("[,\\s]+")) {
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            switch (t) {
                case "DAILY", "ALL", "EVERYDAY" -> out.addAll(EnumSet.allOf(DayOfWeek.class));
                case "WEEKDAYS" -> out.addAll(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
                case "WEEKENDS" -> out.addAll(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
                default -> {
                    for (DayOfWeek d : DayOfWeek.values()) {
                        if (d.name().startsWith(t) && t.length() >= 2) out.add(d);
                    }
                }
            }
        }
        return out;
    }

    public static String daysToString(Set<DayOfWeek> days) {
        if (days.size() == 7) return "daily";
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (!days.contains(d)) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(d.name(), 0, 3);
        }
        return sb.toString();
    }

    /** Parses "16:00" or "4:00 PM". Returns null when it can't. */
    public static LocalTime parseTime(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT).replace(".", "");
        boolean pm = t.endsWith("PM"), am = t.endsWith("AM");
        if (pm || am) t = t.substring(0, t.length() - 2).trim();
        String[] p = t.split(":");
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (pm && h < 12) h += 12;
            if (am && h == 12) h = 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return LocalTime.of(h, m);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
