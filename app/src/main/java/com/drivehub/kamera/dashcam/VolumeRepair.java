package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.os.SystemClock;
import android.os.storage.StorageManager;

import com.drivehub.kamera.dev.DevRuntimeLog;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Asks the platform to unmount a removable volume and mount it again.
 *
 * <p>This exists because of one measurement, repeated three times. On 2026-10-22 the stick in
 * this car reported <b>0 MB free of 29937</b> and refused a seven-byte file with ENOSPC; it was
 * pulled out and pushed back in, and 1.6 seconds later it reported <b>29905 MB free</b> and
 * recorded. The same thing on 2026-10-15: 48 MB, then 29895 MB fifty-five seconds later. A
 * remount does not delete anything, so a medium whose space comes back when it is remounted was
 * never full of anything anybody could open - the allocation was owed to no file, and what
 * repaired it was the filesystem check vold runs before mounting.
 *
 * <p>So the fault lives in the mount, not on the medium, and the hands that fix it are doing
 * something this app can ask for directly. {@link StorageManager#unmount} and
 * {@code mount(String)} are not public API on API 28, so they are reached by reflection, and
 * both are guarded by MOUNT_UNMOUNT_FILESYSTEMS - which this app holds because it is signed with
 * the platform key and runs as the system uid. If any of that is missing the answer is a line in
 * the report saying so, never an exception: an app that cannot repair the volume is still an app
 * that must keep recording on the next one.
 *
 * <p>vold does not know the volume by the name this app uses for it. The FAT serial
 * ({@code C598-441B}) is the directory name of the raw mount; vold's own id looks like
 * {@code public:8,33}, and the only way across is to walk its volume list and match the serial.
 */
public final class VolumeRepair {

    private static final String TAG = "VolumeRepair";

    /**
     * How long to leave a volume alone after asking for it to be remounted.
     *
     * <p>The wait loop that calls this tries eleven times over two and a half minutes. One
     * repair per episode is the intent: a remount takes seconds, and asking again while vold is
     * still running the check would interrupt the only thing that helps.
     */
    private static final long COOLDOWN_MS = 120_000L;

    /** Before any repair, far enough back that the first one is never inside the cooldown. */
    private static long lastRepairAtMs = -COOLDOWN_MS * 2;
    private static String lastRepairedUuid;

    private VolumeRepair() {
    }

    /** What this platform lets us do about a stuck volume, in one line for the report. */
    public static synchronized String describe(Context context, String fsUuid) {
        if (fsUuid == null || fsUuid.isEmpty()) {
            return "repair unavailable: the volume reports no serial to match";
        }
        StorageManager storageManager = context == null
                ? null : context.getSystemService(StorageManager.class);
        if (storageManager == null) {
            return "repair unavailable: no StorageManager";
        }
        String voldId;
        try {
            voldId = voldIdFor(storageManager, fsUuid);
        } catch (Throwable t) {
            return "repair unavailable: cannot read vold's volume list (" + t + ")";
        }
        if (voldId == null) {
            return "repair unavailable: no vold volume carries the serial " + fsUuid;
        }
        boolean canUnmount = methodOrNull(storageManager, "unmount") != null;
        boolean canMount = methodOrNull(storageManager, "mount") != null;
        if (!canUnmount || !canMount) {
            return "repair unavailable: vold id " + voldId + " but "
                    + (canUnmount ? "mount" : "unmount") + "() is not reachable here";
        }
        return "repair available: vold id " + voldId + " for serial " + fsUuid;
    }

    /**
     * Unmounts the volume carrying {@code fsUuid} and mounts it again.
     *
     * <p>Both calls go straight through to vold and block while it works - the mount includes
     * the filesystem check, which is the point - so this must not be called on the main thread.
     * The caller's wait loop will see the volume go away and come back through the usual
     * MEDIA_UNMOUNTED/MEDIA_MOUNTED broadcasts, exactly as if somebody had used their fingers.
     *
     * @return true if vold accepted both halves; false, with a line in the report, otherwise.
     */
    public static synchronized boolean remount(Context context, String fsUuid) {
        if (context == null || fsUuid == null || fsUuid.isEmpty()) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (fsUuid.equals(lastRepairedUuid) && now - lastRepairAtMs < COOLDOWN_MS) {
            DevRuntimeLog.add(TAG, String.format(Locale.US,
                    "not remounting %s again: asked %.0fs ago, giving vold time to finish",
                    fsUuid, (now - lastRepairAtMs) / 1000.0));
            return false;
        }
        StorageManager storageManager = context.getSystemService(StorageManager.class);
        if (storageManager == null) {
            DevRuntimeLog.add(TAG, "cannot remount " + fsUuid + ": no StorageManager");
            return false;
        }
        try {
            String voldId = voldIdFor(storageManager, fsUuid);
            if (voldId == null) {
                DevRuntimeLog.add(TAG, "cannot remount " + fsUuid
                        + ": no vold volume carries that serial");
                return false;
            }
            Method unmount = methodOrNull(storageManager, "unmount");
            Method mount = methodOrNull(storageManager, "mount");
            if (unmount == null || mount == null) {
                DevRuntimeLog.add(TAG, "cannot remount " + voldId
                        + ": unmount/mount not reachable on this platform");
                return false;
            }
            lastRepairedUuid = fsUuid;
            lastRepairAtMs = now;

            DevRuntimeLog.add(TAG, "remounting " + voldId + " (" + fsUuid
                    + "): the medium refused a write it cannot account for");
            long startMs = SystemClock.elapsedRealtime();
            unmount.invoke(storageManager, voldId);
            long unmountedMs = SystemClock.elapsedRealtime();
            mount.invoke(storageManager, voldId);
            long mountedMs = SystemClock.elapsedRealtime();
            DevRuntimeLog.add(TAG, String.format(Locale.US,
                    "remounted %s: unmount %.1fs, mount with its filesystem check %.1fs",
                    voldId, (unmountedMs - startMs) / 1000.0,
                    (mountedMs - unmountedMs) / 1000.0));
            return true;
        } catch (Throwable t) {
            // Includes the one failure that is worth telling apart from the rest: a
            // SecurityException means the permission is not actually granted here, and no
            // number of retries will change that.
            DevRuntimeLog.add(TAG, "remount of " + fsUuid + " failed: " + t);
            return false;
        }
    }

    /** vold's id ("public:8,33") for the volume whose FAT serial is {@code fsUuid}. */
    private static String voldIdFor(StorageManager storageManager, String fsUuid)
            throws Exception {
        Method getVolumes = StorageManager.class.getMethod("getVolumes");
        Object result = getVolumes.invoke(storageManager);
        if (!(result instanceof List)) {
            return null;
        }
        for (Object volume : (List<?>) result) {
            if (volume == null) {
                continue;
            }
            Object uuid = invokeOrNull(volume, "getFsUuid");
            if (uuid instanceof String && fsUuid.equalsIgnoreCase((String) uuid)) {
                Object id = invokeOrNull(volume, "getId");
                return id instanceof String ? (String) id : null;
            }
        }
        // Second chance by path, for a volume that is mounted but reports no serial.
        for (Object volume : (List<?>) result) {
            Object path = invokeOrNull(volume, "getPath");
            if (path instanceof File && fsUuid.equalsIgnoreCase(((File) path).getName())) {
                Object id = invokeOrNull(volume, "getId");
                return id instanceof String ? (String) id : null;
            }
        }
        return null;
    }

    private static Method methodOrNull(StorageManager storageManager, String name) {
        try {
            return StorageManager.class.getMethod(name, String.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeOrNull(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
