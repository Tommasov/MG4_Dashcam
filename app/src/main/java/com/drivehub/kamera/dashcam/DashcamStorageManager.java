package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.settings.UiPrefs;

import androidx.annotation.NonNull;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for where the dashcam writes its footage.
 *
 * Resolution policy:
 *  - AUTO:          USB when a writable medium passes the write test, otherwise internal.
 *  - USB_ONLY:      USB or nothing (resolution.baseDir == null when no usable medium).
 *  - INTERNAL_ONLY: internal storage, USB is never probed.
 *
 * Upstream forces INTERNAL_ONLY here regardless of the stored preference, leaving the USB paths
 * dormant. This fork honours the preference again; the USB code below is upstream's and had
 * never run in a release build, so it wants testing on the vehicle with a real stick before
 * AUTO or USB_ONLY is trusted.
 *
 * "Internal" is whatever {@link DashcamSettings#getRecordsBaseDir} resolves to
 * (default Downloads/dashcam or a configured override path).
 */
public final class DashcamStorageManager {

    private static final String TAG = "DashcamStorage";

    public static final int TARGET_AUTO = 0;
    public static final int TARGET_USB_ONLY = 1;
    public static final int TARGET_INTERNAL_ONLY = 2;

    public static final String KEY_STORAGE_TARGET = "dashcamStorageTarget";
    private static final String KEY_USB_RETENTION_CLIP_COUNT = "dashcamUsbRetentionClipCount";
    private static final String KEY_USB_MAX_RETAINED_EVENT_DIRS = "dashcamUsbMaxRetainedEventDirs";
    /** Empty means "whichever is there", which is right until a second volume shows up. */
    private static final String KEY_USB_VOLUME_ID = "dashcamUsbVolumeId";

    // USB media are larger and tolerate write load better, so defaults are much more generous
    // than the conservative internal defaults (10 clips / 5 events).
    public static final int DEFAULT_USB_RETENTION_CLIP_COUNT = 100;
    public static final int DEFAULT_USB_MAX_RETAINED_EVENT_DIRS = 25;

    private static final String USB_RECORDS_DIR_NAME = "dashcam";
    private static final String EVENTS_DIR_NAME = "events";
    private static final String CLIP_SUFFIX = ".mp4";
    private static final String WRITE_PROBE_FILE_NAME = ".dashcam_write_probe";

    /** Tells Android's media scanner to leave this folder, and everything under it, alone. */
    private static final String NOMEDIA_FILE_NAME = ".nomedia";

    /** The folder the marker was last put in, so it is offered once per medium and not forced. */
    private static final String KEY_NOMEDIA_PLACED_IN = "noMediaPlacedIn";
    private static final File LEGACY_STORAGE_ROOT = new File("/storage");
    /** Where vold mounts removable media before the per-app FUSE view is layered on. */
    private static final File MEDIA_RW_ROOT = new File("/mnt/media_rw");

    public enum UsbState {
        NOT_CHECKED,        // INTERNAL_ONLY mode: USB is deliberately ignored
        OK,                 // exactly one medium found, dir created, write test passed
        NO_MEDIUM,          // no removable volume mounted under /storage
        NOT_WRITABLE,       // volume(s) found but the dashcam dir cannot be created/written
        WRITE_TEST_FAILED,  // dir exists but the actual write probe failed
        MULTIPLE_MEDIA,     // more than one usable medium and no choice made — see setPreferredVolumeId
        CHOSEN_VOLUME_ABSENT // a volume was chosen by hand and it is not connected
    }

    /** Immutable outcome of one storage resolution pass. */
    public static final class Resolution {
        /** Directory to record into; null only in USB_ONLY mode without a usable medium. */
        public final File baseDir;
        public final boolean usingUsb;
        public final int target;
        public final UsbState usbState;

        Resolution(File baseDir, boolean usingUsb, int target, UsbState usbState) {
            this.baseDir = baseDir;
            this.usingUsb = usingUsb;
            this.target = target;
            this.usbState = usbState;
        }
    }

    private DashcamStorageManager() {
    }

    // ---------- Preferences ----------

    public static int getStorageTarget(SharedPreferences prefs) {
        if (prefs == null) {
            return TARGET_INTERNAL_ONLY;
        }
        return clampTarget(prefs.getInt(KEY_STORAGE_TARGET, TARGET_INTERNAL_ONLY));
    }

    public static void setStorageTarget(SharedPreferences prefs, int target) {
        prefs.edit().putInt(KEY_STORAGE_TARGET, clampTarget(target)).apply();
    }

    public static int clampTarget(int target) {
        return Math.max(TARGET_AUTO, Math.min(TARGET_INTERNAL_ONLY, target));
    }

    public static int getUsbRetentionClipCount(SharedPreferences prefs) {
        return DashcamSettings.clampRetentionClipCount(
                prefs.getInt(KEY_USB_RETENTION_CLIP_COUNT, DEFAULT_USB_RETENTION_CLIP_COUNT));
    }

    public static void setUsbRetentionClipCount(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_USB_RETENTION_CLIP_COUNT,
                DashcamSettings.clampRetentionClipCount(count)).apply();
    }

    public static int getUsbMaxRetainedEventDirs(SharedPreferences prefs) {
        return DashcamSettings.clampMaxRetainedEventDirs(
                prefs.getInt(KEY_USB_MAX_RETAINED_EVENT_DIRS, DEFAULT_USB_MAX_RETAINED_EVENT_DIRS));
    }

    public static void setUsbMaxRetainedEventDirs(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_USB_MAX_RETAINED_EVENT_DIRS,
                DashcamSettings.clampMaxRetainedEventDirs(count)).apply();
    }

    // ---------- Per-target retention policy ----------

    public static int getActiveRetentionClipCount(SharedPreferences prefs, boolean usingUsb) {
        return usingUsb
                ? getUsbRetentionClipCount(prefs)
                : DashcamSettings.getRetentionClipCount(prefs);
    }

    public static int getActiveMaxRetainedEventDirs(SharedPreferences prefs, boolean usingUsb) {
        return usingUsb
                ? getUsbMaxRetainedEventDirs(prefs)
                : DashcamSettings.getMaxRetainedEventDirs(prefs);
    }

    // ---------- Which volume ----------

    /**
     * The volume the driver picked, or empty for "whichever is there".
     *
     * <p>Stored as the volume's uuid - the FAT serial, the {@code 9EFB-89C8} that also names the
     * raw vold mount. It survives unplugging, rebooting and being moved between the two ports.
     * It does not survive reformatting, which is why a chosen volume that is not present says so
     * rather than quietly recording somewhere else.
     */
    @NonNull
    public static String getPreferredVolumeId(SharedPreferences prefs) {
        return prefs.getString(KEY_USB_VOLUME_ID, "");
    }

    public static void setPreferredVolumeId(SharedPreferences prefs, @NonNull String volumeId) {
        prefs.edit().putString(KEY_USB_VOLUME_ID, volumeId).apply();
    }

    /** One connected volume, as the picker needs to describe it. */
    public static final class VolumeChoice {
        public final String volumeId;
        public final String description;
        public final long freeBytes;
        public final long totalBytes;

        VolumeChoice(String volumeId, String description, long freeBytes, long totalBytes) {
            this.volumeId = volumeId;
            this.description = description;
            this.freeBytes = freeBytes;
            this.totalBytes = totalBytes;
        }
    }

    /** Every removable volume currently connected. Touches the filesystem - not on the main thread. */
    @NonNull
    public static List<VolumeChoice> listVolumes(Context context) {
        List<UsbCandidate> candidates = findUsbCandidates(context);
        if (candidates.isEmpty()) {
            candidates = findLegacyStorageCandidates();
        }
        List<VolumeChoice> out = new ArrayList<>();
        for (UsbCandidate candidate : candidates) {
            out.add(new VolumeChoice(
                    candidate.volumeId,
                    candidate.description == null || candidate.description.isEmpty()
                            ? candidate.rootDir.getName() : candidate.description,
                    candidate.rootDir.getFreeSpace(),
                    candidate.rootDir.getTotalSpace()));
        }
        return out;
    }

    // ---------- What is on the medium ----------

    /**
     * What the dashcam is holding, told apart by what it means rather than by where it sits.
     *
     * <p>Loop clips are disposable by design - the ring buffer deletes them itself. Saved events
     * are the opposite: somebody pressed something to keep those. Any number the app shows, and
     * anything it offers to delete, has to keep the two apart, or the one button that frees space
     * becomes the one button that throws away the reason you were recording.
     */
    public static final class Usage {
        public final int clipCount;
        public final long clipBytes;
        public final int eventCount;
        public final long eventBytes;

        Usage(int clipCount, long clipBytes, int eventCount, long eventBytes) {
            this.clipCount = clipCount;
            this.clipBytes = clipBytes;
            this.eventCount = eventCount;
            this.eventBytes = eventBytes;
        }

        public long totalBytes() {
            return clipBytes + eventBytes;
        }

        public boolean isEmpty() {
            return clipCount == 0 && eventCount == 0;
        }
    }

    /** Walks the records folder. Touches the filesystem - do not call on the main thread. */
    @NonNull
    public static Usage measureUsage(Context context) {
        File base = resolve(context).baseDir;
        if (base == null || !base.isDirectory()) {
            return new Usage(0, 0L, 0, 0L);
        }
        int clipCount = 0;
        long clipBytes = 0L;
        File[] entries = base.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isFile() && entry.getName().endsWith(CLIP_SUFFIX)) {
                    clipCount++;
                    clipBytes += entry.length();
                }
            }
        }
        int eventCount = 0;
        long eventBytes = 0L;
        File events = new File(base, EVENTS_DIR_NAME);
        File[] eventDirs = events.listFiles();
        if (eventDirs != null) {
            for (File dir : eventDirs) {
                if (!dir.isDirectory()) {
                    continue;
                }
                eventCount++;
                eventBytes += sizeOfDirectory(dir);
            }
        }
        return new Usage(clipCount, clipBytes, eventCount, eventBytes);
    }

    /**
     * Deletes the loop clips and nothing else. Returns how many went.
     *
     * <p>Only files that sit directly in the records folder and end in the clip suffix: the
     * events folder is a directory and is never descended into, so it cannot be caught by
     * accident. Do not call on the main thread.
     */
    public static int deleteLoopClips(Context context) {
        File base = resolve(context).baseDir;
        if (base == null || !base.isDirectory()) {
            return 0;
        }
        File[] entries = base.listFiles();
        if (entries == null) {
            return 0;
        }
        int deleted = 0;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().endsWith(CLIP_SUFFIX) && entry.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    private static long sizeOfDirectory(File dir) {
        long total = 0L;
        File[] entries = dir.listFiles();
        if (entries == null) {
            return 0L;
        }
        for (File entry : entries) {
            total += entry.isDirectory() ? sizeOfDirectory(entry) : entry.length();
        }
        return total;
    }

    // ---------- Resolution ----------

    /**
     * Resolves the storage target for the configured mode. Performs real IO (USB write test)
     * unless the mode is INTERNAL_ONLY — do not call on the main thread.
     */
    /**
     * Offers the clips folder to the media scanner once, and then never argues about it.
     *
     * <p>The clips are indexed today - they show up in the factory video player - and indexing
     * them is pure waste on both sides. Their names are timestamps and the ring buffer replaces
     * them continuously, so every scan meets a hundred files it has never seen, opens each one
     * to read its metadata, and writes rows that are stale within the hour. None of it is ever
     * reused. On this head unit that happens while the volume is being mounted, which is the
     * least helpful moment available: the first seconds after the screen appears are when the
     * factory music player misses its own scan and the storage is at its slowest.
     *
     * <p>Written once per folder and recorded, rather than checked and restored every time.
     * Somebody who wants their clips in the car's video player can delete this file and keep
     * it deleted - it is on their medium, not ours, and putting it back would be insisting.
     */
    private static void offerNoMediaOnce(Context context, File dir) {
        if (dir == null) {
            return;
        }
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        String path = dir.getAbsolutePath();
        if (path.equals(prefs.getString(KEY_NOMEDIA_PLACED_IN, ""))) {
            return;
        }
        File marker = new File(dir, NOMEDIA_FILE_NAME);
        try {
            if (marker.exists() || marker.createNewFile()) {
                prefs.edit().putString(KEY_NOMEDIA_PLACED_IN, path).apply();
                Log.i(TAG, "media scanner marker in place at " + path);
            }
        } catch (Throwable t) {
            // A medium that will not take an empty file has larger problems, and they will be
            // reported by the write probe in a way that says more than this could.
            Log.w(TAG, "could not place " + NOMEDIA_FILE_NAME + " in " + path, t);
        }
    }

    public static Resolution resolve(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        int target = getStorageTarget(prefs);

        if (target == TARGET_INTERNAL_ONLY) {
            File internal = DashcamSettings.getRecordsBaseDir(context);
            // Internal is a public Downloads folder, so it is indexed even more certainly than
            // a stick is.
            offerNoMediaOnce(context, internal);
            return new Resolution(internal, false, target, UsbState.NOT_CHECKED);
        }

        UsbProbe probe = probeUsb(context, null);
        if (probe.state == UsbState.OK) {
            offerNoMediaOnce(context, probe.recordsDir);
            return new Resolution(probe.recordsDir, true, target, UsbState.OK);
        }
        if (target == TARGET_USB_ONLY) {
            return new Resolution(null, false, target, probe.state);
        }
        // AUTO: fall back to internal, but keep the probe result so callers can surface it.
        File internal = DashcamSettings.getRecordsBaseDir(context);
        offerNoMediaOnce(context, internal);
        return new Resolution(internal, false, target, probe.state);
    }

    /**
     * Runs the same probe {@link #resolve} runs, but returns what it saw at every step instead
     * of only the verdict. The head unit has no adb, so a probe that only logs is a probe whose
     * findings never leave the car.
     */
    public static String describeProbe(Context context) {
        List<String> trace = new ArrayList<>();
        UsbProbe probe;
        try {
            probe = probeUsb(context, trace);
        } catch (Throwable t) {
            trace.add("probe threw: " + t);
            probe = new UsbProbe(UsbState.NO_MEDIUM, null);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("verdict: ").append(probe.state);
        if (probe.recordsDir != null) {
            sb.append(" -> ").append(probe.recordsDir.getAbsolutePath());
        }
        for (String line : trace) {
            sb.append("\n  ").append(line);
        }
        return sb.toString();
    }

    private static void trace(List<String> trace, String line) {
        Log.i(TAG, line);
        if (trace != null) {
            trace.add(line);
        }
    }

    /** Permissions as this process sees them, which is the only view that matters here. */
    public static String describeFile(File file) {
        if (file == null) {
            return "null";
        }
        if (!file.exists()) {
            return "missing";
        }
        StringBuilder sb = new StringBuilder();
        // A directory the process may not stat reports isDirectory() == false, so calling it a
        // file would be an assertion this code cannot make. Say what is actually known.
        String kind = file.isDirectory() ? "dir" : (file.isFile() ? "file" : "exists(kind unknown)");
        sb.append(kind)
                .append(" r=").append(file.canRead())
                .append(" w=").append(file.canWrite());
        try {
            long free = file.getFreeSpace();
            long total = file.getTotalSpace();
            if (total > 0) {
                sb.append(" free=").append(free / (1024 * 1024)).append("MB")
                        .append("/").append(total / (1024 * 1024)).append("MB");
            }
        } catch (Throwable ignored) {
            // getFreeSpace throws on some vendor mounts rather than returning 0
        }
        return sb.toString();
    }

    /**
     * Re-runs only the USB write test for the currently known USB records dir. Used by the
     * recording loop to distinguish "USB died" from "camera problem" after a failed segment.
     */
    public static boolean isUsbStillWritable(File usbRecordsDir) {
        if (usbRecordsDir == null) return false;
        return runWriteProbe(usbRecordsDir);
    }

    private static final class UsbProbe {
        final UsbState state;
        final File recordsDir;

        UsbProbe(UsbState state, File recordsDir) {
            this.state = state;
            this.recordsDir = recordsDir;
        }
    }

    private static final class UsbCandidate {
        final File rootDir;
        final String description;
        final boolean usbLike;
        /** Volume uuid, e.g. "9EFB-89C8": the name the raw mount carries under /mnt/media_rw. */
        final String volumeId;
        /** This app's sandbox on the volume, the one place the FUSE view always lets it write. */
        final File appPrivateDir;

        UsbCandidate(File rootDir, String description, boolean usbLike,
                String volumeId, File appPrivateDir) {
            this.rootDir = rootDir;
            this.description = description;
            this.usbLike = usbLike;
            this.volumeId = volumeId == null ? "" : volumeId;
            this.appPrivateDir = appPrivateDir;
        }
    }

    /**
     * Where to try writing on one volume, best first.
     *
     * The vehicle refuses a write at the root of the FUSE view (/storage/UUID): since KitKat an
     * app gets no general write access to a secondary volume there, whatever permissions it
     * holds. Two doors are still open, and the report from the car shows both standing open:
     * the raw vold mount under /mnt/media_rw/UUID, which this app reaches because it runs as
     * uid 1000, and its own sandbox under Android/data on the volume, which is writable for any
     * app. The raw mount comes first because it puts the clips where a person plugging the stick
     * into a computer will actually look.
     */
    /**
     * One place to try, and what kind of place it is.
     *
     * <p>The kind is carried through to the trace because a bare path misleads. The report from
     * the car listed three failed attempts and the last one - the app sandbox, with the package
     * name in it - was read as "this is where it is recording", when in fact nothing had been
     * written anywhere. The path that catches the eye is the one that needs explaining.
     */
    private static final class Where {
        final File dir;
        final String what;

        Where(File dir, String what) {
            this.dir = dir;
            this.what = what;
        }
    }

    private static List<Where> recordsDirCandidates(UsbCandidate candidate) {
        List<Where> dirs = new ArrayList<>();
        if (!candidate.volumeId.isEmpty()) {
            dirs.add(new Where(new File(new File(MEDIA_RW_ROOT, candidate.volumeId), USB_RECORDS_DIR_NAME),
                    "raw vold mount"));
        }
        dirs.add(new Where(new File(candidate.rootDir, USB_RECORDS_DIR_NAME), "volume root"));
        if (candidate.appPrivateDir != null) {
            dirs.add(new Where(new File(candidate.appPrivateDir, USB_RECORDS_DIR_NAME),
                    "app sandbox - last resort, a computer will not find the clips there"));
        }
        return dirs;
    }

    /**
     * Probes every system-known removable-volume candidate instead of blindly scanning /storage.
     * We strongly prefer candidates that look like USB/OTG media by system label/path. If the
     * system exposes no explicit USB hint, we only fall back to a generic removable volume when
     * there is exactly one such candidate.
     */
    private static UsbProbe probeUsb(Context context, List<String> trace) {
        List<UsbCandidate> candidates = findUsbCandidates(context);
        if (candidates.isEmpty()) {
            // AAOS 9 head units typically don't surface OTG/USB volumes via StorageManager:
            // the OS mounts them under /mnt/media_rw/... without registering them with the
            // MediaProvider, so getExternalFilesDirs() returns nothing usable. Fall back to a
            // direct /storage scan, which is how every dashcam-style automotive app has to
            // handle this in practice.
            candidates = findLegacyStorageCandidates();
            trace(trace, "StorageManager exposed no removable volumes; /storage scan found "
                    + candidates.size() + " candidate(s)");
        } else {
            trace(trace, "StorageManager exposed " + candidates.size() + " removable volume(s)");
        }
        for (UsbCandidate candidate : candidates) {
            trace(trace, "candidate: " + candidate.rootDir.getAbsolutePath()
                    + " label=\"" + candidate.description + "\" usbLike=" + candidate.usbLike
                    + " " + describeFile(candidate.rootDir));
        }
        if (candidates.isEmpty()) {
            return new UsbProbe(UsbState.NO_MEDIUM, null);
        }
        String preferred = getPreferredVolumeId(UiPrefs.getPrefs(context));
        if (!preferred.isEmpty()) {
            List<UsbCandidate> chosen = new ArrayList<>();
            for (UsbCandidate candidate : candidates) {
                if (preferred.equals(candidate.volumeId)) {
                    chosen.add(candidate);
                }
            }
            if (chosen.isEmpty()) {
                // Deliberately not falling back to the other volume. Somebody said "this stick",
                // and writing a drive's footage to the wrong medium because the right one was
                // left at home is worse than saying so.
                trace(trace, "chosen volume " + preferred + " is not connected");
                return new UsbProbe(UsbState.CHOSEN_VOLUME_ABSENT, null);
            }
            trace(trace, "chosen volume " + preferred + " is connected; ignoring the others");
            candidates = chosen;
        }
        List<UsbCandidate> prioritized = prioritizeUsbCandidates(candidates);
        List<File> usable = new ArrayList<>();
        boolean anyWriteTestFailed = false;
        for (UsbCandidate candidate : prioritized) {
            File accepted = null;
            String acceptedWhat = "";
            for (Where where : recordsDirCandidates(candidate)) {
                File recordsDir = where.dir;
                File parent = recordsDir.getParentFile();
                boolean existed = recordsDir.isDirectory();
                if (!existed && !recordsDir.mkdirs() && !recordsDir.isDirectory()) {
                    // Upstream skipped silently here, which makes a NOT_WRITABLE result
                    // impossible to tell apart from a missing medium in a log.
                    trace(trace, "no: cannot create " + recordsDir.getAbsolutePath()
                            + " [" + where.what + "] (parent " + describeFile(parent) + ")");
                    continue;
                }
                // canWrite() is access(W_OK): the kernel's opinion about permission bits,
                // not a write. It used to decide this on its own, and a volume it called
                // read-only was rejected without anything ever being attempted - which is how a
                // stick that recorded happily three hours earlier came back as NOT_WRITABLE at
                // the next start, with nothing in the report to say why.
                //
                // So the probe always runs now, and its answer wins. It writes a file, fsyncs
                // it, reads back its length and deletes it: if that works the volume is
                // writable, whatever access(2) thinks. And when it does not work, the reason
                // lands in the report - EROFS for a mount that came up read-only, EACCES for a
                // permission problem - which are different faults that used to arrive as the
                // same line.
                boolean advisoryWritable = recordsDir.canWrite();
                String probeError = writeProbeError(recordsDir);
                if (probeError != null) {
                    anyWriteTestFailed = true;
                    trace(trace, "no: write probe failed in " + recordsDir.getAbsolutePath()
                            + " [" + where.what + "] (existed=" + existed
                            + " canWrite=" + advisoryWritable + " "
                            + describeFile(recordsDir) + "): " + probeError);
                    continue;
                }
                if (!advisoryWritable) {
                    trace(trace, "note: canWrite() said no but the write probe succeeded in "
                            + recordsDir.getAbsolutePath() + " [" + where.what + "]");
                }
                accepted = recordsDir;
                acceptedWhat = where.what;
                break;
            }
            if (accepted == null) {
                trace(trace, "rejected volume: " + candidate.rootDir.getAbsolutePath()
                        + " (no writable location)");
                continue;
            }
            trace(trace, "accepted: " + accepted.getAbsolutePath()
                    + " [" + acceptedWhat + "]");
            usable.add(accepted);
        }
        if (usable.size() == 1) {
            return new UsbProbe(UsbState.OK, usable.get(0));
        }
        if (usable.size() > 1) {
            trace(trace, "multiple usable media (" + usable.size() + "); refusing to pick one");
            return new UsbProbe(UsbState.MULTIPLE_MEDIA, null);
        }
        return new UsbProbe(
                anyWriteTestFailed ? UsbState.WRITE_TEST_FAILED : UsbState.NOT_WRITABLE, null);
    }

    /**
     * Returns removable, non-primary storage roots known to the framework for this app. We start
     * from app-visible external dirs so we only consider mounted media the process can actually
     * access, then map them back to the volume root.
     */
    private static List<UsbCandidate> findUsbCandidates(Context context) {
        Map<String, UsbCandidate> deduped = new LinkedHashMap<>();
        StorageManager storageManager = context.getSystemService(StorageManager.class);
        File[] externalDirs = context.getExternalFilesDirs(null);
        if (externalDirs == null) {
            return new ArrayList<>();
        }
        for (File appExternalDir : externalDirs) {
            if (appExternalDir == null) {
                continue;
            }
            StorageVolume volume = storageManager == null ? null : storageManager.getStorageVolume(appExternalDir);
            if (volume == null || volume.isPrimary() || !volume.isRemovable()) {
                continue;
            }
            File rootDir = resolveVolumeRoot(appExternalDir, volume);
            if (rootDir == null || !rootDir.isDirectory() || !rootDir.canRead()) {
                continue;
            }
            String key = rootDir.getAbsolutePath();
            if (deduped.containsKey(key)) {
                continue;
            }
            String description = safeDescription(volume, context);
            // getUuid() is the volume's FAT serial, which is also the directory name vold gives
            // the raw mount: /mnt/media_rw/9EFB-89C8. Falling back to the root's own name keeps
            // this working if a volume ever reports no uuid.
            String volumeId = volume.getUuid();
            if (volumeId == null || volumeId.isEmpty()) {
                volumeId = rootDir.getName();
            }
            deduped.put(key, new UsbCandidate(rootDir, description,
                    looksLikeUsb(rootDir, description), volumeId, appExternalDir));
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * Last-resort enumeration: list every directory under /storage that isn't the emulated
     * primary volume or the "self" symlink. No StorageManager metadata, no isRemovable hints,
     * just whatever the user can mount and read. Suitability is decided by the write probe.
     */
    private static List<UsbCandidate> findLegacyStorageCandidates() {
        List<UsbCandidate> candidates = new ArrayList<>();
        File[] children = LEGACY_STORAGE_ROOT.listFiles();
        if (children == null) {
            return candidates;
        }
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            String name = child.getName();
            if (name.isEmpty() || "emulated".equals(name) || "self".equals(name)) continue;
            if (!child.canRead()) continue;
            // The /storage scan has no framework metadata: the directory name is the uuid, and
            // there is no app sandbox to fall back on.
            candidates.add(new UsbCandidate(child, name, looksLikeUsb(child, name), name, null));
        }
        return candidates;
    }

    private static List<UsbCandidate> prioritizeUsbCandidates(List<UsbCandidate> candidates) {
        List<UsbCandidate> usbLike = new ArrayList<>();
        List<UsbCandidate> genericRemovable = new ArrayList<>();
        for (UsbCandidate candidate : candidates) {
            if (candidate.usbLike) {
                usbLike.add(candidate);
            } else {
                genericRemovable.add(candidate);
            }
        }
        if (!usbLike.isEmpty()) {
            return usbLike;
        }
        if (genericRemovable.size() == 1) {
            Log.i(TAG, "No explicit USB-labelled volume found; using the only removable candidate: "
                    + genericRemovable.get(0).rootDir.getAbsolutePath());
            return genericRemovable;
        }
        return genericRemovable;
    }

    private static File resolveVolumeRoot(File appExternalDir, StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File directory = volume.getDirectory();
            if (directory != null) {
                return directory;
            }
        }
        String path = appExternalDir.getAbsolutePath();
        int androidIdx = path.indexOf("/Android/");
        if (androidIdx > 0) {
            return new File(path.substring(0, androidIdx));
        }
        File parent = appExternalDir;
        while (parent != null) {
            File next = parent.getParentFile();
            if (next == null || next.getAbsolutePath().equals("/storage")) {
                return parent;
            }
            parent = next;
        }
        return null;
    }

    private static String safeDescription(StorageVolume volume, Context context) {
        try {
            CharSequence description = volume.getDescription(context);
            return description == null ? "" : description.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean looksLikeUsb(File rootDir, String description) {
        String haystack = ((description == null ? "" : description) + " "
                + (rootDir == null ? "" : rootDir.getAbsolutePath())).toLowerCase(Locale.US);
        return haystack.contains("usb")
                || haystack.contains("otg")
                || haystack.contains("flash")
                || haystack.contains("thumb")
                || haystack.contains("mass_storage")
                || haystack.contains("usbdisk")
                || haystack.contains("udisk");
    }

    private static boolean runWriteProbe(File dir) {
        return writeProbeError(dir) == null;
    }

    /** Returns null when the probe wrote and read back, otherwise why it did not. */
    public static String writeProbeError(File dir) {
        File probe = new File(dir, WRITE_PROBE_FILE_NAME);
        try {
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write("dashcam".getBytes());
                out.getFD().sync();
            }
            boolean ok = probe.isFile() && probe.length() > 0;
            // noinspection ResultOfMethodCallIgnored
            probe.delete();
            return ok ? null : "file missing or empty after write";
        } catch (Throwable t) {
            Log.w(TAG, "USB write probe failed in " + dir.getAbsolutePath() + ": " + t);
            // noinspection ResultOfMethodCallIgnored
            probe.delete();
            return String.valueOf(t);
        }
    }
}
