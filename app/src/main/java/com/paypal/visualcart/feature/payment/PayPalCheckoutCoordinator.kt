package com.paypal.visualcart.feature.payment

import android.content.Context
import android.util.Log
import com.paypal.visualcart.domain.model.MerchantListing
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI State representation for the PayPal Checkout Sheet & Lifecycle.
 */
sealed class PayPalCheckoutUiState {
    data object Idle : PayPalCheckoutUiState()

    data class Initializing(
        val productTitle: String,
        val merchantName: String
    ) : PayPalCheckoutUiState()

    data class OrderReady(
        val orderId: String,
        val approvalUrl: String,
        val merchantName: String,
        val productTitle: String,
        val quantity: Int = 1,
        val subtotal: Double,
        val applicableTax: Double,
        val shippingFee: Double,
        val finalTotal: Double,
        val currency: String = "INR",
        val isLiveSandbox: Boolean = false,
        val isAwaitingApproval: Boolean = false,
        val originalListing: MerchantListing? = null
    ) : PayPalCheckoutUiState() {
        val formattedSubtotal: String
            get() = PayPalCheckoutCoordinator.formatAmount(subtotal, currency)
        val formattedTax: String
            get() = PayPalCheckoutCoordinator.formatAmount(applicableTax, currency)
        val formattedShipping: String
            get() = if (shippingFee == 0.0) "FREE" else PayPalCheckoutCoordinator.formatAmount(shippingFee, currency)
        val formattedTotal: String
            get() = PayPalCheckoutCoordinator.formatAmount(finalTotal, currency)
    }

    data class Verifying(
        val orderId: String,
        val payerId: String? = null,
        val message: String = "Verifying payment…"
    ) : PayPalCheckoutUiState()

    data class Success(
        val orderId: String,
        val captureId: String,
        val transactionId: String,
        val verifiedAmount: Double,
        val currency: String,
        val payerEmail: String? = null,
        val payerName: String? = null,
        val isLiveSandbox: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : PayPalCheckoutUiState() {
        val formattedAmount: String
            get() = PayPalCheckoutCoordinator.formatAmount(verifiedAmount, currency)
    }

    data class Failure(
        val orderId: String? = null,
        val errorMessage: String,
        val errorCode: String? = null,
        val canRetry: Boolean = true,
        val lastOrderReady: OrderReady? = null,
        val originalListing: MerchantListing? = null
    ) : PayPalCheckoutUiState()

    data class Cancelled(
        val orderId: String?,
        val message: String = "PayPal Checkout was cancelled.",
        val originalListing: MerchantListing? = null
    ) : PayPalCheckoutUiState()
}

/**
 * PayPalCheckoutCoordinator:
 * Coordinates the checkout lifecycle across Android configuration changes and process recreations.
 * Strictly prevents duplicate capture actions from rapid double-taps or duplicate intent callbacks.
 */
class PayPalCheckoutCoordinator(
    private val backendClient: PayPalBackendClient = PayPalBackendClient(),
    private val paymentAdapter: PayPalSandboxPaymentAdapter = PayPalSandboxPaymentAdapter(backendClient = backendClient),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ioDispatcher: CoroutineDispatcher? = null
) {
    private val workDispatcher: CoroutineDispatcher = ioDispatcher
        ?: scope.coroutineContext[CoroutineDispatcher]?.takeIf { it !is kotlinx.coroutines.MainCoroutineDispatcher }
        ?: Dispatchers.IO
    private val _uiState = MutableStateFlow<PayPalCheckoutUiState>(PayPalCheckoutUiState.Idle)
    val uiState: StateFlow<PayPalCheckoutUiState> = _uiState.asStateFlow()

    private val captureMutex = Mutex()
    private val completedCaptures = ConcurrentHashMap<String, PayPalCheckoutUiState.Success>()
    private val capturingOrders = Collections.synchronizedSet(HashSet<String>())

    private var activeListing: MerchantListing? = null
    private var lastOrderReadyState: PayPalCheckoutUiState.OrderReady? = null

    companion object {
        private const val TAG = "PayPalCheckout"
        const val DEFAULT_INR_TO_USD_RATE = 0.012
        val instance: PayPalCheckoutCoordinator by lazy { PayPalCheckoutCoordinator() }

        fun formatAmount(amount: Double, currency: String): String {
            return when {
                currency.equals("INR", ignoreCase = true) -> "₹%,.2f".format(Locale.US, amount)
                currency.equals("USD", ignoreCase = true) -> "$%,.2f".format(Locale.US, amount)
                else -> "${currency.uppercase(Locale.US)} %,.2f".format(Locale.US, amount)
            }
        }

        private fun logI(tag: String, msg: String) {
            try { Log.i(tag, msg) } catch (_: Throwable) { }
        }

        private fun logW(tag: String, msg: String) {
            try { Log.w(tag, msg) } catch (_: Throwable) { }
        }

        private fun logE(tag: String, msg: String, tr: Throwable? = null) {
            try { Log.e(tag, msg, tr) } catch (_: Throwable) { }
        }

        private fun logD(tag: String, msg: String) {
            try { Log.d(tag, msg) } catch (_: Throwable) { }
        }
    }

    /**
     * Initiates checkout with server-authoritative breakdown.
     */
    fun startCheckout(context: Any? = null, listing: MerchantListing) {
        activeListing = listing
        lastOrderReadyState = null
        val verifiedPrice = listing.price
        if (!listing.hasVerifiedPrice || verifiedPrice == null || verifiedPrice <= 0.0 || !verifiedPrice.isFinite()) {
            _uiState.value = PayPalCheckoutUiState.Failure(
                errorMessage = "Price is unavailable for search links (${listing.merchantName}). Open the merchant page to verify live pricing before PayPal checkout.",
                errorCode = "ERR_PRICE_UNAVAILABLE",
                canRetry = false,
                originalListing = listing
            )
            return
        }
        _uiState.value = PayPalCheckoutUiState.Initializing(
            productTitle = listing.title,
            merchantName = listing.merchantName
        )

        scope.launch(workDispatcher) {
            try {
                val isAvailable = backendClient.isBackendAvailable()
                val orderReady = if (isAvailable) {
                    val orderResult = backendClient.createOrder(
                        listing = listing,
                        returnUrl = PayPalDeepLinkHandler.RETURN_URL_PREFIX,
                        cancelUrl = PayPalDeepLinkHandler.CANCEL_URL_PREFIX
                    )
                    if (orderResult == null) {
                        // Never silently downgrade a live backend rejection/error into simulated SBX-ORD-
                        val rawError = backendClient.lastErrorMessage
                            ?: "Backend failed to create a live PayPal Sandbox order."
                        val issueCode = parseIssueCode(rawError)
                        _uiState.value = PayPalCheckoutUiState.Failure(
                            errorMessage = formatCreateOrderError(rawError, issueCode, listing),
                            errorCode = issueCode,
                            canRetry = true,
                            originalListing = listing
                        )
                        return@launch
                    }

                    val summary = orderResult.orderSummary
                    val subtotal = summary?.subtotal ?: verifiedPrice
                    val tax = summary?.gst ?: (subtotal * 0.18)
                    val delivery = summary?.deliveryFee ?: 0.0
                    val total = summary?.total ?: (subtotal + tax + delivery)

                    PayPalCheckoutUiState.OrderReady(
                        orderId = orderResult.orderId,
                        approvalUrl = orderResult.approveUrl.ifBlank { paymentAdapter.buildSandboxApprovalUrl(orderResult.orderId) },
                        merchantName = listing.merchantName,
                        productTitle = listing.title,
                        quantity = 1,
                        subtotal = subtotal,
                        applicableTax = tax,
                        shippingFee = delivery,
                        finalTotal = total,
                        currency = orderResult.currency,
                        isLiveSandbox = orderResult.isLiveSandbox,
                        originalListing = listing
                    )
                } else {
                    // Offline sandbox simulation breakdown when backend server is unreachable
                    val summary = paymentAdapter.generateOrderSummary(listing)
                    val simOrderId = "SBX-ORD-" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
                    val approvalUrl = paymentAdapter.buildSandboxApprovalUrl(simOrderId)

                    PayPalCheckoutUiState.OrderReady(
                        orderId = simOrderId,
                        approvalUrl = approvalUrl,
                        merchantName = listing.merchantName,
                        productTitle = listing.title,
                        quantity = 1,
                        subtotal = summary.subtotal,
                        applicableTax = summary.gst,
                        shippingFee = summary.deliveryFee,
                        finalTotal = summary.total,
                        currency = summary.currency,
                        isLiveSandbox = false,
                        originalListing = listing
                    )
                }

                lastOrderReadyState = orderReady
                _uiState.value = orderReady
            } catch (e: Exception) {
                logE(TAG, "Error initiating checkout", e)
                _uiState.value = PayPalCheckoutUiState.Failure(
                    errorMessage = e.message ?: "Failed to initiate PayPal Checkout",
                    canRetry = true,
                    originalListing = listing
                )
            }
        }
    }

    /**
     * Explicitly converts a listing into USD settlement currency after user confirmation.
     * Never called silently on INR rejection.
     */
    fun checkoutInUsdExplicitly(
        context: Any? = null,
        listing: MerchantListing,
        usdRate: Double = DEFAULT_INR_TO_USD_RATE
    ) {
        val verifiedPrice = listing.price
        if (!listing.hasVerifiedPrice || verifiedPrice == null || verifiedPrice <= 0.0 || !verifiedPrice.isFinite()) {
            _uiState.value = PayPalCheckoutUiState.Failure(
                errorMessage = "Price is unavailable for search links (${listing.merchantName}). Open the merchant page to verify live pricing.",
                errorCode = "ERR_PRICE_UNAVAILABLE",
                canRetry = false,
                originalListing = listing
            )
            return
        }
        val convertedPrice = if (listing.currency.equals("USD", ignoreCase = true)) {
            verifiedPrice
        } else {
            max(0.01, Math.round(verifiedPrice * usdRate * 100.0) / 100.0)
        }
        val convertedOriginal = listing.originalPrice?.let {
            if (listing.currency.equals("USD", ignoreCase = true)) it
            else max(0.01, Math.round(it * usdRate * 100.0) / 100.0)
        }
        val usdListing = listing.copy(
            price = convertedPrice,
            originalPrice = convertedOriginal,
            currency = "USD"
        )
        startCheckout(context, usdListing)
    }

    private fun parseIssueCode(rawError: String): String? {
        return when {
            rawError.contains("CURRENCY_NOT_SUPPORTED", ignoreCase = true) -> "CURRENCY_NOT_SUPPORTED"
            rawError.contains("DUPLICATE_INVOICE_ID", ignoreCase = true) -> "DUPLICATE_INVOICE_ID"
            rawError.contains("INSTRUMENT_DECLINED", ignoreCase = true) -> "INSTRUMENT_DECLINED"
            else -> Regex("""\[([A-Z0-9_]{3,})\]""").find(rawError)?.groupValues?.get(1)
        }
    }

    private fun formatCreateOrderError(rawError: String, issueCode: String?, listing: MerchantListing): String {
        return if (issueCode == "CURRENCY_NOT_SUPPORTED") {
            "$rawError — PayPal Sandbox does not support ${listing.currency} for this account. " +
                "Tap \"Checkout in USD\" below to convert explicitly and pay in USD."
        } else {
            rawError
        }
    }

    /**
     * Primary approval button action: "Continue to PayPal Sandbox".
     * Guarded against double-taps.
     */
    fun continueToPayPalSandbox(context: Any? = null) {
        val current = _uiState.value
        if (current !is PayPalCheckoutUiState.OrderReady) return
        if (current.isAwaitingApproval) {
            logD(TAG, "Already awaiting approval, ignoring duplicate tap.")
            return
        }

        _uiState.value = current.copy(isAwaitingApproval = true)
        paymentAdapter.launchCustomTabs(context, current.approvalUrl)
    }

    /**
     * Handles incoming custom deep-link callbacks from Chrome Custom Tabs:
     * - visualcart://paypal-return?token=ORDER_ID&PayerID=PAYER_ID
     * - visualcart://paypal-cancel
     * Strictly idempotent against re-delivery or duplicate callbacks.
     */
    fun handleDeepLink(result: PayPalDeepLinkHandler.Result) {
        when (result) {
            is PayPalDeepLinkHandler.Result.Approved -> {
                executeCapture(result.orderId, result.payerId)
            }
            is PayPalDeepLinkHandler.Result.Cancelled -> {
                logI(TAG, "Checkout cancelled by buyer: orderId=${result.orderId}")
                _uiState.value = PayPalCheckoutUiState.Cancelled(
                    orderId = result.orderId,
                    message = "PayPal Sandbox checkout was cancelled by the user.",
                    originalListing = activeListing
                )
            }
            is PayPalDeepLinkHandler.Result.Invalid -> {
                logW(TAG, "Invalid deep link received: ${result.reason}")
                _uiState.value = PayPalCheckoutUiState.Failure(
                    errorMessage = "Invalid PayPal return parameters: ${result.reason}",
                    canRetry = true,
                    lastOrderReady = lastOrderReadyState,
                    originalListing = activeListing
                )
            }
        }
    }

    private fun executeCapture(orderId: String, payerId: String) {
        val cleanOrderId = orderId.trim()
        if (cleanOrderId.isBlank()) return

        // Idempotency Check 1: Already successfully captured
        completedCaptures[cleanOrderId]?.let { cachedSuccess ->
            logI(TAG, "Order $cleanOrderId was already captured, restoring verified state.")
            _uiState.value = cachedSuccess
            return
        }

        // Idempotency Check 2: Already verifying or capturing
        synchronized(capturingOrders) {
            if (capturingOrders.contains(cleanOrderId)) {
                logW(TAG, "Order $cleanOrderId is already being captured, ignoring duplicate intent.")
                return
            }
            val current = _uiState.value
            if (current is PayPalCheckoutUiState.Verifying && current.orderId == cleanOrderId) {
                logW(TAG, "Order $cleanOrderId already in Verifying state, ignoring duplicate intent.")
                return
            }
            capturingOrders.add(cleanOrderId)
        }

        // Transition to server-authoritative loading state: "Verifying payment…"
        _uiState.value = PayPalCheckoutUiState.Verifying(
            orderId = cleanOrderId,
            payerId = payerId,
            message = "Verifying payment…"
        )

        scope.launch(workDispatcher) {
            captureMutex.withLock {
                try {
                    val orderSummary = lastOrderReadyState?.let {
                        OrderSummary(
                            listingId = it.originalListing?.id ?: cleanOrderId,
                            productTitle = it.productTitle,
                            merchantName = it.merchantName,
                            subtotal = it.subtotal,
                            gst = it.applicableTax,
                            deliveryFee = it.shippingFee,
                            total = it.finalTotal,
                            currency = it.currency
                        )
                    }

                    val captureResult = paymentAdapter.captureApprovedOrder(cleanOrderId, orderSummary)

                    when (captureResult) {
                        is CheckoutResult.Success -> {
                            val verifiedTotal = if (captureResult.amount > 0.0) {
                                captureResult.amount
                            } else {
                                lastOrderReadyState?.finalTotal ?: 0.0
                            }

                            val successState = PayPalCheckoutUiState.Success(
                                orderId = cleanOrderId,
                                captureId = captureResult.captureId ?: ("CAP-" + cleanOrderId.takeLast(8).uppercase()),
                                transactionId = captureResult.transactionId,
                                verifiedAmount = verifiedTotal,
                                currency = orderSummary?.currency ?: "INR",
                                payerEmail = captureResult.payerEmail,
                                payerName = captureResult.payerName,
                                isLiveSandbox = captureResult.isLiveSandbox
                            )

                            completedCaptures[cleanOrderId] = successState
                            _uiState.value = successState
                        }
                        is CheckoutResult.Error -> {
                            capturingOrders.remove(cleanOrderId) // Allow retry on failure
                            _uiState.value = PayPalCheckoutUiState.Failure(
                                orderId = cleanOrderId,
                                errorMessage = captureResult.message,
                                errorCode = captureResult.errorCode,
                                canRetry = true,
                                lastOrderReady = lastOrderReadyState,
                                originalListing = activeListing
                            )
                        }
                        is CheckoutResult.Cancelled -> {
                            capturingOrders.remove(cleanOrderId)
                            _uiState.value = PayPalCheckoutUiState.Cancelled(
                                orderId = cleanOrderId,
                                message = captureResult.reason,
                                originalListing = activeListing
                            )
                        }
                        else -> {
                            capturingOrders.remove(cleanOrderId)
                            _uiState.value = PayPalCheckoutUiState.Failure(
                                orderId = cleanOrderId,
                                errorMessage = "Unexpected capture result from gateway",
                                canRetry = true,
                                lastOrderReady = lastOrderReadyState,
                                originalListing = activeListing
                            )
                        }
                    }
                } catch (e: Exception) {
                    logE(TAG, "Capture execution exception", e)
                    capturingOrders.remove(cleanOrderId)
                    _uiState.value = PayPalCheckoutUiState.Failure(
                        orderId = cleanOrderId,
                        errorMessage = e.message ?: "Failed to verify and capture payment on PayPal Sandbox",
                        canRetry = true,
                        lastOrderReady = lastOrderReadyState,
                        originalListing = activeListing
                    )
                } finally {
                    capturingOrders.remove(cleanOrderId)
                }
            }
        }
    }

    /**
     * Retries payment following a failure or cancellation.
     */
    fun retry(context: Any? = null) {
        val current = _uiState.value
        when (current) {
            is PayPalCheckoutUiState.Failure -> {
                if (current.orderId != null && current.lastOrderReady != null) {
                    executeCapture(current.orderId, "")
                } else if (current.originalListing != null) {
                    startCheckout(context, current.originalListing)
                } else if (activeListing != null) {
                    startCheckout(context, activeListing!!)
                }
            }
            is PayPalCheckoutUiState.Cancelled -> {
                if (activeListing != null) {
                    startCheckout(context, activeListing!!)
                }
            }
            else -> Unit
        }
    }

    /**
     * Dismisses checkout sheet and resets state to Idle.
     */
    fun dismiss() {
        _uiState.value = PayPalCheckoutUiState.Idle
    }

    /**
     * Resets in-memory idempotency state for test isolation.
     */
    fun resetIdempotency() {
        completedCaptures.clear()
        capturingOrders.clear()
        paymentAdapter.clearIdempotencyCache()
    }
}
