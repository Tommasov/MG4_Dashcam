#include <jni.h>

#include <android/log.h>

#include <exception>
#include <string>

#include "camera_stream_manager.h"

namespace {

constexpr const char *TAG = "CameraProbeRecord";

/**
 * Runs a recording call and turns anything it throws into a plain false.
 *
 * OpenCV is linked statically into this library and reports every failure by throwing; so does
 * every allocation in here. An exception that reaches the JNI boundary is not caught by anything
 * above it - the C++ runtime calls std::terminate and aborts the process, which on the head unit
 * meant the whole app disappearing mid-drive instead of one clip failing to start.
 *
 * A failed start is a false. The Java side already knows what to do with that.
 */
template <typename Call>
bool guarded(const char *what, Call call) {
    try {
        return call();
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s failed: %s", what, e.what());
        return false;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s failed with an unknown exception", what);
        return false;
    }
}

}  // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startMp4Record(JNIEnv* env, jclass /*clazz*/,
                                                    jint slot,
                                                    jint videoIndex,
                                                    jstring outputPath,
                                                    jint width,
                                                    jint height,
                                                    jint fps,
                                                    jint bitrate) {
    return guarded("startMp4Record", [&]() -> bool {
        if (outputPath == nullptr) {
            return false;
        }

        const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
        if (outputChars == nullptr) {
            return false;
        }

        std::string output(outputChars);
        env->ReleaseStringUTFChars(outputPath, outputChars);

        return camera_stream_manager::startRecording(
                env,
                static_cast<int>(slot),
                static_cast<int>(videoIndex),
                output,
                static_cast<int>(width),
                static_cast<int>(height),
                static_cast<int>(fps),
                static_cast<int>(bitrate)
        );
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_stopMp4Record(JNIEnv* /*env*/, jclass /*clazz*/, jint slot) {
    return guarded("stopMp4Record", [&]() -> bool {
        return camera_stream_manager::stopRecording(static_cast<int>(slot));
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_startCombinedMp4Record(JNIEnv* env, jclass /*clazz*/,
                                                            jstring outputPath,
                                                            jint cellWidth,
                                                            jint cellHeight,
                                                            jint fps,
                                                            jint bitrate,
                                                            jstring signature,
                                                            jboolean showSpeed,
                                                            jint cameraMask) {
    return guarded("startCombinedMp4Record", [&]() -> bool {
        if (outputPath == nullptr) {
            return false;
        }

        const char* outputChars = env->GetStringUTFChars(outputPath, nullptr);
        if (outputChars == nullptr) {
            return false;
        }

        std::string output(outputChars);
        env->ReleaseStringUTFChars(outputPath, outputChars);

        std::string signatureText;
        if (signature != nullptr) {
            const char* signatureChars = env->GetStringUTFChars(signature, nullptr);
            if (signatureChars != nullptr) {
                signatureText.assign(signatureChars);
                env->ReleaseStringUTFChars(signature, signatureChars);
            }
        }

        return camera_stream_manager::startCombinedRecording(
                env,
                output,
                static_cast<int>(cellWidth),
                static_cast<int>(cellHeight),
                static_cast<int>(fps),
                static_cast<int>(bitrate),
                signatureText,
                showSpeed == JNI_TRUE,
                static_cast<int>(cameraMask)
        );
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_drivehub_kamera_CameraProbe_stopCombinedMp4Record(JNIEnv* /*env*/, jclass /*clazz*/) {
    return guarded("stopCombinedMp4Record", [&]() -> bool {
        return camera_stream_manager::stopCombinedRecording();
    }) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_drivehub_kamera_CameraProbe_updateCombinedRecordingSpeed(JNIEnv* /*env*/, jclass /*clazz*/,
                                                                  jint speedKmh) {
    guarded("updateCombinedRecordingSpeed", [&]() -> bool {
        camera_stream_manager::updateCombinedRecordingSpeed(static_cast<int>(speedKmh));
        return true;
    });
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_drivehub_kamera_CameraProbe_describeCameraFormats(JNIEnv* env, jclass /*clazz*/) {
    std::string text;
    try {
        text = camera_stream_manager::describeFormats();
    } catch (...) {
        text = "unavailable";
    }
    return env->NewStringUTF(text.c_str());
}
