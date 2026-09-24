package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.dev.ProbeReport;
import android.os.Build;
import androidx.annotation.NonNull;
import com.drivehub.kamera.BuildConfig;
import com.drivehub.kamera.dev.StandbyJournal;
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
    private static final String ERROR_USB_FULL = "usb volume full";
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
    private ExecutorService eventCopyExecutor = Executors.newSingleThreadExecutor();

    /**
     * Everything that used to happen between two clips, moved off the gap.
     *
     * <p>Deleting an expired 34 MB clip, writing the event state, and probing the volume - which
     * writes a file, fsyncs it and deletes it - all ran after one segment had stopped and before
     * the next had started. The cameras were running the whole time and nothing was being
     * recorded: measured on the road, a second of road missing every thirty, seventeen times in
     * a half-hour drive.
     *
     * <p>None of it has to be there. Retention and bookkeeping happen once the next clip is
     * already recording, and the volume is re-probed while the current one still is.
     */
    private ExecutorService housekeepingExecutor = Executors.newSingleThreadExecutor();

    /**
     * The housekeeping thread, alive.
     *
     * <p>Standby shuts both executors down so nothing is left writing to the stick when the
     * power goes, and the service deliberately survives that - it is not stopped, so that the
     * supervisor can start recording again when the screen comes back. Which left a trap: an
     * executor, once shut down, stays shut down forever, and the submission that ends a segment
     * is not guarded. The first clip after a standby would have thrown, taken the recording loop
     * with it, and stopped the retention that keeps the stick from filling up.
     *
     * <p>Whether the process actually survives a suspend on this head unit is not settled. The
     * trap is removed either way: asking for the executor is how you get one.
     */
    private synchronized ExecutorService housekeeping() {
        if (housekeepingExecutor.isShutdown()) {
            housekeepingExecutor = Executors.newSingleThreadExecutor();
        }
        return housekeepingExecutor;
    }

    private synchronized ExecutorService eventCopy() {
        if (eventCopyExecutor.isShutdown()) {
            eventCopyExecutor = Executors.newSingleThreadExecutor();
        }
        return eventCopyExecutor;
    }

    /**
     * The hole between two clips, and the three things that make it up.
     *
     * <p>Measured from the moment one clip's muxer closes to the moment the next clip's encoder
     * is running, because that is exactly the stretch of road nobody filmed.
     */
    /** The clip being written right now, so retention running in parallel does not count it. */
    private volatile String recordingBaseName;

    private long clipStoppedAtMs;
    private long lastStopMs;
    private long lastResolveMs;

    /** The storage decision for the next segment, worked out during the current one. */
    private volatile File prefetchedBaseDir;
    private volatile boolean prefetchedBaseIsUsb;
    private volatile boolean prefetchInFlight;
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
    /**
     * How often the foreground is checked.
     *
     * <p>It was 200 ms, and that alone could eat the whole budget: the factory 360 view gives
     * up after about 210, so a hand-off noticed a fifth of a second late had already lost.
     * Sixty leaves the time for the release rather than spending it on finding out.
     */
    private static final long OEM_POLL_MS = 60L;
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

    /** Marks the start of a session in the journal, so the previous one has a visible end. */
    private void journalServiceStart() {
        StandbyJournal.add(this, "recording session starting");
        sendStandbyReportIfAsked();
    }

    /**
     * Sends what the journal saw, once per session, when somebody has asked to watch a shutdown.
     *
     * <p>Sent on the way up rather than on the way down, and that is the point. At standby the
     * modem is going away with everything else, so an upload started there is a race against
     * the power - and a report that arrives half written says nothing. The journal is already
     * on internal storage and already synced, so it survives the gap on its own; the next start
     * has a working network, a running system and all the time it needs to post it.
     *
     * <p>What it carries is deliberately small: the journal, which is the answer to the
     * question, and the runtime log for context. The full report stays behind its button.
     */
    private void sendStandbyReportIfAsked() {
        if (!UiPrefs.isStandbyDiagnostics(prefs()) || !ProbeReport.isConfigured()) {
            return;
        }
        String body = "# automatic report: what the last shutdown looked like" + "\n"
                + "version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" + "\n"
                + "fingerprint: " + Build.FINGERPRINT + "\n"
                + "service starts: " + prefs().getInt(KEY_SERVICE_STARTS, 0) + "\n\n"
                + "== standby journal (oldest first) ==" + "\n"
                + StandbyJournal.snapshot(this) + "\n"
                + "== runtime log ==" + "\n"
                + DevRuntimeLog.snapshot() + "\n";
        ProbeReport.send(this, "standby", body, new ProbeReport.Callback() {
            @Override
            public void onSent(@NonNull String reportName) {
                StandbyJournal.add(RecordingService.this, "standby report sent as " + reportName);
            }

            @Override
            public void onFailed(@NonNull String reason) {
                // Worth a line and nothing more: no network at the moment of an ignition is the
                // normal case, and the journal is still there to be read by hand.
                StandbyJournal.add(RecordingService.this, "standby report not sent: " + reason);
            }
        });
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
        oemAskedAtMs = SystemClock.elapsedRealtime();
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
                } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                    onSystemShutdown(goAsync());
                } else if (action != null) {
                    // Not acted on, only written down: these are the landmarks that say what
                    // the head unit does between the display going dark and the processor
                    // stopping.
                    DevRuntimeLog.add("RecordingService", "system says " + action);
                    StandbyJournal.add(RecordingService.this, "system says " + action);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // Everything else the system might say on the way down. Locking the car is not one
        // event but a sequence, and which of these actually arrive on this head unit - and in
        // what order - is the thing being measured. A filter costs nothing; a phase we never
        // saw because nobody listened costs a week.
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(screenReceiver, filter);

        // Media has to be a filter of its own: those broadcasts carry a file: URI, and a
        // receiver without a data scheme never sees them.
        IntentFilter media = new IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_EJECT);
        media.addDataScheme("file");
        registerReceiver(mediaReceiver, media);
        // Screen state is not deliverable from the manifest: it has to be a live receiver.
    }

    /**
     * Wakes the storage wait the moment the system says a volume has arrived.
     *
     * <p>The wait below is a backoff, which means that when the volume becomes usable a second
     * after an attempt, the next look is twenty seconds away. This turns that into an interrupt:
     * recording starts when the stick is ready rather than when the next tick happens to fall.
     */
    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            String line = "media " + action.substring(action.lastIndexOf('.') + 1)
                    + " " + intent.getData();
            DevRuntimeLog.add("RecordingService", line);
            // Into the journal as well, because this is the line that can be checked against
            // something visible: the head unit puts a "disk inserted" message on screen when
            // the volume is properly mounted, and these two should fall at the same moment. If
            // they do, this is the signal to start on. If the message comes later, there is
            // another stage after this one and we are still starting too early.
            StandbyJournal.add(RecordingService.this, line);
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
        }
    };

    private void onScreenOff() {
        // Every one of these used to be able to leave without saying anything, and a journal
        // with no screen-off line in it could mean the broadcast never came or that it came and
        // we decided against acting - two completely different faults reading the same.
        if (worker == null || stopRequested || usbEjectInProgress) {
            StandbyJournal.add(this, "screen off, but nothing to close"
                    + " (worker=" + (worker != null) + " stopping=" + stopRequested
                    + " ejecting=" + usbEjectInProgress + ")");
            return;
        }
        int speedKmh = VehicleSpeedReader.readSpeedKmh();
        if (speedKmh > SCREEN_OFF_MAX_SPEED_KMH) {
            DevRuntimeLog.add("RecordingService",
                    "screen off at " + speedKmh + " km/h; still driving, keeping the loop");
            StandbyJournal.add(this, "screen off at " + speedKmh + " km/h; still driving");
            return;
        }
        DevRuntimeLog.add("RecordingService",
                "screen off at " + speedKmh + " km/h; closing the clip before standby");
        StandbyJournal.add(this, "screen off at " + speedKmh + " km/h; closing the clip");
        pausedForScreenOff = true;
        shutdownRecordingServiceWithoutStopSelf();
        // Off the main thread: quiescence waits for the muxer, and this runs inside a broadcast.
        new Thread(() -> {
            long startedMs = SystemClock.elapsedRealtime();
            boolean quiet = awaitShutdownQuiescence();
            String verdict = String.format(Locale.US, "%s after %.1fs (%s)",
                    quiet ? "medium idle before standby" : "STANDBY CAME FIRST",
                    (SystemClock.elapsedRealtime() - startedMs) / 1000.0,
                    lastQuiescenceDetail);
            DevRuntimeLog.add("RecordingService", verdict);
            StandbyJournal.add(this, verdict);
            startStandbyHeartbeat();
        }, "RecordingServiceStandby").start();
    }

    /**
     * Writes one line a second for as long as we are still being run.
     *
     * <p>Nothing announces the moment the processor stops: the app is simply not scheduled
     * again, and there is no event to catch and no line to write. So the measurement has to be
     * the absence of one - the last beat before the gap is the last moment we existed, and the
     * next session's first line says how long the gap was.
     *
     * <p>Only when asked for, because a beat a second for a minute of standby is pointless wear
     * on every car that is not being investigated. It stops on its own after
     * {@link #STANDBY_HEARTBEAT_MAX}, and the moment the screen comes back.
     */
    private void startStandbyHeartbeat() {
        if (!UiPrefs.isStandbyDiagnostics(prefs()) || standbyHeartbeat != null) {
            return;
        }
        final Thread beat = new Thread(() -> {
            long started = SystemClock.elapsedRealtime();
            int n = 0;
            while (!Thread.currentThread().isInterrupted()
                    && SystemClock.elapsedRealtime() - started < STANDBY_HEARTBEAT_MAX) {
                StandbyJournal.add(this, String.format(Locale.US, "still running, +%ds", ++n));
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            StandbyJournal.add(this, "heartbeat ended on its own after " + n + "s");
        }, "RecordingServiceHeartbeat");
        standbyHeartbeat = beat;
        beat.start();
    }

    private void stopStandbyHeartbeat() {
        Thread beat = standbyHeartbeat;
        standbyHeartbeat = null;
        if (beat != null) {
            beat.interrupt();
        }
    }

    /** Long enough to outlast the suspend, short enough not to run all night if it never comes. */
    private static final long STANDBY_HEARTBEAT_MAX = 5 * 60 * 1000L;

    private volatile Thread standbyHeartbeat;

    /**
     * The broadcast that turned out to be the one that matters on this car.
     *
     * <p>The clean close was built on ACTION_SCREEN_OFF, on the reasoning that locking the car
     * dims the display a minute before the processor stops. The journal from 23 September says
     * that is not what happens here: between one session and the next there is no screen-off
     * line at all, only
     * {@code system says android.intent.action.ACTION_SHUTDOWN} - and then nothing. So the head
     * unit does announce itself, through the one broadcast Android has always had for this, and
     * we were listening for the wrong one. Every ignition since the feature was written has
     * been cutting power with a clip open.
     *
     * <p>Handled with {@code goAsync()}: the work cannot run on the main thread here, and
     * letting {@code onReceive} return would let the system carry on shutting down while the
     * stick is still being written. Holding the broadcast open makes it wait, which is the
     * entire point - but only briefly. Android gives a shutdown receiver about ten seconds
     * before it stops caring, so the wait is given eight and then gives up rather than being
     * killed in the middle.
     */
    private void onSystemShutdown(@Nullable BroadcastReceiver.PendingResult pending) {
        DevRuntimeLog.add("RecordingService", "system says ACTION_SHUTDOWN");
        StandbyJournal.add(this, "ACTION_SHUTDOWN: closing the clip");
        stopStandbyHeartbeat();
        final boolean hadWork = worker != null;
        shutdownRecordingServiceWithoutStopSelf();
        new Thread(() -> {
            long startedMs = SystemClock.elapsedRealtime();
            boolean quiet = hadWork ? awaitShutdownQuiescence(SHUTDOWN_BUDGET_MS) : true;
            StandbyJournal.add(this, String.format(Locale.US, "%s after %.1fs (%s)",
                    quiet ? "medium idle before shutdown" : "SHUTDOWN CAME FIRST",
                    (SystemClock.elapsedRealtime() - startedMs) / 1000.0,
                    hadWork ? lastQuiescenceDetail : "nothing was recording"));
            if (pending != null) {
                try {
                    pending.finish();
                } catch (Throwable ignored) {
                    // The system stopped waiting; nothing useful follows.
                }
            }
        }, "RecordingServiceShutdown").start();
    }

    /** All the time a shutdown receiver can safely take, less a margin. */
    private static final long SHUTDOWN_BUDGET_MS = 8000L;

    private void onScreenOn() {
        stopStandbyHeartbeat();
        if (!pausedForScreenOff) {
            return;
        }
        pausedForScreenOff = false;
        DevRuntimeLog.add("RecordingService", "screen on; the supervisor will restart the loop");
        StandbyJournal.add(this, "screen on");
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
                worker = new Thread(this::recordLoop, "RecordingServiceWorker");
                worker.start();
            } else if (wasPaused) {
                // No toast on the way back. Handing the cameras to the 360 view and taking them
                // back again happens several times a drive, and the message said nothing the
                // driver could act on - the dot in the status bar now says the same thing
                // without covering the screen for two seconds each time.
                publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
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
            // A line per session, so the journal shows where one ends and the next begins -
            // which is also the only way to tell whether the process survived the suspend.
            journalServiceStart();
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
                CameraProbe.stopCombinedMp4Record(false, false);
                CameraProbe.releaseCombinedCameras();
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
            // Worked out during the previous segment, while the cameras were still recording.
            // Only the first pass, and a pass where the prefetch did not finish in time, pays
            // for the probe here.
            final long resolveStartMs = SystemClock.elapsedRealtime();
            File resolved = takePrefetchedBaseDir();
            if (resolved == null) {
                resolved = resolveActiveBaseDir(false);
            }
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            baseDir = resolved;
            final long resolveMs = SystemClock.elapsedRealtime() - resolveStartMs;

            // Read every iteration so settings edits take effect between segments. USB and
            // internal storage carry separate retention limits.
            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs, activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmss");
            lastResolveMs = resolveMs;
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
            // Retention and bookkeeping run while the next clip is already being recorded.
            final File doneDir = baseDir;
            final String doneName = baseName;
            final long doneStart = segmentStartWallMs;
            final long doneEnd = System.currentTimeMillis();
            final int doneKeep = keepSegments;
            lastSegmentCompletedMs = doneEnd;
            housekeeping().execute(
                    () -> onSegmentCompleted(doneDir, doneName, doneStart, doneEnd, doneKeep));

            // Check whether recording has been disabled in prefs.
            enabled = prefs.getBoolean(DashcamSettings.KEY_ENABLED, false);
            if (!enabled)
                break;
        }

        // Whatever ended the loop - the switch turned off, a stick that never came back, a
        // fatal error - no further clip is coming, so nothing may still be holding a camera.
        CameraProbe.releaseCombinedCameras();
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

        CameraProbe.releaseCombinedCameras();
        futureOnlyEventSession = false;
        worker = null;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    /**
     * Works out where the next clip goes, while the current one is still recording.
     *
     * <p>Re-resolving between segments is not optional: it is what notices a stick pulled out,
     * a stick that came back read-only, and a change of target in the settings. But it writes a
     * file to the volume, fsyncs it and deletes it again, which on a slow stick is most of the
     * hole between two clips. Doing it a segment early costs nothing - the cameras are busy
     * anyway - and the answer is ready when the rotation comes.
     *
     * <p>Deliberately not cached across a whole drive: the probe still runs once per segment,
     * so a volume that fails half way through a journey is still caught within thirty seconds.
     */
    private void schedulePrefetch() {
        if (prefetchInFlight) {
            return;
        }
        prefetchInFlight = true;
        prefetchedBaseDir = null;
        housekeeping().execute(() -> {
            try {
                DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(this);
                if (res.baseDir != null && ensureDirectoryExists(res.baseDir, "records base dir")
                        && res.baseDir.canWrite()) {
                    prefetchedBaseIsUsb = res.usingUsb;
                    prefetchedBaseDir = res.baseDir;
                }
            } catch (Throwable t) {
                // A failed prefetch is not a failure: the loop resolves synchronously instead,
                // which is exactly what it did before any of this existed.
                Log.w(TAG, "storage prefetch", t);
            } finally {
                prefetchInFlight = false;
            }
        });
    }

    /**
     * The prefetched target, or null if there is not one ready. Consumed once: a stale answer
     * from two segments ago is worse than resolving again.
     */
    private File takePrefetchedBaseDir() {
        File dir = prefetchedBaseDir;
        prefetchedBaseDir = null;
        if (dir == null || prefetchInFlight) {
            return null;
        }
        boolean wasUsb = activeBaseIsUsb;
        boolean hadPrevious = activeBaseDir != null;
        activeBaseDir = dir;
        activeBaseIsUsb = prefetchedBaseIsUsb;
        if (hadPrevious && wasUsb != prefetchedBaseIsUsb) {
            // The same announcement resolveActiveBaseDir makes when the target changes under it.
            DevRuntimeLog.add("RecordingService",
                    "storage target changed to " + (prefetchedBaseIsUsb ? "usb" : "internal"));
        }
        return dir;
    }

    private boolean recordClip(File baseDir, long durationMs, String baseName, int keepSegments) {
        SharedPreferences prefs = prefs();
        int recordingFps = DashcamSettings.getRecordingFps(prefs);
        String signature = DashcamSettings.getRecordingSignature(prefs);
        boolean showSpeed = DashcamSettings.shouldShowSpeed(prefs);
        int cameraMask = DashcamSettings.getRecordingCameraMask(prefs);
        int selectedCameraCount = DashcamSettings.getRecordingCameraCount(cameraMask);
        File outputFile = new File(baseDir, baseName + ".mp4");
        recordingBaseName = baseName;
        final long startCallMs = SystemClock.elapsedRealtime();
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
        if (clipStoppedAtMs > 0) {
            DevRuntimeLog.add("RecordingService", String.format(Locale.US,
                    "rotation gap %dms = stop %d + resolve %d + start %d",
                    start - clipStoppedAtMs, lastStopMs, lastResolveMs, start - startCallMs));
        }
        // Work out where the next clip goes while this one is still recording, so the answer is
        // waiting at the rotation instead of being computed in the hole.
        schedulePrefetch();
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

        // The cameras stay open only for a plain rotation, where the next clip starts within
        // milliseconds. Reopening four video devices was measured at 254 ms of the 640 ms hole
        // between one clip and the next - by far the largest part of it, and spent reopening
        // devices that had just been closed with the same format.
        //
        // Every other way out of the wait above is a stop that means it: the service going
        // down, the screen going off, or the factory 360 view waiting for the devices. Those
        // pass false and the cameras are released before this call returns, because a device we
        // still hold is one the factory app cannot open.
        final boolean rotating = !stopRequested && !segmentStopRequested && !oemPauseRequested;
        // The factory 360 view is standing there waiting for the devices, and it has been
        // measured giving up after about 210 ms. Everything else can afford to close the clip
        // properly first; this cannot.
        final boolean urgent = oemPauseRequested;
        final long stopCallMs = SystemClock.elapsedRealtime();
        boolean stopped = CameraProbe.stopCombinedMp4Record(rotating, urgent);
        if (urgent && oemAskedAtMs > 0) {
            // The only number that matters on this path. Under about 210 and the factory view
            // opens on the first press; over it and the driver presses twice and blames us.
            DevRuntimeLog.add("RecordingService", String.format(Locale.US,
                    "cameras free %dms after the 360 asked (its limit is about 210)",
                    SystemClock.elapsedRealtime() - oemAskedAtMs));
            oemAskedAtMs = 0L;
        }
        clipStoppedAtMs = SystemClock.elapsedRealtime();
        lastStopMs = clipStoppedAtMs - stopCallMs;
        if (!stopped) {
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
                // A rotation that ended a moment ago may still be holding the four cameras open
                // for a clip that is now not going to start. The factory view is waiting for
                // exactly those devices, so the hold goes first and the banner second.
                CameraProbe.releaseCombinedCameras();
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
            CameraProbe.releaseCombinedCameras();
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
            // Retention now runs while the next clip is already being written, so that clip is
            // sitting in the directory as this counts. Without protecting it the count is one
            // too high and the oldest good clip is thrown away a segment early.
            String inProgress = recordingBaseName;
            if (inProgress != null) {
                protectedBases.add(inProgress);
            }
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
            eventCopy().execute(() -> copyEventSegments(job));
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
    /**
     * Backoff for the wait below: quick at first, then patient for a couple of minutes.
     *
     * <p>It used to give up after half a minute, and that was the whole of the trouble at
     * startup. A volume that has only just been mounted turns up in StorageManager, has its
     * directory, answers yes to canWrite() - and reports <b>zero bytes free</b>, because the
     * free-cluster count of the FAT has not been worked out yet. Every write then fails with
     * ENOSPC, which reads exactly like a full stick and is nothing of the sort: the same stick
     * started by hand a minute later records perfectly.
     *
     * <p>So the retries now run for about two and a half minutes. It costs nothing when the
     * volume is ready at once, and nothing is reported as an error in the meantime - the wait
     * only announces a failure on its last attempt. The mount broadcast below usually cuts it
     * short long before that.
     */
    private static final long[] STORAGE_WAIT_MS = {
            0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L,
            20_000L, 20_000L, 20_000L, 30_000L, 30_000L};

    /** Why the last resolution failed, so the wait above can say it out loud. */
    private DashcamStorageManager.UsbState lastResolveUsbState =
            DashcamStorageManager.UsbState.OK;

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
        final long waitStartMs = SystemClock.elapsedRealtime();
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
                String line = String.format(Locale.US,
                        "storage ready on attempt %d/%d after %.1fs",
                        attempt + 1, STORAGE_WAIT_MS.length,
                        (SystemClock.elapsedRealtime() - waitStartMs) / 1000.0);
                if (attempt > 0) {
                    DevRuntimeLog.add("RecordingService", line);
                }
                // In the journal too, and on every start including the immediate ones: whether
                // the stick is ready at once or after a minute is the question, and answering
                // it from memory across days of driving is not answering it.
                StandbyJournal.add(this, line);
                return dir;
            }
            // Every attempt says something now. This wait used to be silent unless it
            // succeeded late or gave up entirely, so a start that failed on a volume which was
            // there but not yet writable left nothing at all in the report to read afterwards.
            DevRuntimeLog.add("RecordingService", String.format(Locale.US,
                    "storage not ready: attempt %d/%d at +%.1fs, %s",
                    attempt + 1, STORAGE_WAIT_MS.length,
                    (SystemClock.elapsedRealtime() - waitStartMs) / 1000.0,
                    lastResolveUsbState));
        }
        StandbyJournal.add(this, String.format(Locale.US,
                "storage never became usable: gave up after %.0fs, %s",
                (SystemClock.elapsedRealtime() - waitStartMs) / 1000.0, lastResolveUsbState));
        return null;
    }

    private File resolveActiveBaseDir(boolean initial) {
        return resolveActiveBaseDir(initial, true);
    }

    private File resolveActiveBaseDir(boolean initial, boolean announceFailure) {
        DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(this);
        lastResolveUsbState = res.usbState;
        if (res.baseDir == null) {
            if (!announceFailure) {
                // A retry is still in hand; saying "error" now would only make the badge flicker
                // red and back while the volume finishes mounting.
                return null;
            }
            lastResolveUsbState = res.usbState;
            DevRuntimeLog.add("RecordingService", "Storage resolve failed: " + res.usbState);
            if (res.usbState == DashcamStorageManager.UsbState.NOT_ENOUGH_SPACE) {
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_USB_FULL);
                return null;
            }
            boolean readOnly = res.usbState == DashcamStorageManager.UsbState.NOT_WRITABLE
                    || res.usbState == DashcamStorageManager.UsbState.WRITE_TEST_FAILED;
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS,
                    readOnly ? ERROR_USB_READ_ONLY : ERROR_USB_STORAGE);
            return null;
        }
        // The second gate, and the one that would have quietly undone the first: the probe
        // upstream can prove a volume writable and this would still turn it away on the same
        // access(2) answer that was wrong in the first place. It asks the same question the
        // probe asks, and it says out loud why it refused.
        String baseProbeError = null;
        if (ensureDirectoryExists(res.baseDir, "records base dir")) {
            baseProbeError = DashcamStorageManager.writeProbeError(res.baseDir);
        }
        if (baseProbeError != null || !res.baseDir.isDirectory()) {
            DevRuntimeLog.add("RecordingService", "records base dir unusable: "
                    + res.baseDir.getAbsolutePath() + " (canWrite=" + res.baseDir.canWrite()
                    + (baseProbeError == null ? "" : ", probe: " + baseProbeError) + ")");
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
            CameraProbe.stopCombinedMp4Record(false, false);
            CameraProbe.releaseCombinedCameras();
        } catch (Throwable ignored) {
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * Waits until nothing of ours is still touching the medium.
     *
     * <p>It used to wait for the worker and for one of the two executors, and for neither of the
     * flush threads. So it could report the clip closed while the housekeeping thread was
     * deleting expired clips off the stick and a detached thread was still fsyncing 34 MB onto
     * it - and deleting files is precisely what rewrites the FAT chains and the free-cluster
     * count that came back broken.
     *
     * <p>Three things now, each named in the journal, because a shutdown that was not quiet is
     * worth knowing about in detail rather than as one word.
     */
    private boolean awaitShutdownQuiescence() {
        return awaitShutdownQuiescence(17000L);
    }

    /**
     * @param budgetMs everything this is allowed to take. Standby can afford to be patient;
     *                 a shutdown receiver cannot, and being killed half way through waiting is
     *                 worse than giving up on purpose and saying so.
     */
    private boolean awaitShutdownQuiescence(long budgetMs) {
        final long deadline = SystemClock.elapsedRealtime() + budgetMs;
        Thread workerThread = worker;
        boolean workerDone = true;
        if (workerThread != null) {
            try {
                workerThread.join(Math.max(1, Math.min(2000, remaining(deadline))));
                workerDone = !workerThread.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        ExecutorService events = eventCopyExecutor;
        ExecutorService chores = housekeepingExecutor;
        events.shutdown();
        chores.shutdown();
        boolean eventsDone;
        boolean choresDone;
        try {
            eventsDone = events.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS);
            choresDone = chores.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean flushed;
        try {
            flushed = CameraProbe.awaitPendingFlushes((int) remaining(deadline));
        } catch (Throwable t) {
            // An older native library without this entry point must not turn a shutdown into a
            // crash; it only means we cannot tell whether the writes landed.
            Log.w(TAG, "awaitPendingFlushes unavailable", t);
            flushed = false;
        }
        lastQuiescenceDetail = String.format(Locale.US,
                "worker=%s retention=%s events=%s writes=%s",
                workerDone, choresDone, eventsDone, flushed);
        return workerDone && eventsDone && choresDone && flushed;
    }

    /**
     * Redraws the dot now, for a switch that was just flipped.
     *
     * <p>Status is published on its own rhythm - a clip boundary, a hand-off - so without this
     * the dot would appear up to half a minute after being asked for, which reads as a switch
     * that does nothing. Silent when the service is not running: there is no recording to show.
     */
    public static void refreshStatusIcon(@NonNull Context context) {
        RecordingService svc = sInstance;
        if (svc == null) {
            return;
        }
        SharedPreferences p = svc.prefs();
        svc.statusIcon().update(UiPrefs.isStatusBarIcon(p)
                ? p.getString(KEY_STATUS, STATUS_OFF) : null);
    }

    /** The dot over the factory status bar, built the first time somebody asks for it. */
    private StatusIconOverlay statusIcon;

    private synchronized StatusIconOverlay statusIcon() {
        if (statusIcon == null) {
            statusIcon = new StatusIconOverlay(this);
        }
        return statusIcon;
    }

    /** Time left before the deadline, never zero: a zero wait is a wait that never happens. */
    private static long remaining(long deadlineMs) {
        return Math.max(1L, deadlineMs - SystemClock.elapsedRealtime());
    }

    /** When the 360 view was seen asking, so the release can be timed against its patience. */
    private volatile long oemAskedAtMs;

    /** What the last shutdown managed to finish, for the journal. */
    private volatile String lastQuiescenceDetail = "";

    private void stopServiceIfNotEjecting() {
        if (usbEjectInProgress) {
            return;
        }
        statusIcon().hide();
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
        return context.getString(R.string.settings_dashcam_status_error,
                describeError(context, error));
    }

    /**
     * Turns an internal error code into something a driver can read, in their own language.
     *
     * <p>The codes are constants like {@code "usb volume read only"}, and they were being put
     * straight on screen inside an otherwise translated sentence. They are also what gets saved
     * and compared, so they stay exactly as they are and only the rendering changes.
     *
     * <p>Anything unrecognised falls through unchanged rather than disappearing: a code with no
     * message is still better than no message at all.
     */
    private static String describeError(Context context, String code) {
        final int res;
        switch (code) {
            case ERROR_STORAGE_NOT_WRITABLE:
                res = R.string.dashcam_error_storage_not_writable;
                break;
            case ERROR_GRID_START_FAILED:
                res = R.string.dashcam_error_grid_start_failed;
                break;
            case ERROR_GRID_STOP_TIMEOUT:
                res = R.string.dashcam_error_grid_stop_timeout;
                break;
            case ERROR_USB_STORAGE:
                res = R.string.dashcam_error_usb_storage;
                break;
            case ERROR_USB_READ_ONLY:
                res = R.string.dashcam_error_usb_read_only;
                break;
            case ERROR_USB_FULL:
                res = R.string.dashcam_error_usb_full;
                break;
            case ERROR_LOOP_DIED:
                res = R.string.dashcam_error_loop_died;
                break;
            case ERROR_STALLED:
                res = R.string.dashcam_error_stalled;
                break;
            case ERROR_CRASH_LOOP:
                res = R.string.dashcam_error_crash_loop;
                break;
            default:
                return code;
        }
        return context.getString(res);
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

        statusIcon().update(UiPrefs.isStatusBarIcon(prefs()) ? status : null);

        String notificationText;
        if (STATUS_RECORDING.equals(status)) {
            notificationText = getString(R.string.notification_recording_status, activeCameras, totalCameras);
        } else if (STATUS_PAUSED_OEM.equals(status)) {
            notificationText = getString(R.string.notification_recording_paused_oem);
        } else if (STATUS_PARTIAL.equals(status) || STATUS_ERROR.equals(status)) {
            notificationText = getString(R.string.notification_recording_error,
                    describeError(this, lastError));
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
        statusIcon().hide();
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
                // Never registered, or already gone.
            }
            try {
                unregisterReceiver(mediaReceiver);
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
