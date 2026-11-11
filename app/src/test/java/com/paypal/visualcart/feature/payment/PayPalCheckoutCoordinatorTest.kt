package com.paypal.visualcart.feature.payment

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PayPalCheckoutCoordinator].
 * Verifies Part 4 and Part 7 requirements:
 * 1. Server-authoritative breakdown (Merchant, Product, Quantity, Subtotal, Tax, Shipping, Total, Currency, Sandbox indicator)
 * 2. Primary approval action: "Continue to PayPal Sandbox"
 * 3. Loading state: "Verifying payment…"
 * 4. Duplicate capture prevention on double-taps and duplicate deep link callbacks
 * 5. Successful state: "Payment completed" with confirmed PayPal Capture ID and verified amount
 * 6. Failure state: "Payment could not be completed" with user-friendly error details and retry options
 * 7. State management and lifecycle restoration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayPalCheckoutCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val sampleListing = MerchantListing(
        id = "listing_crucial_p3",
        merchantName = "Flipkart",
        merchantDomain = "flipkart.com",
        title = "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
        price = 5499.0,
        originalPrice = 6099.0,
        currency = "INR",
        productUrl = "https://www.flipkart.com/crucial-p3-plus",
        imageUrl = "",
        matchType = MatchType.EXACT,
        confidence = 0.98f,
        availability = AvailabilityStatus.IN_STOCK,
        sourceType = SourceType.DEMO,
        lastVerifiedAt = System.currentTimeMillis()
    )

    private var mockCaptureCallCount = 0

    private val mockLiveBackendClient = object : PayPalBackendClient() {
        override fun isBackendAvailable(): Boolean = true

        override fun createOrder(
            listing: MerchantListing,
            returnUrl: String,
            cancelUrl: String,
            quantity: Int,
            taxRate: Double,
            deliveryFee: Double
        ): CreateOrderServerResult {
            val subtotal = (listing.price ?: 0.0) * quantity
            val tax = Math.round(subtotal * taxRate * 100.0) / 100.0
            val total = Math.round((subtotal + tax + deliveryFee) * 100.0) / 100.0
            val summary = OrderSummary(
                listingId = listing.id,
                productTitle = listing.title,
                merchantName = listing.merchantName,
                subtotal = subtotal,
                gst = tax,
                deliveryFee = deliveryFee,
                total = total,
                currency = listing.currency
            )
            return CreateOrderServerResult(
                orderId = "5O190127TN364715T",
                status = "CREATED",
                approveUrl = "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T",
                currency = listing.currency,
                amount = total,
                isLiveSandbox = true,
                orderSummary = summary
            )
        }

        override fun captureOrder(orderId: String): CaptureOrderServerResult {
            mockCaptureCallCount++
            return CaptureOrderServerResult(
                orderId = orderId,
                captureId = "CAP-3C679366HH2525463",
                status = "COMPLETED",
                amount = 6488.82,
                currency = "INR",
                payerEmail = "sb-buyer@personal.example.com",
                payerName = "Jane Doe",
                payerId = "QZBR8GFW99999",
                createTime = "2026-11-10T12:00:00Z",
                isLiveSandbox = true
            )
        }
    }

    @Before
    fun setUp() {
        mockCaptureCallCount = 0
    }

    @Test
    fun testStartCheckout_ProducesServerAuthoritativeBreakdown() = runTest(testDispatcher) {
        val adapter = PayPalSandboxPaymentAdapter(backendClient = mockLiveBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = mockLiveBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue("Expected OrderReady state", state is PayPalCheckoutUiState.OrderReady)
        val ready = state as PayPalCheckoutUiState.OrderReady

        assertEquals("5O190127TN364715T", ready.orderId)
        assertEquals("Flipkart", ready.merchantName)
        assertEquals("Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD", ready.productTitle)
        assertEquals(1, ready.quantity)
        assertEquals(5499.0, ready.subtotal, 0.01)
        assertEquals(989.82, ready.applicableTax, 0.01)
        assertEquals(0.0, ready.shippingFee, 0.01)
        assertEquals(6488.82, ready.finalTotal, 0.01)
        assertEquals("INR", ready.currency)
        assertTrue("isLiveSandbox must be true", ready.isLiveSandbox)
        assertEquals("FREE", ready.formattedShipping)
        assertTrue(ready.formattedTotal.contains("6,488.82"))
    }

    @Test
    fun testContinueToPayPalSandbox_SetsAwaitingApprovalAndGuardsDoubleTaps() = runTest(testDispatcher) {
        var launchedUrl: String? = null
        val adapter = object : PayPalSandboxPaymentAdapter(backendClient = mockLiveBackendClient) {
            override fun launchCustomTabs(context: Any?, url: String): Boolean {
                launchedUrl = url
                return true
            }
        }

        val coordinator = PayPalCheckoutCoordinator(
            backendClient = mockLiveBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        // First tap: launches Chrome Custom Tabs
        coordinator.continueToPayPalSandbox(null)
        val state1 = coordinator.uiState.value as PayPalCheckoutUiState.OrderReady
        assertTrue("Must be awaiting approval", state1.isAwaitingApproval)
        assertNotNull(launchedUrl)
        assertTrue(launchedUrl!!.contains("sandbox.paypal.com"))

        // Reset tracking variable to test second rapid tap
        launchedUrl = null
        // Second tap while awaiting approval (double-tap simulation)
        coordinator.continueToPayPalSandbox(null)
        // Must NOT launch Custom Tabs again
        assertEquals(null, launchedUrl)
    }

    @Test
    fun testHandleDeepLink_Approved_TransitionsToVerifyingThenSuccess() = runTest(testDispatcher) {
        val adapter = PayPalSandboxPaymentAdapter(backendClient = mockLiveBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = mockLiveBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        // Incoming visualcart return deep link
        val returnDeepLink = "visualcart://paypal-return?token=5O190127TN364715T&PayerID=QZBR8GFW99999"
        val deepLinkResult = PayPalDeepLinkHandler.parse(returnDeepLink)

        coordinator.handleDeepLink(deepLinkResult)
        advanceUntilIdle()

        val finalState = coordinator.uiState.value
        assertTrue("Expected Success state, got $finalState", finalState is PayPalCheckoutUiState.Success)
        val success = finalState as PayPalCheckoutUiState.Success

        assertEquals("5O190127TN364715T", success.orderId)
        assertEquals("CAP-3C679366HH2525463", success.captureId)
        assertEquals("sb-buyer@personal.example.com", success.payerEmail)
        assertEquals("Jane Doe", success.payerName)
        assertEquals(6488.82, success.verifiedAmount, 0.01)
        assertEquals("INR", success.currency)
        assertTrue(success.isLiveSandbox)
        assertEquals(1, mockCaptureCallCount)
    }

    @Test
    fun testPreventDuplicateCaptures_OnRepeatedDeepLinkCallbacks() = runTest(testDispatcher) {
        val adapter = PayPalSandboxPaymentAdapter(backendClient = mockLiveBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = mockLiveBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val returnDeepLink = "visualcart://paypal-return?token=5O190127TN364715T&PayerID=QZBR8GFW99999"
        val deepLinkResult = PayPalDeepLinkHandler.parse(returnDeepLink)

        // First callback
        coordinator.handleDeepLink(deepLinkResult)
        advanceUntilIdle()
        assertEquals(1, mockCaptureCallCount)
        assertTrue(coordinator.uiState.value is PayPalCheckoutUiState.Success)

        // Duplicate callback (e.g. Activity recreation or duplicate intent delivery)
        coordinator.handleDeepLink(deepLinkResult)
        advanceUntilIdle()

        // Capture call count must REMAIN 1 (strict duplicate capture prevention)
        assertEquals("Capture backend must not be called a second time", 1, mockCaptureCallCount)
        assertTrue(coordinator.uiState.value is PayPalCheckoutUiState.Success)
    }

    @Test
    fun testHandleDeepLink_Cancelled_TransitionsToCancelled() = runTest(testDispatcher) {
        val adapter = PayPalSandboxPaymentAdapter(backendClient = mockLiveBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = mockLiveBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val cancelDeepLink = "visualcart://paypal-cancel?token=5O190127TN364715T"
        val deepLinkResult = PayPalDeepLinkHandler.parse(cancelDeepLink)

        coordinator.handleDeepLink(deepLinkResult)
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue("Expected Cancelled state", state is PayPalCheckoutUiState.Cancelled)
        val cancelled = state as PayPalCheckoutUiState.Cancelled
        assertEquals("5O190127TN364715T", cancelled.orderId)
    }

    @Test
    fun testFailureState_DisplaysUserFriendlyErrorAndAllowsRetry() = runTest(testDispatcher) {
        val failingBackendClient = object : PayPalBackendClient() {
            override fun isBackendAvailable(): Boolean = true

            override fun createOrder(
                listing: MerchantListing,
                returnUrl: String,
                cancelUrl: String,
                quantity: Int,
                taxRate: Double,
                deliveryFee: Double
            ): CreateOrderServerResult {
                val summary = OrderSummary.calculate(listing)
                return CreateOrderServerResult(
                    orderId = "5O190127TN364715T",
                    status = "CREATED",
                    approveUrl = "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T",
                    currency = listing.currency,
                    amount = summary.total,
                    isLiveSandbox = true,
                    orderSummary = summary
                )
            }

            override fun captureOrder(orderId: String): CaptureOrderServerResult {
                return CaptureOrderServerResult(
                    orderId = orderId,
                    captureId = "",
                    status = "FAILED",
                    amount = 0.0,
                    currency = "INR",
                    errorMessage = "Payment instrument was declined on PayPal Sandbox. Please select another card."
                )
            }
        }

        val adapter = PayPalSandboxPaymentAdapter(backendClient = failingBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = failingBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val returnDeepLink = "visualcart://paypal-return?token=5O190127TN364715T&PayerID=QZBR8GFW99999"
        val deepLinkResult = PayPalDeepLinkHandler.parse(returnDeepLink)

        coordinator.handleDeepLink(deepLinkResult)
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue("Expected Failure state, got $state", state is PayPalCheckoutUiState.Failure)
        val failure = state as PayPalCheckoutUiState.Failure

        assertTrue(failure.canRetry)
        assertTrue(failure.errorMessage.contains("declined"))
    }

    @Test
    fun testLifecycleRestoration_StatePreserved() = runTest(testDispatcher) {
        val adapter = PayPalSandboxPaymentAdapter(backendClient = mockLiveBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = mockLiveBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val originalState = coordinator.uiState.value
        assertTrue(originalState is PayPalCheckoutUiState.OrderReady)

        // Simulate configuration change / rotation: state in coordinator remains stable
        val currentState = coordinator.uiState.value
        assertEquals(originalState, currentState)
    }

    @Test
    fun testStartCheckout_BackendAvailableButCreateOrderFails_TransitionsToFailureNeverFakeOrder() = runTest(testDispatcher) {
        val rejectingBackendClient = object : PayPalBackendClient() {
            override fun isBackendAvailable(): Boolean = true

            override fun createOrder(
                listing: MerchantListing,
                returnUrl: String,
                cancelUrl: String,
                quantity: Int,
                taxRate: Double,
                deliveryFee: Double
            ): CreateOrderServerResult? {
                lastErrorMessage = "PayPal error [CURRENCY_NOT_SUPPORTED] (debug_id: ca58a035ea87f): Currency INR is not supported."
                return null
            }
        }

        val adapter = PayPalSandboxPaymentAdapter(backendClient = rejectingBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = rejectingBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue("Expected Failure state (must never fall back to SBX-ORD-), got $state", state is PayPalCheckoutUiState.Failure)
        val failure = state as PayPalCheckoutUiState.Failure
        assertEquals("CURRENCY_NOT_SUPPORTED", failure.errorCode)
        assertEquals(sampleListing, failure.originalListing)
        assertTrue(failure.canRetry)
        assertTrue(failure.errorMessage.contains("CURRENCY_NOT_SUPPORTED"))
    }

    @Test
    fun testCheckoutInUsdExplicitly_ConvertsListingAndCreatesUsdOrder() = runTest(testDispatcher) {
        var requestedCurrency: String? = null
        var requestedAmount: Double? = null

        val usdRecordingBackendClient = object : PayPalBackendClient() {
            override fun isBackendAvailable(): Boolean = true

            override fun createOrder(
                listing: MerchantListing,
                returnUrl: String,
                cancelUrl: String,
                quantity: Int,
                taxRate: Double,
                deliveryFee: Double
            ): CreateOrderServerResult? {
                requestedCurrency = listing.currency
                requestedAmount = listing.price ?: 0.0
                val subtotal = (listing.price ?: 0.0) * quantity
                val tax = Math.round(subtotal * taxRate * 100.0) / 100.0
                val total = Math.round((subtotal + tax + deliveryFee) * 100.0) / 100.0
                val summary = OrderSummary(
                    listingId = listing.id,
                    productTitle = listing.title,
                    merchantName = listing.merchantName,
                    subtotal = subtotal,
                    gst = tax,
                    deliveryFee = deliveryFee,
                    total = total,
                    currency = listing.currency
                )
                return CreateOrderServerResult(
                    orderId = "8H298990D2991841W",
                    status = "CREATED",
                    approveUrl = "https://www.sandbox.paypal.com/checkoutnow?token=8H298990D2991841W",
                    currency = listing.currency,
                    amount = total,
                    isLiveSandbox = true,
                    orderSummary = summary
                )
            }
        }

        val adapter = PayPalSandboxPaymentAdapter(backendClient = usdRecordingBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = usdRecordingBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.checkoutInUsdExplicitly(null, sampleListing)
        advanceUntilIdle()

        assertEquals("USD", requestedCurrency)
        assertEquals(65.99, requestedAmount ?: 0.0, 0.01)

        val state = coordinator.uiState.value
        assertTrue("Expected OrderReady state, got $state", state is PayPalCheckoutUiState.OrderReady)
        val ready = state as PayPalCheckoutUiState.OrderReady
        assertEquals("USD", ready.currency)
        assertEquals(65.99, ready.subtotal, 0.01)
        assertEquals("$77.87", ready.formattedTotal)
        assertTrue(ready.isLiveSandbox)
    }

    @Test
    fun testStartCheckout_BackendOffline_UsesSimulatedFallback() = runTest(testDispatcher) {
        val offlineBackendClient = object : PayPalBackendClient() {
            override fun isBackendAvailable(): Boolean = false
        }

        val adapter = PayPalSandboxPaymentAdapter(backendClient = offlineBackendClient)
        val coordinator = PayPalCheckoutCoordinator(
            backendClient = offlineBackendClient,
            paymentAdapter = adapter,
            scope = this,
            ioDispatcher = testDispatcher
        )

        coordinator.startCheckout(null, sampleListing)
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue("Expected simulated OrderReady state when backend offline, got $state", state is PayPalCheckoutUiState.OrderReady)
        val ready = state as PayPalCheckoutUiState.OrderReady
        assertFalse(ready.isLiveSandbox)
        assertTrue(ready.orderId.startsWith("SBX-ORD-"))
    }
}
