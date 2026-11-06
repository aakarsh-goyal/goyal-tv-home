package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AirPlay on demand. The TV's own receiver (the FastCast system app) advertises as an Apple TV; left running it holds
 * about 60 to 110 MB and its analytics for ever. Here it is OFF until the Control Centre's AirPlay tile turns it on:
 * on starts the app's engine in the background (its screen is shown for a moment, then Home returns), off stops it.
 * While on, one alarm checks after 25 minutes whether anything is being cast; if not it stops the receiver, and if so
 * it looks again 15 minutes later. No polling, no service of ours.
 *
 * Stopping another app needs the shell's rights: see LocalAdb. Only the fixed commands below are ever sent.
 */
final class AirPlayControl {
    static final String PKG = "com.creative.fastscreen.tv";
    private static final long IDLE_MS = 25 * 60 * 1000L, RECHECK_MS = 15 * 60 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** @param on null while unknown; note a short word for the tile's second line, or null. */
    interface Listener {
        void state(Boolean on, String note);
    }

    private AirPlayControl() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("airplay", Context.MODE_PRIVATE);
    }

    static boolean cachedOn(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    /** Asks the system whether the receiver's process is really there. */
    static void refresh(Context c, Listener l) {
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            LocalAdb.Result r = LocalAdb.shell(app, "pidof " + PKG, 1500);
            if (!r.connected) {
                post(l, cachedOn(app), r.needsApproval ? "Approve on screen" : null);
                return;
            }
            boolean on = !r.output.trim().isEmpty();
            prefs(app).edit().putBoolean("on", on).apply();
            post(l, on, null);
        });
    }

    /**
     * Keeps "off by default" true: when Home comes to the front (at most every 10 minutes) and the receiver is running
     * although it was not switched on here (after a reboot, or an app launched FastCast), stop it. After a reboot the idle
     * alarm is gone, so a receiver that is on gets its timer again.
     */
    static void reconcile(Context c) {
        final Context app = c.getApplicationContext();
        SharedPreferences sp = prefs(app);
        long now = System.currentTimeMillis();
        if (now - sp.getLong("reconciled", 0) < 10 * 60 * 1000L) return;
        sp.edit().putLong("reconciled", now).apply();
        IO.execute(() -> {
            LocalAdb.Result r = LocalAdb.shell(app, "pidof " + PKG, 1500);
            if (!r.connected) return;
            boolean running = !r.output.trim().isEmpty();
            boolean wantOn = cachedOn(app);
            if (running && !wantOn) LocalAdb.shell(app, "am force-stop " + PKG, 1500);
            else if (!running && wantOn) sp.edit().putBoolean("on", false).apply();
            else if (running) scheduleIdleCheck(app, IDLE_MS);
        });
    }

    static void toggle(Context c, Listener l) {
        final Context app = c.getApplicationContext();
        if (cachedOn(app)) turnOff(app, l);
        else turnOn(app, l);
    }

    private static void turnOn(Context app, Listener l) {
        Intent launch = app.getPackageManager().getLeanbackLaunchIntentForPackage(PKG);
        if (launch == null) launch = app.getPackageManager().getLaunchIntentForPackage(PKG);
        if (launch == null) {
            post(l, false, "Not installed");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        app.startActivity(launch);
        prefs(app).edit().putBoolean("on", true).apply();
        post(l, true, "Starting");
        // The engine runs in the app's services; once it is up, bring Home back.
        MAIN.postDelayed(() -> {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            app.startActivity(home);
            post(l, true, null);
        }, 1600);
        scheduleIdleCheck(app, IDLE_MS);
    }

    private static void turnOff(Context app, Listener l) {
        post(l, false, "Stopping");
        IO.execute(() -> {
            LocalAdb.Result r = LocalAdb.shell(app, "am force-stop " + PKG, 40000);
            if (!r.connected) {
                post(l, true, r.needsApproval ? "Approve on screen" : "Could not stop");
                return;
            }
            prefs(app).edit().putBoolean("on", false).apply();
            cancelIdleCheck(app);
            post(l, false, null);
        });
    }

    /** Called by the alarm: stop the receiver unless something is being cast, in which case look again later. */
    static void idleCheck(Context app, Runnable done) {
        IO.execute(() -> {
            try {
                if (!cachedOn(app)) return;
                LocalAdb.Result focus = LocalAdb.shell(app, "dumpsys window | grep -m1 mCurrentFocus", 1500);
                LocalAdb.Result audio = LocalAdb.shell(app,
                        "P=$(pidof " + PKG + "); [ -n \"$P\" ] && dumpsys audio | grep -c \"pid:$P\"", 1500);
                if (!focus.connected) {
                    scheduleIdleCheck(app, RECHECK_MS);       // cannot tell: try again later, never cut a session off blind
                    return;
                }
                boolean showing = focus.output.contains("fastscreen");
                boolean playing = audio.output.trim().matches("[1-9]\\d*");
                if (showing || playing) {
                    scheduleIdleCheck(app, RECHECK_MS);
                    return;
                }
                LocalAdb.shell(app, "am force-stop " + PKG, 1500);
                prefs(app).edit().putBoolean("on", false).apply();
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private static PendingIntent alarmIntent(Context app) {
        return PendingIntent.getBroadcast(app, 0, new Intent(app, AirPlayIdleReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void scheduleIdleCheck(Context app, long inMs) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.RTC, System.currentTimeMillis() + inMs, alarmIntent(app));
    }

    private static void cancelIdleCheck(Context app) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(app));
    }

    private static void post(Listener l, Boolean on, String note) {
        if (l != null) MAIN.post(() -> l.state(on, note));
    }
}
