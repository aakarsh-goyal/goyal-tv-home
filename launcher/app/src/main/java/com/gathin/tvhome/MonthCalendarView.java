package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * A month grid in the spirit of iOS StandBy's calendar: the month's name, the weekday letters, the days, and today in
 * a red disc. One Canvas, no child views. It redraws only when the date or time zone changes (a system broadcast
 * it listens for while on screen) and when Home comes back; no timer.
 */
final class MonthCalendarView extends View {
    private static final int TODAY = 0xFFFF453A, INK = 0xFFFFFFFF, DIM = 0x80FFFFFF, WEEKEND = 0xB3FFFFFF;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Typeface regular, medium, light, todayFace;
    private boolean registered;
    private boolean mono;

    private final BroadcastReceiver dateChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { invalidate(); }
    };

    MonthCalendarView(Context c) {
        this(c, null);
    }

    MonthCalendarView(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        regular = Typeface.create(c.getString(R.string.font_regular), Typeface.NORMAL);
        medium = Typeface.create(c.getString(R.string.font_medium), Typeface.NORMAL);
        // Day numbers use Roboto, the same family as the big clock.
        light = Typeface.create("sans-serif", Typeface.NORMAL);
        todayFace = Typeface.create("sans-serif-medium", Typeface.BOLD);
    }

    @Override
    protected void onDraw(Canvas c) {
        Calendar now = Calendar.getInstance();
        int today = now.get(Calendar.DAY_OF_MONTH);
        Calendar first = (Calendar) now.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int weekStart = first.getFirstDayOfWeek();
        int lead = (first.get(Calendar.DAY_OF_WEEK) - weekStart + 7) % 7;

        float w = getWidth(), h = getHeight();
        float headerH = 24 * density, weekdayH = 20 * density;
        int rows = (lead + days + 6) / 7;
        float cellW = w / 7f, cellH = Math.min(30 * density, (h - headerH - weekdayH) / Math.max(rows, 1));

        // Month name, left aligned, like StandBy's red month label (white here, today carries the colour).
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(medium);
        p.setColor(INK);
        p.setLetterSpacing(0.16f);
        p.setTextSize(15 * density);
        String month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault());
        c.drawText(month == null ? "" : month.toUpperCase(Locale.getDefault()), 0, 15 * density, p);
        p.setLetterSpacing(0f);

        // Weekday letters.
        p.setTypeface(regular);
        p.setTextSize(12 * density);
        p.setTextAlign(Paint.Align.CENTER);
        Calendar probe = (Calendar) now.clone();
        for (int i = 0; i < 7; i++) {
            int dow = (weekStart - 1 + i) % 7 + 1;
            probe.set(Calendar.DAY_OF_WEEK, dow);
            String name = probe.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            String letter = name == null || name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.getDefault());
            p.setColor(dow == Calendar.SUNDAY || dow == Calendar.SATURDAY ? 0x66FFFFFF : DIM);
            c.drawText(letter, cellW * (i + 0.5f), headerH + 12 * density, p);
        }

        // Days.
        p.setTextSize(Math.min(15 * density, cellH * 0.62f));
        for (int d = 1; d <= days; d++) {
            int slot = lead + d - 1;
            int col = slot % 7, row = slot / 7;
            int dow = (weekStart - 1 + col) % 7 + 1;
            float cx = cellW * (col + 0.5f);
            float cy = headerH + weekdayH + cellH * (row + 0.5f);
            float baseline = cy + p.getTextSize() * 0.36f;
            if (d == today) {
                if (mono) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.8f * density);
                    p.setColor(INK);
                    c.drawCircle(cx, cy, Math.min(cellH, cellW) * 0.42f, p);
                    p.setStyle(Paint.Style.FILL);
                } else {
                    p.setColor(TODAY);
                    c.drawCircle(cx, cy, Math.min(cellH, cellW) * 0.42f, p);
                }
                p.setColor(INK);
                p.setTypeface(todayFace);
            } else {
                p.setColor(dow == Calendar.SUNDAY || dow == Calendar.SATURDAY ? WEEKEND : INK);
                p.setTypeface(light);
            }
            c.drawText(Integer.toString(d), cx, baseline, p);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (registered) return;
        IntentFilter f = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(dateChanged, f);
        registered = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (registered) {
            try {
                getContext().unregisterReceiver(dateChanged);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
    }

    /** Always-on display look: white only, today as an outlined circle instead of a red disc. */
    void setMono(boolean on) {
        mono = on;
        invalidate();
    }

    /** Home came back to the front: the date may have moved while it was away. */
    void refresh() {
        invalidate();
    }
}
