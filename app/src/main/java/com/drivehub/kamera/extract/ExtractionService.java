package com.drivehub.kamera.extract;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettings;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;

import java.io.File;
import java.util.List;

/**
 * Extraction: a phase the car is put into, not a service that lives alongside the recording.
 *
 * <p>The decision this is built on is that a dashcam records, and a web server on a car is not
 * something somebody who wants a dashcam has asked for. So the access point and the server
 * exist only between entering this phase and leaving it, the recording stops while they are up,
 * and leaving puts everything back.
 *
 * <p>Which makes the way out the most important thing here. A dashcam parked in extraction mode
 * films nothing, and the owner would find out days later, so there are three ways out and the
 * driver has to remember none of them: the car starts moving, ten minutes pass with the phone
 * asking for nothing, or the ignition goes off. Each one puts the recording back the way it was.
 */
public class ExtractionService extends Service {

    private static final String TAG = "Extraction";
    private static final String CHANNEL_ID = "mg4_extraction";
    private static final int NOTIF_ID = 4201;

    public static final String ACTION_ENTER = "enter_extraction";
    public static final String ACTION_LEAVE = "leave_extraction";

    /** Set when the recording was running as we entered, so leaving can put it back. */
    private static final String EXTRA_RESUME_RECORDING = "resume_recording";

    /** Above this, the car is moving and the phase is over. Three km/h, as elsewhere. */
    private static final int MOVING_KMH = 3;

    /**
     * How long the phone may ask for nothing before the phase ends by itself.
     *
     * <p>Ten minutes: long enough to look through a day of clips and pull a few off, short
     * enough that forgetting about it costs one drive's footage at worst rather than a week's.
     */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private static final long WATCH_INTERVAL_MS = 5000L;

    /** What the screen shows. Read from the activity, written only here. */
    public static final class Snapshot {
        public final Phase phase;
        public final String ssid;
        public final String passphrase;
        public final String qrPayload;
        public final List<String> addresses;
        public final int port;
        public final int requests;
        public final String trouble;

        Snapshot(Phase phase, String ssid, String passphrase, String qrPayload,
                 List<String> addresses, int port, int requests, String trouble) {
            this.phase = phase;
            this.ssid = ssid;
            this.passphrase = passphrase;
            this.qrPayload = qrPayload;
            this.addresses = addresses;
            this.port = port;
            this.requests = requests;
            this.trouble = trouble;
        }

        /** The address to put on the screen, or null while there is none yet. */
        public String url() {
            if (addresses == null || addresses.isEmpty() || port <= 0) {
                return null;
            }
            return "http://" + addresses.get(0) + ":" + port + "/";
        }
    }

    public enum Phase {
        /** Not in the phase at all. */
        OFF,
        /** Stopping the recording, asking for the access point, opening the socket. */
        STARTING,
        /** Everything up: the QR code and the address are on the screen. */
        READY,
        /**
         * The server is up but the access point is not, and could not be switched on from here.
         * Still useful: the owner turns the hotspot on from the car's settings and the address
         * appears. Better than a dead end, and this is the one step that had never been
         * exercised on the vehicle before this build.
         */
        WAITING_FOR_HOTSPOT,
        /** Nothing could listen. The phase cannot do its job; the report says why. */
        FAILED
    }

    private static volatile Snapshot snapshot =
            new Snapshot(Phase.OFF, null, null, null, null, 0, 0, null);

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean isActive() {
        return snapshot.phase != Phase.OFF;
    }

    public static void enter(Context context, boolean resumeRecordingOnExit) {
        Intent i = new Intent(context, ExtractionService.class)
                .setAction(ACTION_ENTER)
                .putExtra(EXTRA_RESUME_RECORDING, resumeRecordingOnExit);
        context.startForegroundService(i);
    }

    public static void leave(Context context) {
        Intent i = new Intent(context, ExtractionService.class).setAction(ACTION_LEAVE);
        context.startForegroundService(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExtractionServer server;
    private boolean weStartedTheHotspot;
    private boolean resumeRecordingOnExit;
    private BroadcastReceiver shutdownReceiver;
    private Runnable watchdog;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID,
                R.string.notification_channel_extraction);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        startForeground(NOTIF_ID, notification(getString(R.string.notification_extraction)));
        if (ACTION_LEAVE.equals(action)) {
            finish("asked to leave");
            return START_NOT_STICKY;
        }
        if (ACTION_ENTER.equals(action) && snapshot.phase == Phase.OFF) {
            resumeRecordingOnExit = intent.getBooleanExtra(EXTRA_RESUME_RECORDING, false);
            publish(Phase.STARTING, null, null, null, null, 0, null);
            new Thread(this::bringUp, "extraction-start").start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        tearDown();
        super.onDestroy();
    }

    /** Everything that can block or take seconds, off the main thread. */
    private void bringUp() {
        DevRuntimeLog.add(TAG, "entering extraction; " + HotspotControl.describe(this));

        File dir = DashcamStorageManager.resolve(this).baseDir;
        if (dir == null) {
            // No medium, nothing to hand over. Said plainly rather than serving an empty page.
            publish(Phase.FAILED, null, null, null, null, 0,
                    getString(R.string.extraction_no_medium));
            return;
        }

        server = new ExtractionServer(dir);
        int port = server.start();
        if (port < 0) {
            publish(Phase.FAILED, null, null, null, null, 0,
                    getString(R.string.extraction_no_socket));
            return;
        }

        boolean hotspotUp = HotspotControl.isEnabled(this);
        if (!hotspotUp) {
            weStartedTheHotspot = HotspotControl.start(this);
            hotspotUp = weStartedTheHotspot && HotspotControl.awaitEnabled(this, 15000L);
            if (weStartedTheHotspot && !hotspotUp) {
                // We asked and it did not come up. Do not leave a half-started access point
                // behind us when the phase ends.
                weStartedTheHotspot = false;
            }
        } else {
            DevRuntimeLog.add(TAG, "the access point was already on; leaving it that way on exit");
        }

        HotspotControl.Credentials credentials = HotspotControl.credentials(this);
        String ssid = credentials == null ? null : credentials.ssid;
        String pass = credentials == null ? null : credentials.passphrase;
        String qr = credentials == null ? null : credentials.wifiQrPayload();

        publish(hotspotUp ? Phase.READY : Phase.WAITING_FOR_HOTSPOT,
                ssid, pass, qr, HotspotControl.addresses(), port,
                hotspotUp ? null : getString(R.string.extraction_turn_hotspot_on));

        main.post(this::startWatching);
    }

    /**
     * The three ways out, checked every five seconds.
     *
     * <p>Also the only place that refreshes the addresses: on this head unit the access point
     * interface can appear a second or two after the state says enabled, and an address that
     * arrives late is still the address the phone needs.
     */
    private void startWatching() {
        shutdownReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish("ignition off");
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_REBOOT);
        registerReceiver(shutdownReceiver, filter);

        watchdog = new Runnable() {
            @Override
            public void run() {
                if (snapshot.phase == Phase.OFF) {
                    return;
                }
                int speed = VehicleSpeedReader.readSpeedKmh();
                if (speed > MOVING_KMH) {
                    finish("the car is moving at " + speed + " km/h");
                    return;
                }
                ExtractionServer s = server;
                if (s != null) {
                    long idle = System.currentTimeMillis() - s.lastRequestAtMs();
                    if (idle > IDLE_TIMEOUT_MS) {
                        finish("nothing asked for anything in "
                                + (IDLE_TIMEOUT_MS / 60000) + " minutes");
                        return;
                    }
                    Snapshot now = snapshot;
                    List<String> addresses = HotspotControl.addresses();
                    boolean up = HotspotControl.isEnabled(ExtractionService.this);
                    publish(up ? Phase.READY : now.phase, now.ssid, now.passphrase, now.qrPayload,
                            addresses, now.port, up ? null : now.trouble);
                }
                main.postDelayed(this, WATCH_INTERVAL_MS);
            }
        };
        main.postDelayed(watchdog, WATCH_INTERVAL_MS);
    }

    private void finish(String why) {
        if (snapshot.phase == Phase.OFF) {
            return;
        }
        DevRuntimeLog.add(TAG, "leaving extraction: " + why
                + " (" + (server == null ? 0 : server.requestCount()) + " request(s) served)");
        tearDown();
        stopForeground(true);
        stopSelf();
    }

    private void tearDown() {
        if (watchdog != null) {
            main.removeCallbacks(watchdog);
            watchdog = null;
        }
        if (shutdownReceiver != null) {
            try {
                unregisterReceiver(shutdownReceiver);
            } catch (Throwable ignored) {
                // Never registered, or already gone: either way there is nothing to undo.
            }
            shutdownReceiver = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (weStartedTheHotspot) {
            HotspotControl.stop(this);
            weStartedTheHotspot = false;
        }
        publish(Phase.OFF, null, null, null, null, 0, null);
        if (resumeRecordingOnExit) {
            resumeRecordingOnExit = false;
            DashcamSettings.setEnabled(UiPrefs.getPrefs(this), true);
            RecordingService.startIfDashcamEnabled(this);
            DevRuntimeLog.add(TAG, "recording put back the way it was");
        }
    }

    private void publish(Phase phase, String ssid, String pass, String qr,
                         List<String> addresses, int port, String trouble) {
        snapshot = new Snapshot(phase, ssid, pass, qr, addresses, port,
                server == null ? 0 : server.requestCount(), trouble);
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
