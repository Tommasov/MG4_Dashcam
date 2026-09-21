package com.drivehub.kamera;

import com.drivehub.kamera.dashcam.DashcamSettings;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Shows the recorded frame, live.
 *
 * <p>Not a camera viewfinder: what appears here is the composed canvas itself, footer and all,
 * made by the same code that feeds the encoder. That is the whole point - a preview assembled
 * separately would be a picture of what somebody believed the layout to be, and would keep
 * agreeing with that belief after the real one changed.
 *
 * <p>It works whether or not a recording is running. With one, the preview rides on the
 * composer already doing the work, so the frame on screen is the frame going into the file.
 * Without one, a composer starts with no encoder behind it and stops when this screen closes.
 */
public class PreviewActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private SurfaceView surfaceView;
    private TextView hint;
    private boolean surfaceReady;

    /**
     * Recording starting or stopping destroys and replaces the composer, and the window goes with
     * it. Rather than have the native side remember who was watching, this screen re-attaches
     * when it hears the status change.
     */
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (surfaceReady) {
                attach();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        surfaceView = findViewById(R.id.previewSurface);
        hint = findViewById(R.id.tvPreviewHint);
        findViewById(R.id.btnPreviewClose).setOnClickListener(v -> finish());
        surfaceView.getHolder().addCallback(this);
        sizeToCanvas();
    }

    /**
     * Gives the surface the canvas's shape rather than the screen's.
     *
     * <p>The canvas is 3:2 and the head unit is 8:3, so filling the screen would stretch it into
     * something that looks nothing like the recording - which would defeat the purpose of looking
     * at it. The size comes from the native side, so a change to the layout carries over here
     * without anybody remembering to update a number.
     */
    private void sizeToCanvas() {
        final View frame = findViewById(R.id.previewFrame);
        frame.post(() -> {
            int canvasWidth;
            int canvasHeight;
            try {
                canvasWidth = CameraProbe.previewCanvasWidth(
                        RecordingService.CELL_WIDTH, RecordingService.CELL_HEIGHT);
                canvasHeight = CameraProbe.previewCanvasHeight(
                        RecordingService.CELL_WIDTH, RecordingService.CELL_HEIGHT);
            } catch (Throwable t) {
                return;
            }
            if (canvasWidth <= 0 || canvasHeight <= 0
                    || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                return;
            }
            double scale = Math.min((double) frame.getWidth() / canvasWidth,
                    (double) frame.getHeight() / canvasHeight);
            ViewGroup.LayoutParams params = surfaceView.getLayoutParams();
            params.width = (int) Math.round(canvasWidth * scale);
            params.height = (int) Math.round(canvasHeight * scale);
            surfaceView.setLayoutParams(params);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, statusReceiver,
                new IntentFilter(RecordingService.ACTION_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        if (surfaceReady) {
            attach();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered; nothing to undo.
        }
        detach();
    }

    private void attach() {
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        boolean ok;
        try {
            ok = CameraProbe.attachCombinedPreview(
                    surfaceView.getHolder().getSurface(),
                    RecordingService.CELL_WIDTH,
                    RecordingService.CELL_HEIGHT,
                    DashcamSettings.getRecordingFps(prefs),
                    DashcamSettings.getRecordingSignature(prefs),
                    DashcamSettings.shouldShowSpeed(prefs),
                    DashcamSettings.getRecordingCameraMask(prefs));
        } catch (Throwable t) {
            DevRuntimeLog.add("Preview", "attach failed: " + t);
            ok = false;
        }
        DevRuntimeLog.add("Preview", ok ? "attached" : "could not attach");
        // The cameras can be busy - the factory 360 view holds them while it is on screen, and
        // it wins. Saying so beats a black rectangle.
        hint.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void detach() {
        try {
            CameraProbe.detachCombinedPreview();
        } catch (Throwable t) {
            DevRuntimeLog.add("Preview", "detach failed: " + t);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        attach();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The native side sets the buffer geometry itself, so a size change needs no action
        // beyond what attach() already did.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        detach();
    }
}
