cmake_minimum_required(VERSION 3.22.1)
project(cameraprobe LANGUAGES C CXX)

set(OpenCV_STATIC ON)

# Upstream hardcodes its author's own home directory here. The path comes from Gradle instead,
# which reads opencv.dir out of the git-ignored local.properties - the same place the Android SDK
# path lives, and for the same reason: it is true of one machine, not of the project.
if(NOT OPENCV_ANDROID_SDK)
    message(FATAL_ERROR
            "OPENCV_ANDROID_SDK is empty. Add opencv.dir=<path to OpenCV-android-sdk> to "
            "local.properties, or remove it to build against the prebuilt library in "
            "src/main/jniLibs instead.")
endif()
set(OpenCV_DIR "${OPENCV_ANDROID_SDK}/sdk/native/jni")
find_package(OpenCV REQUIRED core imgproc)

add_library(cameraprobe SHARED
        cameraprobe.cpp
        cameraprobe_record.cpp
        camera_stream_manager.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(media-lib mediandk)

target_link_libraries(cameraprobe
        ${log-lib}
        ${android-lib}
        ${media-lib}
        ${OpenCV_LIBS}
)
