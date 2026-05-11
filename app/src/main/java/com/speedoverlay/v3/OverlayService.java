package com.speedoverlay.v3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {

    public static boolean isRunning = false;

    private static final String CHANNEL_ID    = "SpeedOverlayChannel";
    private static final int    NOTIFICATION_ID = 1001;
    private static final String PREFS_SETTINGS  = "SpeedOverlaySettings";
    private static final String KEY_API_KEY     = "tomtom_api_key";

    private static final int MIN_SIZE_DP  = 80;
    private static final int MAX_SIZE_DP  = 220;
    private static final int BASE_SIZE_DP = 110;

    // Standard speed limit steps for Change4 snapping
    private static final int[] SPEED_STEPS = {10, 15, 30, 50, 60, 70, 80, 90, 100, 120, 130};

    private WindowManager              windowManager;
    private View                       overlayView;
    private View                       resizeHandle;
    private View                       purpleDot;
    private WindowManager.LayoutParams layoutParams;

    private TextView tvSpeed;
    private TextView tvUnit;
    private TextView tvSpeedLimit;

    private int   initialX, initialY;
    private float initialTouchX, initialTouchY;
    private int   initialWidth, initialHeight;
    private float initialResizeTouchX, initialResizeTouchY;
    private float density;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;

    private SpeedLimitFetcher speedLimitFetcher;
    private TomTomApiClient   tomTomClient;
    private boolean           hasTomTomKey = false;

    // Current values
    private Integer osmLimit     = null; // null = never received any data
    private int     tomTomFlow   = -1;
    private int     displayLimit = -1;   // the resolved limit shown and used for colors

    private double lastTomTomLat = 0, lastTomTomLon = 0;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        density   = getResources().getDisplayMetrics().density;

        speedLimitFetcher = new SpeedLimitFetcher(this);

        SharedPreferences prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        String apiKey = prefs.getString(KEY_API_KEY, "").trim();
        hasTomTomKey  = !apiKey.isEmpty();
        if (hasTomTomKey) tomTomClient = new TomTomApiClient(apiKey);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        setupOverlay();
        startLocationUpdates();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (fusedLocationClient != null && locationCallback != null)
            fusedLocationClient.removeLocationUpdates(locationCallback);
        if (windowManager != null && overlayView != null)
            windowManager.removeView(overlayView);
        if (speedLimitFetcher != null) speedLimitFetcher.shutdown();
        executor.shutdown();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView   = LayoutInflater.from(this).inflate(R.layout.overlay_speed, null);

        tvSpeed      = overlayView.findViewById(R.id.tvSpeed);
        tvUnit       = overlayView.findViewById(R.id.tvUnit);
        tvSpeedLimit = overlayView.findViewById(R.id.tvSpeedLimit);
        resizeHandle = overlayView.findViewById(R.id.resizeHandle);
        purpleDot    = overlayView.findViewById(R.id.purpleDot);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int sizePx = dpToPx(BASE_SIZE_DP);
        layoutParams = new WindowManager.LayoutParams(
                sizePx, sizePx, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 50; layoutParams.y = 200;

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = layoutParams.x; initialY = layoutParams.y;
                    initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = initialX + (int)(event.getRawX() - initialTouchX);
                    layoutParams.y = initialY + (int)(event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(overlayView, layoutParams);
                    return true;
                case MotionEvent.ACTION_UP: return true;
            }
            return false;
        });

        resizeHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialWidth = layoutParams.width; initialHeight = layoutParams.height;
                    initialResizeTouchX = event.getRawX(); initialResizeTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialResizeTouchX;
                    float dy = event.getRawY() - initialResizeTouchY;
                    float delta = Math.abs(dx) >= Math.abs(dy) ? dx : dy;
                    int newSize = Math.max(dpToPx(MIN_SIZE_DP),
                            Math.min(dpToPx(MAX_SIZE_DP), (int)(initialWidth + delta)));
                    layoutParams.width = newSize; layoutParams.height = newSize;
                    float ratio = (float) newSize / dpToPx(BASE_SIZE_DP);
                    tvSpeed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38 * ratio);
                    tvUnit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11 * ratio);
                    tvSpeedLimit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18 * ratio);
                    windowManager.updateViewLayout(overlayView, layoutParams);
                    return true;
                case MotionEvent.ACTION_UP: return true;
            }
            return false;
        });

        windowManager.addView(overlayView, layoutParams);
        applyColors(0, null, false);
        tvSpeedLimit.setText("--");
    }

    private void startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500).build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                for (Location loc : result.getLocations()) onLocationUpdate(loc);
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) { e.printStackTrace(); }
    }

    private void onLocationUpdate(Location location) {
        int speed = Math.round(location.hasSpeed() ? location.getSpeed() * 3.6f : 0f);

        tvSpeed.setText(String.valueOf(speed));
        applyColors(speed, displayLimit > 0 ? displayLimit : null, osmLimit == null);

        double lat = location.getLatitude(), lon = location.getLongitude();

        // OSM fetch (with persistent cache)
        speedLimitFetcher.fetchIfNeeded(lat, lon, limit -> {
            if (limit != null && limit > 0) {
                osmLimit = limit;
            }
            // Change3: if no OSM data keep last value, don't reset
            resolveAndDisplay(speed);
        });

        // TomTom freeFlowSpeed — optional, only if key provided
        if (hasTomTomKey) {
            double dist = distanceMeters(lat, lon, lastTomTomLat, lastTomTomLon);
            if (dist > 100 || lastTomTomLat == 0) {
                lastTomTomLat = lat; lastTomTomLon = lon;
                executor.execute(() -> tomTomClient.fetchFreeFlowSpeed(lat, lon,
                    new TomTomApiClient.FlowSpeedCallback() {
                        @Override public void onResult(int freeFlowSpeed) {
                            tomTomFlow = freeFlowSpeed;
                            overlayView.post(() -> resolveAndDisplay(speed));
                        }
                        @Override public void onError(String msg) {}
                    }));
            }
        }
    }

    /**
     * Change4: If difference between TomTom and OSM > 15 km/h,
     * snap TomTom value to nearest standard speed step and use that instead.
     */
    private void resolveAndDisplay(int currentSpeed) {
        int resolved = -1;
        boolean noOsmData = (osmLimit == null);

        if (osmLimit != null && osmLimit > 0) {
            resolved = osmLimit;
            // Change4: if TomTom diverges >15 km/h, snap TomTom and use instead
            if (hasTomTomKey && tomTomFlow > 0 && Math.abs(tomTomFlow - osmLimit) > 15) {
                int snapped = snapToNearestStep(tomTomFlow);
                if (snapped > 0) resolved = snapped;
            }
        } else if (hasTomTomKey && tomTomFlow > 0) {
            // No OSM data — snap TomTom as fallback, purple dot shows
            resolved = snapToNearestStep(tomTomFlow);
        }

        displayLimit = resolved;

        // Build display string
        String display;
        if (resolved > 0 && hasTomTomKey && tomTomFlow > 0) {
            display = resolved + "/" + tomTomFlow;
        } else if (resolved > 0) {
            display = String.valueOf(resolved);
        } else {
            display = "--";
        }

        tvSpeedLimit.setText(display);

        // Colors react to resolved limit (OSM or snapped TomTom)
        // Purple dot shown whenever OSM data is unavailable
        applyColors(currentSpeed, resolved > 0 ? resolved : null, noOsmData);
    }

    /** Snaps a speed value to the nearest standard speed limit step */
    private int snapToNearestStep(int speed) {
        int nearest = SPEED_STEPS[0];
        int minDiff = Math.abs(speed - nearest);
        for (int step : SPEED_STEPS) {
            int diff = Math.abs(speed - step);
            if (diff < minDiff) { minDiff = diff; nearest = step; }
        }
        return nearest;
    }

    /**
     * Apply colors based on OSM limit.
     * Change3: when noOsmData=true show purple dot instead of going white.
     */
    private void applyColors(int speed, Integer limitKmh, boolean noOsmData) {
        int bgRes, speedColor, limitColor;

        if (limitKmh == null) {
            // No data — keep last background, show purple dot
            bgRes      = R.drawable.overlay_bg_osm_none;
            speedColor = 0xFFFFFFFF;
            limitColor = 0xFFFFD600;
        } else if (speed > limitKmh + 5) {
            bgRes = R.drawable.overlay_bg_osm_speed; speedColor = 0xFFFF5252; limitColor = 0xFF00E5A0;
        } else if (speed > limitKmh) {
            bgRes = R.drawable.overlay_bg_osm_over; speedColor = 0xFFFF9800; limitColor = 0xFF00E5A0;
        } else {
            bgRes = R.drawable.overlay_bg_osm_ok; speedColor = 0xFF00E5A0; limitColor = 0xFF00E5A0;
        }

        overlayView.setBackgroundResource(bgRes);
        tvSpeed.setTextColor(speedColor);
        tvUnit.setTextColor(0xFFFFFFFF);
        tvSpeedLimit.setTextColor(limitColor);

        // Change3: show/hide purple dot
        if (purpleDot != null) {
            purpleDot.setVisibility(noOsmData ? View.VISIBLE : View.GONE);
        }
    }

    private int dpToPx(int dp) { return Math.round(dp * density); }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                *Math.sin(dLon/2)*Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Speed Overlay", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows current driving speed as an overlay");
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Speed Overlay V3")
                .setContentText("OSM speed limits active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi).setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW).build();
    }
}
