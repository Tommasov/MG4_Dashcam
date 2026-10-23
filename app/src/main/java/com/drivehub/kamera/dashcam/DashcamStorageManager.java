package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import androidx.annotation.NonNull;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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

    /**
     * How many clips of room a volume has to have before it is called usable.
     *
     * <p>The write probe creates a file of seven bytes, and for a long time that was the whole
     * test. A medium with half a megabyte left passes it - and then the first real clip fails,
     * so the app declares a working volume and stops recording a moment later for a reason
     * nobody can see. Being able to write seven bytes does not prove being able to write
     * thirty-four megabytes.
     *
     * <p>Two clips: the one about to be written, and somewhere for the next one to go while
     * the last is still being flushed.
     */
    private static final int REQUIRED_CLIPS_OF_ROOM = 2;

    /** At most this many old clips are removed in one attempt to make room. */
    private static final int MAX_RECLAIM_DELETIONS = 40;

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
        NOT_ENOUGH_SPACE,   // writable, but not enough room for a clip and a margin
        MULTIPLE_MEDIA,     // more than one usable medium and no choice made — see setPreferredVolumeId
        CHOSEN_VOLUME_ABSENT // a volume was chosen by hand and it is not connected
    }

    /**
     * Pushes a directory's metadata onto the medium, and on FAT the allocation table with it.
     *
     * <p>Deleting a file on FAT is two writes - the directory entry freed, and the chain of
     * clusters released in the table - and neither is forced anywhere. They sit in the kernel's
     * cache until it feels like writing them, which on a car is until the ignition is switched
     * off. What survives is a table that still says those clusters are in use and a directory
     * that no longer mentions them, so the next mount finds clusters with no owner and files
     * them under LOST.DIR. A little more every time, until the medium is full of its own
     * deleted recordings.
     *
     * <p>Clips written are already fsynced, which is why this went unseen for months: the fault
     * only appears once the ring buffer is full and every rotation deletes something. On this
     * car that was the day the stick filled up.
     *
     * <p>fsync on a vfat directory is enough - the kernel's implementation flushes the
     * allocation table alongside it - so no native call is needed.
     */
    static boolean syncDirectory(File dir, String why) {
        if (dir == null) {
            return false;
        }
        try {
            if (!CameraProbe.syncFilesystem(dir.getAbsolutePath())) {
                DevRuntimeLog.add(TAG, "sync of " + dir.getAbsolutePath()
                        + " after " + why + " did not succeed");
                return false;
            }
            return true;
        } catch (Throwable t) {
            // Worth a line and nothing more: a medium that will not sync has larger problems,
            // and whatever writes to it next will say so.
            DevRuntimeLog.add(TAG, "could not sync " + dir.getAbsolutePath()
                    + " after " + why + ": " + t);
            return false;
        }
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
            if (entry.isFile() && entry.getName().endsWith(CLIP_SUFFIX)
                    && deleteFreeingSpace(entry)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            syncDirectory(base, "clearing the folder");
        }
        return deleted;
    }

    /** Room for {@link #REQUIRED_CLIPS_OF_ROOM} clips at the configured bitrate and length. */
    private static long bytesNeeded(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        long bitrate = Math.max(1000000L, DashcamSettings.getRecordingBitrateBps(prefs));
        long seconds = Math.max(1, DashcamSettings.getSegmentDurationSec());
        return bitrate / 8L * seconds * REQUIRED_CLIPS_OF_ROOM;
    }

    /**
     * Deletes the oldest clips until there is room, and says so.
     *
     * <p>This is what retention would have done if it had ever been reached. It is deliberately
     * timid: oldest first, never the newest file - which is either the clip being written or
     * the most recent thing worth keeping - and it stops the moment there is enough room rather
     * than tidying for its own sake. If something other than our clips is filling the medium it
     * runs out of things to delete and says so, instead of emptying the folder.
     *
     * @return the free space after trying, so the caller can decide with a number.
     */
    /**
     * The earliest a real recording of ours can be dated.
     *
     * <p>2026-01-01, which is before this app existed and after any clock this head unit falls
     * back to. Not a guess about the future: the only thing it has to separate is "a date the
     * car actually knew" from "the date the car uses when it does not know".
     */
    private static final long PLAUSIBLE_FROM_MS = 1767225600000L;

    /**
     * Whether a clip's timestamp can be believed.
     *
     * <p>The head unit sometimes starts recording before the clock has been set - measured on
     * 2026-09-26, a session that began at {@code 2019-01-01 01:00:11} and ended, correctly, at
     * {@code 22:14:34} the same evening. The footage is real; only the date is not.
     *
     * <p>It matters because both places that free up space delete the oldest file, by modified
     * time. A clip stamped 2019 looks seven years old, so it goes first - and it is in fact the
     * newest thing on the medium, filmed in the minute after a cold start, which is the minute
     * somebody is most likely to want. An unbelievable date does not mean old, it means unknown,
     * and for a dashcam the careful reading of unknown is recent.
     */
    public static boolean isPlausibleClipTime(long modifiedMs) {
        return modifiedMs >= PLAUSIBLE_FROM_MS;
    }

    /** Oldest first, with unbelievable dates held back to the end rather than treated as old. */
    public static final Comparator<File> OLDEST_FIRST =
            Comparator.comparingInt((File f) -> isPlausibleClipTime(f.lastModified()) ? 0 : 1)
                    .thenComparingLong(File::lastModified);

    private static long reclaimSpace(File dir, long needed, List<String> trace) {
        File[] all = dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(CLIP_SUFFIX));
        if (all == null || all.length <= 1) {
            return dir.getUsableSpace();
        }
        List<File> clips = new ArrayList<>(Arrays.asList(all));
        // The file with the latest timestamp comes out of the running first, before any
        // reordering: on a clock telling the truth that is the clip being written, and taking
        // it by position instead would protect whatever the sort happened to leave last.
        clips.remove(Collections.max(clips, Comparator.comparingLong(File::lastModified)));
        Collections.sort(clips, OLDEST_FIRST);

        int deleted = 0;
        long free = dir.getUsableSpace();
        for (File clip : clips) {
            if (free >= needed || deleted >= MAX_RECLAIM_DELETIONS) {
                break;
            }
            if (deleteFreeingSpace(clip)) {
                deleted++;
            }
            free = dir.getUsableSpace();
        }
        if (deleted > 0) {
            syncDirectory(dir, "making room");
            trace(trace, "made room in " + dir.getAbsolutePath() + ": removed " + deleted
                    + " old clip(s), now " + (free / 1048576) + " MB free");
        }
        return free;
    }

    /**
     * The volume a records directory lives on, as a directory this process can read.
     *
     * <p>The raw vold mount first: it is the one the clips are written through, it is the one
     * that answers an ENOSPC, and reading it does not go through FUSE. The volume root under
     * /storage is the fallback, and the records directory's own parent the last resort - which
     * is the right answer anyway for the app-sandbox location, where the volume root is not
     * ours to walk.
     */
    private static File volumeRootOf(UsbCandidate candidate, File recordsDir) {
        File raw = new File(MEDIA_RW_ROOT, candidate.volumeId);
        if (raw.isDirectory() && raw.canRead()) {
            return raw;
        }
        if (candidate.rootDir != null && candidate.rootDir.canRead()) {
            return candidate.rootDir;
        }
        File parent = recordsDir.getParentFile();
        return parent != null ? parent : recordsDir;
    }

    /** Stop walking a medium after this many entries, however much is left. */
    private static final int MEASURE_MAX_ENTRIES = 20_000;

    /** And after this long, which matters more: this runs while the car waits to record. */
    private static final long MEASURE_BUDGET_MS = 3_000L;

    /**
     * Everything on a medium that can actually be opened, as far as it could be counted.
     *
     * <p>{@code complete} is the whole point. A walk that ran out of entries or out of time has
     * not measured the medium, and must not be read as "there is nothing there" - that
     * confusion, between a directory that is empty and a directory that would not answer, is
     * exactly what has cost us a month of guessing.
     */
    private static final class MediumContents {
        final long bytes;
        final int files;
        final boolean complete;

        MediumContents(long bytes, int files, boolean complete) {
            this.bytes = bytes;
            this.files = files;
            this.complete = complete;
        }

        /**
         * Whether a claim of {@code claimedUsed} occupied bytes is believable.
         *
         * <p>Half, because the question is never close. A medium genuinely full of clips
         * accounts for its own claim within a few per cent; the fault this guards against
         * accounts for a tenth of it - three or four gigabytes of ring buffer against a claim
         * of twenty-nine. Anything in between has never been observed, and if it ever is, the
         * report now carries both numbers to argue with.
         *
         * <p>A measurement that did not finish is not evidence, so it never licenses a
         * deletion.
         */
        boolean accountsFor(long claimedUsed) {
            return complete && bytes * 2L >= claimedUsed;
        }

        String describe(File root) {
            if (!complete) {
                return "could not finish counting " + root.getAbsolutePath()
                        + " (reached " + files + " file(s), " + (bytes / 1048576)
                        + " MB): not treating that as an empty medium";
            }
            return "everything that can be opened under " + root.getAbsolutePath() + " is "
                    + files + " file(s) worth " + (bytes / 1048576) + " MB";
        }
    }

    /**
     * Adds up every file under a medium, breadth first, with a bound on both effort and time.
     *
     * <p>Iterative rather than recursive: this walks a stranger's filesystem, and the one thing
     * known about the media this app meets is that they are sometimes in a state nobody
     * designed for.
     */
    private static MediumContents measureMedium(File root) {
        long deadline = SystemClock.elapsedRealtime() + MEASURE_BUDGET_MS;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        long bytes = 0L;
        int files = 0;
        int entries = 0;
        while (!pending.isEmpty()) {
            if (entries >= MEASURE_MAX_ENTRIES || SystemClock.elapsedRealtime() > deadline) {
                return new MediumContents(bytes, files, false);
            }
            File[] children = pending.poll().listFiles();
            if (children == null) {
                // A directory that will not list is a hole in the measurement, not a zero.
                return new MediumContents(bytes, files, false);
            }
            for (File child : children) {
                entries++;
                if (child.isDirectory()) {
                    pending.add(child);
                } else {
                    files++;
                    bytes += child.length();
                }
            }
        }
        return new MediumContents(bytes, files, true);
    }

    /** What the records directory holds right now, in one line for the report. */
    private static String describeRecordsDir(File recordsDir) {
        File[] entries = recordsDir.listFiles();
        if (entries == null) {
            return "cannot list " + recordsDir.getAbsolutePath()
                    + ": the folder would not answer, which is not the same as empty";
        }
        int clips = 0;
        int others = 0;
        long bytes = 0L;
        for (File entry : entries) {
            if (entry.isFile() && entry.getName().toLowerCase(Locale.US).endsWith(CLIP_SUFFIX)) {
                clips++;
                bytes += entry.length();
            } else {
                others++;
            }
        }
        return String.format(Locale.US,
                "holds %d clip(s) worth %d MB and %d other entr%s; the volume says %d MB free"
                        + " of %d MB",
                clips, bytes / 1048576, others, others == 1 ? "y" : "ies",
                recordsDir.getUsableSpace() / 1048576, recordsDir.getTotalSpace() / 1048576);
    }

    /**
     * The volume whose refusal to be written to could not be accounted for, if any.
     *
     * <p>Set here, where the evidence is, and acted on by {@link #repairPendingVolume} from the
     * service's wait loop - which is the only place that can afford to spend twenty seconds on
     * an unmount and a filesystem check, and the only place that is already waiting for the
     * volume to come back.
     */
    private static volatile String pendingRepairVolumeId;

    private static void requestRepair(String volumeId, List<String> trace) {
        pendingRepairVolumeId = volumeId;
        trace(trace, "not deleting anything: a medium that refuses a write it cannot account"
                + " for is a medium to be remounted, not emptied - asking for " + volumeId);
    }

    /**
     * Acts on a repair asked for by the last resolution, at most one per wait loop.
     *
     * @return true if the volume was actually remounted, so the caller can say so and try again.
     */
    public static boolean repairPendingVolume(Context context) {
        String volumeId = pendingRepairVolumeId;
        if (volumeId == null) {
            return false;
        }
        pendingRepairVolumeId = null;
        return VolumeRepair.remount(context, volumeId);
    }

    /** Said once if emptying a file before deleting it ever fails, and then not again. */
    private static boolean truncateComplained;

    /**
     * Removes a file and gives its space back, which on this car are two different things.
     *
     * <p>Measured on 2026-10-23, with chkdsk reading the stick itself: <b>244 lost cluster
     * chains, 7827392 KB, 32079 KB each</b> - one deleted clip apiece, to within the 16 KB
     * cluster. The directory entry goes, the chain stays allocated, and the volume's free count
     * is never credited. Nothing of ours was failing: the ring buffer had deleted those 244
     * clips and syncfs had reported success 244 times, which is in the report.
     *
     * <p>So the file is emptied before it is unlinked. Opening it for writing truncates it,
     * which reaches the allocation table by a different road than unlink takes, and the fsync
     * that follows pushes that road's result onto the medium while there is still a name
     * pointing at the file. If that road works the space comes back at every rotation.
     *
     * <p>If this driver breaks that road too, nothing here will say so - which is why it is not
     * the only defence. {@link #repairIfFillingWithGhosts} has the volume remounted before it
     * can fill, and the rotation line in the report carries the free space before and after, so
     * one line of the next report says which of the two did the work.
     */
    static boolean deleteFreeingSpace(File file) {
        if (file == null) {
            return false;
        }
        if (file.isFile() && file.length() > 0L) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.getFD().sync();
            } catch (Throwable t) {
                if (!truncateComplained) {
                    truncateComplained = true;
                    DevRuntimeLog.add(TAG, "could not empty " + file.getName()
                            + " before deleting it: " + t);
                }
            }
        }
        return file.delete();
    }

    /** Below a quarter of itself free, a volume is near enough to refusing writes. */
    private static final long GHOSTS_FREE_DIVISOR = 4L;

    /** And a gigabyte owed to no file is what makes the shortage ghosts rather than footage. */
    private static final long GHOSTS_MIN_BYTES = 1024L * 1024L * 1024L;

    /**
     * Has the volume remounted if it is filling with allocation that belongs to no file.
     *
     * <p>This is the defence that does not depend on understanding the driver. The medium
     * tolerates about 29.9 GB of writing per mount - measured: 29773 MB consumed over 470
     * recorded minutes between 11 and 15 October, which is 63.3 MB a minute against the 63.4
     * MB a minute we actually write - and then refuses everything. What puts it right is a
     * mount: on 22 October a remount freed 29 GB in 1.6 seconds, because vold checks the
     * filesystem before mounting and a check reclaims chains nobody owns.
     *
     * <p>So rather than waiting for the refusal and reporting a fault for the rest of the
     * drive, the volume is remounted while nothing is recording yet, with gigabytes of margin
     * still in hand. The two conditions are both needed: short of space says it matters soon,
     * and a gap between what the volume claims is occupied and what can actually be opened says
     * the shortage is not somebody's files. A stick genuinely filling up with footage accounts
     * for its own claim and is left alone - the ring buffer is what handles that.
     *
     * @return true if the volume was remounted, so the caller resolves again before recording.
     */
    public static boolean repairIfFillingWithGhosts(Context context, File recordsDir) {
        if (context == null || recordsDir == null) {
            return false;
        }
        String serial = volumeSerialOfRecordsDir(recordsDir);
        if (serial == null) {
            return false;
        }
        long total = recordsDir.getTotalSpace();
        long free = recordsDir.getUsableSpace();
        if (total <= 0L || free > total / GHOSTS_FREE_DIVISOR) {
            return false;
        }
        File root = recordsDir.getParentFile();
        if (root == null) {
            return false;
        }
        MediumContents contents = measureMedium(root);
        if (!contents.complete) {
            // Not evidence, so not a reason to unmount a volume that is about to record.
            DevRuntimeLog.add(TAG, "volume is short of space but " + contents.describe(root));
            return false;
        }
        long gap = (total - free) - contents.bytes;
        if (gap < GHOSTS_MIN_BYTES) {
            DevRuntimeLog.add(TAG, String.format(Locale.US,
                    "volume is down to %d MB free of %d MB and its own files account for it"
                            + " (%d file(s), %d MB): leaving it to the ring buffer",
                    free / 1048576, total / 1048576, contents.files, contents.bytes / 1048576));
            return false;
        }
        DevRuntimeLog.add(TAG, String.format(Locale.US,
                "volume is down to %d MB free of %d MB with %d MB owed to no file"
                        + " (%d file(s) account for %d MB): remounting before recording",
                free / 1048576, total / 1048576, gap / 1048576,
                contents.files, contents.bytes / 1048576));
        return VolumeRepair.remount(context, serial);
    }

    /**
     * The FAT serial of the volume a records directory sits on, or null if it is not on one.
     *
     * <p>The serial is the directory name of both mounts - /mnt/media_rw/C598-441B and
     * /storage/C598-441B - so the records directory's parent carries it. Internal storage and
     * the app-sandbox location are two levels deeper or somewhere else entirely, and neither is
     * a volume anybody can remount, so they come back null rather than guessed at.
     */
    private static String volumeSerialOfRecordsDir(File recordsDir) {
        File parent = recordsDir.getParentFile();
        File grandparent = parent == null ? null : parent.getParentFile();
        if (grandparent == null) {
            return null;
        }
        String root = grandparent.getAbsolutePath();
        if (!root.equals(MEDIA_RW_ROOT.getAbsolutePath())
                && !root.equals(LEGACY_STORAGE_ROOT.getAbsolutePath())) {
            return null;
        }
        return parent.getName();
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
        boolean anyOutOfSpace = false;
        for (UsbCandidate candidate : prioritized) {
            File accepted = null;
            String acceptedWhat = "";
            // Measured at most once per volume per pass. The three locations tried below all
            // live on the same medium, so without this a stick that refuses writes gets walked
            // three times for one answer - and the wait loop asks eleven times.
            File measuredRoot = null;
            MediumContents measured = null;
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
                // The probe decides, and nothing is allowed to decide before it.
                //
                // This has now been got wrong twice, in the same shape, with two different
                // advisory numbers. First canWrite(), which is access(2) and was turning away
                // sticks that recorded perfectly. Then getUsableSpace(), moved in front of the
                // probe so that a full medium would be called full rather than unwritable - and
                // that call returns 0 on any error at all, so "the volume is full" and "the
                // volume could not be asked" arrive as the same number. On a stick that was
                // merely busy, with rotations stretching to 1.7 s just before, a healthy volume
                // with 29.8 GB free was declared out of space; the only thing standing between
                // that and forty deleted clips was that the directory listing failed too.
                //
                // So: a write that actually fails is the only thing allowed to reject a volume,
                // and a write that actually succeeds is proof that a zero reading was a lie.
                boolean advisoryWritable = recordsDir.canWrite();
                long needed = bytesNeeded(context);
                String probeError = writeProbeError(recordsDir);
                boolean outOfSpace = looksOutOfSpace(probeError);
                if (outOfSpace) {
                    // The medium has refused a seven-byte file, and that used to be reason
                    // enough to start deleting. It is not.
                    //
                    // On 2026-10-22 this volume reported 0 MB free of 29937 and refused the
                    // probe with ENOSPC. It was pulled out and pushed back in, and 1.6 seconds
                    // later reported 29905 MB free and recorded all afternoon. A remount does
                    // not delete anything, so there was never 29 GB of anybody's files on it:
                    // the allocation was owed to no file at all. Meanwhile the ring buffer
                    // keeps a hundred clips - three or four gigabytes - so whenever this
                    // happens the folder cannot possibly account for what the volume claims is
                    // occupied, and deleting the footage changes nothing except that the
                    // footage is gone. Up to forty clips per call, and the wait loop calls this
                    // eleven times.
                    //
                    // So the refusal has to add up before anything is removed. Count what the
                    // volume says is occupied, count what can actually be opened, and compare.
                    File volumeRoot = volumeRootOf(candidate, recordsDir);
                    if (measured == null || !volumeRoot.equals(measuredRoot)) {
                        measuredRoot = volumeRoot;
                        measured = measureMedium(volumeRoot);
                    }
                    MediumContents contents = measured;
                    long total = recordsDir.getTotalSpace();
                    long claimedUsed = total - recordsDir.getUsableSpace();
                    trace(trace, "ENOSPC in " + recordsDir.getAbsolutePath() + ": volume claims "
                            + (claimedUsed / 1048576) + " MB of " + (total / 1048576)
                            + " MB occupied; " + contents.describe(volumeRoot));
                    if (contents.accountsFor(claimedUsed)) {
                        // The medium really is full of files somebody can open, and the oldest
                        // clips are ours to recycle. Making room is the one thing that helps,
                        // and the loop is otherwise closed - no room means no clip, no clip
                        // means no segment completes, and retention only runs when one does.
                        reclaimSpace(recordsDir, needed, trace);
                        probeError = writeProbeError(recordsDir);
                        outOfSpace = looksOutOfSpace(probeError);
                    } else {
                        requestRepair(candidate.volumeId, trace);
                    }
                }
                if (probeError != null) {
                    if (outOfSpace) {
                        anyOutOfSpace = true;
                    } else {
                        anyWriteTestFailed = true;
                    }
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

                // The write went through, so the volume is usable, and nothing below is
                // allowed to say otherwise. There used to be a free-space check here, and it
                // was wrong in a way that took three attempts to see.
                //
                // The figure this volume reports for free space is not to be trusted at all. It
                // was first read as a count that had not finished being built - low and
                // climbing for a second or two after the mount - and that reading is wrong:
                // measured across five weeks of reports it does not climb, it decays. Right
                // after a mount the volume says 29.9 GB free; a week of driving later it says
                // 47 MB, having been written to about that much in total while the ring buffer
                // deleted all but a hundred clips. It behaves like a counter that is charged
                // for everything written and credited for nothing deleted, and the only thing
                // that resets it is the filesystem check vold runs when the volume is mounted.
                //
                // On a figure like that, a threshold is worthless and arithmetic is worse: it
                // nearly deleted forty clips to make room that was never missing. Twice it did
                // not, and both times the reason was luck - the directory listing was failing
                // in the same instant, so there was nothing to delete.
                //
                // So the figure decides nothing here. A write that succeeds is proof the medium
                // is usable; a write that fails with ENOSPC is handled above, by measuring
                // whether the refusal can be accounted for before anything is removed.
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
            // The one line the report was missing for a month. Every frame was timed to a
            // tenth of a millisecond while nothing said how many clips were on the stick or
            // what they weighed, which is the only pair of numbers that can tell a full medium
            // apart from a medium that merely says so.
            trace(trace, "  " + describeRecordsDir(accepted));
            trace(trace, "  " + VolumeRepair.describe(context, candidate.volumeId));
            usable.add(accepted);
        }
        if (usable.size() == 1) {
            return new UsbProbe(UsbState.OK, usable.get(0));
        }
        if (usable.size() > 1) {
            trace(trace, "multiple usable media (" + usable.size() + "); refusing to pick one");
            return new UsbProbe(UsbState.MULTIPLE_MEDIA, null);
        }
        if (anyOutOfSpace) {
            return new UsbProbe(UsbState.NOT_ENOUGH_SPACE, null);
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
    /**
     * Whether a failed write failed for want of room rather than for want of permission.
     *
     * <p>Matched on the text, because the text is what there is: the probe hands back the
     * exception, and the kernel's ENOSPC arrives inside it as "No space left on device"
     * whichever exception Java wraps it in. Crude, and still sounder than the alternative - a
     * free-space figure that reads zero when it cannot be taken at all.
     */
    private static boolean looksOutOfSpace(String probeError) {
        if (probeError == null) {
            return false;
        }
        String lower = probeError.toLowerCase(Locale.US);
        return lower.contains("no space left") || lower.contains("enospc");
    }

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
