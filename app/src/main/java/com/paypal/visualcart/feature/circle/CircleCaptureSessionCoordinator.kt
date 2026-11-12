package com.paypal.visualcart.feature.circle

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Kotlin coordinator governing the lifecycle invariants of a Pre-Armed Circle to Shop capture session:
 * 1. Exactly one MediaProjection grant consumption per session.
 * 2. MediaProjection.Callback must be registered prior to VirtualDisplay creation.
 * 3. Exactly one VirtualDisplay and ImageReader instance created and maintained across multiple captures.
 * 4. ImageReader buffers are drained immediately without processing during idle/armed/selecting states.
 * 5. Exactly one clean frame is acquired and processed when a circle gesture is confirmed.
 * 6. Transitions back to ARMED after capture or cancellation without tearing down the underlying projection session.
 * 7. Clean teardown and resource release upon explicit user stop, switch disable, or system revocation.
 */
class CircleCaptureSessionCoordinator(
    private val sessionStore: CircleToShopSessionStore = CircleToShopSessionStore
) {

    private val _sessionId = AtomicLong(0L)
    val sessionId: Long
        get() = _sessionId.get()

    private val _virtualDisplayCreatedCount = AtomicInteger(0)
    val virtualDisplayCreatedCount: Int
        get() = _virtualDisplayCreatedCount.get()

    private val _captureCount = AtomicInteger(0)
    val captureCount: Int
        get() = _captureCount.get()

    private val _isCallbackRegistered = AtomicBoolean(false)
    val isCallbackRegistered: Boolean
        get() = _isCallbackRegistered.get()

    private val _isAwaitingFrameCapture = AtomicBoolean(false)
    val isAwaitingFrameCapture: Boolean
        get() = _isAwaitingFrameCapture.get()

    val currentState: CircleProjectionState
        get() = sessionStore.state.value

    val isArmed: Boolean
        get() = sessionStore.isArmed.value

    /**
     * Initializes a new pre-armed session from an already consumed single-use grant.
     * Establishes initial session identifiers and transitions state.
     */
    fun startSession(grant: PendingCaptureGrant?, startImmediateSelection: Boolean): Boolean {
        if (grant == null) return false
        _sessionId.incrementAndGet()
        _virtualDisplayCreatedCount.set(0)
        _captureCount.set(0)
        _isCallbackRegistered.set(false)
        _isAwaitingFrameCapture.set(false)

        val nextState = if (startImmediateSelection) {
            CircleProjectionState.SELECTING
        } else {
            CircleProjectionState.ARMED
        }
        sessionStore.updateState(nextState)
        return true
    }

    /**
     * Initializes a new pre-armed session by consuming a single-use grant ID.
     */
    fun startSession(grantId: String, startImmediateSelection: Boolean): Boolean {
        val grant = sessionStore.consumeSingleUseGrant(grantId) ?: return false
        return startSession(grant, startImmediateSelection)
    }

    /**
     * Records callback registration before VirtualDisplay creation.
     */
    fun registerProjectionCallback(): Boolean {
        return _isCallbackRegistered.compareAndSet(false, true)
    }

    /**
     * Enforces the Android rule: createVirtualDisplay must NEVER be called more than once
     * on the same MediaProjection instance.
     */
    fun recordVirtualDisplayCreated(): Boolean {
        if (!_isCallbackRegistered.get()) {
            throw IllegalStateException("MediaProjection.Callback must be registered BEFORE creating VirtualDisplay")
        }
        val currentCount = _virtualDisplayCreatedCount.get()
        if (currentCount > 0) {
            throw IllegalStateException("VirtualDisplay already created ($currentCount). Re-creation on same MediaProjection is illegal.")
        }
        return _virtualDisplayCreatedCount.compareAndSet(0, 1)
    }

    /**
     * Triggers the selection overlay from the floating bubble while the session is armed.
     */
    fun triggerSelection(): Boolean {
        if (!isArmed || _virtualDisplayCreatedCount.get() != 1) {
            return false
        }
        sessionStore.markBubbleHiddenForCapture(true)
        sessionStore.updateState(CircleProjectionState.SELECTING)
        return true
    }

    /**
     * Signals that the circle selection overlay has settled and a clean frame is awaited.
     */
    fun requestFrameCapture(): Boolean {
        if (sessionStore.state.value != CircleProjectionState.SELECTING) {
            return false
        }
        sessionStore.updateState(CircleProjectionState.CAPTURING)
        return _isAwaitingFrameCapture.compareAndSet(false, true)
    }

    /**
     * Consumes the frame capture signal once a clean frame has been acquired.
     */
    fun onFrameAcquired(): Boolean {
        if (_isAwaitingFrameCapture.compareAndSet(true, false)) {
            _captureCount.incrementAndGet()
            return true
        }
        return false
    }

    /**
     * Returns the session to ARMED after a successful frame capture and crop handoff.
     * The MediaProjection and VirtualDisplay remain alive for subsequent circles.
     */
    fun returnToArmedAfterCapture(): Boolean {
        _isAwaitingFrameCapture.set(false)
        sessionStore.markBubbleHiddenForCapture(false)
        sessionStore.updateState(CircleProjectionState.ARMED)
        return true
    }

    /**
     * Returns the session to ARMED when the user cancels the selection overlay.
     */
    fun cancelSelection(): Boolean {
        _isAwaitingFrameCapture.set(false)
        sessionStore.markBubbleHiddenForCapture(false)
        sessionStore.updateState(CircleProjectionState.ARMED)
        return true
    }

    /**
     * Teardown upon explicit user stop, notification action, or switch disable.
     */
    fun stopSession(reason: String = "User requested stop") {
        _isAwaitingFrameCapture.set(false)
        _virtualDisplayCreatedCount.set(0)
        _isCallbackRegistered.set(false)
        sessionStore.markBubbleHiddenForCapture(false)
        sessionStore.updateState(CircleProjectionState.IDLE)
    }

    /**
     * Teardown upon external projection revocation or system stop.
     */
    fun onProjectionRevoked() {
        _isAwaitingFrameCapture.set(false)
        _virtualDisplayCreatedCount.set(0)
        _isCallbackRegistered.set(false)
        sessionStore.markBubbleHiddenForCapture(false)
        sessionStore.updateState(CircleProjectionState.REVOKED_OR_DENIED)
        sessionStore.updateState(CircleProjectionState.IDLE)
    }
}
