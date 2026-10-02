package com.cartroyale.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Times {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_HM = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private Times() {
    }

    public static String clock(long epochMs, ZoneId zone) {
        return HM.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    public static String dayClock(long epochMs, ZoneId zone) {
        return DAY_HM.format(Instant.ofEpochMilli(epochMs).atZone(zone)) + " " + zone.getId();
    }

    /** "in 12m", "in 1h 05m", "now". */
    public static String relative(long epochMs) {
        long s = (epochMs - System.currentTimeMillis()) / 1000;
        if (s <= 0) return "now";
        if (s < 60) return "in " + s + "s";
        long m = s / 60;
        if (m < 60) return "in " + m + "m";
        return "in " + (m / 60) + "h " + String.format(Locale.ROOT, "%02d", m % 60) + "m";
    }

    /** "07:42" style duration for mm:ss. */
    public static String mmss(long seconds) {
        if (seconds < 0) seconds = 0;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
