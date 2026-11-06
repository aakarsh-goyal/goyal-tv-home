package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A small weather glyph drawn with a few Canvas paths (no images, no library). It moves gently: sun rays turn,
 * clouds drift, rain and snow fall. One 4-second loop that runs only while Home is in front and animation is on,
 * and repaints just this 30dp square.
 */
final class WeatherIconView extends View {
    private static final int SUN = 0xFFFFD60A, CLOUD = 0xFFE5E5EA, RAIN = 0xFF64D2FF, MOON = 0xFFF2F2F7, BOLT = 0xFFFFD60A;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ValueAnimator loop;
    private float t;
    private int kind = -1;     // 0 clear day, 1 clear night, 2 partly, 3 cloudy, 4 fog, 5 rain, 6 snow, 7 storm
    private boolean animate = true, active;

    WeatherIconView(Context c) {
        super(c);
    }

    WeatherIconView(Context c, android.util.AttributeSet a) {
        super(c, a);
    }

    void setWeather(int wmo, boolean day) {
        int k;
        if (wmo == 0) k = day ? 0 : 1;
        else if (wmo <= 2) k = day ? 2 : 3;
        else if (wmo == 3) k = 3;
        else if (wmo == 45 || wmo == 48) k = 4;
        else if ((wmo >= 71 && wmo <= 77) || wmo == 85 || wmo == 86) k = 6;
        else if (wmo >= 95) k = 7;
        else k = 5;
        kind = k;
        invalidate();
    }

    void setAnimate(boolean animate) {
        this.animate = animate;
        sync();
    }

    /** Home in front (true) or not (false). */
    void setActive(boolean active) {
        this.active = active;
        sync();
    }

    private void sync() {
        boolean run = active && animate && kind >= 0 && getVisibility() == VISIBLE;
        if (run && loop == null) {
            loop = ValueAnimator.ofFloat(0f, 1f);
            loop.setDuration(4000);
            loop.setRepeatCount(ValueAnimator.INFINITE);
            loop.setInterpolator(new LinearInterpolator());
            loop.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            loop.start();
        } else if (!run && loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (kind < 0) return;
        float u = Math.min(getWidth(), getHeight()) / 24f;   // the glyph is drawn on a 24-unit grid
        c.save();
        c.translate((getWidth() - 24 * u) / 2f, (getHeight() - 24 * u) / 2f);
        c.scale(u, u);
        p.setStyle(Paint.Style.FILL);
        switch (kind) {
            case 0: sun(c, 12, 12, 1f); break;
            case 1: moon(c); break;
            case 2: sun(c, 9, 9, 0.75f); cloud(c, 0f); break;
            case 3: cloud(c, 0f); break;
            case 4: cloud(c, 0f); fog(c); break;
            case 5: cloud(c, 0f); drops(c, false); break;
            case 6: cloud(c, 0f); drops(c, true); break;
            default: cloud(c, 0f); bolt(c); break;
        }
        c.restore();
    }

    private void sun(Canvas c, float x, float y, float s) {
        p.setColor(SUN);
        c.drawCircle(x, y, 4.2f * s, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(1.6f * s);
        c.save();
        c.rotate(t * 90f, x, y);   // a quarter turn per loop; the 8 rays repeat every 45 degrees
        for (int i = 0; i < 8; i++) {
            c.drawLine(x, y - 7.2f * s, x, y - 9.4f * s, p);
            c.rotate(45f, x, y);
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    private void moon(Canvas c) {
        p.setColor(MOON);
        path.reset();
        path.addCircle(12, 12, 7.5f, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(16, 9, 6.5f, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(path, p);
    }

    private void cloud(Canvas c, float dy) {
        float drift = (float) Math.sin(t * 2 * Math.PI) * 1.1f;
        p.setColor(CLOUD);
        c.save();
        c.translate(drift, dy);
        c.drawCircle(9.5f, 14f, 4.2f, p);
        c.drawCircle(14.5f, 12.2f, 5.2f, p);
        c.drawCircle(18.2f, 15f, 3.4f, p);
        c.drawRoundRect(6f, 14f, 20f, 18.8f, 2.4f, 2.4f, p);
        c.restore();
    }

    private void drops(Canvas c, boolean snow) {
        p.setColor(snow ? 0xFFFFFFFF : RAIN);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float ph = (t + i / 3f) % 1f;            // each drop falls on its own phase
            float x = 9f + i * 3.6f - (snow ? 0f : ph * 1.2f);
            float y = 17.5f + ph * 5f;
            p.setAlpha((int) (255 * (1f - ph * ph)));
            if (snow) c.drawCircle(x + (float) Math.sin(ph * 6.28f) * 0.6f, y, 1.0f, p);
            else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.3f);
                c.drawLine(x, y, x - 0.8f, y + 2.2f, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        p.setAlpha(255);
    }

    private void fog(Canvas c) {
        p.setColor(0xB3E5E5EA);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(1.4f);
        float s = (float) Math.sin(t * 2 * Math.PI) * 1.2f;
        c.drawLine(6f + s, 20.5f, 18f + s, 20.5f, p);
        c.drawLine(8f - s, 23f, 20f - s, 23f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void bolt(Canvas c) {
        p.setColor(BOLT);
        path.reset();
        path.moveTo(13.5f, 15.5f);
        path.lineTo(10.5f, 20.3f);
        path.lineTo(12.8f, 20.3f);
        path.lineTo(11.6f, 23.8f);
        path.lineTo(16f, 18.6f);
        path.lineTo(13.6f, 18.6f);
        path.close();
        p.setAlpha(t % 0.5f < 0.38f ? 255 : 110);   // a quick flicker
        c.drawPath(path, p);
        p.setAlpha(255);
    }
}
