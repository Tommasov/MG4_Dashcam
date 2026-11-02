package com.drivehub.kamera.extract;

import com.drivehub.kamera.dev.DevRuntimeLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A web server for one job: getting footage off the car and onto a phone.
 *
 * <p>The shape of this - the page grouped by day, the play button, the byte ranges - is taken
 * from the separate probe app that was written to find out whether any of it was possible, and
 * which answered on the car on 2026-09-24: port 8080 bound at the first attempt, no policy
 * refusing a listening socket, and a real phone pulling a clip off the head unit at
 * <b>6.2 MB/s</b> - a thirty-megabyte clip in five seconds.
 *
 * <p>Read-only on purpose. Nothing here deletes, renames or writes anything on the medium: the
 * phone is a window, and a window that could empty the ring buffer from the far side of the car
 * park is a window nobody asked for.
 */
public final class ExtractionServer {

    private static final String TAG = "Extraction";

    /** Tried in order. An ephemeral port last, so a closed 8080 is not the end of it. */
    private static final int[] PORTS = {8080, 8888, 0};

    private static final String CLIP_SUFFIX = ".mp4";

    /** Eight megabytes of nothing, for timing a transfer without touching the footage. */
    private static final int SPEED_TEST_BYTES = 8 * 1024 * 1024;

    private final File recordsDir;
    private ServerSocket socket;
    private volatile boolean running;
    private volatile long lastRequestAtMs;
    private volatile int requests;

    public ExtractionServer(File recordsDir) {
        this.recordsDir = recordsDir;
    }

    /** @return the port it is listening on, or -1 if nothing could listen. */
    public int start() {
        for (int port : PORTS) {
            try {
                socket = new ServerSocket(port);
                break;
            } catch (Throwable t) {
                DevRuntimeLog.add(TAG, "port " + (port == 0 ? "ephemeral" : port)
                        + " refused: " + t);
                socket = null;
            }
        }
        if (socket == null) {
            DevRuntimeLog.add(TAG, "nothing could listen: that is this device's policy saying no");
            return -1;
        }
        running = true;
        lastRequestAtMs = System.currentTimeMillis();
        final ServerSocket server = socket;
        DevRuntimeLog.add(TAG, "listening on port " + server.getLocalPort()
                + ", serving " + recordsDir.getAbsolutePath());
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    handle(server.accept());
                } catch (Throwable t) {
                    if (running) {
                        DevRuntimeLog.add(TAG, "accept failed: " + t);
                    }
                    break;
                }
            }
        }, "extraction-accept");
        accept.setDaemon(true);
        accept.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        ServerSocket server = socket;
        socket = null;
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignored) {
                // Closing is what we wanted; a failure here changes nothing.
            }
        }
        DevRuntimeLog.add(TAG, "server stopped after " + requests + " request(s)");
    }

    /** When the phone last asked for anything, which is what the idle timeout watches. */
    public long lastRequestAtMs() {
        return lastRequestAtMs;
    }

    public int requestCount() {
        return requests;
    }

    // ---------- requests ----------

    private void handle(Socket client) {
        Thread worker = new Thread(() -> {
            try (Socket s = client) {
                s.setSoTimeout(20000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                String request = in.readLine();
                String range = null;
                String header;
                while ((header = in.readLine()) != null && !header.isEmpty()) {
                    if (header.toLowerCase(Locale.US).startsWith("range:")) {
                        range = header.substring(6).trim();
                    }
                }
                lastRequestAtMs = System.currentTimeMillis();
                requests++;

                String path = "/";
                if (request != null) {
                    String[] parts = request.split(" ");
                    if (parts.length > 1) {
                        path = parts[1];
                    }
                }
                OutputStream out = s.getOutputStream();
                if (path.startsWith("/speed")) {
                    serveSpeed(out);
                } else if (path.startsWith("/file")) {
                    serveFile(out, path, range);
                } else {
                    serveIndex(out);
                }
                out.flush();
            } catch (Throwable t) {
                // A browser playing a video aborts ranges constantly; "broken pipe" here is
                // ordinary and not worth a line of its own in the report.
                String message = String.valueOf(t.getMessage());
                if (!message.contains("Broken pipe") && !message.contains("connection abort")) {
                    DevRuntimeLog.add(TAG, "request failed: " + t);
                }
            }
        }, "extraction-conn");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The page, which has to answer one question: which of these hundred files is the moment.
     *
     * <p>The clips are named after the second they started, which is perfect for a computer and
     * useless at arm's length, so the list is written in the language the question is asked in:
     * a day, a wall-clock time, a size. Newest first, because what is being looked for is
     * nearly always the most recent thing that happened.
     *
     * <p>Every row can be played before it is taken. Downloading thirty megabytes to find out it
     * was the wrong half-minute is the failure this page exists to prevent, and playing in place
     * costs nothing once byte ranges are served.
     */
    private void serveIndex(OutputStream out) throws Exception {
        List<File> clips = clips();
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Dashcam</title><style>")
                .append("body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:16px;")
                .append("background:#14181a;color:#eef2f4}")
                .append("h1{font-size:20px;margin:0 0 4px}")
                .append("p.sub{color:#9aa6ad;margin:0 0 20px;font-size:14px}")
                .append("h2{font-size:15px;color:#9aa6ad;margin:24px 0 8px;font-weight:600}")
                .append("ul{list-style:none;margin:0;padding:0}")
                .append("li{display:flex;align-items:center;gap:12px;padding:10px 12px;")
                .append("border-radius:10px;background:#1e2427;margin-bottom:6px}")
                .append(".t{font-variant-numeric:tabular-nums;font-size:17px;min-width:5.5em}")
                .append(".m{color:#9aa6ad;font-size:13px;flex:1}")
                .append("a{color:#4da3ff;text-decoration:none;padding:6px 10px;border-radius:8px;")
                .append("background:#263035;font-size:14px}")
                .append("video{width:100%;margin:8px 0;border-radius:10px;background:#000}")
                .append("</style>");
        b.append("<h1>Dashcam</h1><p class=sub>").append(clips.size()).append(" clip");
        if (clips.isEmpty()) {
            b.append("</p><p>Nessun filmato in ").append(escape(recordsDir.getAbsolutePath()))
                    .append("</p>");
        } else {
            b.append(" &middot; <a href='/speed'>test velocit&agrave;</a></p>");
        }

        SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String day = "";
        boolean open = false;
        for (File f : clips) {
            Date when = startedAt(f);
            String thisDay = dayFormat.format(when);
            if (!thisDay.equals(day)) {
                if (open) {
                    b.append("</ul>");
                }
                b.append("<h2>").append(escape(thisDay)).append("</h2><ul>");
                day = thisDay;
                open = true;
            }
            String url = "/file?n=" + URLEncoder.encode(f.getName(), "UTF-8");
            b.append("<li><span class=t>").append(timeFormat.format(when)).append("</span>")
                    .append("<span class=m>").append(f.length() / 1048576).append(" MB</span>")
                    .append("<a href='#' onclick=\"var v=document.createElement('video');")
                    .append("v.controls=true;v.src='").append(url)
                    .append("';this.parentNode.after(v);this.remove();return false\">guarda</a>")
                    .append("<a href='").append(url).append("&amp;dl=1'>scarica</a></li>");
        }
        if (open) {
            b.append("</ul>");
        }
        byte[] body = b.toString().getBytes("UTF-8");
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(body);
    }

    /**
     * One clip, whole or in pieces.
     *
     * <p>Byte ranges are what make the play button work: a browser asked to play a file it
     * cannot seek in fetches all of it before showing a frame, which on a thirty-megabyte clip
     * is the opposite of a preview.
     */
    private void serveFile(OutputStream out, String path, String range) throws Exception {
        String name = parameter(path, "n");
        boolean download = parameter(path, "dl") != null;
        File f = byName(name);
        if (f == null) {
            byte[] body = "non trovato".getBytes("UTF-8");
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes());
            out.write(body);
            return;
        }
        long total = f.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] parts = range.substring(6).split("-", -1);
            try {
                if (!parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0].trim());
                }
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1].trim());
                }
                partial = true;
            } catch (NumberFormatException ignored) {
                // A range we cannot read is a range we do not honour; the whole file is correct.
                partial = false;
            }
        }
        if (start < 0 || start >= total) {
            start = 0;
        }
        if (end >= total || end < start) {
            end = total - 1;
        }
        long length = end - start + 1;

        StringBuilder head = new StringBuilder();
        head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: video/mp4\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(length).append("\r\n");
        if (partial) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n");
        }
        if (download) {
            // So the phone saves it with its own name instead of opening a player.
            head.append("Content-Disposition: attachment; filename=\"")
                    .append(f.getName()).append("\"\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes());

        try (FileInputStream in = new FileInputStream(f)) {
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            byte[] buffer = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                left -= n;
            }
        }
    }

    private void serveSpeed(OutputStream out) throws Exception {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Length: " + SPEED_TEST_BYTES
                + "\r\nConnection: close\r\n\r\n").getBytes());
        byte[] block = new byte[64 * 1024];
        int left = SPEED_TEST_BYTES;
        while (left > 0) {
            int n = Math.min(block.length, left);
            out.write(block, 0, n);
            left -= n;
        }
    }

    // ---------- the clips ----------

    private List<File> clips() {
        File[] all = recordsDir == null ? null : recordsDir.listFiles(
                (d, name) -> name.toLowerCase(Locale.US).endsWith(CLIP_SUFFIX));
        if (all == null) {
            return new ArrayList<>();
        }
        List<File> clips = new ArrayList<>(Arrays.asList(all));
        Collections.sort(clips, Comparator.comparingLong((File f) -> startedAt(f).getTime())
                .reversed());
        return clips;
    }

    private File byName(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            // Not a name we gave out, so not a file we serve. The list is the only way in.
            return null;
        }
        File f = new File(recordsDir, name);
        return f.isFile() ? f : null;
    }

    /**
     * When a clip was filmed, from its name.
     *
     * <p>The name is yyMMddHHmmss, written by the recorder from the car's clock. The file's own
     * modified time is the moment it was <i>closed</i>, half a minute later and sometimes much
     * more, and on a cold start the car's clock had not arrived yet - so the name is both more
     * accurate and the only thing that stays put.
     */
    private static Date startedAt(File f) {
        String name = f.getName();
        int dot = name.indexOf('.');
        String stamp = dot > 0 ? name.substring(0, dot) : name;
        if (stamp.length() >= 12) {
            try {
                return new SimpleDateFormat("yyMMddHHmmss", Locale.US)
                        .parse(stamp.substring(0, 12));
            } catch (Throwable ignored) {
                // Falls through to the modified time.
            }
        }
        return new Date(f.lastModified());
    }

    private static String parameter(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    return java.net.URLDecoder.decode(value, "UTF-8");
                } catch (Throwable t) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
