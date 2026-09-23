package com.drivehub.kamera.dev;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * The few lines that have to outlive the moment they describe.
 *
 * <p>{@link DevRuntimeLog} is a ring buffer in memory, and both of those words are a problem for
 * the one question that matters most here: was the stick still being written to when the car
 * cut the power? The answer is written down at standby, and by the time anybody asks, the
 * process has been frozen, resumed or killed and the line is gone - a hundred and twenty lines
 * of a normal drive push it out even when the process does survive. It has been lost more than
 * once.
 *
 * <p>So these go to a file, and the file lives on <b>internal storage</b>, not on the stick.
 * That is deliberate and it is the whole point: internal storage is ext4 and journalled, so
 * writing to it during a shutdown is safe. The stick is FAT, has no journal, and being caught
 * writing to it at that exact moment is the fault being investigated - adding one more write
 * there would be the diagnosis causing the disease.
 *
 * <p>Small on purpose. A few hundred bytes, appended and forced to disk immediately, trimmed to
 * the last {@link #MAX_LINES} whenever it grows past them: a journal nobody has to maintain.
 */
public final class StandbyJournal {

    private static final String TAG = "StandbyJournal";
    private static final String FILE_NAME = "standby.log";

    /** Enough for several ignition cycles, far too little to be worth managing. */
    private static final int MAX_LINES = 40;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private StandbyJournal() {
    }

    private static File fileIn(@NonNull Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * Appends one line and forces it to disk before returning.
     *
     * <p>The sync is the reason this class exists. A line sitting in the page cache when the
     * power goes is exactly as useful as no line at all.
     */
    public static void add(@NonNull Context context, @NonNull String message) {
        String line = STAMP.format(new Date()) + "  " + message + "\n";
        synchronized (LOCK) {
            File file = fileIn(context);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } catch (Throwable t) {
                Log.w(TAG, "could not append to the standby journal", t);
                return;
            }
            trimIfLong(file);
        }
    }

    /** Keeps the tail and drops the rest, so the file never needs looking after. */
    private static void trimIfLong(@NonNull File file) {
        try {
            Deque<String> kept = new ArrayDeque<>();
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                String line;
                int total = 0;
                while ((line = in.readLine()) != null) {
                    total++;
                    kept.addLast(line);
                    while (kept.size() > MAX_LINES) {
                        kept.removeFirst();
                    }
                }
                if (total <= MAX_LINES) {
                    return;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String kept_line : kept) {
                sb.append(kept_line).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not trim the standby journal", t);
        }
    }

    /** What the journal holds, for the report. */
    @NonNull
    public static String snapshot(@NonNull Context context) {
        synchronized (LOCK) {
            File file = fileIn(context);
            if (!file.isFile()) {
                return "nothing recorded yet";
            }
            StringBuilder sb = new StringBuilder();
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Throwable t) {
                return "could not be read: " + t;
            }
            return sb.length() == 0 ? "empty" : sb.toString();
        }
    }
}
