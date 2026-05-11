package com.speedoverlay.v3;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Handles caching of TomTom speed limit data per road segment.
 * Cache is stored in SharedPreferences as JSON.
 * Entries expire after 24 hours for static limits.
 * Variable (time-based) limits are resolved locally without extra API calls.
 */
public class TomTomSpeedCache {

    private static final String TAG = "TomTomSpeedCache";
    private static final String PREFS_NAME = "SpeedLimitCache";
    private static final long CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L; // 24 hours

    // Minimum distance (meters) before we consider a new segment lookup needed
    public static final float MIN_DISTANCE_FOR_NEW_LOOKUP = 50f;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public TomTomSpeedCache(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ─── Data Models ────────────────────────────────────────────────────────

    public static class ValidityPeriod {
        @SerializedName("startTime") public String startTime; // "HH:mm"
        @SerializedName("endTime")   public String endTime;   // "HH:mm"
        @SerializedName("days")      public List<String> days; // ["Monday","Tuesday",...]
    }

    public static class VariableSpeedEntry {
        @SerializedName("maxSpeedLimit")   public int maxSpeedLimit;
        @SerializedName("speedLimitUnit")  public String speedLimitUnit;
        @SerializedName("validityPeriods") public List<ValidityPeriod> validityPeriods;
    }

    public static class CacheEntry {
        @SerializedName("segmentKey")      public String segmentKey;
        @SerializedName("staticLimit")     public int staticLimit;          // km/h
        @SerializedName("variableLimits")  public List<VariableSpeedEntry> variableLimits;
        @SerializedName("cachedAtMs")      public long cachedAtMs;

        public boolean isExpired() {
            return System.currentTimeMillis() - cachedAtMs > CACHE_EXPIRY_MS;
        }
    }

    // ─── Cache Key ───────────────────────────────────────────────────────────

    /**
     * Rounds lat/lon to ~50m grid so nearby GPS points share the same cache key.
     * 0.0005 degrees ≈ 55 meters.
     */
    public static String buildSegmentKey(double lat, double lon) {
        double roundedLat = Math.round(lat / 0.0005) * 0.0005;
        double roundedLon = Math.round(lon / 0.0005) * 0.0005;
        return String.format("%.4f_%.4f", roundedLat, roundedLon);
    }

    // ─── Get / Put ───────────────────────────────────────────────────────────

    public CacheEntry get(String segmentKey) {
        String json = prefs.getString(segmentKey, null);
        if (json == null) return null;
        CacheEntry entry = gson.fromJson(json, CacheEntry.class);
        if (entry == null || entry.isExpired()) {
            prefs.edit().remove(segmentKey).apply();
            return null;
        }
        return entry;
    }

    public void put(CacheEntry entry) {
        String json = gson.toJson(entry);
        prefs.edit().putString(entry.segmentKey, json).apply();
        Log.d(TAG, "Cached segment: " + entry.segmentKey + " limit=" + entry.staticLimit);
    }

    /** Clear all cached speed limit entries */
    public void clearAll() {
        prefs.edit().clear().apply();
        Log.d(TAG, "Cache cleared");
    }

    /** Returns number of cached segments */
    public int size() {
        return prefs.getAll().size();
    }

    // ─── Time-based Limit Resolution ────────────────────────────────────────

    /**
     * Given a cache entry, returns the speed limit that applies RIGHT NOW
     * based on the current device time. Resolves variable limits locally
     * without any API call.
     */
    public int resolveCurrentLimit(CacheEntry entry) {
        if (entry.variableLimits == null || entry.variableLimits.isEmpty()) {
            return entry.staticLimit;
        }

        Calendar now = Calendar.getInstance();
        int currentHour   = now.get(Calendar.HOUR_OF_DAY);
        int currentMinute = now.get(Calendar.MINUTE);
        int currentTotalMinutes = currentHour * 60 + currentMinute;
        String currentDay = getDayName(now.get(Calendar.DAY_OF_WEEK));

        for (VariableSpeedEntry variable : entry.variableLimits) {
            if (variable.validityPeriods == null) continue;
            for (ValidityPeriod period : variable.validityPeriods) {
                // Check if today matches
                if (period.days != null && !period.days.contains(currentDay)) continue;

                // Parse start/end times
                int startMinutes = parseTimeToMinutes(period.startTime);
                int endMinutes   = parseTimeToMinutes(period.endTime);

                if (startMinutes < 0 || endMinutes < 0) continue;

                // Handle overnight periods (e.g. 23:00 to 06:00)
                boolean inPeriod;
                if (startMinutes <= endMinutes) {
                    inPeriod = currentTotalMinutes >= startMinutes && currentTotalMinutes < endMinutes;
                } else {
                    inPeriod = currentTotalMinutes >= startMinutes || currentTotalMinutes < endMinutes;
                }

                if (inPeriod) {
                    Log.d(TAG, "Variable limit active: " + variable.maxSpeedLimit +
                          " (" + period.startTime + "-" + period.endTime + ")");
                    return variable.maxSpeedLimit;
                }
            }
        }

        // No variable period matched → fall back to static limit
        return entry.staticLimit;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private int parseTimeToMinutes(String time) {
        if (time == null) return -1;
        String[] parts = time.split(":");
        if (parts.length < 2) return -1;
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String getDayName(int calendarDay) {
        switch (calendarDay) {
            case Calendar.MONDAY:    return "Monday";
            case Calendar.TUESDAY:   return "Tuesday";
            case Calendar.WEDNESDAY: return "Wednesday";
            case Calendar.THURSDAY:  return "Thursday";
            case Calendar.FRIDAY:    return "Friday";
            case Calendar.SATURDAY:  return "Saturday";
            case Calendar.SUNDAY:    return "Sunday";
            default:                 return "";
        }
    }
}
