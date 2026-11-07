package com.paypal.visualcart.service.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.paypal.visualcart.MainActivity
import com.paypal.visualcart.R
import com.paypal.visualcart.service.tile.VisualCartTileService
import kotlin.math.hypot

/**
 * Floating Overlay Service for PayPal Visual Cart.
 * Displays a 56dp Forest Green circular button that snaps to screen edges and
 * offers quick capture options (Screenshot, Paste link, Open cart, Hide).
 */
class VisualCartOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleContainer: FrameLayout? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    private var popupView: View? = null
    private var popupLayoutParams: WindowManager.LayoutParams? = null

    private var snapAnimator: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays. Stopping service.")
            stopSelf()
            return
        }

        isRunning = true
        VisualCartTileService.requestTileUpdate(this)

        startForegroundNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initBubbleView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                Log.d(TAG, "Received ACTION_STOP_OVERLAY. Stopping service.")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        VisualCartTileService.requestTileUpdate(this)

        snapAnimator?.cancel()
        dismissCapturePopup()

        bubbleContainer?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing bubble view: ${e.message}")
            }
        }
        bubbleContainer = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        Log.d(TAG, "VisualCartOverlayService destroyed successfully.")
    }

    private fun startForegroundNotification() {
        createNotificationChannel()

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VisualCartOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_quick_settings_cart)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                0,
                getString(R.string.action_turn_off),
                stopPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun initBubbleView() {
        val wm = windowManager ?: return
        if (bubbleContainer != null) return

        val bubbleSizePx = dpToPx(56)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultX = getScreenWidth() - bubbleSizePx - dpToPx(16)
        val defaultY = dpToPx(160)
        val initialX = prefs.getInt(KEY_POS_X, defaultX)
        val initialY = prefs.getInt(KEY_POS_Y, defaultY)

        val params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        bubbleLayoutParams = params

        // Create 56dp Forest Green circular container with elevation and ivory shopping cart icon
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(bubbleSizePx, bubbleSizePx)
            setBackgroundResource(R.drawable.bg_floating_bubble)
            elevation = dpToPx(8).toFloat()
            clipToOutline = true
        }

        val cartIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(28), dpToPx(28), Gravity.CENTER)
            setImageResource(R.drawable.ic_floating_cart)
            contentDescription = getString(R.string.app_name)
        }
        container.addView(cartIcon)

        attachTouchListener(container, params)

        bubbleContainer = container
        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble overlay view: ${e.message}", e)
        }
    }

    private fun attachTouchListener(container: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (!isDragging && hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop) {
                        isDragging = true
                        dismissCapturePopup()
                    }

                    if (isDragging) {
                        params.x = (initialX + deltaX).toInt()
                        params.y = (initialY + deltaY).toInt()
                        clampPositionToScreen(params)
                        try {
                            if (container.isAttachedToWindow) {
                                windowManager?.updateViewLayout(container, params)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update bubble layout: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToNearestHorizontalEdge(params)
                    } else {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestHorizontalEdge(params: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        val bubble = bubbleContainer ?: return
        val screenWidth = getScreenWidth()
        val bubbleWidth = bubble.width.takeIf { it > 0 } ?: dpToPx(56)
        val edgeMargin = dpToPx(12)

        val leftTargetX = edgeMargin
        val rightTargetX = screenWidth - bubbleWidth - edgeMargin
        val targetX = if (params.x + (bubbleWidth / 2) < screenWidth / 2) {
            leftTargetX
        } else {
            rightTargetX
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    if (bubble.isAttachedToWindow) {
                        wm.updateViewLayout(bubble, params)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating snap animation: ${e.message}")
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveBubblePosition(params.x, params.y)
                }
            })
            start()
        }
    }

    private fun onBubbleClicked() {
        if (popupView != null && popupView?.isAttachedToWindow == true) {
            dismissCapturePopup()
        } else {
            showCapturePopup()
        }
    }

    private fun showCapturePopup() {
        val wm = windowManager ?: return
        val bubble = bubbleContainer ?: return
        val bParams = bubbleLayoutParams ?: return

        dismissCapturePopup()

        val inflater = LayoutInflater.from(this)
        val popup = inflater.inflate(R.layout.overlay_capture_popup, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val popupWidth = dpToPx(230)
        val screenWidth = getScreenWidth()
        val bubbleWidth = bubble.width.takeIf { it > 0 } ?: dpToPx(56)

        val isNearLeft = (bParams.x + bubbleWidth / 2) < (screenWidth / 2)
        val popupX = if (isNearLeft) {
            bParams.x + bubbleWidth + dpToPx(8)
        } else {
            bParams.x - popupWidth - dpToPx(8)
        }
        val popupY = (bParams.y - dpToPx(20)).coerceAtLeast(dpToPx(40))

        val pParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = popupX.coerceIn(dpToPx(8), screenWidth - popupWidth - dpToPx(8))
            y = popupY
        }
        popupLayoutParams = pParams

        // Setup touch dismiss on outside touch
        popup.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissCapturePopup()
                true
            } else {
                false
            }
        }

        // Action 1: Add from screenshot
        popup.findViewById<View>(R.id.action_screenshot)?.setOnClickListener {
            dismissCapturePopup()
            launchMainActivity(
                action = MainActivity.ACTION_CAPTURE,
                extraCaptureType = MainActivity.CAPTURE_TYPE_SCREENSHOT
            )
        }

        // Action 2: Paste product link
        popup.findViewById<View>(R.id.action_paste_link)?.setOnClickListener {
            dismissCapturePopup()
            launchMainActivity(
                action = MainActivity.ACTION_CAPTURE,
                extraCaptureType = MainActivity.CAPTURE_TYPE_LINK
            )
        }

        // Action 3: Open my cart
        popup.findViewById<View>(R.id.action_open_cart)?.setOnClickListener {
            dismissCapturePopup()
            launchMainActivity()
        }

        // Action 4: Hide floating button
        popup.findViewById<View>(R.id.action_hide_overlay)?.setOnClickListener {
            dismissCapturePopup()
            stopSelf()
        }

        popup.findViewById<View>(R.id.btn_close_popup)?.setOnClickListener {
            dismissCapturePopup()
        }

        popupView = popup
        try {
            wm.addView(popup, pParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show capture popup: ${e.message}", e)
        }
    }

    private fun dismissCapturePopup() {
        val popup = popupView ?: return
        try {
            if (popup.isAttachedToWindow) {
                windowManager?.removeViewImmediate(popup)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing capture popup: ${e.message}")
        } finally {
            popupView = null
        }
    }

    private fun launchMainActivity(action: String? = null, extraCaptureType: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (action != null) {
                this.action = action
            }
            if (extraCaptureType != null) {
                putExtra(MainActivity.EXTRA_CAPTURE_TYPE, extraCaptureType)
            }
        }
        startActivity(intent)
    }

    private fun clampPositionToScreen(params: WindowManager.LayoutParams) {
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val bubbleWidth = bubbleContainer?.width ?: dpToPx(56)
        val bubbleHeight = bubbleContainer?.height ?: dpToPx(56)

        val minX = 0
        val maxX = (screenWidth - bubbleWidth).coerceAtLeast(0)
        val minY = dpToPx(24) // Top status bar safe margin
        val maxY = (screenHeight - bubbleHeight - dpToPx(48)).coerceAtLeast(minY)

        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }

    private fun getScreenWidth(): Int {
        val wm = windowManager ?: return resources.displayMetrics.widthPixels
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            metrics.bounds.width()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        }
    }

    private fun getScreenHeight(): Int {
        val wm = windowManager ?: return resources.displayMetrics.heightPixels
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            metrics.bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            metrics.heightPixels
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "VisualCartOverlay"
        private const val CHANNEL_ID = "visual_cart_overlay_channel"
        private const val NOTIFICATION_ID = 4040

        const val ACTION_START_OVERLAY = "com.paypal.visualcart.action.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.paypal.visualcart.action.STOP_OVERLAY"

        private const val PREFS_NAME = "visual_cart_overlay_prefs"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VisualCartOverlayService::class.java).apply {
                action = ACTION_START_OVERLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VisualCartOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            context.startService(intent)
        }
    }
}
