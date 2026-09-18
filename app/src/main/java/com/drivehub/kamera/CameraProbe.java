package com.drivehub.kamera;

public final class CameraProbe {
    static {
        System.loadLibrary("cameraprobe");
    }

    private CameraProbe() {
    }

    /** Probes /dev/video0..maxIndex-1 and returns a human-readable summary. */
    public static native String probeAll(int maxIndex);

    /** Attaches a preview consumer to the given /dev/video index. */
    public static native boolean attachPreview(int videoIndex, android.view.Surface surface);

    /** Detaches the preview consumer from the given /dev/video index. */
    public static native void detachPreview(int videoIndex);

    /**
     * What each camera device reported the last time it was opened: pixel format, size, stride
     * and field order.
     *
     * <p>The field order is the one that matters. The capture path keeps the top half of every
     * frame; whether the other half is a duplicate or the missing scan lines decides whether the
     * recording is at half the vertical resolution it could be.
     */
    public static native String describeCameraFormats();

    /**
     * Shows the composed grid live on a Surface - the same canvas that recording writes.
     *
     * <p>If a recording is running the preview rides on its composer, so what appears is the very
     * frame going into the file. If not, a composer is started with no encoder behind it.
     */
    public static native boolean attachCombinedPreview(android.view.Surface surface,
            int cellWidth, int cellHeight, int fps, String signature, boolean showSpeed,
            int cameraMask);

    public static native boolean detachCombinedPreview();

    /** The composed canvas size, so the preview can be given its shape instead of a stretch. */
    public static native int previewCanvasWidth(int cellWidth, int cellHeight);

    public static native int previewCanvasHeight(int cellWidth, int cellHeight);

    /**
     * Detaches all preview consumers managed by the native camera stream manager.
     */
    public static native void detachAllPreviews();

    /**
     * Starts MP4 recording from a specific /dev/videoX device.
     * slot: 0..3 to allow multiple concurrent recorders.
     */
    public static native boolean startMp4Record(int slot, int videoIndex, String outputPath,
            int width, int height, int fps, int bitrate);

    /** Stops MP4 recording for the given slot. */
    public static native boolean stopMp4Record(int slot);

    /** Starts a grid MP4 recording that combines the selected cameras. */
    public static native boolean startCombinedMp4Record(String outputPath,
            int cellWidth, int cellHeight,
            int fps, int bitrate,
            String signature,
            boolean showSpeed,
            int cameraMask);

    /** Stops the currently running combined grid recording. */
    public static native boolean stopCombinedMp4Record();

    /** Updates the current speed shown in the active combined recording overlay. */
    public static native void updateCombinedRecordingSpeed(int speedKmh);
}
