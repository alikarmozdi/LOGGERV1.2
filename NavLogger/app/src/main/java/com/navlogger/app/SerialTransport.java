package com.navlogger.app;

import android.os.SystemClock;

/**
 * Text link to an ELM327 adapter (BLE or classic Bluetooth). Incoming bytes are collected until
 * the ELM327 prompt character '&gt;' arrives.
 */
abstract class SerialTransport {
    private final StringBuilder in = new StringBuilder();
    protected volatile boolean connected = false;
    protected volatile String lastError = "";

    /** Connects and returns true when the link can carry commands. */
    abstract boolean open(long timeoutMs) throws InterruptedException;

    /** Sends text; returns false if the link refused it. */
    abstract boolean write(String text);

    abstract void close();

    abstract String describe();

    boolean isConnected() {
        return connected;
    }

    String lastError() {
        return lastError;
    }

    /** Called by subclasses when bytes arrive (any thread). */
    protected void onBytes(byte[] b, int off, int len) {
        synchronized (in) {
            for (int i = off; i < off + len; i++) {
                int c = b[i] & 0xFF;
                if (c != 0) in.append((char) c);
            }
            if (in.length() > 16384) in.delete(0, in.length() - 4096);
            in.notifyAll();
        }
    }

    protected void wakeReaders() {
        synchronized (in) {
            in.notifyAll();
        }
    }

    void clearInput() {
        synchronized (in) {
            in.setLength(0);
        }
    }

    /** Returns the text received before the next '&gt;' prompt, or null on timeout / disconnect. */
    String readUntilPrompt(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (in) {
            while (true) {
                int p = in.indexOf(">");
                if (p >= 0) {
                    String r = in.substring(0, p);
                    in.delete(0, p + 1);
                    return r;
                }
                if (!connected) return null;
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                in.wait(left);
            }
        }
    }
}
