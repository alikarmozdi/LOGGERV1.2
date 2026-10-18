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
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ELM327 over Bluetooth Low Energy (for example Vgate iCar Pro "IOS-Vlink"). */
final class BleSerial extends SerialTransport {
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // {service, notify characteristic, write characteristic} used by known ELM327 BLE adapters.
    // Vgate iCar Pro has shipped with FFE0, FFF0 and 18F0 layouts; unknown layouts are found generically.
    private static final String[][] PROFILES = {
            {"0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb"},
            {"0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"},
            {"000018f0-0000-1000-8000-00805f9b34fb", "00002af0-0000-1000-8000-00805f9b34fb", "00002af1-0000-1000-8000-00805f9b34fb"},
            {"e7810a71-73ae-499d-8c15-faa9aef0c3f2", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"},
            {"6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e"},
    };

    private final Context ctx;
    private final String address;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private volatile int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    private volatile String profile = "";
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ready = false;

    BleSerial(Context ctx, String address) {
        this.ctx = ctx.getApplicationContext();
        this.address = address;
    }

    @Override
    boolean open(long timeoutMs) throws InterruptedException {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            lastError = "Bluetooth is off";
            return false;
        }
        BluetoothDevice dev;
        try {
            dev = ad.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            lastError = "bad address " + address;
            return false;
        }
        readyLatch = new CountDownLatch(1);
        ready = false;
        connected = false;
        try {
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            lastError = "Bluetooth permission missing";
            return false;
        }
        if (gatt == null) {
            lastError = "connectGatt failed";
            return false;
        }
        boolean done = readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done || !ready) {
            if (!done) lastError = "no answer within " + (timeoutMs / 1000) + " s";
            close();
            return false;
        }
        return true;
    }

    private void fail(String reason) {
        lastError = reason;
        readyLatch.countDown();
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true;
                // a short pause before service discovery avoids GATT error 133 on many phones
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        discover(g);
                    }
                }, 400);
            } else {
                connected = false;
                if (!ready) fail("connection status " + status);
                wakeReaders();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("service discovery status " + status);
                return;
            }
            if (!pick(g)) {
                fail("no ELM327 serial service on this device");
                return;
            }
            enableNotify(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("enable-notify status " + status);
                return;
            }
            setReady(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null) onBytes(value, 0, value.length);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) onBytes(v, 0, v.length);
        }
    };

    private void discover(BluetoothGatt g) {
        if (!connected || g != gatt) return;
        try {
            if (!g.discoverServices()) fail("service discovery refused");
        } catch (SecurityException e) {
            fail("Bluetooth permission missing");
        }
    }

    private void setReady(BluetoothGatt g) {
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        } catch (SecurityException ignored) {
        }
        ready = true;
        readyLatch.countDown();
    }

    private static boolean canNotify(BluetoothGattCharacteristic c) {
        int p = c.getProperties();
        return (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
    }

    private static boolean canWrite(BluetoothGattCharacteristic c) {
        int p = c.getProperties();
        return (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    private static boolean isStandardService(String uuid) {
        return uuid.startsWith("00001800") || uuid.startsWith("00001801") || uuid.startsWith("0000180a")
                || uuid.startsWith("0000180f");
    }

    private boolean pick(BluetoothGatt g) {
        BluetoothGattCharacteristic n = null;
        BluetoothGattCharacteristic w = null;
        for (String[] p : PROFILES) {
            BluetoothGattService s = g.getService(UUID.fromString(p[0]));
            if (s == null) continue;
            BluetoothGattCharacteristic cn = s.getCharacteristic(UUID.fromString(p[1]));
            BluetoothGattCharacteristic cw = s.getCharacteristic(UUID.fromString(p[2]));
            if (cn != null && cw != null && canNotify(cn) && canWrite(cw)) {
                n = cn;
                w = cw;
                profile = p[0].substring(4, 8).toUpperCase(java.util.Locale.US);
                break;
            }
        }
        if (n == null) {
            for (BluetoothGattService s : g.getServices()) {
                String u = s.getUuid().toString().toLowerCase(java.util.Locale.US);
                if (isStandardService(u)) continue;
                BluetoothGattCharacteristic cn = null;
                BluetoothGattCharacteristic cw = null;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (cn == null && canNotify(c)) cn = c;
                    if (cw == null && canWrite(c)) cw = c;
                }
                if (cn != null && cw != null) {
                    n = cn;
                    w = cw;
                    profile = u;
                    break;
                }
            }
        }
        if (n == null) return false;
        rx = n;
        tx = w;
        writeType = (w.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        return true;
    }

    @SuppressWarnings("deprecation")
    private void enableNotify(BluetoothGatt g) {
        try {
            g.setCharacteristicNotification(rx, true);
            BluetoothGattDescriptor d = rx.getDescriptor(CCCD);
            if (d == null) {
                setReady(g);
                return;
            }
            byte[] val = (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = g.writeDescriptor(d, val) == BluetoothStatusCodes.SUCCESS;
            } else {
                d.setValue(val);
                ok = g.writeDescriptor(d);
            }
            if (!ok) fail("enable-notify refused");
        } catch (SecurityException e) {
            fail("Bluetooth permission missing");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    boolean write(String text) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = tx;
        if (g == null || c == null || !connected) return false;
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        for (int attempt = 0; attempt < 5; attempt++) {
            boolean ok;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    ok = g.writeCharacteristic(c, data, writeType) == BluetoothStatusCodes.SUCCESS;
                } else {
                    c.setWriteType(writeType);
                    c.setValue(data);
                    ok = g.writeCharacteristic(c);
                }
            } catch (SecurityException e) {
                lastError = "Bluetooth permission missing";
                return false;
            }
            if (ok) return true;
            try {
                Thread.sleep(25);   // previous write still busy; try again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        lastError = "write refused";
        return false;
    }

    @Override
    void close() {
        connected = false;
        main.removeCallbacksAndMessages(null);
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
        readyLatch.countDown();
        wakeReaders();
    }

    @Override
    String describe() {
        return "BLE " + address + " (service " + profile + ")";
    }
}
