package com.paypal.visualcart.core.capture

import android.net.Uri
import com.paypal.visualcart.ui.matching.DetectedProductMatchResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Collections
import java.util.UUID

sealed class CaptureIntentEvent(
    val eventId: String = UUID.randomUUID().toString()
) {
    data class SharedUrl(val url: String) : CaptureIntentEvent()
    data class SharedImage(val uri: Uri) : CaptureIntentEvent()
    data class TriggerCapture(val captureType: String) : CaptureIntentEvent()
    data class ShowProductMatch(val result: DetectedProductMatchResult) : CaptureIntentEvent()
    data object OpenCart : CaptureIntentEvent()
    data object OpenSettings : CaptureIntentEvent()
    data class PayPalCheckoutApproved(val orderId: String, val payerId: String) : CaptureIntentEvent()
    data class PayPalCheckoutCancelled(val orderId: String?) : CaptureIntentEvent()
}

object CaptureEventBus {
    private val _events = MutableSharedFlow<CaptureIntentEvent>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val events = _events.asSharedFlow()

    private val consumedIds = Collections.synchronizedSet(HashSet<String>())

    fun emit(event: CaptureIntentEvent) {
        _events.tryEmit(event)
    }

    /**
     * Marks an event as handled so replay=1 recovers cold-start intents without
     * re-triggering on configuration changes or recomposition.
     */
    fun markConsumed(eventId: String): Boolean {
        return consumedIds.add(eventId)
    }
}
