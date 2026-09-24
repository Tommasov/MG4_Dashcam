package com.drivehub.kamera;

import com.drivehub.kamera.dashcam.DashcamSettings;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.CrashTrail;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.dev.StandbyJournal;
import com.drivehub.kamera.update.ApkDownloader;
import com.drivehub.kamera.update.ApkInstaller;
import com.drivehub.kamera.update.DashcamUpdates;
import com.drivehub.kamera.update.UpdateInfo;
import com.drivehub.kamera.dev.ProbeReport;
import com.drivehub.kamera.dev.OemCaptures;
import com.drivehub.kamera.settings.Dialogs;
import com.drivehub.kamera.settings.UiPrefs;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.SeekBar;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
    private EditText etBitrate;
    private EditText etSignature;
    private EditText etOemMaxSpeed;
    private SwitchCompat swShowSpeed;
    private SwitchCompat swOemCoexist;
    private CheckBox cbFront;
    private CheckBox cbRear;
    private CheckBox cbLeft;
    private CheckBox cbRight;

    private boolean syncing = false;
    private long visibleSinceMs = 0L;

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
        etBitrate = findViewById(R.id.etBitrate);
        etSignature = findViewById(R.id.etSignature);
        etOemMaxSpeed = findViewById(R.id.etOemMaxSpeed);
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

        findViewById(R.id.btnPreviewGrid).setOnClickListener(
                v -> startActivity(new Intent(this, PreviewActivity.class)));
        findViewById(R.id.btnUsbVolume).setOnClickListener(v -> chooseUsbVolume());
        findViewById(R.id.btnStorageDetails).setOnClickListener(v -> showStorageDetails());
        findViewById(R.id.btnClearRecords).setOnClickListener(v -> confirmClearRecords());

        // Which build this is, small and out of the way. It costs a corner of the screen and
        // saves the first question of every report: "which version were you running?"
        ((TextView) findViewById(R.id.tvVersion)).setText(getString(
                R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        // Offered only when this build carries a probe key; a button that can only fail is
        // worse than no button.
        View sendProbe = findViewById(R.id.btnSendProbe);
        sendProbe.setVisibility(ProbeReport.isConfigured() ? View.VISIBLE : View.GONE);
        sendProbe.setOnClickListener(v -> confirmSendProbe());
        findViewById(R.id.btnCheckUpdates).setOnClickListener(v -> checkForUpdates());
        SwitchCompat statusDot = findViewById(R.id.swStatusBarIcon);
        statusDot.setChecked(UiPrefs.isStatusBarIcon(prefs()));
        showStatusIconInvite(UiPrefs.isStatusBarIcon(prefs()));
        statusDot.setOnCheckedChangeListener((b, checked) -> {
            UiPrefs.setStatusBarIcon(prefs(), checked);
            showStatusIconInvite(checked);
            // The service redraws the dot on its next status update, which may be a clip away.
            // Turning a switch and seeing nothing happen reads as a switch that does nothing.
            RecordingService.refreshStatusIcon(this);
        });
        SeekBar dotX = findViewById(R.id.sbStatusBarIconX);
        dotX.setProgress(UiPrefs.getStatusBarIconX(prefs()));
        dotX.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                // Live, so the dot follows the finger: a position you have to guess and then
                // check is the thing this control exists to avoid.
                UiPrefs.setStatusBarIconX(prefs(), value);
                RecordingService.refreshStatusIcon(MainActivity.this);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        SwitchCompat standby = findViewById(R.id.swStandbyDiagnostics);
        standby.setChecked(UiPrefs.isStandbyDiagnostics(prefs()));
        standby.setOnCheckedChangeListener(
                (b, checked) -> UiPrefs.setStandbyDiagnostics(prefs(), checked));
        SwitchCompat beta = findViewById(R.id.swBetaChannel);
        beta.setChecked(UiPrefs.isUpdateBetaChannel(prefs()));
        beta.setOnCheckedChangeListener((b, checked) -> {
            UiPrefs.setUpdateBetaChannel(prefs(), checked);
            refreshUpdateBadge();
        });
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
        visibleSinceMs = System.currentTimeMillis();
        DevRuntimeLog.add("MainActivity", "onResume");
        RecordingService.resetPersistedStatusIfStale(prefs());
        refreshStatus();
        refreshUsbVolumeButton();
        refreshStorageStatus();
        refreshStorageUsage();
        refreshUpdateBadge();
        // Rate-limited to once a day inside, and silent whatever it finds: the only thing it
        // can do to the screen is light the mark beside the version.
        DashcamUpdates.checkQuietly(this, this::refreshUpdateBadge);
    }

    /**
     * The mark beside the version line. Read from what the last check wrote down, so opening
     * the screen costs nothing.
     */
    private void refreshUpdateBadge() {
        String pending = DashcamUpdates.pendingVersionName(this, prefs());

        TextView badge = findViewById(R.id.tvUpdateBadge);
        badge.setVisibility(pending == null ? View.GONE : View.VISIBLE);
        if (pending != null) {
            badge.setText(getString(R.string.update_badge, pending));
        }

        // The same news at the top, where the eye lands on opening. The mark by the version
        // number stays: it is the one that answers "which build is this and is it current",
        // which is a different question from "there is something new".
        TextView banner = findViewById(R.id.tvUpdateBanner);
        banner.setVisibility(pending == null ? View.GONE : View.VISIBLE);
        if (pending != null) {
            banner.setText(getString(R.string.update_banner, pending));
            banner.setOnClickListener(v -> checkForUpdates());
        }

        // Which channel this build came from, beside the name. Nothing on stable.
        findViewById(R.id.tvChannel).setVisibility(
                UiPrefs.isUpdateBetaChannel(prefs()) ? View.VISIBLE : View.GONE);
    }

    /** The button: says what it found either way, and offers to install when there is one. */
    private void checkForUpdates() {
        TextView details = findViewById(R.id.tvStorageDetails);
        details.setText(R.string.update_checking);
        DashcamUpdates.checkNow(this, new DashcamUpdates.Listener() {
            @Override
            public void onUpdateAvailable(UpdateInfo info) {
                refreshUpdateBadge();
                details.setText(getString(R.string.update_available_title, info.versionName));
                offerUpdate(info);
            }

            @Override
            public void onUpToDate() {
                refreshUpdateBadge();
                details.setText(R.string.update_up_to_date);
            }

            @Override
            public void onError(Exception e) {
                details.setText(getString(R.string.update_check_failed, String.valueOf(e)));
            }
        });
    }

    private void offerUpdate(UpdateInfo info) {
        String changelog = info.changelog == null ? "" : info.changelog.trim();
        Dialogs.builder(this)
                .setTitle(getString(R.string.update_available_title, info.versionName))
                .setMessage(changelog.isEmpty() ? null : changelog)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.btn_update_install, (d, w) -> onInstallChosen(info))
                .show();
    }

    /**
     * Asks for the permission first and downloads second.
     *
     * <p>Android refuses to install a package handed over by an app the user has not allowed
     * under "install unknown apps", and it refuses with a message that says nothing useful.
     * Checking afterwards meant waiting for five megabytes to arrive and then being told no.
     */
    private void onInstallChosen(UpdateInfo info) {
        // Replacing the package kills this process, and with it the recording service: the
        // clip being written is left without its index and thrown away. Nothing warned about
        // that until updates started installing on their own, at which point the cost stopped
        // being theoretical. The loop switch, not the momentary status: a recording paused for
        // the factory 360 view is still a recording about to continue.
        if (DashcamSettings.isEnabled(prefs())) {
            Dialogs.builder(this)
                    .setTitle(R.string.update_while_recording_title)
                    .setMessage(R.string.update_while_recording_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.update_while_recording_stop, (d, w) -> {
                        // The same path the switch takes, so the service is stopped rather
                        // than left running against a preference that says it should not be.
                        DashcamSettings.setEnabled(prefs(), false);
                        DashcamSettings.rememberStoppedForUpdate(prefs());
                        RecordingService.stopIfRunning(this);
                        swEnabled.setChecked(false);
                        onInstallChosen(info);
                    })
                    .show();
            return;
        }
        if (!DashcamUpdates.canInstall(this)) {
            Dialogs.builder(this)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.update_permission_open_settings,
                            (d, w) -> DashcamUpdates.requestInstallPermission(this))
                    .show();
            return;
        }
        downloadUpdate(info);
    }

    private void downloadUpdate(UpdateInfo info) {
        TextView details = findViewById(R.id.tvStorageDetails);
        ApkDownloader downloader = new ApkDownloader(this);
        downloader.start(info, new ApkDownloader.Callback() {
            @Override
            public void onProgress(int percent) {
                details.setText(getString(R.string.update_downloading, percent));
            }

            @Override
            public void onComplete(java.io.File apk) {
                // Verified before anything is handed to an installer: a truncated download and
                // a tampered one look the same from here, and neither should be installed.
                // Hashing five megabytes is long enough to be felt on the main thread, so it
                // happens on the io executor and only the verdict comes back.
                ioExecutor.execute(() -> {
                    boolean ok = ApkInstaller.verify(apk, info.sha256);
                    mainHandler.post(() -> {
                        if (!ok) {
                            //noinspection ResultOfMethodCallIgnored
                            apk.delete();
                            details.setText(R.string.update_checksum_failed);
                            return;
                        }
                        // Said before the handover, not after: committing the install
                        // replaces the package, which kills this process mid-sentence. A
                        // screen that goes black with no warning reads as a crash, and the
                        // app has just been told to do something - so it owes an explanation
                        // in advance rather than an apology afterwards.
                        details.setText(R.string.update_installing_will_restart);
                        DashcamUpdates.installVerified(MainActivity.this, apk);
                    });
                });
            }

            @Override
            public void onFailed(String reason) {
                details.setText(getString(R.string.update_download_failed, reason));
            }
        });
    }


    /**
     * Records how the screen went away, because "it closes by itself after a while" has two very
     * different causes. If something finished the activity - a back press, real or synthesised,
     * or the car blocking a screen it does not want shown - isFinishing() is true. If something
     * merely came on top, it is false. The elapsed time says whether it is on a timer.
     */
    private void logVisibilityChange(String event) {
        long shown = visibleSinceMs > 0 ? (System.currentTimeMillis() - visibleSinceMs) / 1000 : -1;
        DevRuntimeLog.add("MainActivity", event
                + " after " + shown + "s"
                + " finishing=" + isFinishing()
                + " changingConfig=" + isChangingConfigurations());
    }

    @Override
    protected void onStop() {
        logVisibilityChange("onStop");
        super.onStop();
    }

    @Override
    protected void onPause() {
        logVisibilityChange("onPause");
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
        etBitrate.setText(String.valueOf(DashcamSettings.getRecordingBitrateKbps(prefs)));
        etSignature.setText(DashcamSettings.getRecordingSignature(prefs));
        etOemMaxSpeed.setText(String.valueOf(UiPrefs.getDevOemAvmMaxSpeedKmh(prefs)));

        syncing = false;

        swEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (syncing) return;
            DashcamSettings.setEnabled(prefs(), checked);
            // A switch touched by hand outranks anything remembered from an update.
            DashcamSettings.forgetStoppedForUpdate(prefs());
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
            refreshUsbVolumeButton();
            refreshStorageStatus();
            refreshStorageUsage();
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
        onBlur(etBitrate, () -> {
            DashcamSettings.setRecordingBitrateKbps(prefs(),
                    readInt(etBitrate, DashcamSettings.DEFAULT_RECORDING_BITRATE_KBPS));
            // Written back from the setting, so a value outside the range shows what it became
            // instead of what was typed.
            etBitrate.setText(String.valueOf(DashcamSettings.getRecordingBitrateKbps(prefs())));
        });

        onBlur(etFps, () -> {
            DashcamSettings.setRecordingFps(prefs(), readInt(etFps, DashcamSettings.DEFAULT_RECORDING_FPS));
            etFps.setText(String.valueOf(DashcamSettings.getRecordingFps(prefs())));
        });
        onBlur(etOemMaxSpeed, () -> {
            UiPrefs.setDevOemAvmMaxSpeedKmh(prefs(), readInt(etOemMaxSpeed,
                    UiPrefs.DEFAULT_DEV_OEM_AVM_MAX_SPEED_KMH));
            etOemMaxSpeed.setText(String.valueOf(UiPrefs.getDevOemAvmMaxSpeedKmh(prefs())));
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
    /**
     * How much the dashcam is holding, measured rather than guessed.
     *
     * <p>Walks the folder on the io thread: on a stick with a hundred clips this is a directory
     * listing, but it is a directory listing on a FAT volume that is also being written to, and
     * the main thread has no business waiting for it.
     */
    private void refreshStorageUsage() {
        TextView usage = findViewById(R.id.tvStorageUsage);
        usage.setText(R.string.storage_usage_checking);
        ioExecutor.execute(() -> {
            final DashcamStorageManager.Usage measured;
            try {
                measured = DashcamStorageManager.measureUsage(this);
            } catch (Throwable t) {
                mainHandler.post(() -> usage.setText(""));
                return;
            }
            mainHandler.post(() -> {
                if (measured.isEmpty()) {
                    usage.setText(R.string.storage_usage_empty);
                } else if (measured.eventCount > 0) {
                    usage.setText(getString(R.string.storage_usage,
                            formatSize(measured.totalBytes()),
                            quantity(R.plurals.usage_clips, measured.clipCount),
                            quantity(R.plurals.usage_events, measured.eventCount)));
                } else {
                    usage.setText(getString(R.string.storage_usage_no_events,
                            formatSize(measured.totalBytes()),
                            quantity(R.plurals.usage_clips, measured.clipCount)));
                }
            });
        });
    }

    private String quantity(int pluralResId, int count) {
        return getResources().getQuantityString(pluralResId, count, count);
    }

    private String formatSize(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    /**
     * Emptying the loop is a question, not a button.
     *
     * <p>The clips are disposable and the ring buffer deletes them anyway; the saved events are
     * the opposite, and they are the reason somebody was recording in the first place. So the
     * question says how much is going, and says out loud that the events are staying.
     */
    private void confirmClearRecords() {
        TextView usage = findViewById(R.id.tvStorageUsage);
        usage.setText(R.string.storage_usage_checking);
        ioExecutor.execute(() -> {
            final DashcamStorageManager.Usage measured;
            try {
                measured = DashcamStorageManager.measureUsage(this);
            } catch (Throwable t) {
                mainHandler.post(this::refreshStorageUsage);
                return;
            }
            mainHandler.post(() -> {
                if (measured.clipCount == 0) {
                    usage.setText(R.string.clear_records_none);
                    return;
                }
                refreshStorageUsage();
                Dialogs.builder(this)
                        .setTitle(R.string.clear_records_title)
                        .setMessage(getString(R.string.clear_records_message,
                                formatSize(measured.clipBytes)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.clear_records_confirm,
                                (d, w) -> clearRecords(measured.clipBytes))
                        .show();
            });
        });
    }

    private void clearRecords(long expectedBytes) {
        TextView usage = findViewById(R.id.tvStorageUsage);
        usage.setText(R.string.clear_records_working);
        ioExecutor.execute(() -> {
            final int deleted;
            try {
                deleted = DashcamStorageManager.deleteLoopClips(this);
            } catch (Throwable t) {
                mainHandler.post(this::refreshStorageUsage);
                return;
            }
            mainHandler.post(() -> {
                Toast.makeText(this, getString(R.string.clear_records_done,
                        quantity(R.plurals.cleared_clips, deleted),
                        formatSize(expectedBytes)), Toast.LENGTH_LONG).show();
                refreshStorageUsage();
            });
        });
    }

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
        Dialogs.builder(this)
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

    /**
     * Asks before anything leaves the car, and asks for one line of context while it is at it.
     *
     * A log that arrives on its own is half a report: it says what happened, not what the
     * driver was trying to do. The dialog also spells out exactly what is in the report, which
     * is the only way someone can consent to sending it.
     */
    /**
     * The coloured line that offers the status dot, and stops offering once it is taken.
     *
     * <p>Off by default is the right default - drawing inside the factory interface is not a
     * decision to make for somebody else - but a default nobody knows about is a feature nobody
     * has. A report from another car showed exactly that: the switch untouched, because there
     * was nothing to suggest it was worth touching. So it says what you get, in the accent
     * colour of the car's own launcher, and disappears the moment it is switched on: an
     * invitation, not a nag.
     */
    private void showStatusIconInvite(boolean enabled) {
        View invite = findViewById(R.id.tvStatusBarIconInvite);
        View summary = findViewById(R.id.tvStatusBarIconSummary);
        if (invite != null) {
            invite.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        if (summary != null) {
            summary.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmSendProbe() {
        // The note field is built with the scaled context too, or it would stay small while the
        // title and buttons around it grew.
        final Context dialogContext = Dialogs.scaled(this);
        final EditText note = new EditText(dialogContext);
        note.setHint(R.string.probe_dialog_note_hint);
        int pad = getResources().getDimensionPixelSize(R.dimen.screen_padding);
        note.setPadding(pad, pad / 2, pad, pad / 2);

        Dialogs.builder(this)
                .setTitle(R.string.probe_dialog_title)
                .setMessage(R.string.probe_dialog_message)
                .setView(note)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.probe_dialog_send,
                        (d, which) -> sendProbe(note.getText().toString()))
                .show();
    }

    private void sendProbe(String note) {
        TextView details = findViewById(R.id.tvStorageDetails);
        details.setText(R.string.probe_sending);
        ioExecutor.execute(() -> {
            final String body = buildReport();
            ProbeReport.send(this, note, body, new ProbeReport.Callback() {
                @Override
                public void onSent(String reportName) {
                    details.setText(getString(R.string.probe_sent, reportName));
                }

                @Override
                public void onFailed(String reason) {
                    details.setText(getString(R.string.probe_failed, reason));
                }
            });
        });
    }

    /** The same thing the details button prints, with a header saying which car and build. */
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append("\n");
        sb.append("android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("uid: ").append(android.os.Process.myUid()).append("\n");
        // These cross a process death; the runtime log does not.
        sb.append("service starts: ").append(prefs().getInt(RecordingService.KEY_SERVICE_STARTS, 0))
                .append(", sticky restarts: ")
                .append(prefs().getInt(RecordingService.KEY_STICKY_RESTARTS, 0))
                .append("\n");
        try {
            sb.append("\n").append(DashcamStorageManager.describeProbe(this)).append("\n");
        } catch (Throwable t) {
            sb.append("storage probe failed: ").append(t).append("\n");
        }
        sb.append("\n").append("== what the cameras report ==").append("\n")
                .append(safeCameraFormats());
        sb.append("\n").append("== 360 app captures ==").append("\n")
                .append(safeOemListing());
        sb.append("\n").append("== what android recorded about our deaths ==").append("\n")
                .append(CrashTrail.describe());
        // Named for what it is. It used to say "what happened at the last standby",
        // which is a promise it does not keep: it is a rolling window, so it routinely
        // carries entries from days ago under a heading that implies one event.
        sb.append("\n").append("== standby journal (last ")
                .append(StandbyJournal.maxLines())
                .append(" entries, oldest first) ==").append("\n")
                .append(StandbyJournal.snapshot(this));
        sb.append("\n").append("== runtime log ==").append("\n")
                .append(DevRuntimeLog.snapshot()).append("\n");
        return sb.toString();
    }

    /**
     * What the V4L2 devices say about themselves, if any has been opened this run.
     *
     * <p>Empty until recording has started at least once: the format is read when a device is
     * opened, and nothing opens one just to ask. Worth saying so rather than printing nothing.
     */
    private String safeCameraFormats() {
        try {
            return CameraProbe.describeCameraFormats();
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
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
    /**
     * The button carries the answer, so the screen says which stick without being asked.
     *
     * <p>Hidden when the target is internal storage: an offer to choose between USB volumes, on
     * a screen set to ignore them, is a question with no consequence.
     */
    private void refreshUsbVolumeButton() {
        Button button = findViewById(R.id.btnUsbVolume);
        boolean usesUsb = DashcamStorageManager.getStorageTarget(prefs())
                != DashcamStorageManager.TARGET_INTERNAL_ONLY;
        button.setVisibility(usesUsb ? View.VISIBLE : View.GONE);
        if (!usesUsb) {
            return;
        }
        String chosen = DashcamStorageManager.getPreferredVolumeId(prefs());
        button.setText(getString(R.string.usb_volume_button,
                chosen.isEmpty() ? getString(R.string.usb_volume_any) : chosen));
    }

    /**
     * Lists what is connected and lets one be picked, or the choice dropped.
     *
     * <p>A volume chosen earlier and now absent still appears, marked as such: dropping it from
     * the list would leave the driver unable to tell "you chose a stick that is not here" from
     * "you never chose anything".
     */
    private void chooseUsbVolume() {
        ioExecutor.execute(() -> {
            final List<DashcamStorageManager.VolumeChoice> volumes;
            try {
                volumes = DashcamStorageManager.listVolumes(this);
            } catch (Throwable t) {
                return;
            }
            final String chosen = DashcamStorageManager.getPreferredVolumeId(prefs());
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                List<String> ids = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                ids.add("");
                labels.add(getString(R.string.usb_volume_entry_any));
                boolean chosenListed = chosen.isEmpty();
                for (DashcamStorageManager.VolumeChoice volume : volumes) {
                    ids.add(volume.volumeId);
                    labels.add(getString(R.string.usb_volume_entry,
                            volume.description + " (" + volume.volumeId + ")",
                            formatSize(volume.freeBytes), formatSize(volume.totalBytes)));
                    chosenListed |= chosen.equals(volume.volumeId);
                }
                if (!chosenListed) {
                    // Kept in the list, marked absent. Dropping it would leave no way to tell
                    // "the stick you chose is not here" from "you never chose one".
                    ids.add(chosen);
                    labels.add(getString(R.string.usb_volume_entry_absent, chosen));
                }

                Context dialogContext = Dialogs.scaled(this);
                View body = LayoutInflater.from(dialogContext)
                        .inflate(R.layout.dialog_volume_choice, null);
                ((TextView) body.findViewById(R.id.tvVolumeMessage)).setText(volumes.isEmpty()
                        ? getString(R.string.usb_volume_none)
                        : getString(R.string.usb_volume_message));
                RadioGroup group = body.findViewById(R.id.rgVolumes);
                for (int i = 0; i < labels.size(); i++) {
                    RadioButton option = new RadioButton(dialogContext);
                    option.setId(i);
                    option.setText(labels.get(i));
                    option.setChecked(ids.get(i).equals(chosen));
                    group.addView(option);
                }

                AlertDialog dialog = Dialogs.builder(this)
                        .setTitle(R.string.usb_volume_title)
                        .setView(body)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                group.setOnCheckedChangeListener((g, checkedId) -> {
                    DashcamStorageManager.setPreferredVolumeId(prefs(), ids.get(checkedId));
                    dialog.dismiss();
                    refreshUsbVolumeButton();
                    refreshStorageStatus();
                    refreshStorageUsage();
                });
                dialog                        .show();
            });
        });
    }

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
            // One state gets a sentence of its own: it is the only failure the driver caused on
            // purpose, and the only one they can undo by plugging something in.
            tvStorageStatus.setText(
                    res.usbState == DashcamStorageManager.UsbState.CHOSEN_VOLUME_ABSENT
                            ? getString(R.string.storage_status_chosen_absent)
                            : getString(R.string.storage_status_unavailable, res.usbState.name()));
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
