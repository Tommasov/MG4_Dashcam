package com.drivehub.kamera.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.drivehub.kamera.dev.DevRuntimeLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Verifies a downloaded APK and hands it to the system package installer. */
public final class ApkInstaller {

    private static final String TAG = "ApkInstaller";

    private ApkInstaller() {
    }

    /** True once the user has granted this app permission to install packages. */
    public static boolean canInstall(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /**
     * Sends the user to the system screen where they can allow this app to install
     * unknown apps. They return to the launcher afterwards; re-check {@link #canInstall}.
     */
    public static void requestInstallPermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /** Computes the lowercase hex SHA-256 of a file. */
    @NonNull
    public static String sha256(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** True if the file's SHA-256 matches {@code expectedHex} (case-insensitive). */
    public static boolean verify(@NonNull File apk, @NonNull String expectedHex) {
        try {
            String actual = sha256(apk);
            boolean ok = actual.equalsIgnoreCase(expectedHex.trim());
            if (!ok) {
                Log.w(TAG, "checksum mismatch: expected=" + expectedHex + " actual=" + actual);
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "checksum failed", e);
            return false;
        }
    }

    /** Whether the platform signature actually bought us the permission to install. */
    public static boolean hasInstallPermission(@NonNull Context context) {
        return context.checkSelfPermission(android.Manifest.permission.INSTALL_PACKAGES)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Installs through PackageInstaller, which is the only route open to this app.
     *
     * <p>The launcher this code came from hands the file to the system installer with an
     * ACTION_VIEW intent, and that works for an ordinary app. It does not work here, and the
     * reason is the same thing that lets this app open the cameras at all: it declares
     * {@code android:sharedUserId="android.uid.system"}, so it runs as uid 1000 along with half
     * the platform. The system installer attributes an install to the calling package, and with
     * a shared system uid that attribution does not resolve - the install is refused with a
     * message that explains nothing.
     *
     * <p>Measured rather than assumed: the same build with the shared user id removed shows the
     * "allow installing" prompt and installs; on the head unit the prompt never appears, because
     * {@code canRequestPackageInstalls()} answers yes to a system uid before anything is asked.
     *
     * <p>A session needs no such attribution. If {@code INSTALL_PACKAGES} is granted - which a
     * platform signature should grant - it installs outright; if it is not, the session comes
     * back asking for confirmation and {@link InstallResultReceiver} shows it.
     *
     * @return false only if a session could not be created or written at all.
     */
    public static boolean installViaSession(@NonNull Context context, @NonNull File apk) {
        DevRuntimeLog.add(TAG, "INSTALL_PACKAGES granted: " + hasInstallPermission(context));
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            // SessionParams starts life asking for INSTALL_LOCATION_INTERNAL_ONLY, and asking
            // for internal storage outright is a privilege of apps that live in /system. This
            // one runs as the system user but sits in /data/app, so the check calls it a
            // non-system app and refuses the session with
            // "Not allowed to install non-system apps on internal storage" - measured on the
            // car, with INSTALL_PACKAGES granted and everything else in order. Letting the
            // platform choose where it goes is all that was ever needed.
            params.setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            Intent intent = new Intent(context, InstallResultReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            DevRuntimeLog.add(TAG, "install session committed");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "install session failed", t);
            DevRuntimeLog.add(TAG, "install session failed: " + t);
            if (session != null) {
                try {
                    session.abandon();
                } catch (Throwable ignored) {
                    // Nothing useful to do with a session we already failed to use.
                }
            }
            return false;
        }
    }

    /**
     * Launches the system installer for {@code apk}. Caller must have verified the checksum
     * and confirmed {@link #canInstall} first.
     */
    public static void install(@NonNull Context context, @NonNull File apk) {
        Uri apkUri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}