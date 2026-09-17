# MG4 Dashcam

A loop-recording dashcam for the MG4 EV (pre-facelift, AAOS 9 / API 28). It records the four
factory cameras into one clip on a rolling buffer and does nothing else.

This is a stripped fork of [jamakr4/MG4-360-Camera-App](https://github.com/jamakr4/MG4-360-Camera-App),
whose author did the hard part: finding out how to get frames out of this vehicle's cameras at
all. See [Relationship to upstream](#relationship-to-upstream).

> **Status: early.** Confirmed on the vehicle: it records the loop to a USB stick at a measured
> 25 fps with no dropped frames, and yields the cameras to the factory 360 view. Long-term use,
> the internal-storage target and the event save have had no real mileage yet. Treat every
> release as a test build.

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
- Reads, copies and clears what the factory 360 app leaves in its own private folder - see
  [The factory app's hidden recorder](#the-factory-apps-hidden-recorder).

What it deliberately does **not** do: tile view, turn-signal overlay, digital rearview mirror,
floating banners, in-app updates. Those are upstream's, and upstream is where they belong.

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

Those stills are the reference for what the cameras actually deliver: **720x480 per camera,
progressive, no interlace combing**. This app records cells of 720x240, because the V4L2 buffer
holds two 240-row halves stacked and the capture path keeps one of them.

That costs vertical resolution, not field of view - a 720x240 cell stretched back to 720x480 is
a complete, correctly proportioned fisheye, matching the factory still. Raising the crop to 480
would not help, since it would yield the same picture twice; the two halves would have to be
interleaved. Whether that is worth doing depends on something still untested: if the two halves
are captured 1/50 s apart, weaving them combs every moving object, and keeping one is the right
choice. Recording with `RecordActivity` **while driving** and looking for combing in the
1440x960 output settles it.

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
- Ships the native library as a prebuilt instead of building it, since the sources are
  unchanged.
- New application id and app name; the Java namespace stays `com.drivehub.kamera` because the
  JNI entry points are bound by name.
