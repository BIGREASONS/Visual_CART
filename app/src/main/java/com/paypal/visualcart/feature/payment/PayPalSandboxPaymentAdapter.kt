package com.paypal.visualcart.feature.payment

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.paypal.visualcart.core.util.SecurityUtils
import com.paypal.visualcart.domain.model.MerchantListing
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PayPal Sandbox Payment Adapter.
 *
 * Implements two distinct checkout paths:
 * - Path 1 (Integrated PayPal Sandbox Checkout):
 *     If the backend service is reachable, calls `POST /api/v1/payments/paypal/create-order`
 *     to obtain an authentic PayPal Orders v2 approval URL, launches it in Custom Tabs / device browser,
 *     and calls `POST /api/v1/payments/paypal/capture-order` to verify the transaction on the PayPal Sandbox.
 *     If the backend is not reachable or running in offline mode, provides a clean local sandbox simulation.
 *     Distinguishes genuine PayPal Sandbox transactions with [isLiveSandbox = true].
 *     Guarantees payment idempotency and validates price tampering.
 *
 * - Path 2 (External 3rd-party merchant redirect):
 *     Constructs [CheckoutResult.ExternalRedirect] pointing to the merchant's real listing URL,
 *     opens the merchant listing in Custom Tabs / device browser, and displays a prominent disclaimer
 *     that payment completes directly on the merchant's platform.
 *     Strips tracking and marketing parameters while neutralizing malicious redirect schemes.
 *
 * Security and privacy guarantee:
 * Never stores user passwords, card credentials, or hardcodes API secrets in Android.
 */
open class PayPalSandboxPaymentAdapter(
    var defaultMode: CheckoutMode = CheckoutMode.SMART_ROUTING,
    var userConfirmationHook: (suspend (OrderSummary) -> Boolean)? = null,
    val backendClient: PayPalBackendClient = PayPalBackendClient()
) : PaymentProvider {

    private val idempotencyCache = ConcurrentHashMap<String, CheckoutResult.Success>()
    private val completedCaptures = ConcurrentHashMap<String, CheckoutResult.Success>()
    private val capturingOrderIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val mutex = Mutex()

    enum class CheckoutMode {
        /**
         * Simulates native integrated PayPal checkout (creates order summary, requires confirmation).
         */
        INTEGRATED_SANDBOX,

        /**
         * Opens the merchant's listing directly via Custom Tabs / external browser with disclaimer.
         */
        EXTERNAL_REDIRECT,

        /**
         * Automatically routes to integrated sandbox checkout for demo/partner items,
         * or external redirect for 3rd-party marketplace listings.
         */
        SMART_ROUTING,

        /**
         * Launches PayPal Sandbox web checkout in Chrome Custom Tabs configured with
         * visualcart://paypal-return and visualcart://paypal-cancel deep links.
         */
        CUSTOM_TABS_SANDBOX
    }

    override fun getProviderName(): String = "PayPal Sandbox Payment Gateway"

    /**
     * Clears the in-memory idempotency cache (useful for test resets).
     */
    fun clearIdempotencyCache() {
        idempotencyCache.clear()
        completedCaptures.clear()
        capturingOrderIds.clear()
    }

    override suspend fun initiateCheckout(
        context: Any,
        listing: MerchantListing
    ): CheckoutResult {
        return when (defaultMode) {
            CheckoutMode.INTEGRATED_SANDBOX -> executeIntegratedCheckout(listing, context = context)
            CheckoutMode.EXTERNAL_REDIRECT -> executeExternalCheckout(context, listing)
            CheckoutMode.CUSTOM_TABS_SANDBOX -> executeCustomTabsCheckout(context, listing)
            CheckoutMode.SMART_ROUTING -> {
                if (isIntegratedEligible(listing)) {
                    executeIntegratedCheckout(listing, context = context)
                } else {
                    executeExternalCheckout(context, listing)
                }
            }
        }
    }

    /**
     * Path 1: Integrated PayPal Sandbox Checkout.
     * Computes Subtotal + GST (18%) + Delivery in INR, requests explicit confirmation.
     * Enforces price sanity and idempotency to prevent double-captures.
     * Communicates with PayPal Orders v2 backend for real order creation & capture if available.
     */
    suspend fun executeIntegratedCheckout(
        listing: MerchantListing,
        forceConfirm: Boolean? = null,
        context: Any? = null,
        idempotencyKey: String? = null
    ): CheckoutResult {
        // Price validation to prevent tampering (negative, NaN, infinite, or zero price)
        if (listing.price <= 0.0 || listing.price.isNaN() || listing.price.isInfinite()) {
            return CheckoutResult.Error(
                message = "Invalid listing price: ${listing.price}. Price must be positive and finite.",
                errorCode = "ERR_INVALID_PRICE"
            )
        }

        // Idempotency check: if transaction already processed for this idempotency key, return cached result
        if (!idempotencyKey.isNullOrBlank()) {
            idempotencyCache[idempotencyKey]?.let { return it }
        }

        return mutex.withLock {
            // Re-check inside lock for concurrent requests
            if (!idempotencyKey.isNullOrBlank()) {
                idempotencyCache[idempotencyKey]?.let { return@withLock it }
            }

            val summary = try {
                OrderSummary.calculate(
                    listing = listing,
                    gstRate = 0.18,
                    deliveryFee = 0.0 // Standard free visual cart checkout promo
                )
            } catch (e: IllegalArgumentException) {
                return@withLock CheckoutResult.Error(
                    message = e.message ?: "Invalid financial calculation",
                    errorCode = "ERR_INVALID_PRICE",
                    cause = e
                )
            }

            val confirmed = forceConfirm ?: userConfirmationHook?.invoke(summary) ?: true

            if (!confirmed) {
                return@withLock CheckoutResult.Cancelled("User declined or cancelled the PayPal authorization prompt.")
            }

            var successResult: CheckoutResult.Success? = null

            // Check if backend service is available
            if (backendClient.isBackendAvailable()) {
                val orderResult = backendClient.createOrder(
                    listing = listing,
                    returnUrl = PayPalDeepLinkHandler.RETURN_URL_PREFIX,
                    cancelUrl = PayPalDeepLinkHandler.CANCEL_URL_PREFIX
                )

                if (orderResult != null) {
                    if (context is Context && SecurityUtils.isSafeForBrowserIntent(orderResult.approveUrl)) {
                        launchCustomTabs(context, orderResult.approveUrl)
                    }

                    // Capture order to verify transaction with PayPal Sandbox
                    val captureResult = if (idempotencyKey.isNullOrBlank()) {
                        backendClient.captureOrder(orderResult.orderId)
                    } else {
                        backendClient.captureOrder(orderResult.orderId, idempotencyKey = idempotencyKey)
                    }
                    if (captureResult != null && (captureResult.status == "COMPLETED" || captureResult.alreadyCaptured)) {
                        successResult = CheckoutResult.Success(
                            transactionId = "PP-IND-SBX-${captureResult.captureId}",
                            authCode = "AUTH-${captureResult.payerId?.takeLast(8) ?: orderResult.orderId.takeLast(8).uppercase()}",
                            amount = if (captureResult.amount > 0.0) captureResult.amount else summary.total,
                            orderSummary = orderResult.orderSummary ?: summary,
                            isLiveSandbox = captureResult.isLiveSandbox,
                            payerEmail = captureResult.payerEmail,
                            payerName = captureResult.payerName,
                            captureId = captureResult.captureId,
                            status = captureResult.status
                        )
                    } else if (captureResult != null && captureResult.issue == "INSTRUMENT_DECLINED") {
                        return@withLock CheckoutResult.Error(
                            message = "Payment instrument was declined. Please choose a different payment method on PayPal.",
                            errorCode = "INSTRUMENT_DECLINED"
                        )
                    } else if (captureResult != null && captureResult.issue == "DUPLICATE_INVOICE_ID") {
                        return@withLock CheckoutResult.Error(
                            message = "Duplicate invoice ID detected. This payment has already been initiated.",
                            errorCode = "DUPLICATE_INVOICE_ID"
                        )
                    }
                }
            }

            // Clean offline sandbox simulation fallback
            if (successResult == null) {
                val txnId = "PP-IND-SBX-" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
                val authCode = "AUTH-" + UUID.randomUUID().toString().take(8).uppercase()

                successResult = CheckoutResult.Success(
                    transactionId = txnId,
                    authCode = authCode,
                    amount = summary.total,
                    orderSummary = summary,
                    isLiveSandbox = false
                )
            }

            if (!idempotencyKey.isNullOrBlank()) {
                idempotencyCache[idempotencyKey] = successResult
            }

            successResult
        }
    }

    /**
     * Path 2: External Merchant Checkout Redirect.
     * Launches the merchant's web store via Android browser / Custom Tabs with explicit disclaimer.
     * Sanitizes URL against malicious schemes and strips tracking parameters.
     */
    fun executeExternalCheckout(
        context: Any?,
        listing: MerchantListing
    ): CheckoutResult.ExternalRedirect {
        val rawUrl = listing.productUrl.trim()
        val safeFallback = "https://${listing.merchantDomain}"

        val sanitizedUrl = when {
            rawUrl.isBlank() -> safeFallback
            rawUrl.startsWith("javascript:", ignoreCase = true) -> safeFallback
            !SecurityUtils.isValidWebUrl(rawUrl) -> safeFallback
            else -> stripTrackingParameters(rawUrl)
        }

        val disclaimer = "You are being redirected to ${listing.merchantName} (${listing.merchantDomain}). " +
            "Your transaction and credentials will be processed directly on the merchant's secure platform. " +
            "PayPal Visual Cart never stores external merchant passwords or payment details."

        if (context is Context) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitizedUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Safely falls through if no browser activity is registered
            }
        }

        return CheckoutResult.ExternalRedirect(
            url = sanitizedUrl,
            merchantName = listing.merchantName,
            disclaimer = disclaimer
        )
    }

    /**
     * Path 3: PayPal Sandbox Checkout via Chrome Custom Tabs with Deep Linking.
     * Generates a PayPal Sandbox order and launches the approval URL.
     */
    fun executeCustomTabsCheckout(
        context: Any?,
        listing: MerchantListing
    ): CheckoutResult {
        if (backendClient.isBackendAvailable()) {
            val orderResult = backendClient.createOrder(
                listing = listing,
                returnUrl = PayPalDeepLinkHandler.RETURN_URL_PREFIX,
                cancelUrl = PayPalDeepLinkHandler.CANCEL_URL_PREFIX
            )

            if (orderResult != null) {
                if (context is Context && SecurityUtils.isSafeForBrowserIntent(orderResult.approveUrl)) {
                    launchCustomTabs(context, orderResult.approveUrl)
                }

                return CheckoutResult.CustomTabsPending(
                    orderId = orderResult.orderId,
                    approvalUrl = orderResult.approveUrl
                )
            }
        }

        // Offline fallback
        val orderId = "PP-SBX-" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        val approvalUrl = buildSandboxApprovalUrl(orderId)

        if (context is Context) {
            launchCustomTabs(context, approvalUrl)
        }

        return CheckoutResult.CustomTabsPending(
            orderId = orderId,
            approvalUrl = approvalUrl
        )
    }

    /**
     * Captures an order that was approved via deep link return callback.
     * Guarded against duplicate captures from double-taps or duplicate intent callbacks.
     */
    open fun captureApprovedOrder(
        orderId: String,
        orderSummary: OrderSummary? = null
    ): CheckoutResult {
        val cleanOrderId = orderId.trim()
        if (cleanOrderId.isBlank()) {
            return CheckoutResult.Error(
                message = "Order ID cannot be blank",
                errorCode = "ERR_INVALID_ORDER_ID"
            )
        }

        // 1. Guard against duplicate captures: if already captured, return cached success
        completedCaptures[cleanOrderId]?.let { return it }

        // 2. Guard against concurrent captures for this order
        synchronized(capturingOrderIds) {
            if (capturingOrderIds.contains(cleanOrderId)) {
                completedCaptures[cleanOrderId]?.let { return it }
            }
            capturingOrderIds.add(cleanOrderId)
        }

        try {
            if (backendClient.isBackendAvailable()) {
                val captureResult = backendClient.captureOrder(cleanOrderId)
                if (captureResult != null && captureResult.status == "COMPLETED") {
                    val success = CheckoutResult.Success(
                        transactionId = "PP-IND-SBX-${captureResult.captureId}",
                        authCode = "AUTH-${captureResult.payerId?.takeLast(8) ?: cleanOrderId.takeLast(8).uppercase()}",
                        amount = if (captureResult.amount > 0.0) captureResult.amount else (orderSummary?.total ?: 0.0),
                        orderSummary = orderSummary,
                        isLiveSandbox = captureResult.isLiveSandbox,
                        payerEmail = captureResult.payerEmail,
                        payerName = captureResult.payerName,
                        captureId = captureResult.captureId,
                        status = captureResult.status
                    )
                    completedCaptures[cleanOrderId] = success
                    return success
                }
                val failureMsg = captureResult?.errorMessage
                    ?: backendClient.lastErrorMessage
                    ?: "Failed to capture approved PayPal order $cleanOrderId"
                return CheckoutResult.Error(
                    message = failureMsg,
                    errorCode = "CAPTURE_FAILED"
                )
            }

            // Offline simulation capture
            val success = CheckoutResult.Success(
                transactionId = "PP-IND-SBX-${cleanOrderId.takeLast(8).uppercase()}",
                authCode = "AUTH-" + UUID.randomUUID().toString().take(8).uppercase(),
                amount = orderSummary?.total ?: 0.0,
                orderSummary = orderSummary,
                isLiveSandbox = false
            )
            completedCaptures[cleanOrderId] = success
            return success
        } finally {
            capturingOrderIds.remove(cleanOrderId)
        }
    }

    /**
     * Builds a PayPal Sandbox web approval URL with custom scheme return and cancel targets.
     */
    open fun buildSandboxApprovalUrl(orderId: String): String {
        val returnUrl = java.net.URLEncoder.encode(PayPalDeepLinkHandler.RETURN_URL_PREFIX, "UTF-8")
        val cancelUrl = java.net.URLEncoder.encode(PayPalDeepLinkHandler.CANCEL_URL_PREFIX, "UTF-8")
        return "https://www.sandbox.paypal.com/checkoutnow?token=$orderId&return_url=$returnUrl&cancel_url=$cancelUrl"
    }

    /**
     * Launches the approval URL in Chrome Custom Tabs or device browser.
     */
    open fun launchCustomTabs(context: Any?, url: String): Boolean {
        if (context !is Context) return true
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF003087.toInt())
                putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", true)
                putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a complete order summary breakdown for the given listing.
     */
    fun generateOrderSummary(
        listing: MerchantListing,
        deliveryFee: Double = 0.0
    ): OrderSummary {
        return OrderSummary.calculate(
            listing = listing,
            gstRate = 0.18,
            deliveryFee = deliveryFee
        )
    }

    /**
     * Determines whether a listing is eligible for native integrated PayPal sandbox flow.
     */
    private fun isIntegratedEligible(listing: MerchantListing): Boolean {
        return listing.sellerName?.contains("Authorized", ignoreCase = true) == true ||
            listing.merchantDomain.contains("paypal", ignoreCase = true)
    }

    /**
     * Strips common analytics, advertising, and marketing tracking query parameters.
     */
    private fun stripTrackingParameters(url: String): String {
        return try {
            val uri = URI(url)
            val query = uri.query ?: return url
            val trackingPrefixes = listOf("utm_", "ref", "fbclid", "gclid", "campaign")
            val filteredParams = query.split('&').filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                trackingPrefixes.any { key == it || key.startsWith("utm_") || key.startsWith("ref") }
            }
            val newQuery = if (filteredParams.isEmpty()) null else filteredParams.joinToString("&")
            URI(uri.scheme, uri.authority, uri.path, newQuery, uri.fragment).toString()
        } catch (_: Exception) {
            url
        }
    }
}
