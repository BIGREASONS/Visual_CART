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

class PayPalPaymentTest {

    private val sampleListing = MerchantListing(
        id = "amazon_samsung_990_evo",
        merchantName = "Amazon India",
        merchantDomain = "amazon.in",
        title = "Samsung 990 EVO 1TB NVMe Internal SSD",
        price = 5999.0,
        originalPrice = 9999.0,
        currency = "INR",
        productUrl = "https://www.amazon.in/dp/B0CQ2185T6",
        imageUrl = "https://example.com/image.jpg",
        matchType = MatchType.EXACT,
        confidence = 0.98f,
        availability = AvailabilityStatus.IN_STOCK,
        sourceType = SourceType.DEMO,
        lastVerifiedAt = System.currentTimeMillis(),
        deliveryEstimate = "Next day delivery"
    )

    @Test
    fun testOrderSummaryCalculation() {
        val summary = OrderSummary.calculate(
            listing = sampleListing,
            gstRate = 0.18,
            deliveryFee = 0.0
        )

        assertEquals(5999.0, summary.subtotal, 0.01)
        val expectedGst = Math.round(5999.0 * 0.18 * 100.0) / 100.0
        assertEquals(expectedGst, summary.gst, 0.01) // 1079.82
        assertEquals(0.0, summary.deliveryFee, 0.01)
        assertEquals(5999.0 + expectedGst, summary.total, 0.01) // 7078.82
        assertEquals("FREE", summary.formattedDelivery)
        assertTrue(summary.formattedTotal.contains("7,078"))
    }

    @Test
    fun testPathA_IntegratedSandboxCheckout_Success() {
        runBlocking {
            val adapter = PayPalSandboxPaymentAdapter(
                defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.INTEGRATED_SANDBOX
            )

            val result = adapter.initiateCheckout(
                context = "MockContext",
                listing = sampleListing
            )

            assertTrue("Expected Success, got $result", result is CheckoutResult.Success)
            val success = result as CheckoutResult.Success

            assertTrue(success.transactionId.startsWith("PP-IND-SBX-"))
            assertTrue(success.authCode.startsWith("AUTH-"))
            assertNotNull(success.orderSummary)
            assertEquals(success.orderSummary?.total ?: 0.0, success.amount, 0.01)
        }
    }

    @Test
    fun testPathA_IntegratedSandboxCheckout_UserCancelled() {
        runBlocking {
            val adapter = PayPalSandboxPaymentAdapter(
                defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.INTEGRATED_SANDBOX,
                userConfirmationHook = { _ -> false } // User explicitly rejects confirmation
            )

            val result = adapter.initiateCheckout(
                context = "MockContext",
                listing = sampleListing
            )

            assertTrue("Expected Cancelled, got $result", result is CheckoutResult.Cancelled)
            val cancelled = result as CheckoutResult.Cancelled
            assertTrue(cancelled.reason.contains("declined"))
        }
    }

    @Test
    fun testPathB_ExternalMerchantCheckout_Redirect() {
        runBlocking {
            val adapter = PayPalSandboxPaymentAdapter(
                defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.EXTERNAL_REDIRECT
            )

            val result = adapter.initiateCheckout(
                context = "MockContext",
                listing = sampleListing
            )

            assertTrue("Expected ExternalRedirect, got $result", result is CheckoutResult.ExternalRedirect)
            val redirect = result as CheckoutResult.ExternalRedirect

            assertEquals("https://www.amazon.in/dp/B0CQ2185T6", redirect.url)
            assertEquals("Amazon India", redirect.merchantName)
            assertTrue(redirect.disclaimer.contains("never stores external merchant passwords"))
        }
    }

    @Test
    fun testSmartRouting_MarketplaceRoutesToExternal() {
        runBlocking {
            val adapter = PayPalSandboxPaymentAdapter(
                defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.SMART_ROUTING
            )

            // Amazon India marketplace listing
            val result = adapter.initiateCheckout(
                context = "MockContext",
                listing = sampleListing
            )

            assertTrue(result is CheckoutResult.ExternalRedirect)
        }
    }

    @Test
    fun testProviderName() {
        val adapter = PayPalSandboxPaymentAdapter()
        assertTrue(adapter.getProviderName().contains("PayPal"))
    }
}
