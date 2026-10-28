#include <jni.h>
#include <unistd.h>
#include <string.h>
#include "hs_protocol.h"
#include "hs_fec.h"
#include "hs_bitstream.h"
#include "hs_filter.h"
#include "hs_ringbuffer.h"

namespace {
hs_one_euro_filter_2d_t g_cursor_filter{};
hs_one_euro_filter_2d_t g_stylus_filter{};
bool g_filters_initialized = false;

void ensure_filters_initialized() {
    if (!g_filters_initialized) {
        hs_fec_init();
        hs_one_euro_init(&g_cursor_filter, 1.2, 0.015, 1.0);
        hs_one_euro_init(&g_stylus_filter, 1.5, 0.020, 1.0);
        g_filters_initialized = true;
    }
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeCreateRingBuffer(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jint capacityPow2
) {
    ensure_filters_initialized();
    auto *rb = hs_ringbuffer_create(static_cast<size_t>(capacityPow2));
    return reinterpret_cast<jlong>(rb);
}

JNIEXPORT void JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeDestroyRingBuffer(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto *rb = reinterpret_cast<hs_spsc_ringbuffer_t *>(handle);
    hs_ringbuffer_destroy(rb);
}

/* Zero-GC POSIX bulk read from Android UsbAccessory ParcelFileDescriptor directly into C++ RingBuffer */
JNIEXPORT jint JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeReadUsbFdIntoRingBuffer(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jint usbFd,
    jlong rbHandle,
    jint maxChunkBytes
) {
    auto *rb = reinterpret_cast<hs_spsc_ringbuffer_t *>(rbHandle);
    if (!rb || usbFd < 0) return -1;
    const ssize_t n = hs_usb_fd_read_into_ringbuffer(
        static_cast<int>(usbFd),
        rb,
        static_cast<size_t>(maxChunkBytes)
    );
    return static_cast<jint>(n);
}

/* Pops the next CRC-16 validated packet from the C++ RingBuffer into a direct ByteBuffer for MediaCodec */
JNIEXPORT jint JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativePopPacketIntoDirectBuffer(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong rbHandle,
    jobject directOutBuffer,
    jintArray outMetaArray /* [0]=type, [1]=sequence, [2]=flags, [3]=payload_len */
) {
    auto *rb = reinterpret_cast<hs_spsc_ringbuffer_t *>(rbHandle);
    if (!rb || !directOutBuffer || !outMetaArray) return 0;

    auto *dstPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(directOutBuffer));
    const jlong dstCap = env->GetDirectBufferCapacity(directOutBuffer);
    if (!dstPtr || dstCap <= 0) return 0;

    hs_packet_header_t hdr{};
    if (!hs_ringbuffer_pop_packet(rb, &hdr, dstPtr, static_cast<size_t>(dstCap))) {
        return 0;
    }

    jint meta[4] = {
        static_cast<jint>(hdr.type),
        static_cast<jint>(hdr.sequence),
        static_cast<jint>(hdr.flags),
        static_cast<jint>(hdr.payload_len)
    };
    env->SetIntArrayRegion(outMetaArray, 0, 4, meta);
    return static_cast<jint>(hdr.payload_len);
}

/* Zero-copy writev of a stylus/touch input packet directly to Android UsbAccessory fd */
JNIEXPORT jint JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeWriteStylusEventToFd(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jint fd,
    jint sequence,
    jlong timestampUs,
    jint action,
    jint buttonMask,
    jint activeModifiers,
    jfloat normX,
    jfloat normY,
    jfloat pressure,
    jfloat tiltX,
    jfloat tiltY
) {
    hs_input_stylus_t stylus{};
    stylus.client_pts_us    = static_cast<uint64_t>(timestampUs);
    stylus.action           = static_cast<uint8_t>(action);
    stylus.button_mask      = static_cast<uint8_t>(buttonMask);
    stylus.active_modifiers = static_cast<uint8_t>(activeModifiers);
    stylus.norm_x           = normX;
    stylus.norm_y           = normY;
    stylus.pressure         = pressure;
    stylus.tilt_x           = tiltX;
    stylus.tilt_y           = tiltY;

    hs_packet_header_t hdr{};
    hs_packet_header_init(
        &hdr,
        HS_PKT_INPUT_STYLUS,
        static_cast<uint32_t>(sequence),
        sizeof(stylus),
        0
    );
    return static_cast<jint>(hs_usb_fd_write_packet(static_cast<int>(fd), &hdr, &stylus));
}

/* Filters 240Hz hardware cursor coordinates via the shared 1-Euro Filter */
JNIEXPORT void JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeFilterCursor2D(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong timestampUs,
    jfloat rawX,
    jfloat rawY,
    jfloatArray outXY
) {
    ensure_filters_initialized();
    float filtered[2] = {rawX, rawY};
    hs_one_euro_update_2d(
        &g_cursor_filter,
        static_cast<uint64_t>(timestampUs),
        rawX,
        rawY,
        &filtered[0],
        &filtered[1]
    );
    env->SetFloatArrayRegion(outXY, 0, 2, filtered);
}

/* Creates a C++20 Video Frame Slice + 0ms XOR FEC Reassembler for Wireless UDP streaming */
JNIEXPORT jlong JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeCreateReassembler(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jint maxSlicesPerFrame,
    jint maxSliceBytes
) {
    ensure_filters_initialized();
    auto *r = hs_reassembler_create(
        static_cast<size_t>(maxSlicesPerFrame),
        static_cast<size_t>(maxSliceBytes)
    );
    return reinterpret_cast<jlong>(r);
}

JNIEXPORT void JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeDestroyReassembler(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto *r = reinterpret_cast<hs_frame_reassembler_t *>(handle);
    hs_reassembler_destroy(r);
}

/* Pushes an incoming UDP video slice or FEC parity datagram into the C++20 reassembler.
 * Returns the completed Annex-B frame byte length (>0) when a frame is ready (including 0ms FEC recovery). */
JNIEXPORT jint JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativePushUdpPacketToReassembler(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong reassemblerHandle,
    jobject directPacketBuf,
    jint packetLen,
    jobject directOutFrameBuf,
    jlongArray outFrameMeta /* [0]=frame_id, [1]=capture_pts_us, [2]=flags */
) {
    auto *r = reinterpret_cast<hs_frame_reassembler_t *>(reassemblerHandle);
    if (!r || !directPacketBuf || !directOutFrameBuf || packetLen <= 0) return 0;

    const auto *pktPtr = static_cast<const uint8_t *>(env->GetDirectBufferAddress(directPacketBuf));
    auto *outPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(directOutFrameBuf));
    const jlong outCap = env->GetDirectBufferCapacity(directOutFrameBuf);
    if (!pktPtr || !outPtr || outCap <= 0) return 0;

    size_t outFrameLen = 0;
    uint32_t outFrameId = 0;
    uint64_t outPtsUs = 0;
    uint16_t outFlags = 0;

    if (!hs_reassembler_push_packet(
            r,
            pktPtr,
            static_cast<size_t>(packetLen),
            outPtr,
            static_cast<size_t>(outCap),
            &outFrameLen,
            &outFrameId,
            &outPtsUs,
            &outFlags
        )) {
        return 0;
    }

    if (outFrameMeta != nullptr) {
        jlong meta[3] = {
            static_cast<jlong>(outFrameId),
            static_cast<jlong>(outPtsUs),
            static_cast<jlong>(outFlags)
        };
        env->SetLongArrayRegion(outFrameMeta, 0, 3, meta);
    }
    return static_cast<jint>(outFrameLen);
}

/* Builds an `HS_PKT_VIDEO_NACK` packet for Instant Keyframe / Picture Loss Indication (PLI) request */
JNIEXPORT jbyteArray JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeBuildNackPacket(
    JNIEnv *env,
    jobject /*thiz*/,
    jint sequence,
    jint frameId,
    jint missingSliceIndex,
    jint missingCount,
    jint lastDecodedFrameId
) {
    uint8_t buf[sizeof(hs_packet_header_t) + sizeof(hs_video_nack_t)];
    size_t len = hs_build_video_nack_packet(
        static_cast<uint32_t>(sequence),
        static_cast<uint32_t>(frameId),
        static_cast<uint16_t>(missingSliceIndex),
        static_cast<uint16_t>(missingCount),
        static_cast<uint32_t>(lastDecodedFrameId),
        buf,
        sizeof(buf)
    );
    if (len == 0) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(buf));
    return arr;
}

/* Builds an `HS_PKT_PING_TELEMETRY` packet for RTT measurement and dynamic ABR */
JNIEXPORT jbyteArray JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeBuildTelemetryPingPacket(
    JNIEnv *env,
    jobject /*thiz*/,
    jint sequence,
    jlong hostTimestampUs,
    jint lastDecodedFrameId,
    jint packetsLostRecent,
    jint decodeTimeUs
) {
    uint8_t buf[sizeof(hs_packet_header_t) + sizeof(hs_telemetry_ping_t)];
    size_t len = hs_build_telemetry_packet(
        HS_PKT_PING_TELEMETRY,
        static_cast<uint32_t>(sequence),
        static_cast<uint64_t>(hostTimestampUs),
        static_cast<uint32_t>(lastDecodedFrameId),
        static_cast<uint16_t>(packetsLostRecent),
        static_cast<uint16_t>(decodeTimeUs),
        buf,
        sizeof(buf)
    );
    if (len == 0) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(buf));
    return arr;
}

/* Zero-copy writev of a Finger Touch / Virtual Trackpad gesture packet to USB AOA or Socket fd */
JNIEXPORT jint JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeWriteTouchEventToFd(
    JNIEnv * /*env*/,
    jobject /*thiz*/,
    jint fd,
    jint sequence,
    jlong timestampUs,
    jint mode,
    jint action,
    jint pointerCount,
    jint activeModifiers,
    jfloat x,
    jfloat y,
    jfloat scrollDx,
    jfloat scrollDy,
    jfloat pinchScale
) {
    hs_input_touch_t touch{};
    touch.client_pts_us    = static_cast<uint64_t>(timestampUs);
    touch.mode             = static_cast<uint8_t>(mode);
    touch.action           = static_cast<uint8_t>(action);
    touch.pointer_count    = static_cast<uint8_t>(pointerCount);
    touch.active_modifiers = static_cast<uint8_t>(activeModifiers);
    touch.x                = x;
    touch.y                = y;
    touch.scroll_dx        = scrollDx;
    touch.scroll_dy        = scrollDy;
    touch.pinch_scale      = pinchScale;

    hs_packet_header_t hdr{};
    hs_packet_header_init(
        &hdr,
        HS_PKT_INPUT_TOUCH,
        static_cast<uint32_t>(sequence),
        sizeof(touch),
        0
    );
    return static_cast<jint>(hs_usb_fd_write_packet(static_cast<int>(fd), &hdr, &touch));
}

/* Builds a CRC-16-CCITT signed HS_PKT_CLIENT_HELLO packet (70 bytes) for USB-C / TCP / UDP handshake */
JNIEXPORT jbyteArray JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeBuildClientHelloPacket(
    JNIEnv *env,
    jobject /*thiz*/,
    jint sequence,
    jint pixelWidth,
    jint pixelHeight,
    jint refreshRateHz,
    jlong sessionToken,
    jstring deviceNameStr
) {
    hs_hello_payload_t hello{};
    hello.pixel_width        = static_cast<uint32_t>(pixelWidth);
    hello.pixel_height       = static_cast<uint32_t>(pixelHeight);
    hello.physical_width_mm  = 254;
    hello.physical_height_mm = 160;
    hello.refresh_rate_hz    = static_cast<uint16_t>(refreshRateHz);
    hello.hidpi_enabled      = 1;
    hello.supports_hevc_444  = 0;
    hello.supports_av1       = 0;
    hello.transport_mode     = 0;
    hello.max_bitrate_kbps   = 50000;
    hello.session_token      = static_cast<uint64_t>(sessionToken);
    (void)deviceNameStr;

    hs_packet_header_t hdr{};
    hs_packet_header_init(
        &hdr,
        HS_PKT_CLIENT_HELLO,
        static_cast<uint32_t>(sequence),
        sizeof(hello),
        0
    );

    uint8_t raw[sizeof(hdr) + sizeof(hello)];
    memcpy(raw, &hdr, sizeof(hdr));
    memcpy(raw + sizeof(hdr), &hello, sizeof(hello));

    jbyteArray out = env->NewByteArray(static_cast<jsize>(sizeof(raw)));
    if (out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(sizeof(raw)), reinterpret_cast<const jbyte *>(raw));
    }
    return out;
}

/* Builds a CRC-16-CCITT signed HS_PKT_INPUT_STYLUS packet for USB-C / TCP / UDP transmission */
JNIEXPORT jbyteArray JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeBuildStylusPacket(
    JNIEnv *env,
    jobject /*thiz*/,
    jint sequence,
    jlong timestampUs,
    jint action,
    jint buttonMask,
    jint activeModifiers,
    jfloat normX,
    jfloat normY,
    jfloat pressure,
    jfloat tiltX,
    jfloat tiltY
) {
    hs_input_stylus_t stylus{};
    stylus.client_pts_us    = static_cast<uint64_t>(timestampUs);
    stylus.action           = static_cast<uint8_t>(action);
    stylus.button_mask      = static_cast<uint8_t>(buttonMask);
    stylus.active_modifiers = static_cast<uint8_t>(activeModifiers);
    stylus.norm_x           = normX;
    stylus.norm_y           = normY;
    stylus.pressure         = pressure;
    stylus.tilt_x           = tiltX;
    stylus.tilt_y           = tiltY;

    hs_packet_header_t hdr{};
    hs_packet_header_init(
        &hdr,
        HS_PKT_INPUT_STYLUS,
        static_cast<uint32_t>(sequence),
        sizeof(stylus),
        0
    );

    uint8_t raw[sizeof(hdr) + sizeof(stylus)];
    memcpy(raw, &hdr, sizeof(hdr));
    memcpy(raw + sizeof(hdr), &stylus, sizeof(stylus));

    jbyteArray out = env->NewByteArray(static_cast<jsize>(sizeof(raw)));
    if (out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(sizeof(raw)), reinterpret_cast<const jbyte *>(raw));
    }
    return out;
}

/* Builds a CRC-16-CCITT signed HS_PKT_INPUT_TOUCH packet for Finger Touch / Virtual Trackpad mode */
JNIEXPORT jbyteArray JNICALL
Java_dev_hyperscreen_client_NativeCoreBridge_nativeBuildTouchPacket(
    JNIEnv *env,
    jobject /*thiz*/,
    jint sequence,
    jlong timestampUs,
    jint mode,
    jint action,
    jint pointerCount,
    jint activeModifiers,
    jfloat x,
    jfloat y,
    jfloat scrollDx,
    jfloat scrollDy,
    jfloat pinchScale
) {
    hs_input_touch_t touch{};
    touch.client_pts_us    = static_cast<uint64_t>(timestampUs);
    touch.mode             = static_cast<uint8_t>(mode);
    touch.action           = static_cast<uint8_t>(action);
    touch.pointer_count    = static_cast<uint8_t>(pointerCount);
    touch.active_modifiers = static_cast<uint8_t>(activeModifiers);
    touch.x                = x;
    touch.y                = y;
    touch.scroll_dx        = scrollDx;
    touch.scroll_dy        = scrollDy;
    touch.pinch_scale      = pinchScale;

    hs_packet_header_t hdr{};
    hs_packet_header_init(
        &hdr,
        HS_PKT_INPUT_TOUCH,
        static_cast<uint32_t>(sequence),
        sizeof(touch),
        0
    );

    uint8_t raw[sizeof(hdr) + sizeof(touch)];
    memcpy(raw, &hdr, sizeof(hdr));
    memcpy(raw + sizeof(hdr), &touch, sizeof(touch));

    jbyteArray out = env->NewByteArray(static_cast<jsize>(sizeof(raw)));
    if (out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(sizeof(raw)), reinterpret_cast<const jbyte *>(raw));
    }
    return out;
}

} // extern "C"
