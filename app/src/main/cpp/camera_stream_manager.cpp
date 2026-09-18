#include "camera_stream_manager.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <linux/videodev2.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <exception>
#include <cstdarg>
#include <cstdint>
#include <ctime>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <unistd.h>

namespace camera_stream_manager
{
    namespace
    {

        static constexpr const char *TAG = "CameraStreamManager";
        static constexpr int PREVIEW_SELECT_TIMEOUT_US = 100000;
        static constexpr int STOP_WAIT_MS = 2500;
        static constexpr int COLOR_FORMAT_YUV420_PLANAR = 19;
        static constexpr int COLOR_FORMAT_YUV420_SEMIPLANAR = 21;

        // Camera /dev/videoX indices – keep in sync with CameraIndex.java.
        static constexpr int CAMERA_VIDEO_INDEX_RIGHT = 14;
        static constexpr int CAMERA_VIDEO_INDEX_FRONT = 15;
        static constexpr int CAMERA_VIDEO_INDEX_LEFT  = 16;
        static constexpr int CAMERA_VIDEO_INDEX_REAR  = 17;

        // The rear camera feed is flipped horizontally to match driver expectations.
        static constexpr int CAMERA_INDEX_REAR = 17;

        void logPrint(int level, const char *fmt, va_list args)
        {
            __android_log_vprint(level, TAG, fmt, args);
        }

        void logi(const char *fmt, ...)
        {
            va_list args;
            va_start(args, fmt);
            logPrint(ANDROID_LOG_INFO, fmt, args);
            va_end(args);
        }

        void logw(const char *fmt, ...)
        {
            va_list args;
            va_start(args, fmt);
            logPrint(ANDROID_LOG_WARN, fmt, args);
            va_end(args);
        }

        void loge(const char *fmt, ...)
        {
            va_list args;
            va_start(args, fmt);
            logPrint(ANDROID_LOG_ERROR, fmt, args);
            va_end(args);
        }

        /**
         * Names the V4L2 field order, which is the one thing that says how the buffer is laid out.
         *
         * <p>The capture path takes the top half of every frame and has always treated the rest as
         * a second copy: full field of view at half the vertical resolution. If that is right the
         * driver is reporting SEQ_TB or SEQ_BT - two fields stacked - and the other half is the
         * missing scan lines rather than a duplicate, which would mean the full 720x480 is there
         * for the weaving. The factory app saves progressive 720x480 stills from these same
         * cameras, so something can get at it.
         *
         * <p>Nobody was reading this field, and nothing logged the format at all. Whatever the
         * answer is, it should not take another guess to find out.
         */
        const char *fieldName(__u32 field)
        {
            switch (field)
            {
            case V4L2_FIELD_ANY: return "ANY";
            case V4L2_FIELD_NONE: return "NONE (progressive)";
            case V4L2_FIELD_TOP: return "TOP";
            case V4L2_FIELD_BOTTOM: return "BOTTOM";
            case V4L2_FIELD_INTERLACED: return "INTERLACED (woven)";
            case V4L2_FIELD_SEQ_TB: return "SEQ_TB (two fields stacked, top first)";
            case V4L2_FIELD_SEQ_BT: return "SEQ_BT (two fields stacked, bottom first)";
            case V4L2_FIELD_ALTERNATE: return "ALTERNATE (one field per buffer)";
            case V4L2_FIELD_INTERLACED_TB: return "INTERLACED_TB";
            case V4L2_FIELD_INTERLACED_BT: return "INTERLACED_BT";
            default: return "unknown";
            }
        }

        /** One line per device, replaced when a device is reopened. See describeFormats(). */
        std::mutex gFormatMutex;
        std::map<int, std::string> gFormats;

        /**
         * How many frames each camera has actually delivered, and how many the grid composed.
         *
         * <p>"The preview is not smooth" is a feeling; this turns it into a number. If a camera
         * reports well under the configured rate, the capture thread is losing time somewhere -
         * to the deinterlacing, to the preview copy, or to the encoder - and which of those it is
         * can then be tested instead of guessed.
         */
        std::mutex gRateMutex;
        std::map<int, long long> gFrameCounts;
        std::map<int, int64_t> gFrameFirstUs;
        long long gComposedFrames = 0;
        int64_t gComposedFirstUs = 0;

        void countFrame(int videoIndex, int64_t nowUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            if (gFrameCounts[videoIndex] == 0)
            {
                gFrameFirstUs[videoIndex] = nowUs;
            }
            gFrameCounts[videoIndex]++;
        }

        void countComposedFrame(int64_t nowUs)
        {
            std::lock_guard<std::mutex> lock(gRateMutex);
            if (gComposedFrames == 0)
            {
                gComposedFirstUs = nowUs;
            }
            gComposedFrames++;
        }

        void rememberFormat(int videoIndex, const std::string &line)
        {
            std::lock_guard<std::mutex> lock(gFormatMutex);
            gFormats[videoIndex] = line;
        }

        std::string errnoStr()
        {
            const int err = errno;
            const char *message = std::strerror(err);
            std::ostringstream oss;
            oss << err << " (" << (message ? message : "?") << ")";
            return oss.str();
        }

        std::string fourccToString(__u32 value)
        {
            char text[5];
            text[0] = static_cast<char>(value & 0xFF);
            text[1] = static_cast<char>((value >> 8) & 0xFF);
            text[2] = static_cast<char>((value >> 16) & 0xFF);
            text[3] = static_cast<char>((value >> 24) & 0xFF);
            text[4] = '\0';
            return std::string(text);
        }

        int64_t nowUs()
        {
            using namespace std::chrono;
            return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
        }

        enum class PackedFormat
        {
            UNKNOWN,
            UYVY,
            YUYV
        };

        PackedFormat packedFormatFromFourcc(__u32 fourcc)
        {
            switch (fourcc)
            {
            case V4L2_PIX_FMT_UYVY:
                return PackedFormat::UYVY;
            case V4L2_PIX_FMT_YUYV:
                return PackedFormat::YUYV;
            default:
                return PackedFormat::UNKNOWN;
            }
        }

        int rgbaConversionCode(PackedFormat format)
        {
            switch (format)
            {
            case PackedFormat::UYVY:
                return cv::COLOR_YUV2RGBA_UYVY;
            case PackedFormat::YUYV:
                return cv::COLOR_YUV2RGBA_YUYV;
            default:
                return -1;
            }
        }

        struct MappedBuffer
        {
            void *start = nullptr;
            size_t length = 0;
        };

        void packI420ToNv12(const uint8_t *src, uint8_t *dst, int width, int height)
        {
            const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
            const size_t uvPlaneSize = ySize / 4U;
            const uint8_t *srcY = src;
            const uint8_t *srcU = src + ySize;
            const uint8_t *srcV = srcU + uvPlaneSize;
            std::memcpy(dst, srcY, ySize);
            uint8_t *dstUv = dst + ySize;
            for (size_t i = 0; i < uvPlaneSize; i++)
            {
                dstUv[i * 2U] = srcU[i];
                dstUv[i * 2U + 1U] = srcV[i];
            }
        }

        class RecordingSink
        {
        public:
            RecordingSink(int slot, int videoIndex, std::string outputPath, int requestedWidth,
                          int requestedHeight, int fps, int bitrate, bool flipHorizontal)
                : slot_(slot),
                  videoIndex_(videoIndex),
                  outputPath_(std::move(outputPath)),
                  requestedWidth_(requestedWidth),
                  requestedHeight_(requestedHeight),
                  fps_(fps),
                  bitrate_(bitrate),
                  flipHorizontal_(flipHorizontal)
            {
            }

            ~RecordingSink()
            {
                finalize();
            }

            bool initialize(int srcWidth, int srcHeight)
            {
                recWidth_ = std::min(requestedWidth_, srcWidth);
                // The assignment below used to be inside the comment above it, so recHeight_ kept
                // its initial 0, the validity check just below rejected it, and this recorder
                // could never start - it is still that way upstream. Worth reporting there.
                //
                // The comment was also wrong about what the other half of the buffer holds: not a
                // second camera, but the second field of an interlaced frame. The capture path
                // now weaves them, so a frame arriving here is the full height and nothing needs
                // halving. See docs/camera-format.md.
                recHeight_ = std::min(requestedHeight_, srcHeight);
                if (recWidth_ <= 0 || recHeight_ <= 0 || (recWidth_ % 2) != 0 || (recHeight_ % 2) != 0)
                {
                    loge("slot=%d invalid recording size %dx%d for /dev/video%d", slot_, recWidth_, recHeight_, videoIndex_);
                    return false;
                }

                if (!initializeCodec())
                {
                    return false;
                }

                outFd_ = open(outputPath_.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
                if (outFd_ < 0)
                {
                    loge("slot=%d output open failed: %s", slot_, errnoStr().c_str());
                    finalize();
                    return false;
                }

                muxer_ = AMediaMuxer_new(outFd_, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
                if (!muxer_)
                {
                    loge("slot=%d AMediaMuxer_new failed", slot_);
                    finalize();
                    return false;
                }

                i420Frame_.create(recHeight_ + (recHeight_ / 2), recWidth_, CV_8UC1);
                encoderFrame_.resize(static_cast<size_t>(recWidth_) * static_cast<size_t>(recHeight_) * 3U / 2U);
                frameDurationUs_ = 1000000LL / std::max(1, fps_);
                startUs_ = nowUs();
                nextPtsUs_ = 0;
                frameCount_ = 0;
                logi("slot=%d /dev/video%d encoder color format=%d", slot_, videoIndex_, encoderColorFormat_);
                return true;
            }

            void requestStop()
            {
                stopRequested_.store(true);
            }

            bool isStopRequested() const
            {
                return stopRequested_.load();
            }

            bool isFinalized() const
            {
                return finalized_.load();
            }

            bool waitUntilStopped(int timeoutMs)
            {
                std::unique_lock<std::mutex> lock(waitMutex_);
                return waitCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this]()
                                        { return stopped_; });
            }

            void processFrame(const cv::Mat &rgbaFrame)
            {
                if (isFinalized() || stopRequested_.load())
                {
                    return;
                }

                if (rgbaFrame.empty() || rgbaFrame.cols < recWidth_ || rgbaFrame.rows < recHeight_)
                {
                    return;
                }

                const int64_t elapsedUs = nowUs() - startUs_;
                if (elapsedUs < nextPtsUs_)
                {
                    drainEncoder(0);
                    return;
                }

                cv::Mat rgbaCrop = rgbaFrame(cv::Rect(0, 0, recWidth_, recHeight_));
                const cv::Mat *encodeSource = &rgbaCrop;
                if (flipHorizontal_)
                {
                    flippedRgba_.create(recHeight_, recWidth_, CV_8UC4);
                    cv::flip(rgbaCrop, flippedRgba_, 1);
                    encodeSource = &flippedRgba_;
                }
                cv::cvtColor(*encodeSource, i420Frame_, cv::COLOR_RGBA2YUV_I420);
                if (encoderColorFormat_ == COLOR_FORMAT_YUV420_SEMIPLANAR)
                {
                    packI420ToNv12(i420Frame_.data, encoderFrame_.data(), recWidth_, recHeight_);
                }
                else
                {
                    std::memcpy(encoderFrame_.data(), i420Frame_.data, encoderFrame_.size());
                }

                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    size_t inputSize = 0;
                    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &inputSize);
                    const size_t frameSize = encoderFrame_.size();
                    if (inputBuffer != nullptr && inputSize >= frameSize)
                    {
                        std::memcpy(inputBuffer, encoderFrame_.data(), frameSize);
                        const int64_t pts = frameCount_ * frameDurationUs_;
                        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, frameSize,
                                                         static_cast<uint64_t>(pts), 0) == AMEDIA_OK)
                        {
                            frameCount_++;
                            nextPtsUs_ += frameDurationUs_;
                        }
                        else
                        {
                            logw("slot=%d queueInputBuffer failed", slot_);
                            requestStop();
                        }
                    }
                    else
                    {
                        logw("slot=%d encoder input buffer too small", slot_);
                        requestStop();
                    }
                }

                drainEncoder(0);
            }

            void finalize()
            {
                if (finalized_.exchange(true))
                {
                    return;
                }

                if (codec_ != nullptr)
                {
                    queueEndOfStream();
                    drainEncoder(10000);
                }

                if (muxer_ != nullptr)
                {
                    if (muxerStarted_)
                    {
                        AMediaMuxer_stop(muxer_);
                    }
                    AMediaMuxer_delete(muxer_);
                    muxer_ = nullptr;
                }

                if (outFd_ >= 0)
                {
                    close(outFd_);
                    outFd_ = -1;
                }

                if (codec_ != nullptr)
                {
                    AMediaCodec_stop(codec_);
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }

                {
                    std::lock_guard<std::mutex> lock(waitMutex_);
                    stopped_ = true;
                }
                waitCv_.notify_all();
            }

        private:
            bool initializeCodec()
            {
                const int preferredFormats[] = {
                    COLOR_FORMAT_YUV420_SEMIPLANAR,
                    COLOR_FORMAT_YUV420_PLANAR};
                for (int colorFormat : preferredFormats)
                {
                    if (tryInitializeCodec(colorFormat))
                    {
                        encoderColorFormat_ = colorFormat;
                        return true;
                    }
                    releaseCodecOnly();
                }
                loge("slot=%d no usable encoder color format found", slot_);
                return false;
            }

            bool tryInitializeCodec(int colorFormat)
            {
                codec_ = AMediaCodec_createEncoderByType("video/avc");
                if (!codec_)
                {
                    loge("slot=%d AMediaCodec_createEncoderByType failed", slot_);
                    return false;
                }

                AMediaFormat *format = AMediaFormat_new();
                AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, recWidth_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, recHeight_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrate_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, fps_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);

                media_status_t status = AMediaCodec_configure(
                    codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
                AMediaFormat_delete(format);
                if (status != AMEDIA_OK)
                {
                    logw("slot=%d AMediaCodec_configure failed for color format %d: %d",
                         slot_, colorFormat, static_cast<int>(status));
                    return false;
                }

                status = AMediaCodec_start(codec_);
                if (status != AMEDIA_OK)
                {
                    logw("slot=%d AMediaCodec_start failed for color format %d: %d",
                         slot_, colorFormat, static_cast<int>(status));
                    return false;
                }
                return true;
            }

            void releaseCodecOnly()
            {
                if (codec_ != nullptr)
                {
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }
                encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;
            }

            void queueEndOfStream()
            {
                if (eosQueued_ || codec_ == nullptr)
                {
                    return;
                }
                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, 0,
                                                 static_cast<uint64_t>(frameCount_ * frameDurationUs_),
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eosQueued_ = true;
                }
            }

            void drainEncoder(int timeoutUs)
            {
                if (codec_ == nullptr)
                {
                    return;
                }

                int idleLoops = 0;
                while (idleLoops < 8)
                {
                    AMediaCodecBufferInfo info{};
                    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
                    if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
                    {
                        idleLoops++;
                        if (!eosQueued_)
                        {
                            break;
                        }
                        continue;
                    }
                    if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
                    {
                        if (!muxerStarted_ && muxer_ != nullptr)
                        {
                            AMediaFormat *outputFormat = AMediaCodec_getOutputFormat(codec_);
                            trackIndex_ = AMediaMuxer_addTrack(muxer_, outputFormat);
                            AMediaFormat_delete(outputFormat);
                            if (trackIndex_ >= 0)
                            {
                                AMediaMuxer_start(muxer_);
                                muxerStarted_ = true;
                            }
                        }
                        continue;
                    }
                    if (outputIndex < 0)
                    {
                        break;
                    }

                    if (info.size > 0 && muxerStarted_ && muxer_ != nullptr)
                    {
                        size_t outputSize = 0;
                        uint8_t *outputBuffer =
                            AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(outputIndex), &outputSize);
                        if (outputBuffer != nullptr)
                        {
                            AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(trackIndex_), outputBuffer, &info);
                        }
                    }

                    const bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                    if (isEos)
                    {
                        break;
                    }
                }
            }

            const int slot_;
            const int videoIndex_;
            const std::string outputPath_;
            const int requestedWidth_;
            const int requestedHeight_;
            const int fps_;
            const int bitrate_;
            const bool flipHorizontal_;

            int recWidth_ = 0;
            int recHeight_ = 0;
            int64_t frameDurationUs_ = 0;
            int64_t startUs_ = 0;
            int64_t nextPtsUs_ = 0;
            int64_t frameCount_ = 0;
            int encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;

            AMediaCodec *codec_ = nullptr;
            AMediaMuxer *muxer_ = nullptr;
            int outFd_ = -1;
            ssize_t trackIndex_ = -1;
            bool muxerStarted_ = false;
            bool eosQueued_ = false;

            cv::Mat i420Frame_;
            cv::Mat flippedRgba_;
            std::vector<uint8_t> encoderFrame_;

            std::atomic<bool> stopRequested_{false};
            std::atomic<bool> finalized_{false};
            std::mutex waitMutex_;
            std::condition_variable waitCv_;
            bool stopped_ = false;
        };

        class FrameConsumer
        {
        public:
            virtual ~FrameConsumer() = default;
            virtual void processFrame(const cv::Mat &rgbaFrame) = 0;
            virtual void requestStop() = 0;
            virtual bool isStopRequested() const = 0;
            virtual bool isFinalized() const = 0;
            virtual bool waitUntilStopped(int timeoutMs) = 0;
            virtual void finalize() = 0;
        };

        class SingleCameraRecordingConsumer final : public FrameConsumer
        {
        public:
            explicit SingleCameraRecordingConsumer(std::shared_ptr<RecordingSink> sink) : sink_(std::move(sink))
            {
            }

            void processFrame(const cv::Mat &rgbaFrame) override
            {
                if (sink_ != nullptr)
                {
                    sink_->processFrame(rgbaFrame);
                }
            }

            void requestStop() override
            {
                if (sink_ != nullptr)
                {
                    sink_->requestStop();
                }
            }

            bool isStopRequested() const override
            {
                return sink_ == nullptr || sink_->isStopRequested();
            }

            bool isFinalized() const override
            {
                return sink_ == nullptr || sink_->isFinalized();
            }

            bool waitUntilStopped(int timeoutMs) override
            {
                return sink_ == nullptr || sink_->waitUntilStopped(timeoutMs);
            }

            void finalize() override
            {
                if (sink_ != nullptr)
                {
                    sink_->finalize();
                }
            }

        private:
            std::shared_ptr<RecordingSink> sink_;
        };

        /** The strip under the grid carrying the date, the time and the speed. */
        static constexpr int COMBINED_FOOTER_HEIGHT = 80;

        /**
         * The size of the composed canvas, in one place.
         *
         * <p>The sink builds its buffers from this and the preview sizes its window from it, so a
         * change to the layout cannot leave one of them believing the old shape.
         */
        int combinedCanvasWidth(int cellWidth, int /*cellHeight*/)
        {
            return cellWidth * 2;
        }

        int combinedCanvasHeight(int /*cellWidth*/, int cellHeight)
        {
            return (cellHeight * 2) + COMBINED_FOOTER_HEIGHT;
        }

        class CombinedRecordingSink : public std::enable_shared_from_this<CombinedRecordingSink>
        {
        public:
            CombinedRecordingSink(std::string outputPath, int cellWidth, int cellHeight, int fps, int bitrate,
                                  std::string signature, bool showSpeed)
                : outputPath_(std::move(outputPath)),
                  cellWidth_(cellWidth),
                  cellHeight_(cellHeight),
                  gridWidth_(combinedCanvasWidth(cellWidth, cellHeight)),
                  gridHeight_(cellHeight * 2),
                  footerHeight_(COMBINED_FOOTER_HEIGHT),
                  totalHeight_(combinedCanvasHeight(cellWidth, cellHeight)),
                  fps_(fps),
                  bitrate_(bitrate),
                  signature_(std::move(signature)),
                  showSpeed_(showSpeed)
            {
            }

            ~CombinedRecordingSink()
            {
                finalize();
                if (previewWindow_ != nullptr)
                {
                    ANativeWindow_release(previewWindow_);
                    previewWindow_ = nullptr;
                }
            }

            /**
             * Whether this sink writes a file, as opposed to only composing for the preview.
             *
             * <p>An empty output path means somebody wants to look at the grid without recording
             * it. Everything up to the encoder is the same work, which is the point: what the
             * preview shows is what the file would contain, composed by the same code rather
             * than by an approximation of it.
             */
            bool isRecording() const
            {
                return !outputPath_.empty();
            }

            /**
             * Shows the composed canvas on a Surface, live.
             *
             * <p>Takes the encoder lock because the canvas is written under it, and a preview
             * window that changed mid-compose would be read after being released.
             */
            void setPreviewWindow(ANativeWindow *window)
            {
                std::lock_guard<std::mutex> lock(encoderMutex_);
                if (previewWindow_ != nullptr)
                {
                    ANativeWindow_release(previewWindow_);
                }
                previewWindow_ = window;
                if (previewWindow_ != nullptr)
                {
                    ANativeWindow_setBuffersGeometry(previewWindow_, gridWidth_, totalHeight_,
                                                     AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
                    // Same pre-warm as the single-camera preview: the first locks would
                    // otherwise block while the buffer pool is allocated, and they would block
                    // on the capture thread.
                    for (int i = 0; i < 3; i++)
                    {
                        ANativeWindow_Buffer warm{};
                        if (ANativeWindow_lock(previewWindow_, &warm, nullptr) == 0)
                        {
                            if (warm.bits != nullptr && warm.height > 0 && warm.stride > 0)
                            {
                                std::memset(warm.bits, 0,
                                            static_cast<size_t>(warm.stride) *
                                                static_cast<size_t>(warm.height) * 4U);
                            }
                            ANativeWindow_unlockAndPost(previewWindow_);
                        }
                    }
                }
            }

            /** Hands the window over without releasing it: the next composer will own it. */
            ANativeWindow *takePreviewWindow()
            {
                std::lock_guard<std::mutex> lock(encoderMutex_);
                ANativeWindow *window = previewWindow_;
                previewWindow_ = nullptr;
                return window;
            }

            bool hasPreviewWindow()
            {
                std::lock_guard<std::mutex> lock(encoderMutex_);
                return previewWindow_ != nullptr;
            }

            /**
             * How often the preview is refreshed, at most.
             *
             * <p>Posting it costs a copy of the whole canvas - six megabytes now that the grid is
             * 1440x1040 - and the copy happens on a capture thread, which is not rewarding the
             * V4L2 buffer to the driver while it runs. At the recording rate that is 150 MB/s of
             * memcpy stealing time from the cameras, which is why the preview was never smooth
             * even before the deinterlacing was added.
             *
             * <p>Twelve a second is plenty for judging a layout and for seeing that the cameras
             * are alive, and it halves what the capture threads give up for it.
             */
            static constexpr int64_t PREVIEW_INTERVAL_US = 1000000 / 12;

            void postCanvasToPreviewLocked()
            {
                if (previewWindow_ == nullptr || rgbaCanvas_.empty())
                {
                    return;
                }
                const int64_t now = nowUs();
                if (now - lastPreviewUs_ < PREVIEW_INTERVAL_US)
                {
                    return;
                }
                lastPreviewUs_ = now;
                ANativeWindow_Buffer out{};
                if (ANativeWindow_lock(previewWindow_, &out, nullptr) != 0)
                {
                    return;
                }
                const int rows = std::min(rgbaCanvas_.rows, out.height);
                const int cols = std::min(rgbaCanvas_.cols, out.width);
                const int srcStride = static_cast<int>(rgbaCanvas_.step[0]);
                const int dstStride = out.stride * 4;
                uint8_t *dst = static_cast<uint8_t *>(out.bits);
                for (int row = 0; row < rows; row++)
                {
                    std::memcpy(dst + row * dstStride, rgbaCanvas_.data + row * srcStride,
                                static_cast<size_t>(cols) * 4U);
                }
                ANativeWindow_unlockAndPost(previewWindow_);
            }

            bool initialize()
            {
                if (cellWidth_ <= 0 || cellHeight_ <= 0 || (cellWidth_ % 2) != 0 || (cellHeight_ % 2) != 0)
                {
                    loge("combined invalid cell size %dx%d", cellWidth_, cellHeight_);
                    return false;
                }

                if (!isRecording())
                {
                    // Preview only: no codec, no muxer, no file. Just the canvas to draw on.
                    rgbaCanvas_.create(totalHeight_, gridWidth_, CV_8UC4);
                    rgbaCanvas_.setTo(cv::Scalar(0, 0, 0, 255));
                    for (cv::Mat &frame : latestFrames_)
                    {
                        frame.create(cellHeight_, cellWidth_, CV_8UC4);
                        frame.setTo(cv::Scalar(0, 0, 0, 255));
                    }
                    startUs_ = nowUs();
                    nextPtsUs_ = 0;
                    logi("combined preview %dx%d", gridWidth_, totalHeight_);
                    return true;
                }

                if (!initializeCodec())
                {
                    return false;
                }

                outFd_ = open(outputPath_.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
                if (outFd_ < 0)
                {
                    loge("combined output open failed: %s", errnoStr().c_str());
                    finalize();
                    return false;
                }

                muxer_ = AMediaMuxer_new(outFd_, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
                if (!muxer_)
                {
                    loge("combined AMediaMuxer_new failed");
                    finalize();
                    return false;
                }

                rgbaCanvas_.create(totalHeight_, gridWidth_, CV_8UC4);
                i420Frame_.create(totalHeight_ + (totalHeight_ / 2), gridWidth_, CV_8UC1);
                encoderFrame_.resize(static_cast<size_t>(gridWidth_) * static_cast<size_t>(totalHeight_) * 3U / 2U);
                for (cv::Mat &frame : latestFrames_)
                {
                    frame.create(cellHeight_, cellWidth_, CV_8UC4);
                    frame.setTo(cv::Scalar(0, 0, 0, 255));
                }
                frameDurationUs_ = 1000000LL / std::max(1, fps_);
                startUs_ = nowUs();
                nextPtsUs_ = 0;
                frameCount_ = 0;
                logi("combined encoder color format=%d size=%dx%d", encoderColorFormat_, gridWidth_, totalHeight_);
                return true;
            }

            void requestStop()
            {
                stopRequested_.store(true);
            }

            bool isStopRequested() const
            {
                return stopRequested_.load();
            }

            bool isFinalized() const
            {
                return finalized_.load();
            }

            bool waitUntilStopped(int timeoutMs)
            {
                std::unique_lock<std::mutex> lock(waitMutex_);
                return waitCv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [this]()
                                        { return stopped_; });
            }

            void updateSpeedKmh(int speedKmh)
            {
                currentSpeedKmh_.store(std::max(0, speedKmh));
            }

            void processSourceFrame(int sourceIndex, const cv::Mat &rgbaFrame)
            {
                if (sourceIndex < 0 || sourceIndex >= 4 || rgbaFrame.empty() || isFinalized() || stopRequested_.load())
                {
                    return;
                }
                if (rgbaFrame.cols < cellWidth_ || rgbaFrame.rows < cellHeight_)
                {
                    return;
                }

                std::lock_guard<std::mutex> lock(encoderMutex_);
                if (isFinalized() || stopRequested_.load())
                {
                    return;
                }

                // No mirroring here, deliberately. Upstream flips the rear view horizontally to
                // match what a driver expects from a mirror, which is right when reversing and
                // wrong in an archive: it reverses every number plate behind you.
                cv::Mat rgbaCrop = rgbaFrame(cv::Rect(0, 0, cellWidth_, cellHeight_));
                rgbaCrop.copyTo(latestFrames_[sourceIndex]);

                const int64_t elapsedUs = nowUs() - startUs_;
                if (elapsedUs < nextPtsUs_)
                {
                    drainEncoderLocked(0);
                    return;
                }

                composeCanvasLocked();
                countComposedFrame(nowUs());
                postCanvasToPreviewLocked();
                if (!isRecording())
                {
                    // Nothing downstream of the canvas. The pts gate above still paces us, so a
                    // preview costs the same composition as a recording and nothing more.
                    nextPtsUs_ += frameDurationUs_;
                    return;
                }
                cv::cvtColor(rgbaCanvas_, i420Frame_, cv::COLOR_RGBA2YUV_I420);
                if (encoderColorFormat_ == COLOR_FORMAT_YUV420_SEMIPLANAR)
                {
                    packI420ToNv12(i420Frame_.data, encoderFrame_.data(), gridWidth_, totalHeight_);
                }
                else
                {
                    std::memcpy(encoderFrame_.data(), i420Frame_.data, encoderFrame_.size());
                }

                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    size_t inputSize = 0;
                    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &inputSize);
                    const size_t frameSize = encoderFrame_.size();
                    if (inputBuffer != nullptr && inputSize >= frameSize)
                    {
                        std::memcpy(inputBuffer, encoderFrame_.data(), frameSize);
                        const int64_t pts = frameCount_ * frameDurationUs_;
                        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, frameSize,
                                                         static_cast<uint64_t>(pts), 0) == AMEDIA_OK)
                        {
                            frameCount_++;
                            nextPtsUs_ += frameDurationUs_;
                        }
                        else
                        {
                            logw("combined queueInputBuffer failed");
                            requestStop();
                        }
                    }
                    else
                    {
                        logw("combined encoder input buffer too small");
                        requestStop();
                    }
                }

                drainEncoderLocked(0);
            }

            void finalize()
            {
                if (finalized_.exchange(true))
                {
                    return;
                }

                std::lock_guard<std::mutex> lock(encoderMutex_);
                if (codec_ != nullptr)
                {
                    queueEndOfStreamLocked();
                    drainEncoderLocked(10000);
                }

                if (muxer_ != nullptr)
                {
                    if (muxerStarted_)
                    {
                        AMediaMuxer_stop(muxer_);
                    }
                    AMediaMuxer_delete(muxer_);
                    muxer_ = nullptr;
                }

                if (outFd_ >= 0)
                {
                    close(outFd_);
                    outFd_ = -1;
                }

                if (codec_ != nullptr)
                {
                    AMediaCodec_stop(codec_);
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }

                {
                    std::lock_guard<std::mutex> waitLock(waitMutex_);
                    stopped_ = true;
                }
                waitCv_.notify_all();
            }

        private:
            bool initializeCodec()
            {
                const int preferredFormats[] = {
                    COLOR_FORMAT_YUV420_SEMIPLANAR,
                    COLOR_FORMAT_YUV420_PLANAR};
                for (int colorFormat : preferredFormats)
                {
                    if (tryInitializeCodec(colorFormat))
                    {
                        encoderColorFormat_ = colorFormat;
                        return true;
                    }
                    releaseCodecOnly();
                }
                loge("combined no usable encoder color format found");
                return false;
            }

            bool tryInitializeCodec(int colorFormat)
            {
                codec_ = AMediaCodec_createEncoderByType("video/avc");
                if (!codec_)
                {
                    loge("combined AMediaCodec_createEncoderByType failed");
                    return false;
                }

                AMediaFormat *format = AMediaFormat_new();
                AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, gridWidth_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, totalHeight_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrate_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, fps_);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);

                media_status_t status = AMediaCodec_configure(
                    codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
                AMediaFormat_delete(format);
                if (status != AMEDIA_OK)
                {
                    logw("combined AMediaCodec_configure failed for color format %d: %d",
                         colorFormat, static_cast<int>(status));
                    return false;
                }

                status = AMediaCodec_start(codec_);
                if (status != AMEDIA_OK)
                {
                    logw("combined AMediaCodec_start failed for color format %d: %d",
                         colorFormat, static_cast<int>(status));
                    return false;
                }
                return true;
            }

            void releaseCodecOnly()
            {
                if (codec_ != nullptr)
                {
                    AMediaCodec_delete(codec_);
                    codec_ = nullptr;
                }
                encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;
            }

            void composeCanvasLocked()
            {
                // A 2x2 grid, every cell at the shape its camera actually has.
                //
                // The old layout stood the side cameras on end and stacked front and rear between
                // them, which left 18% of every frame black and squashed all four views - the
                // centre ones vertically, the sides horizontally once rotated. Nothing about that
                // was chosen for a recording: the cell size came from upstream's full-screen
                // single-camera view, and the grid was assembled out of whatever shape that left.
                //
                // Front and rear sit side by side because that is the pair worth reading together
                // when working out who came from where. Left and right go below, each on the side
                // it belongs to.
                rgbaCanvas_.setTo(cv::Scalar(0, 0, 0, 255));
                latestFrames_[0].copyTo(rgbaCanvas_(cv::Rect(0, 0, cellWidth_, cellHeight_)));
                latestFrames_[3].copyTo(rgbaCanvas_(cv::Rect(cellWidth_, 0, cellWidth_, cellHeight_)));
                latestFrames_[2].copyTo(rgbaCanvas_(cv::Rect(0, cellHeight_, cellWidth_, cellHeight_)));
                latestFrames_[1].copyTo(rgbaCanvas_(cv::Rect(cellWidth_, cellHeight_, cellWidth_, cellHeight_)));
                drawFooterLocked();
            }

            void drawFooterLocked()
            {
                cv::Rect footerRect(0, gridHeight_, gridWidth_, footerHeight_);
                cv::Mat footer = rgbaCanvas_(footerRect);
                footer.setTo(cv::Scalar(14, 14, 14, 255));
                cv::line(rgbaCanvas_,
                         cv::Point(0, gridHeight_),
                         cv::Point(gridWidth_, gridHeight_),
                         cv::Scalar(70, 70, 70, 255),
                         2,
                         cv::LINE_AA);

                const int baselineY = gridHeight_ + 50;
                const double fontScale = 0.78;
                const int thickness = 2;
                const int marginX = 24;
                const cv::Scalar textColor(235, 235, 235, 255);

                if (!signature_.empty())
                {
                    cv::putText(rgbaCanvas_,
                                signature_,
                                cv::Point(marginX, baselineY),
                                cv::FONT_HERSHEY_SIMPLEX,
                                fontScale,
                                textColor,
                                thickness,
                                cv::LINE_AA);
                }

                std::string rightText = buildRightFooterText();
                int baseline = 0;
                cv::Size textSize = cv::getTextSize(
                    rightText,
                    cv::FONT_HERSHEY_SIMPLEX,
                    fontScale,
                    thickness,
                    &baseline);
                cv::putText(rgbaCanvas_,
                            rightText,
                            cv::Point(std::max(marginX, gridWidth_ - marginX - textSize.width), baselineY),
                            cv::FONT_HERSHEY_SIMPLEX,
                            fontScale,
                            textColor,
                            thickness,
                            cv::LINE_AA);
            }

            std::string buildRightFooterText() const
            {
                std::time_t now = std::time(nullptr);
                std::tm localNow{};
#if defined(_WIN32)
                localtime_s(&localNow, &now);
#else
                localtime_r(&now, &localNow);
#endif
                char dateBuffer[64];
                if (std::strftime(dateBuffer, sizeof(dateBuffer), "%d.%m.%Y %H:%M:%S", &localNow) == 0)
                {
                    std::snprintf(dateBuffer, sizeof(dateBuffer), "--.--.---- --:--:--");
                }

                std::string text(dateBuffer);
                if (showSpeed_)
                {
                    text += "  |  ";
                    text += std::to_string(currentSpeedKmh_.load());
                    text += " km/h";
                }
                return text;
            }

            void queueEndOfStreamLocked()
            {
                if (eosQueued_ || codec_ == nullptr)
                {
                    return;
                }
                ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
                if (inputIndex >= 0)
                {
                    AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(inputIndex), 0, 0,
                                                 static_cast<uint64_t>(frameCount_ * frameDurationUs_),
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eosQueued_ = true;
                }
            }

            void drainEncoderLocked(int timeoutUs)
            {
                if (codec_ == nullptr)
                {
                    return;
                }

                int idleLoops = 0;
                while (idleLoops < 8)
                {
                    AMediaCodecBufferInfo info{};
                    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
                    if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
                    {
                        idleLoops++;
                        if (!eosQueued_)
                        {
                            break;
                        }
                        continue;
                    }
                    if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
                    {
                        if (!muxerStarted_ && muxer_ != nullptr)
                        {
                            AMediaFormat *outputFormat = AMediaCodec_getOutputFormat(codec_);
                            trackIndex_ = AMediaMuxer_addTrack(muxer_, outputFormat);
                            AMediaFormat_delete(outputFormat);
                            if (trackIndex_ >= 0)
                            {
                                AMediaMuxer_start(muxer_);
                                muxerStarted_ = true;
                            }
                        }
                        continue;
                    }
                    if (outputIndex < 0)
                    {
                        break;
                    }

                    if (info.size > 0 && muxerStarted_ && muxer_ != nullptr)
                    {
                        size_t outputSize = 0;
                        uint8_t *outputBuffer =
                            AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(outputIndex), &outputSize);
                        if (outputBuffer != nullptr)
                        {
                            AMediaMuxer_writeSampleData(muxer_, static_cast<size_t>(trackIndex_), outputBuffer, &info);
                        }
                    }

                    const bool isEos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                    if (isEos)
                    {
                        break;
                    }
                }
            }

            const std::string outputPath_;
            const int cellWidth_;
            const int cellHeight_;
            const int gridWidth_;
            const int gridHeight_;
            const int footerHeight_;
            const int totalHeight_;
            const int fps_;
            const int bitrate_;
            const std::string signature_;
            const bool showSpeed_;

            int64_t frameDurationUs_ = 0;
            int64_t startUs_ = 0;
            int64_t nextPtsUs_ = 0;
            int64_t frameCount_ = 0;
            int encoderColorFormat_ = COLOR_FORMAT_YUV420_PLANAR;

            AMediaCodec *codec_ = nullptr;
            AMediaMuxer *muxer_ = nullptr;
            int outFd_ = -1;
            ssize_t trackIndex_ = -1;
            bool muxerStarted_ = false;
            bool eosQueued_ = false;

            std::mutex encoderMutex_;
            cv::Mat rgbaCanvas_;
            /** Where the composed canvas is also shown, when somebody is looking. */
            ANativeWindow *previewWindow_ = nullptr;
            int64_t lastPreviewUs_ = 0;
            cv::Mat i420Frame_;
            std::array<cv::Mat, 4> latestFrames_{};
            std::vector<uint8_t> encoderFrame_;
            std::atomic<int> currentSpeedKmh_{0};

            std::atomic<bool> stopRequested_{false};
            std::atomic<bool> finalized_{false};
            std::mutex waitMutex_;
            std::condition_variable waitCv_;
            bool stopped_ = false;
        };

        class CombinedInputTap final : public FrameConsumer
        {
        public:
            CombinedInputTap(int sourceIndex, std::shared_ptr<CombinedRecordingSink> sink)
                : sourceIndex_(sourceIndex), sink_(std::move(sink))
            {
            }

            void processFrame(const cv::Mat &rgbaFrame) override
            {
                if (sink_ != nullptr)
                {
                    sink_->processSourceFrame(sourceIndex_, rgbaFrame);
                }
            }

            void requestStop() override
            {
                if (sink_ != nullptr)
                {
                    sink_->requestStop();
                }
            }

            bool isStopRequested() const override
            {
                return sink_ == nullptr || sink_->isStopRequested();
            }

            bool isFinalized() const override
            {
                return sink_ == nullptr || sink_->isFinalized();
            }

            bool waitUntilStopped(int timeoutMs) override
            {
                return sink_ == nullptr || sink_->waitUntilStopped(timeoutMs);
            }

            void finalize() override
            {
                if (sink_ != nullptr)
                {
                    sink_->finalize();
                }
            }

        private:
            const int sourceIndex_;
            std::shared_ptr<CombinedRecordingSink> sink_;
        };

        class CameraSession : public std::enable_shared_from_this<CameraSession>
        {
        public:
            explicit CameraSession(int videoIndex) : videoIndex_(videoIndex)
            {
            }

            ~CameraSession()
            {
                detachPreview();
                std::vector<int> consumerIds;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    consumerIds.reserve(consumers_.size());
                    for (const auto &entry : consumers_)
                    {
                        consumerIds.push_back(entry.first);
                    }
                }
                for (int consumerId : consumerIds)
                {
                    stopConsumer(consumerId);
                }
                requestStopAndJoin();
            }

            bool attachPreview(JNIEnv *env, jobject surface)
            {
                if (surface == nullptr)
                {
                    return false;
                }

                ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
                if (window == nullptr)
                {
                    logw("ANativeWindow_fromSurface failed for /dev/video%d", videoIndex_);
                    return false;
                }

                bool started = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (previewWindow_ != nullptr)
                    {
                        ANativeWindow_release(previewWindow_);
                    }
                    previewWindow_ = window;
                    stopRequested_.store(false);
                    started = ensureStartedLocked();
                    if (started)
                    {
                        configurePreviewWindowLocked();
                        // Pre-warm the BufferQueue: the first ANativeWindow_lock calls would
                        // otherwise block while the consumer-side buffer pool is allocated lazily,
                        // stealing budget from the capture thread (which is already saturated by
                        // cvtColor + recording encoder). Three empty posts are enough to settle
                        // the typical pool size on this device.
                        for (int i = 0; i < 3; i++)
                        {
                            ANativeWindow_Buffer warmBuffer{};
                            if (ANativeWindow_lock(previewWindow_, &warmBuffer, nullptr) == 0)
                            {
                                if (warmBuffer.bits != nullptr && warmBuffer.height > 0 && warmBuffer.stride > 0)
                                {
                                    std::memset(warmBuffer.bits, 0,
                                                static_cast<size_t>(warmBuffer.stride) *
                                                    static_cast<size_t>(warmBuffer.height) * 4U);
                                }
                                ANativeWindow_unlockAndPost(previewWindow_);
                            }
                        }
                    }
                }

                if (!started)
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (previewWindow_ == window)
                    {
                        ANativeWindow_release(previewWindow_);
                        previewWindow_ = nullptr;
                    }
                    else
                    {
                        ANativeWindow_release(window);
                    }
                }
                return started;
            }

            void detachPreview()
            {
                bool shouldStop = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (previewWindow_ != nullptr)
                    {
                        ANativeWindow_release(previewWindow_);
                        previewWindow_ = nullptr;
                    }
                    shouldStop = consumers_.empty();
                    if (shouldStop)
                    {
                        stopRequested_.store(true);
                    }
                }
                if (shouldStop)
                {
                    requestStopAndJoin();
                }
            }

            bool startRecording(int slot, const std::string &outputPath, int width, int height, int fps, int bitrate)
            {
                auto sink = std::make_shared<RecordingSink>(
                    slot, videoIndex_, outputPath, width, height, fps, bitrate, videoIndex_ == CAMERA_INDEX_REAR);
                auto consumer = std::make_shared<SingleCameraRecordingConsumer>(sink);
                bool started = false;
                bool shouldStopAfterInitFailure = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (!ensureStartedLocked())
                    {
                        return false;
                    }
                    if (consumers_.find(slot) != consumers_.end())
                    {
                        logw("slot=%d already recording on /dev/video%d", slot, videoIndex_);
                        return false;
                    }
                    if (!sink->initialize(srcWidth_, srcHeight_))
                    {
                        shouldStopAfterInitFailure = (previewWindow_ == nullptr && consumers_.empty());
                        if (shouldStopAfterInitFailure)
                        {
                            stopRequested_.store(true);
                        }
                    }
                    else
                    {
                        consumers_[slot] = consumer;
                        stopRequested_.store(false);
                        started = true;
                    }
                }

                if (shouldStopAfterInitFailure)
                {
                    requestStopAndJoin();
                }

                if (started)
                {
                    logi("slot=%d recording attached to /dev/video%d", slot, videoIndex_);
                }
                return started;
            }

            bool attachConsumer(int consumerId, const std::shared_ptr<FrameConsumer> &consumer)
            {
                if (consumer == nullptr)
                {
                    return false;
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (!ensureStartedLocked())
                    {
                        return false;
                    }
                    if (consumers_.find(consumerId) != consumers_.end())
                    {
                        logw("consumer=%d already attached on /dev/video%d", consumerId, videoIndex_);
                        return false;
                    }
                    consumers_[consumerId] = consumer;
                    stopRequested_.store(false);
                }
                return true;
            }

            bool stopRecording(int slot)
            {
                return stopConsumer(slot);
            }

            bool stopConsumer(int consumerId)
            {
                std::shared_ptr<FrameConsumer> consumer;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    auto it = consumers_.find(consumerId);
                    if (it == consumers_.end())
                    {
                        return true;
                    }
                    consumer = it->second;
                }

                consumer->requestStop();
                bool stopped = consumer->waitUntilStopped(STOP_WAIT_MS);

                bool shouldStop = false;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    consumers_.erase(consumerId); // <- das ist der Fix
                    shouldStop = (previewWindow_ == nullptr && consumers_.empty());
                    if (shouldStop)
                    {
                        stopRequested_.store(true);
                    }
                }
                if (shouldStop)
                {
                    requestStopAndJoin();
                }
                return stopped;
            }

            bool isIdle()
            {
                std::lock_guard<std::mutex> lock(mutex_);
                return previewWindow_ == nullptr && consumers_.empty() && !running_.load();
            }

        private:
            bool hasConsumersLocked() const
            {
                return previewWindow_ != nullptr || !consumers_.empty();
            }

            void configurePreviewWindowLocked()
            {
                if (previewWindow_ == nullptr || cropWidth_ <= 0 || cropHeight_ <= 0)
                {
                    return;
                }
                ANativeWindow_setBuffersGeometry(previewWindow_, cropWidth_, cropHeight_,
                                                 AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
            }

            bool openCaptureLocked()
            {
                if (fd_ >= 0)
                {
                    return true;
                }

                const std::string devicePath = "/dev/video" + std::to_string(videoIndex_);
                fd_ = open(devicePath.c_str(), O_RDWR | O_CLOEXEC);
                if (fd_ < 0)
                {
                    loge("open %s failed: %s", devicePath.c_str(), errnoStr().c_str());
                    return false;
                }

                v4l2_format format{};
                format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                if (ioctl(fd_, VIDIOC_G_FMT, &format) < 0)
                {
                    loge("VIDIOC_G_FMT failed on %s: %s", devicePath.c_str(), errnoStr().c_str());
                    cleanupCaptureLocked();
                    return false;
                }

                pixelFormat_ = format.fmt.pix.pixelformat;
                packedFormat_ = packedFormatFromFourcc(pixelFormat_);
                if (packedFormat_ == PackedFormat::UNKNOWN)
                {
                    loge("/dev/video%d unsupported format %s", videoIndex_, fourccToString(pixelFormat_).c_str());
                    cleanupCaptureLocked();
                    return false;
                }

                srcWidth_ = format.fmt.pix.width;
                srcHeight_ = format.fmt.pix.height;
                srcStrideBytes_ = format.fmt.pix.bytesperline;
                cropWidth_ = srcWidth_;
                // One field is half the buffer; the frame is the whole of it. The device reports
                // 720x480 and calls it progressive, and it is not: the two halves are the two
                // fields of one interlaced frame. See docs/camera-format.md - it is measured, not
                // assumed. Keeping one field, which is what this app did until now, threw away
                // 127% more vertical detail than it kept.
                fieldHeight_ = srcHeight_ / 2;
                cropHeight_ = srcHeight_;

                {
                    char line[256];
                    snprintf(line, sizeof(line),
                             "/dev/video%d: %s %ux%u stride=%u size=%u field=%u %s -> using %dx%d",
                             videoIndex_, fourccToString(pixelFormat_).c_str(),
                             format.fmt.pix.width, format.fmt.pix.height,
                             format.fmt.pix.bytesperline, format.fmt.pix.sizeimage,
                             format.fmt.pix.field, fieldName(format.fmt.pix.field),
                             cropWidth_, cropHeight_);
                    logi("%s", line);
                    rememberFormat(videoIndex_, line);
                }

                v4l2_requestbuffers request{};
                request.count = 4;
                request.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                request.memory = V4L2_MEMORY_MMAP;
                if (ioctl(fd_, VIDIOC_REQBUFS, &request) < 0 || request.count < 1)
                {
                    loge("VIDIOC_REQBUFS failed on /dev/video%d", videoIndex_);
                    cleanupCaptureLocked();
                    return false;
                }

                buffers_.clear();
                buffers_.resize(request.count);
                for (unsigned i = 0; i < request.count; i++)
                {
                    v4l2_buffer buffer{};
                    buffer.type = request.type;
                    buffer.memory = V4L2_MEMORY_MMAP;
                    buffer.index = i;
                    if (ioctl(fd_, VIDIOC_QUERYBUF, &buffer) < 0)
                    {
                        loge("VIDIOC_QUERYBUF failed on /dev/video%d", videoIndex_);
                        cleanupCaptureLocked();
                        return false;
                    }

                    buffers_[i].length = buffer.length;
                    buffers_[i].start = mmap(nullptr, buffer.length, PROT_READ | PROT_WRITE,
                                             MAP_SHARED, fd_, buffer.m.offset);
                    if (buffers_[i].start == MAP_FAILED)
                    {
                        loge("mmap failed on /dev/video%d", videoIndex_);
                        cleanupCaptureLocked();
                        return false;
                    }

                    if (ioctl(fd_, VIDIOC_QBUF, &buffer) < 0)
                    {
                        loge("VIDIOC_QBUF failed on /dev/video%d", videoIndex_);
                        cleanupCaptureLocked();
                        return false;
                    }
                }

                v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                if (ioctl(fd_, VIDIOC_STREAMON, &type) < 0)
                {
                    loge("VIDIOC_STREAMON failed on /dev/video%d", videoIndex_);
                    cleanupCaptureLocked();
                    return false;
                }

                logi("/dev/video%d ready: %dx%d stride=%d fourcc=%s", videoIndex_, srcWidth_, srcHeight_,
                     srcStrideBytes_, fourccToString(pixelFormat_).c_str());
                return true;
            }

            bool ensureStartedLocked()
            {
                if (running_.load())
                {
                    return true;
                }
                if (!openCaptureLocked())
                {
                    return false;
                }
                stopRequested_.store(false);
                running_.store(true);
                if (worker_.joinable() && worker_.get_id() == std::this_thread::get_id())
                {
                    // Cannot happen today - this runs on whichever thread asked to record, never
                    // on the capture thread - but skipping the join below and falling through to
                    // the assignment would be the very bug this guard exists to prevent.
                    worker_.detach();
                }
                if (worker_.joinable())
                {
                    // The previous loop can end on its own, and usually does: once the last
                    // consumer is gone, cleanupStoppedConsumers() sets stopRequested_ from
                    // inside the loop, threadLoop() breaks out and clears running_ - and
                    // nothing joins worker_. That is exactly what happens every time the
                    // cameras are handed to the factory 360 view.
                    //
                    // Move-assigning onto a joinable std::thread is std::terminate() by the
                    // standard, so without this the process aborted the next time recording
                    // started: SIGABRT, "terminating", on whichever thread asked to record.
                    // The thread has already finished, so the join returns at once.
                    worker_.join();
                }
                auto self = shared_from_this();
                worker_ = std::thread([self]()
                                      { self->threadLoop(); });
                return true;
            }

            void cleanupStoppedConsumers()
            {
                std::vector<std::shared_ptr<FrameConsumer>> finalized;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    for (auto it = consumers_.begin(); it != consumers_.end();)
                    {
                        if (it->second->isStopRequested() || it->second->isFinalized())
                        {
                            finalized.push_back(it->second);
                            it = consumers_.erase(it);
                        }
                        else
                        {
                            ++it;
                        }
                    }
                    if (previewWindow_ == nullptr && consumers_.empty())
                    {
                        stopRequested_.store(true);
                    }
                }

                for (const auto &consumer : finalized)
                {
                    if (consumer != nullptr)
                    {
                        consumer->finalize();
                    }
                }
            }

            /**
             * How far a bottom-field pixel must sit outside both of its top-field neighbours
             * before it is treated as motion rather than detail.
             *
             * <p>The test is a product of two differences, so this is in units of luma squared:
             * 400 is both neighbours disagreeing with it by about 20 levels in the same
             * direction. Low enough to catch a moving edge, high enough that sensor noise - which
             * is a couple of levels, even on the night capture the format was measured from -
             * does not trip it.
             */
            static constexpr int COMB_THRESHOLD = 400;

            /**
             * Rebuilds the full frame from the two fields in the buffer, without combing.
             *
             * <p>The even lines are the top field and are always taken as they are: they were
             * captured, they are real. Every odd line has two candidates - the bottom field line
             * that was captured at that position, and the average of the top-field lines above
             * and below it.
             *
             * <p>Which one is right depends on whether anything moved in the 1/50 s between the
             * two fields. Weaving a still scene doubles the vertical resolution honestly; weaving
             * a moving one serrates every edge, because the two halves of the picture are showing
             * different moments. So the choice is made per pixel, by asking whether the captured
             * line disagrees with *both* of its neighbours in the same direction - which is what a
             * combed edge looks like and what a vertical detail does not.
             *
             * <p>Where it disagrees, the interpolation is used: that is exactly the picture this
             * app recorded before, so the worst case of this whole change is the old behaviour,
             * on the pixels that would have combed.
             */
            void deinterlaceLocked(const cv::Mat &packedFrame)
            {
                const int code = rgbaConversionCode(packedFormat_);
                const cv::Mat packedTop = packedFrame(cv::Rect(0, 0, cropWidth_, fieldHeight_));
                const cv::Mat packedBot = packedFrame(cv::Rect(0, fieldHeight_, cropWidth_, fieldHeight_));

                cv::cvtColor(packedTop, rgbaTopField_, code);
                cv::cvtColor(packedBot, rgbaBotField_, code);

                // Luma straight out of the packed pairs: UYVY carries it in channel 1, so this is
                // a strided read rather than another colour conversion.
                cv::extractChannel(packedTop, lumaTopField_, 1);
                cv::extractChannel(packedBot, lumaBotField_, 1);

                const int last = fieldHeight_ - 1;
                interpolated_.create(fieldHeight_, cropWidth_, CV_8UC4);
                cv::addWeighted(rgbaTopField_.rowRange(0, last), 0.5,
                                rgbaTopField_.rowRange(1, fieldHeight_), 0.5, 0.0,
                                interpolated_.rowRange(0, last));
                // The last odd line has no top-field line below it to average with.
                rgbaTopField_.row(last).copyTo(interpolated_.row(last));

                lumaTopField_.rowRange(0, last).convertTo(combA_, CV_16S);
                lumaTopField_.rowRange(1, fieldHeight_).convertTo(combB_, CV_16S);
                cv::Mat below;
                lumaBotField_.rowRange(0, last).convertTo(below, CV_16S);
                combA_ -= below;
                combB_ -= below;
                // Same sign means the captured line is outside the range of both neighbours, and
                // the product is then positive and large. CV_32S because 255 * 255 does not fit
                // in the 16-bit type the differences arrive in.
                cv::multiply(combA_, combB_, combProduct_, 1.0, CV_32S);
                combMask_.create(fieldHeight_, cropWidth_, CV_8UC1);
                combMask_.setTo(cv::Scalar(0));
                cv::compare(combProduct_, COMB_THRESHOLD, combMask_.rowRange(0, last), cv::CMP_GT);

                rgbaScratch_.create(cropHeight_, cropWidth_, CV_8UC4);
                // Views onto alternate lines of the output: same data, twice the row step. Lets
                // both fields be written with one copy each instead of a loop over 480 rows.
                cv::Mat evenLines(fieldHeight_, cropWidth_, CV_8UC4,
                                  rgbaScratch_.data, rgbaScratch_.step[0] * 2);
                cv::Mat oddLines(fieldHeight_, cropWidth_, CV_8UC4,
                                 rgbaScratch_.data + rgbaScratch_.step[0], rgbaScratch_.step[0] * 2);
                rgbaTopField_.copyTo(evenLines);
                rgbaBotField_.copyTo(oddLines);
                interpolated_.copyTo(oddLines, combMask_);
            }

            void renderPreviewLocked(const cv::Mat &rgbaFrame)
            {
                if (previewWindow_ == nullptr)
                {
                    return;
                }

                const cv::Mat *previewSource = &rgbaFrame;
                if (videoIndex_ == CAMERA_INDEX_REAR)
                {
                    previewScratch_.create(rgbaFrame.rows, rgbaFrame.cols, rgbaFrame.type());
                    cv::flip(rgbaFrame, previewScratch_, 1);
                    previewSource = &previewScratch_;
                }

                ANativeWindow_Buffer outBuffer{};
                if (ANativeWindow_lock(previewWindow_, &outBuffer, nullptr) != 0)
                {
                    return;
                }

                const int copyWidth = std::min(previewSource->cols, outBuffer.width);
                const int copyHeight = std::min(previewSource->rows, outBuffer.height);
                const uint8_t *src = previewSource->data;
                uint8_t *dst = static_cast<uint8_t *>(outBuffer.bits);
                const int srcStrideBytes = static_cast<int>(previewSource->step[0]);
                const int dstStrideBytes = outBuffer.stride * 4;
                for (int row = 0; row < copyHeight; row++)
                {
                    std::memcpy(dst + row * dstStrideBytes, src + row * srcStrideBytes, static_cast<size_t>(copyWidth) * 4U);
                }

                ANativeWindow_unlockAndPost(previewWindow_);
            }

            void threadLoop()
            {
                // OpenCV is linked statically here and reports every failure by throwing:
                // an unexpected frame size, a conversion code it does not know, a failed
                // allocation. Nothing in this file used to catch any of it, so a bad frame from
                // a camera the factory app was taking away did not end a capture - it called
                // std::terminate and took the whole process with it.
                //
                // Ending this one camera's capture is the right size of failure. The cleanup
                // below still runs, and ensureStartedLocked() can start it again afterwards.
                try
                {
                    while (running_.load())
                    {
                        bool shouldExit = false;
                        {
                            std::lock_guard<std::mutex> lock(mutex_);
                            shouldExit = stopRequested_.load() && !hasConsumersLocked();
                        }
                        if (shouldExit)
                        {
                            break;
                        }

                        fd_set readSet;
                        FD_ZERO(&readSet);
                        FD_SET(fd_, &readSet);
                        timeval timeout{0, PREVIEW_SELECT_TIMEOUT_US};
                        const int ready = select(fd_ + 1, &readSet, nullptr, nullptr, &timeout);
                        if (ready <= 0)
                        {
                            cleanupStoppedConsumers();
                            continue;
                        }

                        v4l2_buffer buffer{};
                        buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                        buffer.memory = V4L2_MEMORY_MMAP;
                        if (ioctl(fd_, VIDIOC_DQBUF, &buffer) < 0)
                        {
                            continue;
                        }

                        cv::Mat packedFrame(srcHeight_, srcWidth_, CV_8UC2, buffers_[buffer.index].start, srcStrideBytes_);
                        deinterlaceLocked(packedFrame);
                        countFrame(videoIndex_, nowUs());

                        std::vector<std::shared_ptr<FrameConsumer>> consumers;
                        {
                            std::lock_guard<std::mutex> lock(mutex_);
                            renderPreviewLocked(rgbaScratch_);
                            consumers.reserve(consumers_.size());
                            for (const auto &entry : consumers_)
                            {
                                consumers.push_back(entry.second);
                            }
                        }

                        for (const auto &consumer : consumers)
                        {
                            if (consumer != nullptr)
                            {
                                consumer->processFrame(rgbaScratch_);
                            }
                        }

                        ioctl(fd_, VIDIOC_QBUF, &buffer);
                        cleanupStoppedConsumers();
                    }

                }
                catch (const std::exception &e)
                {
                    loge("capture loop for /dev/video%d ended on an exception: %s",
                         videoIndex_, e.what());
                }
                catch (...)
                {
                    loge("capture loop for /dev/video%d ended on an unknown exception",
                         videoIndex_);
                }

                cleanupStoppedConsumers();
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    cleanupCaptureLocked();
                    running_.store(false);
                }
            }

            void cleanupCaptureLocked()
            {
                if (fd_ >= 0)
                {
                    v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                    ioctl(fd_, VIDIOC_STREAMOFF, &type);
                }

                for (MappedBuffer &buffer : buffers_)
                {
                    if (buffer.start != nullptr && buffer.start != MAP_FAILED)
                    {
                        munmap(buffer.start, buffer.length);
                    }
                    buffer.start = nullptr;
                    buffer.length = 0;
                }
                buffers_.clear();

                if (fd_ >= 0)
                {
                    close(fd_);
                    fd_ = -1;
                }

                rgbaScratch_.release();
                rgbaTopField_.release();
                rgbaBotField_.release();
                lumaTopField_.release();
                lumaBotField_.release();
                interpolated_.release();
                combA_.release();
                combB_.release();
                combProduct_.release();
                combMask_.release();
                srcWidth_ = 0;
                srcHeight_ = 0;
                srcStrideBytes_ = 0;
                cropWidth_ = 0;
                cropHeight_ = 0;
                fieldHeight_ = 0;
                pixelFormat_ = 0;
                packedFormat_ = PackedFormat::UNKNOWN;
            }

            void requestStopAndJoin()
            {
                std::thread worker;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    stopRequested_.store(true);
                    worker = std::move(worker_);
                }

                if (worker.joinable())
                {
                    if (worker.get_id() == std::this_thread::get_id())
                    {
                        // We are the capture thread, tearing down the session we belong to.
                        //
                        // That happens because the thread holds a shared_ptr to its own session:
                        // eraseSessionIfIdle() can drop the map's reference in the moment between
                        // threadLoop() clearing running_ and the lambda being destroyed, and then
                        // the last reference is ours. The destructor runs here, on this thread.
                        //
                        // Joining yourself is EDEADLK, join() throws, and the local std::thread is
                        // then destroyed still joinable - which is std::terminate(), called with
                        // no exception caught yet. That is the bare "terminating" abort on the car.
                        //
                        // Detaching is correct rather than merely safe: this thread is one
                        // statement from returning, and there is nothing left to wait for.
                        worker.detach();
                    }
                    else
                    {
                        worker.join();
                    }
                }

                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    running_.store(false);
                    cleanupCaptureLocked();
                }
            }

            const int videoIndex_;

            std::mutex mutex_;
            int fd_ = -1;
            __u32 pixelFormat_ = 0;
            PackedFormat packedFormat_ = PackedFormat::UNKNOWN;
            int srcWidth_ = 0;
            int srcHeight_ = 0;
            int srcStrideBytes_ = 0;
            int cropWidth_ = 0;
            /** The full frame height: what consumers now receive. */
            int cropHeight_ = 0;
            /** Half of it: the height of one field, and of every scratch buffer below. */
            int fieldHeight_ = 0;
            std::vector<MappedBuffer> buffers_;
            ANativeWindow *previewWindow_ = nullptr;
            std::unordered_map<int, std::shared_ptr<FrameConsumer>> consumers_;
            std::atomic<bool> running_{false};
            std::atomic<bool> stopRequested_{false};
            std::thread worker_;
            cv::Mat rgbaScratch_;
            // Deinterlacing scratch. Allocated once and reused: four cameras at 25 fps is no
            // place to be asking the allocator for anything.
            cv::Mat rgbaTopField_;
            cv::Mat rgbaBotField_;
            cv::Mat lumaTopField_;
            cv::Mat lumaBotField_;
            cv::Mat interpolated_;
            cv::Mat combA_;
            cv::Mat combB_;
            cv::Mat combProduct_;
            cv::Mat combMask_;
            cv::Mat previewScratch_;
        };

        std::mutex gManagerMutex;
        std::mutex gCombinedMutex;
        std::unordered_map<int, std::shared_ptr<CameraSession>> gSessions;
        std::unordered_map<int, int> gSlotToVideoIndex;
        std::shared_ptr<CombinedRecordingSink> gCombinedSink;
        bool gCombinedRecordingActive = false;

        static constexpr int COMBINED_CONSUMER_ID_BASE = 1000;
        static constexpr int COMBINED_CAMERA_MASK_ALL = 0x0F;
        static constexpr std::array<int, 4> COMBINED_VIDEO_INDICES = {
            CAMERA_VIDEO_INDEX_FRONT, CAMERA_VIDEO_INDEX_RIGHT,
            CAMERA_VIDEO_INDEX_LEFT,  CAMERA_VIDEO_INDEX_REAR
        };

        std::shared_ptr<CameraSession> getOrCreateSession(int videoIndex)
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSessions.find(videoIndex);
            if (it != gSessions.end())
            {
                return it->second;
            }
            auto session = std::make_shared<CameraSession>(videoIndex);
            gSessions[videoIndex] = session;
            return session;
        }

        std::shared_ptr<CameraSession> getSession(int videoIndex)
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSessions.find(videoIndex);
            return (it != gSessions.end()) ? it->second : nullptr;
        }

        std::shared_ptr<CameraSession> getSessionForSlot(int slot, int *outVideoIndex = nullptr)
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSlotToVideoIndex.find(slot);
            if (it == gSlotToVideoIndex.end())
            {
                return nullptr;
            }
            if (outVideoIndex != nullptr)
            {
                *outVideoIndex = it->second;
            }
            auto sessionIt = gSessions.find(it->second);
            return (sessionIt != gSessions.end()) ? sessionIt->second : nullptr;
        }

        void eraseSessionIfIdle(int videoIndex, const std::shared_ptr<CameraSession> &session)
        {
            if (session == nullptr || !session->isIdle())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(gManagerMutex);
            auto it = gSessions.find(videoIndex);
            if (it != gSessions.end() && it->second == session)
            {
                gSessions.erase(it);
            }
        }

    } // namespace

    bool attachPreview(JNIEnv *env, int videoIndex, jobject surface)
    {
        auto session = getOrCreateSession(videoIndex);
        const bool ok = session->attachPreview(env, surface);
        if (!ok)
        {
            eraseSessionIfIdle(videoIndex, session);
        }
        return ok;
    }

    void detachPreview(int videoIndex)
    {
        auto session = getSession(videoIndex);
        if (session == nullptr)
        {
            return;
        }
        session->detachPreview();
        eraseSessionIfIdle(videoIndex, session);
    }

    void detachAllPreviews()
    {
        std::vector<std::pair<int, std::shared_ptr<CameraSession>>> sessions;
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            sessions.reserve(gSessions.size());
            for (const auto &entry : gSessions)
            {
                sessions.emplace_back(entry.first, entry.second);
            }
        }
        for (const auto &entry : sessions)
        {
            entry.second->detachPreview();
            eraseSessionIfIdle(entry.first, entry.second);
        }
    }

    bool startRecording(JNIEnv * /*env*/, int slot, int videoIndex, const std::string &outputPath,
                        int width, int height, int fps, int bitrate)
    {
        auto session = getOrCreateSession(videoIndex);
        if (!session->startRecording(slot, outputPath, width, height, fps, bitrate))
        {
            eraseSessionIfIdle(videoIndex, session);
            return false;
        }

        std::lock_guard<std::mutex> lock(gManagerMutex);
        gSlotToVideoIndex[slot] = videoIndex;
        return true;
    }

    namespace
    {

    /**
     * Wires a composed sink to the cameras it needs, or to none of them if any refuses.
     *
     * <p>Shared by recording and preview: the two differ only in whether the sink has an encoder
     * behind it, and nothing about the attaching changes. Caller holds gCombinedMutex.
     */
    bool attachCombinedSinkLocked(const std::shared_ptr<CombinedRecordingSink> &sink, int normalizedMask)
    {
        std::vector<std::pair<int, std::shared_ptr<CameraSession>>> attached;
        std::vector<int> attachedConsumerIds;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            if ((normalizedMask & (1 << static_cast<int>(i))) == 0)
            {
                continue;
            }
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            const int consumerId = COMBINED_CONSUMER_ID_BASE + static_cast<int>(i);
            auto session = getOrCreateSession(videoIndex);
            auto tap = std::make_shared<CombinedInputTap>(static_cast<int>(i), sink);
            if (!session->attachConsumer(consumerId, tap))
            {
                sink->requestStop();
                for (size_t j = 0; j < attached.size(); j++)
                {
                    attached[j].second->stopConsumer(attachedConsumerIds[j]);
                    eraseSessionIfIdle(attached[j].first, attached[j].second);
                }
                sink->finalize();
                return false;
            }
            attached.emplace_back(videoIndex, session);
            attachedConsumerIds.push_back(consumerId);
        }
        return true;
    }

    /** Detaches whatever the combined sink was using. Caller must not hold gCombinedMutex. */
    bool detachCombinedSessions()
    {
        bool allStopped = true;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            auto session = getSession(videoIndex);
            if (session == nullptr)
            {
                continue;
            }
            if (!session->stopConsumer(COMBINED_CONSUMER_ID_BASE + static_cast<int>(i)))
            {
                allStopped = false;
            }
            eraseSessionIfIdle(videoIndex, session);
        }
        return allStopped;
    }

    } // namespace

    bool startCombinedRecording(JNIEnv * /*env*/, const std::string &outputPath,
                                int cellWidth, int cellHeight, int fps, int bitrate,
                                const std::string &signature, bool showSpeed, int cameraMask)
    {
        std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
        std::shared_ptr<CombinedRecordingSink> sink;
        if (gCombinedRecordingActive)
        {
            logw("combined recording already active");
            return false;
        }

        // A preview-only composer may be holding the cameras. It has to go before a recording
        // one can have them, but its window does not: it is handed over below so the picture on
        // screen carries on, now coming from the frames that are being written to the file.
        ANativeWindow *previewWindow = nullptr;
        if (gCombinedSink != nullptr)
        {
            auto previewSink = gCombinedSink;
            gCombinedSink.reset();
            previewWindow = previewSink->takePreviewWindow();
            previewSink->requestStop();
            detachCombinedSessions();
            previewSink->waitUntilStopped(STOP_WAIT_MS);
        }

        int normalizedMask = cameraMask & COMBINED_CAMERA_MASK_ALL;
        if (normalizedMask == 0)
        {
            normalizedMask = COMBINED_CAMERA_MASK_ALL;
        }

        sink = std::make_shared<CombinedRecordingSink>(
            outputPath,
            cellWidth,
            cellHeight,
            fps,
            bitrate,
            signature,
            showSpeed);
        if (!sink->initialize())
        {
            return false;
        }

        if (!attachCombinedSinkLocked(sink, normalizedMask))
        {
            return false;
        }

        // A preview that was running on its own composer hands its window to the recording one,
        // so starting to record does not blank the screen somebody is watching.
        if (previewWindow != nullptr)
        {
            sink->setPreviewWindow(previewWindow);
        }
        gCombinedSink = sink;
        gCombinedRecordingActive = true;
        logi("combined recording attached with camera mask 0x%x", normalizedMask);
        return true;
    }

    bool stopRecording(int slot)
    {
        int videoIndex = -1;
        auto session = getSessionForSlot(slot, &videoIndex);
        if (session == nullptr)
        {
            return true;
        }

        bool stopped = session->stopRecording(slot);
        {
            std::lock_guard<std::mutex> lock(gManagerMutex);
            gSlotToVideoIndex.erase(slot);
        }
        eraseSessionIfIdle(videoIndex, session);
        return stopped;
    }

    /**
     * Shows the composed grid on a Surface, exactly as it would be recorded.
     *
     * <p>If a recording is running the preview rides on its composer, so what appears is the very
     * frame being written to the file - not a second rendering that might differ from it. If not,
     * a composer is started with no encoder behind it: the same work up to the canvas, and
     * nothing after.
     */
    bool attachCombinedPreview(JNIEnv *env, jobject surface, int cellWidth, int cellHeight,
                               int fps, const std::string &signature, bool showSpeed,
                               int cameraMask)
    {
        if (surface == nullptr)
        {
            return false;
        }
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (window == nullptr)
        {
            logw("combined preview: ANativeWindow_fromSurface failed");
            return false;
        }

        std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
        if (gCombinedSink != nullptr)
        {
            gCombinedSink->setPreviewWindow(window);
            return true;
        }

        int normalizedMask = cameraMask & COMBINED_CAMERA_MASK_ALL;
        if (normalizedMask == 0)
        {
            normalizedMask = COMBINED_CAMERA_MASK_ALL;
        }
        // The empty output path is what makes this a preview: see CombinedRecordingSink.
        auto sink = std::make_shared<CombinedRecordingSink>(
            std::string(), cellWidth, cellHeight, fps, 0, signature, showSpeed);
        if (!sink->initialize() || !attachCombinedSinkLocked(sink, normalizedMask))
        {
            ANativeWindow_release(window);
            return false;
        }
        sink->setPreviewWindow(window);
        gCombinedSink = sink;
        logi("combined preview attached with camera mask 0x%x", normalizedMask);
        return true;
    }

    /**
     * Stops showing it. A recording composer keeps going without its window; a preview-only one
     * has nothing left to do and lets the cameras go.
     */
    int previewCanvasWidth(int cellWidth, int cellHeight)
    {
        return combinedCanvasWidth(cellWidth, cellHeight);
    }

    int previewCanvasHeight(int cellWidth, int cellHeight)
    {
        return combinedCanvasHeight(cellWidth, cellHeight);
    }

    bool detachCombinedPreview()
    {
        std::shared_ptr<CombinedRecordingSink> sink;
        {
            std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
            if (gCombinedSink == nullptr)
            {
                return true;
            }
            if (gCombinedRecordingActive)
            {
                gCombinedSink->setPreviewWindow(nullptr);
                return true;
            }
            sink = gCombinedSink;
            gCombinedSink.reset();
        }
        sink->setPreviewWindow(nullptr);
        sink->requestStop();
        const bool detached = detachCombinedSessions();
        const bool stopped = sink->waitUntilStopped(STOP_WAIT_MS);
        return detached && stopped;
    }

    bool stopCombinedRecording()
    {
        std::shared_ptr<CombinedRecordingSink> sink;
        {
            std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
            if (!gCombinedRecordingActive || gCombinedSink == nullptr)
            {
                return true;
            }
            sink = gCombinedSink;
            gCombinedSink.reset();
            gCombinedRecordingActive = false;
        }

        sink->requestStop();
        bool allConsumersStopped = true;
        for (size_t i = 0; i < COMBINED_VIDEO_INDICES.size(); i++)
        {
            const int videoIndex = COMBINED_VIDEO_INDICES[i];
            auto session = getSession(videoIndex);
            if (session == nullptr)
            {
                continue;
            }
            if (!session->stopConsumer(COMBINED_CONSUMER_ID_BASE + static_cast<int>(i)))
            {
                allConsumersStopped = false;
            }
            eraseSessionIfIdle(videoIndex, session);
        }

        const bool sinkStopped = sink->waitUntilStopped(STOP_WAIT_MS);
        return allConsumersStopped && sinkStopped;
    }

    std::string describeFormats()
    {
        std::lock_guard<std::mutex> lock(gFormatMutex);
        if (gFormats.empty())
        {
            return "no camera has been opened yet";
        }
        std::string out;
        for (const auto &entry : gFormats)
        {
            out += entry.second;
            out += "\n";
        }
        {
            std::lock_guard<std::mutex> rateLock(gRateMutex);
            const int64_t now = nowUs();
            char line[160];
            for (const auto &entry : gFrameCounts)
            {
                const double seconds = (now - gFrameFirstUs[entry.first]) / 1000000.0;
                snprintf(line, sizeof(line), "/dev/video%d: %lld frames in %.1fs = %.1f fps",
                         entry.first, entry.second, seconds,
                         seconds > 0.5 ? entry.second / seconds : 0.0);
                out += line;
                out += "\n";
            }
            if (gComposedFrames > 0)
            {
                const double seconds = (now - gComposedFirstUs) / 1000000.0;
                snprintf(line, sizeof(line), "grid: %lld composed in %.1fs = %.1f fps",
                         gComposedFrames, seconds,
                         seconds > 0.5 ? gComposedFrames / seconds : 0.0);
                out += line;
                out += "\n";
            }
        }
        return out;
    }

    void updateCombinedRecordingSpeed(int speedKmh)
    {
        std::shared_ptr<CombinedRecordingSink> sink;
        {
            std::lock_guard<std::mutex> combinedLock(gCombinedMutex);
            sink = gCombinedSink;
        }
        if (sink != nullptr)
        {
            sink->updateSpeedKmh(speedKmh);
        }
    }

} // namespace camera_stream_manager
