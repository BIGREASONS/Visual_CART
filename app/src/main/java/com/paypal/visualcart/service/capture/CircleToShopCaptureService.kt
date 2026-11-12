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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
import com.paypal.visualcart.feature.circle.CircleCaptureSessionCoordinator
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
 * Pre-Armed Foreground MediaProjection service for Circle to Shop.
 *
 * Implements the pre-armed capture architecture:
 * 1. Obtains MediaProjection consent once ahead of time while inside UniCart.
 * 2. Starts foreground service with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION].
 * 3. Registers [MediaProjection.Callback] BEFORE calling [MediaProjection.createVirtualDisplay].
 * 4. Establishes ONE persistent VirtualDisplay and ONE ImageReader on a dedicated [HandlerThread].
 * 5. Reuses the same VirtualDisplay and Surface across multiple user circle gestures;
 *    NEVER calls createVirtualDisplay more than once on the same MediaProjection.
 * 6. Drains incoming buffers immediately during idle/armed states (acquireLatestImage()?.close())
 *    without reading pixels, creating bitmaps, or uploading screen data.
 * 7. When the user taps Circle to Shop on the floating bubble while armed, triggers
 *    [CircleSelectionOverlay] directly via WindowManager without launching any Activity,
 *    preventing video players (Instagram Reels, YouTube Shorts, Chrome) from pausing playback.
 * 8. Captures and locally crops exactly one clean frame upon gesture confirmation, releases
 *    full-frame buffers immediately, hands off the crop token to [VisualCartShareActivity],
 *    and returns to ARMED state.
 * 9. Displays a persistent ongoing notification with an explicit "Stop" action and tears down
 *    all resources upon user stop, switch disable, or system revocation.
 */
class CircleToShopCaptureService : Service() {

    private data class CaptureDisplayMetrics(
        val displayWidthPx: Int,
        val displayHeightPx: Int,
        val densityDpi: Int,
        val frameWidthPx: Int,
        val frameHeightPx: Int
    )

    private data class PendingCropTarget(
        val sessionId: Long,
        val bounds: NormalizedRectF,
        val overlayW: Int,
        val overlayH: Int,
        val offsetX: Int,
        val offsetY: Int,
        val metrics: CaptureDisplayMetrics
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = CircleCaptureSessionCoordinator()

    private var windowManager: WindowManager? = null
    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var selectionOverlay: CircleSelectionOverlay? = null

    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null

    private var captureTimeoutRunnable: Runnable? = null
    private var processingJob: Job? = null
    private val isHandlingFrame = AtomicBoolean(false)
    private val isFinalizeAwaitingFrame = AtomicBoolean(false)

    @Volatile
    private var pendingCapturedBitmap: Bitmap? = null

    @Volatile
    private var activeCropTarget: PendingCropTarget? = null

    @Volatile
    private var cachedMetrics: CaptureDisplayMetrics? = null

    @Volatile
    private var isTearingDownLocally = false

    @Volatile
    private var isServiceDestroyed = false

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
                Log.d(TAG, "Received ACTION_STOP_CAPTURE. Tearing down armed session.")
                teardownAndStopService(finalState = CircleProjectionState.IDLE)
                return START_NOT_STICKY
            }

            ACTION_TRIGGER_SELECTION -> {
                Log.d(TAG, "Received ACTION_TRIGGER_SELECTION from floating bubble.")
                if (coordinator.isArmed && mediaProjection != null && virtualDisplay != null) {
                    startSelectionOverlay(coordinator.sessionId)
                } else {
                    Log.w(TAG, "Session not armed or VirtualDisplay missing. Requesting fresh consent.")
                    showToast("Circle to Shop not armed. Tap to enable.")
                    teardownAndStopService(finalState = CircleProjectionState.IDLE)
                }
                return START_NOT_STICKY
            }

            ACTION_START_CIRCLE_CAPTURE -> {
                val grantId = intent.getStringExtra(EXTRA_GRANT_ID).orEmpty()
                val startImmediateSelection = intent.getBooleanExtra(EXTRA_START_IMMEDIATE_SELECTION, false)

                var grant: PendingCaptureGrant? = CircleToShopSessionStore.consumeSingleUseGrant(grantId)

                if (grant == null || !Settings.canDrawOverlays(this)) {
                    grant = null
                    CircleToShopSessionStore.clearPendingGrants()
                    Log.w(TAG, "Missing/expired Circle to Shop grant or overlay permission revoked.")
                    showToast(SESSION_EXPIRED_MESSAGE)
                    releaseSessionResources()
                    coordinator.stopSession("Grant missing or expired")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Tear down any prior capture resources before initializing the fresh armed session.
                releaseSessionResources()
                isTearingDownLocally = false

                if (!coordinator.startSession(grant = grant, startImmediateSelection = startImmediateSelection)) {
                    // Fallback if grant could not be started in coordinator
                    grant = null
                    showToast(SESSION_EXPIRED_MESSAGE)
                    coordinator.stopSession("Coordinator start failed")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val sessionId = coordinator.sessionId

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
                    grant = null
                }

                if (projection == null) {
                    coordinator.onProjectionRevoked()
                    showToast(SESSION_EXPIRED_MESSAGE)
                    teardownAndStopService(finalState = CircleProjectionState.IDLE)
                    return START_NOT_STICKY
                }

                mediaProjection = projection

                // Android 14+: Register MediaProjection.Callback BEFORE calling createVirtualDisplay.
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (isTearingDownLocally || sessionId != coordinator.sessionId) {
                            return
                        }
                        Log.w(TAG, "MediaProjection stopped externally via system or user.")
                        coordinator.onProjectionRevoked()
                        showToast("Screen capture stopped.")
                        teardownAndStopService(finalState = CircleProjectionState.IDLE)
                    }
                }
                projectionCallback = callback
                projection.registerCallback(callback, mainHandler)
                coordinator.registerProjectionCallback()

                // Establish single background thread, ImageReader, and VirtualDisplay
                if (!initializeVirtualDisplay(projection, sessionId)) {
                    Log.e(TAG, "Failed to initialize VirtualDisplay for armed session.")
                    showToast(PROTECTED_OR_FAILED_MESSAGE)
                    teardownAndStopService(finalState = CircleProjectionState.IDLE)
                    return START_NOT_STICKY
                }

                if (startImmediateSelection) {
                    startSelectionOverlay(sessionId)
                } else {
                    Log.i(TAG, "Circle to Shop successfully pre-armed. Waiting for user bubble activation.")
                }

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

    private fun initializeVirtualDisplay(projection: MediaProjection, sessionId: Long): Boolean {
        val thread = HandlerThread("CircleImageDrainThread").apply { start() }
        drainThread = thread
        val bgHandler = Handler(thread.looper)
        drainHandler = bgHandler

        val metrics = resolveDisplayMetrics()
        cachedMetrics = metrics

        val reader = try {
            ImageReader.newInstance(
                metrics.frameWidthPx,
                metrics.frameHeightPx,
                PixelFormat.RGBA_8888,
                3
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate ImageReader: ${e.message}", e)
            return false
        }
        imageReader = reader

        reader.setOnImageAvailableListener({ availableReader ->
            if (sessionId != coordinator.sessionId || isServiceDestroyed || isTearingDownLocally) {
                drainQuietly(availableReader)
                return@setOnImageAvailableListener
            }

            if (coordinator.isAwaitingFrameCapture) {
                val target = activeCropTarget
                if (target == null || target.sessionId != sessionId) {
                    drainQuietly(availableReader)
                    return@setOnImageAvailableListener
                }

                val image = try {
                    availableReader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                val plane = image.planes.firstOrNull()
                val bitmap = if (plane != null) {
                    try {
                        CroppedImageProcessor.convertRgbaPlaneToBitmap(
                            plane.buffer,
                            image.width,
                            image.height,
                            plane.pixelStride,
                            plane.rowStride
                        )
                    } catch (e: Exception) {
                        null
                    }
                } else null
                try {
                    image.close()
                } catch (_: Exception) {
                }

                if (bitmap != null) {
                    val old = pendingCapturedBitmap
                    pendingCapturedBitmap = bitmap
                    if (old != null && old !== bitmap && !old.isRecycled) {
                        old.recycle()
                    }

                    if (isFinalizeAwaitingFrame.compareAndSet(true, false)) {
                        cancelTimeoutWatchdog()
                        coordinator.onFrameAcquired()
                        activeCropTarget = null
                        pendingCapturedBitmap = null
                        processCapturedFrameAsync(
                            frameBitmap = bitmap,
                            bounds = target.bounds,
                            overlayW = target.overlayW,
                            overlayH = target.overlayH,
                            offsetX = target.offsetX,
                            offsetY = target.offsetY,
                            displayW = target.metrics.displayWidthPx,
                            displayH = target.metrics.displayHeightPx
                        )
                    }
                }
            } else {
                // Idle or selecting: drain buffers immediately with zero allocations or pixel reads.
                drainQuietly(availableReader)
            }
        }, bgHandler)

        try {
            coordinator.recordVirtualDisplayCreated()
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                metrics.frameWidthPx,
                metrics.frameHeightPx,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                bgHandler
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay: ${e.message}", e)
            return false
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
            .setContentText("Screen capture armed · Tap bubble to circle a product")
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
                description = "Persistent indicator while Circle to Shop screen capture is armed"
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

        if (!coordinator.triggerSelection()) {
            Log.w(TAG, "Cannot trigger selection in current coordinator state.")
            return
        }

        selectionOverlay?.dismiss()

        val overlay = CircleSelectionOverlay(this, wm)
        selectionOverlay = overlay

        overlay.show(
            onSelectionReady = { bounds, overlayW, overlayH, offsetX, offsetY ->
                if (isServiceDestroyed || isTearingDownLocally || sessionId != coordinator.sessionId) {
                    return@show
                }
                prepareCleanFrameCapture(sessionId, bounds, overlayW, overlayH, offsetX, offsetY)
                overlay.hideForCapture {
                    if (isServiceDestroyed || isTearingDownLocally || sessionId != coordinator.sessionId) {
                        return@hideForCapture
                    }
                    selectionOverlay = null
                    finalizeCleanFrameCapture(sessionId)
                }
            },
            onInvalidSelection = { invalidMessage ->
                showToast(invalidMessage)
            },
            onClear = {},
            onCancel = {
                Log.d(TAG, "Selection overlay cancelled by user. Returning to ARMED.")
                selectionOverlay = null
                coordinator.cancelSelection()
            }
        )
    }

    private fun prepareCleanFrameCapture(
        sessionId: Long,
        bounds: NormalizedRectF,
        overlayW: Int,
        overlayH: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        val metrics = cachedMetrics ?: resolveDisplayMetrics().also { cachedMetrics = it }
        val target = PendingCropTarget(
            sessionId = sessionId,
            bounds = bounds,
            overlayW = overlayW,
            overlayH = overlayH,
            offsetX = offsetX,
            offsetY = offsetY,
            metrics = metrics
        )
        activeCropTarget = target
        isFinalizeAwaitingFrame.set(false)
        val old = pendingCapturedBitmap
        pendingCapturedBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
        coordinator.requestFrameCapture()

        // Force SurfaceFlinger to emit a fresh buffer even on static/paused screens.
        // On stable video frames SurfaceFlinger only pushes dirty regions; a brief VirtualDisplay
        // resize triggers a full composition pass that guarantees at least one new ImageReader buffer.
        val vd = virtualDisplay
        val reader = imageReader
        if (vd != null && reader != null) {
            drainHandler?.post {
                try {
                    // Temporarily shrink then restore to original dimensions — forces a full redraw.
                    val w = reader.width
                    val h = reader.height
                    val dpi = metrics.densityDpi
                    vd.resize(w - 1, h - 1, dpi)
                    vd.resize(w, h, dpi)
                } catch (e: Exception) {
                    Log.w(TAG, "VirtualDisplay resize-to-force-frame failed: ${e.message}")
                }
            }
        }
    }

    private fun finalizeCleanFrameCapture(sessionId: Long) {
        val target = activeCropTarget
        if (target == null || target.sessionId != sessionId || isServiceDestroyed || isTearingDownLocally) {
            handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
            return
        }

        // Check if the ImageReader already has a pending frame we haven't picked up yet.
        // This handles the race where SurfaceFlinger delivered a frame between prepareCleanFrameCapture
        // and the overlay finishing its hide animation — ImageReader holds it but the listener
        // already ran and stored it, or it's sitting in the queue unprocessed.
        val reader = imageReader
        if (pendingCapturedBitmap == null && reader != null && coordinator.isAwaitingFrameCapture) {
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes.firstOrNull()
                    val bmp = if (plane != null) {
                        try {
                            CroppedImageProcessor.convertRgbaPlaneToBitmap(
                                plane.buffer, image.width, image.height,
                                plane.pixelStride, plane.rowStride
                            )
                        } catch (e: Exception) { null }
                    } else null
                    try { image.close() } catch (_: Exception) { }
                    if (bmp != null) {
                        pendingCapturedBitmap = bmp
                        Log.d(TAG, "finalizeCleanFrameCapture: eagerly grabbed pending frame ${bmp.width}x${bmp.height}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "finalizeCleanFrameCapture eager poll failed: ${e.message}")
            }
        }

        val bitmap = pendingCapturedBitmap
        if (bitmap != null) {
            cancelTimeoutWatchdog()
            coordinator.onFrameAcquired()
            activeCropTarget = null
            pendingCapturedBitmap = null
            processCapturedFrameAsync(
                frameBitmap = bitmap,
                bounds = target.bounds,
                overlayW = target.overlayW,
                overlayH = target.overlayH,
                offsetX = target.offsetX,
                offsetY = target.offsetY,
                displayW = target.metrics.displayWidthPx,
                displayH = target.metrics.displayHeightPx
            )
        } else {
            isFinalizeAwaitingFrame.set(true)
            val timeoutRunnable = Runnable {
                if (sessionId == coordinator.sessionId && isFinalizeAwaitingFrame.compareAndSet(true, false)) {
                    Log.w(TAG, "Timed out waiting for clean frame (${CAPTURE_TIMEOUT_MS}ms).")
                    activeCropTarget = null
                    val old = pendingCapturedBitmap
                    pendingCapturedBitmap = null
                    if (old != null && !old.isRecycled) {
                        old.recycle()
                    }
                    coordinator.returnToArmedAfterCapture()
                    handleCaptureFailure(PROTECTED_OR_FAILED_MESSAGE)
                }
            }
            captureTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)
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
                        coordinator.returnToArmedAfterCapture()
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
                            // Crucial: Keep session armed and ready for subsequent circles!
                            coordinator.returnToArmedAfterCapture()
                        }
                    }

                    is CropProcessingResult.Failure -> {
                        val errorMessage = if (result.isBlankOrProtectedFrame || result.reason.isBlank()) {
                            PROTECTED_OR_FAILED_MESSAGE
                        } else {
                            result.reason
                        }
                        withContext(Dispatchers.Main) {
                            coordinator.returnToArmedAfterCapture()
                            handleCaptureFailure(errorMessage)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error processing captured frame: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    if (!isServiceDestroyed) {
                        coordinator.returnToArmedAfterCapture()
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
        launchShareActivityManualFallback(errorMessage)
    }

    private fun teardownAndStopService(finalState: CircleProjectionState) {
        releaseSessionResources()
        coordinator.stopSession("Teardown requested")
        CircleToShopSessionStore.updateState(finalState)
        stopSelf()
    }

    private fun releaseSessionResources() {
        isTearingDownLocally = true
        cancelTimeoutWatchdog()
        activeCropTarget = null
        isHandlingFrame.set(false)
        isFinalizeAwaitingFrame.set(false)
        val oldBitmap = pendingCapturedBitmap
        pendingCapturedBitmap = null
        if (oldBitmap != null && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }

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

        drainThread?.let { thread ->
            try {
                thread.quitSafely()
            } catch (e: Exception) {
                Log.w(TAG, "Error quitting drain thread: ${e.message}")
            }
        }
        drainThread = null
        drainHandler = null
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

        coordinator.stopSession("Service destroyed")

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
        const val ACTION_TRIGGER_SELECTION = "com.paypal.visualcart.action.TRIGGER_SELECTION"
        const val ACTION_STOP_CAPTURE = "com.paypal.visualcart.action.STOP_CAPTURE"
        const val EXTRA_GRANT_ID = "extra_grant_id"
        const val EXTRA_START_IMMEDIATE_SELECTION = "extra_start_immediate_selection"

        fun startWithGrant(context: Context, grantId: String, startImmediateSelection: Boolean = false) {
            val intent = Intent(context, CircleToShopCaptureService::class.java).apply {
                action = ACTION_START_CIRCLE_CAPTURE
                putExtra(EXTRA_GRANT_ID, grantId)
                putExtra(EXTRA_START_IMMEDIATE_SELECTION, startImmediateSelection)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun triggerSelectionOverlay(context: Context) {
            val intent = Intent(context, CircleToShopCaptureService::class.java).apply {
                action = ACTION_TRIGGER_SELECTION
            }
            context.startService(intent)
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, CircleToShopCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }
}
