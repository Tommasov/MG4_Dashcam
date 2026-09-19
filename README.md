# MG4 Dashcam

A loop-recording dashcam for the MG4 EV (pre-facelift, AAOS 9 / API 28). It records the four
factory cameras into one clip on a rolling buffer and does nothing else.

This is a stripped fork of [jamakr4/MG4-360-Camera-App](https://github.com/jamakr4/MG4-360-Camera-App),
whose author did the hard part: finding out how to get frames out of this vehicle's cameras at
all. See [Relationship to upstream](#relationship-to-upstream).

> **Status: it works, on one car.** A full commute on 18 September 2026 produced 27 minutes of
> continuous recording: 51 consecutive clips, no crash, no dropped service, and every file
> closed properly by the muxer. Two hand-offs to the factory 360 view happened during that run
> — one on the move, one while parking — and recording picked itself back up both times.
>
> That car is the author's. Nobody else's MG4 has run this, and the internal-storage target and
> the event save still have little real mileage. Two known limits: the app refuses to choose
> when **two** USB volumes are connected, and sharing one stick with music playback can make
> the music stutter — both are being worked on.

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_Dashcam/screenshot-day.png" alt="The dashcam screen on the head unit, light theme" width="90%">
</p>
<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_Dashcam/screenshot-night.png" alt="The same screen at night" width="90%">
</p>

<p align="center">
  <em>One screen, sized for 1920x720 read at arm's length. The badge answers the only question
  worth asking from the driver seat.</em>
</p>

## What it does

- Records front, rear, left and right into a single 1200x800 clip at 25 fps, in 30-second
  segments, with a footer showing the time, the speed and a signature of your choice.
- Keeps the most recent N segments and deletes the rest. Default: 10 segments, about
  5 minutes and 340 MB.
- Saves an **event** on request: the segments around the moment of the request are copied into
  a folder the ring buffer never touches. Trigger it with a broadcast:

  ```
  am broadcast -a com.drivehub.kamera.action.TRIGGER_DASHCAM_EVENT -p com.tommasov.mg4dashcam
  ```

  If the loop is not running, the event captures the next three segments instead of the
  previous ones.
- Records to internal storage, to a USB stick, or to USB with a fall back to internal.
- Yields the cameras while the factory 360/reverse view is on screen, so the stock reversing
  camera keeps working. Turning this off means the OEM app gets "Device or resource busy" for
  as long as the dashcam is recording.
- Starts itself on boot when the switch is on.
- Says what it is doing from across the cabin: a green **ON** badge while recording, amber
  while it has handed the cameras over, red when something is wrong. Green is checked rather
  than claimed - a watchdog turns it red if no clip has been written for three segment lengths,
  because a badge that lies is worse than no badge.
- Reads, copies and clears what the factory 360 app leaves in its own private folder - see
  [The factory app's hidden recorder](#the-factory-apps-hidden-recorder).
- Sends a diagnostics report, on request and after a confirmation - see
  [Diagnostics](#diagnostics).
- Speaks English and Italian, and follows the head unit between its light and dark themes.

What it deliberately does **not** do: tile view, turn-signal overlay, digital rearview mirror,
floating banners, in-app updates. Those are upstream's, and upstream is where they belong.

## What a recording looks like

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_Dashcam/video-frame.png" alt="One recorded frame: front and rear stacked in the middle, the side cameras rotated down each edge, a footer with the date, time and speed" width="90%">
</p>

<p align="center">
  <em>One frame as 1.0.0 records it.</em>
</p>

Two things about that frame are wrong, and both come from a layout that was inherited rather
than designed. Upstream's app shows one camera at a time, full screen on a 1920x720 head unit,
so its cell is 720x240 - near enough the shape of the screen. The grid was assembled out of
those cells afterwards.

The canvas height is decided by the **side** cameras: they are 720 wide at the source, so they
are 720 tall once rotated. The centre column stacks two 240-row cells and reaches 480, leaving
120 rows of black above and below. **Eighteen per cent of every frame is black**, and it costs
the same bitrate as picture does.

The cells are also the wrong shape. The camera buffer holds two stacked 240-row fields rather
than one 480-row image; the app keeps a single field, which is the whole field of view at half
the vertical resolution. A 720x240 cell should therefore be shown as 720x480 - and it is not, so
the fisheye circles come out as ellipses: **front and rear squashed vertically by 2x, and the
sides squashed horizontally by the same amount** once they have been rotated. Look at the left
and right strips above and you are looking at exactly that: a usable view, compressed into
something you cannot read.

It matters more here than it would elsewhere. Wrong proportions make distance and speed hard to
judge, in footage somebody may one day have to read carefully.

### What the next major version records

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_Dashcam/video-frame-next.png" alt="A recorded frame from the new layout: a 2x2 grid with front and rear on top, left and right below, all four in their true proportions" width="90%">
</p>

<p align="center">
  <em>Not a mock-up any more: a frame recorded by 1.1.0-beta.8.</em>
</p>

A 2x2 grid at 1440x1040. Every cell at its true 720x480 shape, no rotation, no black. Front and
rear sit side by side on top, which is the pair you want together when you are working out who
came from where; left and right go below, each on the side it belongs to.

It records at **25 fps**, the same as the factory 360 app, with the cameras delivering 29.9
each. Getting there meant finding out why four cameras open together managed six frames a
second - see [docs/capture-performance.md](docs/capture-performance.md).

The vertical resolution is the part still owed. Each cell is the top field of the interlaced
buffer, 720x240, stretched to 720x480; the other field's lines are in the buffer and are being
thrown away. Weaving them back was written and measured - **127% more vertical detail**, see
[docs/camera-format.md](docs/camera-format.md) - and then parked, because doing it in software
cost half the frame rate. The way back to it is the MediaTek hardware de-interlacer the factory
app uses, which gives full-height cells and still holds 25 fps.

The frame above was recorded by 1.1.0-beta.8, which did weave the fields. What the current
build records has the same geometry and a softer vertical detail.

Three smaller decisions came with it:

- **The rear view stops being mirrored.** Upstream flips it horizontally to match what a driver
  expects from a mirror, which is right when you are reversing and wrong in an archive: it
  reverses every number plate behind you.
- **The stretch is bilinear**, not something sharper. Lanczos cost 61% more bitrate at equal
  quality and bicubic 57%, against bilinear's 41%: a sharper filter invents edges the encoder
  then pays for, and there is no real detail there to recover.
- **The bitrate stays at 9 Mbit/s.** Quality per pixel drops, but today almost a fifth of those
  bits go on black and the side views are unreadable anyway. Spending the same budget on picture
  is the better trade, and it keeps the write rate - and the USB stick - where it is.


## Installing

The app declares `android:sharedUserId="android.uid.system"`. That is what lets it open the
`/dev/video*` nodes, and it means **the APK must be signed with the head unit's platform key**
or it will not install. The MG4 ships with the public AOSP platform key, so you can sign it
yourself:

```shell
# once: fetch the public AOSP platform key pair
curl -sL "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/platform.pk8?format=TEXT" | base64 -d > platform.pk8
curl -sL "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/platform.x509.pem?format=TEXT" | base64 -d > platform.x509.pem

# per build
./gradlew :app:assembleRelease
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk mg4dashcam.apk
apksigner sign --key platform.pk8 --cert platform.x509.pem mg4dashcam.apk
apksigner verify --print-certs --min-sdk-version 28 mg4dashcam.apk
```

Then `adb install -r mg4dashcam.apk`, or copy it to a USB stick and install it from the
vehicle's Files app. Upstream's README documents how to get ADB working on the head unit and
which firmware versions still allow it; that part applies here unchanged.

This fork uses its own application id (`com.tommasov.mg4dashcam`), so it installs alongside
upstream's app rather than replacing it. Both cannot record at the same time: they contend for
the same camera devices.

## Building

No NDK, CMake or OpenCV SDK needed. `app/src/main/jniLibs/arm64-v8a/libcameraprobe.so` is
upstream's own build of the C++ in `app/src/main/cpp/`, which this fork does not modify — see
[`app/src/main/jniLibs/README.md`](app/src/main/jniLibs/README.md) for its provenance and for
how to rebuild it from source instead.

Everything else is a normal Gradle build against the Android SDK.

One optional file: `apikeys.properties` at the root, git-ignored, holding `probe.key` for the
diagnostics endpoint. Without it the build succeeds and the send button is not shown, which is
the intended behaviour for anyone building this who is not the author.

## Sharing the cameras with the car

There are four camera devices and only one of anything can hold them, so when the factory
around-view app wants them the dashcam has to let go - for reverse, for the steering-wheel
button, for the indicator view at low speed. It is a race, and losing it looks like the screen
dimming with no picture behind it.

Three things decide it:

- The broadcast from the factory app is answered **in the receiver**, which raises the flags and
  interrupts the recording thread directly. Going through the service first cost service
  creation and scheduling before anything was released, which was time spent on the wrong side
  of the race.
- A second detector watches which app is actually in front, once a second. It is slower than a
  broadcast but catches every route into the factory camera, including any this project has not
  mapped, and it notices when the factory app goes away without saying so.
- A pause never lasts more than a minute. Whatever was missed - a signal, a broadcast, a stale
  reading - a dashcam that stays paused is a dashcam that is not recording, and contending
  briefly with the factory camera is the lesser failure.

Above **20 km/h** the cameras are not handed over, which is adjustable in the app. The factory
view stops being offered around 15 km/h anyway, so the margin errs upwards on purpose: too high
costs an occasional lost segment, too low leaves the reversing camera dark.

The clip in progress is closed when the cameras go, so a hand-off costs seconds, not a file. If
the encoder cannot finish it in time the fragment is deleted rather than left behind: an
unreadable clip would otherwise take a retention slot from one that can be watched.

## Storage, and what it costs

At 25 fps and 9 Mbit/s a clip is about **67 MB per minute, 4 GB per hour**. With the default
10-segment buffer that is only ~340 MB on disk, so space is not the problem — continuous
rewriting is. An hour of driving a day writes roughly 1.5 TB a year onto the head unit's eMMC.

Two things worth knowing before turning the retention up:

- The canvas and the bitrate do not depend on how many cameras you select. Recording only the
  front camera still produces a full 1200x800 frame at 9 Mbit/s, with the other three
  quadrants black. Deselecting cameras saves camera bandwidth, not storage.
- Both numbers are constants in the code (`RecordingService.recordClip` and the sink
  constructor in `camera_stream_manager.cpp`). Lowering them means editing and, for the
  canvas, rebuilding the native library.

### Recording to a USB stick

A stick takes the eMMC out of the picture, and this fork can use one. Where the clips land is
not where you would expect, and the reason is worth knowing before reading either codebase.

Android has refused general write access at the root of a secondary volume since KitKat. On
this vehicle the same 32 GB stick reports, to the app itself:

    /storage/9EFB-89C8                          r=true  w=false
    /mnt/media_rw/9EFB-89C8                     r=true  w=true
    /storage/9EFB-89C8/Android/data/<pkg>/files r=true  w=true

Upstream probes only the first of those, so its USB support could not have worked on any MG4,
whatever the stick or the permissions. This fork tries three locations per volume and keeps the
first that survives an actual write:

1. `/mnt/media_rw/<uuid>/dashcam` — the raw vold mount. Reachable because the app runs as
   uid 1000, and the one that puts clips at the root of the stick, where someone plugging it
   into a computer will look for them.
2. `<volume>/dashcam` — what upstream tries. Kept in case some firmware allows it.
3. `.../Android/data/com.tommasov.mg4dashcam/files/dashcam` — the app's own sandbox. Always
   writable, but **deleted when the app is uninstalled**.

**Show storage details**, on the main screen, prints which one was chosen and, for a volume it
rejected, why it was rejected. The head unit has no adb, so that button is the only way to see
any of this from inside the car.

Use **Stop and eject USB** before pulling the stick: it stops the loop and waits for the
pending writes instead of truncating the clip in flight.

## The factory app's hidden recorder

The stock around-view app ships a screen that is not on any menu:
`com.saicmotor.hmi.aroundview.RecordActivity`. It is reachable from outside because its
intent-filter carries the invented action `android.intent.action.MAIN3` with
`category.LAUNCHER` - a trick that keeps it out of the launcher while leaving it exported,
since the app targets API 29 and an intent-filter makes `exported` default to true there.

```
am start -n com.saicmotor.hmi.aroundview/.RecordActivity
```

or point any activity-launcher app at that component. It shows the four cameras live with two
buttons in Chinese: start/stop video, and capture a single frame.

It is safe to open. Its only calls are `V4l2Utils.init`, `startRecordVideo`, `stopRecordVideo`,
`saveCameraImageX4`, `setAngle`, `setSpeed` and `unInit`. Nothing touches calibration, which on
this vehicle is not stored on the head unit at all - the unit has no persist partition, the app
holds no calibration file, and a factory reset does not lose it.

**Turn loop recording off first.** `RecordActivity` does not emit the AVM broadcasts that
`AVMActivity` sends, so the automatic hand-off that yields the cameras to the factory app does
not cover this screen. With the dashcam running, whichever opens the devices second gets
nothing. And the video button has no time limit: it records until pressed again.

### What it writes

Into `/data/user/0/com.saicmotor.hmi.aroundview/files/`, and it never cleans up:

| file | what it is |
|---|---|
| `720x480_nv12_<ms>_0..3.nv12` | four raw NV12 stills, one per camera, 518400 bytes each |
| `720x480_<ms>.h264` | the four cameras as one **1440x960** raw H.264 stream at 25 fps - the name is the per-camera size, not the frame size |
| `test_720x480_0.nv12` | a debug dump whose path is hardcoded inside `libv4l2utils.so`; one camera only, overwritten every time |

Channel order is the 2x2 reading order: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right.
On this car channel 0 is the left camera. That numbering is the factory app's own and does not
match this app's camera mask, where bit 0 is the front camera.

That directory is private to the factory app, so a file manager cannot reach it - but that app
declares `android:sharedUserId="android.uid.system"` exactly as this one does, so both run as
uid 1000 and the files belong to us as much as to it. **Factory 360 app** on the main screen
lists them, copies them into the dashcam records folder, and deletes them.

To look at them on a computer:

```shell
ffmpeg -f rawvideo -pix_fmt nv12 -s 720x480 -i 720x480_nv12_1789590561951_0.nv12 cam0.png
ffmpeg -r 25 -i 720x480_1789590524395.h264 -c copy oem_360.mp4
```

Both are headerless streams: nothing in the file states its geometry or frame rate, so it has
to be given on the command line.

### Why it is worth knowing about

Those stills are 720x480 per camera, and they are what sent us looking at the capture format -
which turned out to be the most useful thing in this repository.

Each camera hands over a **720x480 interlaced** frame. This app records cells of 720x240, because
the capture path keeps one field and discards the other. That costs vertical resolution, not
field of view: a 720x240 cell stretched back to 720x480 is a complete, correctly proportioned
fisheye. But the missing scan lines are not gone - they are in the same buffer, in rows 240-479,
and interleaving the two halves recovers **127% more vertical detail**, measured.

The driver reports `field=V4L2_FIELD_NONE`, a progressive frame. It is wrong. And the factory
stills are not evidence to the contrary: the factory app runs the frames through the MediaTek
hardware de-interlacer first - `libv4l2utils.so` exports `v4l2_OpenMtkDI`.

The evidence, the method for reproducing it, and what to do about it are in
[docs/camera-format.md](docs/camera-format.md).

### Reading all four at once

Four cameras open together delivered six frames a second each, against the factory app's
twenty-five, and the cause was not in the compositor: the buffers `VIDIOC_REQBUFS` hands back
are **not cached**, and the CPU reads them at about 85 MB/s. Every extra pass over one costs
another four milliseconds, so a conversion that touches the mapped buffer three times is
slower than one that touches it once, however much less memory it moves overall.

Reading the buffer once and keeping per-frame housekeeping off the hot path took capture to
29.9 fps per camera and the recording to 24.4. How to tell a slow device from a slow reader,
what else looked guilty and was not, and two ways of measuring a frame rate that give
confidently wrong answers, are in
[docs/capture-performance.md](docs/capture-performance.md).

## Diagnostics

This head unit gives a developer almost nothing to work with. No adb once the firmware is new
enough, no real browser, and nothing on board that accepts a share: the firmware ships the
Bluetooth stack with `profile_supported_opp` false, which disables the one activity handling
`ACTION_SEND`. There is no text field to paste into either. A log that cannot leave the car can
only be photographed off the screen.

So there are two ways out, and the app prefers the one that costs nothing to read:

- **Show storage details** prints, on the car, what the storage probe saw, what the factory 360
  app has left in its folder, and the runtime log - every hand-off, every pause, every failure,
  with timings.
- **Send a diagnostics report** posts the same text to the author's endpoint. It asks first,
  states exactly what will be sent, and asks for one line about what you were doing, which is
  the half that makes the other half readable. Nothing leaves the car until you confirm, and
  the report carries no video, no images and no location.

The report endpoint can only accept: it cannot read a report back, list what is there or delete
anything, with any key. That is what makes it safe to ship its write key inside an APK anyone
can unzip. The key lives in `apikeys.properties`, which is git-ignored - a clone without one
still builds, and simply does not offer the button.

## Graphic resources

The switch track and thumb are taken from the vehicle's own system software, so that a toggle
in this app is the toggle the driver already knows rather than a phone control dropped into a
car. The palette and the type scale beside them are measured from the factory launcher, not
copied from it: values, not artwork.

Those images are **not licensed to this project**. They remain the property of SAIC/MG and
their respective owners, and are included here only so that the app can match the system on a
vehicle that already contains them. No ownership is claimed over them, and their presence
implies no permission, endorsement or affiliation. Anyone who redistributes this project, or
builds on it, does so under their own responsibility. The same applies to trademarks and brand
names, used here descriptively only.

Note that the GPL-3.0 licence covering this code says nothing about third-party assets sitting
in the tree: the two coexist, and forking this repository does not place those images under the
GPL.

## Relationship to upstream

Upstream is GPL-3.0 and so is this, as required. The work that matters — the V4L2 device
mapping for this vehicle, the doubled frame height the devices report, the encoder colour
format this SoC accepts, the OEM AVM broadcast names recovered from the factory app — is
[jamakr4](https://github.com/jamakr4)'s, and it lives in `app/src/main/cpp/` and in the
`dashcam/` package, both kept here with their history.

Changes made in this fork:

- Removed the tile view, turn-signal overlay, digital rearview mirror, in-app updater, the
  settings dialog, the developer panel and the floating banner service. The banner service was
  replaced by toasts (`DashcamNotice`), the settings dialog by a single screen.
- `DashcamSettingsController` became `DashcamSettings`, a prefs accessor with no views.
- Re-enabled the storage target preference. Upstream's `DashcamStorageManager` ignores it and
  hardcodes internal storage, which is worth knowing if you are reading upstream's README:
  it documents a choice the code does not honour.
- Writes to a volume's raw mount, or to the app sandbox, when the root of the FUSE view
  refuses — see [Recording to a USB stick](#recording-to-a-usb-stick). Upstream probes only
  `/storage/<uuid>`, which no app may write to.
- Logs what the storage probe saw at each step, and surfaces it on screen, because a head unit
  with no adb cannot be asked afterwards.
- Reaches into the factory 360 app's private directory, which is possible because both declare
  the same system shared user, to recover and clear what its hidden recorder leaves there.
- Hardened the recording loop: an exception can no longer kill the worker quietly, leaving the
  service alive and the UI claiming to record. A stalled loop turns the badge red instead.
- Made the camera hand-off fast enough to win its race, added a second detector that watches the
  foreground app, and capped a pause at a minute - see
  [Sharing the cameras with the car](#sharing-the-cameras-with-the-car).
- Ships the native library as a prebuilt instead of building it, since the sources are
  unchanged.
- New application id and app name; the Java namespace stays `com.drivehub.kamera` because the
  JNI entry points are bound by name.
