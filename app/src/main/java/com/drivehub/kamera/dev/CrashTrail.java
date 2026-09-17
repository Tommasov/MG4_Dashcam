package com.drivehub.kamera.dev;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Looks for what Android wrote down when this process died, because the runtime log did not
 * survive to tell the story.
 *
 * <p>A process that is killed takes its in-memory log with it, which is exactly the moment the
 * log would have been worth reading. The system keeps its own records though, and this app runs
 * as uid 1000, so it can read them:
 *
 * <ul>
 *   <li>{@code /data/tombstones} - a native crash, SIGSEGV and friends. This is the one a Java
 *       try/catch can never see, and the reason a guarded loop can still vanish.
 *   <li>{@code /data/system/dropbox} - the framework's own drawer, where ANRs, Java crashes and
 *       low-memory kills are filed by name.
 * </ul>
 *
 * <p>Reads headers and first lines only: enough to say which of the three happened and when,
 * without dragging a whole crash dump through a form post. Every failure is swallowed - being
 * unable to read a tombstone is itself worth reporting, and is not worth an exception.
 */
public final class CrashTrail {

    private static final File TOMBSTONES = new File("/data/tombstones");
    private static final File DROPBOX = new File("/data/system/dropbox");

    /** How many of the newest entries to name. */
    private static final int MAX_ENTRIES = 4;

    /** Lines taken from the newest tombstone: signal, fault address and the top frames. */
    private static final int TOMBSTONE_LINES = 24;

    private CrashTrail() {
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(describeDir(TOMBSTONES, "tombstones (native crashes)"));
        sb.append(describeDir(DROPBOX, "dropbox (ANRs, java crashes, kills)"));
        File newest = newestIn(TOMBSTONES);
        if (newest != null) {
            sb.append("\nhead of ").append(newest.getName()).append(":\n");
            sb.append(head(newest, TOMBSTONE_LINES));
        }
        return sb.toString();
    }

    private static String describeDir(File dir, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" - ").append(dir.getAbsolutePath()).append('\n');
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Throwable t) {
            sb.append("  unreadable: ").append(t).append('\n');
            return sb.toString();
        }
        if (files == null) {
            sb.append("  unreadable (exists=").append(dir.exists())
                    .append(" read=").append(dir.canRead()).append(")\n");
            return sb.toString();
        }
        if (files.length == 0) {
            sb.append("  empty\n");
            return sb.toString();
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        int shown = 0;
        for (File f : files) {
            if (shown++ >= MAX_ENTRIES) {
                sb.append("  … ").append(files.length - MAX_ENTRIES).append(" older\n");
                break;
            }
            sb.append("  ").append(when.format(new Date(f.lastModified())))
                    .append("  ").append(f.getName())
                    .append("  ").append(f.length()).append(" bytes\n");
        }
        return sb.toString();
    }

    private static File newestIn(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return null;
            }
            File newest = files[0];
            for (File f : files) {
                if (f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }
            return newest.canRead() ? newest : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String head(File file, int lines) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int n = 0;
            while (n++ < lines && (line = reader.readLine()) != null) {
                sb.append("  ").append(line).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  unreadable: ").append(t).append('\n');
        }
        return sb.toString();
    }
}
