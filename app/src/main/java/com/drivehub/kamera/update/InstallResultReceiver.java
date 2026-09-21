package com.drivehub.kamera.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import com.drivehub.kamera.dev.DevRuntimeLog;

/**
 * Where a PackageInstaller session reports back.
 *
 * <p>Three outcomes, and the middle one is the one that was missed the first time round.
 *
 * <p><b>Success</b> is usually never seen: the install replaces this process.
 *
 * <p><b>Pending user action</b> means the session was accepted but the system wants the user to
 * confirm, and it hands over an intent to show. Ignoring it looks exactly like nothing
 * happening - the session commits, no error arrives, and the app is not updated. That was the
 * silent install "working" and doing nothing.
 *
 * <p><b>Failure</b> is written down, because on this head unit there is no adb and a status
 * that only reaches logcat reaches nobody.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                DevRuntimeLog.add("Update", "installed");
                return;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                DevRuntimeLog.add("Update", "install needs confirmation"
                        + (confirm == null ? " but no intent came with it" : ""));
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                return;
            default:
                DevRuntimeLog.add("Update", "install failed: status=" + status
                        + (message == null ? "" : " " + message));
        }
    }
}
