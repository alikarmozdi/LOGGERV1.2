package com.navlogger.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Small helper for remembered settings (selected devices, detected OBD protocol). */
public final class Prefs {
    private Prefs() {}

    public static final String OBD_ADDR = "obd_addr";
    public static final String OBD_NAME = "obd_name";
    public static final String OBD_TYPE = "obd_type";     // "ble" or "classic"
    public static final String OBD_PROTO = "obd_proto";   // ELM327 protocol number that worked last time
    public static final String IMU_ADDR = "imu_addr";
    public static final String IMU_NAME = "imu_name";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("navlogger", Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key, String def) {
        String v = sp(c).getString(key, def);
        return v == null ? def : v;
    }

    public static void put(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }
}
