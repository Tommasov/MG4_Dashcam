# Prebuilt native library

`arm64-v8a/libcameraprobe.so` is taken verbatim from upstream release **v0.8.7** of
<https://github.com/jamakr4/MG4-360-Camera-App> (asset
`MG4-360-Camera-App-v0.8.7-release.apk`, sha256
`6357701de57c2df8254868a75fee09a4a53b014cc53e791ab478f0d15fc89359`).

It is kept so that a fresh clone builds with nothing but the Android SDK. **It is no longer what
this app ships**: the sources in `app/src/main/cpp/` now differ from upstream's, and the
difference is the fix for a crash that took the whole process down every few minutes of
recording — see `camera_stream_manager.cpp`, `ensureStartedLocked`. Build from source to get it.

The corresponding source is kept in `app/src/main/cpp/` as required by the GPL-3.0 licence.

## Building from source instead

Add the OpenCV Android SDK path to `local.properties`, next to `sdk.dir`:

```properties
opencv.dir=D:/Android/OpenCV-android-sdk
```

That one line switches `app/build.gradle` over: with it, the library is compiled from
`src/main/cpp` and this directory is left out of the APK; without it, the prebuilt here is used
and no native toolchain is needed. Nothing else to edit — `OpenCV_DIR` is passed to CMake from
there, rather than hardcoded as upstream does with its author's own home directory.

The NDK and CMake are downloaded by Gradle on the first build, so only OpenCV has to be fetched
by hand:

```shell
curl -LO https://github.com/opencv/opencv/releases/download/4.9.0/opencv-4.9.0-android-sdk.zip
```

Version 4.9.0 and NDK r28c (`28.2.13676358`) are not arbitrary: they are what built the binary
in this directory, read out of its own `.note.android.ident` and the OpenCV build banner it
carries. Other combinations may well work; that one is known to.
