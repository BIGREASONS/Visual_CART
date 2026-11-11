package com.paypal.visualcart.service.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.paypal.visualcart.MainActivity
import com.paypal.visualcart.R
import com.paypal.visualcart.feature.circle.CircleProjectionState
import com.paypal.visualcart.feature.circle.CircleToShopSessionStore
import com.paypal.visualcart.feature.circle.CropProcessingResult
import com.paypal.visualcart.feature.circle.CroppedImageProcessor
import com.paypal.visualcart.feature.circle.NormalizedRectF
import com.paypal.visualcart.feature.circle.PendingCaptureGrant
import com.paypal.visualcart.feature.circle.PixelCropRect
import com.paypal.visualcart.feature.circle.ScreenCropCalculator
import com.paypal.visualcart.service.overlay.CircleSelectionOverlay
import com.paypal.visualcart.ui.share.VisualCartShareActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Foreground MediaProjection service for single-use Circle to Shop capture.
 *
 * Enforces Android 14+ compliance:
 * - Never enters foreground with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] before user consent.
 * - Consumes single-use grants atomically and clears all grant references immediately.
 * - Registers [MediaProjection.Callback] before creating a single [VirtualDisplay].
 * - Captures exactly one frame, stops projection immediately, crops locally, and discards full frame.
 */
class CircleToShopCaptureService : Service() {

    private data class CaptureDisplayMetrics(
        val displayWidthPx: Int,
        val displayHeightPx: Int,
        val densityDpi: Int,
        val frameWidthPx: Int,
        val frameHeightPx: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var windowManager: WindowManager? = null
    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var selectionOverlay: CircleSelectionOverlay? = null

    private var captureTimeoutRunnable: Runnable? = null
    private var processingJob: Job? = null
    private val frameCaptured = AtomicBoolean(false)

    @Volatile
    private var isTearingDownLocally = false

    @Volatile
    private var isServiceDestroyed = false

    @Volatile
    private var activeSessionId = 0L

    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAPTURE -> {
                Log.d(TAG, "Received ACTION_STOP_CAPTURE. Stopping capture immediately.")
                teardownAndStopService(finalState = CircleProjectionState.IDLE)
                return START_NOT_STICKY
            }

            ACTION_START_CIRCLE_CAPTURE -> {
                val grantId = intent.getStringExtra(EXTRA_GRANT_ID)
                var grant: PendingCaptureGrant? = if (!grantId.isNullOrBlank()) {
                    CircleToShopSessionStore.consumeSingleUseGrant(grantId)
                } else {
                    null
                }

                if (grant == null || !Settings.canDrawOverlays(this)) {
                    grant = null
                    CircleToShopSessionStore.clearPendingGrants()
                    Log.w(TAG, "Missing/expired Circle to Shop grant or overlay permission revoked.")
                    showToast(SESSION_EXPIRED_MESSAGE)
                    releaseSessionResources()
                    CircleToShopSessionStore.markBubbleHiddenForCapture(false)
                    CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Tear down any prior capture resources before initializing the fresh session.
                releaseSessionResources()
                frameCaptured.set(false)
                isTearingDownLocally = false
                activeSessionId += 1L
                val sessionId = activeSessionId

                // Android 14+: startForeground(MEDIA_PROJECTION) must run after consent and before getMediaProjection.
                try {
                    startForegroundNotification()
                } catch (e: Exception) {
                    grant = null
                    Log.e(TAG, "Failed to start MediaProjection foreground service: ${e.message}", e)
                    showToast(SESSION_EXPIRED_MESSAGE)
                    teardownAndStopService(finalState = CircleProjectionState.IDLE)
                    return START_NOT_STICKY
                }

                val mgr = projectionManager
                    ?: (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager).also {
                        projectionManager = it
                    }
                val projection = try {
                    mgr?.getMediaProjection(grant.resultCode, grant.resultData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to obtain MediaProjection: ${e.message}", e)
                    null
                } finally {
                    // Drop the Intent grant reference immediately so it is never retained.
                    grant = null
                }

                if (projection == null) {
                    CircleToShopSessionStore.updateState(CircleProjectionState.REVOKED_OR_DENIED)
                    showToast(SESSION_EXPIRED_MESSAGE)
                    teardownAndStopService(finalState = CircleProjectionState.IDLE)
                    return START_NOT_STICKY
                }

                mediaProjection = projection

                // Android 14+: MediaProjection.Callback must be registered BEFORE createVirtualDisplay.
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (isTearingDownLocally || frameCaptured.get() || sessionId != activeSessionId) {
                            return
                        }
                        Log.w(TAG, "MediaProjection stopped externally via system or user.")
                        CircleToShopSessionStore.updateState(CircleProjectionState.REVOKED_OR_DENIED)
                        showToast("Screen capture stopped.")
                        teardownAndStopService(finalState = CircleProjectionState.IDLE)
                    }
                }
                projectionCallback = callback
                projection.registerCallback(callback, mainHandler)

                startSelectionOverlay(sessionId)
                return START_NOT_STICKY
            }

            else -> {
                if (!isForegroundStarted) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CircleToShopCaptureService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Circle to Shop")
            .setContentText("Screen capture active · Circle a product")
            .setSmallIcon(R.drawable.ic_quick_settings_cart)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Stop capture", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Circle to Shop Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active indicator during single-use Circle to Shop screen capture"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startSelectionOverlay(sessionId: Long) {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.also {
            windowManager = it
        }
        if (wm == null) {
            handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            return
        }

        CircleToShopSessionStore.markBubbleHiddenForCapture(true)
        CircleToShopSessionStore.updateState(CircleProjectionState.SELECTING)

        val overlay = CircleSelectionOverlay(this, wm)
        selectionOverlay = overlay

        overlay.show(
            { bounds, overlayW, overlayH, offsetX, offsetY ->
                if (isServiceDestroyed || isTearingDownLocally || sessionId != activeSessionId) {
                    return@show
                }
                overlay.hideForCapture {
                    if (isServiceDestroyed || isTearingDownLocally || sessionId != activeSessionId) {
                        return@hideForCapture
                    }
                    captureSingleCleanFrame(
                        sessionId = sessionId,
                        bounds = bounds,
                        overlayW = overlayW,
                        overlayH = overlayH,
                        offsetX = offsetX,
                        offsetY = offsetY
                    )
                }
            },
            { invalidMessage ->
                showToast(invalidMessage)
            },
            {},
            {
                Log.d(TAG, "Selection overlay cancelled by user.")
                teardownAndStopService(finalState = CircleProjectionState.IDLE)
            }
        )
    }

    private fun captureSingleCleanFrame(
        sessionId: Long,
        bounds: NormalizedRectF,
        overlayW: Int,
        overlayH: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        val projection = mediaProjection
        if (projection == null || isServiceDestroyed || isTearingDownLocally || sessionId != activeSessionId) {
            handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            return
        }

        CircleToShopSessionStore.updateState(CircleProjectionState.CAPTURING)

        val metrics = resolveDisplayMetrics()
        val reader = try {
            ImageReader.newInstance(
                metrics.frameWidthPx,
                metrics.frameHeightPx,
                PixelFormat.RGBA_8888,
                2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate ImageReader: ${e.message}", e)
            handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            return
        }
        imageReader = reader

        val timeoutRunnable = Runnable {
            if (sessionId == activeSessionId && frameCaptured.compareAndSet(false, true)) {
                Log.w(TAG, "Timed out waiting for VirtualDisplay frame (${CAPTURE_TIMEOUT_MS}ms).")
                handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            }
        }
        captureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)

        reader.setOnImageAvailableListener({ availableReader ->
            if (sessionId != activeSessionId || isServiceDestroyed || isTearingDownLocally) {
                drainQuietly(availableReader)
                return@setOnImageAvailableListener
            }

            val image = try {
                availableReader.acquireLatestImage()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire ImageReader frame: ${e.message}", e)
                null
            } ?: return@setOnImageAvailableListener

            if (!frameCaptured.compareAndSet(false, true)) {
                try {
                    image.close()
                } catch (_: Exception) {
                }
                return@setOnImageAvailableListener
            }

            cancelTimeoutWatchdog()

            var fullFrameBitmap: Bitmap? = null
            try {
                val plane = image.planes.firstOrNull()
                if (plane != null) {
                    fullFrameBitmap = CroppedImageProcessor.convertRgbaPlaneToBitmap(
                        plane.buffer,
                        image.width,
                        image.height,
                        plane.pixelStride,
                        plane.rowStride
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed converting RGBA plane to Bitmap: ${e.message}", e)
            } finally {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }

            // Immediately release VirtualDisplay, ImageReader, MediaProjection, and overlay once 1 frame is captured.
            releaseSessionResources()

            val capturedBitmap = fullFrameBitmap
            if (capturedBitmap == null) {
                handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
                return@setOnImageAvailableListener
            }

            processCapturedFrameAsync(
                frameBitmap = capturedBitmap,
                bounds = bounds,
                overlayW = overlayW,
                overlayH = overlayH,
                offsetX = offsetX,
                offsetY = offsetY,
                displayW = metrics.displayWidthPx,
                displayH = metrics.displayHeightPx
            )
        }, mainHandler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                metrics.frameWidthPx,
                metrics.frameHeightPx,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating VirtualDisplay: ${e.message}", e)
            if (frameCaptured.compareAndSet(false, true)) {
                handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay: ${e.message}", e)
            if (frameCaptured.compareAndSet(false, true)) {
                handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            }
        }
    }

    private fun processCapturedFrameAsync(
        frameBitmap: Bitmap,
        bounds: NormalizedRectF,
        overlayW: Int,
        overlayH: Int,
        offsetX: Int,
        offsetY: Int,
        displayW: Int,
        displayH: Int
    ) {
        processingJob?.cancel()
        processingJob = serviceScope.launch {
            try {
                val cropRect: PixelCropRect? = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                    bounds,
                    overlayW,
                    overlayH,
                    frameBitmap.width,
                    frameBitmap.height,
                    offsetX,
                    offsetY,
                    displayW,
                    displayH
                )

                if (cropRect == null) {
                    if (!frameBitmap.isRecycled) {
                        frameBitmap.recycle()
                    }
                    withContext(Dispatchers.Main) {
                        handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
                    }
                    return@launch
                }

                when (
                    val result = CroppedImageProcessor.cropSaveAndDiscardFullFrame(
                        frameBitmap,
                        cropRect,
                        cacheDir,
                        true
                    )
                ) {
                    is CropProcessingResult.Success -> {
                        val artifact = result.artifact
                        val captureToken = CircleToShopSessionStore.registerCroppedArtifact(
                            file = artifact.file,
                            mimeType = artifact.mimeType,
                            width = artifact.width,
                            height = artifact.height,
                            byteSize = artifact.byteSize
                        )
                        withContext(Dispatchers.Main) {
                            launchShareActivityWithCropResult(captureToken)
                            teardownAndStopService(finalState = CircleProjectionState.IDLE)
                        }
                    }

                    is CropProcessingResult.Failure -> {
                        val errorMessage = if (result.isBlankOrProtectedFrame || result.reason.isBlank()) {
                            PROTECTED_OR_FAILED_MESSAGE
                        } else {
                            result.reason
                        }
                        withContext(Dispatchers.Main) {
                            handleCaptureFailure(errorMessage)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error processing captured frame: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    if (!isServiceDestroyed) {
                        handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
                    }
                }
            } finally {
                if (!frameBitmap.isRecycled) {
                    frameBitmap.recycle()
                }
            }
        }
    }

    private fun resolveDisplayMetrics(): CaptureDisplayMetrics {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

        var displayW = 0
        var displayH = 0
        var densityDpi = resources.displayMetrics.densityDpi

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            displayW = bounds.width()
            displayH = bounds.height()
            if (resources.configuration.densityDpi > 0) {
                densityDpi = resources.configuration.densityDpi
            }
        }

        if (displayW <= 0 || displayH <= 0) {
            val realMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = defaultDisplay ?: wm.defaultDisplay
            @Suppress("DEPRECATION")
            display?.getRealMetrics(realMetrics)
            displayW = realMetrics.widthPixels.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            displayH = realMetrics.heightPixels.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            densityDpi = realMetrics.densityDpi.takeIf { it > 0 } ?: densityDpi
        }

        val safeDisplayW = displayW.coerceAtLeast(1)
        val safeDisplayH = displayH.coerceAtLeast(1)
        val safeDpi = densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT)
        val (frameW, frameH) = clampFrameSize(safeDisplayW, safeDisplayH, MAX_CAPTURE_DIMENSION_PX)

        return CaptureDisplayMetrics(
            displayWidthPx = safeDisplayW,
            displayHeightPx = safeDisplayH,
            densityDpi = safeDpi,
            frameWidthPx = frameW,
            frameHeightPx = frameH
        )
    }

    private fun clampFrameSize(
        displayW: Int,
        displayH: Int,
        maxLongestSide: Int
    ): Pair<Int, Int> {
        val longest = maxOf(displayW, displayH)
        if (longest <= maxLongestSide) {
            return displayW to displayH
        }
        val scale = maxLongestSide.toFloat() / longest.toFloat()
        val frameW = (displayW * scale).roundToInt().coerceAtLeast(1)
        val frameH = (displayH * scale).roundToInt().coerceAtLeast(1)
        return frameW to frameH
    }

    private fun launchShareActivityWithCropResult(captureToken: String) {
        val intent = Intent(this, VisualCartShareActivity::class.java).apply {
            action = VisualCartShareActivity.ACTION_CIRCLE_CROP_RESULT
            putExtra(VisualCartShareActivity.EXTRA_CIRCLE_CAPTURE_TOKEN, captureToken)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch VisualCartShareActivity with crop result: ${e.message}", e)
            showToast("Could not open Visual Cart results.")
        }
    }

    private fun launchShareActivityManualFallback(errorMessage: String) {
        val intent = Intent(this, VisualCartShareActivity::class.java).apply {
            action = VisualCartShareActivity.ACTION_CIRCLE_MANUAL_FALLBACK
            putExtra(VisualCartShareActivity.EXTRA_CIRCLE_ERROR_MESSAGE, errorMessage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch VisualCartShareActivity manual fallback: ${e.message}", e)
            showToast(errorMessage)
        }
    }

    private fun handleCaptureFailure(errorMessage: String) {
        releaseSessionResources()
        launchShareActivityManualFallback(errorMessage)
        teardownAndStopService(finalState = CircleProjectionState.IDLE)
    }

    private fun teardownAndStopService(finalState: CircleProjectionState) {
        releaseSessionResources()
        CircleToShopSessionStore.markBubbleHiddenForCapture(false)
        CircleToShopSessionStore.updateState(finalState)
        stopSelf()
    }

    private fun releaseSessionResources() {
        isTearingDownLocally = true
        cancelTimeoutWatchdog()

        selectionOverlay?.let { overlay ->
            try {
                overlay.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Error dismissing selection overlay: ${e.message}")
            }
        }
        selectionOverlay = null

        virtualDisplay?.let { display ->
            try {
                display.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing VirtualDisplay: ${e.message}")
            }
        }
        virtualDisplay = null

        imageReader?.let { reader ->
            try {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ImageReader: ${e.message}")
            }
        }
        imageReader = null

        val projection = mediaProjection
        val callback = projectionCallback
        if (projection != null) {
            if (callback != null) {
                try {
                    projection.unregisterCallback(callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering MediaProjection callback: ${e.message}")
                }
            }
            try {
                projection.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaProjection: ${e.message}")
            }
        }
        projectionCallback = null
        mediaProjection = null
    }

    private fun cancelTimeoutWatchdog() {
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null
    }

    private fun drainQuietly(reader: ImageReader) {
        try {
            reader.acquireLatestImage()?.close()
        } catch (_: Exception) {
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        processingJob?.cancel()
        processingJob = null
        releaseSessionResources()
        serviceScope.cancel()

        CircleToShopSessionStore.markBubbleHiddenForCapture(false)
        CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)

        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
        isForegroundStarted = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CircleCaptureService"
        private const val CHANNEL_ID = "visual_cart_circle_capture_channel"
        private const val NOTIFICATION_ID = 4041
        private const val CONTENT_REQUEST_CODE = 4041
        private const val STOP_REQUEST_CODE = 4042
        private const val VIRTUAL_DISPLAY_NAME = "UniCartCircleCapture"
        private const val MAX_CAPTURE_DIMENSION_PX = 1920
        private const val CAPTURE_TIMEOUT_MS = 2500L

        private const val SESSION_EXPIRED_MESSAGE =
            "Circle to Shop session expired. Please tap Circle to Shop again."
        private const val PROTECTED_OR_FAILED_MESSAGE =
            "This screen is protected (FLAG_SECURE) or could not be captured. Enter a product name to search manually."

        const val ACTION_START_CIRCLE_CAPTURE = "com.paypal.visualcart.action.START_CIRCLE_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.paypal.visualcart.action.STOP_CAPTURE"
        const val EXTRA_GRANT_ID = "extra_grant_id"

        fun startWithGrant(context: Context, grantId: String) {
            val intent = Intent(context, CircleToShopCaptureService::class.java).apply {
                action = ACTION_START_CIRCLE_CAPTURE
                putExtra(EXTRA_GRANT_ID, grantId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, CircleToShopCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }
}
