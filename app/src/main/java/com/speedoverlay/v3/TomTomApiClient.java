package com.speedoverlay.v3;

import android.util.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches freeFlowSpeed from TomTom Traffic Flow API.
 * This is NOT the posted speed limit — it is the typical free flow speed.
 * Used as secondary display only (e.g. "30/34").
 */
public class TomTomApiClient {

    private static final String TAG = "TomTomApiClient";
    private static final String BASE_URL =
            "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json";

    private final OkHttpClient httpClient;
    private final String apiKey;

    public interface FlowSpeedCallback {
        void onResult(int freeFlowSpeed);
        void onError(String message);
    }

    public TomTomApiClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public void fetchFreeFlowSpeed(double lat, double lon, FlowSpeedCallback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError("No API key");
            return;
        }
        String url = BASE_URL + "?point=" + lat + "," + lon + "&key=" + apiKey + "&unit=KMPH";
        Log.d(TAG, "Fetching flow: " + url.replace(apiKey, "***"));
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                callback.onError("HTTP " + response.code());
                return;
            }
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Flow response: " + body);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("flowSegmentData")) {
                JsonObject data = root.getAsJsonObject("flowSegmentData");
                if (data.has("freeFlowSpeed")) {
                    int speed = data.get("freeFlowSpeed").getAsInt();
                    Log.d(TAG, "freeFlowSpeed: " + speed);
                    callback.onResult(speed);
                    return;
                }
            }
            callback.onError("No freeFlowSpeed in response");
        } catch (Exception e) {
            callback.onError("Error: " + e.getMessage());
            Log.e(TAG, "Error", e);
        }
    }
}
