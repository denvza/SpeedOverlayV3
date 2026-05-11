package com.speedoverlay.v3;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpeedLimitFetcher {

    private static final String TAG           = "SpeedLimitFetcher";
    private static final String OVERPASS_URL  = "https://overpass-api.de/api/interpreter";
    private static final double REFETCH_DIST  = 100.0;

    private static class RoadData {
        String maxspeed;
        String maxspeedConditional;
    }

    public interface Callback {
        void onSpeedLimitResult(Integer speedLimitKmh);
    }

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final OsmCache        osmCache;

    private double   lastFetchLat = 0;
    private double   lastFetchLon = 0;
    private RoadData cachedRoad   = null;
    private boolean  fetching     = false;

    public SpeedLimitFetcher(Context context) {
        this.osmCache = new OsmCache(context);
    }

    public void fetchIfNeeded(double lat, double lon, Callback callback) {
        // Check persistent cache first
        String cacheKey = OsmCache.buildKey(lat, lon);
        int cached = osmCache.get(cacheKey);
        if (cached > 0) {
            Log.d(TAG, "OSM persistent cache HIT: " + cached);
            callback.onSpeedLimitResult(cached);
            return;
        }

        // Check in-memory cached road data
        if (cachedRoad != null) {
            double dist = distanceMeters(lat, lon, lastFetchLat, lastFetchLon);
            if (dist < REFETCH_DIST) {
                callback.onSpeedLimitResult(effectiveLimit(cachedRoad));
                return;
            }
        }

        if (fetching) return;
        fetching     = true;
        lastFetchLat = lat;
        lastFetchLon = lon;

        executor.execute(() -> {
            RoadData road  = queryOverpass(lat, lon);
            cachedRoad     = road;
            fetching       = false;
            Integer limit  = effectiveLimit(road);

            // Save to persistent cache
            if (limit != null && limit > 0) {
                osmCache.put(cacheKey, limit);
            }

            mainHandler.post(() -> callback.onSpeedLimitResult(limit));
        });
    }

    public OsmCache getCache() { return osmCache; }

    public void shutdown() { executor.shutdown(); }

    private RoadData queryOverpass(double lat, double lon) {
        String query = "[out:json][timeout:8];"
                + "way(around:30," + lat + "," + lon + ")"
                + "[highway][~\"^maxspeed\"~\".\"];"
                + "out tags 1;";
        try {
            URL url = new URL(OVERPASS_URL + "?data=" +
                    java.net.URLEncoder.encode(query, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestProperty("User-Agent", "SpeedOverlayApp/3.0");
            int code = conn.getResponseCode();
            if (code != 200) { Log.w(TAG, "Overpass HTTP " + code); return null; }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return parseRoadData(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Overpass failed: " + e.getMessage());
            return null;
        }
    }

    private RoadData parseRoadData(String json) {
        try {
            JSONObject root     = new JSONObject(json);
            JSONArray  elements = root.getJSONArray("elements");
            if (elements.length() == 0) return null;
            JSONObject tags = elements.getJSONObject(0).getJSONObject("tags");
            RoadData road = new RoadData();
            road.maxspeed            = tags.optString("maxspeed", null);
            road.maxspeedConditional = tags.optString("maxspeed:conditional", null);
            Log.d(TAG, "maxspeed=" + road.maxspeed + " conditional=" + road.maxspeedConditional);
            return road;
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            return null;
        }
    }

    private Integer effectiveLimit(RoadData road) {
        if (road == null) return null;
        if (road.maxspeedConditional != null) {
            Integer conditional = evaluateConditional(road.maxspeedConditional);
            if (conditional != null) return conditional;
        }
        return parseSpeedString(road.maxspeed);
    }

    private Integer evaluateConditional(String conditional) {
        if (conditional == null || conditional.isEmpty()) return null;
        for (String rule : conditional.split(";")) {
            rule = rule.trim();
            int atIdx = rule.indexOf('@');
            if (atIdx < 0) continue;
            String speedPart     = rule.substring(0, atIdx).trim();
            String conditionPart = rule.substring(atIdx + 1).trim()
                    .replaceAll("^\\(|\\)$", "").trim();
            Integer speed = parseSpeedString(speedPart);
            if (speed == null) continue;
            if (conditionMatches(conditionPart)) return speed;
        }
        return null;
    }

    private boolean conditionMatches(String condition) {
        for (String sub : condition.split(";")) {
            if (subConditionMatches(sub.trim())) return true;
        }
        return false;
    }

    private boolean subConditionMatches(String condition) {
        Calendar now = Calendar.getInstance();
        int nowMin   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int dow      = now.get(Calendar.DAY_OF_WEEK);
        String dayPart = null;
        String timePart = condition;
        Pattern dayPattern = Pattern.compile(
                "^(Mo|Tu|We|Th|Fr|Sa|Su)-(Mo|Tu|We|Th|Fr|Sa|Su)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher dayMatcher = dayPattern.matcher(condition);
        if (dayMatcher.matches()) {
            dayPart  = dayMatcher.group(1) + "-" + dayMatcher.group(2);
            timePart = dayMatcher.group(3).trim();
        }
        if (dayPart != null && !dayRangeMatches(dayPart, dow)) return false;
        return timeRangeMatches(timePart, nowMin);
    }

    private boolean dayRangeMatches(String range, int dow) {
        String[] parts = range.split("-");
        if (parts.length != 2) return true;
        int start = dayNameToInt(parts[0].trim());
        int end   = dayNameToInt(parts[1].trim());
        if (start < 0 || end < 0) return true;
        return start <= end ? dow >= start && dow <= end : dow >= start || dow <= end;
    }

    private int dayNameToInt(String day) {
        switch (day.toLowerCase()) {
            case "mo": return 2; case "tu": return 3; case "we": return 4;
            case "th": return 5; case "fr": return 6; case "sa": return 7;
            case "su": return 1; default: return -1;
        }
    }

    private boolean timeRangeMatches(String range, int nowMin) {
        Pattern p = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");
        Matcher m = p.matcher(range);
        if (!m.find()) return false;
        int startMin = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        int endMin   = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
        if (startMin == 0 && endMin == 24 * 60) return true;
        return startMin < endMin ? nowMin >= startMin && nowMin < endMin
                                 : nowMin >= startMin || nowMin < endMin;
    }

    private Integer parseSpeedString(String value) {
        if (value == null || value.isEmpty()) return null;
        value = value.trim().toLowerCase();
        if (value.contains("motorway")) return 130;
        if (value.contains("rural"))    return 80;
        if (value.contains("urban"))    return 50;
        if (value.contains("living"))   return 15;
        if (value.contains("walk"))     return 7;
        if (value.equals("none"))       return null;
        value = value.replace("km/h", "").trim();
        if (value.contains("mph")) {
            try { return (int) Math.round(Double.parseDouble(value.replace("mph","").trim()) * 1.60934); }
            catch (NumberFormatException e) { return null; }
        }
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
