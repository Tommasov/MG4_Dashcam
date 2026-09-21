package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.helper.vehiclesensors.VehicleGearProbe;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.Notification;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "start_recording";
    public static final String ACTION_STOP = "stop_recording";
    public static final String ACTION_RECORD_TEST = "record_test";
    public static final String ACTION_EJECT_USB = "eject_usb";
    public static final String ACTION_TRIGGER_EVENT_SAVE = "trigger_event_save";
    public static final String ACTION_PAUSE_FOR_OEM_REQUEST = "pause_for_oem_request";
    public static final String ACTION_RESUME_AFTER_OEM_REQUEST = "resume_after_oem_request";
    public static final String ACTION_STATUS_CHANGED = "com.drivehub.kamera.ACTION_RECORDING_STATUS_CHANGED";
    public static final String ACTION_USB_EJECT_READY = "com.drivehub.kamera.ACTION_USB_EJECT_READY";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ACTIVE_CAMERAS = "active_cameras";
    public static final String EXTRA_TOTAL_CAMERAS = "total_cameras";
    public static final String EXTRA_LAST_ERROR = "last_error";
    public static final String EXTRA_TEST_RECORD_DURATION_SEC = "test_record_duration_sec";
    public static final String EXTRA_USB_EJECT_SAFE_TO_REMOVE = "usb_eject_safe_to_remove";
    public static final String EXTRA_USB_EJECT_MESSAGE_RES = "usb_eject_message_res";
    public static final String EXTRA_EVENT_ALLOW_FUTURE_ONLY = "event_allow_future_only";
    public static final String STATUS_OFF = "off";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_RECORDING = "recording";
    public static final String STATUS_PAUSED_OEM = "paused_oem";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_ERROR = "error";

    private static final String ERROR_STORAGE_NOT_WRITABLE = "storage not writable";
    private static final String ERROR_GRID_START_FAILED = "grid start failed";
    private static final String ERROR_GRID_STOP_TIMEOUT = "grid stop timeout";
    private static final String ERROR_USB_STORAGE = "usb storage unavailable";
    /**
     * The chosen volume is there and will not take a byte.
     *
     * <p>Worth its own state because the only thing that fixes it is physical, and nothing in
     * the app can do it: the volume comes back read-only after the head unit is suspended
     * mid-write, and stays that way until it is unplugged and reinserted, which is what gets
     * the filesystem checked. Telling somebody "USB unavailable" while the stick sits there
     * with its light on sends them looking in the wrong place.
     */
    private static final String ERROR_USB_READ_ONLY = "usb volume read only";
    private static final String ERROR_LOOP_DIED = "recording loop died";
    private static final String ERROR_STALLED = "recording stalled";
    private static final String ERROR_CRASH_LOOP = "crash loop";

    private static final String KEY_STATUS = "recordingStatus";
    private static final String KEY_ACTIVE_CAMERAS = "recordingActiveCameras";
    private static final String KEY_TOTAL_CAMERAS = "recordingTotalCameras";
    private static final String KEY_LAST_ERROR = "recordingLastError";
    private static final String KEY_EVENT_COMPLETED_SEGMENT_COUNT = "eventCompletedSegmentCount";
    private static final String KEY_EVENT_RECENT_SEGMENTS = "eventRecentSegments";
    private static final String KEY_EVENT_PENDING_REQUESTS = "eventPendingRequests";
    // The runtime log lives in memory and dies with the process, which is exactly when it would
    // have been worth reading. These survive in preferences instead.
    public static final String KEY_SERVICE_STARTS = "serviceStartCount";
    public static final String KEY_STICKY_RESTARTS = "stickyRestartCount";
    public static final String KEY_LAST_SERVICE_START = "lastServiceStartMs";
    // The crash-loop brake counts in preferences, not in a field: the field dies with the
    // process every time round the loop, which is the whole problem.
    private static final String KEY_CRASH_WINDOW_START = "crashWindowStartMs";
    private static final String KEY_CRASH_WINDOW_COUNT = "crashWindowCount";

    /** How long a run of sticky restarts has to happen within to count as a loop. */
    private static final long CRASH_LOOP_WINDOW_MS = 3 * 60_000L;
    /** Restarts within that window before giving up. Coming back once is right; five times is not. */
    private static final int CRASH_LOOP_LIMIT = 5;

    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;
    private static final int TOTAL_CAMERAS = 4;
    /**
     * The size of one camera's cell in the composed grid.
     *
     * <p>Public because the preview composes with the same numbers: a preview built on different
     * ones would be a picture of a layout nobody records.
     */
    public static final int CELL_WIDTH = 720;
    /**
     * The full frame height, not half of it.
     *
     * <p>The capture path used to hand over one field of the interlaced buffer and this was 240
     * to match. It now deinterlaces, so a cell is the whole picture the camera took - see
     * docs/camera-format.md.
     */
    public static final int CELL_HEIGHT = 480;
    private static final int EVENT_SEGMENTS_BEFORE_CURRENT = 2;
    private static final int EVENT_SEGMENTS_AFTER_CURRENT = 2;
    private static final int FUTURE_ONLY_EVENT_SEGMENTS = 3;
    private static final long ERROR_OVERLAY_DELAY_MS = 5_000L;

    private static volatile boolean sServiceRunning = false;
    /** Same process as the receivers, so they can act without a service round trip. */
    private static volatile RecordingService sInstance;
    private static volatile boolean sWorkerActive = false;

    private final Object eventLock = new Object();
    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService eventCopyExecutor = Executors.newSingleThreadExecutor();
    private volatile Thread worker;
    private volatile boolean stopRequested = false;
    private volatile boolean segmentStopRequested = false;
    private volatile boolean oemPauseRequested = false;
    private volatile int pendingErrorSubtitleResId = 0;
    private volatile int pendingErrorNotificationResId = 0;
    private volatile int pendingErrorGeneration = 0;
    private volatile boolean errorOverlayShown = false;
    private volatile File activeBaseDir;
    private volatile boolean activeBaseIsUsb;
    private volatile boolean usbEjectInProgress = false;
    private volatile boolean futureOnlyEventSession = false;
    private long completedSegmentCount = 0L;
    private volatile long lastSegmentCompletedMs = 0L;
    private static final long WATCHDOG_PERIOD_MS = 20_000L;
    private static final long SUPERVISOR_PERIOD_MS = 20_000L;
    /**
     * How often to look at what is on screen.
     *
     * <p>A second was too coarse, and the car proved it: when the factory 360 view fails to open
     * because we are holding the cameras, it is on screen for three or four tenths of a second
     * and then gone. The activity's own onPause/onResume caught three such attempts in one
     * session; the poll caught none of them, so the hand-off never started and the driver's
     * button did nothing. A visit that short is seen roughly three times in ten at one second,
     * and every time at two hundred milliseconds.
     *
     * <p>Five binder calls a second, on a thread of their own, for a car that is plugged into an
     * engine. The hand-off is worth more than the cycles.
     */
    private static final long OEM_POLL_MS = 200L;
    private static final String OEM_AVM_PACKAGE = "com.saicmotor.hmi.aroundview";
    /** When the factory app first appeared, so a failed attempt can be told from a real one. */
    private long oemForegroundSinceMs = 0L;
    /** When it left the screen, or 0 while it is still there. */
    private long oemGoneSinceMs = 0L;

    /**
     * A visit shorter than this is the factory app failing to open, not somebody looking at it.
     * It comes up, finds the cameras busy, and closes itself in three or four tenths of a second;
     * the shortest visit worth calling a look is a couple of seconds. Measured at the poll
     * period, so it only means anything now that the poll is fast enough to measure it.
     */
    private static final long OEM_FAILED_VISIT_MS = 1_500L;
    /** After a real 360 session: long enough that a flicker between screens is not a departure. */
    private static final long OEM_RESUME_DELAY_MS = 2_000L;
    /**
     * After a failed one: long enough for the driver to press the button again and find the
     * cameras free. Recording gives up those seconds, which is the right trade - the driver is
     * asking to see behind the car right now, and we were the reason they could not.
     */
    private static final long OEM_RETRY_GRACE_MS = 8_000L;
    private boolean lastForeground = false;
    /**
     * The hand-off runs here rather than on the main thread. {@code getRunningTasks} is a binder
     * call, and once a second on the main thread it is a standing invitation to an ANR.
     *
     * <p>It was answered once by reading the previous poll's result on the main thread and
     * refreshing it in the background, which kept the main thread free but made every reading a
     * second old. A second is the whole budget: by the time the factory camera is seen in the
     * foreground it has already opened the device, our V4L2 stream breaks under it and the
     * native encoder aborts the process. Reading and reacting on the same background thread
     * keeps the main thread free and the reading current.
     */
    private final ScheduledExecutorService oemPollExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "RecordingServiceOemWatch"));
    /** Bumped when a session starts, so an older chain of polls retires instead of doubling up. */
    private volatile int oemWatchGeneration = 0;
    private volatile long pausedSinceMs = 0L;
    /** A pause this long means a signal was missed; recording matters more. */
    private static final long MAX_OEM_PAUSE_MS = 60_000L;

    public static boolean isRunning() {
        return sServiceRunning;
    }

    /**
     * Whether a worker is actually recording, which is not the same as the service existing: a
     * sticky restart brings the service back with no worker at all.
     */
    public static boolean isRecordingActive() {
        return sWorkerActive;
    }

    public static void startIfDashcamEnabled(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        boolean enabled = prefs.getBoolean(DashcamSettings.KEY_ENABLED, false);
        if (!enabled)
            return;
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stopIfRunning(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    public static void startTestClip(Context context, int durationSec) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_RECORD_TEST);
        i.putExtra(EXTRA_TEST_RECORD_DURATION_SEC, durationSec);
        context.startForegroundService(i);
    }

    public static void triggerEventSave(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_TRIGGER_EVENT_SAVE);
        context.startService(i);
    }

    public static void triggerEventSaveOrFutureOnly(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_TRIGGER_EVENT_SAVE);
        i.putExtra(EXTRA_EVENT_ALLOW_FUTURE_ONLY, true);
        context.startForegroundService(i);
    }

    public static void requestUsbEject(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_EJECT_USB);
        context.startService(i);
    }

    /**
     * Yields the cameras as fast as this process can.
     *
     * The factory AVM calls V4l2_Init almost immediately after it launches, and loses if the
     * devices are still held: the screen dims and no picture arrives. Going through
     * startForegroundService first costs service creation and scheduling before a single flag
     * is raised, which is time spent on the wrong side of that race. The receiver runs in this
     * very process, so it raises the flags and interrupts the worker directly, and only then
     * starts the service for the status, the notification and the banner.
     */
    public static void pauseForOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!prefs.getBoolean(DashcamSettings.KEY_ENABLED, false) || !isRunning()) {
            return;
        }
        RecordingService service = sInstance;
        if (service != null) {
            service.beginOemPauseNow();
        }
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_PAUSE_FOR_OEM_REQUEST);
        context.startForegroundService(i);
    }

    /** The whole of the release path, minus anything that touches the screen. */
    void beginOemPauseNow() {
        if (oemPauseRequested) {
            return;
        }
        DevRuntimeLog.add("RecordingService", "oem pause: releasing cameras now");
        pausedSinceMs = System.currentTimeMillis();
        oemPauseRequested = true;
        segmentStopRequested = true;
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    public static void resumeAfterOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!prefs.getBoolean(DashcamSettings.KEY_ENABLED, false) || !isRunning()) {
            return;
        }
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_RESUME_AFTER_OEM_REQUEST);
        context.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sServiceRunning = true;
        sInstance = this;
        SharedPreferences p = prefs();
        p.edit()
                .putInt(KEY_SERVICE_STARTS, p.getInt(KEY_SERVICE_STARTS, 0) + 1)
                .putLong(KEY_LAST_SERVICE_START, System.currentTimeMillis())
                .apply();
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID, R.string.notification_channel_recording);
        restoreEventState();
        registerScreenReceiver();
        mainHandler.postDelayed(supervisor, SUPERVISOR_PERIOD_MS);
    }

    /**
     * Closes the clip before the head unit suspends, and picks up again when it wakes.
     *
     * <p>Locking the car puts the tablet into standby and, about a minute later, the SoC stops
     * running us. Nothing in this app decided to stop: we were simply frozen, mid-clip, with a
     * file open on the stick. On 19 September 2026 that left the volume mounted read-only on the
     * next drive - present, listed, and refusing every one of the three paths the probe tries -
     * until it was unplugged and put back, which is what makes a filesystem get checked.
     *
     * <p>It had survived dozens of ignition cycles before that and failed the first time one
     * happened with the loop running, which is what points at the open file rather than at the
     * mount. Closing the clip is a mitigation, not a proof: an interrupted write is far more
     * likely to leave an inconsistency than a finished one, but only more mileage will say
     * whether it is the whole story.
     *
     * <p>Screen off is not by itself a reason to stop. The display can be turned off on the
     * move, and a dashcam that stops recording because somebody dimmed the screen at night
     * would be worse than the bug it is fixing - so the car has to be stationary too.
     */
    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // Screen state is not deliverable from the manifest: it has to be a live receiver.
        registerReceiver(screenReceiver, filter);
    }

    private void onScreenOff() {
        if (worker == null || stopRequested || usbEjectInProgress) {
            return;
        }
        int speedKmh = VehicleSpeedReader.readSpeedKmh();
        if (speedKmh > SCREEN_OFF_MAX_SPEED_KMH) {
            DevRuntimeLog.add("RecordingService",
                    "screen off at " + speedKmh + " km/h; still driving, keeping the loop");
            return;
        }
        DevRuntimeLog.add("RecordingService",
                "screen off at " + speedKmh + " km/h; closing the clip before standby");
        pausedForScreenOff = true;
        shutdownRecordingServiceWithoutStopSelf();
        // Off the main thread: quiescence waits for the muxer, and this runs inside a broadcast.
        new Thread(() -> {
            boolean quiet = awaitShutdownQuiescence();
            DevRuntimeLog.add("RecordingService",
                    quiet ? "clip closed before standby" : "standby came before the clip closed");
        }, "RecordingServiceStandby").start();
    }

    private void onScreenOn() {
        if (!pausedForScreenOff) {
            return;
        }
        pausedForScreenOff = false;
        DevRuntimeLog.add("RecordingService", "screen on; the supervisor will restart the loop");
        // No restart here. The supervisor already starts a loop whenever the switch is on and
        // no worker is running, and it is the path that has been exercised; a second way in
        // would be a second thing to keep correct.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Android restarts a START_STICKY service with a null intent after killing the
            // process. Returning here left the service alive with no worker: nothing recording,
            // and - because the status had been persisted as RECORDING - a green badge over it.
            // Restarting the loop is the entire reason this service is sticky.
            DevRuntimeLog.add("RecordingService", "sticky restart after the process was killed");
            SharedPreferences sp = prefs();
            sp.edit().putInt(KEY_STICKY_RESTARTS, sp.getInt(KEY_STICKY_RESTARTS, 0) + 1).apply();
            if (!prefs().getBoolean(DashcamSettings.KEY_ENABLED, false)) {
                publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (crashLoopDetected()) {
                DevRuntimeLog.add("RecordingService", "crash loop: stopped resuming, switch off");
                // Turning the switch off is the only thing that actually ends the loop. Leaving
                // it on means the next glance at the app starts the whole thing again, and the
                // driver is left with a head unit that will not sit still.
                prefs().edit().putBoolean(DashcamSettings.KEY_ENABLED, false).apply();
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_CRASH_LOOP);
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_STOP");
            usbEjectInProgress = false;
            shutdownRecordingService();
            return START_NOT_STICKY;
        }

        if (ACTION_EJECT_USB.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_EJECT_USB");
            // Stop first, then decide on the worker thread: resolve() hits the filesystem for
            // every target except INTERNAL_ONLY, so it must not run here. Upstream could call
            // it inline because its resolve() always short-circuited to internal storage.
            usbEjectInProgress = true;
            shutdownRecordingServiceWithoutStopSelf();
            new Thread(() -> {
                boolean usingUsb = activeBaseIsUsb || DashcamStorageManager.resolve(this).usingUsb;
                if (!usingUsb) {
                    usbEjectInProgress = false;
                    broadcastUsbEjectReady(
                            false, R.string.settings_dashcam_storage_eject_unavailable_message);
                    stopForeground(true);
                    stopSelf();
                    return;
                }
                boolean safeToRemove = awaitShutdownQuiescence();
                broadcastUsbEjectReady(
                        safeToRemove,
                        safeToRemove
                                ? R.string.settings_dashcam_storage_eject_ready_message
                                : R.string.settings_dashcam_storage_eject_unavailable_message);
                stopForeground(true);
                stopSelf();
            }, "RecordingServiceUsbEject").start();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE_FOR_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_PAUSE_FOR_OEM_REQUEST");
            boolean enabled = prefs().getBoolean(DashcamSettings.KEY_ENABLED, false);
            boolean changed = !oemPauseRequested;
            oemPauseRequested = true;
            segmentStopRequested = true;
            // The worker sleeps in 200 ms ticks inside recordClip; interrupt wakes it instantly
            // so stopCombinedMp4Record runs before AVM's V4l2_Init hits "Device or resource busy".
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
            if (worker != null) {
                worker.interrupt();
            }
            if (enabled) {
                if (worker == null) {
                    stopRequested = false;
                    startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_paused_oem)));
                }
                publishStatus(STATUS_PAUSED_OEM, 0, TOTAL_CAMERAS, "");
                if (changed) {
                    DashcamNotice.showOemPause(this);
                }
            }
            return START_STICKY;
        }

        if (ACTION_RESUME_AFTER_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_RESUME_AFTER_OEM_REQUEST");
            boolean wasPaused = STATUS_PAUSED_OEM.equals(prefs().getString(KEY_STATUS, STATUS_OFF));
            oemPauseRequested = false;
            segmentStopRequested = false;
            // The watchdog asks how long it has been since a segment was written. While the
            // cameras are with the factory app the answer is "a while, on purpose", and the
            // watchdog knows to stay quiet - but the time still accumulated, and the first
            // check after the hand-off ended saw all of it at once. On 20 September 2026 a
            // legitimate two-and-a-half minute 360 session ended with "no segment for 168s"
            // and a recording error on screen, eleven seconds after recording had resumed
            // perfectly well. The clock for "have we written anything lately" starts when we
            // are able to write again.
            lastSegmentCompletedMs = System.currentTimeMillis();
            pausedSinceMs = 0L;
            oemForegroundSinceMs = 0L;
            oemGoneSinceMs = 0L;
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
            boolean enabled = prefs().getBoolean(DashcamSettings.KEY_ENABLED, false);
            if (!enabled) {
                if (worker == null) {
                    publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
                    stopForeground(true);
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            if (worker == null) {
                stopRequested = false;
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
                publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
                if (wasPaused) {
                    DashcamNotice.showOemResume(this);
                }
                worker = new Thread(this::recordLoop, "RecordingServiceWorker");
                worker.start();
            } else if (wasPaused) {
                publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
                DashcamNotice.showOemResume(this);
            }
            return START_STICKY;
        }

        if (ACTION_TRIGGER_EVENT_SAVE.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_TRIGGER_EVENT_SAVE");
            boolean allowFutureOnly = intent.getBooleanExtra(EXTRA_EVENT_ALLOW_FUTURE_ONLY, false);
            if (worker == null) {
                if (allowFutureOnly && startFutureOnlyEventSession()) {
                    DashcamNotice.showFutureOnlyConfirmation(this);
                } else if (allowFutureOnly) {
                    startForeground(NOTIF_ID, buildNotification(""));
                    stopForeground(true);
                    stopSelf();
                }
                return allowFutureOnly ? START_NOT_STICKY : START_STICKY;
            }
            if (futureOnlyEventSession) {
                DashcamNotice.showFutureOnlyConfirmation(this);
                return START_STICKY;
            }
            if (!prefs().getBoolean(DashcamSettings.KEY_ENABLED, false)) {
                return START_STICKY;
            }
            if (armEventCapture(EVENT_SEGMENTS_BEFORE_CURRENT, EVENT_SEGMENTS_AFTER_CURRENT)) {
                DashcamNotice.showConfirmation(this);
            }
            return START_STICKY;
        }

        if (worker != null) {
            // Do not start again if it is already running.
            publishCurrentStatus();
            return START_STICKY;
        }

        stopRequested = false;
        if (ACTION_START.equals(action)) {
            // Asked for by hand, from the switch or from the boot receiver. Whatever went wrong
            // before, this is a fresh attempt and deserves its full allowance of restarts.
            prefs().edit()
                    .remove(KEY_CRASH_WINDOW_START)
                    .remove(KEY_CRASH_WINDOW_COUNT)
                    .apply();
        }
        DevRuntimeLog.add("RecordingService", action == null ? "ACTION_START(null)" : action);
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
        publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        if (ACTION_RECORD_TEST.equals(action)) {
            int durationSec = intent.getIntExtra(
                    EXTRA_TEST_RECORD_DURATION_SEC,
                    DashcamSettings.getTestRecordDurationSec(prefs()));
            long durationMs = Math.max(0L, durationSec * 1000L);
            worker = new Thread(() -> recordTestClip(durationMs), "RecordingServiceTestWorker");
        } else {
            worker = new Thread(this::recordLoop, "RecordingServiceWorker");
        }
        worker.start();
        return START_STICKY;
    }

    private void recordTestClip(long durationMs) {
        File recordsBase = resolveActiveBaseDir(true);
        if (recordsBase == null) {
            worker = null;
            stopForeground(true);
            stopSelf();
            return;
        }
        // Write test clips into a separate subdirectory so they never participate
        // in ring-buffer cleanup and don't accumulate as phantom entries there.
        File testDir = new File(recordsBase, "test");
        if (!ensureDirectoryExists(testDir, "test dir")) {
            worker = null;
            stopForeground(true);
            stopSelf();
            return;
        }
        boolean startedAnyCamera = recordClip(testDir, durationMs,
                makeTimestampBase(System.currentTimeMillis(), "yyMMddHHmmss"), -1);
        worker = null;
        if (startedAnyCamera) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    /**
     * Wraps the loop so a thrown exception cannot kill the worker quietly.
     *
     * Without this the thread dies, `worker` is never cleared, the service stays alive and the
     * persisted status stays RECORDING: the app shows a green badge over a dashcam that stopped
     * recording. Seen once on a real drive. Failing loudly is the whole point.
     */
    private void recordLoop() {
        try {
            recordLoopBody();
        } catch (Throwable t) {
            Log.e(TAG, "recording loop died", t);
            DevRuntimeLog.add("RecordingService", "loop died: " + t);
            worker = null;
            sWorkerActive = false;
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_LOOP_DIED);
            try {
                CameraProbe.stopCombinedMp4Record();
            } catch (Throwable ignored) {
                // the encoder may already be gone; nothing useful to do here
            }
            // Deliberately does not stop the service. Stopping it would take the supervisor with
            // it, and then only the driver noticing a red badge could ever start recording
            // again. Staying alive costs a retry every twenty seconds and heals by itself when
            // the cause was temporary - the cameras held by the factory app, say.
        }
    }

    private void recordLoopBody() {
        sWorkerActive = true;
        // NOTE: For now we only record MP4 clips, not speed or turn-signal data.
        SharedPreferences prefs = prefs();
        boolean enabled = prefs.getBoolean(DashcamSettings.KEY_ENABLED, false);
        int segmentSec = DashcamSettings.getSegmentDurationSec();

        if (!enabled || segmentSec <= 0) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            worker = null;
            stopSelf();
            return;
        }

        File baseDir = awaitRecordsBaseDir();
        if (baseDir == null) {
            worker = null;
            // Deliberately not stopSelf(). Stopping takes the supervisor with it, and then only
            // the driver noticing a red badge and toggling the switch could ever start recording
            // again. Staying alive costs a retry every twenty seconds and heals by itself when
            // the stick is finally ready - or when one is plugged in.
            return;
        }

        long segmentMs = segmentSec * 1000L;
        // Asks once whether the standard car API can tell us the gear on this vehicle. Writes
        // the answer to the runtime log and does nothing else; see VehicleGearProbe.
        VehicleGearProbe.probe(this);
        mainHandler.post(this::startWatchdog);
        startOemWatch();

        boolean endedWithFatalError = false;

        while (!stopRequested) {
            if (!waitForOemPauseToClear(prefs)) {
                break;
            }
            // Re-resolve between segments so USB hot-plug/unplug and settings changes are
            // picked up. AUTO mode switches targets with a banner; USB_ONLY mode turns a
            // missing medium into a fatal error.
            File resolved = resolveActiveBaseDir(false);
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            baseDir = resolved;

            // Read every iteration so settings edits take effect between segments. USB and
            // internal storage carry separate retention limits.
            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs, activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmss");
            boolean startedAnyCamera = recordClip(baseDir, segmentMs, baseName, keepSegments);
            if (!startedAnyCamera) {
                // A clip cut short because the factory camera asked for the devices often fails
                // to finalise: the muxer never writes its index and the file is unreadable. That
                // costs one segment, and nothing more. Treating it as fatal ended the session,
                // so nothing was left alive to resume when the 360 view closed — the recording
                // simply never came back.
                if (oemPauseRequested || segmentStopRequested) {
                    DevRuntimeLog.add("RecordingService",
                            "segment lost to the camera hand-off; waiting to resume");
                    continue;
                }
                if (isRecoverableUsbFailure()) {
                    // AUTO mode: the segment failed because USB died. The next loop pass
                    // re-resolves to internal storage and shows the fallback banner.
                    continue;
                }
                endedWithFatalError = true;
                break;
            }
            onSegmentCompleted(baseDir, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);

            // Check whether recording has been disabled in prefs.
            enabled = prefs.getBoolean(DashcamSettings.KEY_ENABLED, false);
            if (!enabled)
                break;
        }

        worker = null;
        sWorkerActive = false;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private void recordFutureOnlyEventLoop() {
        int segmentSec = DashcamSettings.getSegmentDurationSec();
        if (segmentSec <= 0) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            futureOnlyEventSession = false;
            worker = null;
            stopSelf();
            return;
        }

        File baseDir = resolveActiveBaseDir(true);
        if (baseDir == null) {
            futureOnlyEventSession = false;
            worker = null;
            stopSelf();
            return;
        }

        long segmentMs = segmentSec * 1000L;
        boolean endedWithFatalError = false;
        int remainingSegments = FUTURE_ONLY_EVENT_SEGMENTS;

        while (!stopRequested && remainingSegments > 0) {
            File resolved = resolveActiveBaseDir(false);
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            baseDir = resolved;

            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs(), activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmss");
            boolean startedAnyCamera = recordClip(baseDir, segmentMs, baseName, keepSegments);
            if (!startedAnyCamera) {
                if (isRecoverableUsbFailure()) {
                    continue;
                }
                endedWithFatalError = true;
                break;
            }
            onSegmentCompleted(baseDir, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);
            remainingSegments--;
        }

        futureOnlyEventSession = false;
        worker = null;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private boolean recordClip(File baseDir, long durationMs, String baseName, int keepSegments) {
        SharedPreferences prefs = prefs();
        int recordingFps = DashcamSettings.getRecordingFps(prefs);
        String signature = DashcamSettings.getRecordingSignature(prefs);
        boolean showSpeed = DashcamSettings.shouldShowSpeed(prefs);
        int cameraMask = DashcamSettings.getRecordingCameraMask(prefs);
        int selectedCameraCount = DashcamSettings.getRecordingCameraCount(cameraMask);
        File outputFile = new File(baseDir, baseName + ".mp4");
        boolean started = CameraProbe.startCombinedMp4Record(
                outputFile.getAbsolutePath(),
                CELL_WIDTH,
                CELL_HEIGHT,
                recordingFps,
                DashcamSettings.getRecordingBitrateBps(prefs),
                signature,
                showSpeed,
                cameraMask);

        if (!started) {
            discardUnreadable(outputFile);
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_GRID_START_FAILED);
            return false;
        }

        publishStatus(STATUS_RECORDING, selectedCameraCount, TOTAL_CAMERAS, "");

        long start = SystemClock.elapsedRealtime();
        while (!stopRequested
                && !segmentStopRequested
                && (SystemClock.elapsedRealtime() - start) < durationMs) {
            if (showSpeed) {
                CameraProbe.updateCombinedRecordingSpeed(VehicleSpeedReader.readSpeedKmh());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }

        if (!CameraProbe.stopCombinedMp4Record()) {
            // The muxer never closed, so whatever is on disk has no index and no player will
            // open it. Leaving it would also cost a retention slot, pushing out a clip that can
            // actually be watched.
            discardUnreadable(outputFile);
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_GRID_STOP_TIMEOUT);
            return false;
        }

        return true;
    }

    /** Removes a clip the encoder never finished, and says so. */
    private void discardUnreadable(File outputFile) {
        try {
            if (outputFile != null && outputFile.exists()) {
                long size = outputFile.length();
                if (outputFile.delete()) {
                    DevRuntimeLog.add("RecordingService",
                            "discarded unreadable clip " + outputFile.getName()
                                    + " (" + size + " bytes)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not discard " + outputFile, t);
        }
    }

    private boolean waitForOemPauseToClear(SharedPreferences prefs) {
        boolean announcedPause = false;
        while (!stopRequested
                && prefs.getBoolean(DashcamSettings.KEY_ENABLED, false)
                && oemPauseRequested) {
            segmentStopRequested = true;
            if (!announcedPause) {
                publishStatus(STATUS_PAUSED_OEM, 0, TOTAL_CAMERAS, "");
                announcedPause = true;
            }
            synchronized (stateLock) {
                try {
                    stateLock.wait(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (stopRequested || !prefs.getBoolean(DashcamSettings.KEY_ENABLED, false)) {
            return false;
        }
        segmentStopRequested = false;
        if (announcedPause) {
            publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        }
        return true;
    }

    /**
     * Second detector, restored from upstream's SignalService.
     *
     * The broadcasts are the fast path but not a guarantee: a route into the factory camera we
     * have not mapped, or a broadcast that arrives late, leaves the dashcam holding the
     * devices while the AVM sits on screen showing nothing. Watching which app is actually in
     * front catches every route, because it looks at the outcome instead of the signal. It is
     * slower than a broadcast, so it is a net, not a replacement.
     *
     * It also covers the other half: if the AVM goes away without sending ACTION_STOP — a
     * crash, a force-stop — nothing would otherwise resume recording.
     */
    private void startOemWatch() {
        scheduleOemPoll(++oemWatchGeneration);
    }

    private void scheduleOemPoll(int generation) {
        try {
            oemPollExecutor.schedule(
                    () -> pollOemForeground(generation), OEM_POLL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The service is on its way out. Losing the hand-off is a worse turn for the factory
            // camera, not a reason to take the process down with us.
            DevRuntimeLog.add("RecordingService", "oem watch not scheduled: " + e);
        }
    }

    /**
     * One look at what is on screen, and the hand-off that follows from it.
     *
     * <p>Reschedules itself at the end rather than running on a fixed rate: a poll that overran
     * would otherwise queue up behind itself, and a burst of catching-up polls is the last thing
     * a camera hand-off needs.
     */
    private void pollOemForeground(int generation) {
        if (generation != oemWatchGeneration || worker == null || stopRequested) {
            return;
        }
        try {
            if (UiPrefs.isOemAvmCoexistEnabled(prefs())) {
                    // Synchronous, and current. This is the background thread the watch runs on,
                    // so the binder call costs the main thread nothing and the answer is the one
                    // that is true right now, which is the only kind worth acting on.
                boolean front = isOemAvmInForeground();
                if (front != lastForeground) {
                    // Only on change: at one poll a second, logging every result would bury
                    // everything else in the runtime log.
                    DevRuntimeLog.add("RecordingService",
                            "oem foreground=" + front + " (paused=" + oemPauseRequested + ")");
                    lastForeground = front;
                }
                long now = System.currentTimeMillis();
                if (front) {
                    oemGoneSinceMs = 0L;
                    if (oemForegroundSinceMs == 0L) {
                        oemForegroundSinceMs = now;
                    }
                    if (!oemPauseRequested) {
                        // The same gate OemAvmReceiver applies to the broadcast, applied here
                        // too - because here is where it actually happens. The broadcast never
                        // arrives on this vehicle, so the speed threshold shown in the settings
                        // was being honoured only on a path that never runs: the promise that
                        // the dashcam does not let go of the cameras while the car is moving was
                        // written on the screen and enforced nowhere.
                        //
                        // Read only at this moment rather than on every poll: five system
                        // property reads a second to answer a question that matters a few times
                        // a drive is a poor trade.
                        int speedKmh = VehicleSpeedReader.readSpeedKmh();
                        int maxSpeedKmh = UiPrefs.getDevOemAvmMaxSpeedKmh(prefs());
                        if (speedKmh > maxSpeedKmh) {
                            DevRuntimeLog.add("RecordingService",
                                    "oem in foreground at " + speedKmh + " km/h (threshold "
                                            + maxSpeedKmh + "); keeping the cameras");
                            oemForegroundSinceMs = 0L;
                        } else {
                            DevRuntimeLog.add("RecordingService", "oem in foreground without a broadcast");
                            pausedSinceMs = now;
                            beginOemPauseNow();
                            publishStatus(STATUS_PAUSED_OEM, 0, TOTAL_CAMERAS, "");
                        }
                    }
                } else if (oemPauseRequested) {
                    if (oemGoneSinceMs == 0L) {
                        oemGoneSinceMs = now;
                    }
                    // How long it stayed decides how long we stay out of the way.
                    //
                    // We only find out the factory app wants the cameras once it is already on
                    // screen, and by then it has tried to open them and found us holding them.
                    // So its first attempt fails, it closes itself within a second, and if we
                    // take the cameras straight back the second press fails for the same reason.
                    // That is the "press it twice and nothing happens" on the car.
                    //
                    // A short visit is therefore read as a failed attempt, and the cameras stay
                    // free long enough for the next press to find them. A real session ended by
                    // the driver gets the cameras back promptly instead.
                    long onScreenMs = Math.max(0L, oemGoneSinceMs - oemForegroundSinceMs);
                    boolean failedAttempt = onScreenMs < OEM_FAILED_VISIT_MS;
                    long holdMs = failedAttempt ? OEM_RETRY_GRACE_MS : OEM_RESUME_DELAY_MS;
                    if (now - oemGoneSinceMs >= holdMs) {
                        DevRuntimeLog.add("RecordingService", "oem gone after " + onScreenMs
                                + "ms" + (failedAttempt ? " (failed attempt)" : "") + "; resuming");
                        resumeAfterOemRequest(this);
                    }
                }
                // Last resort. Whatever went wrong - a signal we never saw, a foreground reading
                // that stays stale, a broadcast that never came - a dashcam that stays paused is
                // a dashcam that is not recording. Contending briefly with the factory camera is
                // the lesser failure.
                //
                // But not while the factory app is demonstrably still on screen. A long 360
                // session is indistinguishable from a stuck pause if the only thing consulted
                // is how long the pause has lasted, and on 20 September 2026 a two-and-a-half
                // minute session became a loop: every sixty seconds we took the cameras back,
                // opened a segment the hand-off immediately killed, discarded it at 3223 bytes
                // and paused again. The poll already knows the answer - `front` was read a few
                // lines up - so the fallback now only covers the case it was written for, a
                // pause with nothing on the other end of it.
                if (oemPauseRequested && !front && pausedSinceMs > 0
                        && System.currentTimeMillis() - pausedSinceMs > MAX_OEM_PAUSE_MS) {
                    DevRuntimeLog.add("RecordingService",
                            "paused for over " + (MAX_OEM_PAUSE_MS / 1000) + "s; forcing resume");
                    pausedSinceMs = 0L;
                    oemForegroundSinceMs = 0L;
                    oemGoneSinceMs = 0L;
                    resumeAfterOemRequest(this);
                }
            }
        } catch (Throwable t) {
            // Nothing here is worth the process. A missed poll is a second of contention with
            // the factory camera; an exception out of this thread would be the end of recording.
            Log.w(TAG, "oem watch", t);
        }
        scheduleOemPoll(generation);
    }

    /**
     * getRunningTasks is restricted, but this app is the system user, which is the same reason
     * it can open the camera devices at all.
     */
    private boolean isOemAvmInForeground() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ActivityManager.RunningTaskInfo top = tasks.get(0);
            return top.topActivity != null
                    && OEM_AVM_PACKAGE.equals(top.topActivity.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A green badge is a claim, and this is what checks it. The worker publishes RECORDING once
     * and then only updates between segments, so a loop that stops producing them leaves the
     * claim standing. If nothing completes for three segment lengths, say so.
     */
    /**
     * Runs for as long as the service does, not just for as long as the loop does.
     *
     * Today's failure was a service that came back from a sticky restart with no worker: the
     * loop-bound watchdog could not fire because the loop had never started. A supervisor that
     * outlives the loop is the only thing that catches the states nobody thought of.
     */
    /** Standby closed the clip; the supervisor must not undo that until the screen is back. */
    private volatile boolean pausedForScreenOff = false;
    private BroadcastReceiver screenReceiver;

    /**
     * Below this the car counts as stopped for the purpose of a screen going dark. Not zero:
     * the property can read a km/h or two at a standstill, and this decision only has to tell
     * parked from driving.
     */
    private static final int SCREEN_OFF_MAX_SPEED_KMH = 3;

    private final Runnable supervisor = new Runnable() {
        @Override
        public void run() {
            try {
                if (!stopRequested && !usbEjectInProgress && !pausedForScreenOff && worker == null
                        && prefs().getBoolean(DashcamSettings.KEY_ENABLED, false)) {
                    DevRuntimeLog.add("RecordingService", "supervisor: enabled but no worker; restarting");
                    stopRequested = false;
                    startForeground(NOTIF_ID,
                            buildNotification(getString(R.string.notification_recording_starting)));
                    publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
                    worker = new Thread(RecordingService.this::recordLoop, "RecordingServiceWorker");
                    worker.start();
                }
            } catch (Throwable t) {
                Log.w(TAG, "supervisor", t);
            }
            mainHandler.postDelayed(this, SUPERVISOR_PERIOD_MS);
        }
    };

    private void startWatchdog() {
        lastSegmentCompletedMs = System.currentTimeMillis();
        mainHandler.removeCallbacks(watchdog);
        // Nothing else is cancelled here. Two things used to be, and both were wrong:
        //
        // The supervisor is meant to outlive the loop - that is its entire job - and removing it
        // at the start of every session left nothing watching once the loop was up.
        //
        // probeExecutor.shutdownNow() was worse. The executor is a field of the service, not of
        // the session, and shutting it down is permanent. A second later oemForegroundWatch
        // called execute() on it, RejectedExecutionException came back on the main thread, and
        // the process died; START_STICKY brought it back, the loop started again, and it died
        // again two seconds later. That was the crash loop on the car. The executor is shut down
        // in onDestroy, where a service-scoped resource belongs.
        mainHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (worker == null || stopRequested) {
                return;
            }
            long stale = System.currentTimeMillis() - lastSegmentCompletedMs;
            long limit = 3L * Math.max(1, DashcamSettings.getSegmentDurationSec()) * 1000L;
            if (!oemPauseRequested
                    && STATUS_RECORDING.equals(prefs().getString(KEY_STATUS, STATUS_OFF))
                    && stale > limit) {
                DevRuntimeLog.add("RecordingService",
                        "watchdog: no segment for " + (stale / 1000) + "s");
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STALLED);
            }
            mainHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    private void onSegmentCompleted(File baseDir, String baseName, long startMs, long endMs, int keepSegments) {
        lastSegmentCompletedMs = System.currentTimeMillis();
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            long segmentOrdinal = ++completedSegmentCount;
            recentSegments.add(new SegmentInfo(segmentOrdinal, baseName, startMs, endMs,
                    baseDir.getAbsolutePath()));
            while (recentSegments.size() > keepSegments + 6) {
                recentSegments.remove(0);
            }
            copyJobs = collectEventCopyJobsLocked(segmentOrdinal);
            Set<String> protectedBases = collectProtectedBasesLocked();
            cleanupOldSegments(baseDir, keepSegments, protectedBases);
            persistEventStateLocked();
        }
        enqueueEventCopyJobs(copyJobs);
    }

    private boolean startFutureOnlyEventSession() {
        if (resolveActiveBaseDir(true) == null) {
            return false;
        }
        if (!armEventCapture(0, FUTURE_ONLY_EVENT_SEGMENTS - 1)) {
            return false;
        }
        futureOnlyEventSession = true;
        stopRequested = false;
        segmentStopRequested = false;
        oemPauseRequested = false;
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
        publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        worker = new Thread(this::recordFutureOnlyEventLoop, "RecordingServiceFutureEventWorker");
        worker.start();
        return true;
    }

    private boolean armEventCapture(int segmentsBeforeCurrent, int segmentsAfterCurrent) {
        long now = System.currentTimeMillis();
        String eventBaseName = "event_" + makeTimestampBase(now, "yyMMddHHmmssSSS");
        File eventsBaseDir = getEventsBaseDir();
        if (eventsBaseDir == null || !eventsBaseDir.canWrite()) {
            DevRuntimeLog.add("RecordingService", "Event capture failed: events base dir unavailable or not writable");
            Log.e(TAG, "Failed to arm event capture because events base dir is unavailable or not writable");
            notifyEventStorageFailure();
            return false;
        }
        File eventDir = new File(eventsBaseDir, eventBaseName);
        if (!ensureDirectoryExists(eventDir, "event dir")) {
            DevRuntimeLog.add("RecordingService", "Event capture failed: mkdir " + eventDir.getAbsolutePath());
            Log.e(TAG, "Failed to create event dir " + eventDir.getAbsolutePath());
            notifyEventStorageFailure();
            return false;
        }
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            long currentSegmentOrdinal = completedSegmentCount + 1L;
            long firstSegmentOrdinal = Math.max(1L, currentSegmentOrdinal - Math.max(0, segmentsBeforeCurrent));
            long lastSegmentOrdinal = currentSegmentOrdinal + Math.max(0, segmentsAfterCurrent);
            pendingEventRequests.add(new EventCaptureRequest(
                    eventBaseName,
                    firstSegmentOrdinal,
                    lastSegmentOrdinal
            ));
            trimOldEventDirsLocked(eventsBaseDir);
            copyJobs = collectEventCopyJobsLocked(completedSegmentCount);
            persistEventStateLocked();
            DevRuntimeLog.add(
                    "RecordingService",
                    "Event armed: " + eventBaseName
                            + " ordinals " + firstSegmentOrdinal
                            + "-" + lastSegmentOrdinal);
        }
        enqueueEventCopyJobs(copyJobs);
        return true;
    }

    private List<EventCopyJob> collectEventCopyJobsLocked(long completedThroughOrdinal) {
        List<EventCopyJob> jobs = new ArrayList<>();
        List<EventCaptureRequest> completed = new ArrayList<>();
        for (EventCaptureRequest request : pendingEventRequests) {
            List<SegmentCopyRef> segments = new ArrayList<>();
            for (SegmentInfo segment : recentSegments) {
                if (segment.ordinal < request.firstSegmentOrdinal
                        || segment.ordinal > request.lastSegmentOrdinal) {
                    continue;
                }
                if (!request.copiedBaseNames.contains(segment.baseName)
                        && request.inFlightBaseNames.add(segment.baseName)) {
                    segments.add(new SegmentCopyRef(segment.baseName, segment.sourceDirPath));
                }
            }
            if (!segments.isEmpty()) {
                jobs.add(new EventCopyJob(request.eventBaseName, segments));
            }
            if (isRequestCompleteLocked(request, completedThroughOrdinal)) {
                completed.add(request);
            }
        }
        pendingEventRequests.removeAll(completed);
        return jobs;
    }

    private Set<String> collectProtectedBasesLocked() {
        Set<String> protectedBases = new HashSet<>();
        for (EventCaptureRequest request : pendingEventRequests) {
            for (SegmentInfo segment : recentSegments) {
                if (segment.ordinal >= request.firstSegmentOrdinal
                        && segment.ordinal <= request.lastSegmentOrdinal) {
                    protectedBases.add(segment.baseName);
                }
            }
        }
        return protectedBases;
    }

    private void enqueueEventCopyJobs(List<EventCopyJob> jobs) {
        for (EventCopyJob job : jobs) {
            eventCopyExecutor.execute(() -> copyEventSegments(job));
        }
    }

    private boolean isRequestCompleteLocked(EventCaptureRequest request, long completedThroughOrdinal) {
        if (completedThroughOrdinal < request.lastSegmentOrdinal) {
            return false;
        }
        for (SegmentInfo segment : recentSegments) {
            if (segment.ordinal < request.firstSegmentOrdinal
                    || segment.ordinal > request.lastSegmentOrdinal) {
                continue;
            }
            if (!request.copiedBaseNames.contains(segment.baseName)
                    || request.inFlightBaseNames.contains(segment.baseName)) {
                return false;
            }
        }
        return true;
    }

    private void copyEventSegments(EventCopyJob job) {
        if (job.segments.isEmpty()) {
            Log.w(TAG, "Skipping empty event copy job for " + job.eventBaseName);
            onEventCopyFinished(job, new ArrayList<>());
            return;
        }
        File eventsBaseDir = getEventsBaseDir();
        if (eventsBaseDir == null || !eventsBaseDir.canWrite()) {
            DevRuntimeLog.add("RecordingService", "Event copy failed: events base dir unavailable or not writable");
            Log.e(TAG, "Failed to copy event segments because events base dir is unavailable or not writable");
            mainHandler.post(this::notifyEventStorageFailure);
            onEventCopyFinished(job, new ArrayList<>());
            return;
        }
        File eventDir = new File(eventsBaseDir, job.eventBaseName);
        if (!ensureDirectoryExists(eventDir, "event dir")) {
            DevRuntimeLog.add("RecordingService", "Event copy failed: mkdir " + eventDir.getAbsolutePath());
            Log.e(TAG, "Failed to create event dir " + eventDir.getAbsolutePath());
            mainHandler.post(this::notifyEventStorageFailure);
            onEventCopyFinished(job, new ArrayList<>());
            return;
        }
        DevRuntimeLog.add("RecordingService", "Event copy: " + job.eventBaseName + " files " + job.segments.size());
        List<String> copiedBaseNames = new ArrayList<>();
        for (SegmentCopyRef ref : job.segments) {
            // Read each segment from the root it was recorded into — after a USB→internal
            // fallback an event can span both roots. Legacy persisted entries without a
            // source path fall back to the currently active root.
            File sourceDir = ref.sourceDirPath.isEmpty()
                    ? getActiveRecordsBaseDir()
                    : new File(ref.sourceDirPath);
            if (copySegmentGroup(sourceDir, eventDir, ref.baseName)) {
                copiedBaseNames.add(ref.baseName);
            }
        }
        onEventCopyFinished(job, copiedBaseNames);
    }

    private void onEventCopyFinished(EventCopyJob job, List<String> copiedBaseNames) {
        synchronized (eventLock) {
            EventCaptureRequest request = findPendingEventRequestLocked(job.eventBaseName);
            if (request == null) {
                return;
            }
            request.inFlightBaseNames.removeAll(job.baseNames());
            request.copiedBaseNames.addAll(copiedBaseNames);
            if (isRequestCompleteLocked(request, completedSegmentCount)) {
                pendingEventRequests.remove(request);
            }
            persistEventStateLocked();
        }
    }

    private EventCaptureRequest findPendingEventRequestLocked(String eventBaseName) {
        for (EventCaptureRequest request : pendingEventRequests) {
            if (request.eventBaseName.equals(eventBaseName)) {
                return request;
            }
        }
        return null;
    }

    private boolean copySegmentGroup(File sourceDir, File targetDir, String baseName) {
        File combinedSource = new File(sourceDir, baseName + ".mp4");
        if (combinedSource.exists()) {
            return copyFile(combinedSource, new File(targetDir, combinedSource.getName()));
        }

        char[] suffixes = new char[] { 'F', 'R', 'X', 'Y' };
        boolean copiedAny = false;
        for (char suffix : suffixes) {
            File source = new File(sourceDir, baseName + "_" + suffix + ".mp4");
            if (!source.exists()) {
                continue;
            }
            File target = new File(targetDir, source.getName());
            copiedAny |= copyFile(source, target);
        }
        return copiedAny;
    }

    private boolean copyFile(File source, File target) {
        byte[] buffer = new byte[64 * 1024];
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (temp.exists() && !temp.delete()) {
            Log.w(TAG, "Could not delete stale temp file " + temp.getAbsolutePath());
        }
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(temp)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
            if (target.exists() && !target.delete()) {
                throw new IOException("delete target failed: " + target.getAbsolutePath());
            }
            if (!temp.renameTo(target)) {
                throw new IOException("rename failed: " + temp.getAbsolutePath() + " -> " + target.getAbsolutePath());
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to copy " + source.getAbsolutePath() + " -> " + target.getAbsolutePath(), t);
            // noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return false;
    }

    private void cleanupOldSegments(File baseDir, int keepSegments, Set<String> protectedBases) {
        File[] files = baseDir.listFiles();
        if (files == null)
            return;

        // baseName => earliestModified
        Map<String, Long> groupTime = new HashMap<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".mp4"))
                continue;
            int underscore = name.indexOf('_');
            String base;
            if (underscore > 0) {
                base = name.substring(0, underscore);
            } else {
                base = name.substring(0, name.length() - 4);
            }
            long t = f.lastModified();
            groupTime.merge(base, t, Math::min);
        }

        List<Map.Entry<String, Long>> groups = new ArrayList<>(groupTime.entrySet());
        groups.sort(Comparator.comparingLong(Map.Entry::getValue));

        if (groups.size() <= keepSegments)
            return;
        int deleteCount = groups.size() - keepSegments;

        int deleted = 0;
        for (int i = 0; i < groups.size() && deleted < deleteCount; i++) {
            String base = groups.get(i).getKey();
            if (protectedBases != null && protectedBases.contains(base)) {
                continue;
            }
            File combinedFile = new File(baseDir, base + ".mp4");
            if (combinedFile.exists()) {
                // noinspection ResultOfMethodCallIgnored
                combinedFile.delete();
            } else {
                char[] suffixes = new char[] { 'F', 'R', 'X', 'Y' };
                for (char s : suffixes) {
                    File f = new File(baseDir, base + "_" + s + ".mp4");
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            deleted++;
        }
    }

    private void trimOldEventDirsLocked(File eventsBaseDir) {
        if (eventsBaseDir == null) return;
        File[] files = eventsBaseDir.listFiles();
        if (files == null) return;
        List<File> eventDirs = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("event_")) {
                eventDirs.add(f);
            }
        }
        int maxRetained = DashcamStorageManager.getActiveMaxRetainedEventDirs(prefs(), activeBaseIsUsb);
        if (eventDirs.size() <= maxRetained) return;
        // event_<yyMMddHHmmssSSS> sorts chronologically by name.
        eventDirs.sort(Comparator.comparing(File::getName));
        Set<String> pendingNames = new HashSet<>();
        for (EventCaptureRequest req : pendingEventRequests) {
            pendingNames.add(req.eventBaseName);
        }
        int toDelete = eventDirs.size() - maxRetained;
        int deleted = 0;
        for (File dir : eventDirs) {
            if (deleted >= toDelete) break;
            // Never delete a dir whose copy jobs may still write into it.
            if (pendingNames.contains(dir.getName())) continue;
            if (deleteDirRecursive(dir)) {
                DevRuntimeLog.add("RecordingService", "Event cap: deleted " + dir.getName());
                deleted++;
            } else {
                Log.w(TAG, "Failed to delete old event dir " + dir.getAbsolutePath());
            }
        }
    }

    private boolean deleteDirRecursive(File f) {
        if (f == null) return false;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteDirRecursive(c);
                }
            }
        }
        return f.delete();
    }

    private File getEventsBaseDir() {
        File dir = new File(getActiveRecordsBaseDir(), "events");
        return ensureDirectoryExists(dir, "events base dir") ? dir : null;
    }

    private void notifyEventStorageFailure() {
        DashcamNotice.showRecordingError(
                this,
                R.string.dashcam_recording_error_overlay_subtitle_storage,
                R.string.notification_dashcam_recording_error_storage_text);
    }

    /**
     * Resolves the recording target via {@link DashcamStorageManager} and updates the
     * service-wide active dir. Returns null when no usable target exists (which also
     * publishes the matching error status).
     *
     * @param initial true on the first resolution of a recording session — suppresses
     *                the USB↔internal transition banner that only makes sense mid-session.
     */
    /** Backoff for the wait below: about half a minute in all, most of it in the first seconds. */
    private static final long[] STORAGE_WAIT_MS = {0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L};

    /**
     * The records directory, waiting for it rather than giving up the first time it is not there.
     *
     * <p>At boot the head unit is still mounting the USB stick while this service is already
     * starting - the report from the car caught Android running a filesystem check on it in the
     * same minute. The write test fails for a few seconds and then starts passing.
     *
     * <p>That was treated as fatal: the service stopped, taking the supervisor with it, and
     * nothing recorded for the rest of the drive unless the driver happened to look at the badge
     * and work out that the switch needed turning off and on again. The first failure is not an
     * answer, it is a "not yet".
     */
    private File awaitRecordsBaseDir() {
        for (int attempt = 0; attempt < STORAGE_WAIT_MS.length; attempt++) {
            if (stopRequested || !prefs().getBoolean(DashcamSettings.KEY_ENABLED, false)) {
                return null;
            }
            long wait = STORAGE_WAIT_MS[attempt];
            if (wait > 0) {
                synchronized (stateLock) {
                    try {
                        stateLock.wait(wait);
                    } catch (InterruptedException ignored) {
                        // Interrupted means somebody wants us to stop or to hand the cameras
                        // over; either way the loop above re-reads the state and decides.
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                Thread.interrupted();
            }
            boolean lastTry = attempt == STORAGE_WAIT_MS.length - 1;
            File dir = resolveActiveBaseDir(true, lastTry);
            if (dir != null) {
                if (attempt > 0) {
                    DevRuntimeLog.add("RecordingService",
                            "storage ready on attempt " + (attempt + 1));
                }
                return dir;
            }
        }
        return null;
    }

    private File resolveActiveBaseDir(boolean initial) {
        return resolveActiveBaseDir(initial, true);
    }

    private File resolveActiveBaseDir(boolean initial, boolean announceFailure) {
        DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(this);
        if (res.baseDir == null) {
            if (!announceFailure) {
                // A retry is still in hand; saying "error" now would only make the badge flicker
                // red and back while the volume finishes mounting.
                return null;
            }
            DevRuntimeLog.add("RecordingService", "Storage resolve failed: " + res.usbState);
            boolean readOnly = res.usbState == DashcamStorageManager.UsbState.NOT_WRITABLE
                    || res.usbState == DashcamStorageManager.UsbState.WRITE_TEST_FAILED;
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS,
                    readOnly ? ERROR_USB_READ_ONLY : ERROR_USB_STORAGE);
            return null;
        }
        if (!ensureDirectoryExists(res.baseDir, "records base dir") || !res.baseDir.canWrite()) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
            return null;
        }
        boolean wasUsb = activeBaseIsUsb;
        boolean hadPrevious = activeBaseDir != null;
        activeBaseDir = res.baseDir;
        activeBaseIsUsb = res.usingUsb;
        if (!initial && hadPrevious) {
            if (wasUsb && !res.usingUsb) {
                DevRuntimeLog.add("RecordingService",
                        "USB storage lost (" + res.usbState + ") => falling back to internal");
                DashcamNotice.showRecordingError(
                        this,
                        R.string.dashcam_recording_error_overlay_subtitle_usb_fallback,
                        R.string.notification_dashcam_recording_error_usb_fallback_text);
            } else if (!wasUsb && res.usingUsb) {
                DevRuntimeLog.add("RecordingService", "USB storage available again => switching back to USB");
            }
        }
        return res.baseDir;
    }

    /**
     * Called after a failed segment. Decides whether the failure was caused by the USB medium
     * (as opposed to the camera pipeline) and whether the configured mode allows recovering
     * from it by falling back to internal storage.
     */
    private boolean isRecoverableUsbFailure() {
        if (!activeBaseIsUsb) {
            return false;
        }
        if (DashcamStorageManager.isUsbStillWritable(activeBaseDir)) {
            // Storage is fine — this is a genuine camera/encoder failure.
            return false;
        }
        int target = DashcamStorageManager.getStorageTarget(prefs());
        if (target == DashcamStorageManager.TARGET_USB_ONLY) {
            DevRuntimeLog.add("RecordingService", "USB storage failed in USB-only mode => stopping");
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_USB_STORAGE);
            return false;
        }
        DevRuntimeLog.add("RecordingService", "USB storage failed in auto mode => retry on internal");
        return true;
    }

    private File getActiveRecordsBaseDir() {
        File dir = activeBaseDir;
        return dir != null ? dir : DashcamSettings.getRecordsBaseDir(this);
    }

    private boolean ensureDirectoryExists(File dir, String label) {
        if (dir == null) {
            return false;
        }
        if (dir.exists()) {
            if (dir.isDirectory()) {
                return true;
            }
            Log.e(TAG, label + " exists but is not a directory: " + dir.getAbsolutePath());
            return false;
        }
        if (dir.mkdirs()) {
            return true;
        }
        boolean created = dir.exists() && dir.isDirectory();
        if (!created) {
            Log.e(TAG, "Failed to create " + label + ": " + dir.getAbsolutePath());
        }
        return created;
    }

    private String makeTimestampBase(long epochMs, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(epochMs);
    }

    private void shutdownRecordingService() {
        shutdownRecordingServiceWithoutStopSelf();
        stopForeground(true);
        stopSelf();
    }

    private void shutdownRecordingServiceWithoutStopSelf() {
        stopRequested = true;
        segmentStopRequested = true;
        oemPauseRequested = false;
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
        publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        try {
            for (int s = 0; s < 4; s++) {
                CameraProbe.stopMp4Record(s);
            }
            CameraProbe.stopCombinedMp4Record();
        } catch (Throwable ignored) {
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private boolean awaitShutdownQuiescence() {
        Thread workerThread = worker;
        if (workerThread != null) {
            try {
                workerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        eventCopyExecutor.shutdown();
        try {
            return eventCopyExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void stopServiceIfNotEjecting() {
        if (usbEjectInProgress) {
            return;
        }
        stopForeground(true);
        stopSelf();
    }

    private void broadcastUsbEjectReady(boolean safeToRemove, int messageRes) {
        Intent intent = new Intent(ACTION_USB_EJECT_READY);
        // Keep it inside the app, as the status broadcast already does: the only listener is
        // our own activity, and an implicit broadcast is one other apps can read.
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_USB_EJECT_SAFE_TO_REMOVE, safeToRemove);
        intent.putExtra(EXTRA_USB_EJECT_MESSAGE_RES, messageRes);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return b.build();
    }

    public static final class PersistedStatus {
        public final String status;
        public final int activeCameras;
        public final int totalCameras;
        public final String lastError;

        PersistedStatus(String status, int activeCameras, int totalCameras, String lastError) {
            this.status = status;
            this.activeCameras = activeCameras;
            this.totalCameras = totalCameras;
            this.lastError = lastError;
        }
    }

    public static PersistedStatus readPersistedStatus(SharedPreferences prefs) {
        return new PersistedStatus(
                prefs.getString(KEY_STATUS, STATUS_OFF),
                prefs.getInt(KEY_ACTIVE_CAMERAS, 0),
                prefs.getInt(KEY_TOTAL_CAMERAS, TOTAL_CAMERAS),
                prefs.getString(KEY_LAST_ERROR, ""));
    }

    public static String formatStatusText(Context context, String status, int activeCameras, int totalCameras, String lastError) {
        if (status == null || STATUS_OFF.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_off);
        }
        if (STATUS_RECORDING.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_recording, activeCameras, totalCameras);
        }
        if (STATUS_PAUSED_OEM.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_paused_oem);
        }
        if (STATUS_STARTING.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_starting);
        }
        String error = lastError == null || lastError.trim().isEmpty() ? status : lastError.trim();
        return context.getString(R.string.settings_dashcam_status_error, error);
    }

    /**
     * If the prefs say we were recording but the service isn't actually running (e.g. crash,
     * OOM kill), reset the persisted state so the UI doesn't show a stale "RECORDING" pill.
     */
    public static void resetPersistedStatusIfStale(SharedPreferences prefs) {
        String status = prefs.getString(KEY_STATUS, STATUS_OFF);
        // A live service is not a recording one. Checking only isRunning() let the badge stay
        // green after a sticky restart, which is the worst thing a status can do.
        if (status == null || STATUS_OFF.equals(status)
                || (isRunning() && isRecordingActive())) return;
        prefs.edit()
                .putString(KEY_STATUS, STATUS_OFF)
                .putInt(KEY_ACTIVE_CAMERAS, 0)
                .putInt(KEY_TOTAL_CAMERAS, TOTAL_CAMERAS)
                .putString(KEY_LAST_ERROR, "")
                .apply();
    }

    private void publishCurrentStatus() {
        PersistedStatus s = readPersistedStatus(prefs());
        publishStatus(s.status, s.activeCameras, s.totalCameras, s.lastError);
    }

    private void publishStatus(String status, int activeCameras, int totalCameras, String lastError) {
        if (status == null)
            status = STATUS_OFF;
        if (lastError == null)
            lastError = "";
        prefs().edit()
                .putString(KEY_STATUS, status)
                .putInt(KEY_ACTIVE_CAMERAS, Math.max(0, activeCameras))
                .putInt(KEY_TOTAL_CAMERAS, Math.max(0, totalCameras))
                .putString(KEY_LAST_ERROR, lastError)
                .apply();

        String notificationText;
        if (STATUS_RECORDING.equals(status)) {
            notificationText = getString(R.string.notification_recording_status, activeCameras, totalCameras);
        } else if (STATUS_PAUSED_OEM.equals(status)) {
            notificationText = getString(R.string.notification_recording_paused_oem);
        } else if (STATUS_PARTIAL.equals(status) || STATUS_ERROR.equals(status)) {
            notificationText = getString(R.string.notification_recording_error, lastError);
        } else if (STATUS_STARTING.equals(status)) {
            notificationText = getString(R.string.notification_recording_starting);
        } else {
            notificationText = "";
        }

        if (!notificationText.isEmpty()) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(notificationText));
            }
        }

        updateDashcamOverlayState(status, lastError);

        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_ACTIVE_CAMERAS, activeCameras);
        intent.putExtra(EXTRA_TOTAL_CAMERAS, totalCameras);
        intent.putExtra(EXTRA_LAST_ERROR, lastError);
        sendBroadcast(intent);
    }

    private void updateDashcamOverlayState(String status, String lastError) {
        if (STATUS_ERROR.equals(status) || STATUS_PARTIAL.equals(status)) {
            scheduleDelayedErrorOverlay(lastError);
            return;
        }

        cancelPendingErrorOverlay();
        if (errorOverlayShown && STATUS_RECORDING.equals(status)) {
            errorOverlayShown = false;
            DashcamNotice.showRecordingRecovered(this);
        } else if (!STATUS_RECORDING.equals(status)) {
            errorOverlayShown = false;
        }
    }

    private void scheduleDelayedErrorOverlay(String lastError) {
        OverlayMessageSpec spec = mapRecordingError(lastError);
        if (spec == null) {
            cancelPendingErrorOverlay();
            return;
        }
        if (errorOverlayShown
                && pendingErrorSubtitleResId == spec.subtitleResId
                && pendingErrorNotificationResId == spec.notificationTextResId) {
            return;
        }

        pendingErrorSubtitleResId = spec.subtitleResId;
        pendingErrorNotificationResId = spec.notificationTextResId;
        final int generation = ++pendingErrorGeneration;
        mainHandler.removeCallbacksAndMessages(this);
        mainHandler.postAtTime(() -> {
            if (generation != pendingErrorGeneration) {
                return;
            }
            errorOverlayShown = true;
            DashcamNotice.showRecordingError(
                    RecordingService.this,
                    pendingErrorSubtitleResId,
                    pendingErrorNotificationResId);
        }, this, SystemClock.uptimeMillis() + ERROR_OVERLAY_DELAY_MS);
    }

    private void cancelPendingErrorOverlay() {
        pendingErrorGeneration++;
        pendingErrorSubtitleResId = 0;
        pendingErrorNotificationResId = 0;
        mainHandler.removeCallbacksAndMessages(this);
    }

    private OverlayMessageSpec mapRecordingError(String lastError) {
        if (ERROR_STORAGE_NOT_WRITABLE.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_storage,
                    R.string.notification_dashcam_recording_error_storage_text);
        }
        if (ERROR_GRID_START_FAILED.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_start_failed,
                    R.string.notification_dashcam_recording_error_start_failed_text);
        }
        if (ERROR_GRID_STOP_TIMEOUT.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_stop_failed,
                    R.string.notification_dashcam_recording_error_stop_failed_text);
        }
        if (ERROR_STALLED.equals(lastError) || ERROR_LOOP_DIED.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_stalled,
                    R.string.notification_dashcam_recording_error_stalled_text);
        }
        if (ERROR_CRASH_LOOP.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_crash_loop,
                    R.string.notification_dashcam_recording_error_crash_loop_text);
        }
        if (ERROR_USB_READ_ONLY.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_usb_read_only,
                    R.string.notification_dashcam_recording_error_usb_read_only_text);
        }
        if (ERROR_USB_STORAGE.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_usb_unavailable,
                    R.string.notification_dashcam_recording_error_usb_unavailable_text);
        }
        return new OverlayMessageSpec(
                R.string.dashcam_recording_error_overlay_subtitle_generic,
                R.string.notification_dashcam_recording_error_text);
    }

    /**
     * Whether the process is dying faster than it can record.
     *
     * <p>A sticky service whose process dies on start is brought back by Android at once, and
     * dies again. On the car that came to twenty-eight crashes inside a minute, with the app
     * flickering in and out and nothing recorded in between. Coming back once is the right
     * answer; coming back five times in three minutes is a loop, and the only useful thing left
     * to do is stop and say so.
     */
    private boolean crashLoopDetected() {
        SharedPreferences sp = prefs();
        long now = System.currentTimeMillis();
        long windowStart = sp.getLong(KEY_CRASH_WINDOW_START, 0L);
        int inWindow = sp.getInt(KEY_CRASH_WINDOW_COUNT, 0);
        // The head unit sets its clock from the network once it wakes, so time can jump either
        // way. A window that no longer makes sense is started again rather than trusted.
        if (windowStart == 0L || now < windowStart || now - windowStart > CRASH_LOOP_WINDOW_MS) {
            windowStart = now;
            inWindow = 0;
        }
        inWindow++;
        sp.edit()
                .putLong(KEY_CRASH_WINDOW_START, windowStart)
                .putInt(KEY_CRASH_WINDOW_COUNT, inWindow)
                .apply();
        return inWindow >= CRASH_LOOP_LIMIT;
    }

    private SharedPreferences prefs() {
        return UiPrefs.getPrefs(this);
    }

    private void restoreEventState() {
        SharedPreferences prefs = prefs();
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            completedSegmentCount = prefs.getLong(KEY_EVENT_COMPLETED_SEGMENT_COUNT, 0L);
            recentSegments.clear();
            pendingEventRequests.clear();

            try {
                JSONArray segmentsJson = new JSONArray(prefs.getString(KEY_EVENT_RECENT_SEGMENTS, "[]"));
                for (int i = 0; i < segmentsJson.length(); i++) {
                    JSONObject obj = segmentsJson.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    String baseName = obj.optString("baseName", "");
                    long ordinal = obj.optLong("ordinal", -1L);
                    long startMs = obj.optLong("startMs", 0L);
                    long endMs = obj.optLong("endMs", 0L);
                    String sourceDir = obj.optString("sourceDir", "");
                    if (baseName.isEmpty() || ordinal <= 0L) {
                        continue;
                    }
                    recentSegments.add(new SegmentInfo(ordinal, baseName, startMs, endMs, sourceDir));
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to restore recent segment state", t);
                recentSegments.clear();
            }

            try {
                JSONArray requestsJson = new JSONArray(prefs.getString(KEY_EVENT_PENDING_REQUESTS, "[]"));
                for (int i = 0; i < requestsJson.length(); i++) {
                    JSONObject obj = requestsJson.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    String eventBaseName = obj.optString("eventBaseName", "");
                    long firstSegmentOrdinal = obj.optLong("firstSegmentOrdinal", -1L);
                    long lastSegmentOrdinal = obj.optLong("lastSegmentOrdinal", -1L);
                    if (eventBaseName.isEmpty() || firstSegmentOrdinal <= 0L || lastSegmentOrdinal < firstSegmentOrdinal) {
                        continue;
                    }
                    EventCaptureRequest request =
                            new EventCaptureRequest(eventBaseName, firstSegmentOrdinal, lastSegmentOrdinal);
                    JSONArray copiedJson = obj.optJSONArray("copiedBaseNames");
                    if (copiedJson != null) {
                        for (int j = 0; j < copiedJson.length(); j++) {
                            String copiedBaseName = copiedJson.optString(j, "");
                            if (!copiedBaseName.isEmpty()) {
                                request.copiedBaseNames.add(copiedBaseName);
                            }
                        }
                    }
                    pendingEventRequests.add(request);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to restore pending event requests", t);
                pendingEventRequests.clear();
            }
            copyJobs = collectEventCopyJobsLocked(completedSegmentCount);
            persistEventStateLocked();
        }
        enqueueEventCopyJobs(copyJobs);
    }

    private void persistEventStateLocked() {
        JSONArray segmentsJson = new JSONArray();
        for (SegmentInfo segment : recentSegments) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("ordinal", segment.ordinal);
                obj.put("baseName", segment.baseName);
                obj.put("startMs", segment.startMs);
                obj.put("endMs", segment.endMs);
                obj.put("sourceDir", segment.sourceDirPath);
                segmentsJson.put(obj);
            } catch (Throwable ignored) {
            }
        }

        JSONArray requestsJson = new JSONArray();
        for (EventCaptureRequest request : pendingEventRequests) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("eventBaseName", request.eventBaseName);
                obj.put("firstSegmentOrdinal", request.firstSegmentOrdinal);
                obj.put("lastSegmentOrdinal", request.lastSegmentOrdinal);
                JSONArray copiedJson = new JSONArray();
                for (String copiedBaseName : request.copiedBaseNames) {
                    copiedJson.put(copiedBaseName);
                }
                obj.put("copiedBaseNames", copiedJson);
                requestsJson.put(obj);
            } catch (Throwable ignored) {
            }
        }

        prefs().edit()
                .putLong(KEY_EVENT_COMPLETED_SEGMENT_COUNT, completedSegmentCount)
                .putString(KEY_EVENT_RECENT_SEGMENTS, segmentsJson.toString())
                .putString(KEY_EVENT_PENDING_REQUESTS, requestsJson.toString())
                .apply();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sServiceRunning = false;
        sInstance = null;
        sWorkerActive = false;
        stopRequested = true;
        mainHandler.removeCallbacks(watchdog);
        mainHandler.removeCallbacks(supervisor);
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
                // Already gone: the service is being torn down either way.
            }
            screenReceiver = null;
        }
        cancelPendingErrorOverlay();
        oemWatchGeneration++;
        oemPollExecutor.shutdownNow();
        eventCopyExecutor.shutdown();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            eventCopyExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        super.onDestroy();
    }

    private final List<SegmentInfo> recentSegments = new ArrayList<>();
    private final List<EventCaptureRequest> pendingEventRequests = new ArrayList<>();

    private static final class SegmentInfo {
        final long ordinal;
        final String baseName;
        final long startMs;
        final long endMs;
        // Absolute path of the records root this segment was written into. Segments recorded
        // before a USB→internal fallback live in a different root than the currently active
        // one, so event copies must read from here, not from the active dir.
        final String sourceDirPath;

        SegmentInfo(long ordinal, String baseName, long startMs, long endMs, String sourceDirPath) {
            this.ordinal = ordinal;
            this.baseName = baseName;
            this.startMs = startMs;
            this.endMs = endMs;
            this.sourceDirPath = sourceDirPath == null ? "" : sourceDirPath;
        }
    }

    private static final class SegmentCopyRef {
        final String baseName;
        final String sourceDirPath;

        SegmentCopyRef(String baseName, String sourceDirPath) {
            this.baseName = baseName;
            this.sourceDirPath = sourceDirPath == null ? "" : sourceDirPath;
        }
    }

    private static final class EventCaptureRequest {
        final String eventBaseName;
        final long firstSegmentOrdinal;
        final long lastSegmentOrdinal;
        final Set<String> copiedBaseNames = new HashSet<>();
        final Set<String> inFlightBaseNames = new HashSet<>();

        EventCaptureRequest(String eventBaseName, long firstSegmentOrdinal, long lastSegmentOrdinal) {
            this.eventBaseName = eventBaseName;
            this.firstSegmentOrdinal = firstSegmentOrdinal;
            this.lastSegmentOrdinal = lastSegmentOrdinal;
        }
    }

    private static final class EventCopyJob {
        final String eventBaseName;
        final List<SegmentCopyRef> segments;

        EventCopyJob(String eventBaseName, List<SegmentCopyRef> segments) {
            this.eventBaseName = eventBaseName;
            this.segments = segments;
        }

        List<String> baseNames() {
            List<String> names = new ArrayList<>(segments.size());
            for (SegmentCopyRef ref : segments) {
                names.add(ref.baseName);
            }
            return names;
        }
    }

    private static final class OverlayMessageSpec {
        final int subtitleResId;
        final int notificationTextResId;

        OverlayMessageSpec(int subtitleResId, int notificationTextResId) {
            this.subtitleResId = subtitleResId;
            this.notificationTextResId = notificationTextResId;
        }
    }
}
