package com.navlogger.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes one merged CSV. Every row is one event from one source (IMU, GPS or OBD) with its own
 * timestamp; the columns of the other sources are left empty. Rows are held for 2 seconds and
 * written in time order, so the file is sorted by t_s.
 */
public final class CsvLogger {
    public static final String HEADER = "t_s,src,lat,lon,yaw_rate_dps,mag_heading_deg,obd_speed_kmh,"
            + "gps_alt_m,gps_speed_mps,gps_bearing_deg,gps_acc_m,gps_utc_ms,"
            + "gyro_x_dps,gyro_y_dps,acc_x_g,acc_y_g,acc_z_g,mag_x_G,mag_y_G,mag_z_G,imu_esp_us,obd_rtt_ms";

    // numeric column indexes (the columns after t_s and src)
    static final int LAT = 0, LON = 1, YAW = 2, HEAD = 3, OBD = 4, ALT = 5, GSPD = 6, GBRG = 7, GACC = 8,
            GUTC = 9, GX = 10, GY = 11, AX = 12, AY = 13, AZ = 14, MX = 15, MY = 16, MZ = 17, ESP = 18,
            RTT = 19, NCOL = 20;
    private static final int[] DEC = {8, 8, 3, 2, 0, 1, 2, 1, 1, 0, 3, 3, 4, 4, 4, 4, 4, 4, 0, 1};

    private static final long REORDER_NS = 2_000_000_000L;

    private static final class Row {
        final long tNs;
        final long seq;
        final String rest;

        Row(long tNs, long seq, String rest) {
            this.tNs = tNs;
            this.seq = seq;
            this.rest = rest;
        }
    }

    private static final Comparator<Row> ORDER = new Comparator<Row>() {
        @Override
        public int compare(Row a, Row b) {
            if (a.tNs != b.tNs) return a.tNs < b.tNs ? -1 : 1;
            return Long.compare(a.seq, b.seq);
        }
    };

    private final LinkedBlockingQueue<Row> inQ = new LinkedBlockingQueue<>();
    private final PriorityQueue<Row> pq = new PriorityQueue<>(512, ORDER);
    private final AtomicLong seqGen = new AtomicLong();
    private final StringBuilder line = new StringBuilder(256);
    private final Object evLock = new Object();

    private volatile boolean stopping = false;
    private volatile boolean closed = false;
    private long t0Ns;
    private Writer csv;
    private Writer ev;
    private Thread writer;

    /** Empty value row: every column NaN (= empty cell). */
    static double[] blank() {
        double[] v = new double[NCOL];
        Arrays.fill(v, Double.NaN);
        return v;
    }

    /** Called from any thread. tNs is SystemClock.elapsedRealtimeNanos() time of the measurement. */
    void add(long tNs, String src, double[] v) {
        if (closed || stopping) return;
        StringBuilder sb = new StringBuilder(176);
        sb.append(src);
        for (int i = 0; i < NCOL; i++) {
            sb.append(',');
            Num.fixed(sb, v[i], DEC[i]);
        }
        inQ.offer(new Row(tNs, seqGen.getAndIncrement(), sb.toString()));
    }

    /**
     * Opens Download/NavLogger/nav_yyyyMMdd_HHmmss.csv (+ _events.txt).
     * Falls back to the app folder if the Downloads folder cannot be used. Returns where the file is.
     */
    String open(Context ctx, long t0Ns, String meta) throws IOException {
        this.t0Ns = t0Ns;
        String base = "nav_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        OutputStream csvOut = null;
        OutputStream evOut = null;
        String where;
        try {
            csvOut = openDownload(ctx, base + ".csv", "text/csv");
            evOut = openDownload(ctx, base + "_events.txt", "text/plain");
            where = "Download/NavLogger/" + base + ".csv";
        } catch (IOException | RuntimeException e) {
            if (csvOut != null) {
                try {
                    csvOut.close();
                } catch (IOException ignored) {
                }
            }
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, base + ".csv");
            csvOut = new FileOutputStream(f);
            evOut = new FileOutputStream(new File(dir, base + "_events.txt"));
            where = f.getAbsolutePath();
        }
        csv = new BufferedWriter(new OutputStreamWriter(csvOut, StandardCharsets.UTF_8), 1 << 16);
        ev = new BufferedWriter(new OutputStreamWriter(evOut, StandardCharsets.UTF_8), 1 << 12);
        csv.write(HEADER);
        csv.write('\n');
        ev.write(meta);
        ev.write('\n');
        ev.flush();
        csv.flush();
        writer = new Thread(this::runWriter, "csv-writer");
        writer.start();
        return where;
    }

    private static OutputStream openDownload(Context ctx, String name, String mime) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NavLogger");
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("MediaStore insert failed");
        OutputStream os = ctx.getContentResolver().openOutputStream(uri, "w");
        if (os == null) throw new IOException("cannot open " + name);
        return os;
    }

    void writeEvent(String text) {
        synchronized (evLock) {
            if (ev == null) return;
            try {
                ev.write(text);
                ev.write('\n');
            } catch (IOException ignored) {
            }
        }
    }

    private void flushEvents() {
        synchronized (evLock) {
            if (ev == null) return;
            try {
                ev.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private void runWriter() {
        ArrayList<Row> tmp = new ArrayList<>();
        long lastFlush = SystemClock.elapsedRealtime();
        try {
            while (true) {
                Row r = inQ.poll(200, TimeUnit.MILLISECONDS);
                if (r != null) pq.add(r);
                inQ.drainTo(tmp);
                pq.addAll(tmp);
                tmp.clear();
                long horizon = stopping ? Long.MAX_VALUE : SystemClock.elapsedRealtimeNanos() - REORDER_NS;
                while (!pq.isEmpty() && pq.peek().tNs <= horizon) writeRow(pq.poll());
                long now = SystemClock.elapsedRealtime();
                if (now - lastFlush >= 1000) {
                    csv.flush();
                    flushEvents();
                    lastFlush = now;
                }
                if (stopping && inQ.isEmpty() && pq.isEmpty()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LiveState.event("LOG", "write error: " + e.getMessage());
        } finally {
            try {
                csv.flush();
                csv.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void writeRow(Row r) throws IOException {
        line.setLength(0);
        Num.fixed(line, (r.tNs - t0Ns) / 1e9, 6);
        line.append(',').append(r.rest).append('\n');
        csv.write(line.toString());
        LiveState.rowsWritten.incrementAndGet();
    }

    /** Writes everything still buffered and closes both files (blocks up to 10 s). */
    void close() {
        stopping = true;
        Thread w = writer;
        if (w != null) {
            try {
                w.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closed = true;
        synchronized (evLock) {
            if (ev != null) {
                try {
                    ev.close();
                } catch (IOException ignored) {
                }
                ev = null;
            }
        }
    }
}
