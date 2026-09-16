package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;
import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Short user-visible notices from the recording loop.
 *
 * Upstream this was DashcamEventOverlayService: a floating banner window with configurable
 * size, colour and sound. This fork has no overlays, so a notice is a toast plus a line in
 * the runtime log; the recording notification carries the persistent state.
 */
public final class DashcamNotice {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DashcamNotice() {
    }

    /** An event save was armed while the loop was already running. */
    public static void showConfirmation(Context context) {
        show(context, R.string.notice_event_saved, "event saved");
    }

    /** An event save was requested with the loop stopped: only future segments are captured. */
    public static void showFutureOnlyConfirmation(Context context) {
        show(context, R.string.notice_event_future_only, "event future-only");
    }

    public static void showOemPause(Context context) {
        show(context, R.string.notice_oem_pause, "oem pause");
    }

    public static void showOemResume(Context context) {
        show(context, R.string.notice_oem_resume, "oem resume");
    }

    public static void showRecordingRecovered(Context context) {
        show(context, R.string.notice_recording_recovered, "recording recovered");
    }

    /**
     * @param subtitleResId         kept for call-site compatibility with the upstream overlay,
     *                              which showed a subtitle above the message
     * @param notificationTextResId the message actually shown
     */
    public static void showRecordingError(Context context, int subtitleResId, int notificationTextResId) {
        show(context, notificationTextResId, "recording error");
    }

    private static void show(Context context, int messageResId, String logLabel) {
        if (context == null) {
            return;
        }
        DevRuntimeLog.add("DashcamNotice", logLabel);
        Context appContext = context.getApplicationContext();
        MAIN.post(() -> Toast.makeText(appContext, messageResId, Toast.LENGTH_LONG).show());
    }
}
