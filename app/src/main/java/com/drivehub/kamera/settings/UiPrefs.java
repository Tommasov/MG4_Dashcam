package com.drivehub.kamera.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Access to the shared preferences file and the handful of non-dashcam settings this fork
 * still has. Dashcam settings themselves live in
 * {@link com.drivehub.kamera.dashcam.DashcamSettings}.
 *
 * The preferences file name is kept as upstream's so an install over the original app keeps
 * its dashcam configuration.
 */
public final class UiPrefs {

    public static final String REC_PREFS_NAME = "rec_prefs";

    public static final String KEY_OEM_AVM_COEXIST = "oemCoexist";
    public static final String KEY_DEV_OEM_AVM_MAX_SPEED_KMH = "devOemAvmMaxSpeedKmh";

    public static final int MIN_DEV_OEM_AVM_MAX_SPEED_KMH = 0;
    public static final int MAX_DEV_OEM_AVM_MAX_SPEED_KMH = 60;
    public static final int DEFAULT_DEV_OEM_AVM_MAX_SPEED_KMH = 20;

    private UiPrefs() {
    }

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(REC_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Whether to yield the camera devices while the factory 360/reverse view is on screen.
     * With this off, the OEM AVM app gets "Device or resource busy" for as long as the
     * dashcam is recording, so the stock reversing camera stops working.
     */
    public static boolean isOemAvmCoexistEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_OEM_AVM_COEXIST, true);
    }

    public static void setOemAvmCoexistEnabled(SharedPreferences prefs, boolean enabled) {
        prefs.edit().putBoolean(KEY_OEM_AVM_COEXIST, enabled).apply();
    }

    public static int getDevOemAvmMaxSpeedKmh(SharedPreferences prefs) {
        return clampOemAvmMaxSpeedKmh(
                prefs.getInt(KEY_DEV_OEM_AVM_MAX_SPEED_KMH, DEFAULT_DEV_OEM_AVM_MAX_SPEED_KMH));
    }

    public static void setDevOemAvmMaxSpeedKmh(SharedPreferences prefs, int speedKmh) {
        prefs.edit()
                .putInt(KEY_DEV_OEM_AVM_MAX_SPEED_KMH, clampOemAvmMaxSpeedKmh(speedKmh))
                .apply();
    }

    private static int clampOemAvmMaxSpeedKmh(int speedKmh) {
        return Math.max(MIN_DEV_OEM_AVM_MAX_SPEED_KMH,
                Math.min(MAX_DEV_OEM_AVM_MAX_SPEED_KMH, speedKmh));
    }
}
