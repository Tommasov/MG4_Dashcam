package com.drivehub.kamera.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fetches the remote version manifest and decides whether a newer build is available. */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    /** Release channels, each published as its own manifest next to the other. */
    public static final String MANIFEST_STABLE = "version.json";
    public static final String MANIFEST_BETA = "version-beta.json";
    private static final int TIMEOUT_MS = 15_000;

    public interface Callback {
        /** A build newer than the installed one is available. */
        void onUpdateAvailable(@NonNull UpdateInfo info);

        /** The check succeeded and the app is already up to date. */
        void onUpToDate();

        /** The check failed (no network, bad manifest, server error, ...). */
        void onError(@NonNull Exception e);
    }

    private final Context appContext;
    private final String baseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public UpdateChecker(@NonNull Context context, @NonNull String baseUrl) {
        this.appContext = context.getApplicationContext();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    /**
     * Runs the check off the main thread; the callback is always invoked on the main
     * thread. {@code manifestFile} selects the channel.
     */
    public void check(@NonNull String manifestFile, @NonNull Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject(download(baseUrl + manifestFile));
                UpdateInfo info = UpdateInfo.fromJson(json, baseUrl);
                long current = currentVersionCode();
                Log.i(TAG, "current=" + current + " remote=" + info.versionCode);
                if (info.versionCode > current) {
                    mainHandler.post(() -> callback.onUpdateAvailable(info));
                } else {
                    mainHandler.post(callback::onUpToDate);
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    private long currentVersionCode() throws PackageManager.NameNotFoundException {
        PackageInfo pi = appContext.getPackageManager()
                .getPackageInfo(appContext.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.getLongVersionCode();
        }
        //noinspection deprecation
        return pi.versionCode;
    }

    @NonNull
    private static String download(@NonNull String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code + " for " + urlString);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }
}