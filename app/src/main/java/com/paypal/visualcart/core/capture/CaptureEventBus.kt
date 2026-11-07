package com.paypal.visualcart.core.capture

import android.net.Uri
import com.paypal.visualcart.ui.matching.DetectedProductMatchResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class CaptureIntentEvent {
    data class SharedUrl(val url: String) : CaptureIntentEvent()
    data class SharedImage(val uri: Uri) : CaptureIntentEvent()
    data class TriggerCapture(val captureType: String) : CaptureIntentEvent()
    data class ShowProductMatch(val result: DetectedProductMatchResult) : CaptureIntentEvent()
    data object OpenCart : CaptureIntentEvent()
    data class PayPalCheckoutApproved(val orderId: String, val payerId: String) : CaptureIntentEvent()
    data class PayPalCheckoutCancelled(val orderId: String?) : CaptureIntentEvent()
}

object CaptureEventBus {
    private val _events = MutableSharedFlow<CaptureIntentEvent>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

    fun emit(event: CaptureIntentEvent) {
        _events.tryEmit(event)
    }
}
