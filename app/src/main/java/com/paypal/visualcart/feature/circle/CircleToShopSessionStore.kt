package com.paypal.visualcart.feature.circle

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifecycle states for a single-use Circle to Shop MediaProjection session.
 */
enum class CircleProjectionState {
    IDLE,
    REQUESTING_CONSENT,
    ARMED,
    SELECTING,
    CAPTURING,
    REVOKED_OR_DENIED
}

/**
 * Ephemeral, single-use MediaProjection consent grant returned from the system consent dialog.
 * Never persisted or reused across capture sessions.
 */
data class PendingCaptureGrant(
    val resultCode: Int,
    val resultData: Intent
)

/**
 * Metadata for a locally cropped capture artifact awaiting handoff to [VisualCartShareActivity].
 */
data class StoredCircleArtifact(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Thread-safe in-memory session store coordinating single-use MediaProjection grants,
 * capture state, bubble visibility during capture, and ephemeral cropped image artifacts.
 */
object CircleToShopSessionStore {

    private val _state = MutableStateFlow(CircleProjectionState.IDLE)
    val state: StateFlow<CircleProjectionState> = _state.asStateFlow()

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _isBubbleHiddenForCapture = MutableStateFlow(false)
    val isBubbleHiddenForCapture: StateFlow<Boolean> = _isBubbleHiddenForCapture.asStateFlow()

    private val grantLock = Any()
    private val pendingGrants = ConcurrentHashMap<String, PendingCaptureGrant>()
    private val croppedArtifacts = ConcurrentHashMap<String, StoredCircleArtifact>()

    fun updateState(newState: CircleProjectionState) {
        _state.value = newState
        _isArmed.value = newState == CircleProjectionState.ARMED ||
            newState == CircleProjectionState.SELECTING ||
            newState == CircleProjectionState.CAPTURING
    }

    fun markBubbleHiddenForCapture(hidden: Boolean) {
        _isBubbleHiddenForCapture.value = hidden
    }

    /**
     * Clears any stale pending grants and stores a fresh single-use consent grant keyed by UUID.
     */
    fun storeSingleUseGrant(resultCode: Int, data: Intent): String {
        val grantId = UUID.randomUUID().toString()
        synchronized(grantLock) {
            // Ensure stale consent tokens can never accumulate or be reused.
            pendingGrants.clear()
            pendingGrants[grantId] = PendingCaptureGrant(
                resultCode = resultCode,
                resultData = data
            )
        }
        return grantId
    }

    /**
     * Atomically consumes a single-use grant and clears any remaining grants so subsequent
     * calls return null and spoofed non-blank probes fail closed.
     */
    fun consumeSingleUseGrant(grantId: String): PendingCaptureGrant? {
        if (grantId.isBlank()) return null
        synchronized(grantLock) {
            val grant = pendingGrants.remove(grantId)
            pendingGrants.clear()
            return grant
        }
    }

    fun clearPendingGrants() {
        synchronized(grantLock) {
            pendingGrants.clear()
        }
    }

    /**
     * Registers a locally cropped image artifact under an ephemeral UUID capture token.
     */
    fun registerCroppedArtifact(
        file: File,
        mimeType: String,
        width: Int,
        height: Int,
        byteSize: Long
    ): String {
        val captureToken = UUID.randomUUID().toString()
        croppedArtifacts[captureToken] = StoredCircleArtifact(
            file = file,
            mimeType = mimeType,
            width = width,
            height = height,
            byteSize = byteSize
        )
        return captureToken
    }

    /**
     * Atomically removes and returns the cropped artifact for [captureToken], or null if already consumed.
     */
    fun consumeCroppedArtifact(captureToken: String): StoredCircleArtifact? {
        if (captureToken.isBlank()) return null
        return croppedArtifacts.remove(captureToken)
    }

    fun peekCroppedArtifact(captureToken: String): StoredCircleArtifact? {
        if (captureToken.isBlank()) return null
        return croppedArtifacts[captureToken]
    }

    fun clearAll() {
        synchronized(grantLock) {
            pendingGrants.clear()
        }
        croppedArtifacts.clear()
        _isBubbleHiddenForCapture.value = false
        _isArmed.value = false
        _state.value = CircleProjectionState.IDLE
    }
}
