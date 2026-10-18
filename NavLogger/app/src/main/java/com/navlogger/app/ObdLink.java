package com.navlogger.app;

import android.content.Context;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Keeps an ELM327 OBD adapter connected and reads vehicle speed (PID 010D) as fast as the car answers.
 * Stability measures: one connection attempt at a time with a timeout, every failed link is fully
 * closed before retrying, the ELM327 is re-initialised after repeated bad replies, the working OBD
 * protocol is remembered for the next start, and a watchdog rebuilds the link if no valid speed
 * arrives for 8 seconds.
 */
final class ObdLink {
    private final Context ctx;
    private final CsvLogger log;
    private final String address;
    private final String type;
    private volatile boolean running = false;
    private volatile SerialTransport link;
    private Thread thread;
    private boolean countSuffix = true;

    ObdLink(Context ctx, CsvLogger log, String address, String type) {
        this.ctx = ctx.getApplicationContext();
        this.log = log;
        this.address = address;
        this.type = type;
    }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "obd");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        SerialTransport t = link;
        if (t != null) t.close();
        Thread th = thread;
        if (th != null) {
            th.interrupt();
            try {
                th.join(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LiveState.obdState = "stopped";
    }

    private static void event(String msg) {
        LiveState.event("OBD", msg);
    }

    private void loop() {
        int failures = 0;
        while (running) {
            SerialTransport t = null;
            try {
                LiveState.obdState = "connecting";
                t = "classic".equals(type) ? new ClassicSerial(ctx, address) : new BleSerial(ctx, address);
                link = t;
                if (!t.open(15000)) {
                    failures++;
                    event("connect failed: " + t.lastError() + " (try " + failures + ")");
                    t.close();
                    LiveState.obdState = "retrying";
                    Thread.sleep(Math.min(5000L, 1000L * failures));
                    continue;
                }
                event("link up: " + t.describe());
                if (!initElm(t)) {
                    failures++;
                    t.close();
                    LiveState.obdState = "retrying";
                    Thread.sleep(Math.min(5000L, 1500L * failures));
                    continue;
                }
                failures = 0;
                poll(t);
                t.close();
                LiveState.obdState = "reconnecting";
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            } catch (RuntimeException e) {
                event("error: " + e);
                if (t != null) t.close();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        SerialTransport t = link;
        if (t != null) t.close();
    }

    /** Sends one command and waits for the '>' prompt. Returns null on timeout or lost link. */
    private static String cmd(SerialTransport t, String command, long timeoutMs) throws InterruptedException {
        t.clearInput();
        if (!t.write(command + "\r")) return null;
        return t.readUntilPrompt(timeoutMs);
    }

    static String clean(String r) {
        return r.toUpperCase(Locale.US).replace("SEARCHING...", "").replaceAll("\\s", "");
    }

    private static String oneLine(String r) {
        String s = r.replace('\r', ' ').replace('\n', ' ').trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private boolean initElm(SerialTransport t) throws InterruptedException {
        LiveState.obdState = "init ELM327";
        t.write("\r");
        Thread.sleep(300);
        t.clearInput();
        String r = cmd(t, "ATZ", 5000);
        if (r == null) r = cmd(t, "ATZ", 5000);
        if (r == null) {
            event("ELM327 does not answer ATZ");
            return false;
        }
        event("adapter: " + oneLine(r));
        Thread.sleep(200);
        String[] setup = {"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1"};
        for (String c : setup) {
            if (cmd(t, c, 2000) == null) {
                event(c + ": no answer");
                return false;
            }
        }
        String saved = Prefs.get(ctx, Prefs.OBD_PROTO, "0");
        if (tryProtocol(t, saved, 20000)) return finishInit(t);
        if (!"0".equals(saved)) {
            // a protocol worked before: only retry it and automatic search
            if (tryProtocol(t, "0", 20000)) return finishInit(t);
        } else {
            // first use: also try each protocol explicitly (K-line cars first)
            String[] order = {"5", "4", "3", "6", "8", "7", "9", "1", "2"};
            for (String p : order) {
                if (!running || !t.isConnected()) return false;
                if (tryProtocol(t, p, 9000)) return finishInit(t);
            }
        }
        event("the car ECU does not answer (is the engine/ignition on?)");
        return false;
    }

    private boolean tryProtocol(SerialTransport t, String p, long timeoutMs) throws InterruptedException {
        LiveState.obdState = "searching protocol " + p;
        if (cmd(t, "ATSP" + p, 2000) == null) return false;
        String r = cmd(t, "0100", timeoutMs);
        boolean ok = r != null && clean(r).contains("4100");
        event("protocol " + p + " -> " + (r == null ? "timeout" : oneLine(r)));
        return ok;
    }

    private boolean finishInit(SerialTransport t) throws InterruptedException {
        String dpn = cmd(t, "ATDPN", 2000);
        if (dpn != null) {
            String c = clean(dpn);
            if (c.length() > 0) {
                char ch = c.charAt(c.length() - 1);
                if (Character.isLetterOrDigit(ch) && ch != '0') Prefs.put(ctx, Prefs.OBD_PROTO, String.valueOf(ch));
            }
        }
        String dp = cmd(t, "ATDP", 2000);
        LiveState.obdProtocol = dp == null ? "?" : oneLine(dp);
        // "010D1" = ask for speed and return after the first answer (much faster on most cars)
        String r = cmd(t, "010D1", 3000);
        countSuffix = r != null && clean(r).contains("410D");
        if (!countSuffix) {
            r = cmd(t, "010D", 3000);
            if (r == null || !clean(r).contains("410D")) event("speed PID 0D answer: " + (r == null ? "timeout" : oneLine(r)));
        }
        event("ready, protocol " + LiveState.obdProtocol + (countSuffix ? "" : " (slow mode)"));
        return true;
    }

    static int parseSpeed(String r) {
        String s = clean(r);
        int i = s.indexOf("410D");
        if (i < 0 || i + 6 > s.length()) return -1;
        try {
            return Integer.parseInt(s.substring(i + 4, i + 6), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void poll(SerialTransport t) throws InterruptedException {
        LiveState.obdState = "streaming";
        int timeouts = 0;
        int errors = 0;
        long lastOk = SystemClock.elapsedRealtime();
        String request = countSuffix ? "010D1" : "010D";
        while (running && t.isConnected()) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            String r = cmd(t, request, 1500);
            long t1 = SystemClock.elapsedRealtimeNanos();
            if (r == null) {
                if (!t.isConnected()) {
                    event("link lost");
                    return;
                }
                if (++timeouts >= 3) {
                    event("3 timeouts in a row, rebuilding link");
                    return;
                }
                continue;
            }
            int speed = parseSpeed(r);
            if (speed >= 0) {
                timeouts = 0;
                errors = 0;
                lastOk = SystemClock.elapsedRealtime();
                double[] v = CsvLogger.blank();
                v[CsvLogger.OBD] = speed;
                v[CsvLogger.RTT] = (t1 - t0) / 1e6;
                log.add(t0 + (t1 - t0) / 2, "OBD", v);
                LiveState.obdSpeed = speed;
                LiveState.obdCount.incrementAndGet();
            } else {
                errors++;
                LiveState.obdErrors.incrementAndGet();
                if (errors == 1 || errors % 20 == 0) event("unexpected reply: " + oneLine(r));
                if (errors >= 15) {
                    event("re-initialising ELM327");
                    if (!initElm(t)) return;
                    request = countSuffix ? "010D1" : "010D";
                    LiveState.obdState = "streaming";
                    errors = 0;
                    lastOk = SystemClock.elapsedRealtime();
                } else {
                    Thread.sleep(50);
                }
            }
            if (SystemClock.elapsedRealtime() - lastOk > 8000) {
                event("no valid speed for 8 s, rebuilding link");
                return;
            }
        }
    }
}
