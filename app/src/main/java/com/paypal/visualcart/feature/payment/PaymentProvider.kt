package com.paypal.visualcart.feature.payment

import com.paypal.visualcart.domain.model.MerchantListing

/**
 * Normalized order financial breakdown in Indian Rupees (INR).
 */
data class OrderSummary(
    val listingId: String,
    val productTitle: String,
    val merchantName: String,
    val subtotal: Double,
    val gst: Double,
    val deliveryFee: Double,
    val total: Double,
    val currency: String = "INR"
) {
    val formattedSubtotal: String
        get() = formatInr(subtotal)

    val formattedGst: String
        get() = formatInr(gst)

    val formattedDelivery: String
        get() = if (deliveryFee == 0.0) "FREE" else formatInr(deliveryFee)

    val formattedTotal: String
        get() = formatInr(total)

    companion object {
        fun calculate(
            listing: MerchantListing,
            gstRate: Double = 0.18,
            deliveryFee: Double = 0.0
        ): OrderSummary {
            require(listing.price > 0.0 && listing.price.isFinite()) {
                "Invalid listing price: ${listing.price}. Price must be positive and finite."
            }
            require(gstRate in 0.0..1.0 && gstRate.isFinite()) {
                "Invalid GST rate: $gstRate. Rate must be between 0.0 and 1.0."
            }
            require(deliveryFee >= 0.0 && deliveryFee.isFinite()) {
                "Invalid delivery fee: $deliveryFee. Delivery fee cannot be negative."
            }

            val subtotal = listing.price
            val gst = Math.round(subtotal * gstRate * 100.0) / 100.0
            val total = Math.round((subtotal + gst + deliveryFee) * 100.0) / 100.0
            return OrderSummary(
                listingId = listing.id,
                productTitle = listing.title,
                merchantName = listing.merchantName,
                subtotal = subtotal,
                gst = gst,
                deliveryFee = deliveryFee,
                total = total,
                currency = listing.currency
            )
        }

        /**
         * Validates financial integrity of [summary] against [expectedListing] to detect tampering.
         */
        fun validateIntegrity(expectedListing: MerchantListing, summary: OrderSummary): Boolean {
            if (summary.listingId != expectedListing.id) return false
            if (Math.abs(summary.subtotal - expectedListing.price) > 0.001) return false
            if (summary.currency != expectedListing.currency) return false
            val expectedTotal = Math.round((summary.subtotal + summary.gst + summary.deliveryFee) * 100.0) / 100.0
            if (Math.abs(summary.total - expectedTotal) > 0.01) return false
            return true
        }

        private fun formatInr(amount: Double): String {
            return "₹%,.2f".format(amount)
        }
    }
}

/**
 * Result returned upon completing or redirecting a checkout flow.
 */
sealed class CheckoutResult {

    /**
     * Successful integrated PayPal sandbox checkout.
     */
    data class Success(
        val transactionId: String,
        val authCode: String,
        val amount: Double,
        val orderSummary: OrderSummary? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val isLiveSandbox: Boolean = false,
        val payerEmail: String? = null,
        val payerName: String? = null,
        val captureId: String? = null,
        val status: String = "COMPLETED"
    ) : CheckoutResult()

    /**
     * External checkout redirect where user completes purchase on merchant's native store.
     */
    data class ExternalRedirect(
        val url: String,
        val merchantName: String = "",
        val disclaimer: String = DEFAULT_DISCLAIMER
    ) : CheckoutResult() {
        companion object {
            const val DEFAULT_DISCLAIMER =
                "You are being redirected to complete your purchase directly on the merchant's platform. " +
                "PayPal Visual Cart never accesses, stores, or handles your external merchant payment credentials."
        }
    }

    /**
     * User explicitly cancelled the checkout authorization.
     */
    data class Cancelled(
        val reason: String = "User cancelled checkout authorization"
    ) : CheckoutResult()

    /**
     * PayPal Sandbox web authorization launched via Chrome Custom Tabs.
     * Completes asynchronously when Android receives visualcart://paypal-return or visualcart://paypal-cancel.
     */
    data class CustomTabsPending(
        val orderId: String,
        val approvalUrl: String
    ) : CheckoutResult()

    /**
     * Checkout operation failed or encountered an error.
     */
    data class Error(
        val message: String,
        val errorCode: String? = null,
        val cause: Throwable? = null
    ) : CheckoutResult()
}

/**
 * Abstraction for payment and checkout processing.
 */
interface PaymentProvider {

    /**
     * Initiates checkout for the given [listing].
     * [context] can be an Android Context / Activity for launching Custom Tabs / UI flows,
     * or a mock/testing context.
     */
    suspend fun initiateCheckout(context: Any, listing: MerchantListing): CheckoutResult

    /**
     * Returns the display name of this payment provider (e.g. "PayPal Sandbox Payment Adapter").
     */
    fun getProviderName(): String
}
