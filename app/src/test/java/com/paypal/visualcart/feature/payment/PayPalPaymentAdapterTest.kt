package com.paypal.visualcart.feature.payment

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PayPalPaymentAdapter and OrderSummary calculations.
 * Validates financial breakdowns (Subtotal, GST 18%, Delivery, Total),
 * strict separation between integrated sandbox payments and external merchant redirects,
 * and user cancellation handling.
 */
class PayPalPaymentAdapterTest {

    private val sampleListing = MerchantListing(
        id = "listing_ssd_1",
        merchantName = "Amazon India",
        merchantDomain = "amazon.in",
        title = "Samsung 990 EVO 1TB NVMe SSD",
        price = 10000.0,
        originalPrice = 12000.0,
        currency = "INR",
        productUrl = "https://www.amazon.in/dp/B0CX23VFP2",
        imageUrl = "https://example.com/samsung990.jpg",
        matchType = MatchType.EXACT,
        confidence = 0.95f,
        availability = AvailabilityStatus.IN_STOCK,
        sourceType = SourceType.DEMO,
        lastVerifiedAt = System.currentTimeMillis()
    )

    @Test
    fun testOrderSummaryCalculationWithStandardGst() {
        val summary = OrderSummary.calculate(
            listing = sampleListing,
            gstRate = 0.18,
            deliveryFee = 0.0
        )

        assertEquals(10000.0, summary.subtotal, 0.001)
        assertEquals(1800.0, summary.gst, 0.001)
        assertEquals(0.0, summary.deliveryFee, 0.001)
        assertEquals(11800.0, summary.total, 0.001)
        assertEquals("INR", summary.currency)
        assertEquals("FREE", summary.formattedDelivery)
        assertTrue(summary.formattedTotal.contains("11,800.00"))
    }

    @Test
    fun testOrderSummaryCalculationWithCustomDelivery() {
        val summary = OrderSummary.calculate(
            listing = sampleListing,
            gstRate = 0.18,
            deliveryFee = 250.0
        )

        assertEquals(10000.0, summary.subtotal, 0.001)
        assertEquals(1800.0, summary.gst, 0.001)
        assertEquals(250.0, summary.deliveryFee, 0.001)
        assertEquals(12050.0, summary.total, 0.001)
        assertTrue(summary.formattedDelivery.contains("250.00"))
        assertTrue(summary.formattedTotal.contains("12,050.00"))
    }

    @Test
    fun testIntegratedCheckoutSuccess() = runBlocking {
        val adapter = PayPalSandboxPaymentAdapter(
            defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.INTEGRATED_SANDBOX
        )

        val result = adapter.executeIntegratedCheckout(sampleListing, forceConfirm = true)

        assertTrue("Expected CheckoutResult.Success", result is CheckoutResult.Success)
        val success = result as CheckoutResult.Success
        assertTrue("Transaction ID must follow sandbox convention", success.transactionId.startsWith("PP-IND-SBX-"))
        assertTrue("Auth code must start with AUTH-", success.authCode.startsWith("AUTH-"))
        assertEquals(11800.0, success.amount, 0.001)
        assertNotNull(success.orderSummary)
    }

    @Test
    fun testIntegratedCheckoutCancellation() = runBlocking {
        val adapter = PayPalSandboxPaymentAdapter(
            defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.INTEGRATED_SANDBOX
        )

        val result = adapter.executeIntegratedCheckout(sampleListing, forceConfirm = false)

        assertTrue("Expected CheckoutResult.Cancelled when declined", result is CheckoutResult.Cancelled)
        val cancelled = result as CheckoutResult.Cancelled
        assertTrue(cancelled.reason.contains("declined", ignoreCase = true) || cancelled.reason.contains("cancel", ignoreCase = true))
    }

    @Test
    fun testExternalRedirectCheckoutSeparation() {
        val adapter = PayPalSandboxPaymentAdapter()

        val redirect = adapter.executeExternalCheckout(null, sampleListing)

        assertEquals("https://www.amazon.in/dp/B0CX23VFP2", redirect.url)
        assertEquals("Amazon India", redirect.merchantName)
        assertTrue(
            "Redirect disclaimer must state Visual Cart never stores merchant passwords or credentials",
            redirect.disclaimer.contains("never stores", ignoreCase = true)
        )
    }

    @Test
    fun testSmartRoutingInitiateCheckout() = runBlocking {
        // External marketplace listing routes to external redirect
        val adapter = PayPalSandboxPaymentAdapter(
            defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.EXTERNAL_REDIRECT
        )

        val result = adapter.initiateCheckout(Any(), sampleListing)
        assertTrue("Expected ExternalRedirect in EXTERNAL_REDIRECT mode", result is CheckoutResult.ExternalRedirect)
    }

    @Test
    fun testCustomTabsSandboxCheckout() = runBlocking {
        val adapter = PayPalSandboxPaymentAdapter(
            defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.CUSTOM_TABS_SANDBOX
        )

        val result = adapter.initiateCheckout(Any(), sampleListing)
        assertTrue("Expected CustomTabsPending in CUSTOM_TABS_SANDBOX mode", result is CheckoutResult.CustomTabsPending)
        val pending = result as CheckoutResult.CustomTabsPending
        assertTrue("Order ID must follow sandbox naming", pending.orderId.startsWith("PP-SBX-"))
        assertTrue("Approval URL must contain PayPal sandbox host", pending.approvalUrl.contains("sandbox.paypal.com"))
        assertTrue("Approval URL must include return_url", pending.approvalUrl.contains("return_url="))
        assertTrue("Approval URL must include cancel_url", pending.approvalUrl.contains("cancel_url="))
    }

    @Test
    fun testBuildSandboxApprovalUrlConfiguresDeepLinks() {
        val adapter = PayPalSandboxPaymentAdapter()
        val approvalUrl = adapter.buildSandboxApprovalUrl("ORDER-TEST-123")

        assertTrue(approvalUrl.startsWith("https://www.sandbox.paypal.com/checkoutnow?token=ORDER-TEST-123"))
        assertTrue(approvalUrl.contains("return_url=visualcart%3A%2F%2Fpaypal-return"))
        assertTrue(approvalUrl.contains("cancel_url=visualcart%3A%2F%2Fpaypal-cancel"))
    }
}
