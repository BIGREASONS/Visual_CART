package com.paypal.visualcart.service.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.paypal.visualcart.feature.circle.CircleGestureAnalyzer
import com.paypal.visualcart.feature.circle.GestureAnalysisResult
import com.paypal.visualcart.feature.circle.NormalizedRectF
import com.paypal.visualcart.feature.circle.TouchPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen transparent WindowManager overlay for Circle to Shop selection.
 * Uses pure Android Views and Canvas drawing so it attaches safely from a Service
 * without Compose lifecycle or AppCompat theme dependencies.
 */
class CircleSelectionOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var visibleState: Boolean = false
    val isVisible: Boolean
        get() = visibleState

    private var rootView: FrameLayout? = null
    private var canvasView: SelectionCanvasView? = null
    private var headerDotView: View? = null
    private var feedbackBanner: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pulseAnimator: ValueAnimator? = null

    private var onSelectionReadyCallback: (
        (
            bounds: NormalizedRectF,
            overlayWidth: Int,
            overlayHeight: Int,
            offsetX: Int,
            offsetY: Int
        ) -> Unit
    )? = null
    private var onInvalidSelectionCallback: ((String) -> Unit)? = null
    private var onClearCallback: (() -> Unit)? = null
    private var onCancelCallback: (() -> Unit)? = null

    private var pendingSelectionRunnable: Runnable? = null
    private var pendingFeedbackHideRunnable: Runnable? = null
    private var pendingSettleRunnable: Runnable? = null

    fun show(
        onSelectionReady: (
            bounds: NormalizedRectF,
            overlayWidth: Int,
            overlayHeight: Int,
            offsetX: Int,
            offsetY: Int
        ) -> Unit,
        onInvalidSelection: (String) -> Unit,
        onClear: () -> Unit = {},
        onCancel: () -> Unit
    ) {
        runOnMainThread {
            dismissInternal()

            onSelectionReadyCallback = onSelectionReady
            onInvalidSelectionCallback = onInvalidSelection
            onClearCallback = onClear
            onCancelCallback = onCancel

            val params = createOverlayLayoutParams()
            val root = buildOverlayHierarchy()

            layoutParams = params
            rootView = root

            try {
                windowManager.addView(root, params)
                visibleState = true
                ViewCompat.requestApplyInsets(root)
                startPulseAnimation()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach CircleSelectionOverlay: ${e.message}", e)
                dismissInternal()
            }
        }
    }

    fun hideForCapture(onHiddenAndSettled: () -> Unit) {
        runOnMainThread {
            val hadRoot = rootView != null
            dismissInternal()

            if (!hadRoot) {
                mainHandler.post { onHiddenAndSettled() }
                return@runOnMainThread
            }

            val settleTask = Runnable {
                pendingSettleRunnable = null
                onHiddenAndSettled()
            }
            pendingSettleRunnable = settleTask
            mainHandler.postDelayed(settleTask, CAPTURE_SETTLE_DELAY_MS)
        }
    }

    fun showInvalidFeedback(message: String) {
        runOnMainThread {
            val banner = feedbackBanner ?: return@runOnMainThread
            cancelFeedbackHide()

            banner.text = message
            banner.visibility = View.VISIBLE
            banner.alpha = 0f
            banner.animate()
                .alpha(1f)
                .setDuration(160L)
                .start()

            val hideRunnable = Runnable {
                pendingFeedbackHideRunnable = null
                hideFeedbackBanner()
            }
            pendingFeedbackHideRunnable = hideRunnable
            mainHandler.postDelayed(hideRunnable, FEEDBACK_DISPLAY_DURATION_MS)
        }
    }

    fun clearDrawing() {
        runOnMainThread {
            cancelPendingSelection()
            cancelFeedbackHide()
            hideFeedbackBanner()
            canvasView?.clearStrokeState()
        }
    }

    fun dismiss() {
        runOnMainThread {
            dismissInternal()
        }
    }

    private fun dismissInternal() {
        visibleState = false
        cancelPendingCallbacks()
        pulseAnimator?.cancel()
        pulseAnimator = null

        val root = rootView
        if (root != null) {
            try {
                if (root.isAttachedToWindow) {
                    windowManager.removeViewImmediate(root)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing CircleSelectionOverlay: ${e.message}")
            }
        }

        rootView = null
        canvasView = null
        headerDotView = null
        feedbackBanner = null
        layoutParams = null
        onSelectionReadyCallback = null
        onInvalidSelectionCallback = null
        onClearCallback = null
        onCancelCallback = null
    }

    private fun createOverlayLayoutParams(): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
    }

    private fun buildOverlayHierarchy(): FrameLayout {
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }

        val canvas = SelectionCanvasView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        canvasView = canvas
        root.addView(canvas)

        val topStack = buildTopHeaderContainer()
        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(28)
            leftMargin = dp(16)
            rightMargin = dp(16)
        }
        root.addView(topStack, topParams)

        val bottomBar = buildBottomControlsBar()
        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(28)
            leftMargin = dp(16)
            rightMargin = dp(16)
        }
        root.addView(bottomBar, bottomParams)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (topStack.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = max(bars.top, dp(14)) + dp(12)
                lp.leftMargin = bars.left + dp(16)
                lp.rightMargin = bars.right + dp(16)
                topStack.layoutParams = lp
            }
            (bottomBar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = max(bars.bottom, dp(16)) + dp(16)
                lp.leftMargin = bars.left + dp(16)
                lp.rightMargin = bars.right + dp(16)
                bottomBar.layoutParams = lp
            }
            canvas.updateSafeInsets(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }

        return root
    }

    private fun buildTopHeaderContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val headerPill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createRoundedDrawable(
                fillColor = COLOR_PILL_SURFACE,
                strokeColor = COLOR_HEADER_BORDER,
                strokeWidthPx = dp(1),
                cornerRadiusPx = dpFloat(22f)
            )
            elevation = dpFloat(8f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(7)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_TERRACOTTA)
            }
        }
        headerDotView = dot

        val statusLabel = TextView(context).apply {
            text = "CAPTURE ACTIVE"
            setTextColor(COLOR_TERRACOTTA)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        statusRow.addView(dot)
        statusRow.addView(statusLabel)

        val promptLabel = TextView(context).apply {
            text = "Circle anything you want to find."
            setTextColor(COLOR_WARM_IVORY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }

        headerPill.addView(statusRow)
        headerPill.addView(promptLabel)

        val banner = TextView(context).apply {
            visibility = View.GONE
            setTextColor(COLOR_WARM_IVORY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(
                fillColor = COLOR_BANNER_SURFACE,
                strokeColor = COLOR_TERRACOTTA,
                strokeWidthPx = dp(1).coerceAtLeast(2),
                cornerRadiusPx = dpFloat(18f)
            )
            elevation = dpFloat(6f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val bannerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
        feedbackBanner = banner

        container.addView(headerPill)
        container.addView(banner, bannerParams)
        return container
    }

    private fun buildBottomControlsBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clearButton = createActionPillButton(
            label = "Clear",
            fillColor = COLOR_PILL_SURFACE,
            strokeColor = COLOR_SAGE_BORDER,
            textColor = COLOR_WARM_IVORY,
            rippleColor = COLOR_RIPPLE_LIGHT
        ) {
            clearDrawing()
            onClearCallback?.invoke()
        }

        val cancelButton = createActionPillButton(
            label = "Cancel",
            fillColor = COLOR_WARM_IVORY,
            strokeColor = COLOR_TERRACOTTA,
            textColor = COLOR_DEEP_INK,
            rippleColor = COLOR_RIPPLE_DARK
        ) {
            val cancelCallback = onCancelCallback
            dismissInternal()
            cancelCallback?.invoke()
        }

        val clearParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(12)
        }

        bar.addView(clearButton, clearParams)
        bar.addView(cancelButton)
        return bar
    }

    private fun createActionPillButton(
        label: String,
        fillColor: Int,
        strokeColor: Int,
        textColor: Int,
        rippleColor: Int,
        onClick: () -> Unit
    ): TextView {
        val baseDrawable = createRoundedDrawable(
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = dp(1),
            cornerRadiusPx = dpFloat(24f)
        )
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            minWidth = dp(96)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            elevation = dpFloat(6f)
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                baseDrawable,
                null
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createRoundedDrawable(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        cornerRadiusPx: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                canvasView?.setPulsePhase(phase)
                headerDotView?.alpha = 0.6f + (0.4f * phase)
            }
            start()
        }
    }

    private fun hideFeedbackBanner() {
        val banner = feedbackBanner ?: return
        banner.animate().cancel()
        banner.visibility = View.GONE
        banner.alpha = 0f
    }

    private fun cancelPendingCallbacks() {
        cancelPendingSelection()
        cancelFeedbackHide()
        pendingSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSettleRunnable = null
    }

    private fun cancelPendingSelection() {
        pendingSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSelectionRunnable = null
    }

    private fun cancelFeedbackHide() {
        pendingFeedbackHideRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingFeedbackHideRunnable = null
    }

    private fun handleValidSelection(
        result: GestureAnalysisResult.ValidCircle,
        canvas: SelectionCanvasView
    ) {
        cancelPendingSelection()
        cancelFeedbackHide()
        hideFeedbackBanner()

        val location = IntArray(2)
        canvas.getLocationOnScreen(location)
        val overlayWidth = canvas.width
        val overlayHeight = canvas.height
        val offsetX = location[0]
        val offsetY = location[1]

        val submitRunnable = Runnable {
            pendingSelectionRunnable = null
            if (!visibleState) return@Runnable
            onSelectionReadyCallback?.invoke(
                result.boundingBox,
                overlayWidth,
                overlayHeight,
                offsetX,
                offsetY
            )
        }
        pendingSelectionRunnable = submitRunnable
        mainHandler.postDelayed(submitRunnable, SELECTION_CONFIRM_DELAY_MS)
    }

    private fun handleInvalidSelection(result: GestureAnalysisResult.InvalidSelection) {
        showInvalidFeedback(result.message)
        onInvalidSelectionCallback?.invoke(result.message)
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    private fun dpFloat(value: Float): Float {
        return value * context.resources.displayMetrics.density
    }

    /**
     * Full-screen drawing surface rendering the Deep Ink scrim, viewfinder corner ticks,
     * smoothed quadratic Bezier stroke with Forest Green aura, and confirmed selection bounds.
     */
    private inner class SelectionCanvasView(context: Context) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val minSampleDistancePx = 1.5f * density

        private val strokePoints = mutableListOf<TouchPoint>()
        private val strokePath = Path()
        private var lastPathX = 0f
        private var lastPathY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var isSubmittingSelection = false
        private var confirmedBox: RectF? = null
        private var pulsePhase = 0f

        private var safeLeftPx = 0
        private var safeTopPx = 0
        private var safeRightPx = 0
        private var safeBottomPx = 0

        private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * density
            color = COLOR_MUTED_SAGE
        }

        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 15f * density
            color = COLOR_FOREST_GREEN
        }

        private val midGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 9f * density
            color = COLOR_FOREST_GREEN
        }

        private val coreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4f * density
            color = COLOR_WARM_IVORY
        }

        private val confirmedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_CONFIRMED_FILL
        }

        private val confirmedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = COLOR_WARM_IVORY
        }

        private val confirmedAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5.5f * density
            color = COLOR_CONFIRMED_BORDER_AURA
        }

        fun updateSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
            safeLeftPx = left
            safeTopPx = top
            safeRightPx = right
            safeBottomPx = bottom
            invalidate()
        }

        fun setPulsePhase(phase: Float) {
            pulsePhase = phase
            invalidate()
        }

        fun clearStrokeState() {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            isSubmittingSelection = false
            confirmedBox = null
            strokePoints.clear()
            strokePath.reset()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(COLOR_SCRIM_BACKDROP)
            drawViewfinderCorners(canvas)
            drawActiveStroke(canvas)
            drawConfirmedBoundingBox(canvas)
        }

        private fun drawViewfinderCorners(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val insetPulse = (2f * density) * pulsePhase
            val marginH = 22f * density + insetPulse
            val left = safeLeftPx + marginH
            val right = w - safeRightPx - marginH
            val top = safeTopPx + (86f * density) + insetPulse
            val bottom = h - safeBottomPx - (86f * density) - insetPulse
            if (right - left < 64f * density || bottom - top < 64f * density) return

            val arm = 20f * density
            cornerStrokePaint.alpha = (110 + (75 * pulsePhase)).roundToInt().coerceIn(0, 255)

            // Top-left
            canvas.drawLine(left, top + arm, left, top, cornerStrokePaint)
            canvas.drawLine(left, top, left + arm, top, cornerStrokePaint)
            // Top-right
            canvas.drawLine(right - arm, top, right, top, cornerStrokePaint)
            canvas.drawLine(right, top, right, top + arm, cornerStrokePaint)
            // Bottom-left
            canvas.drawLine(left, bottom - arm, left, bottom, cornerStrokePaint)
            canvas.drawLine(left, bottom, left + arm, bottom, cornerStrokePaint)
            // Bottom-right
            canvas.drawLine(right - arm, bottom, right, bottom, cornerStrokePaint)
            canvas.drawLine(right, bottom - arm, right, bottom, cornerStrokePaint)
        }

        private fun drawActiveStroke(canvas: Canvas) {
            if (strokePoints.isEmpty()) return
            outerGlowPaint.alpha = (55 + (35 * pulsePhase)).roundToInt().coerceIn(0, 255)
            midGlowPaint.alpha = (115 + (45 * pulsePhase)).roundToInt().coerceIn(0, 255)
            canvas.drawPath(strokePath, outerGlowPaint)
            canvas.drawPath(strokePath, midGlowPaint)
            canvas.drawPath(strokePath, coreStrokePaint)
        }

        private fun drawConfirmedBoundingBox(canvas: Canvas) {
            val rect = confirmedBox ?: return
            val radius = 14f * density
            canvas.drawRoundRect(rect, radius, radius, confirmedFillPaint)
            canvas.drawRoundRect(rect, radius, radius, confirmedAccentPaint)
            canvas.drawRoundRect(rect, radius, radius, confirmedBorderPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isSubmittingSelection) return true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    cancelFeedbackHide()
                    hideFeedbackBanner()
                    beginStroke(event.x, event.y, event.eventTime)
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex == -1) return true
                    appendHistoricalSamples(event, pointerIndex)
                    appendStrokeSample(
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                        event.eventTime
                    )
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val liftedIndex = event.actionIndex
                    if (event.getPointerId(liftedIndex) == activePointerId) {
                        finishStroke(
                            event.getX(liftedIndex),
                            event.getY(liftedIndex),
                            event.eventTime
                        )
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        finishStroke(
                            event.getX(pointerIndex),
                            event.getY(pointerIndex),
                            event.eventTime
                        )
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    clearStrokeState()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun beginStroke(x: Float, y: Float, timeMs: Long) {
            confirmedBox = null
            strokePoints.clear()
            strokePath.reset()
            strokePath.moveTo(x, y)
            lastPathX = x
            lastPathY = y
            strokePoints.add(TouchPoint(x, y, timeMs))
            invalidate()
        }

        private fun appendHistoricalSamples(event: MotionEvent, pointerIndex: Int) {
            val historyCount = event.historySize
            for (h in 0 until historyCount) {
                appendStrokeSample(
                    event.getHistoricalX(pointerIndex, h),
                    event.getHistoricalY(pointerIndex, h),
                    event.getHistoricalEventTime(h)
                )
            }
        }

        private fun appendStrokeSample(x: Float, y: Float, timeMs: Long) {
            if (hypot(x - lastPathX, y - lastPathY) < minSampleDistancePx) return
            val midX = (lastPathX + x) * 0.5f
            val midY = (lastPathY + y) * 0.5f
            strokePath.quadTo(lastPathX, lastPathY, midX, midY)
            lastPathX = x
            lastPathY = y
            strokePoints.add(TouchPoint(x, y, timeMs))
        }

        private fun finishStroke(x: Float, y: Float, timeMs: Long) {
            if (strokePoints.isEmpty()) {
                clearStrokeState()
                return
            }
            strokePath.lineTo(x, y)
            if (hypot(x - lastPathX, y - lastPathY) >= 0.5f) {
                strokePoints.add(TouchPoint(x, y, timeMs))
            }

            val analysis = CircleGestureAnalyzer.analyze(
                points = strokePoints.toList(),
                surfaceWidthPx = width,
                surfaceHeightPx = height,
                density = density
            )

            when (analysis) {
                is GestureAnalysisResult.ValidCircle -> {
                    isSubmittingSelection = true
                    confirmedBox = RectF(
                        analysis.boundingBox.left,
                        analysis.boundingBox.top,
                        analysis.boundingBox.right,
                        analysis.boundingBox.bottom
                    )
                    invalidate()
                    handleValidSelection(analysis, this)
                }

                is GestureAnalysisResult.InvalidSelection -> {
                    clearStrokeState()
                    handleInvalidSelection(analysis)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CircleSelectionOverlay"

        private const val SELECTION_CONFIRM_DELAY_MS = 140L
        private const val CAPTURE_SETTLE_DELAY_MS = 120L
        private const val FEEDBACK_DISPLAY_DURATION_MS = 2600L
        private const val PULSE_CYCLE_MS = 1400L

        // UniCart editorial palette
        private const val COLOR_WARM_IVORY = 0xFFF6F3EC.toInt()
        private const val COLOR_DEEP_INK = 0xFF202B32.toInt()
        private const val COLOR_SCRIM_BACKDROP = 0x52202B32
        private const val COLOR_FOREST_GREEN = 0xFF356B57.toInt()
        private const val COLOR_MUTED_SAGE = 0xFFA6B5A0.toInt()
        private const val COLOR_TERRACOTTA = 0xFFC97858.toInt()

        // Tonal surface derivations
        private const val COLOR_PILL_SURFACE = 0xEB202B32.toInt()
        private const val COLOR_BANNER_SURFACE = 0xF2202B32.toInt()
        private const val COLOR_HEADER_BORDER = 0x38F6F3EC
        private const val COLOR_SAGE_BORDER = 0x7AA6B5A0
        private const val COLOR_CONFIRMED_FILL = 0x26356B57
        private const val COLOR_CONFIRMED_BORDER_AURA = 0x80356B57.toInt()
        private const val COLOR_RIPPLE_LIGHT = 0x33F6F3EC
        private const val COLOR_RIPPLE_DARK = 0x24202B32
    }
}
