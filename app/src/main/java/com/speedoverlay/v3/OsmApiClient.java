package com.speedoverlay.v3;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OsmApiClient {

    private static final String TAG = "OsmApiClient";

    private final OkHttpClient httpClient;

    public interface OsmSpeedCallback {
        void onResult(int speedLimit);
        void onError(String message);
    }

    public OsmApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public void fetchSpeedLimit(double lat, double lon, OsmSpeedCallback callback) {
        // Use Overpass API via GET with simple URL encoding
        // Query: find highway ways within 50m with maxspeed tag
        String url = "https://overpass-api.de/api/interpreter"
                + "?data=%5Bout%3Ajson%5D%5Btimeout%3A15%5D%3B"
                + "way%28around%3A50%2C" + lat + "%2C" + lon + "%29%5Bhighway%5D%3B"
                + "out%20tags%205%3B";

        Log.d(TAG, "OSM GET: " + lat + "," + lon);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "SpeedOverlayApp/1.0")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            Log.d(TAG, "OSM HTTP: " + response.code());
            if (!response.isSuccessful()) {
                callback.onError("OSM HTTP " + response.code());
                return;
            }
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "OSM response: " + body);
            int limit = parseOsmResponse(body);
            if (limit > 0) {
                Log.d(TAG, "OSM limit: " + limit);
                callback.onResult(limit);
            } else {
                callback.onError("No maxspeed found");
            }
        } catch (IOException e) {
            callback.onError("OSM error: " + e.getMessage());
            Log.e(TAG, "OSM error", e);
        }
    }

    private int parseOsmResponse(String json) {
        try {
            JSONObject root     = new JSONObject(json);
            JSONArray  elements = root.getJSONArray("elements");
            Log.d(TAG, "OSM elements: " + elements.length());
            int bestLimit = -1;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject way = elements.getJSONObject(i);
                if (!way.has("tags")) continue;
                JSONObject tags = way.getJSONObject("tags");
                Log.d(TAG, "Way " + i + " highway=" + tags.optString("highway", "-")
                        + " maxspeed=" + tags.optString("maxspeed", "-")
                        + " conditional=" + tags.optString("maxspeed:conditional", "-"));
                if (tags.has("maxspeed:conditional")) {
                    int resolved = resolveConditional(tags.getString("maxspeed:conditional"));
                    if (resolved > 0) return resolved;
                }
                if (tags.has("maxspeed") && bestLimit < 0) {
                    int limit = parseMaxspeed(tags.getString("maxspeed"));
                    if (limit > 0) bestLimit = limit;
                }
            }
            return bestLimit;
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage(), e);
            return -1;
        }
    }

    private int resolveConditional(String conditional) {
        try {
            Calendar now   = Calendar.getInstance();
            int curMin     = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            String curDay  = getDayAbbrev(now.get(Calendar.DAY_OF_WEEK));
            for (String part : conditional.split(";")) {
                part = part.trim();
                int atIdx = part.indexOf('@');
                if (atIdx < 0) continue;
                int speed = parseMaxspeed(part.substring(0, atIdx).trim());
                if (speed <= 0) continue;
                String cond = part.substring(atIdx + 1).trim()
                        .replace("(", "").replace(")", "").trim();
                if (matchesCondition(cond, curDay, curMin)) return speed;
            }
        } catch (Exception e) {
            Log.e(TAG, "Conditional error: " + e.getMessage());
        }
        return -1;
    }

    private boolean matchesCondition(String condition, String curDay, int curMin) {
        try {
            String dayPart = "", timePart = condition;
            if (condition.matches("(?i)(Mo|Tu|We|Th|Fr|Sa|Su).*")) {
                int sp = condition.indexOf(' ');
                if (sp > 0) {
                    dayPart  = condition.substring(0, sp).trim();
                    timePart = condition.substring(sp).trim();
                }
            }
            if (!dayPart.isEmpty() && !dayMatches(dayPart, curDay)) return false;
            for (String range : timePart.split(",")) {
                if (timeInRange(range.trim(), curMin)) return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "matchesCondition: " + e.getMessage());
        }
        return false;
    }

    private boolean dayMatches(String spec, String cur) {
        if (spec.contains("-")) {
            String[] p = spec.split("-");
            if (p.length == 2) {
                return dayIndex(cur) >= dayIndex(p[0].trim())
                    && dayIndex(cur) <= dayIndex(p[1].trim());
            }
        }
        for (String d : spec.split(",")) {
            if (d.trim().equalsIgnoreCase(cur)) return true;
        }
        return spec.equalsIgnoreCase(cur);
    }

    private boolean timeInRange(String range, int cur) {
        String[] p = range.split("-");
        if (p.length != 2) return false;
        int s = parseTime(p[0].trim()), e = parseTime(p[1].trim());
        if (s < 0 || e < 0) return false;
        return s <= e ? cur >= s && cur < e : cur >= s || cur < e;
    }

    private int parseTime(String t) {
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return -1; }
    }

    private int dayIndex(String d) {
        switch (d.toLowerCase()) {
            case "mo": return 0; case "tu": return 1; case "we": return 2;
            case "th": return 3; case "fr": return 4; case "sa": return 5;
            case "su": return 6; default: return -1;
        }
    }

    private String getDayAbbrev(int d) {
        switch (d) {
            case Calendar.MONDAY:    return "Mo"; case Calendar.TUESDAY:   return "Tu";
            case Calendar.WEDNESDAY: return "We"; case Calendar.THURSDAY:  return "Th";
            case Calendar.FRIDAY:    return "Fr"; case Calendar.SATURDAY:  return "Sa";
            case Calendar.SUNDAY:    return "Su"; default:                 return "Mo";
        }
    }

    private int parseMaxspeed(String raw) {
        if (raw == null) return -1;
        raw = raw.trim().toLowerCase();
        switch (raw) {
            case "nl:urban":         return 50;
            case "nl:rural":         return 80;
            case "nl:motorway":      return 100;
            case "nl:living_street": return 15;
            case "be:urban":         return 50;
            case "be:rural":         return 70;
            case "be:motorway":      return 120;
            case "de:urban":         return 50;
            case "de:rural":         return 100;
            case "fr:urban":         return 50;
            case "fr:rural":         return 80;
            case "fr:motorway":      return 130;
            case "walk":             return 7;
            case "living_street":    return 15;
            case "none":             return -1;
        }
        if (raw.contains("mph")) {
            try { return (int) Math.round(Integer.parseInt(raw.replace("mph","").trim()) * 1.60934); }
            catch (NumberFormatException e) { return -1; }
        }
        try { return Integer.parseInt(raw.replace("km/h","").trim()); }
        catch (NumberFormatException e) { return -1; }
    }
}
