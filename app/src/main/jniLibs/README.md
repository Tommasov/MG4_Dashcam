# Prebuilt native library

`arm64-v8a/libcameraprobe.so` is taken verbatim from upstream release **v0.8.7** of
<https://github.com/jamakr4/MG4-360-Camera-App> (asset
`MG4-360-Camera-App-v0.8.7-release.apk`, sha256
`6357701de57c2df8254868a75fee09a4a53b014cc53e791ab478f0d15fc89359`).

It is used as-is because the C++ sources in `app/src/main/cpp/` are byte-identical between
upstream tag `v0.8.7` and this fork, so rebuilding would produce the same library while
requiring the Android NDK, CMake and the OpenCV Android SDK.

The corresponding source is kept in `app/src/main/cpp/` as required by the GPL-3.0 licence.
To rebuild it instead of using this binary:

1. Install the NDK and CMake 3.22.1+ through the Android SDK manager.
2. Download the OpenCV Android SDK and point `OpenCV_DIR` in
   `app/src/main/cpp/CMakeLists.txt` at its `sdk/native/jni` directory (upstream hardcodes
   the original author's own path).
3. Restore the `externalNativeBuild` blocks in `app/build.gradle` and delete this directory.
