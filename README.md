# MG4 Dashcam

A loop-recording dashcam for the MG4 EV (pre-facelift, AAOS 9 / API 28). It records the four
factory cameras into one clip on a rolling buffer and does nothing else.

This is a stripped fork of [jamakr4/MG4-360-Camera-App](https://github.com/jamakr4/MG4-360-Camera-App),
whose author did the hard part: finding out how to get frames out of this vehicle's cameras at
all. See [Relationship to upstream](#relationship-to-upstream).

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

Recording to a USB stick avoids the eMMC question entirely. Note that upstream forced internal
storage and left the USB code dormant; this fork re-enables it, but that path has never run in
a released build, so test it with a real stick before relying on it.

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
- Ships the native library as a prebuilt instead of building it, since the sources are
  unchanged.
- New application id and app name; the Java namespace stays `com.drivehub.kamera` because the
  JNI entry points are bound by name.
