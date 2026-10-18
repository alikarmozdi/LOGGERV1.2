package com.navlogger.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main screen: choose devices, start/stop recording, live values refreshed once per second. */
public final class MainActivity extends Activity {
    private TextView obdDev;
    private TextView imuDev;
    private TextView obdLine;
    private TextView imuLine;
    private TextView gpsLine;
    private TextView logLine;
    private TextView events;
    private Button startBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        root.addView(label("NavLogger", 22, true));

        root.addView(section("OBD adapter (Vgate) — آداپتور OBD"));
        obdDev = label("", 14, false);
        root.addView(obdDev);
        root.addView(button("انتخاب آداپتور OBD", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(true);
            }
        }));
        obdLine = mono();
        root.addView(obdLine);

        root.addView(section("ICM module (ESP32) — ماژول ICM"));
        imuDev = label("", 14, false);
        root.addView(imuDev);
        root.addView(button("انتخاب ماژول ICM", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(false);
            }
        }));
        imuLine = mono();
        root.addView(imuLine);

        root.addView(section("Phone GPS — GPS گوشی"));
        gpsLine = mono();
        root.addView(gpsLine);

        root.addView(section("Recording — ثبت"));
        startBtn = button("شروع ثبت", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        root.addView(startBtn);
        logLine = mono();
        root.addView(logLine);
        root.addView(button("تنظیمات باتری برنامه (Unrestricted)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSettings();
            }
        }));

        root.addView(section("Events — رویدادها"));
        events = mono();
        events.setTextSize(11);
        root.addView(events);

        setContentView(sv);
        showDevices();
        if (!hasCorePermissions()) requestPermissions(neededPermissions(), 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(refresher);
        h.post(refresher);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(refresher);
        super.onPause();
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(String s, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(String s) {
        TextView t = label(s, 16, true);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView mono() {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ permissions and checks

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCorePermissions() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false;
        if (Build.VERSION.SDK_INT >= 31) {
            return granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!hasCorePermissions()) {
            toast("Location (precise) and Nearby devices permissions are needed. Allow them in app settings.");
        }
    }

    /** True when permissions, Bluetooth and Location are all ready; otherwise asks the user and returns false. */
    private boolean ensureReady() {
        if (!hasCorePermissions()) {
            requestPermissions(neededPermissions(), 1);
            return false;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) {
            toast("This phone has no Bluetooth");
            return false;
        }
        if (!ad.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (SecurityException | ActivityNotFoundException e) {
                toast("Please turn Bluetooth on");
            }
            return false;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && !lm.isLocationEnabled()) {
            toast("Please turn Location (GPS) on");
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
            }
            return false;
        }
        return true;
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            toast("Battery > Unrestricted");
        } catch (ActivityNotFoundException e) {
            toast("Settings > Apps > NavLogger > Battery > Unrestricted");
        }
    }

    // ------------------------------------------------------------------ actions

    private void pick(final boolean forObd) {
        if (LiveState.running) {
            toast("Stop recording first");
            return;
        }
        if (!ensureReady()) return;
        DevicePicker.show(this, forObd, new DevicePicker.Listener() {
            @Override
            public void onPicked(String address, String name, String type) {
                if (forObd) {
                    Prefs.put(MainActivity.this, Prefs.OBD_ADDR, address);
                    Prefs.put(MainActivity.this, Prefs.OBD_NAME, name);
                    Prefs.put(MainActivity.this, Prefs.OBD_TYPE, type);
                } else {
                    Prefs.put(MainActivity.this, Prefs.IMU_ADDR, address);
                    Prefs.put(MainActivity.this, Prefs.IMU_NAME, name);
                }
                showDevices();
            }
        });
    }

    private void toggle() {
        if (LiveState.busy) return;
        if (LiveState.running) {
            startService(new Intent(this, LoggerService.class).setAction(LoggerService.ACTION_STOP));
            return;
        }
        if (!ensureReady()) return;
        if (Prefs.get(this, Prefs.OBD_ADDR, "").isEmpty() && Prefs.get(this, Prefs.IMU_ADDR, "").isEmpty()) {
            toast("No OBD adapter or ICM module selected: only GPS will be recorded");
        }
        startForegroundService(new Intent(this, LoggerService.class).setAction(LoggerService.ACTION_START));
    }

    private void showDevices() {
        String oa = Prefs.get(this, Prefs.OBD_ADDR, "");
        String ot = Prefs.get(this, Prefs.OBD_TYPE, "ble");
        obdDev.setText(oa.isEmpty() ? "No adapter selected"
                : Prefs.get(this, Prefs.OBD_NAME, "") + "   (" + ("classic".equals(ot) ? "classic Bluetooth" : "BLE") + ")   " + oa);
        String ia = Prefs.get(this, Prefs.IMU_ADDR, "");
        imuDev.setText(ia.isEmpty() ? "No module selected" : Prefs.get(this, Prefs.IMU_NAME, "") + "   (BLE)   " + ia);
    }

    private void refresh() {
        boolean run = LiveState.running;
        boolean busy = LiveState.busy;
        startBtn.setEnabled(!busy);
        startBtn.setText(busy ? "در حال ذخیره فایل..." : run ? "توقف ثبت" : "شروع ثبت");

        obdLine.setText(String.format(Locale.US, "%s\nrate %.0f Hz | speed %s km/h | bad replies %d\n%s",
                LiveState.obdState, LiveState.obdRateHz,
                LiveState.obdSpeed < 0 ? "-" : String.valueOf(LiveState.obdSpeed),
                LiveState.obdErrors.get(), LiveState.obdProtocol));
        imuLine.setText(String.format(Locale.US, "%s\nrate %.0f Hz | yaw rate %s deg/s | heading %s deg\nlost packets %d | battery %s V",
                LiveState.imuState, LiveState.imuRateHz, Num.fixed(LiveState.imuGz, 2), Num.fixed(LiveState.imuHeading, 1),
                LiveState.imuLostPackets.get(),
                LiveState.imuBatteryMv > 0 ? Num.fixed(LiveState.imuBatteryMv / 1000.0, 2) : "-"));
        gpsLine.setText(String.format(Locale.US, "%s\nrate %.0f Hz | satellites %d/%d | accuracy %s m\n%s, %s",
                LiveState.gpsState, LiveState.gpsRateHz, LiveState.gpsSatsUsed, LiveState.gpsSatsTotal,
                Num.fixed(LiveState.gpsAcc, 1), Num.fixed(LiveState.gpsLat, 7), Num.fixed(LiveState.gpsLon, 7)));
        if (run) {
            long s = (SystemClock.elapsedRealtime() - LiveState.startElapsedMs) / 1000L;
            logLine.setText(String.format(Locale.US, "%s\nrows %d | time %02d:%02d:%02d",
                    LiveState.fileName, LiveState.rowsWritten.get(), s / 3600, (s / 60) % 60, s % 60));
        } else {
            logLine.setText(LiveState.fileName.isEmpty() ? "not recording"
                    : "last file: " + LiveState.fileName + "\nrows " + LiveState.rowsWritten.get());
        }
        StringBuilder sb = new StringBuilder();
        for (String e : LiveState.recentEvents()) sb.append(e).append('\n');
        events.setText(sb);
    }
}
