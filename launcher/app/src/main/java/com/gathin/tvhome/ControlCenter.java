package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

/**
 * The Control Centre: one glass panel with Wi-Fi, Bluetooth, Inputs and Settings as big rounded tiles, the way
 * macOS lays them out. Built in code (no layout file) and event-driven: the Wi-Fi and Bluetooth tiles follow the
 * system's state broadcasts only while the panel is on screen, and nothing runs while it is closed.
 *
 * Safety: a Bluetooth remote is often the only way to drive the TV, so the Bluetooth tile only switches
 * Bluetooth ON; when it is on, OK opens the Bluetooth settings instead of switching it off.
 */
final class ControlCenter extends LinearLayout {
    private static final int TILE = 0xFF3A3A3C, TILE_FOCUS = 0xFF58585C, ACTIVE = 0xFFF2F2F7, INK = 0xFF1C1C1E;

    private final Callbacks cb;
    private final float density;
    private Tile wifiTile, btTile, airTile;
    private boolean airBusy;
    private View first;
    private boolean registered;

    interface Callbacks {
        void openInputs();
        void openSettings();
        void openIntent(Intent intent);
        /** Null when the launcher has no such action. */
        Runnable launcherSettings();
        Runnable reorder();
        void dismiss();
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    ControlCenter(Context context, Callbacks cb) {
        super(context);
        this.cb = cb;
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFC1C1C1E);
        bg.setCornerRadius(dp(28));
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        setBackground(bg);

        wifiTile = tile(R.drawable.ic_wifi, context.getString(R.string.wifi));
        btTile = tile(R.drawable.ic_bluetooth, "Bluetooth");
        Tile inputs = tile(R.drawable.ic_input, context.getString(R.string.inputs));
        Tile settings = tile(R.drawable.ic_settings, context.getString(R.string.settings));
        inputs.sub.setVisibility(GONE);
        settings.sub.setVisibility(GONE);
        addRow(wifiTile, btTile);
        addRow(inputs, settings);
        // Third row onwards: AirPlay, then Launcher settings and Reorder where the launcher has them.
        java.util.List<Tile> extras = new java.util.ArrayList<>();
        airTile = tile(R.drawable.ic_airplay, context.getString(R.string.airplay_title));
        airTile.root.setOnClickListener(v -> airplayClicked());
        extras.add(airTile);
        Runnable launcher = cb.launcherSettings();
        if (launcher != null) {
            Tile t = tile(R.drawable.ic_home_settings_pill, "Launcher");   // the full name does not fit a half-width tile
            t.sub.setVisibility(GONE);
            t.root.setOnClickListener(v -> { cb.dismiss(); launcher.run(); });
            extras.add(t);
        }
        Runnable reorder = cb.reorder();
        if (reorder != null) {
            Tile t = tile(R.drawable.ic_swap_vert, context.getString(R.string.reorder));
            t.sub.setVisibility(GONE);
            t.root.setOnClickListener(v -> { cb.dismiss(); reorder.run(); });
            extras.add(t);
        }
        for (int i = 0; i < extras.size(); i += 2) addRow(extras.get(i), i + 1 < extras.size() ? extras.get(i + 1) : null);
        first = wifiTile.root;

        wifiTile.root.setOnClickListener(v -> toggleWifi());
        wifiTile.root.setOnLongClickListener(v -> { go(new Intent(Settings.ACTION_WIFI_SETTINGS)); return true; });
        btTile.root.setOnClickListener(v -> bluetoothClicked());
        btTile.root.setOnLongClickListener(v -> { go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return true; });
        inputs.root.setOnClickListener(v -> { cb.dismiss(); cb.openInputs(); });
        settings.root.setOnClickListener(v -> { cb.dismiss(); cb.openSettings(); });
        refresh();
        showAirplay(AirPlayControl.cachedOn(context), null);
        AirPlayControl.refresh(context, (on, note) -> showAirplay(on, note));
    }

    View firstFocus() { return first; }

    private int dp(float v) { return Math.round(v * density); }

    private void go(Intent i) {
        cb.dismiss();
        cb.openIntent(i);
    }

    // ---- tiles -----------------------------------------------------------------------------------

    private static final class Tile {
        LinearLayout root;
        android.widget.FrameLayout chip;
        ImageView icon;
        TextView title, sub;
        boolean active;
    }

    private Tile tile(int iconRes, String label) {
        Context c = getContext();
        Tile t = new Tile();
        t.root = new LinearLayout(c);
        t.root.setOrientation(HORIZONTAL);
        t.root.setGravity(Gravity.CENTER_VERTICAL);
        t.root.setPadding(dp(12), 0, dp(14), 0);
        t.root.setFocusable(true);
        t.root.setClickable(true);
        t.root.setBackground(round(TILE, 18));

        t.chip = new android.widget.FrameLayout(c);
        t.icon = new ImageView(c);
        t.icon.setImageResource(iconRes);
        t.chip.addView(t.icon, new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        t.root.addView(t.chip, new LayoutParams(dp(40), dp(40)));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        t.title = new TextView(c);
        t.title.setTextColor(Color.WHITE);
        t.title.setTextSize(15);
        t.title.setSingleLine();
        t.title.setTypeface(Typeface.create(c.getString(R.string.font_medium), Typeface.NORMAL));
        t.title.setText(label);
        t.sub = new TextView(c);
        t.sub.setTextColor(0x99FFFFFF);
        t.sub.setTextSize(12);
        t.sub.setSingleLine();
        t.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.sub.setTypeface(Typeface.create(c.getString(R.string.font_regular), Typeface.NORMAL));
        text.addView(t.title);
        text.addView(t.sub);
        LayoutParams tp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(12));
        t.root.addView(text, tp);

        SpringAnimation sx = new SpringAnimation(t.root, DynamicAnimation.SCALE_X);
        SpringAnimation sy = new SpringAnimation(t.root, DynamicAnimation.SCALE_Y);
        sx.setSpring(Motion.focus(true).setFinalPosition(1f));
        sy.setSpring(Motion.focus(true).setFinalPosition(1f));
        t.root.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.05f : 1f;
            sx.animateToFinalPosition(s);
            sy.animateToFinalPosition(s);
            v.setBackground(round(focused ? TILE_FOCUS : TILE, 18));
            v.setElevation(focused ? dp(6) : 0f);
        });
        setActive(t, false);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void setActive(Tile t, boolean active) {
        t.active = active;
        t.chip.setBackground(round(active ? ACTIVE : 0x40FFFFFF, 20));
        t.icon.setColorFilter(active ? INK : Color.WHITE);
    }

    private void addRow(Tile a, Tile b) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setClipChildren(false);
        LayoutParams lp = new LayoutParams(dp(156), dp(64));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        if (b != null) {
            row.addView(a.root, lp);
            row.addView(b.root, new LayoutParams(lp));
        } else {
            // A lone tile spans both columns, so its label fits.
            LayoutParams wide = new LayoutParams(dp(156 * 2 + 8), dp(64));
            wide.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(a.root, wide);
        }
        LayoutParams rp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(row, rp);
        setClipChildren(false);
    }

    // ---- state -----------------------------------------------------------------------------------

    private void airplayClicked() {
        if (airBusy) return;
        airBusy = true;
        AirPlayControl.toggle(getContext(), (on, note) -> showAirplay(on, note));
    }

    private void showAirplay(Boolean on, String note) {
        boolean running = on != null && on;
        setActive(airTile, running);
        airTile.sub.setText(note != null ? note : running ? "On" : "Off");
        if (note == null || "Could not stop".equals(note)) airBusy = false;
    }

    private WifiManager wifi() {
        return (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private void refresh() {
        WifiManager wm = wifi();
        boolean wifiOn = wm != null && wm.isWifiEnabled();
        setActive(wifiTile, wifiOn);
        String ssid = null;
        if (wifiOn) {
            try {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null && !"<unknown ssid>".equals(info.getSSID())) {
                    ssid = info.getSSID().replace("\"", "");
                }
            } catch (SecurityException ignored) {
            }
        }
        wifiTile.sub.setText(!wifiOn ? "Off" : ssid != null ? ssid : "On");

        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        boolean btOn = false;
        try {
            btOn = bt != null && bt.isEnabled();
        } catch (SecurityException ignored) {
        }
        setActive(btTile, btOn);
        btTile.sub.setText(bt == null ? getContext().getString(R.string.not_available) : btOn ? "On" : "Off");
    }

    private void toggleWifi() {
        WifiManager wm = wifi();
        // Apps may switch Wi-Fi only before Android 10; later versions get the Wi-Fi settings instead.
        if (wm == null || Build.VERSION.SDK_INT >= 29) {
            go(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        try {
            wm.setWifiEnabled(!wm.isWifiEnabled());
        } catch (SecurityException e) {
            go(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    @SuppressLint("MissingPermission")
    private void bluetoothClicked() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) return;
        try {
            if (!bt.isEnabled() && Build.VERSION.SDK_INT < 33) {
                bt.enable();
                return;
            }
        } catch (SecurityException ignored) {
        }
        go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    // Follow the system only while the panel is on screen.
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (registered) return;
        IntentFilter f = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        getContext().registerReceiver(stateReceiver, f);
        registered = true;
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (registered) {
            try {
                getContext().unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
    }
}
