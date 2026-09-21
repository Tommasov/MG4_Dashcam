package com.drivehub.kamera.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.drivehub.kamera.BuildConfig;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import java.io.File;

/**
 * Keeping the app current without sending anybody back to GitHub.
 *
 * <p>Two ways in, deliberately different in how much they are allowed to interrupt.
 *
 * <p>The <b>quiet check</b> runs at most once a day, asks for a few hundred bytes of JSON, and
 * does nothing with the answer but write it down. No dialog, no toast, no notification: the
 * only visible result is a small mark on the main screen. It exists because a mark that only
 * appears after you have gone looking is a mark nobody needs - the point is to be told without
 * being asked to care.
 *
 * <p>The <b>button</b> checks immediately and says what it found either way, including "you are
 * up to date", because a button that sometimes does nothing visible feels broken.
 *
 * <p>Downloading only ever happens after somebody chooses to install. The daily check costs a
 * JSON file; the megabytes are never spent on a guess.
 */
public final class DashcamUpdates {

    private static final String TAG = "Update";

    /**
     * Where the two channel manifests live, next to each other, as in the launcher.
     *
     * <p>A build setting rather than a constant, so a test build can be pointed somewhere else
     * without editing code that then has to be edited back.
     */
    private static final String BASE_URL = BuildConfig.UPDATE_BASE_URL;

    /**
     * How often the quiet check is allowed to run.
     *
     * <p>Releases happen at most a few times a week and the answer is nearly always "nothing
     * new", so anything more often is 4G spent to learn the same thing twice.
     */
    private static final long QUIET_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    public interface Listener {
        void onUpdateAvailable(@NonNull UpdateInfo info);

        void onUpToDate();

        void onError(@NonNull Exception e);
    }

    private DashcamUpdates() {
    }

    private static String manifestFor(@NonNull SharedPreferences prefs) {
        return UiPrefs.isUpdateBetaChannel(prefs)
                ? UpdateChecker.MANIFEST_BETA
                : UpdateChecker.MANIFEST_STABLE;
    }

    /**
     * Whether the last check found something newer than what is running.
     *
     * <p>Answered from what was written down rather than from the network, so the screen can
     * ask on every redraw without costing anything.
     */
    public static boolean hasPendingUpdate(@NonNull Context context,
                                           @NonNull SharedPreferences prefs) {
        return UiPrefs.getUpdateSeenVersionCode(prefs) > currentVersionCode(context);
    }

    @Nullable
    public static String pendingVersionName(@NonNull Context context,
                                            @NonNull SharedPreferences prefs) {
        if (!hasPendingUpdate(context, prefs)) {
            return null;
        }
        String name = UiPrefs.getUpdateSeenVersionName(prefs);
        return name.isEmpty() ? null : name;
    }

    /**
     * The daily check. Silent whatever happens, including failure: no network in an underground
     * car park is the normal case, not something to report.
     */
    public static void checkQuietly(@NonNull Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        long since = System.currentTimeMillis() - UiPrefs.getUpdateLastCheckMs(prefs);
        if (since >= 0 && since < QUIET_CHECK_INTERVAL_MS) {
            return;
        }
        new UpdateChecker(context, BASE_URL).check(manifestFor(prefs), new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                UiPrefs.rememberUpdateSeen(prefs, info.versionCode, info.versionName);
                DevRuntimeLog.add(TAG, "quiet check: " + info.versionName + " available");
            }

            @Override
            public void onUpToDate() {
                UiPrefs.rememberUpdateSeen(prefs, currentVersionCode(context), "");
                DevRuntimeLog.add(TAG, "quiet check: up to date");
            }

            @Override
            public void onError(@NonNull Exception e) {
                // Deliberately not recorded as a failure and deliberately not retried sooner:
                // a car spends most of its life somewhere without a usable connection.
                DevRuntimeLog.add(TAG, "quiet check failed: " + e);
            }
        });
    }

    /** The button. Reports both outcomes, and remembers what it found. */
    public static void checkNow(@NonNull Context context, @NonNull Listener listener) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        new UpdateChecker(context, BASE_URL).check(manifestFor(prefs), new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                UiPrefs.rememberUpdateSeen(prefs, info.versionCode, info.versionName);
                DevRuntimeLog.add(TAG, "found " + info.versionName + " (" + info.versionCode + ")");
                listener.onUpdateAvailable(info);
            }

            @Override
            public void onUpToDate() {
                UiPrefs.rememberUpdateSeen(prefs, currentVersionCode(context), "");
                listener.onUpToDate();
            }

            @Override
            public void onError(@NonNull Exception e) {
                DevRuntimeLog.add(TAG, "check failed: " + e);
                listener.onError(e);
            }
        });
    }

    /**
     * Whether Android will let this app install anything at all.
     *
     * <p>Checked before the download, not after: the launcher this came from asks first and
     * spends the megabytes second, which is the right way round. Getting it backwards meant
     * somebody could wait for a five megabyte download and then be told no.
     */
    public static boolean canInstall(@NonNull Context context) {
        return ApkInstaller.canInstall(context);
    }

    public static void requestInstallPermission(@NonNull Context context) {
        ApkInstaller.requestInstallPermission(context);
    }

    /**
     * Installs a downloaded and checksum-verified APK.
     *
     * <p>Through a PackageInstaller session, because this app shares the system user id and the
     * installer-intent route the launcher uses is closed to it. The intent route stays as a
     * fallback for a build that is not platform-signed, where a session would be refused and
     * the ordinary "allow installing" flow is the right one.
     */
    public static void installVerified(@NonNull Activity activity, @NonNull File apk) {
        if (ApkInstaller.installViaSession(activity, apk)) {
            return;
        }
        DevRuntimeLog.add(TAG, "session refused; trying the system installer");
        if (!ApkInstaller.canInstall(activity)) {
            requestInstallPermission(activity);
            return;
        }
        ApkInstaller.install(activity, apk);
    }

    static long currentVersionCode(@NonNull Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }
}
