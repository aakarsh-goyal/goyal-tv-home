package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * How often Home opened each app, so the grid can put the most used first. One counter per package in a small
 * SharedPreferences file, bumped on a launch from a tile: no polling, no usage-access permission. When the total
 * grows large every count is halved, so an app you stopped using drifts down instead of staying on top for ever.
 */
final class UsageCount {
    private static final String PREFS = "usage_count";
    private static final int HALVE_AT = 400;
    private static int revision;

    private UsageCount() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void record(Context c, String pkg) {
        if (pkg == null) return;
        SharedPreferences sp = prefs(c);
        Map<String, ?> all = sp.getAll();
        int total = 0;
        for (Object v : all.values()) total += (Integer) v;
        SharedPreferences.Editor e = sp.edit();
        if (total >= HALVE_AT) {
            for (Map.Entry<String, ?> en : all.entrySet()) e.putInt(en.getKey(), ((Integer) en.getValue()) / 2);
        }
        int now = sp.getInt(pkg, 0);
        if (total >= HALVE_AT) now /= 2;
        e.putInt(pkg, now + 1).apply();
        revision++;
    }

    static Map<String, Integer> all(Context c) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> en : prefs(c).getAll().entrySet()) out.put(en.getKey(), (Integer) en.getValue());
        return out;
    }

    /** Changes with every counted launch: Home compares it to know the order may have moved. */
    static int revision() {
        return revision;
    }
}
