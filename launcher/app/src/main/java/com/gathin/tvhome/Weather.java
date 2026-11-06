package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Scanner;

/**
 * Current weather from Open-Meteo (open data, no key, no account). One small request when Home comes to the front
 * and the last answer is over 30 minutes old; nothing in between: no timer, no service, no polling. The city is
 * looked up once and its coordinates kept. Off while config.xml's weather_city is empty.
 *
 * Preview: a file weather_override.json in the app's files folder ({"code":95,"day":false,"wind":30,"cloud":90,
 * "precip":6,"temp":24}) replaces the live answer, so every kind of weather can be tried on the home screen.
 */
final class Weather {
    interface Listener {
        /** @param code WMO weather code; wind in km/h, cloud cover in percent, precipitation in mm; aqi is the US AQI or -1. */
        void onWeather(int code, boolean day, int tempC, float windKmh, float cloudPct, float precipMm, int aqi);
    }

    private static final long FRESH_MS = 30 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    private Weather() {
    }

    static void refresh(Context context, String city, Listener l) {
        final Context app = context.getApplicationContext();
        if (preview(app, l)) return;
        if (city == null || city.trim().isEmpty()) return;
        final SharedPreferences sp = app.getSharedPreferences("weather", Context.MODE_PRIVATE);
        final String key = city.trim();
        boolean same = key.equals(sp.getString("city", ""));
        if (same && sp.contains("code") && sp.contains("wind") && sp.contains("aqi")) {
            l.onWeather(sp.getInt("code", 0), sp.getBoolean("day", true), sp.getInt("temp", 0),
                    sp.getFloat("wind", 0f), sp.getFloat("cloud", 0f), sp.getFloat("precip", 0f), sp.getInt("aqi", -1));
            if (System.currentTimeMillis() - sp.getLong("ts", 0) < FRESH_MS) return;
        }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                double lat, lon;
                if (same && sp.contains("lat")) {
                    lat = Double.longBitsToDouble(sp.getLong("lat", 0));
                    lon = Double.longBitsToDouble(sp.getLong("lon", 0));
                } else {
                    JSONObject g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(key, "UTF-8"));
                    JSONObject hit = g.getJSONArray("results").getJSONObject(0);
                    lat = hit.getDouble("latitude");
                    lon = hit.getDouble("longitude");
                    sp.edit().putString("city", key).putLong("lat", Double.doubleToLongBits(lat))
                            .putLong("lon", Double.doubleToLongBits(lon)).remove("code").apply();
                }
                JSONObject cur = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code,is_day,"
                        + "wind_speed_10m,cloud_cover,precipitation&latitude=" + lat + "&longitude=" + lon).getJSONObject("current");
                final int code = cur.getInt("weather_code");
                final boolean day = cur.getInt("is_day") == 1;
                final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                final float wind = (float) cur.optDouble("wind_speed_10m", 0);
                final float cloud = (float) cur.optDouble("cloud_cover", 0);
                final float precip = (float) cur.optDouble("precipitation", 0);
                int aqi = sp.getInt("aqi", -1);
                try {
                    // Air quality is a separate free feed; if it fails the weather still shows.
                    aqi = (int) Math.round(get("https://air-quality-api.open-meteo.com/v1/air-quality?current=us_aqi&latitude="
                            + lat + "&longitude=" + lon).getJSONObject("current").getDouble("us_aqi"));
                } catch (Exception ignored) {
                }
                final int air = aqi;
                sp.edit().putInt("code", code).putBoolean("day", day).putInt("temp", temp).putFloat("wind", wind)
                        .putFloat("cloud", cloud).putFloat("precip", precip).putInt("aqi", air)
                        .putLong("ts", System.currentTimeMillis()).apply();
                MAIN.post(() -> l.onWeather(code, day, temp, wind, cloud, precip, air));
            } catch (Exception ignored) {
                // Offline or the service is down: whatever was shown stays.
            } finally {
                busy = false;
            }
        }, "weather").start();
    }

    /** The preview file, if there is one: delivered at once, no network. */
    private static boolean preview(Context app, Listener l) {
        try {
            File dir = app.getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, "weather_override.json");
            if (f == null || !f.isFile()) return false;
            String text;
            try (InputStream in = new FileInputStream(f); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "{}";
            }
            JSONObject o = new JSONObject(text);
            l.onWeather(o.optInt("code", 0), o.optBoolean("day", true), o.optInt("temp", 25), (float) o.optDouble("wind", 8),
                    (float) o.optDouble("cloud", 30), (float) o.optDouble("precip", 0), o.optInt("aqi", -1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return new JSONObject(s.hasNext() ? s.next() : "{}");
        } finally {
            c.disconnect();
        }
    }
}
