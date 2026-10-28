package dev.hyperscreen.client

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-ADB USB-C Open Accessory (`UsbAccessory`) Transport Engine.
 * Reassembles multi-slice Annex-B frames via C++20 `nativePushUdpPacketToReassembler`.
 */
class UsbAccessoryTransport(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onAnnexBFrame: (ByteArray) -> Unit,
    private val onCursorUpdate: (Long, Float, Float, Boolean) -> Unit,
    private val onConnected: () -> Unit
) {
    private var parcelFd: ParcelFileDescriptor? = null
    private var rawFd: Int = -1
    private var ringBufferHandle: Long = 0L
    private var reassemblerHandle: Long = 0L
    private val running = AtomicBoolean(false)
    private val outSequence = AtomicInteger(1)

    fun attachAccessory(accessory: UsbAccessory): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(accessory)) {
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent("dev.hyperscreen.USB_PERMISSION"),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(accessory, pi)
            return false
        }
        val pfd = usbManager.openAccessory(accessory) ?: return false
        parcelFd = pfd
        rawFd = pfd.fd
        ringBufferHandle = NativeCoreBridge.nativeCreateRingBuffer(4 * 1024 * 1024)
        reassemblerHandle = NativeCoreBridge.nativeCreateReassembler(512, 16400)
        running.set(true)
        onConnected()

        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val directPayloadBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            val directFullPktBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            val directOutFrameBuf = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val meta = IntArray(4)
            val frameMeta = LongArray(3)

            while (running.get() && rawFd >= 0) {
                val bytesRead = NativeCoreBridge.nativeReadUsbFdIntoRingBuffer(rawFd, ringBufferHandle, 65536)
                if (bytesRead < 0) break

                while (true) {
                    directPayloadBuf.clear()
                    val payloadLen = NativeCoreBridge.nativePopPacketIntoDirectBuffer(
                        ringBufferHandle,
                        directPayloadBuf,
                        meta
                    )
                    if (payloadLen <= 0) break

                    val pktType = meta[0]
                    val pktSeq = meta[1]
                    val pktFlags = meta[2]
                    if (pktType == 0x02 /* HS_PKT_VIDEO_SLICE */ || pktType == 0x03 /* HS_PKT_VIDEO_FEC */) {
                        // Reconstruct 16B header + slice payload for the C++20 frame reassembler
                        directFullPktBuf.clear()
                        directFullPktBuf.putShort(0, 0x5348.toShort()) // 'HS'
                        directFullPktBuf.put(2, 1.toByte())
                        directFullPktBuf.put(3, pktType.toByte())
                        directFullPktBuf.putInt(4, pktSeq)
                        directFullPktBuf.putInt(8, payloadLen)
                        directFullPktBuf.putShort(12, pktFlags.toShort())
                        directFullPktBuf.putShort(14, 0.toShort())
                        directFullPktBuf.position(16)
                        directPayloadBuf.position(0)
                        directPayloadBuf.limit(payloadLen)
                        directFullPktBuf.put(directPayloadBuf)

                        directOutFrameBuf.clear()
                        val frameBytes = NativeCoreBridge.nativePushUdpPacketToReassembler(
                            reassemblerHandle,
                            directFullPktBuf,
                            16 + payloadLen,
                            directOutFrameBuf,
                            frameMeta
                        )
                        if (frameBytes > 0) {
                            val annexB = ByteArray(frameBytes)
                            directOutFrameBuf.position(0)
                            directOutFrameBuf.get(annexB, 0, frameBytes)
                            onAnnexBFrame(annexB)
                        }
                    } else if (pktType == 0x05 /* HS_PKT_CURSOR_STATE */ && payloadLen >= 25) {
                        val tsUs = directPayloadBuf.getLong(0)
                        val nx = directPayloadBuf.getFloat(8)
                        val ny = directPayloadBuf.getFloat(12)
                        val vis = directPayloadBuf.get(24).toInt() != 0
                        onCursorUpdate(tsUs, nx, ny, vis)
                    }
                }
            }
        }, "HyperScreen-AOA-USB-Reader").start()

        return true
    }

    fun sendStylusEvent(
        action: Int,
        buttons: Int,
        modifiers: Int,
        normX: Float,
        normY: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float
    ) {
        val fd = rawFd
        if (fd < 0) return
        NativeCoreBridge.nativeWriteStylusEventToFd(
            fd,
            outSequence.getAndIncrement(),
            System.nanoTime() / 1_000L,
            action,
            buttons,
            modifiers,
            normX,
            normY,
            pressure,
            tiltX,
            tiltY
        )
    }

    fun sendTouchEvent(
        mode: Int,
        action: Int,
        pointerCount: Int,
        modifiers: Int,
        x: Float,
        y: Float,
        scrollDx: Float,
        scrollDy: Float
    ) {
        val fd = rawFd
        if (fd < 0) return
        NativeCoreBridge.nativeWriteTouchEventToFd(
            fd,
            outSequence.getAndIncrement(),
            System.nanoTime() / 1_000L,
            mode,
            action,
            pointerCount,
            modifiers,
            x,
            y,
            scrollDx,
            scrollDy,
            1.0f
        )
    }

    fun close() {
        running.set(false)
        runCatching { parcelFd?.close() }
        parcelFd = null
        rawFd = -1
        if (ringBufferHandle != 0L) {
            NativeCoreBridge.nativeDestroyRingBuffer(ringBufferHandle)
            ringBufferHandle = 0L
        }
        if (reassemblerHandle != 0L) {
            NativeCoreBridge.nativeDestroyReassembler(reassemblerHandle)
            reassemblerHandle = 0L
        }
    }
}
