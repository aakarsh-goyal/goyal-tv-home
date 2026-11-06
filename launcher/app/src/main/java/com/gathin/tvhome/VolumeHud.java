package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The volume pill on screen, over Home or any app. It listens for the system's volume-changed and mute broadcasts
 * (registered once, in the app's process), so nothing runs between key presses; the pill's window exists only while
 * it is visible. It needs "Display over other apps", which the install grants with appops.
 */
final class VolumeHud {
    private static final int STREAM_MUSIC = AudioManager.STREAM_MUSIC;
    private static final long HIDE_AFTER_MS = 1700;

    private static VolumeHud instance;

    private final Context app;
    private final WindowManager wm;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private VolumeHudView view;
    private boolean showing;
    private SpringAnimation enterX, enterY;
    private final Runnable hide = this::hideNow;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if ("android.media.VOLUME_CHANGED_ACTION".equals(a)) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != STREAM_MUSIC) return;
            } else if ("android.media.STREAM_MUTE_CHANGED_ACTION".equals(a)) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != STREAM_MUSIC) return;
            } else {
                return;
            }
            show();
        }
    };

    private VolumeHud(Context c) {
        app = c.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        density = app.getResources().getDisplayMetrics().density;
    }

    /** Called once from the Application: registers the receiver. */
    static void install(Context c) {
        if (instance != null) return;
        instance = new VolumeHud(c);
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        f.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        instance.app.registerReceiver(instance.receiver, f);
    }

    private float level() {
        int max = Math.max(1, audio.getStreamMaxVolume(STREAM_MUSIC));
        return audio.getStreamVolume(STREAM_MUSIC) / (float) max;
    }

    private void show() {
        if (!Settings.canDrawOverlays(app)) return;   // not granted: the system's own bar is the only one
        boolean muted = audio.isStreamMute(STREAM_MUSIC);
        float level = level();
        handler.removeCallbacks(hide);
        if (!showing) {
            view = new VolumeHudView(app);
            view.snapTo(level, muted);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(VolumeHudView.WIDTH_DP * density), Math.round(VolumeHudView.HEIGHT_DP * density),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(64 * density);
            lp.setTitle("TvHomeVolume");
            view.setAlpha(0f);
            view.setScaleX(0.9f);
            view.setScaleY(0.9f);
            view.setTranslationY(14 * density);
            try {
                wm.addView(view, lp);
            } catch (RuntimeException e) {
                view = null;
                return;
            }
            showing = true;
            // Rises into place on the same spring family as the rest of the launcher.
            view.animate().alpha(1f).setDuration(160).start();
            enterX = spring(view, DynamicAnimation.SCALE_X, 1f);
            enterY = spring(view, DynamicAnimation.SCALE_Y, 1f);
            spring(view, DynamicAnimation.TRANSLATION_Y, 0f);
        } else {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setLevel(level, muted);
        }
        view.setLevel(level, muted);
        handler.postDelayed(() -> { if (view != null) view.settle(); }, 500);
        handler.postDelayed(hide, HIDE_AFTER_MS);
    }

    private static SpringAnimation spring(android.view.View v, DynamicAnimation.ViewProperty prop, float to) {
        SpringAnimation s = new SpringAnimation(v, prop);
        s.setSpring(new SpringForce().setStiffness(520f).setDampingRatio(0.78f).setFinalPosition(to));
        s.start();
        return s;
    }

    private void hideNow() {
        final VolumeHudView v = view;
        if (v == null) return;
        v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(6 * density).setDuration(240)
                .setInterpolator(Motion.EASE).withEndAction(() -> {
                    if (view != v) return;   // shown again meanwhile
                    v.cancelAll();
                    try {
                        wm.removeViewImmediate(v);
                    } catch (RuntimeException ignored) {
                    }
                    view = null;
                    showing = false;
                }).start();
    }
}
