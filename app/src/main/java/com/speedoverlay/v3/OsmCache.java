package com.speedoverlay.v3;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persistent OSM speed limit cache — stores data for 7 days.
 * Keys are rounded GPS coordinates (~50m grid).
 */
public class OsmCache {

    private static final String TAG        = "OsmCache";
    private static final String PREFS_NAME = "OsmSpeedCache";
    private static final long   EXPIRY_MS  = 7L * 24 * 60 * 60 * 1000; // 7 days

    private final SharedPreferences prefs;

    public OsmCache(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String buildKey(double lat, double lon) {
        double rLat = Math.round(lat / 0.0005) * 0.0005;
        double rLon = Math.round(lon / 0.0005) * 0.0005;
        return String.format("%.4f_%.4f", rLat, rLon);
    }

    /** Returns cached speed limit or -1 if not cached / expired */
    public int get(String key) {
        String raw = prefs.getString(key, null);
        if (raw == null) return -1;
        try {
            String[] parts = raw.split(":");
            long timestamp = Long.parseLong(parts[0]);
            int   limit    = Integer.parseInt(parts[1]);
            if (System.currentTimeMillis() - timestamp > EXPIRY_MS) {
                prefs.edit().remove(key).apply();
                return -1;
            }
            return limit;
        } catch (Exception e) {
            return -1;
        }
    }

    public void put(String key, int speedLimit) {
        String raw = System.currentTimeMillis() + ":" + speedLimit;
        prefs.edit().putString(key, raw).apply();
        Log.d(TAG, "Cached OSM: " + key + " = " + speedLimit);
    }

    public void clearAll() {
        prefs.edit().clear().apply();
        Log.d(TAG, "OSM cache cleared");
    }

    public int size() {
        return prefs.getAll().size();
    }
}
