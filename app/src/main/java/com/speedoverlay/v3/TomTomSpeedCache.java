package com.speedoverlay.v3;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persistent TomTom freeFlowSpeed cache — stores data for 7 days.
 * Keys are rounded GPS coordinates (~50m grid), same as OsmCache.
 */
public class TomTomSpeedCache {

    private static final String TAG        = "TomTomSpeedCache";
    private static final String PREFS_NAME = "TomTomFlowCache";
    private static final long   EXPIRY_MS  = 7L * 24 * 60 * 60 * 1000; // 7 days

    private final SharedPreferences prefs;

    public TomTomSpeedCache(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns cached freeFlowSpeed or -1 if not cached / expired */
    public int get(String key) {
        String raw = prefs.getString(key, null);
        if (raw == null) return -1;
        try {
            String[] parts = raw.split(":");
            long timestamp = Long.parseLong(parts[0]);
            int   value    = Integer.parseInt(parts[1]);
            if (System.currentTimeMillis() - timestamp > EXPIRY_MS) {
                prefs.edit().remove(key).apply();
                return -1;
            }
            return value;
        } catch (Exception e) {
            return -1;
        }
    }

    public void put(String key, int freeFlowSpeed) {
        String raw = System.currentTimeMillis() + ":" + freeFlowSpeed;
        prefs.edit().putString(key, raw).apply();
        Log.d(TAG, "Cached TomTom: " + key + " = " + freeFlowSpeed);
    }

    public void clearAll() {
        prefs.edit().clear().apply();
        Log.d(TAG, "TomTom cache cleared");
    }

    public int size() {
        return prefs.getAll().size();
    }
}
