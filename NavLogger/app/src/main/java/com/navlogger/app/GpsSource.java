package com.navlogger.app;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

/** Phone GNSS receiver (pure GPS provider, every fix the chip delivers, usually 1 Hz). */
final class GpsSource implements LocationListener {
    private final Context ctx;
    private final CsvLogger log;
    private LocationManager lm;
    private HandlerThread thread;
    private GnssStatus.Callback gnssCallback;

    GpsSource(Context ctx, CsvLogger log) {
        this.ctx = ctx.getApplicationContext();
        this.log = log;
    }

    void start() {
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            LiveState.gpsState = "no location service";
            return;
        }
        thread = new HandlerThread("gps");
        thread.start();
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LiveState.gpsState = "Location is OFF in phone settings";
            LiveState.event("GPS", "Location is turned off in phone settings");
        } else {
            LiveState.gpsState = "waiting for fix";
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, thread.getLooper());
            gnssCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0;
                    int n = status.getSatelliteCount();
                    for (int i = 0; i < n; i++) if (status.usedInFix(i)) used++;
                    LiveState.gpsSatsUsed = used;
                    LiveState.gpsSatsTotal = n;
                }
            };
            lm.registerGnssStatusCallback(gnssCallback, new Handler(thread.getLooper()));
        } catch (SecurityException e) {
            LiveState.gpsState = "location permission missing";
            LiveState.event("GPS", "location permission missing");
        } catch (IllegalArgumentException e) {
            LiveState.gpsState = "no GPS provider";
            LiveState.event("GPS", "this phone has no GPS provider");
        }
    }

    void stop() {
        if (lm != null) {
            try {
                lm.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
            if (gnssCallback != null) lm.unregisterGnssStatusCallback(gnssCallback);
        }
        if (thread != null) thread.quitSafely();
        LiveState.gpsState = "stopped";
    }

    @Override
    public void onLocationChanged(Location l) {
        double[] v = CsvLogger.blank();
        v[CsvLogger.LAT] = l.getLatitude();
        v[CsvLogger.LON] = l.getLongitude();
        if (l.hasAltitude()) v[CsvLogger.ALT] = l.getAltitude();
        if (l.hasSpeed()) v[CsvLogger.GSPD] = l.getSpeed();
        if (l.hasBearing()) v[CsvLogger.GBRG] = l.getBearing();
        if (l.hasAccuracy()) v[CsvLogger.GACC] = l.getAccuracy();
        v[CsvLogger.GUTC] = l.getTime();
        log.add(l.getElapsedRealtimeNanos(), "GPS", v);
        LiveState.gpsLat = l.getLatitude();
        LiveState.gpsLon = l.getLongitude();
        LiveState.gpsAcc = l.hasAccuracy() ? l.getAccuracy() : Float.NaN;
        LiveState.gpsState = "fix";
        LiveState.gpsCount.incrementAndGet();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        LiveState.gpsState = "waiting for fix";
        LiveState.event("GPS", "location turned on");
    }

    @Override
    public void onProviderDisabled(String provider) {
        LiveState.gpsState = "Location is OFF in phone settings";
        LiveState.event("GPS", "location turned off");
    }
}
