package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.settings.UiPrefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * Every dashcam preference, as plain static accessors.
 *
 * Upstream this was DashcamSettingsController, which also owned the settings dialog views.
 * This fork has no dialog, so only the prefs layer survives.
 */
public final class DashcamSettings {

    private static final String TAG = "DashcamSettings";

    public static final String KEY_ENABLED = "enabled";

    /**
     * When the loop was switched off only so that an update could install.
     *
     * <p>Installing replaces the package and kills the process, so the app asks to stop the loop
     * first rather than lose the clip in flight. Which left the switch off afterwards, and the
     * driver having to notice and turn it back on - for a stop nobody asked for. This is the
     * timestamp of that stop; it is honoured once, soon, and only after an update.
     */
    public static final String KEY_STOPPED_FOR_UPDATE_MS = "stoppedForUpdateMs";

    /** Long enough for a download and an install, short enough not to surprise anybody later. */
    private static final long STOPPED_FOR_UPDATE_VALID_MS = 30L * 60L * 1000L;

    public static void rememberStoppedForUpdate(SharedPreferences prefs) {
        prefs.edit().putLong(KEY_STOPPED_FOR_UPDATE_MS, System.currentTimeMillis()).apply();
    }

    public static void forgetStoppedForUpdate(SharedPreferences prefs) {
        prefs.edit().remove(KEY_STOPPED_FOR_UPDATE_MS).apply();
    }

    /**
     * True once, if the loop was stopped for an update that has just happened.
     *
     * <p>Consumed whatever the answer, so a cancelled update cannot leave the loop armed to
     * switch itself on at some unrelated moment weeks later.
     */
    public static boolean consumeStoppedForUpdate(SharedPreferences prefs) {
        long at = prefs.getLong(KEY_STOPPED_FOR_UPDATE_MS, 0L);
        forgetStoppedForUpdate(prefs);
        long age = System.currentTimeMillis() - at;
        return at > 0L && age >= 0L && age < STOPPED_FOR_UPDATE_VALID_MS;
    }

    public static final int DEFAULT_SEGMENT_SEC = 30;
    public static final int DEFAULT_RETENTION_CLIP_COUNT = 10;
    public static final int DEFAULT_MAX_RETAINED_EVENT_DIRS = 5;
    public static final int MIN_RETENTION_CLIP_COUNT = 1;
    public static final int MAX_RETENTION_CLIP_COUNT = 500;
    public static final int MIN_MAX_RETAINED_EVENT_DIRS = 1;
    public static final int MAX_MAX_RETAINED_EVENT_DIRS = 50;

    private static final String KEY_RECORDS_PATH = "recordsPath";
    private static final String KEY_RECORDING_FPS = "recordingFps";
    private static final String KEY_RECORDING_BITRATE_KBPS = "recordingBitrateKbps";
    private static final String KEY_SIGNATURE = "recordingSignature";
    private static final String KEY_SHOW_SPEED = "recordingShowSpeed";
    private static final String KEY_CAMERA_MASK = "recordingCameraMask";
    private static final String KEY_TEST_RECORD_DURATION_SEC = "testRecordDurationSec";
    private static final String KEY_RETENTION_CLIP_COUNT = "devRetentionClipCount";
    private static final String KEY_MAX_RETAINED_EVENT_DIRS = "devMaxRetainedEventDirs";

    public static final int DEFAULT_RECORDING_FPS = 25;
    public static final int MIN_RECORDING_FPS = 1;
    public static final int MAX_RECORDING_FPS = 60;

    /**
     * Video bitrate, in kbit/s. Nine megabits is what upstream hardcoded and what a drive was
     * measured at: about 1.1 MB/s, 58 MB a minute.
     *
     * <p>Worth having a hand on, for two reasons. It is the only lever on how hard the USB stick
     * is being written to, which matters when the same stick is also playing music - a stick that
     * stalls stalls for everything reading it. And it is the thing to change if a future frame
     * layout needs more or fewer bits to look the same.
     *
     * <p>The floor is low enough to be genuinely gentle on a slow stick, the ceiling high enough
     * to stop a typo asking the encoder for something it cannot do.
     */
    public static final int DEFAULT_RECORDING_BITRATE_KBPS = 9000;
    public static final int MIN_RECORDING_BITRATE_KBPS = 1000;
    public static final int MAX_RECORDING_BITRATE_KBPS = 30000;
    private static final int DEFAULT_TEST_RECORD_DURATION_SEC = 30;
    private static final int MIN_TEST_RECORD_DURATION_SEC = 0;
    private static final int MAX_TEST_RECORD_DURATION_SEC = 120;
    private static final int MAX_SIGNATURE_LENGTH = 40;
    private static final String RECORDS_DIR_NAME = "dashcam";

    public static final int CAMERA_MASK_FRONT = 1;
    public static final int CAMERA_MASK_RIGHT = 1 << 1;
    public static final int CAMERA_MASK_LEFT = 1 << 2;
    public static final int CAMERA_MASK_REAR = 1 << 3;
    public static final int DEFAULT_CAMERA_MASK = CAMERA_MASK_FRONT
            | CAMERA_MASK_RIGHT
            | CAMERA_MASK_LEFT
            | CAMERA_MASK_REAR;

    private DashcamSettings() {
    }

    // ---------- Master switch ----------

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(SharedPreferences prefs, boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ---------- Recording ----------

    /** Fixed segment length (30 s); not user-configurable. */
    public static int getSegmentDurationSec() {
        return DEFAULT_SEGMENT_SEC;
    }

    public static int getRecordingFps(SharedPreferences prefs) {
        return clampRecordingFps(prefs.getInt(KEY_RECORDING_FPS, DEFAULT_RECORDING_FPS));
    }

    /** In kbit/s, as the field shows it. */
    public static int getRecordingBitrateKbps(SharedPreferences prefs) {
        return clampRecordingBitrateKbps(
                prefs.getInt(KEY_RECORDING_BITRATE_KBPS, DEFAULT_RECORDING_BITRATE_KBPS));
    }

    /** In bit/s, as the encoder wants it. */
    public static int getRecordingBitrateBps(SharedPreferences prefs) {
        return getRecordingBitrateKbps(prefs) * 1000;
    }

    public static void setRecordingBitrateKbps(SharedPreferences prefs, int kbps) {
        prefs.edit().putInt(KEY_RECORDING_BITRATE_KBPS, clampRecordingBitrateKbps(kbps)).apply();
    }

    public static int clampRecordingBitrateKbps(int kbps) {
        return Math.max(MIN_RECORDING_BITRATE_KBPS,
                Math.min(MAX_RECORDING_BITRATE_KBPS, kbps));
    }

    public static void setRecordingFps(SharedPreferences prefs, int fps) {
        prefs.edit().putInt(KEY_RECORDING_FPS, clampRecordingFps(fps)).apply();
    }

    public static String getRecordingSignature(SharedPreferences prefs) {
        return normalizeSignature(prefs.getString(KEY_SIGNATURE, ""));
    }

    public static void setRecordingSignature(SharedPreferences prefs, String signature) {
        prefs.edit().putString(KEY_SIGNATURE, normalizeSignature(signature)).apply();
    }

    public static boolean shouldShowSpeed(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_SHOW_SPEED, true);
    }

    public static void setShowSpeed(SharedPreferences prefs, boolean showSpeed) {
        prefs.edit().putBoolean(KEY_SHOW_SPEED, showSpeed).apply();
    }

    public static int getRecordingCameraMask(SharedPreferences prefs) {
        return normalizeCameraMask(prefs.getInt(KEY_CAMERA_MASK, DEFAULT_CAMERA_MASK));
    }

    public static void setRecordingCameraMask(SharedPreferences prefs, int cameraMask) {
        prefs.edit().putInt(KEY_CAMERA_MASK, normalizeCameraMask(cameraMask)).apply();
    }

    public static int getRecordingCameraCount(int cameraMask) {
        return Integer.bitCount(normalizeCameraMask(cameraMask));
    }

    public static int getTestRecordDurationSec(SharedPreferences prefs) {
        return clampTestRecordDurationSec(
                prefs.getInt(KEY_TEST_RECORD_DURATION_SEC, DEFAULT_TEST_RECORD_DURATION_SEC));
    }

    // ---------- Retention ----------

    public static int getRetentionClipCount(SharedPreferences prefs) {
        return clampRetentionClipCount(prefs.getInt(KEY_RETENTION_CLIP_COUNT, DEFAULT_RETENTION_CLIP_COUNT));
    }

    public static void setRetentionClipCount(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_RETENTION_CLIP_COUNT, clampRetentionClipCount(count)).apply();
    }

    public static int getMaxRetainedEventDirs(SharedPreferences prefs) {
        return clampMaxRetainedEventDirs(
                prefs.getInt(KEY_MAX_RETAINED_EVENT_DIRS, DEFAULT_MAX_RETAINED_EVENT_DIRS));
    }

    public static void setMaxRetainedEventDirs(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_MAX_RETAINED_EVENT_DIRS, clampMaxRetainedEventDirs(count)).apply();
    }

    public static int clampRetentionClipCount(int count) {
        return Math.max(MIN_RETENTION_CLIP_COUNT, Math.min(MAX_RETENTION_CLIP_COUNT, count));
    }

    public static int clampMaxRetainedEventDirs(int count) {
        return Math.max(MIN_MAX_RETAINED_EVENT_DIRS, Math.min(MAX_MAX_RETAINED_EVENT_DIRS, count));
    }

    // ---------- Storage path ----------

    public static String getConfiguredRecordsPath(SharedPreferences prefs) {
        return normalizeRecordsPath(prefs.getString(KEY_RECORDS_PATH, ""));
    }

    public static void setConfiguredRecordsPath(SharedPreferences prefs, String recordsPath) {
        prefs.edit().putString(KEY_RECORDS_PATH, normalizeRecordsPath(recordsPath)).apply();
    }

    /** Custom path if one is configured, otherwise Downloads/dashcam. */
    public static File getRecordsBaseDir(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        String customPath = getConfiguredRecordsPath(prefs);
        File dir = customPath.isEmpty() ? getDefaultRecordsBaseDir() : new File(customPath);
        if (!dir.mkdirs() && !dir.exists()) {
            Log.w(TAG, "Failed to create records dir: " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static File getDefaultRecordsBaseDir() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                RECORDS_DIR_NAME);
    }

    // ---------- Normalisation ----------

    public static int clampRecordingFps(int fps) {
        return Math.max(MIN_RECORDING_FPS, Math.min(MAX_RECORDING_FPS, fps));
    }

    private static int clampTestRecordDurationSec(int sec) {
        return Math.max(MIN_TEST_RECORD_DURATION_SEC, Math.min(MAX_TEST_RECORD_DURATION_SEC, sec));
    }

    private static int normalizeCameraMask(int cameraMask) {
        int normalized = cameraMask & DEFAULT_CAMERA_MASK;
        return normalized == 0 ? DEFAULT_CAMERA_MASK : normalized;
    }

    private static String normalizeSignature(String value) {
        if (value == null)
            return "";
        String trimmed = value.trim();
        return trimmed.length() <= MAX_SIGNATURE_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_SIGNATURE_LENGTH);
    }

    private static String normalizeRecordsPath(String value) {
        if (value == null)
            return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty())
            return "";
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
