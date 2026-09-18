#pragma once

#include <jni.h>

#include <string>

namespace camera_stream_manager {

bool attachPreview(JNIEnv* env, int videoIndex, jobject surface);
void detachPreview(int videoIndex);
void detachAllPreviews();

bool startRecording(JNIEnv* env, int slot, int videoIndex, const std::string& outputPath,
                    int width, int height, int fps, int bitrate);
bool stopRecording(int slot);
bool startCombinedRecording(JNIEnv* env, const std::string& outputPath,
                            int cellWidth, int cellHeight, int fps, int bitrate,
                            const std::string& signature, bool showSpeed, int cameraMask);
bool stopCombinedRecording();
void updateCombinedRecordingSpeed(int speedKmh);

/**
 * What each camera device reported the last time it was opened: pixel format, size, stride and
 * field order.
 *
 * <p>There is no adb on this head unit, so anything the native side learns and only writes to
 * logcat is learned in private. This hands it back for the diagnostics report.
 */
std::string describeFormats();

} // namespace camera_stream_manager
