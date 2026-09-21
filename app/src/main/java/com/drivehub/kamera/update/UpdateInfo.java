package com.drivehub.kamera.update;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/** Parsed contents of the remote {@code version.json} describing the latest release. */
public class UpdateInfo {

    public final long versionCode;
    public final String versionName;
    /** Absolute URL of the APK to download. */
    public final String apkUrl;
    /** Lowercase hex SHA-256 of the APK, used to verify the download before install. */
    public final String sha256;
    public final String changelog;
    /** When true the user should not be allowed to dismiss the update prompt. */
    public final boolean mandatory;

    private UpdateInfo(long versionCode, String versionName, String apkUrl,
                       String sha256, String changelog, boolean mandatory) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.changelog = changelog;
        this.mandatory = mandatory;
    }

    /**
     * Parses the manifest. {@code apkUrl} and {@code sha256} are required; a relative
     * {@code apkUrl} is resolved against {@code baseUrl} so the server can list just a filename.
     *
     * @throws JSONException if a required field is missing or malformed.
     */
    @NonNull
    public static UpdateInfo fromJson(@NonNull JSONObject json, @NonNull String baseUrl)
            throws JSONException {
        long versionCode = json.getLong("versionCode");
        String rawApkUrl = json.getString("apkUrl");
        String sha256 = json.getString("sha256");
        if (TextUtils.isEmpty(rawApkUrl) || TextUtils.isEmpty(sha256)) {
            throw new JSONException("apkUrl and sha256 are required");
        }
        return new UpdateInfo(
                versionCode,
                json.optString("versionName", String.valueOf(versionCode)),
                resolveUrl(baseUrl, rawApkUrl),
                sha256.trim().toLowerCase(),
                json.optString("changelog", ""),
                json.optBoolean("mandatory", false));
    }

    /** Resolves a possibly-relative APK url against the manifest's base url. */
    private static String resolveUrl(@NonNull String baseUrl, @NonNull String apkUrl) {
        Uri uri = Uri.parse(apkUrl);
        if (uri.isAbsolute()) {
            return apkUrl;
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }
        return baseUrl + apkUrl;
    }

    @Nullable
    public String fileName() {
        String last = Uri.parse(apkUrl).getLastPathSegment();
        return TextUtils.isEmpty(last) ? null : last;
    }
}
