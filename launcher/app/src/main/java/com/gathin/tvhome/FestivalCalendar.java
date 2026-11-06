package com.gathin.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hindu festivals (and Christmas) by date, from the file tools/festival_calendar.py builds out of open sources
 * (python-holidays and the real positions of the Sun and Moon). Read once, about 3 KB. Home asks what today is, or what
 * is coming within a week; nothing here runs on its own.
 */
final class FestivalCalendar {
    static final class Info {
        String key, name, greeting;
        int accent;
        String[] tidbits;
    }

    static final class Day {
        Info info;
        boolean today;
        int daysUntil;      // 0 for today
    }

    private static final int LOOKAHEAD_DAYS = 7;
    private static List<LocalDate> dates;
    private static List<String> keys;
    private static Map<String, Info> infos;

    private FestivalCalendar() {
    }

    /** How many days a festival is celebrated: Navratri is nine nights, the rest one day. */
    private static int length(String key) {
        return "navratri".equals(key) ? 9 : 1;
    }

    private static synchronized void load(Context c) {
        if (dates != null) return;
        List<LocalDate> d = new ArrayList<>();
        List<String> k = new ArrayList<>();
        Map<String, Info> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(R.raw.festivals), "UTF-8"))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#") || line.indexOf(',') < 0) continue;
                String[] p = line.split(",");
                d.add(LocalDate.parse(p[0].trim()));
                k.add(p[1].trim());
            }
        } catch (Exception ignored) {
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(R.raw.festival_info), "UTF-8"))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("\\|");
                if (p.length < 5) continue;
                Info i = new Info();
                i.key = p[0];
                i.name = p[1];
                i.greeting = p[2];
                i.accent = 0xFF000000 | Integer.parseInt(p[3], 16);
                i.tidbits = java.util.Arrays.copyOfRange(p, 4, p.length);
                m.put(i.key, i);
            }
        } catch (Exception ignored) {
        }
        dates = d;
        keys = k;
        infos = m;
    }

    /** Today's festival if there is one, otherwise the nearest one in the next week, otherwise null. */
    static Day current(Context c, LocalDate today) {
        load(c);
        Day best = null;
        int bestStart = Integer.MIN_VALUE;
        for (int i = 0; i < dates.size(); i++) {
            LocalDate start = dates.get(i);
            String key = keys.get(i);
            Info info = infos.get(key);
            if (info == null) continue;
            long fromStart = java.time.temporal.ChronoUnit.DAYS.between(start, today);
            if (fromStart >= 0 && fromStart < length(key)) {
                int s = (int) start.toEpochDay();
                if (best == null || !best.today || s > bestStart) {      // the latest start wins (Dussehra over Navratri)
                    best = new Day();
                    best.info = info;
                    best.today = true;
                    best.daysUntil = 0;
                    bestStart = s;
                }
            } else if (fromStart < 0 && -fromStart <= LOOKAHEAD_DAYS && (best == null || !best.today)) {
                if (best == null || -fromStart < best.daysUntil) {
                    best = new Day();
                    best.info = info;
                    best.today = false;
                    best.daysUntil = (int) -fromStart;
                }
            }
        }
        return best;
    }
}
