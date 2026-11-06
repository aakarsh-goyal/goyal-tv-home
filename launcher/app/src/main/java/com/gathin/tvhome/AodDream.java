package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Random;

/**
 * Standby, in the manner of Apple's always-on display: the screen goes pitch black and only the left half of Home's
 * information stays, in plain white: greeting, time, date, the month with today in an outline. No colour, no pictures,
 * nothing on the right, nothing animated. The only changes are the clock's minute and, once a minute, a nudge of a few
 * pixels so the same pixels do not stay lit for hours.
 */
public final class AodDream extends DreamService {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private LinearLayout pane;
    private TextView greeting, weather;
    private float density;
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            refreshTexts();
            pane.setTranslationX((rnd.nextInt(21) - 10) * density);
            pane.setTranslationY((rnd.nextInt(21) - 10) * density);
            ui.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        density = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(56), dp(30), dp(20), dp(30));

        greeting = text(12, 0x99FFFFFF, "sans-serif-medium", 0.22f, true);
        pane.addView(greeting, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(104);
        clock.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        clock.setLetterSpacing(-0.03f);
        clock.setIncludeFontPadding(false);
        timeRow.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        TextClock ampm = new TextClock(this);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("");
        ampm.setTextColor(0xB3FFFFFF);
        ampm.setTextSize(20);
        ampm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ampm.setAllCaps(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2, 0f);
        ap.gravity = Gravity.BOTTOM;
        ap.setMargins(dp(8), 0, 0, dp(14));
        timeRow.addView(ampm, ap);
        pane.addView(timeRow, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(this);
        String fmt = getString(R.string.date_format_home);
        date.setFormat12Hour(fmt);
        date.setFormat24Hour(fmt);
        date.setTextColor(0xE6FFFFFF);
        date.setTextSize(16);
        date.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        date.setLetterSpacing(0.14f);
        date.setAllCaps(true);
        date.setMaxLines(1);
        dateRow.addView(date, new LinearLayout.LayoutParams(-2, -2));
        weather = text(16, 0xE6FFFFFF, "sans-serif", 0.06f, false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
        wp.setMarginStart(dp(16));
        dateRow.addView(weather, wp);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = dp(2);
        pane.addView(dateRow, dp);

        MonthCalendarView cal = new MonthCalendarView(this);
        cal.setMono(true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(250));
        cp.topMargin = dp(18);
        cp.setMarginEnd(dp(8));
        pane.addView(cal, cp);

        root.addView(pane, new FrameLayout.LayoutParams((int) (screenW * 0.44f), -1, Gravity.START));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        ui.removeCallbacks(minute);
        ui.post(minute);
    }

    @Override
    public void onDreamingStopped() {
        ui.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    private TextView text(int sp, int color, String family, float spacing, boolean caps) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setLetterSpacing(spacing);
        t.setAllCaps(caps);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** The greeting and the temperature: plain text, from what Home last fetched. */
    private void refreshTexts() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning : hour < 17 ? R.string.good_afternoon
                : hour < 22 ? R.string.good_evening : R.string.good_night;
        String name = getString(R.string.greeting_name);
        greeting.setText(name.isEmpty() ? getString(res) : getString(R.string.greeting_with_name, getString(res), name));
        SharedPreferences sp = getSharedPreferences("weather", Context.MODE_PRIVATE);
        StringBuilder w = new StringBuilder();
        if (sp.contains("temp")) w.append(sp.getInt("temp", 0)).append("°");
        int aqi = sp.getInt("aqi", -1);
        if (aqi >= 0) w.append(w.length() > 0 ? "  ·  " : "").append("AQI ").append(aqi);
        weather.setText(w);
    }
}
