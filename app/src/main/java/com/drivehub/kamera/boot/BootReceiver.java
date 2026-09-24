package com.drivehub.kamera.boot;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.dashcam.DashcamSettings;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Brings the app back, after the car has started and after the app has replaced itself.
 *
 * <p>The two cases want the same thing for different reasons. On boot, recording should resume
 * because the driver left the switch on. After an update, it should resume because installing a
 * package kills the process that was doing it - and the person who pressed install did not ask
 * for the recording to stop, only for the app to become newer.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        final boolean booted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        final boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!booted && !replaced) {
            return;
        }

        if (replaced) {
            android.content.SharedPreferences prefs = UiPrefs.getPrefs(context);
            if (DashcamSettings.consumeStoppedForUpdate(prefs)) {
                // The loop was on until the update asked it to stop. Nobody wanted it off; they
                // wanted a newer app. Putting it back finishes what was started rather than
                // making a decision of its own.
                DevRuntimeLog.add(TAG, "loop was stopped for the update; switching it back on");
                DashcamSettings.setEnabled(prefs, true);
            }
        }

        try {
            RecordingService.startIfDashcamEnabled(context);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start RecordingService", e);
        }

        if (!replaced) {
            return;
        }
        // Coming back on screen is the whole point of handling this. Installing an update makes
        // the app vanish mid-gesture, with no warning and nothing to look at, which reads as a
        // crash rather than as the thing that was just asked for. Reopening it puts the new
        // version in front of the person who asked for it.
        DevRuntimeLog.add(TAG, "package replaced; reopening");
        try {
            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(open);
        } catch (Throwable t) {
            // A head unit that refuses a background activity start is not a failure worth
            // making noise about: the update itself went through, and the launcher still works.
            Log.w(TAG, "could not reopen after the update", t);
        }
    }
}
