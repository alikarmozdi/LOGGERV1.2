package com.navlogger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Foreground service: keeps logging with the screen off. Owns the GPS, ICM and OBD sources and the CSV file. */
public final class LoggerService extends Service {
    public static final String ACTION_START = "com.navlogger.app.START";
    public static final String ACTION_STOP = "com.navlogger.app.STOP";
    private static final String CHANNEL = "navlogger";
    private static final int NOTE_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CsvLogger logger;
    private ObdLink obd;
    private ImuLink imu;
    private GpsSource gps;
    private PowerManager.WakeLock wakeLock;
    private long lastObd, lastImu, lastGps;
    private int tick;
    private boolean stopping = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAll();
        } else if (!LiveState.running && !stopping) {
            startAll();
        }
        return START_NOT_STICKY;
    }

    private void startAll() {
        createChannel();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, buildNotification("starting..."),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTE_ID, buildNotification("starting..."));
            }
        } catch (RuntimeException e) {
            LiveState.event("APP", "cannot start background logging: " + e.getMessage());
            stopSelf();
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NavLogger:logging");
            wakeLock.acquire(12L * 3600L * 1000L);
        }

        LiveState.resetForNewSession();
        long t0 = SystemClock.elapsedRealtimeNanos();
        String obdAddr = Prefs.get(this, Prefs.OBD_ADDR, "");
        String imuAddr = Prefs.get(this, Prefs.IMU_ADDR, "");
        String meta = "NavLogger session\n"
                + "start_local=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date()) + "\n"
                + "start_epoch_ms=" + System.currentTimeMillis() + "\n"
                + "start_elapsed_ns=" + t0 + "   (t_s in the CSV = seconds after this moment)\n"
                + "phone=" + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n"
                + "obd=" + Prefs.get(this, Prefs.OBD_NAME, "-") + " " + obdAddr + " (" + Prefs.get(this, Prefs.OBD_TYPE, "-") + ")\n"
                + "icm=" + Prefs.get(this, Prefs.IMU_NAME, "-") + " " + imuAddr + "\n"
                + "units: yaw_rate/gyro deg/s (ICM z axis), acc g, mag Gauss (raw, uncalibrated), "
                + "mag_heading_deg = atan2(mag_y, mag_x) of the raw field (module assumed level, not calibrated), "
                + "obd_speed km/h, obd_rtt_ms = request/answer time, gps speed m/s\n"
                + "rows: src=IMU (about 100 per s), GPS (about 1 per s), OBD (as fast as the car answers); empty cell = no data\n"
                + "----";
        logger = new CsvLogger();
        try {
            LiveState.fileName = logger.open(this, t0, meta);
        } catch (IOException e) {
            LiveState.event("APP", "cannot create the log file: " + e.getMessage());
            logger = null;
            stopAll();
            return;
        }
        LiveState.setSink(logger);
        LiveState.running = true;
        LiveState.startElapsedMs = SystemClock.elapsedRealtime();
        LiveState.event("APP", "logging to " + LiveState.fileName);

        gps = new GpsSource(this, logger);
        gps.start();

        if (imuAddr.isEmpty()) {
            LiveState.imuState = "no ICM module selected";
        } else {
            imu = new ImuLink(this, logger, imuAddr);
            imu.start();
        }

        if (obdAddr.isEmpty()) {
            LiveState.obdState = "no OBD adapter selected";
        } else {
            // start OBD a little later so the two Bluetooth connections do not start at the same moment
            main.postDelayed(startObd, 1500);
        }
        lastObd = lastImu = lastGps = 0;
        tick = 0;
        main.postDelayed(ticker, 1000);
    }

    private final Runnable startObd = new Runnable() {
        @Override
        public void run() {
            if (!LiveState.running || obd != null || logger == null) return;
            obd = new ObdLink(LoggerService.this, logger, Prefs.get(LoggerService.this, Prefs.OBD_ADDR, ""),
                    Prefs.get(LoggerService.this, Prefs.OBD_TYPE, "ble"));
            obd.start();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!LiveState.running) return;
            long o = LiveState.obdCount.get();
            long i = LiveState.imuCount.get();
            long g = LiveState.gpsCount.get();
            LiveState.obdRateHz = o - lastObd;
            LiveState.imuRateHz = i - lastImu;
            LiveState.gpsRateHz = g - lastGps;
            lastObd = o;
            lastImu = i;
            lastGps = g;
            tick++;
            if (tick % 10 == 0) {
                String s = "ICM " + (long) LiveState.imuRateHz + " Hz, OBD " + (long) LiveState.obdRateHz
                        + " Hz, GPS " + (long) LiveState.gpsRateHz + " Hz, rows " + LiveState.rowsWritten.get();
                LiveState.event("RATE", s);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTE_ID, buildNotification(s));
            }
            main.postDelayed(this, 1000);
        }
    };

    private void stopAll() {
        if (stopping) return;
        stopping = true;
        LiveState.busy = true;
        main.removeCallbacks(startObd);
        main.removeCallbacks(ticker);
        final boolean wasRunning = LiveState.running;
        LiveState.running = false;
        final ObdLink o = obd;
        final ImuLink i = imu;
        final GpsSource g = gps;
        final CsvLogger l = logger;
        obd = null;
        imu = null;
        gps = null;
        logger = null;
        // stopping can take a few seconds (closing Bluetooth links, writing the file) -> not on the UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (o != null) o.stop();
                if (i != null) i.stop();
                if (g != null) g.stop();
                if (wasRunning) LiveState.event("APP", "logging stopped, " + LiveState.rowsWritten.get() + " rows saved");
                LiveState.setSink(null);
                if (l != null) l.close();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishStop();
                    }
                });
            }
        }, "stopper").start();
    }

    private void finishStop() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopping = false;
        LiveState.busy = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (LiveState.running) stopAll();
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Logging", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, LoggerService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build();
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("NavLogger is recording")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(stopAction)
                .build();
    }
}
