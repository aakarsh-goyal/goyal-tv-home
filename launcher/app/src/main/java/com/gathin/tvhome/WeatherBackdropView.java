package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * The weather outside, quietly in the Home background: clouds drifting right to left, rain, snow, a warm sun glow,
 * a moon with a few stars, fog and the odd lightning flash. Deliberately tiny: a handful of gradient ovals, one
 * batched draw for the rain, one for the snow, one for the stars; about 20 frames a second, only while Home is in
 * front; no bitmaps, threads, blur or per-frame allocation. Clouds lean towards the focused app's colour.
 */
public final class WeatherBackdropView extends SurfaceView implements SurfaceHolder.Callback {
    private static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6, PLAIN = 7;
    /** Festival decoration modes (setFestival). */
    public static final int FEST_NONE = 0, FEST_LIGHTS = 1, FEST_COLORS = 2, FEST_SNOW = 3, FEST_PETALS = 4;
    private static final int BUF_W = 640, BUF_H = 360;

    /** Frame interval per scene: what barely moves (sun, night) costs the compositor almost nothing. */
    private long frameMs() {
        switch (kind) {
            case PLAIN: return 125;
            case CLEAR: return 250;           // 4 fps: a slow shimmer of light and dust
            case PARTLY: return 200;
            case CLOUDY: case FOG: return 160;
            default: return 100;              // rain, snow, storm: 10 fps
        }
    }

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int kind = -1;
    private boolean day = true;
    private volatile boolean active, animate = true, surfaceReady;
    private boolean running;
    private int w, h;                       // the scene's size: the view's, in screen pixels
    private float wind = 8f, intensity = 0.5f;
    private long last;
    private float t;
    private volatile int accentTarget = 0xFFFFFFFF;
    private volatile long pausedUntil;
    private volatile float haze;            // 0 clear air .. 1 thick smog, from the US AQI
    private int accentNow = 0xFFFFFFFF;

    // One unit cloud: a soft white disc, stretched into an oval when drawn.
    private Shader cloudShader, sunShader, moonShader;
    private final float[] cx = new float[6], cy = new float[6], cw = new float[6], cv = new float[6];
    private int clouds;
    private float[] rain = new float[0];     // x, y, speed per drop, packed
    private float[] lines = new float[0];
    private float[] flake = new float[0];    // x, y, speed per flake
    private float[] pts = new float[0];
    private float[] star = new float[0];     // x, y per star
    private float[] mote = new float[0];     // x, y, speed per dust speck in the sun
    private final android.graphics.Path ray = new android.graphics.Path();
    private float flash, nextFlash = 6f;

    // Festival decoration: drifting sparkles in the festival's colours, and a garland of marigolds along the top.
    private int festMode;
    private final int[] festColor = {0xFFFFC857, 0xFFFFE9B0, 0xFFFFFFFF};
    private boolean festGarland;
    private float[] fp = new float[0];       // x, y, speed per sparkle
    private final float[] garland = new float[96];

    private final Object lock = new Object();
    private HandlerThread thread;
    private Handler bg;

    public WeatherBackdropView(Context c) {
        this(c, null);
    }

    public WeatherBackdropView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        getHolder().addCallback(this);
        getHolder().setFormat(PixelFormat.RGBX_8888);
        getHolder().setFixedSize(BUF_W, BUF_H);
    }

    private void ensureThread() {
        if (bg == null) {
            thread = new HandlerThread("weather-bg");
            thread.start();
            bg = new Handler(thread.getLooper());
        }
    }

    // ---- control ---------------------------------------------------------------------------------

    /** @param wmo WMO weather code; wind in km/h, cloud cover in percent, precipitation in mm. */
    public void setWeather(final int wmo, final boolean day, final float windKmh, final float cloudPct, final float precipMm) {
        ensureThread();
        bg.post(() -> {
            if (wmo <= 1) kind = CLEAR;
            else if (wmo == 2) kind = PARTLY;
            else if (wmo == 3) kind = CLOUDY;
            else if (wmo == 45 || wmo == 48) kind = FOG;
            else if ((wmo >= 71 && wmo <= 77) || wmo == 85 || wmo == 86) kind = SNOW;
            else if (wmo >= 95) kind = STORM;
            else kind = RAIN;
            this.day = day;
            this.wind = Math.max(0f, Math.min(60f, windKmh));
            this.intensity = kind == RAIN ? Math.max(wmo >= 65 ? 1f : wmo >= 61 ? 0.6f : 0.35f, Math.min(1f, precipMm / 6f))
                    : kind == SNOW ? (wmo == 75 || wmo == 86 ? 1f : 0.55f) : 0.8f;
            populate();
            syncLoop();
            drawOnce();
        });
        post(() -> setVisibility(VISIBLE));
    }

    public void setActive(boolean on) {
        active = on;
        ensureThread();
        bg.post(this::syncLoop);
    }

    public void setAnimate(boolean on) {
        animate = on;
        ensureThread();
        bg.post(this::syncLoop);
    }

    /** The focused app's colour: the clouds lean towards it. */
    public void setAccent(int argb) {
        accentTarget = 0xFF000000 | argb;
    }

    /** Decorate for a festival: mode, three colours, and whether to hang the marigold garland. FEST_NONE clears it. */
    public void setFestival(final int mode, final int c0, final int c1, final int c2, final boolean garlandOn) {
        ensureThread();
        bg.post(() -> {
            festMode = mode;
            festColor[0] = c0;
            festColor[1] = c1;
            festColor[2] = c2;
            festGarland = garlandOn && mode == FEST_LIGHTS;
            if (mode != FEST_NONE && kind < 0) kind = PLAIN;          // a festival needs the layer even before the weather is known
            populate();
            syncLoop();
            drawOnce();
        });
        if (mode != FEST_NONE) post(() -> setVisibility(VISIBLE));
    }

    /** The US AQI (or -1): above ~100 a brownish haze builds, and the sun dims behind it. */
    public void setHaze(int aqi) {
        haze = aqi < 100 ? 0f : Math.min(1f, (aqi - 100) / 250f);
    }

    /** The cursor just moved: hold the backdrop still for a moment so the compositor serves the UI first. */
    public void pauseFor(long ms) {
        pausedUntil = android.os.SystemClock.uptimeMillis() + ms;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now >= pausedUntil) {
                step(Math.min(0.1f, (now - last) / 1000f));
                drawOnce();
            }
            last = now;
            bg.postDelayed(this, frameMs());
        }
    };

    /** Runs on the background thread. */
    private void syncLoop() {
        boolean run = active && animate && kind >= 0 && kind != PLAIN && surfaceReady;
        if (run && !running) {
            running = true;
            last = android.os.SystemClock.uptimeMillis();
            bg.postDelayed(tick, frameMs());
        } else if (!run && running) {
            running = false;
            bg.removeCallbacks(tick);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        ensureThread();
        synchronized (lock) {
            surfaceReady = true;
        }
        final int vw = getWidth(), vh = getHeight();
        bg.post(() -> {
            if (vw > 0 && vh > 0) { w = vw; h = vh; buildShaders(); populate(); }
            syncLoop();
            drawOnce();
        });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // The drawing thread must be finished with the surface before this returns.
        synchronized (lock) {
            surfaceReady = false;
        }
        if (bg != null) bg.post(this::syncLoop);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            bg = null;
        }
        running = false;
    }

    private void buildShaders() {
        cloudShader = new RadialGradient(0, 0, 100, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sunShader = new RadialGradient(w * 0.93f, h * 0.07f, h * 1.2f,
                new int[]{0xCCFFD27A, 0x88FFA94D, 0x33FF8A3C, 0x00FF8A3C}, new float[]{0f, 0.22f, 0.58f, 1f}, Shader.TileMode.CLAMP);
        moonShader = new RadialGradient(w * 0.86f, h * 0.10f, h * 0.45f, new int[]{0x55C8DAFF, 0x1A8FA8FF, 0x008FA8FF},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
    }

    /** One frame into the surface, at BUF_W x BUF_H; the compositor scales it to the screen. */
    private void drawOnce() {
        synchronized (lock) {
            if (!surfaceReady || kind < 0 || w == 0) return;
            SurfaceHolder holder = getHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                c.drawColor(0xFF000000);
                c.save();
                c.scale(BUF_W / (float) w, BUF_H / (float) h);
                render(c);
                c.restore();
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    // ---- scene ------------------------------------------------------------------------------------

    private static float r(int seed, int i) {   // cheap repeatable pseudo-random in [0,1)
        int x = seed * 374761393 + i * 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        return ((x ^ (x >>> 16)) & 0xFFFF) / 65536f;
    }

    private void populate() {
        if (w == 0 || kind < 0) return;
        clouds = kind == PLAIN ? 0 : kind == CLEAR ? 1 : kind == PARTLY ? 3 : kind == FOG ? 3 : kind == SNOW ? 3 : kind == CLOUDY ? 5 : 5;
        for (int i = 0; i < clouds; i++) {
            cw[i] = w * (kind == FOG ? 1.1f : 0.5f + 0.3f * r(1, i));
            cx[i] = (w + cw[i]) * r(2, i) - cw[i] * 0.5f;
            cy[i] = kind == FOG ? h * (0.5f + 0.15f * i) : h * (0.04f + 0.3f * r(3, i));
            cv[i] = (8f + 10f * r(4, i)) * d * (1f + wind / 30f);
        }
        int drops = kind == RAIN ? 35 + (int) (35 * intensity) : kind == STORM ? 80 : 0;
        if (rain.length != drops * 3) { rain = new float[drops * 3]; lines = new float[drops * 4]; }
        for (int i = 0; i < drops; i++) {
            rain[i * 3] = r(5, i) * (w + h);
            rain[i * 3 + 1] = r(6, i) * h;
            rain[i * 3 + 2] = (800f + 500f * r(7, i)) * d * (0.7f + 0.4f * intensity);
        }
        int flakes = kind == SNOW ? 25 + (int) (25 * intensity) : 0;
        if (flake.length != flakes * 3) flake = new float[flakes * 3];
        for (int i = 0; i < flakes; i++) {
            flake[i * 3] = r(8, i) * w;
            flake[i * 3 + 1] = r(9, i) * h;
            flake[i * 3 + 2] = (30f + 30f * r(10, i)) * d;
        }
        int stars = !day && (kind == CLEAR || kind == PARTLY) ? 32 : 0;
        if (star.length != stars * 2) star = new float[stars * 2];
        for (int i = 0; i < stars; i++) {
            star[i * 2] = r(11, i) * w;
            star[i * 2 + 1] = r(12, i) * h * 0.65f;
        }
        int motes = day && (kind == CLEAR || kind == PARTLY) ? 18 : 0;
        if (mote.length != motes * 3) mote = new float[motes * 3];
        for (int i = 0; i < motes; i++) {
            mote[i * 3] = r(16, i) * w;
            mote[i * 3 + 1] = r(17, i) * h;
            mote[i * 3 + 2] = (7f + 9f * r(18, i)) * d;
        }
        int sparks = festMode == FEST_NONE ? 0 : 36;
        if (fp.length != sparks * 3) fp = new float[sparks * 3];
        for (int i = 0; i < sparks; i++) {
            fp[i * 3] = r(20, i) * w;
            fp[i * 3 + 1] = r(21, i) * h;
            fp[i * 3 + 2] = (festMode == FEST_LIGHTS ? 14f + 16f * r(22, i) : 26f + 30f * r(22, i)) * d;
        }
        pts = new float[Math.max(Math.max(Math.max(flakes, motes), sparks), 1) * 2];
    }

    private void step(float dt) {
        if (w == 0) return;
        t += dt;
        float k = Math.min(1f, dt * 2f);
        accentNow = mix(accentNow, accentTarget, k);
        for (int i = 0; i < clouds; i++) {
            cx[i] -= cv[i] * dt * (kind == FOG && i % 2 == 1 ? -0.6f : 1f);
            if (cx[i] + cw[i] < 0) cx[i] = w;
            else if (cx[i] > w * 1.6f) cx[i] = -cw[i];
        }
        float slant = Math.min(0.5f, wind / 80f);
        for (int i = 0; i < rain.length; i += 3) {
            rain[i + 1] += rain[i + 2] * dt;
            rain[i] -= rain[i + 2] * slant * dt;
            if (rain[i + 1] > h) {
                rain[i + 1] -= h + 30 * d;
                rain[i] = r(13, (int) (t * 10) + i) * (w + h * slant);
            }
        }
        for (int i = 0; i < flake.length; i += 3) {
            flake[i + 1] += flake[i + 2] * dt;
            flake[i] += ((float) Math.sin(t + i) * 18f * d - wind * d) * dt;
            if (flake[i + 1] > h) { flake[i + 1] = -6 * d; flake[i] = r(14, (int) (t * 10) + i) * w; }
            if (flake[i] < -8 * d) flake[i] = w;
        }
        for (int i = 0; i < mote.length; i += 3) {
            mote[i + 1] -= mote[i + 2] * dt;
            mote[i] += ((float) Math.sin(t * 0.6f + i) * 10f * d - wind * 0.2f * d) * dt;
            if (mote[i + 1] < -6 * d) { mote[i + 1] = h + 6 * d; mote[i] = r(19, (int) (t * 10) + i) * w; }
        }
        if (kind == STORM) {
            flash *= (float) Math.exp(-dt * 8f);
            if (t > nextFlash) { flash = 0.22f; nextFlash = t + 6f + 9f * r(15, (int) t); }
        }
    }

    // ---- drawing ----------------------------------------------------------------------------------

    private void render(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);

        if (kind == CLEAR || kind == PARTLY) {
            float breathe = 0.93f + 0.07f * (float) Math.sin(t * 0.7f);
            if (day) {
                drawSun(c, w, h, breathe, (kind == PARTLY ? 0.7f : 1f) * (1f - 0.45f * haze));
            } else {
                p.setShader(moonShader);
                p.setAlpha((int) (255 * breathe));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                p.setColor(0xFFF4F1E6);
                c.drawCircle(w * 0.93f, h * 0.10f, 22f * d, p);
                drawStars(c);
            }
        }

        int base = kind == STORM ? 0xFF4A5164 : kind == RAIN ? 0xFF6F7A90 : kind == CLOUDY ? 0xFFAAB3C4
                : kind == SNOW ? 0xFFC8D0E0 : kind == FOG ? 0xFFDDE3EE : 0xFFFFFFFF;
        if (!day) base = mix(base, 0xFF5A6788, 0.6f);
        cloudPaint.setShader(cloudShader);
        cloudPaint.setColor(mix(base, accentNow, 0.22f));
        float a = (kind == CLEAR ? 0.10f : kind == PARTLY ? 0.16f : kind == FOG ? 0.12f : kind == STORM ? 0.36f : 0.26f)
                * (1f + flash * 3f);
        cloudPaint.setAlpha(Math.min(255, (int) (255 * a)));
        for (int i = 0; i < clouds; i++) {
            c.save();
            c.translate(cx[i] + cw[i] / 2f, cy[i] + cw[i] * 0.12f);
            float sx = cw[i] / 200f;            // the unit disc has radius 100
            c.scale(sx, sx * (kind == FOG ? 0.22f : 0.42f));
            c.drawCircle(0, 0, 100, cloudPaint);
            c.restore();
        }
        cloudPaint.setShader(null);

        if (rain.length > 0) {
            float len = 24f * d * (0.8f + 0.5f * intensity), slant = Math.min(0.5f, wind / 80f);
            int n = 0;
            for (int i = 0; i < rain.length; i += 3) {
                lines[n++] = rain[i];
                lines[n++] = rain[i + 1];
                lines[n++] = rain[i] + len * slant;
                lines[n++] = rain[i + 1] - len;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(mix(0xFFBBD6FF, accentNow, 0.15f));
            p.setStrokeWidth(4.2f * d);          // a soft halo, like the motion blur of a real raindrop
            p.setAlpha(34);
            c.drawLines(lines, 0, n, p);
            p.setStrokeWidth(1.8f * d);          // and its brighter core
            p.setAlpha(125);
            c.drawLines(lines, 0, n, p);
            p.setStyle(Paint.Style.FILL);
        }
        if (flake.length > 0) {
            int n = 0;
            for (int i = 0; i < flake.length; i += 3) {
                pts[n++] = flake[i];
                pts[n++] = flake[i + 1];
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(3.2f * d);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(150);
            c.drawPoints(pts, 0, n, p);
            p.setStyle(Paint.Style.FILL);
        }
        drawFestival(c, w, h);
        if (haze > 0.02f) {                              // smog: a dusty brown-grey wash over the whole scene
            p.setColor(0xFFA89078);
            p.setAlpha((int) (62 * haze));
            c.drawRect(0, 0, w, h, p);
        }
        if (kind == STORM && flash > 0.01f) {
            p.setColor(0xFFCFE0FF);
            p.setAlpha((int) (255 * flash));
            c.drawRect(0, 0, w, h, p);
        }
        p.setAlpha(255);
    }

    private void drawFestival(Canvas c, int w, int h) {
        if (festMode == FEST_NONE) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int b = 0; b < 3; b++) {
            int n = 0;
            for (int i = b; i * 3 < fp.length; i += 3) {
                pts[n++] = fp[i * 3];
                pts[n++] = fp[i * 3 + 1];
            }
            p.setColor(festColor[b]);
            p.setStrokeWidth((festMode == FEST_COLORS ? 8f + b * 2f : festMode == FEST_PETALS ? 7f + b : 4.5f + b * 1.8f) * d);
            p.setAlpha(festMode == FEST_LIGHTS ? 175 : 170);        // still: the festival decoration never moves
            c.drawPoints(pts, 0, n, p);
        }
        if (festGarland) {                                 // a marigold toran along the top, three scallops
            int count = 24;
            for (int b = 0; b < 2; b++) {
                int n = 0;
                for (int i = b; i < count; i += 2) {
                    float x = (i + 0.5f) * w / count;
                    garland[n++] = x;
                    garland[n++] = 6 * d + 15 * d * (float) Math.abs(Math.sin(Math.PI * 3 * x / w));
                }
                p.setColor(festColor[b]);
                p.setStrokeWidth(10f * d);
                p.setAlpha(235);
                c.drawPoints(garland, 0, n, p);
            }
        }
        if (festMode == FEST_LIGHTS) drawDiyas(c, w, h);
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);
    }

    /** A still row of lit diyas along the bottom of the right-hand side, under the apps. */
    private void drawDiyas(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        int n = 9;
        for (int i = 0; i < n; i++) {
            float x = w * (0.50f + 0.46f * (i + 0.5f) / n), y = h - 34 * d;
            p.setColor(festColor[0]);
            p.setAlpha(34);
            c.drawCircle(x, y - 10 * d, 22 * d, p);                  // the glow
            p.setColor(0xFFC1622B);
            p.setAlpha(255);
            ray.reset();
            ray.moveTo(x - 13 * d, y);
            ray.quadTo(x, y + 15 * d, x + 13 * d, y);
            ray.quadTo(x, y + 4 * d, x - 13 * d, y);
            c.drawPath(ray, p);                                       // the clay bowl
            p.setColor(0xFFFFD66B);
            ray.reset();
            ray.moveTo(x, y - 20 * d);
            ray.quadTo(x + 6.5f * d, y - 10 * d, x, y - 2 * d);
            ray.quadTo(x - 6.5f * d, y - 10 * d, x, y - 20 * d);
            c.drawPath(ray, p);                                       // the flame
        }
        p.setAlpha(255);
    }

    /** A warm, bright day: a wash of gold, a big sun glow in the corner, soft rays sweeping across, the disc, dust in the light. */
    private void drawSun(Canvas c, int w, int h, float breathe, float strength) {
        p.setShader(null);
        p.setColor(mix(0xFFFFB347, accentNow, 0.12f));
        p.setAlpha((int) (28 * strength));
        c.drawRect(0, 0, w, h, p);                       // golden wash over everything

        p.setShader(sunShader);
        p.setAlpha((int) (255 * breathe * strength));
        c.drawRect(0, 0, w, h, p);                       // the sun's glow
        p.setShader(null);

        float sx = w * 0.93f, sy = h * 0.07f;
        float sway = (float) Math.sin(t * 0.35f) * 3.5f;
        p.setColor(mix(0xFFFFEBB8, accentNow, 0.10f));
        for (int i = 0; i < 6; i++) {                    // rays fanning down and to the left, two widths each
            float ang = (float) Math.toRadians(103 + i * 21 + sway * (i % 2 == 0 ? 1 : -1));
            for (int k = 0; k < 2; k++) {
                float half = (float) Math.toRadians(k == 0 ? 5.5f : 2.4f);
                float len = h * 1.7f;
                ray.reset();
                ray.moveTo(sx, sy);
                ray.lineTo(sx + len * (float) Math.cos(ang - half), sy + len * (float) Math.sin(ang - half));
                ray.lineTo(sx + len * (float) Math.cos(ang + half), sy + len * (float) Math.sin(ang + half));
                ray.close();
                p.setAlpha((int) ((k == 0 ? 16 : 24) * strength * breathe));
                c.drawPath(ray, p);
            }
        }
        p.setColor(0xFFFFF6D6);                          // the disc and its halo
        p.setAlpha((int) (70 * strength));
        c.drawCircle(sx, sy, 0.15f * h, p);
        p.setAlpha((int) (235 * strength));
        c.drawCircle(sx, sy, 0.05f * h, p);

        if (mote.length > 0) {                           // dust and pollen turning in the light
            int n = 0;
            for (int i = 0; i < mote.length; i += 3) {
                pts[n++] = mote[i];
                pts[n++] = mote[i + 1];
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(3.2f * d);
            p.setColor(0xFFFFEBB0);
            p.setAlpha((int) (120 + 60 * Math.sin(t * 1.3f)));
            c.drawPoints(pts, 0, n, p);
            p.setStyle(Paint.Style.FILL);
        }
        p.setAlpha(255);
    }

    private void drawStars(Canvas c) {
        if (star.length == 0) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(2f * d);
        p.setColor(0xFFFFFFFF);
        p.setAlpha((int) (110 + 90 * Math.sin(t * 0.9f)));
        c.drawPoints(star, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static int mix(int a, int b, float f) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
