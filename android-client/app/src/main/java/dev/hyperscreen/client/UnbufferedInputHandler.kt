package dev.hyperscreen.client

import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor

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
