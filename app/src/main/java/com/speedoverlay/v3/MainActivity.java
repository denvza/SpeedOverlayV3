package com.speedoverlay.v3;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_SETTINGS = "SpeedOverlaySettings";
    private static final String KEY_API_KEY    = "tomtom_api_key";

    private Button   btnToggle;
    private Button   btnClearOsm;
    private Button   btnClearTomTom;
    private Button   btnShowHideKey;
    private EditText etApiKey;
    private TextView tvStatus;
    private TextView tvOsmCacheInfo;
    private TextView tvTomTomCacheInfo;

    private boolean          serviceRunning = false;
    private OsmCache         osmCache;
    private TomTomSpeedCache tomTomCache;

    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private ActivityResultLauncher<Intent>   overlayPermissionLauncher;
    private ActivityResultLauncher<String>   notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        osmCache    = new OsmCache(this);
        tomTomCache = new TomTomSpeedCache(this);

        btnToggle        = findViewById(R.id.btnToggle);
        btnClearOsm      = findViewById(R.id.btnClearOsm);
        btnClearTomTom   = findViewById(R.id.btnClearTomTom);
        btnShowHideKey   = findViewById(R.id.btnShowHideKey);
        etApiKey         = findViewById(R.id.etApiKey);
        tvStatus         = findViewById(R.id.tvStatus);
        tvOsmCacheInfo   = findViewById(R.id.tvOsmCacheInfo);
        tvTomTomCacheInfo= findViewById(R.id.tvTomTomCacheInfo);

        SharedPreferences prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""));

        setupPermissionLaunchers();

        btnToggle.setOnClickListener(v -> {
            saveApiKey();
            if (serviceRunning) stopOverlayService();
            else checkPermissionsAndStart();
        });

        btnClearOsm.setOnClickListener(v -> {
            osmCache.clearAll();
            updateCacheInfo();
            Toast.makeText(this, "OSM cache cleared!", Toast.LENGTH_SHORT).show();
        });

        btnClearTomTom.setOnClickListener(v -> {
            tomTomCache.clearAll();
            updateCacheInfo();
            Toast.makeText(this, "TomTom cache cleared!", Toast.LENGTH_SHORT).show();
        });

        etApiKey.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) saveApiKey(); });

        btnShowHideKey.setOnClickListener(v -> {
            int type = etApiKey.getInputType();
            if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
                etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                btnShowHideKey.setText("Hide");
            } else {
                etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                btnShowHideKey.setText("Show");
            }
            etApiKey.setSelection(etApiKey.getText().length());
        });

        updateUI();
        updateCacheInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        serviceRunning = isServiceRunning(OverlayService.class);
        updateUI();
        updateCacheInfo();
    }

    private void saveApiKey() {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                .putString(KEY_API_KEY, etApiKey.getText().toString().trim()).apply();
    }

    private void updateCacheInfo() {
        tvOsmCacheInfo.setText("OSM cached segments: " + osmCache.size());
        tvTomTomCacheInfo.setText("TomTom cached segments: " + tomTomCache.size());
    }

    private void checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        requestLocationPermission();
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startOverlayService();
        } else {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void startOverlayService() {
        saveApiKey();
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        serviceRunning = true;
        updateUI();
        Toast.makeText(this, "Speed Overlay V3 started!", Toast.LENGTH_SHORT).show();
    }

    private void stopOverlayService() {
        stopService(new Intent(this, OverlayService.class));
        serviceRunning = false;
        updateUI();
        Toast.makeText(this, "Speed Overlay stopped.", Toast.LENGTH_SHORT).show();
    }

    private void setupPermissionLaunchers() {
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { if (Settings.canDrawOverlays(this)) requestLocationPermission(); });

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> { if (Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION)))
                    startOverlayService(); });

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> requestLocationPermission());
    }

    private void updateUI() {
        if (serviceRunning) {
            btnToggle.setText("Stop Overlay");
            btnToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_red_light));
            tvStatus.setText("Overlay is running.");
        } else {
            btnToggle.setText("Start Overlay");
            btnToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_green_light));
            tvStatus.setText("TomTom API key is optional. OSM works without it.");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
            if (serviceClass.getName().equals(service.service.getClassName())) return true;
        return false;
    }
}
