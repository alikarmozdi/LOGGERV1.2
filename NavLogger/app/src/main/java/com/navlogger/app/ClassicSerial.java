package com.navlogger.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** ELM327 over classic Bluetooth SPP (for example Vgate "Android-Vlink"; pair it in phone settings first). */
final class ClassicSerial extends SerialTransport {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context ctx;
    private final String address;
    private volatile BluetoothSocket sock;
    private volatile OutputStream out;
    private Thread reader;

    ClassicSerial(Context ctx, String address) {
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
        try {
            ad.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
        BluetoothSocket ok = null;
        for (int mode = 0; mode < 3 && ok == null; mode++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            BluetoothSocket s = null;
            try {
                if (mode == 0) s = dev.createRfcommSocketToServiceRecord(SPP);
                else if (mode == 1) s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                if (s == null) continue;
                sock = s;            // lets close() abort a connect() that is in progress
                s.connect();
                ok = s;
            } catch (Exception e) {
                lastError = "SPP mode " + mode + ": " + e.getMessage();
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                sock = null;
                Thread.sleep(300);
            }
        }
        if (ok == null) return false;
        try {
            out = ok.getOutputStream();
        } catch (IOException e) {
            lastError = e.getMessage();
            close();
            return false;
        }
        connected = true;
        final BluetoothSocket fs = ok;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[512];
                try {
                    InputStream in = fs.getInputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) onBytes(buf, 0, n);
                } catch (IOException ignored) {
                }
                connected = false;
                wakeReaders();
            }
        }, "spp-reader");
        reader.setDaemon(true);
        reader.start();
        return true;
    }

    @Override
    boolean write(String text) {
        OutputStream o = out;
        if (o == null || !connected) return false;
        try {
            o.write(text.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            return true;
        } catch (IOException e) {
            lastError = e.getMessage();
            connected = false;
            wakeReaders();
            return false;
        }
    }

    @Override
    void close() {
        connected = false;
        BluetoothSocket s = sock;
        sock = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        wakeReaders();
    }

    @Override
    String describe() {
        return "classic SPP " + address;
    }
}
