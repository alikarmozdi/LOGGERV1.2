package com.navlogger.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Keeps the ESP32 "NAV-IMU" module (ICM-45686 + QMC6309) connected over BLE, decodes its packets
 * and logs every sample. All connection steps run one after another on one thread; every failed
 * connection is closed before a new one starts, and a watchdog reconnects if data stops for 3 s.
 */
final class ImuLink {
    static final UUID SERVICE = UUID.fromString("a8b10001-6d3e-4c27-9f3a-1c2d3e4f5a60");
    static final UUID DATA = UUID.fromString("a8b10002-6d3e-4c27-9f3a-1c2d3e4f5a60");
    private static final int SAMPLE_BYTES = 23;
    private static final double GYRO_LSB_PER_DPS = 65.536;   // +-500 dps
    private static final double ACC_LSB_PER_G = 4096.0;      // +-8 g
    private static final double MAG_LSB_PER_GAUSS = 4000.0;  // +-8 Gauss

    private final Context ctx;
    private final CsvLogger log;
    private final String address;
    private HandlerThread thread;
    private volatile Handler h;
    private volatile boolean running = false;

    // connection state, only used on the handler thread
    private BluetoothGatt gatt;
    private boolean streaming = false;
    private boolean discoveryStarted = false;
    private int attempts = 0;
    private volatile long lastPacketMs = 0;

    // decoder state (BLE callback thread, synchronized)
    private int lastSeq = -1;
    private long lastRaw = -1;
    private long wrapBase = 0;
    private long offCur = Long.MAX_VALUE;
    private long offPrev = Long.MAX_VALUE;
    private long blockStartUs = 0;
    private int lastImuOk = -1;
    private int lastMagOk = -1;
    private int statusCount = 0;

    private final Runnable connectRunnable = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };
    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!streaming) failAndRetry("no connection within 15 s (module on? in range?)");
        }
    };
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            onWatchdog();
        }
    };

    ImuLink(Context ctx, CsvLogger log, String address) {
        this.ctx = ctx.getApplicationContext();
        this.log = log;
        this.address = address;
    }

    void start() {
        running = true;
        thread = new HandlerThread("imu-ble");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(connectRunnable);
    }

    void stop() {
        running = false;
        Handler hh = h;
        if (hh != null) {
            hh.removeCallbacksAndMessages(null);
            hh.post(new Runnable() {
                @Override
                public void run() {
                    closeGatt();
                }
            });
        }
        HandlerThread t = thread;
        if (t != null) {
            t.quitSafely();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LiveState.imuState = "stopped";
    }

    private void post(Runnable r) {
        Handler hh = h;
        if (hh != null) hh.post(r);
    }

    private void connect() {
        if (!running) return;
        closeGatt();
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            LiveState.imuState = "Bluetooth is off";
            scheduleReconnect();
            return;
        }
        resetDecoder();
        LiveState.imuState = "connecting";
        try {
            BluetoothDevice d = ad.getRemoteDevice(address);
            gatt = d.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (IllegalArgumentException | SecurityException e) {
            LiveState.event("ICM", "cannot connect: " + e.getMessage());
            gatt = null;
        }
        if (gatt == null) {
            scheduleReconnect();
            return;
        }
        h.postDelayed(timeoutRunnable, 15000);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            post(new Runnable() {
                @Override
                public void run() {
                    onState(g, status, newState);
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, final int mtu, final int status) {
            post(new Runnable() {
                @Override
                public void run() {
                    onMtu(g, mtu);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            post(new Runnable() {
                @Override
                public void run() {
                    onServices(g, status);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            post(new Runnable() {
                @Override
                public void run() {
                    onNotifyEnabled(g, status);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onPacket(value, SystemClock.elapsedRealtimeNanos());
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onPacket(c.getValue(), SystemClock.elapsedRealtimeNanos());
        }
    };

    private void onState(final BluetoothGatt g, int status, int newState) {
        if (g != gatt) {
            try {
                g.close();
            } catch (SecurityException ignored) {
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            LiveState.imuState = "connected, setting up";
            boolean asked = false;
            try {
                asked = g.requestMtu(247);
            } catch (SecurityException ignored) {
            }
            if (!asked) {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        discover(g);
                    }
                }, 300);
            }
        } else {
            failAndRetry((streaming ? "disconnected" : "connect failed") + " (status " + status + ")");
        }
    }

    private void onMtu(final BluetoothGatt g, int mtu) {
        if (g != gatt) return;
        if (mtu < 123) LiveState.event("ICM", "MTU is only " + mtu + "; 123 or more is needed");
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        } catch (SecurityException ignored) {
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                discover(g);
            }
        }, 150);
    }

    private void discover(BluetoothGatt g) {
        if (g != gatt || discoveryStarted) return;
        discoveryStarted = true;
        try {
            if (!g.discoverServices()) failAndRetry("service discovery refused");
        } catch (SecurityException e) {
            failAndRetry("Bluetooth permission missing");
        }
    }

    @SuppressWarnings("deprecation")
    private void onServices(BluetoothGatt g, int status) {
        if (g != gatt) return;
        BluetoothGattService s = status == BluetoothGatt.GATT_SUCCESS ? g.getService(SERVICE) : null;
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(DATA);
        if (c == null) {
            failAndRetry("NAV-IMU service not found (is the new ESP32 firmware flashed?)");
            return;
        }
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(BleSerial.CCCD);
            if (d == null) {
                failAndRetry("notify descriptor missing");
                return;
            }
            boolean ok;
            byte[] val = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = g.writeDescriptor(d, val) == BluetoothStatusCodes.SUCCESS;
            } else {
                d.setValue(val);
                ok = g.writeDescriptor(d);
            }
            if (!ok) failAndRetry("enable-notify refused");
        } catch (SecurityException e) {
            failAndRetry("Bluetooth permission missing");
        }
    }

    private void onNotifyEnabled(BluetoothGatt g, int status) {
        if (g != gatt) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failAndRetry("enable-notify status " + status);
            return;
        }
        streaming = true;
        attempts = 0;
        h.removeCallbacks(timeoutRunnable);
        lastPacketMs = SystemClock.elapsedRealtime();
        LiveState.imuState = "streaming";
        LiveState.event("ICM", "streaming");
        h.postDelayed(watchdogRunnable, 1000);
    }

    private void onWatchdog() {
        if (!running || !streaming) return;
        if (SystemClock.elapsedRealtime() - lastPacketMs > 3000) {
            failAndRetry("no data for 3 s");
            return;
        }
        h.postDelayed(watchdogRunnable, 1000);
    }

    private void failAndRetry(String why) {
        LiveState.event("ICM", why);
        closeGatt();
        scheduleReconnect();
    }

    private void closeGatt() {
        Handler hh = h;
        if (hh != null) {
            hh.removeCallbacks(timeoutRunnable);
            hh.removeCallbacks(watchdogRunnable);
        }
        streaming = false;
        discoveryStarted = false;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (SecurityException ignored) {
            }
            try {
                g.close();
            } catch (SecurityException ignored) {
            }
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        attempts++;
        long delay = Math.min(5000L, 600L * attempts);
        LiveState.imuState = "reconnecting (try " + attempts + ")";
        Handler hh = h;
        if (hh == null) return;
        hh.removeCallbacks(connectRunnable);
        hh.postDelayed(connectRunnable, delay);
    }

    // ------------------------------------------------------------------ decoding

    private synchronized void resetDecoder() {
        lastSeq = -1;
        lastRaw = -1;
        resetTimeBase();
    }

    private void resetTimeBase() {
        wrapBase = 0;
        offCur = Long.MAX_VALUE;
        offPrev = Long.MAX_VALUE;
        blockStartUs = 0;
    }

    private synchronized void onPacket(byte[] v, long rxNs) {
        if (v == null || v.length < 5 || (v[0] & 0xFF) != 0xA5) return;
        lastPacketMs = SystemClock.elapsedRealtime();
        int type = v[1] & 0xFF;
        int seq = (v[2] & 0xFF) | ((v[3] & 0xFF) << 8);
        if (lastSeq >= 0) {
            int gap = (seq - lastSeq - 1) & 0xFFFF;
            if (gap > 0 && gap < 5000) LiveState.imuLostPackets.addAndGet(gap);
        }
        lastSeq = seq;
        ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
        if (type == 0x02) {
            onStatus(b, v.length);
            return;
        }
        int n = v[4] & 0xFF;
        if (type != 0x01 || n == 0 || v.length != 5 + n * SAMPLE_BYTES) return;

        // ESP32 time of each sample in microseconds (32-bit counter, unwrapped)
        long[] tUs = new long[n];
        for (int k = 0; k < n; k++) {
            long raw = b.getInt(5 + k * SAMPLE_BYTES) & 0xFFFFFFFFL;
            if (lastRaw >= 0 && raw < lastRaw) {
                if (lastRaw - raw > 0x80000000L) wrapBase += 0x100000000L;   // counter wrapped
                else resetTimeBase();                                         // module restarted
            }
            lastRaw = raw;
            tUs[k] = wrapBase + raw;
        }
        // phone clock = ESP32 clock + offset; offset = smallest (receive time - sample time) seen in ~10-20 s
        long rxUs = rxNs / 1000L;
        long d = rxUs - tUs[n - 1];
        if (blockStartUs == 0 || rxUs - blockStartUs > 10_000_000L) {
            offPrev = offCur;
            offCur = d;
            blockStartUs = rxUs;
        } else if (d < offCur) {
            offCur = d;
        }
        long off = Math.min(offCur, offPrev);

        double gz = Double.NaN;
        double heading = Double.NaN;
        for (int k = 0; k < n; k++) {
            int o = 5 + k * SAMPLE_BYTES;
            double[] row = CsvLogger.blank();
            row[CsvLogger.GX] = b.getShort(o + 4) / GYRO_LSB_PER_DPS;
            row[CsvLogger.GY] = b.getShort(o + 6) / GYRO_LSB_PER_DPS;
            gz = b.getShort(o + 8) / GYRO_LSB_PER_DPS;
            row[CsvLogger.YAW] = gz;
            row[CsvLogger.AX] = b.getShort(o + 10) / ACC_LSB_PER_G;
            row[CsvLogger.AY] = b.getShort(o + 12) / ACC_LSB_PER_G;
            row[CsvLogger.AZ] = b.getShort(o + 14) / ACC_LSB_PER_G;
            int flags = v[o + 22] & 0xFF;
            boolean magNew = (flags & 0x01) != 0 && (flags & 0x04) != 0;
            if (magNew) {
                double mx = b.getShort(o + 16) / MAG_LSB_PER_GAUSS;
                double my = b.getShort(o + 18) / MAG_LSB_PER_GAUSS;
                double mz = b.getShort(o + 20) / MAG_LSB_PER_GAUSS;
                row[CsvLogger.MX] = mx;
                row[CsvLogger.MY] = my;
                row[CsvLogger.MZ] = mz;
                double hd = Math.toDegrees(Math.atan2(my, mx));
                if (hd < 0) hd += 360.0;
                row[CsvLogger.HEAD] = hd;
                heading = hd;
            }
            row[CsvLogger.ESP] = tUs[k];
            log.add((tUs[k] + off) * 1000L, "IMU", row);
        }
        LiveState.imuCount.addAndGet(n);
        LiveState.imuGz = gz;
        if (!Double.isNaN(heading)) LiveState.imuHeading = heading;
    }

    private void onStatus(ByteBuffer b, int len) {
        if (len < 18) return;
        int imuOk = b.get(5) & 0xFF;
        int magOk = b.get(6) & 0xFF;
        int i2cErr = b.getShort(7) & 0xFFFF;
        int drops = b.getShort(9) & 0xFFFF;
        int bat = b.getShort(11) & 0xFFFF;
        long upS = (b.getInt(13) & 0xFFFFFFFFL) / 1000L;
        int fw = b.get(17) & 0xFF;
        LiveState.imuBatteryMv = bat;
        boolean changed = imuOk != lastImuOk || magOk != lastMagOk;
        lastImuOk = imuOk;
        lastMagOk = magOk;
        if (changed || statusCount % 30 == 0) {
            LiveState.event("ICM", "ESP32 status: gyro/acc " + (imuOk == 1 ? "ok" : "NOT FOUND")
                    + ", mag " + (magOk == 1 ? "ok" : "NOT FOUND") + ", i2cErr " + i2cErr + ", drops " + drops
                    + ", battery " + bat + " mV, up " + upS + " s, fw " + fw);
        }
        statusCount++;
    }
}
