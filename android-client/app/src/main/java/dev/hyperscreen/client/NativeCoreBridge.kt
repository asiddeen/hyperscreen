package dev.hyperscreen.client

import java.nio.ByteBuffer

/**
 * JNI Bridge to `libhyperscreen_core` (C/C++20) for zero-GC USB AOA file descriptor I/O,
 * CRC-16 packet framing, GF(2^8) FEC recovery, and 1-Euro 240Hz cursor smoothing.
 */
object NativeCoreBridge {
    init {
        System.loadLibrary("hyperscreen_jni")
    }

    external fun nativeCreateRingBuffer(capacityPow2: Int): Long
    external fun nativeDestroyRingBuffer(handle: Long)
    external fun nativeReadUsbFdIntoRingBuffer(usbFd: Int, rbHandle: Long, maxChunkBytes: Int): Int
    external fun nativePopPacketIntoDirectBuffer(
        rbHandle: Long,
        directOutBuffer: ByteBuffer,
        outMetaArray: IntArray
    ): Int

    external fun nativeCreateReassembler(maxSlicesPerFrame: Int, maxSliceBytes: Int): Long
    external fun nativeDestroyReassembler(handle: Long)
    external fun nativePushUdpPacketToReassembler(
        reassemblerHandle: Long,
        directPacketBuf: ByteBuffer,
        packetLen: Int,
        directOutFrameBuf: ByteBuffer,
        outFrameMeta: LongArray
    ): Int

    external fun nativeBuildNackPacket(
        sequence: Int,
        frameId: Int,
        missingSliceIndex: Int,
        missingCount: Int,
        lastDecodedFrameId: Int
    ): ByteArray?

    external fun nativeBuildTelemetryPingPacket(
        sequence: Int,
        hostTimestampUs: Long,
        lastDecodedFrameId: Int,
        packetsLostRecent: Int,
        decodeTimeUs: Int
    ): ByteArray?

    external fun nativeWriteStylusEventToFd(
        fd: Int,
        sequence: Int,
        timestampUs: Long,
        action: Int,
        buttonMask: Int,
        activeModifiers: Int,
        normX: Float,
        normY: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float
    ): Int

    external fun nativeWriteTouchEventToFd(
        fd: Int,
        sequence: Int,
        timestampUs: Long,
        mode: Int,
        action: Int,
        pointerCount: Int,
        activeModifiers: Int,
        x: Float,
        y: Float,
        scrollDx: Float,
        scrollDy: Float,
        pinchScale: Float
    ): Int

    external fun nativeFilterCursor2D(
        timestampUs: Long,
        rawX: Float,
        rawY: Float,
        outXY: FloatArray
    )

    external fun nativeBuildClientHelloPacket(
        sequence: Int,
        pixelWidth: Int,
        pixelHeight: Int,
        refreshRateHz: Int,
        sessionToken: Long,
        deviceNameStr: String
    ): ByteArray

    external fun nativeBuildStylusPacket(
        sequence: Int,
        timestampUs: Long,
        action: Int,
        buttonMask: Int,
        activeModifiers: Int,
        normX: Float,
        normY: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float
    ): ByteArray

    external fun nativeBuildTouchPacket(
        sequence: Int,
        timestampUs: Long,
        mode: Int,
        action: Int,
        pointerCount: Int,
        activeModifiers: Int,
        x: Float,
        y: Float,
        scrollDx: Float,
        scrollDy: Float,
        pinchScale: Float
    ): ByteArray
}
