package com.navlogger.app;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Live values shared between the logging service and the screen (screen reads them once per second). */
public final class LiveState {
    private LiveState() {}

    public static volatile boolean running = false;
    public static volatile boolean busy = false;          // true while stopping and saving
    public static volatile long startElapsedMs = 0;
    public static volatile String fileName = "";
    public static final AtomicLong rowsWritten = new AtomicLong();

    // OBD
    public static volatile String obdState = "idle";
    public static volatile String obdProtocol = "";
    public static volatile int obdSpeed = -1;
    public static volatile double obdRateHz = 0;
    public static final AtomicLong obdCount = new AtomicLong();
    public static final AtomicLong obdErrors = new AtomicLong();

    // ICM module (ESP32)
    public static volatile String imuState = "idle";
    public static volatile double imuRateHz = 0;
    public static final AtomicLong imuCount = new AtomicLong();
    public static volatile double imuGz = Double.NaN;
    public static volatile double imuHeading = Double.NaN;
    public static final AtomicLong imuLostPackets = new AtomicLong();
    public static volatile int imuBatteryMv = 0;

    // GPS
    public static volatile String gpsState = "idle";
    public static volatile double gpsRateHz = 0;
    public static final AtomicLong gpsCount = new AtomicLong();
    public static volatile double gpsLat = Double.NaN;
    public static volatile double gpsLon = Double.NaN;
    public static volatile float gpsAcc = Float.NaN;
    public static volatile int gpsSatsUsed = 0;
    public static volatile int gpsSatsTotal = 0;

    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static volatile CsvLogger sink;

    public static void setSink(CsvLogger s) {
        sink = s;
    }

    /** Adds a line to the on-screen event list and to the _events.txt file. */
    public static void event(String tag, String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " [" + tag + "] " + msg;
        Log.i("NavLogger", line);
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > 14) recent.removeFirst();
        }
        CsvLogger s = sink;
        if (s != null) s.writeEvent(line);
    }

    public static List<String> recentEvents() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public static void resetForNewSession() {
        rowsWritten.set(0);
        obdCount.set(0);
        obdErrors.set(0);
        imuCount.set(0);
        imuLostPackets.set(0);
        gpsCount.set(0);
        obdSpeed = -1;
        obdProtocol = "";
        imuGz = Double.NaN;
        imuHeading = Double.NaN;
        imuBatteryMv = 0;
        gpsLat = Double.NaN;
        gpsLon = Double.NaN;
        gpsAcc = Float.NaN;
        gpsSatsUsed = 0;
        gpsSatsTotal = 0;
        obdRateHz = 0;
        imuRateHz = 0;
        gpsRateHz = 0;
        synchronized (recent) {
            recent.clear();
        }
    }
}
