package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The volume pill: a dark liquid-glass capsule (soft top sheen, bright rim), a speaker whose waves follow the level,
 * and a slim track. The level moves on a spring with a touch of overshoot, so the fill glides and settles instead of
 * jumping, and the track swells a little while the volume is changing, like the sliders on Apple's devices.
 * Drawn with one Canvas and no blur: it has to be cheap on a TV with a weak GPU.
 */
final class VolumeHudView extends View {
    static final int WIDTH_DP = 400, HEIGHT_DP = 64;

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private float level;       // 0..1 (may overshoot a hair while the spring settles)
    private float swell;       // 0..1: the track grows while the volume is moving
    private boolean muted;
    private final SpringAnimation levelSpring, swellSpring;

    VolumeHudView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        levelSpring = new SpringAnimation(this, new FloatPropertyCompat<VolumeHudView>("hudLevel") {
            @Override public float getValue(VolumeHudView v) { return v.level; }
            @Override public void setValue(VolumeHudView v, float value) { v.level = value; v.invalidate(); }
        });
        levelSpring.setMinimumVisibleChange(0.002f);
        levelSpring.setSpring(new SpringForce().setStiffness(520f).setDampingRatio(0.72f).setFinalPosition(0f));
        swellSpring = new SpringAnimation(this, new FloatPropertyCompat<VolumeHudView>("hudSwell") {
            @Override public float getValue(VolumeHudView v) { return v.swell; }
            @Override public void setValue(VolumeHudView v, float value) { v.swell = value; v.invalidate(); }
        });
        swellSpring.setMinimumVisibleChange(0.01f);
        swellSpring.setSpring(new SpringForce().setStiffness(380f).setDampingRatio(0.8f).setFinalPosition(0f));
    }

    /** Jumps to a level with no motion (the HUD was hidden), before it is shown. */
    void snapTo(float to, boolean muted) {
        levelSpring.cancel();
        level = muted ? 0f : to;
        this.muted = muted;
        invalidate();
    }

    void setLevel(float to, boolean muted) {
        this.muted = muted;
        levelSpring.animateToFinalPosition(muted ? 0f : to);
        swellSpring.animateToFinalPosition(1f);
    }

    /** The volume stopped changing: the track settles back. */
    void settle() {
        swellSpring.animateToFinalPosition(0f);
    }

    void cancelAll() {
        levelSpring.cancel();
        swellSpring.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = h / 2f;

        // Glass body: a translucent graphite gradient.
        r.set(0, 0, w, h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xEB303035, 0xEB18181B, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        // Sheen: light falling on the top half.
        p.setShader(new LinearGradient(0, 0, 0, h * 0.62f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.save();
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(path);
        c.drawRect(0, 0, w, h * 0.62f, p);
        c.restore();
        // Rim: bright on the top edge, faint underneath, so it reads as a lit glass edge.
        p.setShader(new LinearGradient(0, 0, 0, h, 0xAAFFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f * d);
        r.inset(0.7f * d, 0.7f * d);
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);

        // Speaker and waves.
        float cx = 34 * d, cy = h / 2f;
        drawSpeaker(c, cx, cy);

        // Track.
        float x0 = 64 * d, x1 = w - 28 * d;
        float th = (6f + 4f * swell) * d;
        float ty = cy - th / 2f;
        p.setColor(0x38FFFFFF);
        r.set(x0, ty, x1, ty + th);
        c.drawRoundRect(r, th / 2f, th / 2f, p);
        float fill = Math.max(0f, Math.min(1.03f, level));
        float fx = x0 + (x1 - x0) * fill;
        c.save();
        path.reset();
        r.set(x0, ty, x1, ty + th);
        path.addRoundRect(r, th / 2f, th / 2f, Path.Direction.CW);
        c.clipPath(path);
        p.setColor(0xFF000000);
        p.setShader(new LinearGradient(0, ty, 0, ty + th, 0xFFFFFFFF, 0xFFD9D9DE, Shader.TileMode.CLAMP));
        r.set(x0 - th, ty, Math.max(fx, x0 + th), ty + th);   // starts off-track so the left end stays round
        c.drawRoundRect(r, th / 2f, th / 2f, p);
        p.setShader(null);
        c.restore();
    }

    private void drawSpeaker(Canvas c, float cx, float cy) {
        float u = d;
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        // Body and cone.
        path.reset();
        path.moveTo(cx - 11 * u, cy - 4 * u);
        path.lineTo(cx - 6 * u, cy - 4 * u);
        path.lineTo(cx + 0.5f * u, cy - 10 * u);
        path.lineTo(cx + 0.5f * u, cy + 10 * u);
        path.lineTo(cx - 6 * u, cy + 4 * u);
        path.lineTo(cx - 11 * u, cy + 4 * u);
        path.close();
        c.drawPath(path, p);
        // Waves fade in one after the other as the level rises.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(1.9f * u);
        float l = muted ? 0f : Math.max(0f, Math.min(1f, level));
        for (int i = 0; i < 3; i++) {
            float a = Math.max(0f, Math.min(1f, (l - i / 3f) * 3f));
            if (a <= 0f) continue;
            p.setAlpha((int) (255 * a));
            float rr = (6 + i * 4.6f) * u;
            r.set(cx + 2 * u - rr * 0.35f, cy - rr, cx + 2 * u + rr * 1.65f, cy + rr);
            c.drawArc(r, -42, 84, false, p);
        }
        p.setAlpha(255);
        if (muted || level <= 0.001f) {
            // A slash through the speaker.
            p.setStrokeWidth(2.2f * u);
            c.drawLine(cx - 12 * u, cy - 11 * u, cx + 11 * u, cy + 11 * u, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
