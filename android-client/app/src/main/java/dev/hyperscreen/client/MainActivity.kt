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
