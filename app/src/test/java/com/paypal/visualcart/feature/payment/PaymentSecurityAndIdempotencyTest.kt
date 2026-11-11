package com.paypal.visualcart.feature.payment

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Security unit tests for payment operations:
 * 1. Payment idempotency: prevents double captures and duplicate charges on retries/concurrent requests.
 * 2. Amount tampering prevention: detects and rejects negative, NaN, infinite, or manipulated prices.
 * 3. Financial order integrity verification.
 * 4. External redirect URL sanitization (malicious scheme neutralization).
 */
class PaymentSecurityAndIdempotencyTest {

    private lateinit var adapter: PayPalSandboxPaymentAdapter

    private val validListing = MerchantListing(
        id = "listing_samsung_ssd",
        merchantName = "Amazon India",
        merchantDomain = "amazon.in",
        title = "Samsung 990 EVO 1TB NVMe SSD",
        price = 6000.0,
        originalPrice = 8000.0,
        currency = "INR",
        productUrl = "https://www.amazon.in/dp/B0CX23VFP2?utm_source=ad&ref=deal",
        imageUrl = "https://example.com/samsung.jpg",
        matchType = MatchType.EXACT,
        confidence = 0.98f,
        availability = AvailabilityStatus.IN_STOCK,
        sourceType = SourceType.DEMO,
        lastVerifiedAt = System.currentTimeMillis()
    )

    @Before
    fun setUp() {
        adapter = PayPalSandboxPaymentAdapter(
            defaultMode = PayPalSandboxPaymentAdapter.CheckoutMode.INTEGRATED_SANDBOX
        )
        adapter.clearIdempotencyCache()
    }

    @Test
    fun testPaymentIdempotencyReturnsIdenticalTransactionOnRetry() = runBlocking {
        val idempotencyKey = "req-idempotency-1001-abc"

        // First execution: initial authorization & capture
        val firstResult = adapter.executeIntegratedCheckout(
            listing = validListing,
            forceConfirm = true,
            idempotencyKey = idempotencyKey
        )
        assertTrue("First attempt should succeed", firstResult is CheckoutResult.Success)
        val firstSuccess = firstResult as CheckoutResult.Success

        // Second execution with identical idempotency key: simulating rapid double-tap or network retry
        val secondResult = adapter.executeIntegratedCheckout(
            listing = validListing,
            forceConfirm = true,
            idempotencyKey = idempotencyKey
        )
        assertTrue("Second attempt should return cached Success", secondResult is CheckoutResult.Success)
        val secondSuccess = secondResult as CheckoutResult.Success

        // Must NOT create a second distinct transaction or double capture
        assertEquals("Transaction ID must be identical (no double capture)", firstSuccess.transactionId, secondSuccess.transactionId)
        assertEquals("Auth code must be identical", firstSuccess.authCode, secondSuccess.authCode)
        assertEquals(firstSuccess.amount, secondSuccess.amount, 0.001)
        assertEquals(firstSuccess.timestamp, secondSuccess.timestamp)
    }

    @Test
    fun testDifferentIdempotencyKeysCreateDistinctTransactions() = runBlocking {
        val result1 = adapter.executeIntegratedCheckout(
            listing = validListing,
            forceConfirm = true,
            idempotencyKey = "key-001"
        )
        val result2 = adapter.executeIntegratedCheckout(
            listing = validListing,
            forceConfirm = true,
            idempotencyKey = "key-002"
        )

        assertTrue(result1 is CheckoutResult.Success)
        assertTrue(result2 is CheckoutResult.Success)
        assertNotEquals(
            "Distinct orders must receive distinct transaction IDs",
            (result1 as CheckoutResult.Success).transactionId,
            (result2 as CheckoutResult.Success).transactionId
        )
    }

    @Test
    fun testConcurrentDoubleCapturePrevention() = runBlocking {
        val sharedKey = "concurrent-idempotency-key-999"

        // Launch 5 concurrent checkout requests with the same idempotency key
        val deferredResults = (1..5).map {
            async {
                adapter.executeIntegratedCheckout(
                    listing = validListing,
                    forceConfirm = true,
                    idempotencyKey = sharedKey
                )
            }
        }

        val results = deferredResults.awaitAll()
        assertTrue("All requests should return Success", results.all { it is CheckoutResult.Success })

        val firstTxnId = (results.first() as CheckoutResult.Success).transactionId
        for (res in results) {
            val success = res as CheckoutResult.Success
            assertEquals("All concurrent calls must resolve to the identical transaction ID", firstTxnId, success.transactionId)
        }
    }

    @Test
    fun testNegativePriceRejected() = runBlocking {
        try {
            val tamperedListing = validListing.copy(price = -500.0)
            adapter.executeIntegratedCheckout(tamperedListing, forceConfirm = true)
            org.junit.Assert.fail("Expected negative price to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("positive finite price") == true)
        }
    }

    @Test
    fun testZeroOrNaNPriceRejected() = runBlocking {
        for (badPrice in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                val badListing = validListing.copy(price = badPrice)
                adapter.executeIntegratedCheckout(badListing, forceConfirm = true)
                org.junit.Assert.fail("Expected tampered/invalid price $badPrice to be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("positive finite price") == true)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOrderSummaryThrowsOnNegativePrice() {
        val tampered = validListing.copy(price = -100.0)
        OrderSummary.calculate(tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOrderSummaryThrowsOnInvalidGstRate() {
        OrderSummary.calculate(validListing, gstRate = -0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOrderSummaryThrowsOnNegativeDeliveryFee() {
        OrderSummary.calculate(validListing, deliveryFee = -50.0)
    }

    @Test
    fun testOrderSummaryValidateIntegrityDetectsTampering() {
        val validSummary = OrderSummary.calculate(validListing, gstRate = 0.18, deliveryFee = 0.0)
        assertTrue("Authentic summary must pass integrity check", OrderSummary.validateIntegrity(validListing, validSummary))

        // 1. Tampered subtotal
        val tamperedSubtotal = validSummary.copy(subtotal = 3000.0)
        assertFalse("Tampered subtotal must fail integrity check", OrderSummary.validateIntegrity(validListing, tamperedSubtotal))

        // 2. Tampered total amount (undercharge attack)
        val tamperedTotal = validSummary.copy(total = 100.0)
        assertFalse("Tampered total amount must fail integrity check", OrderSummary.validateIntegrity(validListing, tamperedTotal))

        // 3. Mismatched listing ID (cross-product injection)
        val mismatchedId = validSummary.copy(listingId = "different_listing_id")
        assertFalse("Mismatched listing ID must fail integrity check", OrderSummary.validateIntegrity(validListing, mismatchedId))

        // 4. Mismatched currency (currency manipulation)
        val mismatchedCurrency = validSummary.copy(currency = "USD")
        assertFalse("Mismatched currency must fail integrity check", OrderSummary.validateIntegrity(validListing, mismatchedCurrency))
    }

    @Test
    fun testExternalRedirectUrlSanitizationNeutralizesMaliciousSchemes() {
        val maliciousListing = validListing.copy(
            productUrl = "javascript:alert('malicious_redirect')"
        )

        val redirect = adapter.executeExternalCheckout(null, maliciousListing)

        assertFalse("External redirect URL must never contain javascript scheme", redirect.url.startsWith("javascript:"))
        assertEquals("Must fall back to safe HTTPS merchant domain", "https://amazon.in", redirect.url)
    }

    @Test
    fun testExternalRedirectUrlStripsTrackingParameters() {
        val trackingListing = validListing.copy(
            productUrl = "https://www.amazon.in/dp/B0CX23VFP2?utm_source=facebook&utm_campaign=retargeting&ref=cart_btn"
        )

        val redirect = adapter.executeExternalCheckout(null, trackingListing)

        assertFalse("Redirect URL must strip utm_source", redirect.url.contains("utm_source"))
        assertFalse("Redirect URL must strip utm_campaign", redirect.url.contains("utm_campaign"))
        assertFalse("Redirect URL must strip ref", redirect.url.contains("ref="))
        assertEquals("https://www.amazon.in/dp/B0CX23VFP2", redirect.url)
    }

    @Test
    fun testUnpricedAndDirectSearchUrlListingRejectedWithPriceUnavailable() = runBlocking {
        val unpricedSearchListing = validListing.copy(
            id = "listing_search_only",
            price = null,
            originalPrice = null,
            availability = AvailabilityStatus.UNKNOWN,
            listingOrigin = com.paypal.visualcart.domain.model.ListingOrigin.DIRECT_SEARCH_URL
        )
        assertFalse(unpricedSearchListing.hasVerifiedPrice)

        val result = adapter.executeIntegratedCheckout(unpricedSearchListing, forceConfirm = true)
        assertTrue("Expected CheckoutResult.Error for unpriced search link", result is CheckoutResult.Error)
        val error = result as CheckoutResult.Error
        assertEquals("ERR_PRICE_UNAVAILABLE", error.errorCode)
    }
}
