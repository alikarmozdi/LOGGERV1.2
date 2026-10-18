package com.navlogger.app;

/** Locale-independent fixed-point number formatting (always ASCII digits and '.'). */
final class Num {
    private Num() {}

    private static final long[] POW10 = {
            1L, 10L, 100L, 1000L, 10000L, 100000L, 1000000L, 10000000L, 100000000L, 1000000000L
    };

    /** Appends v with exactly dec decimals. NaN/Infinity append nothing (empty CSV cell). */
    static void fixed(StringBuilder sb, double v, int dec) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        long scale = POW10[dec];
        long r = Math.round(Math.abs(v) * scale);
        if (v < 0 && r != 0) sb.append('-');
        sb.append(r / scale);
        if (dec > 0) {
            sb.append('.');
            String f = Long.toString(r % scale);
            for (int i = f.length(); i < dec; i++) sb.append('0');
            sb.append(f);
        }
    }

    static String fixed(double v, int dec) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "-";
        StringBuilder sb = new StringBuilder(24);
        fixed(sb, v, dec);
        return sb.toString();
    }
}
