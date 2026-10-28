package dev.hyperscreen.client

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.concurrent.atomic.AtomicInteger

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
