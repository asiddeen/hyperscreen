package dev.hyperscreen.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.text.InputType
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.input.motionprediction.MotionEventPredictor
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

enum class TransportMode {
    USB,
    WIFI
}

/**
 * Ultra-Low-Latency Hardware `MediaCodec` Engine (HEVC 4:2:0 / 4:4:4 & AV1).
 * Configures Android 11+ `KEY_LOW_LATENCY = 1` and vendor low-delay keys so frames decode in 1-2ms
 * with zero internal frame-reordering queue. Supports dynamic dimension and rotation changes.
 */
class LowLatencyMediaCodec(
    private val surface: Surface,
    private var width: Int,
    private var height: Int,
    private val refreshRateHz: Int = 90,
    private val useAv1: Boolean = false
) : Choreographer.FrameCallback {

    private var codec: MediaCodec? = null
    private val availableInputIndices = ConcurrentLinkedQueue<Int>()
    private val pendingFrames = ConcurrentLinkedQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val fallbackStage = AtomicInteger(0)
    @Volatile private var lastKeyframeBytes: ByteArray? = null
    @Volatile var lastDecodeLatencyUs: Int = 4000
    private var renderedFramesCount = 0L

    fun start() {
        startDecoderInternal(stage = 0)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun reconfigureDimensions(newW: Int, newH: Int) {
        if (width != newW || height != newH) {
            Log.i("HyperScreen", "Reconfiguring LowLatencyMediaCodec from ${width}x${height} to ${newW}x${newH}")
            width = newW
            height = newH
            startDecoderInternal(stage = fallbackStage.get())
        }
    }

    private fun startDecoderInternal(stage: Int) {
        fallbackStage.set(stage)
        availableInputIndices.clear()
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null

        val mime = if (useAv1) MediaFormat.MIMETYPE_VIDEO_AV1 else MediaFormat.MIMETYPE_VIDEO_HEVC
        val initW = maxOf(width, 320)
        val initH = maxOf(height, 240)
        val format = MediaFormat.createVideoFormat(mime, initW, initH).apply {
            if (stage == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
        }

        val decoder = runCatching {
            if (stage >= 2 && !useAv1) {
                MediaCodec.createByCodecName("c2.android.hevc.decoder")
            } else {
                MediaCodec.createDecoderByType(mime)
            }
        }.getOrElse { MediaCodec.createDecoderByType(mime) }

        decoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                val nextFrame = pendingFrames.poll()
                if (nextFrame != null) {
                    feedInputBuffer(mc, index, nextFrame)
                } else {
                    availableInputIndices.offer(index)
                }
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                runCatching {
                    val nowUs = System.nanoTime() / 1_000L
                    if (info.presentationTimeUs > 0) {
                        val lat = (nowUs - info.presentationTimeUs).toInt()
                        if (lat in 500..100_000) {
                            lastDecodeLatencyUs = lat
                        }
                    }
                    mc.releaseOutputBuffer(index, true)
                    renderedFramesCount++
                    if (renderedFramesCount == 1L || renderedFramesCount % 120L == 0L) {
                        Log.i("HyperScreen", "Rendered video frames: $renderedFramesCount (stage=$stage, lat=${lastDecodeLatencyUs}us)")
                    }
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("HyperScreen", "MediaCodec error at stage $stage: ${e.message}")
                val nextStage = fallbackStage.get() + 1
                if (nextStage <= 2 && isRunning.get()) {
                    lastKeyframeBytes?.let { kf -> pendingFrames.offer(kf) }
                    startDecoderInternal(stage = nextStage)
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                Log.i("HyperScreen", "Decoder output format changed: $format")
            }
        })

        runCatching {
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            isRunning.set(true)
            Log.i("HyperScreen", "Started MediaCodec (${decoder.name}) at stage=$stage")
        }.onFailure { err ->
            Log.e("HyperScreen", "Failed starting MediaCodec at stage=$stage: ${err.message}")
            if (stage < 2) {
                startDecoderInternal(stage = stage + 1)
            }
        }
    }

    fun submitAnnexBNaluDirect(directBuf: ByteBuffer, frameBytes: Int) {
        if (!isRunning.get() || frameBytes <= 0) return

        // Cache latest IDR keyframe (starts with 00 00 00 01 40 VPS NALU in HEVC)
        if (frameBytes > 5 &&
            directBuf.get(0) == 0.toByte() &&
            directBuf.get(1) == 0.toByte() &&
            directBuf.get(2) == 0.toByte() &&
            directBuf.get(3) == 1.toByte() &&
            ((directBuf.get(4).toInt() and 0x7E) shr 1) == 32
        ) {
            val kf = ByteArray(frameBytes)
            val pos = directBuf.position()
            directBuf.position(0)
            directBuf.get(kf, 0, frameBytes)
            directBuf.position(pos)
            lastKeyframeBytes = kf
        }

        val mc = codec ?: return
        val inputIdx = availableInputIndices.poll()
        if (inputIdx != null) {
            feedInputDirect(mc, inputIdx, directBuf, frameBytes)
        } else {
            // Keep at most 1 pending frame to eliminate queued delay
            if (pendingFrames.size >= 1) {
                pendingFrames.poll()
            }
            val copy = ByteArray(frameBytes)
            val pos = directBuf.position()
            directBuf.position(0)
            directBuf.get(copy, 0, frameBytes)
            directBuf.position(pos)
            pendingFrames.offer(copy)
        }
    }

    private fun feedInputDirect(mc: MediaCodec, index: Int, directBuf: ByteBuffer, frameBytes: Int) {
        val buf = runCatching { mc.getInputBuffer(index) }.getOrNull() ?: return
        buf.clear()
        val len = minOf(buf.remaining(), frameBytes)
        directBuf.position(0)
        directBuf.limit(len)
        buf.put(directBuf)
        runCatching {
            mc.queueInputBuffer(index, 0, len, System.nanoTime() / 1_000L, 0)
        }
    }

    fun submitAnnexBNaluFrame(annexBBytes: ByteArray) {
        if (!isRunning.get() || annexBBytes.isEmpty()) return
        // Cache latest IDR keyframe (starts with 00 00 00 01 40 VPS NALU in HEVC)
        if (annexBBytes.size > 5 &&
            annexBBytes[0] == 0.toByte() &&
            annexBBytes[1] == 0.toByte() &&
            annexBBytes[2] == 0.toByte() &&
            annexBBytes[3] == 1.toByte() &&
            ((annexBBytes[4].toInt() and 0x7E) shr 1) == 32
        ) {
            lastKeyframeBytes = annexBBytes
        }

        val mc = codec ?: return
        val inputIdx = availableInputIndices.poll()
        if (inputIdx != null) {
            feedInputBuffer(mc, inputIdx, annexBBytes)
        } else {
            if (pendingFrames.size >= 1) {
                pendingFrames.poll()
            }
            pendingFrames.offer(annexBBytes)
        }
    }

    private fun feedInputBuffer(mc: MediaCodec, index: Int, annexBBytes: ByteArray) {
        val buf = runCatching { mc.getInputBuffer(index) }.getOrNull() ?: return
        buf.clear()
        val len = minOf(buf.remaining(), annexBBytes.size)
        buf.put(annexBBytes, 0, len)
        runCatching {
            mc.queueInputBuffer(index, 0, len, System.nanoTime() / 1_000L, 0)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (isRunning.get()) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        isRunning.set(false)
        Choreographer.getInstance().removeFrameCallback(this)
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }
}

/**
 * Theme selection modes for HyperScreen Pro Android client.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * High-craft semantic color palette for HyperScreen Pro Android.
 * Adapts seamlessly between Dark and Light environments while preserving
 * high legibility, WCAG contrast compliance, and Apple/Material physical glass depth.
 */
class HyperScreenPalette(val isDark: Boolean) {
    // Root Canvas & Glass Layer
    val canvasBg = if (isDark) Color.parseColor("#0A0E1A") else Color.parseColor("#F1F5F9")
    val cardBg = if (isDark) Color.argb(246, 15, 22, 38) else Color.argb(252, 255, 255, 255)
    val cardBorder = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
    val cardShadow = if (isDark) Color.argb(110, 0, 0, 0) else Color.argb(25, 15, 23, 42)
    val cardInnerRim = if (isDark) Color.argb(22, 255, 255, 255) else Color.argb(140, 255, 255, 255)

    // Floating Streaming HUDs (High-Contrast Frosted Overlays over Mac Stream)
    val hudBg = if (isDark) Color.argb(235, 12, 17, 29) else Color.argb(242, 255, 255, 255)
    val hudBorder = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
    val hudShadow = if (isDark) Color.argb(130, 0, 0, 0) else Color.argb(35, 15, 23, 42)

    // Typography
    val title = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
    val subtitle = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
    val body = if (isDark) Color.parseColor("#E0F2FE") else Color.parseColor("#1E293B")
    val muted = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
    val caption = if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")

    // Buttons & Interactivity
    val primaryBtnBg = Color.parseColor("#0284C7")
    val primaryBtnText = Color.parseColor("#FFFFFF")
    val secondaryBtnBg = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#F8FAFC")
    val secondaryBtnBorder = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
    val secondaryBtnText = if (isDark) Color.parseColor("#F1F5F9") else Color.parseColor("#1E293B")
    val inactiveBtnText = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

    // Diagnostics & Panels
    val panelBg = if (isDark) Color.parseColor("#131B2E") else Color.parseColor("#F8FAFC")
    val panelBorder = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
    val checklistLabel = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

    // Status Signals & Halos
    val success = if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669")
    val successHalo = if (isDark) Color.argb(55, 52, 211, 153) else Color.argb(45, 5, 150, 105)
    val warning = if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
    val warningHalo = if (isDark) Color.argb(55, 245, 158, 11) else Color.argb(45, 217, 119, 6)
    val warningBg = if (isDark) Color.argb(35, 245, 158, 11) else Color.argb(30, 217, 119, 6)
    val warningTitle = if (isDark) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
    val warningHint = if (isDark) Color.parseColor("#E2E8F0") else Color.parseColor("#475569")

    // Contextual Errors
    val error = if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#DC2626")
    val errorBg = if (isDark) Color.argb(240, 28, 18, 24) else Color.argb(245, 254, 242, 242)
    val errorBorder = if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#FCA5A5")
    val errorDiagText = if (isDark) Color.parseColor("#FCA5A5") else Color.parseColor("#B91C1C")

    // Destructive Actions
    val disconnectBg = if (isDark) Color.parseColor("#DC2626") else Color.parseColor("#EF4444")
    val cancelBg = if (isDark) Color.parseColor("#991B1B") else Color.parseColor("#FEE2E2")
    val cancelBorder = if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#F87171")
    val cancelText = if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#991B1B")

    // Modifiers & Trackpad Badges
    val activeModifierBg = Color.parseColor("#6366F1")
    val trackpadBadgeBg = Color.parseColor("#A855F7")
    val rightClickBadgeBg = Color.parseColor("#0EA5E9")
}

/**
 * 240Hz Decoupled Hardware Vector Cursor Plane + Pro Connection Hub (when disconnected)
 * + Collapsible Pro HUD with One-Tap Disconnect & Mac Modifier Bar (when streaming).
 * Dynamically adapts to Light and Dark themes with refined glass aesthetics.
 */
class HardwareCursorAndModifierOverlayView(
    context: Context,
    private val onConnectUsbClicked: () -> Unit,
    private val onConnectWifiClicked: () -> Unit,
    private val onEnterPinClicked: () -> Unit,
    private val onScanQrClicked: () -> Unit,
    private val onDisconnectClicked: () -> Unit,
    private val onSelectTransportMode: (TransportMode) -> Unit,
    private val onCancelConnectionClicked: () -> Unit,
    private val onForgetMacClicked: () -> Unit = {},
    private val onDismissErrorClicked: () -> Unit = {}
) : View(context) {

    private fun isSystemInDarkTheme(): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    var themeMode: ThemeMode = ThemeMode.SYSTEM
        private set

    var palette: HyperScreenPalette = HyperScreenPalette(isSystemInDarkTheme())
        private set

    var onThemeChangedListener: ((ThemeMode) -> Unit)? = null

    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
    }
    private val cursorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeJoin = Paint.Join.ROUND
    }
    private val cursorTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        style = Paint.Style.FILL
    }
    private val cursorPath = Path()

    private val ambientGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val cardInnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val pillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val primaryBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val secondaryBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val secondaryBtnBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val disconnectBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cancelBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cancelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val activeKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackpadBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rightClickBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        textAlign = Paint.Align.CENTER
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val btnInactiveTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val versionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val checklistOkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checklistOkHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checklistWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checklistWarnHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checklistWaitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val errorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val errorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val panelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val brandIcon = runCatching {
        context.resources.getDrawable(R.mipmap.ic_launcher_foreground, context.theme)
    }.getOrNull()

    init {
        applyPalette()
    }

    fun applyPalette() {
        cardBgPaint.color = palette.cardBg
        cardBorderPaint.color = palette.cardBorder
        cardShadowPaint.color = palette.cardShadow
        cardInnerRimPaint.color = palette.cardInnerRim

        pillPaint.color = palette.hudBg
        pillStrokePaint.color = palette.hudBorder
        pillShadowPaint.color = palette.hudShadow

        primaryBtnPaint.color = palette.primaryBtnBg
        secondaryBtnPaint.color = palette.secondaryBtnBg
        secondaryBtnBorderPaint.color = palette.secondaryBtnBorder

        disconnectBtnPaint.color = palette.disconnectBg
        cancelBtnPaint.color = palette.cancelBg
        cancelBorderPaint.color = palette.cancelBorder

        activeKeyPaint.color = palette.activeModifierBg
        trackpadBadgePaint.color = palette.trackpadBadgeBg
        rightClickBadgePaint.color = palette.rightClickBadgeBg

        titlePaint.color = palette.title
        subtitlePaint.color = palette.subtitle
        btnTextPaint.color = palette.primaryBtnText
        btnInactiveTextPaint.color = palette.inactiveBtnText
        textPaint.color = palette.body

        statusTextPaint.color = palette.success
        badgeTextPaint.color = palette.success
        versionPaint.color = palette.caption

        checklistOkPaint.color = palette.success
        checklistOkHaloPaint.color = palette.successHalo
        checklistWarnPaint.color = palette.warning
        checklistWarnHaloPaint.color = palette.warningHalo
        checklistWaitPaint.color = palette.caption

        errorBgPaint.color = palette.errorBg
        errorBorderPaint.color = palette.errorBorder

        panelBgPaint.color = palette.panelBg
        panelBorderPaint.color = palette.panelBorder
    }

    fun setThemeMode(mode: ThemeMode) {
        themeMode = mode
        val isDark = when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        palette = HyperScreenPalette(isDark)
        applyPalette()
        postInvalidateOnAnimation()
        onThemeChangedListener?.invoke(mode)
    }

    fun cycleThemeMode() {
        val next = when (themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        setThemeMode(next)
    }

    fun onSystemConfigurationChanged(newConfig: Configuration) {
        if (themeMode == ThemeMode.SYSTEM) {
            val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            palette = HyperScreenPalette(isDark)
            applyPalette()
            postInvalidateOnAnimation()
        }
    }

    @Volatile private var cursorNormX: Float = 0.5f
    @Volatile private var cursorNormY: Float = 0.5f
    @Volatile private var cursorVisible: Boolean = true
    @Volatile var connectionStatusText: String = "Ready to Connect"
    @Volatile var hasReceivedVideoFrame: Boolean = false
    @Volatile var isDisconnectedByUser: Boolean = false
    @Volatile var isHudCollapsed: Boolean = false
    @Volatile var discoveredLanIpLabel: String = "192.168.18.9"
    @Volatile var discoveredMacName: String = "HyperScreen-Mac"
    @Volatile var discoveredMacPin: String = ""
    @Volatile var activeTransportMode: TransportMode = TransportMode.USB
    @Volatile var isConnecting: Boolean = false
    @Volatile var appVersion: String = "1.0.1"

    // SideScreen Pro Features: Pre-Flight USB Diagnostics, Saved Mac Profile & Contextual Error Recovery
    @Volatile var isUsbCablePlugged: Boolean = false
    @Volatile var isUsbTunnelActive: Boolean = false
    @Volatile var isMacHostReady: Boolean = false

    @Volatile var savedMacName: String? = null
    @Volatile var savedMacIp: String? = null
    @Volatile var savedMacPort: Int = 9453
    @Volatile var savedMacPin: String = ""
    @Volatile var isSavedMacOnline: Boolean = false

    @Volatile var isConnectionError: Boolean = false
    @Volatile var errorTitle: String = "Connection Failed"
    @Volatile var errorDiagnostic: String = ""
    @Volatile var errorHint: String = ""
    private val filteredXY = FloatArray(2)

    val activeModifiersMask = AtomicInteger(0)
    var isVirtualTrackpadMode: Boolean = false
    var isRightClickNextTap: Boolean = false

    fun updateCursorFromNetwork(timestampUs: Long, rawNormX: Float, rawNormY: Float, visible: Boolean) {
        NativeCoreBridge.nativeFilterCursor2D(timestampUs, rawNormX, rawNormY, filteredXY)
        cursorNormX = filteredXY[0]
        cursorNormY = filteredXY[1]
        cursorVisible = visible
        postInvalidateOnAnimation()
    }

    fun updateCursorFromLocalTouch(normX: Float, normY: Float) {
        cursorNormX = normX.coerceIn(0f, 1f)
        cursorNormY = normY.coerceIn(0f, 1f)
        cursorVisible = true
        postInvalidateOnAnimation()
    }

    fun updateStatus(status: String, streaming: Boolean = hasReceivedVideoFrame) {
        connectionStatusText = status
        hasReceivedVideoFrame = streaming
        if (streaming) {
            isConnecting = false
        }
        postInvalidateOnAnimation()
    }

    fun handleOverlayTouch(x: Float, y: Float): Boolean {
        if (width <= 0 || height <= 0) return false

        // 1. Disconnected / Standby Mode: Check Centered Pro Connection Hub Card
        if (isDisconnectedByUser || !hasReceivedVideoFrame) {
            val isPortrait = height > width
            val cardW = if (isPortrait) (width - 48f).coerceAtMost(640f) else (width - 64f).coerceAtMost(780f)
            val cardH = if (isPortrait) 660f.coerceAtMost(height - 48f) else 550f.coerceAtMost(height - 64f)
            val cardLeft = (width - cardW) / 2f
            val cardTop = (height - cardH) / 2f
            val cardRight = cardLeft + cardW
            val contentLeft = cardLeft + 36f
            val contentRight = cardRight - 36f
            val contentW = contentRight - contentLeft
            val segTop = cardTop + 124f
            val segH = 50f
            val segMid = contentLeft + contentW / 2f
            val pad = 12f

            // Theme Mode Toggle Button (Top-Right of Connection Hub Card)
            val themeBtnW = 104f
            val themeBtnH = 38f
            val themeBtnRight = cardRight - 36f
            val themeBtnLeft = themeBtnRight - themeBtnW
            val themeBtnTop = cardTop + 24f
            val themeBtnBottom = themeBtnTop + themeBtnH
            if (x in (themeBtnLeft - pad)..(themeBtnRight + pad) && y in (themeBtnTop - pad)..(themeBtnBottom + pad)) {
                cycleThemeMode()
                return true
            }

            // A. Segmented Switcher [ 🔌 USB-C ] [ 📶 Wireless LAN ]
            if (y in (segTop - pad)..(segTop + segH + pad)) {
                if (x in (contentLeft - pad)..segMid) {
                    if (activeTransportMode != TransportMode.USB) {
                        onSelectTransportMode(TransportMode.USB)
                    }
                    return true
                } else if (x in segMid..(contentRight + pad)) {
                    if (activeTransportMode != TransportMode.WIFI) {
                        onSelectTransportMode(TransportMode.WIFI)
                    }
                    return true
                }
            }

            // B. Inactive Virtual Display State: Disconnect to Standby
            val isInactiveDisplay = connectionStatusText.contains("Virtual Display Inactive")
            if (isInactiveDisplay) {
                val boxTop = segTop + segH + 20f
                val boxH = 96f
                val discTop = boxTop + boxH + 20f
                val discH = 64f
                if (x in (contentLeft - pad)..(contentRight + pad) && y in (discTop - pad)..(discTop + discH + pad)) {
                    onDisconnectClicked()
                    return true
                }
                return true
            }

            // C. Connecting State: Cancel Button
            if (isConnecting) {
                val boxTop = segTop + segH + 20f
                val boxH = 70f
                val cancelTop = boxTop + boxH + 20f
                val cancelH = 64f
                if (x in (contentLeft - pad)..(contentRight + pad) && y in (cancelTop - pad)..(cancelTop + cancelH + pad)) {
                    onCancelConnectionClicked()
                    return true
                }
                return true
            }

            // D. Contextual Error State: Recovery Button or Dismiss
            if (isConnectionError) {
                val boxTop = segTop + segH + 16f
                val boxH = 105f
                val recovTop = boxTop + boxH + 16f
                val recovH = 60f
                val dismissTop = recovTop + recovH + 12f
                val dismissH = 52f

                if (x in (contentLeft - pad)..(contentRight + pad)) {
                    if (y in (recovTop - pad)..(recovTop + recovH + pad)) {
                        if (activeTransportMode == TransportMode.USB) {
                            onConnectUsbClicked()
                        } else {
                            onScanQrClicked()
                        }
                        return true
                    } else if (y in (dismissTop - pad)..(dismissTop + dismissH + pad)) {
                        onDismissErrorClicked()
                        return true
                    }
                }
                return true
            }

            // E. USB Mode: Pre-Flight Diagnostic Checklist + Primary Connect Button
            if (activeTransportMode == TransportMode.USB) {
                val infoTop = segTop + segH + 16f
                val infoH = 118f
                val btnTop = infoTop + infoH + 18f
                val btnH = 64f
                if (x in (contentLeft - pad)..(contentRight + pad) && y in (btnTop - pad)..(btnTop + btnH + pad)) {
                    onConnectUsbClicked()
                    return true
                }
                return true
            }

            // F. Wi-Fi Mode with Saved Mac Profile: Reconnect / Scan QR / Forget
            if (savedMacIp != null) {
                val infoTop = segTop + segH + 16f
                val infoH = 104f
                val b1Top = infoTop + infoH + 14f
                val b1H = 60f
                if (x in (contentLeft - pad)..(contentRight + pad) && y in (b1Top - pad)..(b1Top + b1H + pad)) {
                    onConnectWifiClicked()
                    return true
                }

                val b2Top = b1Top + b1H + 12f
                val b2H = 54f
                val splitX = contentLeft + contentW * 0.58f
                if (y in (b2Top - pad)..(b2Top + b2H + pad)) {
                    if (x in (contentLeft - pad)..splitX) {
                        onScanQrClicked()
                        return true
                    } else if (x in splitX..(contentRight + pad)) {
                        onForgetMacClicked()
                        return true
                    }
                }
                return true
            }

            // G. Wi-Fi Mode without Saved Profile: 3 Action Buttons (Connect, PIN, QR)
            val infoTop = segTop + segH + 16f
            val infoH = 50f
            val b1Top = infoTop + infoH + 14f
            val b1H = 60f
            if (x in (contentLeft - pad)..(contentRight + pad) && y in (b1Top - pad)..(b1Top + b1H + pad)) {
                onConnectWifiClicked()
                return true
            }

            val b2Top = b1Top + b1H + 12f
            val b2H = 54f
            if (x in (contentLeft - pad)..(contentRight + pad) && y in (b2Top - pad)..(b2Top + b2H + pad)) {
                onEnterPinClicked()
                return true
            }

            val b3Top = b2Top + b2H + 12f
            val b3H = 54f
            if (x in (contentLeft - pad)..(contentRight + pad) && y in (b3Top - pad)..(b3Top + b3H + pad)) {
                onScanQrClicked()
                return true
            }
            return true
        }

        // 2. Streaming Mode — Collapsed HUD Pill in Top-Right
        if (isHudCollapsed) {
            val colRight = width - 24f
            val colLeft = colRight - 165f
            val colTop = 20f
            val colBottom = 74f
            if (x in colLeft..colRight && y in colTop..colBottom) {
                isHudCollapsed = false
                postInvalidateOnAnimation()
                return true
            }
            return false
        }

        // 3. Streaming Mode — Expanded Top-Right Control Bar (Status | QR/IP | Disconnect | Hide)
        val barRight = width - 24f
        val barLeft = (barRight - 690f).coerceAtLeast(24f)
        val barTop = 20f
        val barBottom = 84f
        if (x in barLeft..barRight && y in barTop..barBottom) {
            val hideLeft = barRight - 110f
            val discLeft = hideLeft - 175f
            val qrLeft = discLeft - 135f
            when {
                x >= hideLeft -> {
                    isHudCollapsed = true
                    postInvalidateOnAnimation()
                }
                x >= discLeft -> {
                    onDisconnectClicked()
                }
                x >= qrLeft -> {
                    onScanQrClicked()
                }
                else -> {
                    onScanQrClicked()
                }
            }
            return true
        }

        // 4. Streaming Mode — Bottom-Left Mac Pro Modifier & Input Bar
        val pillWidth = 840f.coerceAtMost(width - 48f)
        val pillHeight = 76f
        val pillLeft = 24f
        val pillTop = height - pillHeight - 24f
        if (x in pillLeft..(pillLeft + pillWidth) && y in pillTop..(pillTop + pillHeight)) {
            val keysStartLeft = pillLeft + 74f
            val keysWidth = pillWidth - 74f
            if (x >= keysStartLeft) {
                val slotW = keysWidth / 6f
                val idx = ((x - keysStartLeft) / slotW).toInt().coerceIn(0, 5)
                val bits = intArrayOf(1, 2, 4, 8, 0, 0)
                when (idx) {
                    in 0..3 -> {
                        val bit = bits[idx]
                        val cur = activeModifiersMask.get()
                        activeModifiersMask.set(cur xor bit)
                    }
                    4 -> {
                        isVirtualTrackpadMode = !isVirtualTrackpadMode
                    }
                    5 -> {
                        isRightClickNextTap = !isRightClickNextTap
                    }
                }
                postInvalidateOnAnimation()
            }
            return true
        }
        return false
    }

    /**
     * Draws text that is mathematically guaranteed to fit within [maxWidth].
     * Dynamically scales [paint.textSize] down towards [minTextSize]. If it still exceeds
     * [maxWidth] even at [minTextSize], it safely truncates and appends "…".
     */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Float,
        minTextSize: Float = 11f
    ) {
        if (text.isEmpty() || maxWidth <= 0f) return
        val origTextSize = paint.textSize
        val measuredW = paint.measureText(text)

        if (measuredW > maxWidth) {
            val scaleRatio = (maxWidth / measuredW) * 0.96f
            val candidateSize = (origTextSize * scaleRatio).coerceIn(minTextSize, origTextSize)
            paint.textSize = candidateSize
        }

        val textToDraw = if (paint.measureText(text) > maxWidth) {
            var truncated = text
            while (truncated.isNotEmpty() && paint.measureText("$truncated…") > maxWidth) {
                truncated = truncated.dropLast(1)
            }
            if (truncated.isNotEmpty()) "$truncated…" else ""
        } else {
            text
        }

        canvas.drawText(textToDraw, x, y, paint)
        paint.textSize = origTextSize
    }

    private fun drawMacVectorCursor(canvas: Canvas, cx: Float, cy: Float) {
        cursorPath.reset()
        cursorPath.moveTo(cx, cy)
        cursorPath.lineTo(cx, cy + 30f)
        cursorPath.lineTo(cx + 7.8f, cy + 23.2f)
        cursorPath.lineTo(cx + 13.5f, cy + 35f)
        cursorPath.lineTo(cx + 18.5f, cy + 32.5f)
        cursorPath.lineTo(cx + 12.8f, cy + 20.8f)
        cursorPath.lineTo(cx + 22f, cy + 20.8f)
        cursorPath.close()

        canvas.drawPath(cursorPath, cursorBorderPaint)
        canvas.drawPath(cursorPath, cursorFillPaint)
        canvas.drawCircle(cx + 1.5f, cy + 1.5f, 3.2f, cursorTipPaint)
    }

    private fun drawChecklistItem(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        text: String,
        isOk: Boolean,
        xText: Float,
        yText: Float,
        maxW: Float
    ) {
        val haloPaint = if (isOk) checklistOkHaloPaint else checklistWarnHaloPaint
        val dotPaint = if (isOk) checklistOkPaint else checklistWarnPaint
        canvas.drawCircle(cx, cy, 9f, haloPaint)
        canvas.drawCircle(cx, cy, 5.2f, dotPaint)
        val itemPaint = Paint(statusTextPaint).apply {
            color = if (isOk) palette.success else palette.muted
            textSize = 16f
            textAlign = Paint.Align.LEFT
        }
        drawFittedText(canvas, text, xText, yText, itemPaint, maxW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // A. Disconnected or Waiting for Stream: Render Canvas + Ambient Atmosphere + Centered Pro Connection Hub Card
        if (isDisconnectedByUser || !hasReceivedVideoFrame) {
            canvas.drawColor(palette.canvasBg)

            // Ambient background depth glow
            val glowRadius = maxOf(width, height) * 0.75f
            ambientGlowPaint.shader = RadialGradient(
                width / 2f,
                height * 0.28f,
                glowRadius,
                if (palette.isDark) {
                    intArrayOf(Color.argb(38, 2, 132, 199), Color.argb(0, 10, 14, 26))
                } else {
                    intArrayOf(Color.argb(45, 186, 230, 253), Color.argb(0, 241, 245, 249))
                },
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ambientGlowPaint)

            val isPortrait = height > width
            val cardW = if (isPortrait) (width - 48f).coerceAtMost(640f) else (width - 64f).coerceAtMost(780f)
            val cardH = if (isPortrait) 660f.coerceAtMost(height - 48f) else 550f.coerceAtMost(height - 64f)
            val cardLeft = (width - cardW) / 2f
            val cardTop = (height - cardH) / 2f
            val cardRight = cardLeft + cardW
            val cardBottom = cardTop + cardH

            // Card elevation shadow, background, border & inner glass rim
            canvas.drawRoundRect(cardLeft + 2f, cardTop + 6f, cardRight + 2f, cardBottom + 12f, 36f, 36f, cardShadowPaint)
            canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, 36f, 36f, cardBgPaint)
            canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, 36f, 36f, cardBorderPaint)
            canvas.drawRoundRect(cardLeft + 1.5f, cardTop + 1.5f, cardRight - 1.5f, cardBottom - 1.5f, 34.5f, 34.5f, cardInnerRimPaint)

            brandIcon?.let { drawable ->
                val iconSize = 52
                val left = (cardLeft + 36f).toInt()
                val top = (cardTop + 24f).toInt()
                drawable.setBounds(left, top, left + iconSize, top + iconSize)
                drawable.draw(canvas)
            }

            // Theme Mode Toggle Button (Top-Right of Connection Hub Card)
            val themeBtnW = 104f
            val themeBtnH = 38f
            val themeBtnRight = cardRight - 36f
            val themeBtnLeft = themeBtnRight - themeBtnW
            val themeBtnTop = cardTop + 24f
            val themeBtnBottom = themeBtnTop + themeBtnH
            canvas.drawRoundRect(themeBtnLeft, themeBtnTop, themeBtnRight, themeBtnBottom, 19f, 19f, secondaryBtnPaint)
            canvas.drawRoundRect(themeBtnLeft, themeBtnTop, themeBtnRight, themeBtnBottom, 19f, 19f, secondaryBtnBorderPaint)
            val (tIcon, tLabel) = when (themeMode) {
                ThemeMode.SYSTEM -> "🌓" to "Auto"
                ThemeMode.LIGHT -> "☀️" to "Light"
                ThemeMode.DARK -> "🌙" to "Dark"
            }
            val themeBtnTextPaint = Paint(btnTextPaint).apply {
                color = palette.secondaryBtnText
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            drawFittedText(
                canvas,
                "$tIcon $tLabel",
                (themeBtnLeft + themeBtnRight) / 2f,
                themeBtnTop + 24f,
                themeBtnTextPaint,
                themeBtnW - 12f
            )

            val contentLeft = cardLeft + 36f
            val contentRight = cardRight - 36f
            val contentW = contentRight - contentLeft

            val headerTitle = if (activeTransportMode == TransportMode.USB) "HyperScreen Pro • USB" else "HyperScreen Pro • Wi-Fi"
            drawFittedText(canvas, headerTitle, cardLeft + cardW / 2f, cardTop + 60f, titlePaint, cardW - 220f)
            val isInactiveDisplay = connectionStatusText.contains("Virtual Display Inactive")
            subtitlePaint.color = if (isInactiveDisplay) palette.warning else palette.subtitle
            drawFittedText(canvas, connectionStatusText, cardLeft + cardW / 2f, cardTop + 100f, subtitlePaint, contentW)

            // 1. Segmented Switcher [ 🔌 USB-C ] [ 📶 Wireless LAN ]
            val segTop = cardTop + 124f
            val segH = 50f
            val segMid = contentLeft + contentW / 2f
            val isUsb = (activeTransportMode == TransportMode.USB)
            val segBtnMaxW = (contentW / 2f) - 20f

            // Track background & border
            canvas.drawRoundRect(contentLeft, segTop, contentRight, segTop + segH, 20f, 20f, panelBgPaint)
            canvas.drawRoundRect(contentLeft, segTop, contentRight, segTop + segH, 20f, 20f, panelBorderPaint)

            // USB-C Segment
            if (isUsb) {
                canvas.drawRoundRect(contentLeft + 3f, segTop + 3f, segMid - 3f, segTop + segH - 3f, 17f, 17f, primaryBtnPaint)
                drawFittedText(canvas, "🔌 USB-C", (contentLeft + segMid) / 2f, segTop + 32f, btnTextPaint, segBtnMaxW)
                drawFittedText(canvas, "📶 Wireless LAN", (segMid + contentRight) / 2f, segTop + 32f, btnInactiveTextPaint, segBtnMaxW)
            } else {
                canvas.drawRoundRect(segMid + 3f, segTop + 3f, contentRight - 3f, segTop + segH - 3f, 17f, 17f, primaryBtnPaint)
                drawFittedText(canvas, "🔌 USB-C", (contentLeft + segMid) / 2f, segTop + 32f, btnInactiveTextPaint, segBtnMaxW)
                drawFittedText(canvas, "📶 Wireless LAN", (segMid + contentRight) / 2f, segTop + 32f, btnTextPaint, segBtnMaxW)
            }

            // 2. Dynamic Content: Inactive Display vs Connecting vs Error vs USB vs Wi-Fi
            if (isInactiveDisplay) {
                val boxTop = segTop + segH + 20f
                val boxH = 96f
                canvas.drawRoundRect(contentLeft, boxTop, contentRight, boxTop + boxH, 20f, 20f, panelBgPaint)
                val warnStroke = Paint(panelBorderPaint).apply {
                    color = palette.warning
                    strokeWidth = 2.2f
                }
                canvas.drawRoundRect(contentLeft, boxTop, contentRight, boxTop + boxH, 20f, 20f, warnStroke)
                val warnTitlePaint = Paint(textPaint).apply {
                    color = palette.warningTitle
                    textSize = 21f
                }
                drawFittedText(canvas, "⚠️ Mac Virtual Display is Inactive", (contentLeft + contentRight) / 2f, boxTop + 38f, warnTitlePaint, contentW - 24f)
                val warnHintPaint = Paint(versionPaint).apply {
                    color = palette.warningHint
                    textSize = 17f
                }
                drawFittedText(canvas, "Click 'Start Virtual Display' (⌘S) on your Mac to stream", (contentLeft + contentRight) / 2f, boxTop + 68f, warnHintPaint, contentW - 24f)

                val discTop = boxTop + boxH + 20f
                val discH = 64f
                canvas.drawRoundRect(contentLeft, discTop, contentRight, discTop + discH, 20f, 20f, disconnectBtnPaint)
                drawFittedText(canvas, "⏹ Disconnect to Standby", (contentLeft + contentRight) / 2f, discTop + 40f, btnTextPaint, contentW - 24f)

                drawFittedText(canvas, "v$appVersion • Waiting for Mac display activation...", cardLeft + cardW / 2f, discTop + discH + 32f, versionPaint, cardW - 32f)
                return
            }

            if (isConnecting) {
                val boxTop = segTop + segH + 20f
                val boxH = 70f
                canvas.drawRoundRect(contentLeft, boxTop, contentRight, boxTop + boxH, 20f, 20f, panelBgPaint)
                canvas.drawRoundRect(contentLeft, boxTop, contentRight, boxTop + boxH, 20f, 20f, panelBorderPaint)
                drawFittedText(canvas, "⏳ Connecting... Tap Cancel to abort", (contentLeft + contentRight) / 2f, boxTop + 43f, textPaint, contentW - 24f)

                val cancelTop = boxTop + boxH + 20f
                val cancelH = 64f
                canvas.drawRoundRect(contentLeft, cancelTop, contentRight, cancelTop + cancelH, 20f, 20f, cancelBtnPaint)
                canvas.drawRoundRect(contentLeft, cancelTop, contentRight, cancelTop + cancelH, 20f, 20f, cancelBorderPaint)
                val cancelTextPaint = Paint(btnTextPaint).apply {
                    color = palette.cancelText
                }
                drawFittedText(canvas, "✕ Cancel Connection", (contentLeft + contentRight) / 2f, cancelTop + 40f, cancelTextPaint, contentW - 24f)

                drawFittedText(canvas, "v$appVersion • Ultra-Low Latency Display Engine", cardLeft + cardW / 2f, cancelTop + cancelH + 32f, versionPaint, cardW - 32f)
                return
            }

            if (isConnectionError) {
                val boxTop = segTop + segH + 16f
                val boxH = 105f
                canvas.drawRoundRect(contentLeft, boxTop, contentRight, boxTop + boxH, 20f, 20f, errorBgPaint)
                canvas.drawRoundRect(contentLeft, boxTop, contentRight, boxTop + boxH, 20f, 20f, errorBorderPaint)

                val errTitlePaint = Paint(titlePaint).apply {
                    color = palette.error
                    textSize = 21f
                    textAlign = Paint.Align.CENTER
                }
                val errDiagPaint = Paint(subtitlePaint).apply {
                    color = palette.errorDiagText
                    textSize = 17f
                    textAlign = Paint.Align.CENTER
                }
                val errHintPaint = Paint(versionPaint).apply {
                    color = palette.muted
                    textSize = 15f
                    textAlign = Paint.Align.CENTER
                }
                drawFittedText(canvas, "⚠️ $errorTitle", (contentLeft + contentRight) / 2f, boxTop + 34f, errTitlePaint, contentW - 24f)
                drawFittedText(canvas, errorDiagnostic, (contentLeft + contentRight) / 2f, boxTop + 62f, errDiagPaint, contentW - 24f)
                drawFittedText(canvas, "💡 $errorHint", (contentLeft + contentRight) / 2f, boxTop + 88f, errHintPaint, contentW - 24f)

                val recovTop = boxTop + boxH + 16f
                val recovH = 60f
                val recovText = if (isUsb) "⚡ Retry USB-C Connection" else "📷 Scan Mac QR Code to Re-Pair"
                canvas.drawRoundRect(contentLeft, recovTop, contentRight, recovTop + recovH, 20f, 20f, primaryBtnPaint)
                drawFittedText(canvas, recovText, (contentLeft + contentRight) / 2f, recovTop + 38f, btnTextPaint, contentW - 24f)

                val dismissTop = recovTop + recovH + 12f
                val dismissH = 52f
                canvas.drawRoundRect(contentLeft, dismissTop, contentRight, dismissTop + dismissH, 18f, 18f, secondaryBtnPaint)
                canvas.drawRoundRect(contentLeft, dismissTop, contentRight, dismissTop + dismissH, 18f, 18f, secondaryBtnBorderPaint)
                val dismissTextPaint = Paint(btnTextPaint).apply {
                    color = palette.secondaryBtnText
                }
                drawFittedText(canvas, "✕ Dismiss to Standby", (contentLeft + contentRight) / 2f, dismissTop + 33f, dismissTextPaint, contentW - 24f)

                drawFittedText(canvas, "v$appVersion • Contextual Error Recovery", cardLeft + cardW / 2f, dismissTop + dismissH + 28f, versionPaint, cardW - 32f)
                return
            }

            if (isUsb) {
                // USB-C Content with Pre-Flight Diagnostics
                val infoTop = segTop + segH + 16f
                val infoH = 118f
                canvas.drawRoundRect(contentLeft, infoTop, contentRight, infoTop + infoH, 18f, 18f, panelBgPaint)
                canvas.drawRoundRect(contentLeft, infoTop, contentRight, infoTop + infoH, 18f, 18f, panelBorderPaint)

                val chkHeaderPaint = Paint(textPaint).apply {
                    color = palette.checklistLabel
                    textSize = 14f
                    textAlign = Paint.Align.LEFT
                    letterSpacing = 0.08f
                }
                drawFittedText(canvas, "PRE-FLIGHT HARDWARE DIAGNOSTICS", contentLeft + 22f, infoTop + 24f, chkHeaderPaint, contentW - 44f)

                val cableOk = isUsbCablePlugged || isUsbTunnelActive
                drawChecklistItem(
                    canvas = canvas,
                    cx = contentLeft + 28f,
                    cy = infoTop + 47f,
                    text = if (cableOk) "USB-C Cable Connected" else "USB-C Cable Disconnected (Plug cable to Mac)",
                    isOk = cableOk,
                    xText = contentLeft + 44f,
                    yText = infoTop + 52f,
                    maxW = contentW - 56f
                )

                val tunnelOk = isUsbTunnelActive
                drawChecklistItem(
                    canvas = canvas,
                    cx = contentLeft + 28f,
                    cy = infoTop + 73f,
                    text = if (tunnelOk) "Port 9453 Loopback Active (127.0.0.1:9453)" else "Port 9453 Loopback Waiting (run 'adb reverse')",
                    isOk = tunnelOk,
                    xText = contentLeft + 44f,
                    yText = infoTop + 78f,
                    maxW = contentW - 56f
                )

                val hostOk = isMacHostReady || isUsbTunnelActive
                drawChecklistItem(
                    canvas = canvas,
                    cx = contentLeft + 28f,
                    cy = infoTop + 99f,
                    text = if (hostOk) "HyperScreen Mac Host Ready" else "HyperScreen Mac Host Waiting",
                    isOk = hostOk,
                    xText = contentLeft + 44f,
                    yText = infoTop + 104f,
                    maxW = contentW - 56f
                )

                val btnTop = infoTop + infoH + 18f
                val btnH = 64f
                canvas.drawRoundRect(contentLeft, btnTop, contentRight, btnTop + btnH, 20f, 20f, primaryBtnPaint)
                drawFittedText(canvas, "⚡ Connect via USB-C Cable", (contentLeft + contentRight) / 2f, btnTop + 41f, btnTextPaint, contentW - 24f)

                drawFittedText(canvas, "Requires USB cable with USB Debugging enabled on tablet", cardLeft + cardW / 2f, btnTop + btnH + 30f, versionPaint, cardW - 32f)
                drawFittedText(canvas, "v$appVersion • Zero-Lag 120Hz Hardware Pipeline", cardLeft + cardW / 2f, btnTop + btnH + 54f, versionPaint, cardW - 32f)
                return
            }

            // Wi-Fi Content: Saved Mac Profile vs Discovery
            val savedIp = savedMacIp
            if (savedIp != null) {
                val infoTop = segTop + segH + 16f
                val infoH = 104f
                canvas.drawRoundRect(contentLeft, infoTop, contentRight, infoTop + infoH, 18f, 18f, panelBgPaint)
                canvas.drawRoundRect(contentLeft, infoTop, contentRight, infoTop + infoH, 18f, 18f, panelBorderPaint)

                val badgeStr = if (isSavedMacOnline) "● Online via Bonjour • Ready to Connect" else "○ Saved Mac Profile"
                val bColor = if (isSavedMacOnline) palette.success else palette.subtitle
                val bPaint = Paint(badgeTextPaint).apply {
                    color = bColor
                    textSize = 15f
                    textAlign = Paint.Align.LEFT
                }
                drawFittedText(canvas, badgeStr, contentLeft + 22f, infoTop + 26f, bPaint, contentW - 44f)

                val namePaint = Paint(titlePaint).apply {
                    color = palette.title
                    textSize = 23f
                    textAlign = Paint.Align.LEFT
                }
                val displayName = savedMacName ?: "Saved Mac"
                drawFittedText(canvas, "💻 $displayName", contentLeft + 22f, infoTop + 58f, namePaint, contentW - 44f)

                val pinFormatted = if (savedMacPin.isNotEmpty()) {
                    val digits = savedMacPin.filter { it.isDigit() }
                    if (digits.length == 8) "${digits.substring(0, 4)}-${digits.substring(4)}" else savedMacPin
                } else ""
                val detailStr = if (pinFormatted.isNotEmpty()) {
                    "IP: $savedIp:$savedMacPort • PIN: $pinFormatted"
                } else {
                    "IP: $savedIp:$savedMacPort"
                }
                val detailPaint = Paint(versionPaint).apply {
                    color = palette.muted
                    textSize = 15f
                    textAlign = Paint.Align.LEFT
                }
                drawFittedText(canvas, detailStr, contentLeft + 22f, infoTop + 86f, detailPaint, contentW - 44f)

                // Action 1: 1-Tap Reconnect
                val b1Top = infoTop + infoH + 14f
                val b1H = 60f
                canvas.drawRoundRect(contentLeft, b1Top, contentRight, b1Top + b1H, 20f, 20f, primaryBtnPaint)
                drawFittedText(canvas, "⚡ Reconnect to $displayName", (contentLeft + contentRight) / 2f, b1Top + 38f, btnTextPaint, contentW - 24f)

                // Action 2 & 3: Split Row [ 📷 Scan QR / PIN ] [ ✕ Forget This Mac ]
                val b2Top = b1Top + b1H + 12f
                val b2H = 54f
                val splitX = contentLeft + contentW * 0.58f

                // Left: Scan QR / PIN
                canvas.drawRoundRect(contentLeft, b2Top, splitX - 6f, b2Top + b2H, 18f, 18f, secondaryBtnPaint)
                canvas.drawRoundRect(contentLeft, b2Top, splitX - 6f, b2Top + b2H, 18f, 18f, secondaryBtnBorderPaint)
                val splitBtnPaint = Paint(btnTextPaint).apply {
                    color = palette.secondaryBtnText
                }
                drawFittedText(canvas, "📷 Scan QR / PIN", (contentLeft + splitX - 6f) / 2f, b2Top + 34f, splitBtnPaint, (splitX - 6f - contentLeft) - 16f)

                // Right: Forget This Mac
                canvas.drawRoundRect(splitX + 6f, b2Top, contentRight, b2Top + b2H, 18f, 18f, secondaryBtnPaint)
                canvas.drawRoundRect(splitX + 6f, b2Top, contentRight, b2Top + b2H, 18f, 18f, secondaryBtnBorderPaint)
                val forgetPaint = Paint(btnTextPaint).apply {
                    color = palette.error
                }
                drawFittedText(canvas, "✕ Forget", (splitX + 6f + contentRight) / 2f, b2Top + 34f, forgetPaint, (contentRight - splitX - 6f) - 16f)

                drawFittedText(canvas, "v$appVersion • 5GHz/6GHz Wi-Fi recommended", cardLeft + cardW / 2f, b2Top + b2H + 28f, versionPaint, cardW - 32f)
                return
            }

            // Wi-Fi Content without Saved Profile
            val infoTop = segTop + segH + 16f
            val infoH = 50f
            canvas.drawRoundRect(contentLeft, infoTop, contentRight, infoTop + infoH, 16f, 16f, panelBgPaint)
            canvas.drawRoundRect(contentLeft, infoTop, contentRight, infoTop + infoH, 16f, 16f, panelBorderPaint)
            val wifiBadge = if (discoveredMacPin.isNotEmpty() || discoveredLanIpLabel != "192.168.18.9") {
                "● Discovered: $discoveredMacName ($discoveredLanIpLabel)"
            } else {
                "○ Searching for Mac via Bonjour mDNS..."
            }
            val discoveryPaint = Paint(badgeTextPaint).apply {
                color = if (discoveredMacPin.isNotEmpty() || discoveredLanIpLabel != "192.168.18.9") palette.success else palette.muted
            }
            drawFittedText(canvas, wifiBadge, (contentLeft + contentRight) / 2f, infoTop + 32f, discoveryPaint, contentW - 24f)

            // Action 1: Connect Wi-Fi
            val b1Top = infoTop + infoH + 14f
            val b1H = 60f
            canvas.drawRoundRect(contentLeft, b1Top, contentRight, b1Top + b1H, 20f, 20f, primaryBtnPaint)
            drawFittedText(canvas, "📶 Connect Wireless LAN ($discoveredLanIpLabel)", (contentLeft + contentRight) / 2f, b1Top + 38f, btnTextPaint, contentW - 24f)

            // Action 2: Enter Pairing PIN
            val b2Top = b1Top + b1H + 12f
            val b2H = 54f
            canvas.drawRoundRect(contentLeft, b2Top, contentRight, b2Top + b2H, 18f, 18f, secondaryBtnPaint)
            canvas.drawRoundRect(contentLeft, b2Top, contentRight, b2Top + b2H, 18f, 18f, secondaryBtnBorderPaint)
            val wifiBtnTextPaint = Paint(btnTextPaint).apply {
                color = palette.secondaryBtnText
            }
            drawFittedText(canvas, "🔑 Enter 8-Digit Pairing PIN", (contentLeft + contentRight) / 2f, b2Top + 35f, wifiBtnTextPaint, contentW - 24f)

            // Action 3: Scan QR Code / Manual IP
            val b3Top = b2Top + b2H + 12f
            val b3H = 54f
            canvas.drawRoundRect(contentLeft, b3Top, contentRight, b3Top + b3H, 18f, 18f, secondaryBtnPaint)
            canvas.drawRoundRect(contentLeft, b3Top, contentRight, b3Top + b3H, 18f, 18f, secondaryBtnBorderPaint)
            drawFittedText(canvas, "📷 Scan Mac QR Code / Manual IP", (contentLeft + contentRight) / 2f, b3Top + 35f, wifiBtnTextPaint, contentW - 24f)

            drawFittedText(canvas, "v$appVersion • 5GHz/6GHz Wi-Fi recommended", cardLeft + cardW / 2f, b3Top + b3H + 28f, versionPaint, cardW - 32f)
            return
        }

        // B. Connected & Streaming: Suppress duplicate vector cursor when receiving video frames
        // (because macOS ScreenCaptureKit composites the native hardware cursor directly into the video
        // stream with full dynamic shapes: arrow, I-beam text selection, pointer hand, resize handles).
        if (!hasReceivedVideoFrame && cursorVisible) {
            val cx = cursorNormX * width
            val cy = cursorNormY * height
            drawMacVectorCursor(canvas, cx, cy)
        }

        // C. If HUD is Collapsed: Render minimal top-right pill ("⚡ HUD ▾") with high-contrast frosted backing
        if (isHudCollapsed) {
            val colRight = width - 24f
            val colLeft = colRight - 165f
            val colTop = 20f
            val colBottom = 74f
            canvas.drawRoundRect(colLeft + 1f, colTop + 3f, colRight + 1f, colBottom + 5f, 27f, 27f, pillShadowPaint)
            canvas.drawRoundRect(colLeft, colTop, colRight, colBottom, 27f, 27f, pillPaint)
            canvas.drawRoundRect(colLeft, colTop, colRight, colBottom, 27f, 27f, pillStrokePaint)
            val colTextPaint = Paint(textPaint).apply {
                color = palette.body
                textSize = 18f
            }
            drawFittedText(canvas, "⚡ HUD ▾", (colLeft + colRight) / 2f, colTop + 35f, colTextPaint, (colRight - colLeft) - 16f)
            return
        }

        // D. Expanded Top-Right Pro Control Bar (Status | QR/IP | Disconnect | Hide)
        val barRight = width - 24f
        val barLeft = (barRight - 690f).coerceAtLeast(24f)
        val barTop = 20f
        val barBottom = 84f
        canvas.drawRoundRect(barLeft + 1f, barTop + 3f, barRight + 1f, barBottom + 5f, 32f, 32f, pillShadowPaint)
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 32f, 32f, pillPaint)
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 32f, 32f, pillStrokePaint)

        val hideLeft = barRight - 110f
        val discLeft = hideLeft - 175f
        val qrLeft = discLeft - 135f

        val statusMaxW = ((qrLeft - 12f) - (barLeft + 44f)).coerceAtLeast(60f)
        canvas.drawCircle(barLeft + 26f, (barTop + barBottom) / 2f, 8f, checklistOkHaloPaint)
        canvas.drawCircle(barLeft + 26f, (barTop + barBottom) / 2f, 4.5f, checklistOkPaint)
        drawFittedText(canvas, connectionStatusText, barLeft + 42f, barTop + 38f, statusTextPaint, statusMaxW)

        // QR / IP Button
        canvas.drawRoundRect(qrLeft, barTop + 10f, discLeft - 10f, barBottom - 10f, 22f, 22f, secondaryBtnPaint)
        canvas.drawRoundRect(qrLeft, barTop + 10f, discLeft - 10f, barBottom - 10f, 22f, 22f, secondaryBtnBorderPaint)
        val hudBtnTextPaint = Paint(btnTextPaint).apply {
            color = palette.secondaryBtnText
            textSize = 19f
        }
        drawFittedText(canvas, "📷 IP/QR", (qrLeft + discLeft - 10f) / 2f, barTop + 40f, hudBtnTextPaint, (discLeft - 10f - qrLeft) - 10f)

        // Disconnect Button (Red)
        canvas.drawRoundRect(discLeft, barTop + 10f, hideLeft - 10f, barBottom - 10f, 22f, 22f, disconnectBtnPaint)
        drawFittedText(canvas, "⏹ Disconnect", (discLeft + hideLeft - 10f) / 2f, barTop + 40f, btnTextPaint, (hideLeft - 10f - discLeft) - 10f)

        // Collapse / Hide HUD Button
        canvas.drawRoundRect(hideLeft, barTop + 10f, barRight - 12f, barBottom - 10f, 22f, 22f, secondaryBtnPaint)
        canvas.drawRoundRect(hideLeft, barTop + 10f, barRight - 12f, barBottom - 10f, 22f, 22f, secondaryBtnBorderPaint)
        drawFittedText(canvas, "▴ Hide", (hideLeft + barRight - 12f) / 2f, barTop + 40f, hudBtnTextPaint, (barRight - 12f - hideLeft) - 10f)

        // E. Bottom-Left Mac Pro Modifier & Input Bar
        val pillWidth = 840f.coerceAtMost(width - 48f)
        val pillHeight = 76f
        val pillLeft = 24f
        val pillTop = height - pillHeight - 24f
        canvas.drawRoundRect(pillLeft + 1f, pillTop + 3f, pillLeft + pillWidth + 1f, pillTop + pillHeight + 5f, 38f, 38f, pillShadowPaint)
        canvas.drawRoundRect(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight, 38f, 38f, pillPaint)
        canvas.drawRoundRect(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight, 38f, 38f, pillStrokePaint)

        val iconPadding = 12
        val iconSize = (pillHeight - iconPadding * 2).toInt()
        brandIcon?.let { drawable ->
            val left = (pillLeft + 14f).toInt()
            val top = (pillTop + iconPadding).toInt()
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.draw(canvas)
        }

        val keysStartLeft = pillLeft + 74f
        val keysWidth = pillWidth - 74f
        val labels = arrayOf(
            "⌘ Cmd",
            "⌥ Opt",
            "⌃ Ctrl",
            "⇧ Shift",
            if (isVirtualTrackpadMode) "🖱️ Trackpad" else "👆 Touch",
            "R-Click"
        )
        val bits = intArrayOf(1, 2, 4, 8, 0, 0)
        val slotW = keysWidth / labels.size
        val mask = activeModifiersMask.get()

        val activeKeyTextPaint = Paint(btnTextPaint).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val inactiveKeyTextPaint = Paint(btnTextPaint).apply {
            color = palette.body
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        for (i in labels.indices) {
            val slotLeft = keysStartLeft + i * slotW
            val isSelected = when (i) {
                in 0..3 -> (mask and bits[i]) != 0
                4 -> isVirtualTrackpadMode
                5 -> isRightClickNextTap
                else -> false
            }
            if (isSelected) {
                val badgePaint = when (i) {
                    4 -> trackpadBadgePaint
                    5 -> rightClickBadgePaint
                    else -> activeKeyPaint
                }
                canvas.drawRoundRect(
                    slotLeft + 4f,
                    pillTop + 8f,
                    slotLeft + slotW - 4f,
                    pillTop + pillHeight - 8f,
                    26f,
                    26f,
                    badgePaint
                )
                drawFittedText(
                    canvas,
                    labels[i],
                    slotLeft + slotW / 2f,
                    pillTop + pillHeight / 2f + 7f,
                    activeKeyTextPaint,
                    slotW - 8f
                )
            } else {
                drawFittedText(
                    canvas,
                    labels[i],
                    slotLeft + slotW / 2f,
                    pillTop + pillHeight / 2f + 7f,
                    inactiveKeyTextPaint,
                    slotW - 8f
                )
            }
        }
    }
}

/**
 * Unbuffered Pro Input Dispatcher for both Stylus (S-Pen / USI) and Finger Touch / Trackpad gestures.
 */
class UnbufferedInputHandler(
    targetView: View,
    private val overlay: HardwareCursorAndModifierOverlayView,
    private val onStylusPacket: (
        action: Int,
        buttons: Int,
        modifiers: Int,
        normX: Float,
        normY: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float
    ) -> Unit,
    private val onTouchPacket: (
        mode: Int,
        action: Int,
        pointerCount: Int,
        modifiers: Int,
        x: Float,
        y: Float,
        scrollDx: Float,
        scrollDy: Float
    ) -> Unit
) : View.OnTouchListener, View.OnGenericMotionListener {

    private val predictor: MotionEventPredictor? = runCatching {
        MotionEventPredictor.newInstance(targetView)
    }.getOrNull()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isInteractingWithOverlay = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            v.requestUnbufferedDispatch(event)
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            isInteractingWithOverlay = overlay.handleOverlayTouch(event.x, event.y)
            if (isInteractingWithOverlay) {
                return true
            }
        } else if (isInteractingWithOverlay) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isInteractingWithOverlay = false
            }
            return true
        } else if (overlay.isDisconnectedByUser || !overlay.hasReceivedVideoFrame) {
            return true
        }

        predictor?.record(event)

        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            dispatchStylusEvent(v, event)
            return true
        }

        dispatchFingerEvent(v, event)
        return true
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_STYLUS)) {
            dispatchStylusEvent(v, event)
            return true
        }
        return false
    }

    private fun dispatchFingerEvent(v: View, event: MotionEvent) {
        if (v.width <= 0 || v.height <= 0) return
        val hsAction = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                0
            }
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> 2
            else -> return
        }

        val dx = event.x - lastTouchX
        val dy = event.y - lastTouchY
        lastTouchX = event.x
        lastTouchY = event.y

        val isTrackpad = overlay.isVirtualTrackpadMode
        val normX = (event.x / v.width.toFloat()).coerceIn(0f, 1f)
        val normY = (event.y / v.height.toFloat()).coerceIn(0f, 1f)
        if (!isTrackpad) {
            overlay.updateCursorFromLocalTouch(normX, normY)
        }

        val normOrDeltaX = if (isTrackpad) dx else normX
        val normOrDeltaY = if (isTrackpad) dy else normY
        val scrollDx = if (event.pointerCount >= 2) dx else 0f
        val scrollDy = if (event.pointerCount >= 2) dy else 0f

        var effectivePtrs = event.pointerCount
        if (overlay.isRightClickNextTap) {
            effectivePtrs = 2
            if (hsAction == 2) {
                overlay.isRightClickNextTap = false
                overlay.postInvalidateOnAnimation()
            }
        }

        onTouchPacket(
            if (isTrackpad) 1 else 0,
            hsAction,
            effectivePtrs,
            overlay.activeModifiersMask.get(),
            normOrDeltaX,
            normOrDeltaY,
            scrollDx,
            scrollDy
        )
    }

    private fun dispatchStylusEvent(v: View, event: MotionEvent) {
        if (v.width <= 0 || v.height <= 0) return

        val predicted = predictor?.predict()
        val sampleEvent = predicted ?: event

        val normX = (sampleEvent.x / v.width.toFloat()).coerceIn(0f, 1f)
        val normY = (sampleEvent.y / v.height.toFloat()).coerceIn(0f, 1f)
        overlay.updateCursorFromLocalTouch(normX, normY)

        val pressure = sampleEvent.pressure.coerceIn(0f, 1f)
        val tiltRad = sampleEvent.getAxisValue(MotionEvent.AXIS_TILT)
        val orientationRad = sampleEvent.getAxisValue(MotionEvent.AXIS_ORIENTATION)
        val tiltX = (kotlin.math.sin(orientationRad.toDouble()) * kotlin.math.sin(tiltRad.toDouble())).toFloat()
        val tiltY = (kotlin.math.cos(orientationRad.toDouble()) * kotlin.math.sin(tiltRad.toDouble())).toFloat()

        predicted?.recycle()

        val hsAction = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
            MotionEvent.ACTION_HOVER_MOVE -> 3
            MotionEvent.ACTION_HOVER_ENTER -> 4
            MotionEvent.ACTION_HOVER_EXIT -> 5
            else -> 1
        }

        val buttonMask = if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) 4 else event.buttonState
        onStylusPacket(
            hsAction,
            buttonMask,
            overlay.activeModifiersMask.get(),
            normX,
            normY,
            pressure,
            tiltX,
            tiltY
        )
    }
}

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

/**
 * Main Fullscreen Immersive Display Activity for HyperScreen Pro on Android 11+ (API 30+).
 * Multi-Path Auto-Connect:
 * 1. USB-C Direct AOA v2 (`UsbAccessory` intent + live hotplug broadcast + `accessoryList` scan)
 * 2. USB-C High-Speed Tunnel (`127.0.0.1:9453` via automatic macOS `adb reverse tcp:9453 tcp:9453`)
 * 3. Wireless 5GHz/6GHz LAN (`_hyperscreen._udp` Bonjour + QR Deep-Link + Manual Mac IP Dialog)
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var cursorOverlay: HardwareCursorAndModifierOverlayView
    private var decoder: LowLatencyMediaCodec? = null
    private var usbTransport: UsbAccessoryTransport? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val isActiveSession = AtomicBoolean(false)
    private val isSurfaceAlive = AtomicBoolean(false)
    private val isAppRunning = AtomicBoolean(true)
    private val isBackgrounded = AtomicBoolean(false)
    private var streamThread: Thread? = null
    private val outSequence = AtomicInteger(1)
    private val activeTcpOut = AtomicReference<OutputStream?>(null)
    private val activeStreamSocket = AtomicReference<Socket?>(null)
    private val activeUdpSocket = AtomicReference<DatagramSocket?>(null)
    private val activeUdpTarget = AtomicReference<InetSocketAddress?>(null)

    @Volatile private var preferredHostIp: String = "127.0.0.1"
    @Volatile private var preferredHostPort: Int = 9453
    @Volatile private var discoveredLanHostIp: String = "192.168.18.9"
    @Volatile private var isUserDisconnected: Boolean = true
    @Volatile private var activeTransportMode: TransportMode = TransportMode.USB
    @Volatile private var currentRotationAngle: Int = 0
    @Volatile private var isMacDisplayStandby = false
    @Volatile private var wasStreamingBeforeBackground = false
    @Volatile private var resumeTargetIp: String? = null
    @Volatile private var resumeTargetPort: Int = 9453

    companion object {
        private const val PREFS_NAME = "hyperscreen_prefs"
        private const val KEY_SAVED_MAC_NAME = "last_mac_name"
        private const val KEY_SAVED_MAC_IP = "last_mac_ip"
        private const val KEY_SAVED_MAC_PORT = "last_mac_port"
        private const val KEY_SAVED_MAC_PIN = "last_mac_pin"
    }

    private var hasSavedCurrentProfile = false
    private val usbCheckHandler = Handler(android.os.Looper.getMainLooper())
    private val usbCheckRunnable = object : Runnable {
        override fun run() {
            if (!isAppRunning.get()) return
            if (activeTransportMode == TransportMode.USB && !cursorOverlay.hasReceivedVideoFrame) {
                performUsbPreFlightCheck()
            }
            usbCheckHandler.postDelayed(this, 1200)
        }
    }

    private fun isUsbDebuggingEnabled(): Boolean {
        return runCatching {
            android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    private fun performUsbPreFlightCheck() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: 0
        val isCable = (plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC)

        val adbEnabled = isUsbDebuggingEnabled()

        // Only test loopback socket when idle on connection screen to prevent socket thrashing
        if (!cursorOverlay.isConnecting && !cursorOverlay.hasReceivedVideoFrame && !isActiveSession.get()) {
            Thread {
                val canConnect = if (adbEnabled) {
                    runCatching {
                        Socket().use { s ->
                            s.connect(InetSocketAddress("127.0.0.1", 9453), 150)
                            true
                        }
                    }.getOrDefault(false)
                } else false

                runOnUiThread {
                    cursorOverlay.isUsbCablePlugged = isCable || canConnect
                    cursorOverlay.isUsbTunnelActive = canConnect
                    cursorOverlay.isMacHostReady = canConnect
                    cursorOverlay.postInvalidateOnAnimation()
                }
            }.start()
        } else {
            cursorOverlay.isUsbCablePlugged = isCable
        }
    }

    private fun clearVideoSurface() {
        if (!isSurfaceAlive.get()) return
        runCatching {
            val canvas = surfaceView.holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(android.graphics.Color.parseColor("#0A0E1A"))
                surfaceView.holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun showEnableUsbDebuggingDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable USB Debugging")
            .setMessage("Zero-lag USB-C mode requires USB Debugging enabled in Developer Options on your tablet.\n\n1. Open Settings -> Developer options\n2. Turn ON 'USB debugging'\n3. Re-connect USB cable and tap Connect")
            .setPositiveButton("Open Settings") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveMacProfile(name: String, ip: String, port: Int, pin: String) {
        if (ip == "127.0.0.1" || ip == "localhost" || ip.isEmpty()) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SAVED_MAC_NAME, name)
            .putString(KEY_SAVED_MAC_IP, ip)
            .putInt(KEY_SAVED_MAC_PORT, port)
            .putString(KEY_SAVED_MAC_PIN, pin)
            .apply()
        runOnUiThread {
            cursorOverlay.savedMacName = name
            cursorOverlay.savedMacIp = ip
            cursorOverlay.savedMacPort = port
            cursorOverlay.savedMacPin = pin
            cursorOverlay.isSavedMacOnline = discoveredMacs.containsKey(ip)
            cursorOverlay.postInvalidateOnAnimation()
        }
    }

    private fun clearSavedMacProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        disconnectFromHost()
        cursorOverlay.savedMacName = null
        cursorOverlay.savedMacIp = null
        cursorOverlay.savedMacPort = 9453
        cursorOverlay.savedMacPin = ""
        cursorOverlay.isSavedMacOnline = false
        cursorOverlay.updateStatus("Mac profile forgotten • Ready to Connect", streaming = false)
        cursorOverlay.postInvalidateOnAnimation()
    }

    private fun loadSavedMacProfile() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ip = prefs.getString(KEY_SAVED_MAC_IP, null)
        if (!ip.isNullOrEmpty()) {
            val name = prefs.getString(KEY_SAVED_MAC_NAME, "HyperScreen-Mac")
            val port = prefs.getInt(KEY_SAVED_MAC_PORT, 9453)
            val pin = prefs.getString(KEY_SAVED_MAC_PIN, "") ?: ""
            cursorOverlay.savedMacName = name
            cursorOverlay.savedMacIp = ip
            cursorOverlay.savedMacPort = port
            cursorOverlay.savedMacPin = pin
            cursorOverlay.isSavedMacOnline = discoveredMacs.containsKey(ip)
            cursorOverlay.postInvalidateOnAnimation()
        }
    }

    data class DiscoveredMacInfo(
        val ip: String,
        val port: Int,
        val name: String,
        val pin: String
    )
    private val discoveredMacs = java.util.concurrent.ConcurrentHashMap<String, DiscoveredMacInfo>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED == intent.action ||
                "dev.hyperscreen.USB_PERMISSION" == intent.action
            ) {
                isUserDisconnected = false
                if (::cursorOverlay.isInitialized) {
                    cursorOverlay.isDisconnectedByUser = false
                    cursorOverlay.activeTransportMode = TransportMode.USB
                }
                activeTransportMode = TransportMode.USB
                preferredHostIp = "127.0.0.1"
                runCatching { activeStreamSocket.get()?.close() }
            }
        }
    }

    private fun getDeviceRefreshRate(): Int {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.mode?.refreshRate?.toInt() ?: 90
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.refreshRate?.toInt() ?: 90
            }
        }.getOrDefault(90).coerceIn(60, 144)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent tablet screen timeout and lockscreen activation while HyperScreen Pro is open
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Allow tablet to rotate completely freely to all landscape & portrait orientations before connection
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val root = FrameLayout(this).apply {
            keepScreenOn = true
        }
        surfaceView = SurfaceView(this).also {
            it.keepScreenOn = true
            it.holder.addCallback(this)
        }
        cursorOverlay = HardwareCursorAndModifierOverlayView(
            context = this,
            onConnectUsbClicked = {
                if (!isUsbDebuggingEnabled()) {
                    showEnableUsbDebuggingDialog()
                    return@HardwareCursorAndModifierOverlayView
                }
                activeTransportMode = TransportMode.USB
                cursorOverlay.activeTransportMode = TransportMode.USB
                isUserDisconnected = false
                cursorOverlay.isDisconnectedByUser = false
                cursorOverlay.isConnecting = true
                cursorOverlay.isConnectionError = false
                cursorOverlay.updateStatus("Connecting via USB-C...", streaming = false)
                connectFromQrUriOrRawText("127.0.0.1:9453")
            },
            onConnectWifiClicked = {
                activeTransportMode = TransportMode.WIFI
                cursorOverlay.activeTransportMode = TransportMode.WIFI
                isUserDisconnected = false
                cursorOverlay.isDisconnectedByUser = false
                cursorOverlay.isConnecting = true
                cursorOverlay.isConnectionError = false
                val savedIp = cursorOverlay.savedMacIp
                if (savedIp != null) {
                    val target = "$savedIp:${cursorOverlay.savedMacPort}"
                    cursorOverlay.updateStatus("Connecting via Wi-Fi ($target)...", streaming = false)
                    connectFromQrUriOrRawText(target)
                } else if (discoveredMacs.size > 1) {
                    showSelectMacHostDialog()
                } else {
                    val target = "$discoveredLanHostIp:$preferredHostPort"
                    cursorOverlay.updateStatus("Connecting via Wi-Fi ($target)...", streaming = false)
                    connectFromQrUriOrRawText(target)
                }
            },
            onEnterPinClicked = {
                showPairingPinDialog()
            },
            onScanQrClicked = {
                showManualMacIpDialog()
            },
            onDisconnectClicked = {
                disconnectFromHost()
            },
            onSelectTransportMode = { mode ->
                if (isActiveSession.get() || cursorOverlay.isConnecting) {
                    cancelConnectionAttempt()
                }
                cursorOverlay.isConnectionError = false
                activeTransportMode = mode
                cursorOverlay.activeTransportMode = mode
                if (mode == TransportMode.USB) {
                    preferredHostIp = "127.0.0.1"
                    cursorOverlay.updateStatus("USB-C Selected • Zero-Lag Tunnel", streaming = false)
                    performUsbPreFlightCheck()
                } else {
                    preferredHostIp = cursorOverlay.savedMacIp ?: discoveredLanHostIp
                    cursorOverlay.updateStatus("Wireless LAN Selected • Ready to Connect", streaming = false)
                }
            },
            onCancelConnectionClicked = {
                cancelConnectionAttempt()
            },
            onForgetMacClicked = {
                clearSavedMacProfile()
            },
            onDismissErrorClicked = {
                cancelConnectionAttempt()
            }
        ).also {
            it.keepScreenOn = true
            it.isDisconnectedByUser = true
            it.hasReceivedVideoFrame = false
            it.connectionStatusText = "Ready to Connect (Select USB-C or Wi-Fi)"
            it.discoveredLanIpLabel = discoveredLanHostIp
            val vName = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "1.0.1"
            it.appVersion = vName
        }

        loadSavedMacProfile()
        performUsbPreFlightCheck()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedThemeStr = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        val initialThemeMode = runCatching { ThemeMode.valueOf(savedThemeStr ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
        cursorOverlay.setThemeMode(initialThemeMode)
        cursorOverlay.onThemeChangedListener = { mode ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("theme_mode", mode.name)
                .apply()
            enableImmersiveFullscreen()
        }

        // Hide Android system mouse pointer during display sessions so physical tablet mice/trackpads
        // do not render a duplicate Android arrow on top of the native macOS cursor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val nullPointer = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
            root.pointerIcon = nullPointer
            surfaceView.pointerIcon = nullPointer
            cursorOverlay.pointerIcon = nullPointer
        }

        val inputHandler = UnbufferedInputHandler(
            targetView = cursorOverlay,
            overlay = cursorOverlay,
            onStylusPacket = { action, buttons, mods, nx, ny, pressure, tx, ty ->
                dispatchStylusToActiveTransport(action, buttons, mods, nx, ny, pressure, tx, ty)
            },
            onTouchPacket = { mode, action, ptrs, mods, x, y, sdx, sdy ->
                dispatchTouchToActiveTransport(mode, action, ptrs, mods, x, y, sdx, sdy)
            }
        )
        cursorOverlay.setOnTouchListener(inputHandler)
        cursorOverlay.setOnGenericMotionListener(inputHandler)
        surfaceView.setOnTouchListener(inputHandler)
        surfaceView.setOnGenericMotionListener(inputHandler)

        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            cursorOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        enableImmersiveFullscreen()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction("dev.hyperscreen.USB_PERMISSION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (::cursorOverlay.isInitialized && !cursorOverlay.palette.isDark) {
                    controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                } else {
                    controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isBackgrounded.set(false)
        if (wasStreamingBeforeBackground && !isUserDisconnected) {
            val targetIp = resumeTargetIp ?: preferredHostIp
            val targetPort = resumeTargetPort
            if (isSurfaceAlive.get()) {
                wasStreamingBeforeBackground = false
                ensureDecoderActive()
                connectFromQrUriOrRawText("$targetIp:$targetPort")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        enableImmersiveFullscreen()
        if (!cursorOverlay.hasReceivedVideoFrame) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        usbCheckHandler.removeCallbacks(usbCheckRunnable)
        usbCheckHandler.post(usbCheckRunnable)
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HyperScreen::DisplayWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::cursorOverlay.isInitialized) {
            cursorOverlay.onSystemConfigurationChanged(newConfig)
        }
        enableImmersiveFullscreen()
        cursorOverlay.postInvalidateOnAnimation()
    }

    override fun onPause() {
        usbCheckHandler.removeCallbacks(usbCheckRunnable)
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        isBackgrounded.set(true)
        if (!isUserDisconnected && (isActiveSession.get() || cursorOverlay.hasReceivedVideoFrame || cursorOverlay.isConnecting)) {
            wasStreamingBeforeBackground = true
            resumeTargetIp = preferredHostIp
            resumeTargetPort = preferredHostPort

            // Cleanly pause streaming to preserve tablet battery & prevent background decode exceptions
            runCatching { activeStreamSocket.getAndSet(null)?.close() }
            runCatching { activeUdpSocket.getAndSet(null)?.close() }
            runCatching { activeTcpOut.getAndSet(null)?.close() }
            activeUdpTarget.set(null)
            runCatching { decoder?.stop() }
            decoder = null
            isActiveSession.set(false)
            cursorOverlay.hasReceivedVideoFrame = false
            cursorOverlay.isConnecting = false
            clearVideoSurface()
        }
    }

    private fun cancelConnectionAttempt() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        isUserDisconnected = true
        isMacDisplayStandby = false
        wasStreamingBeforeBackground = false
        resumeTargetIp = null
        isActiveSession.set(false)
        hasSavedCurrentProfile = false
        cursorOverlay.isConnecting = false
        cursorOverlay.isConnectionError = false
        cursorOverlay.hasReceivedVideoFrame = false
        clearVideoSurface()
        runCatching { activeStreamSocket.getAndSet(null)?.close() }
        runCatching { activeUdpSocket.getAndSet(null)?.close() }
        runCatching { activeTcpOut.getAndSet(null)?.close() }
        activeUdpTarget.set(null)
        val modeLabel = if (activeTransportMode == TransportMode.USB) "USB-C" else "Wireless LAN"
        cursorOverlay.updateStatus("Connection cancelled • $modeLabel Ready", streaming = false)
    }

    private fun disconnectFromHost() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        isUserDisconnected = true
        isMacDisplayStandby = false
        wasStreamingBeforeBackground = false
        resumeTargetIp = null
        isActiveSession.set(false)
        hasSavedCurrentProfile = false
        cursorOverlay.isConnecting = false
        cursorOverlay.isConnectionError = false
        cursorOverlay.isDisconnectedByUser = true
        cursorOverlay.hasReceivedVideoFrame = false
        val modeLabel = if (activeTransportMode == TransportMode.USB) "USB-C" else "Wireless LAN"
        cursorOverlay.updateStatus("Disconnected • $modeLabel Ready", streaming = false)
        clearVideoSurface()

        // Note: Do NOT set surfaceView.visibility = INVISIBLE because cursorOverlay renders a full
        // dark background (#0A0E1A) in onDraw() while keeping the underlying Surface and touch pipeline active!
        runCatching { decoder?.stop() }
        decoder = null

        runCatching { activeTcpOut.getAndSet(null)?.close() }
        runCatching { activeStreamSocket.getAndSet(null)?.close() }
        runCatching { activeUdpSocket.getAndSet(null)?.close() }
        activeUdpTarget.set(null)
    }

    private fun showPairingPinDialog() {
        val density = resources.displayMetrics.density
        val p = cursorOverlay.palette
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * density).toInt(), (18 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.cardBg)
                cornerRadius = 24 * density
                setStroke((1.5f * density).toInt(), p.cardBorder)
            }
        }

        val hint = TextView(this).apply {
            text = "Enter the 8-digit Pairing PIN shown on your Mac (e.g. 1234-5678):"
            setTextColor(p.muted)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(hint)

        val pinInput = EditText(this).apply {
            setHint("••••-••••")
            inputType = InputType.TYPE_CLASS_PHONE
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789-")
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.25f
            setTextColor(p.subtitle)
            setHintTextColor(p.caption)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = 14 * density
                setStroke((1.5f * density).toInt(), p.panelBorder)
            }
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            filters = arrayOf(android.text.InputFilter.LengthFilter(9))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        }

        var isFormatting = false
        pinInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true

                val digits = s.filter { it.isDigit() }.take(8)
                val formatted = if (digits.length > 4) {
                    "${digits.substring(0, 4)}-${digits.substring(4)}"
                } else {
                    digits.toString()
                }

                if (formatted != s.toString()) {
                    s.replace(0, s.length, formatted)
                }
                val curLen = pinInput.text?.length ?: 0
                pinInput.setSelection(curLen)

                isFormatting = false
            }
        })
        container.addView(pinInput)

        val targetName = cursorOverlay.discoveredMacName.ifEmpty { "Mac" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Pairing PIN ($targetName)")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val rawDigits = pinInput.text.toString().filter { it.isDigit() }
                if (rawDigits.length < 8) {
                    cursorOverlay.updateStatus("PIN must be 8 digits (e.g. 1234-5678)", streaming = false)
                    return@setPositiveButton
                }
                activeTransportMode = TransportMode.WIFI
                cursorOverlay.activeTransportMode = TransportMode.WIFI
                isUserDisconnected = false
                cursorOverlay.isDisconnectedByUser = false
                cursorOverlay.isConnecting = true

                val formattedPin = "${rawDigits.substring(0, 4)}-${rawDigits.substring(4)}"
                val macName = cursorOverlay.discoveredMacName.ifEmpty { "HyperScreen-Mac" }
                saveMacProfile(macName, discoveredLanHostIp, preferredHostPort, formattedPin)
                cursorOverlay.updateStatus("Connecting to $macName with PIN $formattedPin...", streaming = false)
                connectFromQrUriOrRawText("$discoveredLanHostIp:$preferredHostPort")
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.primaryBtnBg)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.muted)
        }
        dialog.show()
    }

    private fun showSelectMacHostDialog() {
        val macs = discoveredMacs.values.toList()
        if (macs.isEmpty()) {
            showPairingPinDialog()
            return
        }
        val options = macs.map { "${it.name} (${it.ip})" }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Mac Host on Wi-Fi")
            .setItems(options) { _, which ->
                val selected = macs[which]
                discoveredLanHostIp = selected.ip
                cursorOverlay.discoveredLanIpLabel = selected.ip
                cursorOverlay.discoveredMacName = selected.name
                preferredHostPort = selected.port
                showPairingPinDialog()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cursorOverlay.palette.muted)
        }
        dialog.show()
    }

    fun onDisplayReconfigured(newW: Int, newH: Int, rotation: Int) {
        currentRotationAngle = rotation
        runOnUiThread {
            cursorOverlay.isConnecting = false
            Log.i("HyperScreen", "Display reconfigured: ${newW}x${newH}, rotation=$rotation°")
            val targetOrientation = when (rotation) {
                90 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (requestedOrientation != targetOrientation) {
                requestedOrientation = targetOrientation
            }
            surfaceView.holder.setFixedSize(newW, newH)
            decoder?.reconfigureDimensions(newW, newH)
            val modeLabel = if (activeTransportMode == TransportMode.USB) "USB-C" else "Wi-Fi"
            cursorOverlay.updateStatus("$modeLabel • ${newW}x${newH} (${rotation}°)", streaming = true)
        }
    }

    private fun ensureDecoderActive() {
        if (decoder == null && isSurfaceAlive.get()) {
            val holder = surfaceView.holder
            if (holder.surface?.isValid == true) {
                val dec = LowLatencyMediaCodec(
                    surface = holder.surface,
                    width = 1920,
                    height = 1200,
                    refreshRateHz = getDeviceRefreshRate()
                )
                dec.start()
                decoder = dec
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isSurfaceAlive.get()) {
            checkQrDeepLinkIntent(intent)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceAlive.set(true)
        ensureDecoderActive()

        // 1. Check QR code deep link (`hyperscreen://pair?ip=...&port=9453`)
        checkQrDeepLinkIntent(intent)

        // 2. Start unified USB-C (`127.0.0.1:9453`) + Wireless LAN (`192.168.x.x:9453`) stream engine
        startUnifiedStreamConnectionLoop()

        // 3. Start Wireless Bonjour (`_hyperscreen._udp`) discovery
        startWirelessBonjourAndUdpFec()

        // 4. Resume stream if activity was backgrounded during an active session
        if (wasStreamingBeforeBackground && !isUserDisconnected) {
            wasStreamingBeforeBackground = false
            val targetIp = resumeTargetIp ?: preferredHostIp
            val targetPort = resumeTargetPort
            connectFromQrUriOrRawText("$targetIp:$targetPort")
        }
    }

    private fun checkQrDeepLinkIntent(incomingIntent: Intent?) {
        val data = incomingIntent?.data ?: return
        connectFromQrUriOrRawText(data.toString())
    }

    private fun connectFromQrUriOrRawText(rawText: String) {
        val trimmed = rawText.trim()
        var targetIp = trimmed
        var targetPort = 9453

        if (trimmed.startsWith("hyperscreen://")) {
            val uri = Uri.parse(trimmed)
            targetIp = uri.getQueryParameter("ip") ?: return
            targetPort = uri.getQueryParameter("port")?.toIntOrNull() ?: 9453
        } else if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            targetIp = parts[0]
            targetPort = parts.getOrNull(1)?.toIntOrNull() ?: 9453
        }

        if (targetIp != "127.0.0.1") {
            activeTransportMode = TransportMode.WIFI
            cursorOverlay.activeTransportMode = TransportMode.WIFI
            discoveredLanHostIp = targetIp
            cursorOverlay.discoveredLanIpLabel = targetIp
        } else {
            activeTransportMode = TransportMode.USB
            cursorOverlay.activeTransportMode = TransportMode.USB
        }
        isUserDisconnected = false
        cursorOverlay.isDisconnectedByUser = false
        cursorOverlay.isConnecting = true
        preferredHostIp = targetIp
        preferredHostPort = targetPort
        cursorOverlay.hasReceivedVideoFrame = false
        ensureDecoderActive()
        val label = if (targetIp == "127.0.0.1") "USB-C Cable" else "$targetIp:$targetPort"
        cursorOverlay.updateStatus("Connecting via $label...", streaming = false)

        ensureStreamLoopRunning()

        // Interrupt any active socket so startUnifiedStreamConnectionLoop immediately connects to targetIp:targetPort
        runCatching { activeStreamSocket.get()?.close() }

        if (targetIp != "127.0.0.1") {
            Thread {
                runCatching {
                    val addr = InetAddress.getByName(targetIp)
                    startUdpFecReceiverThread(addr, targetPort)
                }
            }.start()
        } else {
            // Close any existing Wi-Fi UDP socket when explicitly selecting USB-C
            runCatching { activeUdpSocket.getAndSet(null)?.close() }
            activeUdpTarget.set(null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            showBuiltInQrScannerAndIpDialog()
        }
    }

    private fun showManualMacIpDialog() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
            return
        }
        showBuiltInQrScannerAndIpDialog()
    }

    @Suppress("DEPRECATION")
    private fun configureCameraTransform(
        textureView: TextureView,
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int = 640,
        previewHeight: Int = 480
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / previewHeight.toFloat(),
                viewWidth.toFloat() / previewWidth.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else {
            val scaleX = viewWidth.toFloat() / previewWidth.toFloat()
            val scaleY = viewHeight.toFloat() / previewHeight.toFloat()
            val maxScale = maxOf(scaleX, scaleY)
            matrix.setScale(maxScale / scaleX, maxScale / scaleY, centerX, centerY)
            if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    @SuppressLint("MissingPermission")
    private fun showBuiltInQrScannerAndIpDialog() {
        val density = resources.displayMetrics.density
        val p = cursorOverlay.palette
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.cardBg)
                cornerRadius = 24 * density
                setStroke((1.5f * density).toInt(), p.cardBorder)
            }
        }

        val statusHint = TextView(this).apply {
            text = "Point camera at the macOS HyperScreen QR Code, or enter Mac IP below:"
            setTextColor(p.muted)
            textSize = 13.5f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(statusHint)

        // Square 230dp x 230dp camera viewfinder so 4:3 sensor feed is never stretched across landscape dialog
        val squarePx = (230 * density).toInt()
        val previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(squarePx, squarePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (10 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = 16 * density
                setStroke((1.5f * density).toInt(), p.panelBorder)
            }
            clipToOutline = true
        }

        val textureView = TextureView(this)
        previewFrame.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Cyan/Azure Targeting Reticle Overlay inside the Square Camera Viewfinder
        val reticleView = object : View(this) {
            private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = p.subtitle
                style = Paint.Style.STROKE
                strokeWidth = 4.5f
            }
            override fun onDraw(c: Canvas) {
                super.onDraw(c)
                val boxSize = minOf(width, height) * 0.72f
                val left = (width - boxSize) / 2f
                val top = (height - boxSize) / 2f
                c.drawRoundRect(left, top, left + boxSize, top + boxSize, 24f, 24f, reticlePaint)
            }
        }
        previewFrame.addView(
            reticleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val flipCamBtn = Button(this).apply {
            text = "Flip"
            textSize = 11f
            setTextColor(p.secondaryBtnText)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.secondaryBtnBg)
                cornerRadius = 12 * density
                setStroke((1f * density).toInt(), p.secondaryBtnBorder)
            }
        }
        previewFrame.addView(
            flipCamBtn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (6 * density).toInt()
                rightMargin = (6 * density).toInt()
            }
        )
        container.addView(previewFrame)

        val ipInput = EditText(this).apply {
            hint = "Mac IP (e.g. 192.168.18.9)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(discoveredLanHostIp)
            setTextColor(p.body)
            setHintTextColor(p.caption)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = 12 * density
                setStroke((1.5f * density).toInt(), p.panelBorder)
            }
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }
        container.addView(
            ipInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
        )

        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var imageReader: ImageReader? = null
        var bgThread: HandlerThread? = null
        var bgHandler: Handler? = null
        var useFrontCamera = false
        val qrDecodedOnce = AtomicBoolean(false)
        val qrReader = QRCodeReader()
        val qrHints = mapOf(
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )

        fun stopCamera() {
            runCatching { captureSession?.close() }
            captureSession = null
            runCatching { cameraDevice?.close() }
            cameraDevice = null
            runCatching { imageReader?.close() }
            imageReader = null
            runCatching { bgThread?.quitSafely() }
            bgThread = null
            bgHandler = null
        }

        lateinit var dialog: AlertDialog

        fun startCameraPreview() {
            stopCamera()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                statusHint.text = "Camera permission not granted — enter Mac IP below:"
                return
            }
            val st = textureView.surfaceTexture ?: return
            st.setDefaultBufferSize(640, 480)
            configureCameraTransform(textureView, textureView.width, textureView.height, 640, 480)
            val previewSurface = Surface(st)

            val thread = HandlerThread("HyperScreen-QR-Cam").also { it.start() }
            val handler = Handler(thread.looper)
            bgThread = thread
            bgHandler = handler

            val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (qrDecodedOnce.get()) return@setOnImageAvailableListener
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val rowStride = plane.rowStride
                    val w = img.width
                    val h = img.height
                    val yBytes = ByteArray(rowStride * h)
                    buf.get(yBytes, 0, minOf(yBytes.size, buf.remaining()))

                    val source = PlanarYUVLuminanceSource(yBytes, rowStride, h, 0, 0, w, h, false)
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    var result = runCatching { qrReader.decode(bitmap, qrHints) }.getOrNull()
                    if (result == null && source.isRotateSupported) {
                        val rotated = BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise()))
                        result = runCatching { qrReader.decode(rotated, qrHints) }.getOrNull()
                    }
                    if (result != null && !result.text.isNullOrBlank()) {
                        if (qrDecodedOnce.compareAndSet(false, true)) {
                            val scannedText = result.text
                            runOnUiThread {
                                stopCamera()
                                runCatching { dialog.dismiss() }
                                connectFromQrUriOrRawText(scannedText)
                            }
                        }
                    }
                } finally {
                    img.close()
                }
            }, handler)

            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val desiredFacing = if (useFrontCamera) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val camId = camManager.cameraIdList.firstOrNull { id ->
                camManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desiredFacing
            } ?: camManager.cameraIdList.firstOrNull() ?: return

            runCatching {
                camManager.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        cameraDevice = cam
                        val targets = listOf(previewSurface, reader.surface)
                        runCatching {
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                addTarget(reader.surface)
                            }
                            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    runCatching {
                                        session.setRepeatingRequest(req.build(), null, handler)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {}
                            }, handler)
                        }
                    }
                    override fun onDisconnected(cam: CameraDevice) {
                        cam.close()
                    }
                    override fun onError(cam: CameraDevice, error: Int) {
                        cam.close()
                    }
                }, handler)
            }
        }

        flipCamBtn.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCameraPreview()
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                configureCameraTransform(textureView, width, height, 640, 480)
                startCameraPreview()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configureCameraTransform(textureView, width, height, 640, 480)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Scan Mac QR Code or Connect IP")
            .setView(container)
            .setPositiveButton("Connect IP") { _, _ ->
                stopCamera()
                val hostStr = ipInput.text.toString().trim().ifEmpty { discoveredLanHostIp }
                connectFromQrUriOrRawText(hostStr)
            }
            .setNeutralButton("USB-C (127.0.0.1)") { _, _ ->
                stopCamera()
                connectFromQrUriOrRawText("127.0.0.1")
            }
            .setNegativeButton("Cancel") { _, _ ->
                stopCamera()
            }
            .setOnDismissListener {
                stopCamera()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.primaryBtnBg)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(p.subtitle)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.muted)
        }

        dialog.show()
    }

    /**
     * Unified Low-Latency Stream Engine supporting both:
     * 1. USB-C High-Speed Tunnel (`127.0.0.1:9453` via macOS `adb reverse tcp:9453 tcp:9453`)
     * 2. Wireless LAN Direct (`192.168.x.x:9453` via QR Code / Bonjour / Manual IP)
     */
    @Synchronized
    private fun ensureStreamLoopRunning() {
        if (streamThread?.isAlive == true) return
        val t = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val reassembler = NativeCoreBridge.nativeCreateReassembler(512, 16400)
            val directPktBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            val directOutFrameBuf = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val frameMeta = LongArray(3)
            val hdrBuf = ByteArray(16)
            val payloadBuf = ByteArray(65536)
            var lastDecodedFrameId = 0

            while (isAppRunning.get()) {
                val targetHost = preferredHostIp
                val targetPort = preferredHostPort
                val isUsbLoopback = (targetHost == "127.0.0.1" || targetHost == "localhost")

                if (isBackgrounded.get() || isUserDisconnected || (activeTransportMode != TransportMode.USB && isUsbLoopback)) {
                    Thread.sleep(300)
                    continue
                }

                val connectedCleanly = runCatching {
                    Socket().use { sock ->
                        activeStreamSocket.set(sock)
                        sock.tcpNoDelay = true
                        sock.receiveBufferSize = 4 * 1024 * 1024
                        sock.soTimeout = 1500
                        sock.connect(InetSocketAddress(targetHost, targetPort), 900)

                        val out = sock.getOutputStream()
                        val inp = sock.getInputStream()
                        activeTcpOut.set(out)
                        isActiveSession.set(true)

                        if (!cursorOverlay.hasReceivedVideoFrame) {
                            val label = if (isUsbLoopback) "USB-C" else targetHost
                            cursorOverlay.updateStatus("Connected ($label) • Checking Virtual Display...", streaming = false)
                        }

                        val panelHz = getDeviceRefreshRate()
                        val pinToken = if (isUsbLoopback) 0L else (cursorOverlay.savedMacPin.filter { it.isDigit() }.toLongOrNull() ?: 0L)
                        // Send 1920x1200 HELLO matching active panel refresh rate with authenticated pairing PIN token
                        val helloBytes = NativeCoreBridge.nativeBuildClientHelloPacket(
                            outSequence.getAndIncrement(),
                            1920,
                            1200,
                            panelHz,
                            pinToken,
                            Build.MODEL ?: "Android Tablet"
                        )
                        synchronized(out) {
                            out.write(helloBytes)
                            out.flush()
                        }

                        while (isAppRunning.get() && !isBackgrounded.get() && !isUserDisconnected && !sock.isClosed && preferredHostIp == targetHost) {
                            var readHdr = 0
                            val hdrSuccess = runCatching {
                                while (readHdr < 16) {
                                    val r = inp.read(hdrBuf, readHdr, 16 - readHdr)
                                    if (r < 0) throw java.io.EOFException()
                                    readHdr += r
                                }
                                true
                            }.getOrElse { ex ->
                                if (ex is java.net.SocketTimeoutException && readHdr == 0) {
                                    // Re-send CLIENT_HELLO on timeout only when actively connecting and not in standby
                                    if (!isMacDisplayStandby && cursorOverlay.isConnecting) {
                                        synchronized(out) {
                                            out.write(helloBytes)
                                            out.flush()
                                        }
                                    }
                                    false
                                } else {
                                    throw ex
                                }
                            }
                            if (!hdrSuccess) continue

                            val magic = ((hdrBuf[1].toInt() and 0xFF) shl 8) or (hdrBuf[0].toInt() and 0xFF)
                            if (magic != 0x5348) break
                            val pktType = hdrBuf[3].toInt() and 0xFF
                            val payloadLen = (hdrBuf[8].toInt() and 0xFF) or
                                ((hdrBuf[9].toInt() and 0xFF) shl 8) or
                                ((hdrBuf[10].toInt() and 0xFF) shl 16) or
                                ((hdrBuf[11].toInt() and 0xFF) shl 24)

                            if (payloadLen < 0 || payloadLen > payloadBuf.size) break

                            var readPayload = 0
                            while (readPayload < payloadLen) {
                                val r = inp.read(payloadBuf, readPayload, payloadLen - readPayload)
                                if (r < 0) throw java.io.EOFException()
                                readPayload += r
                            }

                            if (pktType == 0x02 /* HS_PKT_VIDEO_SLICE */ || pktType == 0x03 /* HS_PKT_VIDEO_FEC */) {
                                directPktBuf.clear()
                                directPktBuf.put(hdrBuf, 0, 16)
                                directPktBuf.put(payloadBuf, 0, payloadLen)

                                directOutFrameBuf.clear()
                                val frameBytes = NativeCoreBridge.nativePushUdpPacketToReassembler(
                                    reassembler,
                                    directPktBuf,
                                    16 + payloadLen,
                                    directOutFrameBuf,
                                    frameMeta
                                )
                                if (frameBytes > 0) {
                                    lastDecodedFrameId = frameMeta[0].toInt()
                                    cursorOverlay.isConnecting = false
                                    cursorOverlay.isConnectionError = false
                                    if (!cursorOverlay.hasReceivedVideoFrame) {
                                        val activeBanner = if (isUsbLoopback) {
                                            "USB-C • 1920x1200 @ ${panelHz}Hz HiDPI"
                                        } else {
                                            "Wi-Fi • 1920x1200 @ ${panelHz}Hz ($targetHost)"
                                        }
                                        cursorOverlay.updateStatus(activeBanner, streaming = true)
                                        if (!isUsbLoopback && !hasSavedCurrentProfile) {
                                            hasSavedCurrentProfile = true
                                            val mName = cursorOverlay.discoveredMacName.ifEmpty { "HyperScreen-Mac" }
                                            saveMacProfile(mName, targetHost, targetPort, cursorOverlay.discoveredMacPin)
                                        }
                                    }
                                    directOutFrameBuf.position(0)
                                    decoder?.submitAnnexBNaluDirect(directOutFrameBuf, frameBytes)
                                }
                            } else if (pktType == 0x09 /* HS_PKT_PONG_TELEMETRY */) {
                                if (payloadLen >= 16) {
                                    directPktBuf.clear()
                                    directPktBuf.put(payloadBuf, 0, payloadLen)
                                    val hostTs = directPktBuf.getLong(0)
                                    val decLat = decoder?.lastDecodeLatencyUs ?: 4000
                                    val pingPacket = NativeCoreBridge.nativeBuildTelemetryPingPacket(
                                        outSequence.getAndIncrement(),
                                        hostTs,
                                        lastDecodedFrameId,
                                        0,
                                        decLat
                                    )
                                    if (pingPacket != null) {
                                        synchronized(out) {
                                            out.write(pingPacket)
                                            out.flush()
                                        }
                                    }
                                }
                            } else if (pktType == 0x01 /* HS_PKT_SERVER_HELLO */) {
                                if (payloadLen >= 32) {
                                    directPktBuf.clear()
                                    directPktBuf.put(payloadBuf, 0, payloadLen)
                                    val w = directPktBuf.getInt(0)
                                    val h = directPktBuf.getInt(4)
                                    val rawReserved = directPktBuf.getShort(18).toInt() and 0xFFFF
                                    val isAuthFailed = (rawReserved and 0x4000) != 0
                                    val isDisplayActive = (rawReserved and 0x8000) != 0 && w > 0 && h > 0
                                    val rot = rawReserved and 0x01FF
                                    if (isAuthFailed) {
                                        runOnUiThread {
                                            cursorOverlay.isConnecting = false
                                            cursorOverlay.isConnectionError = true
                                            cursorOverlay.errorTitle = "PIN Authentication Failed"
                                            cursorOverlay.errorDiagnostic = "Incorrect pairing PIN for $targetHost"
                                            cursorOverlay.errorHint = "Verify the 8-digit PIN displayed on your Mac menu bar"
                                            cursorOverlay.updateStatus("Authentication Failed", streaming = false)
                                            cancelConnectionAttempt()
                                        }
                                        break
                                    }
                                    if (!isUsbLoopback && !hasSavedCurrentProfile) {
                                        hasSavedCurrentProfile = true
                                        val mName = cursorOverlay.discoveredMacName.ifEmpty { "HyperScreen-Mac" }
                                        saveMacProfile(mName, targetHost, targetPort, cursorOverlay.discoveredMacPin)
                                    }
                                    if (isDisplayActive) {
                                        isMacDisplayStandby = false
                                        onDisplayReconfigured(w, h, rot)
                                    } else {
                                        isMacDisplayStandby = true
                                        runOnUiThread {
                                            cursorOverlay.hasReceivedVideoFrame = false
                                            cursorOverlay.isConnecting = false
                                            val label = if (isUsbLoopback) "USB-C" else "Wi-Fi"
                                            cursorOverlay.updateStatus("Mac Connected ($label) • Virtual Display Inactive on Mac", streaming = false)
                                            clearVideoSurface()
                                        }
                                    }
                                } else if (payloadLen == 2 && payloadBuf[0] == 0.toByte() && payloadBuf[1] == 0.toByte()) {
                                    runOnUiThread {
                                        disconnectFromHost()
                                    }
                                    break
                                }
                            } else if (pktType == 0x05 /* HS_PKT_CURSOR_STATE */ && payloadLen >= 25) {
                                directPktBuf.clear()
                                directPktBuf.put(payloadBuf, 0, payloadLen)
                                val tsUs = directPktBuf.getLong(0)
                                val nx = directPktBuf.getFloat(8)
                                val ny = directPktBuf.getFloat(12)
                                val vis = directPktBuf.get(24).toInt() != 0
                                cursorOverlay.updateCursorFromNetwork(tsUs, nx, ny, vis)
                            }
                        }
                        true
                    }
                }.getOrDefault(false)

                activeTcpOut.set(null)
                activeStreamSocket.set(null)

                if (isUserDisconnected) {
                    continue
                }

                if (!connectedCleanly) {
                    runOnUiThread {
                        cursorOverlay.isConnecting = false
                        if (isUsbLoopback && !cursorOverlay.hasReceivedVideoFrame) {
                            cursorOverlay.isConnectionError = true
                            cursorOverlay.errorTitle = "USB-C Connection Failed"
                            cursorOverlay.errorDiagnostic = "No response from Mac on 127.0.0.1:9453"
                            cursorOverlay.errorHint = "Check cable & verify 'adb reverse tcp:9453 tcp:9453'"
                            cursorOverlay.updateStatus("USB-C connection failed", streaming = false)
                        } else if (!isUsbLoopback && !cursorOverlay.hasReceivedVideoFrame) {
                            cursorOverlay.isConnectionError = true
                            cursorOverlay.errorTitle = "Wi-Fi Connection Failed"
                            cursorOverlay.errorDiagnostic = "Could not connect to $targetHost:$targetPort"
                            cursorOverlay.errorHint = "Ensure both Mac & tablet are on 5GHz/6GHz Wi-Fi"
                            cursorOverlay.updateStatus("Wi-Fi connection failed", streaming = false)
                        }
                    }
                    Thread.sleep(1200)
                } else {
                    Thread.sleep(600)
                }
            }
            NativeCoreBridge.nativeDestroyReassembler(reassembler)
        }, "HyperScreen-Stream-Client")
        streamThread = t
        t.start()
    }

    private fun startUnifiedStreamConnectionLoop() {
        ensureStreamLoopRunning()
    }

    @Suppress("DEPRECATION")
    private fun startWirelessBonjourAndUdpFec() {
        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {
                val sName = service.serviceName ?: ""
                val toRemove = discoveredMacs.filterValues { it.name == sName }.keys.toList()
                for (ip in toRemove) {
                    discoveredMacs.remove(ip)
                    if (cursorOverlay.savedMacIp == ip) {
                        runOnUiThread {
                            cursorOverlay.isSavedMacOnline = false
                            cursorOverlay.postInvalidateOnAnimation()
                        }
                    }
                }
                if (cursorOverlay.discoveredMacName == sName) {
                    val nextMac = discoveredMacs.values.firstOrNull()
                    if (nextMac != null) {
                        discoveredLanHostIp = nextMac.ip
                        cursorOverlay.discoveredLanIpLabel = nextMac.ip
                        cursorOverlay.discoveredMacName = nextMac.name
                    }
                    runOnUiThread { cursorOverlay.postInvalidateOnAnimation() }
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_hyperscreen._udp")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host ?: return
                            val hostIp = host.hostAddress ?: return
                            if (!hostIp.startsWith("127.") && !hostIp.contains(":")) {
                                discoveredLanHostIp = hostIp
                                cursorOverlay.discoveredLanIpLabel = hostIp
                                preferredHostPort = resolved.port
                                val sName = resolved.serviceName ?: "HyperScreen-Mac"
                                val attrs = resolved.attributes
                                val pin = attrs["pin"]?.let { String(it, Charsets.UTF_8) } ?: ""
                                discoveredMacs[hostIp] = DiscoveredMacInfo(hostIp, resolved.port, sName, pin)
                                cursorOverlay.discoveredMacName = sName
                                cursorOverlay.discoveredMacPin = pin
                                if (cursorOverlay.savedMacIp == hostIp) {
                                    cursorOverlay.isSavedMacOnline = true
                                }
                                cursorOverlay.postInvalidateOnAnimation()
                            }
                        }
                    })
                }
            }
        }
        runCatching {
            nsdManager.discoverServices("_hyperscreen._udp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    private fun startUdpFecReceiverThread(hostAddress: InetAddress, port: Int) {
        runCatching { activeUdpSocket.get()?.close() }
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val reassembler = NativeCoreBridge.nativeCreateReassembler(512, 16400)
            val pktBuf = ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN)
            val outFrameBuf = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val frameMeta = LongArray(3)
            val recvArray = ByteArray(2048)

            runCatching {
                DatagramSocket().use { socket ->
                    socket.receiveBufferSize = 8 * 1024 * 1024
                    socket.soTimeout = 1000
                    val target = InetSocketAddress(hostAddress, port)
                    activeUdpSocket.set(socket)
                    activeUdpTarget.set(target)
                    runCatching { socket.connect(target) }

                    val panelHz = getDeviceRefreshRate()
                    val pinToken = cursorOverlay.savedMacPin.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val helloBytes = NativeCoreBridge.nativeBuildClientHelloPacket(
                        outSequence.getAndIncrement(),
                        1920,
                        1200,
                        panelHz,
                        pinToken,
                        Build.MODEL ?: "Android Tablet"
                    )
                    socket.send(DatagramPacket(helloBytes, helloBytes.size, target))

                    val dg = DatagramPacket(recvArray, recvArray.size)
                    var udpTimeoutCount = 0
                    var lastSeenFrameId = 0
                    var lastDecodedFrameId = 0
                    var lastNackSentTimeMs = 0L
                    var recentLossCount = 0

                    while (isAppRunning.get() && !isBackgrounded.get() && !isUserDisconnected && !Thread.currentThread().isInterrupted && activeUdpSocket.get() === socket) {
                        val received = runCatching {
                            socket.receive(dg)
                            true
                        }.getOrDefault(false)

                        if (!received) {
                            if (!cursorOverlay.hasReceivedVideoFrame) {
                                udpTimeoutCount++
                                if (udpTimeoutCount >= 5 && cursorOverlay.isConnecting) {
                                    runOnUiThread {
                                        cursorOverlay.isConnecting = false
                                        cursorOverlay.isConnectionError = true
                                        cursorOverlay.errorTitle = "Wi-Fi Host Not Responding"
                                        cursorOverlay.errorDiagnostic = "Mac host at ${hostAddress.hostAddress} stopped responding"
                                        cursorOverlay.errorHint = "Scan Mac QR code or verify Wi-Fi network"
                                        cursorOverlay.updateStatus("Wi-Fi host not responding", streaming = false)
                                    }
                                }
                                if (!isMacDisplayStandby && cursorOverlay.isConnecting) {
                                    socket.send(DatagramPacket(helloBytes, helloBytes.size, target))
                                }
                            }
                            continue
                        } else {
                            udpTimeoutCount = 0
                        }

                        val len = dg.length
                        if (len < 16) continue

                        pktBuf.clear()
                        pktBuf.put(recvArray, 0, len)
                        val pktType = pktBuf.get(3).toInt() and 0xFF

                        if (pktType == 0x02 /* HS_PKT_VIDEO_SLICE */ || pktType == 0x03 /* HS_PKT_VIDEO_FEC */) {
                            if (pktType == 0x02 && len >= 16 + 12) {
                                val incomingFid = pktBuf.getInt(16)
                                if (lastSeenFrameId > 0 && incomingFid > lastSeenFrameId + 1) {
                                    recentLossCount += (incomingFid - lastSeenFrameId - 1)
                                    // Missing frame detected on Wi-Fi - emit rate-limited PLI NACK to trigger IDR (max 1 NACK per 100ms)
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastNackSentTimeMs >= 100L) {
                                        lastNackSentTimeMs = now
                                        val nack = NativeCoreBridge.nativeBuildNackPacket(
                                            outSequence.getAndIncrement(),
                                            incomingFid,
                                            0xFFFF,
                                            1,
                                            lastDecodedFrameId
                                        )
                                        if (nack != null) {
                                            runCatching { socket.send(DatagramPacket(nack, nack.size, target)) }
                                        }
                                    }
                                }
                                if (incomingFid > lastSeenFrameId) {
                                    lastSeenFrameId = incomingFid
                                }
                            }

                            outFrameBuf.clear()
                            val frameBytes = NativeCoreBridge.nativePushUdpPacketToReassembler(
                                reassembler,
                                pktBuf,
                                len,
                                outFrameBuf,
                                frameMeta
                            )
                            if (frameBytes > 0) {
                                val compFid = frameMeta[0].toInt()
                                lastDecodedFrameId = compFid
                                cursorOverlay.isConnecting = false
                                cursorOverlay.isConnectionError = false
                                if (!cursorOverlay.hasReceivedVideoFrame) {
                                    cursorOverlay.updateStatus("Wi-Fi • 1920x1200 @ ${panelHz}Hz (${hostAddress.hostAddress})", streaming = true)
                                    val hIp = hostAddress.hostAddress ?: ""
                                    if (hIp.isNotEmpty() && !hIp.startsWith("127.") && !hasSavedCurrentProfile) {
                                        hasSavedCurrentProfile = true
                                        val mName = cursorOverlay.discoveredMacName.ifEmpty { "HyperScreen-Mac" }
                                        saveMacProfile(mName, hIp, port, cursorOverlay.discoveredMacPin)
                                    }
                                }
                                outFrameBuf.position(0)
                                decoder?.submitAnnexBNaluDirect(outFrameBuf, frameBytes)
                            }
                        } else if (pktType == 0x09 /* HS_PKT_PONG_TELEMETRY */) {
                            if (len >= 16 + 16) {
                                val hostTs = pktBuf.getLong(16)
                                val decLat = decoder?.lastDecodeLatencyUs ?: 4000
                                val losses = recentLossCount
                                recentLossCount = 0
                                val pingPacket = NativeCoreBridge.nativeBuildTelemetryPingPacket(
                                    outSequence.getAndIncrement(),
                                    hostTs,
                                    lastDecodedFrameId,
                                    losses,
                                    decLat
                                )
                                if (pingPacket != null) {
                                    runCatching { socket.send(DatagramPacket(pingPacket, pingPacket.size, target)) }
                                }
                            }
                        } else if (pktType == 0x01 /* HS_PKT_SERVER_HELLO */) {
                            if (len >= 16 + 32) {
                                val w = pktBuf.getInt(16)
                                val h = pktBuf.getInt(20)
                                val rawReserved = pktBuf.getShort(34).toInt() and 0xFFFF
                                val isAuthFailed = (rawReserved and 0x4000) != 0
                                val isDisplayActive = (rawReserved and 0x8000) != 0 && w > 0 && h > 0
                                val rot = rawReserved and 0x01FF
                                if (isAuthFailed) {
                                    runOnUiThread {
                                        cursorOverlay.isConnecting = false
                                        cursorOverlay.isConnectionError = true
                                        cursorOverlay.errorTitle = "PIN Authentication Failed"
                                        cursorOverlay.errorDiagnostic = "Incorrect pairing PIN for ${hostAddress.hostAddress}"
                                        cursorOverlay.errorHint = "Verify the 8-digit PIN displayed on your Mac menu bar"
                                        cursorOverlay.updateStatus("Authentication Failed", streaming = false)
                                        cancelConnectionAttempt()
                                    }
                                    return@Thread
                                }
                                val hIp = hostAddress.hostAddress ?: ""
                                if (hIp.isNotEmpty() && !hIp.startsWith("127.") && !hasSavedCurrentProfile) {
                                    hasSavedCurrentProfile = true
                                    val mName = cursorOverlay.discoveredMacName.ifEmpty { "HyperScreen-Mac" }
                                    saveMacProfile(mName, hIp, port, cursorOverlay.discoveredMacPin)
                                }
                                if (isDisplayActive) {
                                    isMacDisplayStandby = false
                                    onDisplayReconfigured(w, h, rot)
                                } else {
                                    isMacDisplayStandby = true
                                    runOnUiThread {
                                        cursorOverlay.hasReceivedVideoFrame = false
                                        cursorOverlay.isConnecting = false
                                        cursorOverlay.updateStatus("Mac Connected (Wi-Fi) • Virtual Display Inactive on Mac", streaming = false)
                                        clearVideoSurface()
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    disconnectFromHost()
                                }
                            }
                        } else if (pktType == 0x05 /* HS_PKT_CURSOR_STATE */ && len >= 41) {
                            val tsUs = pktBuf.getLong(16)
                            val nx = pktBuf.getFloat(24)
                            val ny = pktBuf.getFloat(28)
                            val vis = pktBuf.get(40).toInt() != 0
                            cursorOverlay.updateCursorFromNetwork(tsUs, nx, ny, vis)
                        }
                    }
                }
            }
            NativeCoreBridge.nativeDestroyReassembler(reassembler)
        }, "HyperScreen-UDP-FEC-Receiver").start()
    }

    private fun dispatchStylusToActiveTransport(
        action: Int,
        buttons: Int,
        mods: Int,
        nx: Float,
        ny: Float,
        pressure: Float,
        tx: Float,
        ty: Float
    ) {
        usbTransport?.let {
            it.sendStylusEvent(action, buttons, mods, nx, ny, pressure, tx, ty)
            return
        }
        val pkt = NativeCoreBridge.nativeBuildStylusPacket(
            outSequence.getAndIncrement(),
            System.nanoTime() / 1_000L,
            action,
            buttons,
            mods,
            nx,
            ny,
            pressure,
            tx,
            ty
        )
        sendRawInputPacketAsync(pkt)
    }

    private fun dispatchTouchToActiveTransport(
        mode: Int,
        action: Int,
        ptrs: Int,
        mods: Int,
        x: Float,
        y: Float,
        sdx: Float,
        sdy: Float
    ) {
        usbTransport?.let {
            it.sendTouchEvent(mode, action, ptrs, mods, x, y, sdx, sdy)
            return
        }
        val pkt = NativeCoreBridge.nativeBuildTouchPacket(
            outSequence.getAndIncrement(),
            System.nanoTime() / 1_000L,
            mode,
            action,
            ptrs,
            mods,
            x,
            y,
            sdx,
            sdy,
            1.0f
        )
        sendRawInputPacketAsync(pkt)
    }

    private val inputEventQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
    @Volatile private var inputWorkerThread: Thread? = null

    private fun ensureInputWorkerRunning() {
        if (inputWorkerThread?.isAlive == true) return
        val t = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (isAppRunning.get()) {
                val pkt = runCatching { inputEventQueue.take() }.getOrNull() ?: break
                val tcpOut = activeTcpOut.get()
                if (tcpOut != null) {
                    runCatching {
                        synchronized(tcpOut) {
                            tcpOut.write(pkt)
                            tcpOut.flush()
                        }
                    }
                    continue
                }
                val udpSock = activeUdpSocket.get()
                val udpTarget = activeUdpTarget.get()
                if (udpSock != null && udpTarget != null) {
                    runCatching {
                        udpSock.send(DatagramPacket(pkt, pkt.size, udpTarget))
                    }
                }
            }
        }, "HyperScreen-Input-Writer")
        t.isDaemon = true
        inputWorkerThread = t
        t.start()
    }

    private fun sendRawInputPacketAsync(pkt: ByteArray) {
        ensureInputWorkerRunning()
        if (!inputEventQueue.offer(pkt)) {
            // Drop oldest motion event when saturated to maintain low-latency
            inputEventQueue.poll()
            inputEventQueue.offer(pkt)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceAlive.set(false)
        usbTransport?.close()
        usbTransport = null
        decoder?.stop()
        decoder = null
    }

    override fun onDestroy() {
        isAppRunning.set(false)
        inputEventQueue.clear()
        inputWorkerThread?.interrupt()
        inputWorkerThread = null
        runCatching { activeStreamSocket.get()?.close() }
        runCatching { activeUdpSocket.get()?.close() }
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }
}
