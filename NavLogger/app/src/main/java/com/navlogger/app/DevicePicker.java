package com.navlogger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Dialog that lists nearby BLE devices (and, for OBD, paired classic Bluetooth devices). */
final class DevicePicker {
    interface Listener {
        void onPicked(String address, String name, String type);
    }

    private DevicePicker() {}

    private static boolean recommended(String name, boolean forObd) {
        String n = name.toUpperCase(Locale.US);
        if (forObd) return n.contains("VLINK") || n.contains("V-LINK") || n.contains("OBD") || n.contains("ELM") || n.contains("VGATE");
        return n.contains("NAV-IMU");
    }

    static void show(final Activity a, final boolean forObd, final Listener listener) {
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            Toast.makeText(a, "Bluetooth is off", Toast.LENGTH_LONG).show();
            return;
        }
        final List<String[]> items = new ArrayList<>();      // {address, name, type}
        final List<String> labels = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_list_item_1, labels);

        if (forObd) {
            try {
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        String name = d.getName();
                        if (name == null) name = "(no name)";
                        items.add(new String[]{d.getAddress(), name, "classic"});
                        labels.add((recommended(name, true) ? "* " : "") + name + "\nclassic Bluetooth (paired)  " + d.getAddress());
                    }
                }
            } catch (SecurityException ignored) {
            }
        }

        final ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, final ScanResult r) {
                final String addr = r.getDevice().getAddress();
                String name = null;
                ScanRecord rec = r.getScanRecord();
                if (rec != null) name = rec.getDeviceName();
                if (name == null) {
                    try {
                        name = r.getDevice().getName();
                    } catch (SecurityException ignored) {
                    }
                }
                if (name == null || name.trim().isEmpty()) return;    // skip nameless devices
                final String fname = name.trim();
                final int rssi = r.getRssi();
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String label = (recommended(fname, forObd) ? "* " : "") + fname
                                + "\nBLE  " + addr + "   signal " + rssi + " dBm";
                        for (int i = 0; i < items.size(); i++) {
                            String[] it = items.get(i);
                            if (it[0].equals(addr) && it[2].equals("ble")) {
                                labels.set(i, label);
                                adapter.notifyDataSetChanged();
                                return;
                            }
                        }
                        items.add(new String[]{addr, fname, "ble"});
                        labels.add(label);
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        };

        final BluetoothLeScanner scanner = ad.getBluetoothLeScanner();
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable stopScan = new Runnable() {
            @Override
            public void run() {
                if (scanner == null) return;
                try {
                    scanner.stopScan(cb);
                } catch (SecurityException | IllegalStateException ignored) {
                }
            }
        };

        String title = forObd ? "Choose the OBD adapter (BLE: IOS-Vlink, classic: Android-Vlink)" : "Choose the ICM module (NAV-IMU)";
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(title)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which >= 0 && which < items.size()) {
                            String[] it = items.get(which);
                            listener.onPicked(it[0], it[1], it[2]);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                h.removeCallbacks(stopScan);
                stopScan.run();
            }
        });
        dlg.show();

        if (scanner == null) {
            Toast.makeText(a, "BLE scanner not available", Toast.LENGTH_LONG).show();
            return;
        }
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, cb);
            h.postDelayed(stopScan, 15000);
        } catch (SecurityException e) {
            Toast.makeText(a, "Bluetooth permission missing", Toast.LENGTH_LONG).show();
        }
    }
}
