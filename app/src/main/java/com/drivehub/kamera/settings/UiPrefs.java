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

    /**
     * Updates.
     *
     * <p>The beta channel is a developer setting rather than an ordinary one on purpose: it is
     * a way to install builds that have not been driven yet, and somebody who found this app on
     * GitHub should have to go looking for it rather than trip over it.
     *
     * <p>The last seen version is remembered so the screen can show a quiet mark without
     * asking the server every time it is opened.
     */
    public static final String KEY_DEV_UPDATE_BETA_CHANNEL = "devUpdateBetaChannel";
    public static final String KEY_DEV_STANDBY_DIAGNOSTICS = "devStandbyDiagnostics";
    public static final String KEY_STATUS_BAR_ICON = "statusBarIcon";
    public static final String KEY_STATUS_BAR_ICON_X = "statusBarIconX";
    public static final String KEY_UPDATE_LAST_CHECK_MS = "updateLastCheckMs";
    public static final String KEY_UPDATE_SEEN_VERSION_CODE = "updateSeenVersionCode";
    public static final String KEY_UPDATE_SEEN_VERSION_NAME = "updateSeenVersionName";

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

    public static boolean isUpdateBetaChannel(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_DEV_UPDATE_BETA_CHANNEL, false);
    }

    /**
     * Whether to watch a shutdown closely and report it afterwards.
     *
     * <p>Off unless somebody asks for it, and that is not a detail. With it on, the app keeps a
     * beat in the journal through the whole standby and sends a report by itself the next time
     * it starts. That is the right tool for working out what the head unit does between locking
     * the car and the processor stopping - and it is also an app that uploads on its own, which
     * this one promises not to be unless the driver says so.
     */
    public static boolean isStandbyDiagnostics(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_DEV_STANDBY_DIAGNOSTICS, false);
    }

    /**
     * Whether to put a dot in the head unit's own status bar.
     *
     * <p>Off by default, and left to the driver rather than decided here. The factory bar has no
     * way of taking an icon from another app, so showing one means drawing over it - which works
     * and looks right, and is also the most conspicuous thing this app does. Somebody who would
     * rather the car looked untouched should not have to switch it off.
     */
    public static boolean isStatusBarIcon(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_STATUS_BAR_ICON, false);
    }

    /**
     * Where along the top the dot sits, 0 hard left to 100 hard right.
     *
     * <p>The middle by default, because of how that bar is laid out. The factory icons fill it
     * <b>from the right towards the middle</b>, so how far left they reach depends on how many
     * of them there are at that moment - connect something and the whole row shifts. Anchoring
     * on the right would put the dot clear of them most of the time and underneath one of them
     * occasionally, which is the worst kind of fault: the sort that only appears when something
     * else is plugged in. The centre is the climate control, fixed, with a space of its own.
     *
     * <p>What is left is the band between the two, and the way to use it follows from which of
     * its two edges moves. The centre block - the strip the climate panel is pulled down from -
     * has a fixed width, so the left edge of the band stays where it is; the icons on the right
     * do not. So the dot goes as close to the centre as it can while still clearing it: every
     * pixel further right is a pixel nearer the edge that shifts.
     *
     * <p>Sixty-two per cent, which is where it was dragged to on the car and left: x=1025 of a
     * 1778-pixel bar, just clear of the climate strip. Every MG4 has the same head unit and the
     * same bar, so this is the right answer everywhere rather than a personal preference, and
     * nobody else should have to find it again.
     *
     * <p>Still a slider rather than a number fixed here. Dragging the dot into the gap takes five seconds; finding the same
     * number by rebuilding takes a drive each time.
     *
     * <p>Sitting over that strip would cost nothing but looks, incidentally: the window is
     * untouchable, so a finger reaching for the climate panel goes straight through it.
     */
    public static int getStatusBarIconX(SharedPreferences prefs) {
        return Math.max(0, Math.min(100, prefs.getInt(KEY_STATUS_BAR_ICON_X, 62)));
    }

    public static void setStatusBarIconX(SharedPreferences prefs, int percent) {
        prefs.edit().putInt(KEY_STATUS_BAR_ICON_X, Math.max(0, Math.min(100, percent))).apply();
    }

    public static void setStatusBarIcon(SharedPreferences prefs, boolean on) {
        prefs.edit().putBoolean(KEY_STATUS_BAR_ICON, on).apply();
    }

    public static void setStandbyDiagnostics(SharedPreferences prefs, boolean on) {
        prefs.edit().putBoolean(KEY_DEV_STANDBY_DIAGNOSTICS, on).apply();
    }

    public static void setUpdateBetaChannel(SharedPreferences prefs, boolean beta) {
        // The remembered version belongs to the other channel now, so it is dropped rather
        // than left to advertise a build this channel may not have.
        prefs.edit()
                .putBoolean(KEY_DEV_UPDATE_BETA_CHANNEL, beta)
                .remove(KEY_UPDATE_SEEN_VERSION_CODE)
                .remove(KEY_UPDATE_SEEN_VERSION_NAME)
                .putLong(KEY_UPDATE_LAST_CHECK_MS, 0L)
                .apply();
    }

    public static long getUpdateLastCheckMs(SharedPreferences prefs) {
        return prefs.getLong(KEY_UPDATE_LAST_CHECK_MS, 0L);
    }

    public static long getUpdateSeenVersionCode(SharedPreferences prefs) {
        return prefs.getLong(KEY_UPDATE_SEEN_VERSION_CODE, 0L);
    }

    public static String getUpdateSeenVersionName(SharedPreferences prefs) {
        return prefs.getString(KEY_UPDATE_SEEN_VERSION_NAME, "");
    }

    /** Records what the last check found, whether or not anything newer turned up. */
    public static void rememberUpdateSeen(SharedPreferences prefs, long versionCode,
                                          String versionName) {
        prefs.edit()
                .putLong(KEY_UPDATE_LAST_CHECK_MS, System.currentTimeMillis())
                .putLong(KEY_UPDATE_SEEN_VERSION_CODE, versionCode)
                .putString(KEY_UPDATE_SEEN_VERSION_NAME, versionName == null ? "" : versionName)
                .apply();
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
