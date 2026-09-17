package com.drivehub.kamera.dev;

import com.drivehub.kamera.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

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
 * <p>Both drawers are opened the same way now: list what is there, then print the newest entry
 * that names this app. The first version listed the dropbox by name and date only, and a crash
 * loop on the car duly reported twenty-eight {@code data_app_crash} files without a single line
 * of what they said - the stack trace was sitting there unread. A file name is not a diagnosis.
 *
 * <p>Every failure is swallowed: being unable to read a tombstone is itself worth reporting, and
 * is not worth an exception.
 */
public final class CrashTrail {

    private static final File TOMBSTONES = new File("/data/tombstones");
    private static final File DROPBOX = new File("/data/system/dropbox");

    /** How many of the newest entries to name. */
    private static final int MAX_ENTRIES = 4;

    /** Lines taken from the newest tombstone: signal, fault address and the top frames. */
    private static final int TOMBSTONE_LINES = 24;

    /**
     * Lines taken from the newest dropbox entry. A Java stack trace is worth more per line than
     * a register dump, and the frames that matter are usually below our own: the framework call
     * that led into them says which of our entry points was running.
     */
    private static final int DROPBOX_LINES = 60;

    /**
     * How many dropbox entries to open looking for one of ours. The drawer is shared with every
     * app on the head unit, so the newest file is often somebody else's.
     */
    private static final int DROPBOX_SEARCH_DEPTH = 12;

    private CrashTrail() {
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(describeDir(TOMBSTONES, "tombstones (native crashes)"));
        sb.append(describeDir(DROPBOX, "dropbox (ANRs, java crashes, kills)"));

        File tombstone = newestIn(TOMBSTONES);
        if (tombstone != null) {
            sb.append("\nhead of ").append(tombstone.getName()).append(":\n");
            sb.append(head(tombstone, TOMBSTONE_LINES));
        }

        File crash = newestOursIn(DROPBOX);
        if (crash != null) {
            sb.append("\nhead of ").append(crash.getName()).append(" (ours):\n");
            sb.append(head(crash, DROPBOX_LINES));
        } else {
            sb.append("\nno dropbox entry naming ").append(BuildConfig.APPLICATION_ID)
                    .append(" in the newest ").append(DROPBOX_SEARCH_DEPTH).append('\n');
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
        File[] files = sortedNewestFirst(dir);
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f.canRead()) {
                return f;
            }
        }
        return null;
    }

    /**
     * The newest dropbox entry that mentions this app. Every app on the head unit files into the
     * same drawer, and on a car that has been running all day ours is rarely the newest.
     */
    private static File newestOursIn(File dir) {
        File[] files = sortedNewestFirst(dir);
        if (files == null) {
            return null;
        }
        int opened = 0;
        for (File f : files) {
            if (opened >= DROPBOX_SEARCH_DEPTH) {
                return null;
            }
            if (!f.canRead()) {
                continue;
            }
            opened++;
            if (head(f, DROPBOX_LINES).contains(BuildConfig.APPLICATION_ID)) {
                return f;
            }
        }
        return null;
    }

    private static File[] sortedNewestFirst(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return null;
            }
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return files;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads the first lines, transparently through gzip: the dropbox compresses as it pleases. */
    private static String head(File file, int lines) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = open(file)) {
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

    private static BufferedReader open(File file) throws Exception {
        if (!file.getName().endsWith(".gz")) {
            return new BufferedReader(new FileReader(file));
        }
        InputStream in = new GZIPInputStream(new FileInputStream(file));
        return new BufferedReader(new InputStreamReader(in));
    }
}
