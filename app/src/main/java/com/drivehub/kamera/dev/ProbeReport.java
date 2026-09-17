package com.drivehub.kamera.dev;

import com.drivehub.kamera.BuildConfig;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends a diagnostics report to the author's probe, because on this head unit there is nowhere
 * else for it to go.
 *
 * <p>No adb, no browser, and nothing on board that accepts a share — the firmware ships the
 * Bluetooth stack with {@code profile_supported_opp} false, which disables the one activity
 * handling {@code ACTION_SEND}. The clipboard is no help either, since there is no text field
 * to paste into. Writing the log onto the USB stick works but means carrying the stick indoors
 * every time, which is its own kind of tedious.
 *
 * <p>The receiving end accepts a report and does nothing else: it cannot read one back, list
 * what is there or delete anything, with any key. That is what makes it safe to ship the write
 * key inside an APK that anyone can unzip.
 *
 * <p>Deliberately mirrors the launcher's implementation, down to the field names, so one probe
 * serves both apps with one drawer each.
 */
public final class ProbeReport {

    private static final int TIMEOUT_MS = 20_000;

    /** The probe refuses an empty or wrong key with a 403 and no explanation. */
    private static final int HTTP_FORBIDDEN = 403;

    public interface Callback {
        /** Stored, under the name the probe gave it. */
        void onSent(@NonNull String reportName);

        /** Not stored. The reason is meant to be shown: it is the only clue the driver has. */
        void onFailed(@NonNull String reason);
    }

    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ProbeReport() {
    }

    /**
     * Whether this build can send at all. The key lives in the git-ignored
     * {@code apikeys.properties}, so a fresh clone builds without one — and then the button is
     * not offered, rather than offered and failing.
     */
    public static boolean isConfigured() {
        return !BuildConfig.PROBE_KEY.isEmpty() && !BuildConfig.PROBE_URL.isEmpty();
    }

    /** Uploads off the main thread; the callback always lands back on it. */
    public static void send(@NonNull Context context, @NonNull String note,
            @NonNull String body, @NonNull Callback callback) {
        SENDER.execute(() -> {
            try {
                String form = "k=" + encode(BuildConfig.PROBE_KEY)
                        + "&app=" + encode(BuildConfig.PROBE_APP)
                        + "&note=" + encode(note(note))
                        + "&text=" + encode(body);
                String answer = post(BuildConfig.PROBE_URL, form);
                if (answer.startsWith("OK")) {
                    String name = answer.substring(2).trim();
                    MAIN.post(() -> callback.onSent(name));
                } else {
                    String reason = answer.startsWith("ERR") ? answer.substring(3).trim() : answer;
                    MAIN.post(() -> callback.onFailed(reason));
                }
            } catch (Exception e) {
                MAIN.post(() -> callback.onFailed(describe(e)));
            }
        });
    }

    /**
     * One line, so a report can be told apart in the list without opening it: the build and the
     * car, then whatever the driver typed.
     *
     * <p>What they typed is the half that makes the other half readable. A log says what
     * happened; only that sentence says what they were trying to do at the time.
     */
    @NonNull
    private static String note(@NonNull String typed) {
        String head = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") - "
                + Build.MANUFACTURER + " " + Build.MODEL;
        String trimmed = typed.trim();
        return trimmed.isEmpty() ? head : head + " - " + trimmed;
    }

    @NonNull
    private static String post(@NonNull String urlString, @NonNull String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=utf-8");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            if (code == HTTP_FORBIDDEN) {
                throw new IllegalStateException("key refused (HTTP 403)");
            }
            // A rejected report comes back as 400 with an ERR line, so the error stream is
            // worth reading rather than discarding.
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IllegalStateException("HTTP " + code + ", empty answer");
            }
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name()).trim();
            }
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static String encode(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    /** A cause short enough to fit in a dialog on a car screen. */
    @NonNull
    private static String describe(@NonNull Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
