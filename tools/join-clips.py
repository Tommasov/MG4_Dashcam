#!/usr/bin/env python3
"""Join dashcam clips into one video, without re-encoding.

The app writes 30-second clips named after the moment they started, so a drive arrives as a few
dozen files that sort correctly by name and nothing else. This turns them into one video.

Nothing is re-encoded. Every clip comes out of the same encoder with the same settings, so
ffmpeg's concat demuxer can copy the packets across untouched: the result is bit-for-bit the
same video, and it takes seconds rather than the half hour a re-encode would.

Two things are added on the way through:

  * **Chapters**, one per source clip, titled with the wall-clock time the clip started. A
    twenty-seven minute video is no use if you then have to scrub through it looking for the
    moment; with chapters, a player jumps straight to 09:19:29.

  * **A gap report.** Recording is not continuous - a clip is cut short when the factory 360
    view takes the cameras, and the rotation between clips costs a moment. Those gaps close up
    silently when the clips are joined, so the video runs shorter than the wall clock and every
    time after a gap is shifted. The report says where that happened and by how much, and the
    chapter titles keep the real times readable regardless.

Usage:

    python join-clips.py [FOLDER] [-o OUTPUT] [--per-drive] [--gap SECONDS] [--dry-run]

With no folder, works on the current directory. With --per-drive, writes one video per drive
instead of one overall, splitting wherever the recording stopped for longer than --gap.
"""

import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

# The app's clip names: yyMMddHHmmss.mp4, as makeTimestampBase() writes them.
CLIP_NAME = re.compile(r"^(\d{12})\.mp4$", re.IGNORECASE)

# Longer than this between the end of one clip and the start of the next, and it is a separate
# drive rather than a hiccup. A hand-off to the 360 view costs seconds; parking costs minutes.
DEFAULT_DRIVE_GAP_S = 300.0


class Clip:
    def __init__(self, path, started, duration, stream):
        self.path = path
        self.started = started
        self.duration = duration
        self.stream = stream

    @property
    def ended(self):
        return self.started + datetime.timedelta(seconds=self.duration)


def tool(name):
    found = shutil.which(name)
    if not found:
        sys.exit("%s is not on the PATH. Install ffmpeg and try again." % name)
    return found


def probe(path):
    """Duration and stream shape, so mismatched clips can be refused rather than mangled."""
    out = subprocess.run(
        [tool("ffprobe"), "-v", "error", "-print_format", "json",
         "-show_format", "-show_streams", path],
        capture_output=True, text=True)
    if out.returncode != 0:
        return None, None
    info = json.loads(out.stdout)
    video = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), None)
    if video is None:
        return None, None
    duration = float(info.get("format", {}).get("duration", 0.0))
    # Kept as a tuple so clips that cannot be copy-joined are caught before ffmpeg writes a file
    # that plays for ten seconds and then falls apart.
    #
    # The frame rate is deliberately not part of it. The app stamps each frame with the moment
    # it was composed rather than with a slot on a fixed grid, so the spacing is not uniform and
    # ffprobe guesses a different r_frame_rate for every clip - 25/1 for one, 151/6 for the
    # next, 90000/1 for a third. None of that stops the packets being copied across: the codec,
    # the picture size and the pixel format are what have to match.
    stream = (video.get("codec_name"), video.get("width"), video.get("height"),
              video.get("pix_fmt"))
    return duration, stream


def collect(folder):
    clips = []
    skipped = []
    for name in sorted(os.listdir(folder)):
        match = CLIP_NAME.match(name)
        if not match:
            continue
        path = os.path.join(folder, name)
        try:
            started = datetime.datetime.strptime(match.group(1), "%y%m%d%H%M%S")
        except ValueError:
            skipped.append((name, "unreadable timestamp"))
            continue
        duration, stream = probe(path)
        if duration is None or duration <= 0:
            # A clip the muxer never closed. The app deletes these itself, but one can survive on
            # a stick pulled at the wrong moment.
            skipped.append((name, "no playable video"))
            continue
        clips.append(Clip(path, started, duration, stream))
    clips.sort(key=lambda c: c.started)
    return clips, skipped


def split_drives(clips, gap_s):
    drives = []
    current = []
    for clip in clips:
        if current and (clip.started - current[-1].ended).total_seconds() > gap_s:
            drives.append(current)
            current = []
        current.append(clip)
    if current:
        drives.append(current)
    return drives


def hhmmss(seconds):
    seconds = int(round(seconds))
    return "%d:%02d:%02d" % (seconds // 3600, seconds % 3600 // 60, seconds % 60)


def escape_concat(path):
    # The concat demuxer takes a quoted path and treats backslash as an escape, which on Windows
    # is every separator in the path.
    return path.replace("\\", "/").replace("'", "'\\''")


def escape_metadata(text):
    return re.sub(r"([=;#\\\n])", r"\\\1", text)


def write_inputs(clips, directory):
    """The concat list, and the chapter metadata that rides alongside it."""
    list_path = os.path.join(directory, "clips.txt")
    with open(list_path, "w", encoding="utf-8") as handle:
        for clip in clips:
            handle.write("file '%s'\n" % escape_concat(os.path.abspath(clip.path)))

    meta_path = os.path.join(directory, "chapters.txt")
    with open(meta_path, "w", encoding="utf-8") as handle:
        handle.write(";FFMETADATA1\n")
        handle.write("title=%s\n" % escape_metadata(
            "Dashcam " + clips[0].started.strftime("%Y-%m-%d %H:%M")))
        offset = 0.0
        for clip in clips:
            start_ms = int(round(offset * 1000))
            offset += clip.duration
            end_ms = int(round(offset * 1000))
            if end_ms <= start_ms:
                continue
            handle.write("\n[CHAPTER]\nTIMEBASE=1/1000\n")
            handle.write("START=%d\nEND=%d\n" % (start_ms, end_ms))
            handle.write("title=%s\n" % escape_metadata(clip.started.strftime("%H:%M:%S")))
    return list_path, meta_path


def report_gaps(clips):
    gaps = []
    for previous, clip in zip(clips, clips[1:]):
        missing = (clip.started - previous.ended).total_seconds()
        if missing >= 1.0:
            gaps.append((previous, clip, missing))
    return gaps


def join(clips, output, dry_run):
    covered = (clips[-1].ended - clips[0].started).total_seconds()
    recorded = sum(clip.duration for clip in clips)
    gaps = report_gaps(clips)

    print("  %d clips, %s to %s" % (
        len(clips), clips[0].started.strftime("%Y-%m-%d %H:%M:%S"),
        clips[-1].ended.strftime("%H:%M:%S")))
    print("  %s of video over %s of wall clock" % (hhmmss(recorded), hhmmss(covered)))
    if gaps:
        print("  %d gaps, %s missing in total:" % (len(gaps), hhmmss(covered - recorded)))
        offset = 0.0
        index = {clip.path: None for clip in clips}
        for clip in clips:
            index[clip.path] = offset
            offset += clip.duration
        for previous, clip, missing in gaps:
            print("    at %s in the video  (%s -> %s, %.0fs)" % (
                hhmmss(index[clip.path]),
                previous.ended.strftime("%H:%M:%S"),
                clip.started.strftime("%H:%M:%S"), missing))
    else:
        print("  no gaps: the recording is continuous")

    if dry_run:
        print("  would write %s" % output)
        return

    workspace = tempfile.mkdtemp(prefix="joinclips-")
    try:
        list_path, meta_path = write_inputs(clips, workspace)
        command = [
            tool("ffmpeg"), "-hide_banner", "-loglevel", "warning", "-y",
            "-f", "concat", "-safe", "0", "-i", list_path,
            "-i", meta_path,
            "-map_metadata", "1",
            "-map", "0",
            "-c", "copy",
            # The clips carry no audio, but copying whatever streams exist keeps this honest if
            # that ever changes.
            "-movflags", "+faststart",
            output,
        ]
        result = subprocess.run(command)
        if result.returncode != 0:
            sys.exit("ffmpeg failed (%d)" % result.returncode)
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    size = os.path.getsize(output)
    print("  wrote %s  (%.1f MB, %d chapters)" % (output, size / 1e6, len(clips)))


def main():
    parser = argparse.ArgumentParser(
        description="Join MG4 Dashcam clips into one video, without re-encoding.")
    parser.add_argument("folder", nargs="?", default=".",
                        help="folder holding the clips (default: the current one)")
    parser.add_argument("-o", "--output",
                        help="output file (default: named after the first clip)")
    parser.add_argument("--per-drive", action="store_true",
                        help="one video per drive instead of one overall")
    parser.add_argument("--gap", type=float, default=DEFAULT_DRIVE_GAP_S,
                        help="seconds of silence that separate two drives (default: %(default)s)")
    parser.add_argument("--dry-run", action="store_true",
                        help="say what would happen and write nothing")
    args = parser.parse_args()

    folder = os.path.abspath(args.folder)
    if not os.path.isdir(folder):
        sys.exit("%s is not a folder" % folder)

    print("Reading %s" % folder)
    clips, skipped = collect(folder)
    for name, why in skipped:
        print("  skipped %s: %s" % (name, why))
    if not clips:
        sys.exit("No clips found. Expected names like 260918090457.mp4.")

    shapes = {clip.stream for clip in clips}
    if len(shapes) > 1:
        # Copying packets from differently-shaped clips into one file produces something that
        # plays until the first change and then stops. Better to say so than to write it.
        print("These clips were not all recorded with the same settings:")
        for shape in sorted(shapes, key=str):
            print("  %s" % (shape,))
        sys.exit("Refusing to join them by copying. Split them into folders, or re-encode.")

    groups = split_drives(clips, args.gap) if args.per_drive else [clips]
    if args.per_drive:
        print("%d drive(s), split at gaps over %s\n" % (len(groups), hhmmss(args.gap)))

    for group in groups:
        if args.output and len(groups) == 1:
            output = os.path.abspath(args.output)
        else:
            output = os.path.join(
                folder, group[0].started.strftime("dashcam-%Y%m%d-%H%M%S.mp4"))
        join(group, output, args.dry_run)
        print()


if __name__ == "__main__":
    main()
