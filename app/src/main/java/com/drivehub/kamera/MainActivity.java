package com.drivehub.kamera;

import com.drivehub.kamera.dashcam.DashcamSettings;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.dev.OemCaptures;
import com.drivehub.kamera.settings.UiPrefs;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole user interface: a switch that arms the loop, the recording parameters, and where
 * the clips go. Everything else the upstream app put on screen is gone.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 1337;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private SwitchCompat swEnabled;
    private TextView tvStatus;
    private TextView tvStatusDetail;
    private TextView tvStorageStatus;
    private RadioGroup rgStorageTarget;
    private EditText etRecordsPath;
    private EditText etRetentionInternal;
    private EditText etRetentionUsb;
    private EditText etMaxEventDirs;
    private EditText etFps;
    private EditText etSignature;
    private SwitchCompat swShowSpeed;
    private SwitchCompat swOemCoexist;
    private CheckBox cbFront;
    private CheckBox cbRear;
    private CheckBox cbLeft;
    private CheckBox cbRight;

    private boolean syncing = false;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    private final BroadcastReceiver ejectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int messageRes = intent.getIntExtra(
                    RecordingService.EXTRA_USB_EJECT_MESSAGE_RES,
                    R.string.settings_dashcam_storage_eject_unavailable_message);
            Toast.makeText(MainActivity.this, messageRes, Toast.LENGTH_LONG).show();
            syncing = true;
            swEnabled.setChecked(DashcamSettings.isEnabled(prefs()));
            syncing = false;
            refreshStatus();
            refreshStorageStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnabled = findViewById(R.id.swEnabled);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusDetail = findViewById(R.id.tvStatusDetail);
        tvStorageStatus = findViewById(R.id.tvStorageStatus);
        rgStorageTarget = findViewById(R.id.rgStorageTarget);
        etRecordsPath = findViewById(R.id.etRecordsPath);
        etRetentionInternal = findViewById(R.id.etRetentionInternal);
        etRetentionUsb = findViewById(R.id.etRetentionUsb);
        etMaxEventDirs = findViewById(R.id.etMaxEventDirs);
        etFps = findViewById(R.id.etFps);
        etSignature = findViewById(R.id.etSignature);
        swShowSpeed = findViewById(R.id.swShowSpeed);
        swOemCoexist = findViewById(R.id.swOemCoexist);
        cbFront = findViewById(R.id.cbFront);
        cbRear = findViewById(R.id.cbRear);
        cbLeft = findViewById(R.id.cbLeft);
        cbRight = findViewById(R.id.cbRight);

        findViewById(R.id.btnTestClip).setOnClickListener(v -> {
            SharedPreferences prefs = prefs();
            RecordingService.startTestClip(this, DashcamSettings.getTestRecordDurationSec(prefs));
        });

        // Stops the loop and waits for the pending writes so the stick can be pulled without
        // truncating the clip that was in flight.
        findViewById(R.id.btnEjectUsb).setOnClickListener(v -> {
            DashcamSettings.setEnabled(prefs(), false);
            syncing = true;
            swEnabled.setChecked(false);
            syncing = false;
            RecordingService.requestUsbEject(this);
        });

        findViewById(R.id.btnStorageDetails).setOnClickListener(v -> showStorageDetails());
        findViewById(R.id.btnCopyOemCaptures).setOnClickListener(v -> copyOemCaptures());
        findViewById(R.id.btnDeleteOemCaptures).setOnClickListener(v -> confirmDeleteOemCaptures());

        bind();
        ensureStoragePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(
                this,
                statusReceiver,
                new IntentFilter(RecordingService.ACTION_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(
                this,
                ejectReceiver,
                new IntentFilter(RecordingService.ACTION_USB_EJECT_READY),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        RecordingService.resetPersistedStatusIfStale(prefs());
        refreshStatus();
        refreshStorageStatus();
    }

    @Override
    protected void onPause() {
        for (BroadcastReceiver receiver : new BroadcastReceiver[] { statusReceiver, ejectReceiver }) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // not registered
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---------- Binding ----------

    private void bind() {
        SharedPreferences prefs = prefs();
        syncing = true;

        swEnabled.setChecked(DashcamSettings.isEnabled(prefs));
        swShowSpeed.setChecked(DashcamSettings.shouldShowSpeed(prefs));
        swOemCoexist.setChecked(UiPrefs.isOemAvmCoexistEnabled(prefs));

        int mask = DashcamSettings.getRecordingCameraMask(prefs);
        cbFront.setChecked((mask & DashcamSettings.CAMERA_MASK_FRONT) != 0);
        cbRear.setChecked((mask & DashcamSettings.CAMERA_MASK_REAR) != 0);
        cbLeft.setChecked((mask & DashcamSettings.CAMERA_MASK_LEFT) != 0);
        cbRight.setChecked((mask & DashcamSettings.CAMERA_MASK_RIGHT) != 0);

        rgStorageTarget.check(radioIdForTarget(DashcamStorageManager.getStorageTarget(prefs)));

        etRecordsPath.setText(DashcamSettings.getConfiguredRecordsPath(prefs));
        etRecordsPath.setHint(DashcamSettings.getDefaultRecordsBaseDir().getAbsolutePath());
        etRetentionInternal.setText(String.valueOf(DashcamSettings.getRetentionClipCount(prefs)));
        etRetentionUsb.setText(String.valueOf(DashcamStorageManager.getUsbRetentionClipCount(prefs)));
        etMaxEventDirs.setText(String.valueOf(DashcamSettings.getMaxRetainedEventDirs(prefs)));
        etFps.setText(String.valueOf(DashcamSettings.getRecordingFps(prefs)));
        etSignature.setText(DashcamSettings.getRecordingSignature(prefs));

        syncing = false;

        swEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (syncing) return;
            DashcamSettings.setEnabled(prefs(), checked);
            if (checked) {
                RecordingService.startIfDashcamEnabled(this);
            } else {
                RecordingService.stopIfRunning(this);
            }
            refreshStatus();
        });
        swShowSpeed.setOnCheckedChangeListener((v, checked) -> {
            if (!syncing) DashcamSettings.setShowSpeed(prefs(), checked);
        });
        swOemCoexist.setOnCheckedChangeListener((v, checked) -> {
            if (!syncing) UiPrefs.setOemAvmCoexistEnabled(prefs(), checked);
        });

        CheckBox[] cameraBoxes = { cbFront, cbRear, cbLeft, cbRight };
        for (CheckBox box : cameraBoxes) {
            box.setOnCheckedChangeListener((v, checked) -> {
                if (syncing) return;
                saveCameraMask();
            });
        }

        rgStorageTarget.setOnCheckedChangeListener((group, checkedId) -> {
            if (syncing) return;
            DashcamStorageManager.setStorageTarget(prefs(), targetForRadioId(checkedId));
            refreshStorageStatus();
        });

        onBlur(etRecordsPath, () -> {
            DashcamSettings.setConfiguredRecordsPath(prefs(), etRecordsPath.getText().toString());
            refreshStorageStatus();
        });
        onBlur(etRetentionInternal, () -> {
            DashcamSettings.setRetentionClipCount(prefs(), readInt(etRetentionInternal,
                    DashcamSettings.DEFAULT_RETENTION_CLIP_COUNT));
            etRetentionInternal.setText(String.valueOf(DashcamSettings.getRetentionClipCount(prefs())));
        });
        onBlur(etRetentionUsb, () -> {
            DashcamStorageManager.setUsbRetentionClipCount(prefs(), readInt(etRetentionUsb,
                    DashcamStorageManager.DEFAULT_USB_RETENTION_CLIP_COUNT));
            etRetentionUsb.setText(String.valueOf(DashcamStorageManager.getUsbRetentionClipCount(prefs())));
        });
        onBlur(etMaxEventDirs, () -> {
            DashcamSettings.setMaxRetainedEventDirs(prefs(), readInt(etMaxEventDirs,
                    DashcamSettings.DEFAULT_MAX_RETAINED_EVENT_DIRS));
            etMaxEventDirs.setText(String.valueOf(DashcamSettings.getMaxRetainedEventDirs(prefs())));
        });
        onBlur(etFps, () -> {
            DashcamSettings.setRecordingFps(prefs(), readInt(etFps, DashcamSettings.DEFAULT_RECORDING_FPS));
            etFps.setText(String.valueOf(DashcamSettings.getRecordingFps(prefs())));
        });
        onBlur(etSignature, () -> {
            DashcamSettings.setRecordingSignature(prefs(), etSignature.getText().toString());
            etSignature.setText(DashcamSettings.getRecordingSignature(prefs()));
        });
    }

    private void saveCameraMask() {
        int mask = 0;
        if (cbFront.isChecked()) mask |= DashcamSettings.CAMERA_MASK_FRONT;
        if (cbRear.isChecked()) mask |= DashcamSettings.CAMERA_MASK_REAR;
        if (cbLeft.isChecked()) mask |= DashcamSettings.CAMERA_MASK_LEFT;
        if (cbRight.isChecked()) mask |= DashcamSettings.CAMERA_MASK_RIGHT;
        DashcamSettings.setRecordingCameraMask(prefs(), mask);
        // An empty selection falls back to all four, so mirror whatever was actually stored.
        syncing = true;
        int stored = DashcamSettings.getRecordingCameraMask(prefs());
        cbFront.setChecked((stored & DashcamSettings.CAMERA_MASK_FRONT) != 0);
        cbRear.setChecked((stored & DashcamSettings.CAMERA_MASK_REAR) != 0);
        cbLeft.setChecked((stored & DashcamSettings.CAMERA_MASK_LEFT) != 0);
        cbRight.setChecked((stored & DashcamSettings.CAMERA_MASK_RIGHT) != 0);
        syncing = false;
    }

    /**
     * Prints the storage probe's own account of itself into the screen.
     *
     * Where a volume may be written is not something this code can reason about from a rule:
     * on this vehicle the root of /storage/UUID refuses writes while the same volume's raw
     * mount accepts them, and other firmware will differ again. When that goes wrong the only
     * useful answer is what the probe actually saw, and the head unit has no adb to ask.
     */
    private void showStorageDetails() {
        TextView details = findViewById(R.id.tvStorageDetails);
        details.setText(R.string.storage_details_working);
        ioExecutor.execute(() -> {
            String text;
            try {
                text = DashcamStorageManager.describeProbe(this);
            } catch (Throwable t) {
                text = String.valueOf(t);
            }
            final String finalText = text
                    + "\n\n== 360 app captures ==\n" + safeOemListing()
                    + "\n\n== runtime log ==\n" + DevRuntimeLog.snapshot();
            mainHandler.post(() -> details.setText(finalText));
        });
    }

    private static String safeOemListing() {
        try {
            return OemCaptures.describe();
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /**
     * Lifts whatever the factory 360 app's RecordActivity left in its private directory into
     * the dashcam records folder, where it can be read off the car. Both apps run as uid 1000,
     * so this is an ordinary copy rather than a way around anything.
     */
    private void copyOemCaptures() {
        TextView details = findViewById(R.id.tvStorageDetails);
        details.setText(R.string.oem_captures_working);
        ioExecutor.execute(() -> {
            String result;
            try {
                result = OemCaptures.copyTo(DashcamSettings.getRecordsBaseDir(this));
            } catch (Throwable t) {
                result = String.valueOf(t);
            }
            final String finalResult = result + "\n\n" + safeOemListing();
            mainHandler.post(() -> details.setText(finalResult));
        });
    }

    /** Deleting another app's files deserves a question first, especially on a touch screen. */
    private void confirmDeleteOemCaptures() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.oem_delete_title)
                .setMessage(R.string.oem_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.oem_delete_confirm, (d, which) -> deleteOemCaptures())
                .show();
    }

    private void deleteOemCaptures() {
        TextView details = findViewById(R.id.tvStorageDetails);
        details.setText(R.string.oem_deleting);
        ioExecutor.execute(() -> {
            String result;
            try {
                result = OemCaptures.deleteCaptures();
            } catch (Throwable t) {
                result = String.valueOf(t);
            }
            final String finalResult = result + "\n\n" + safeOemListing();
            mainHandler.post(() -> details.setText(finalResult));
        });
    }

    // ---------- Status ----------

    /**
     * The badge answers one question from across the cabin: is it recording. The sentence
     * beside it answers the follow-up for anyone who leans in. Green rather than the camcorder
     * red, so that red can mean only that something is wrong.
     */
    private void refreshStatus() {
        RecordingService.PersistedStatus s = RecordingService.readPersistedStatus(prefs());
        // Off is what the badge already says; repeating it beside the badge is noise. Every
        // other state has something to add.
        tvStatusDetail.setText(RecordingService.STATUS_OFF.equals(s.status)
                ? ""
                : RecordingService.formatStatusText(
                        this, s.status, s.activeCameras, s.totalCameras, s.lastError));

        final int label;
        final int fill;
        int textColor = R.color.status_text;
        if (RecordingService.STATUS_RECORDING.equals(s.status)) {
            label = R.string.status_pill_on;
            fill = R.color.status_recording;
        } else if (RecordingService.STATUS_PAUSED_OEM.equals(s.status)) {
            label = R.string.status_pill_paused;
            fill = R.color.status_paused;
        } else if (RecordingService.STATUS_STARTING.equals(s.status)) {
            label = R.string.status_pill_starting;
            fill = R.color.status_paused;
        } else if (RecordingService.STATUS_ERROR.equals(s.status)
                || RecordingService.STATUS_PARTIAL.equals(s.status)) {
            label = R.string.status_pill_error;
            fill = R.color.status_error;
        } else {
            label = R.string.status_pill_off;
            fill = R.color.status_off;
            textColor = R.color.status_text_off;
        }
        tvStatus.setText(label);
        tvStatus.setTextColor(ContextCompat.getColor(this, textColor));
        tvStatus.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, fill)));
    }

    /** {@link DashcamStorageManager#resolve} does real IO for the USB targets. */
    private void refreshStorageStatus() {
        tvStorageStatus.setText(R.string.storage_status_checking);
        ioExecutor.execute(() -> {
            final DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(this);
            mainHandler.post(() -> applyStorageStatus(res));
        });
    }

    private void applyStorageStatus(DashcamStorageManager.Resolution res) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (res.baseDir == null) {
            tvStorageStatus.setText(getString(R.string.storage_status_unavailable, res.usbState.name()));
            return;
        }
        File dir = res.baseDir;
        tvStorageStatus.setText(res.usingUsb
                ? getString(R.string.storage_status_usb, dir.getAbsolutePath())
                : getString(R.string.storage_status_internal, dir.getAbsolutePath(), res.usbState.name()));
    }

    // ---------- Helpers ----------

    private SharedPreferences prefs() {
        return UiPrefs.getPrefs(this);
    }

    private static int radioIdForTarget(int target) {
        switch (target) {
            case DashcamStorageManager.TARGET_AUTO:
                return R.id.rbStorageAuto;
            case DashcamStorageManager.TARGET_USB_ONLY:
                return R.id.rbStorageUsb;
            default:
                return R.id.rbStorageInternal;
        }
    }

    private static int targetForRadioId(int radioId) {
        if (radioId == R.id.rbStorageAuto) {
            return DashcamStorageManager.TARGET_AUTO;
        }
        if (radioId == R.id.rbStorageUsb) {
            return DashcamStorageManager.TARGET_USB_ONLY;
        }
        return DashcamStorageManager.TARGET_INTERNAL_ONLY;
    }

    private static int readInt(EditText editText, int fallback) {
        try {
            return Integer.parseInt(editText.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Commits on focus loss rather than on every keystroke. */
    private void onBlur(EditText editText, Runnable commit) {
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !syncing) {
                commit.run();
            }
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Intentionally empty: values are committed on blur.
            }
        });
    }

    private void ensureStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            refreshStorageStatus();
        }
    }
}
