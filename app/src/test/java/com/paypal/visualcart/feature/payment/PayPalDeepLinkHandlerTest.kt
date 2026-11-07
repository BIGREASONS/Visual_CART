package com.paypal.visualcart.feature.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PayPalDeepLinkHandler].
 * Verifies custom deep-link scheme validation, order ID and payer ID extraction,
 * cancellation parsing, and input sanitization against XSS/injections.
 */
class PayPalDeepLinkHandlerTest {

    @Test
    fun testIsPayPalDeepLinkValidSchemes() {
        val returnUri = "visualcart://paypal-return?token=ORDER_123&PayerID=PAYER_456"
        val cancelUri = "visualcart://paypal-cancel?token=ORDER_123"

        assertTrue("Expected valid return deep link", PayPalDeepLinkHandler.isPayPalDeepLink(returnUri))
        assertTrue("Expected valid cancel deep link", PayPalDeepLinkHandler.isPayPalDeepLink(cancelUri))
    }

    @Test
    fun testIsPayPalDeepLinkRejectsInvalidSchemesAndHosts() {
        val nonVisualCart = "https://www.paypal.com/checkoutnow"
        val wrongHost = "visualcart://unknown-action"
        val maliciousScheme = "javascript:alert(1)"
        val nullUri: String? = null

        assertFalse("HTTP must not match deep link", PayPalDeepLinkHandler.isPayPalDeepLink(nonVisualCart))
        assertFalse("Unknown host must not match", PayPalDeepLinkHandler.isPayPalDeepLink(wrongHost))
        assertFalse("Unsafe scheme must not match", PayPalDeepLinkHandler.isPayPalDeepLink(maliciousScheme))
        assertFalse("Null uri must not match", PayPalDeepLinkHandler.isPayPalDeepLink(nullUri))
    }

    @Test
    fun testParseStandardPayPalReturnUrl() {
        val uri = "visualcart://paypal-return?token=5O190127TN364715T&PayerID=QZ255L4G57X64"
        val result = PayPalDeepLinkHandler.parse(uri)

        assertTrue("Expected Result.Approved", result is PayPalDeepLinkHandler.Result.Approved)
        val approved = result as PayPalDeepLinkHandler.Result.Approved
        assertEquals("5O190127TN364715T", approved.orderId)
        assertEquals("QZ255L4G57X64", approved.payerId)
    }

    @Test
    fun testParseAlternativeParameterNames() {
        // Many PayPal client integrations use order_id and payer_id
        val uri = "visualcart://paypal-return?order_id=ORD-998877&payer_id=PAY-112233"
        val result = PayPalDeepLinkHandler.parse(uri)

        assertTrue("Expected Result.Approved", result is PayPalDeepLinkHandler.Result.Approved)
        val approved = result as PayPalDeepLinkHandler.Result.Approved
        assertEquals("ORD-998877", approved.orderId)
        assertEquals("PAY-112233", approved.payerId)
    }

    @Test
    fun testParsePayPalCancelUrlWithOrderId() {
        val uri = "visualcart://paypal-cancel?token=5O190127TN364715T"
        val result = PayPalDeepLinkHandler.parse(uri)

        assertTrue("Expected Result.Cancelled", result is PayPalDeepLinkHandler.Result.Cancelled)
        val cancelled = result as PayPalDeepLinkHandler.Result.Cancelled
        assertEquals("5O190127TN364715T", cancelled.orderId)
    }

    @Test
    fun testParsePayPalCancelUrlWithoutOrderId() {
        val uri = "visualcart://paypal-cancel"
        val result = PayPalDeepLinkHandler.parse(uri)

        assertTrue("Expected Result.Cancelled", result is PayPalDeepLinkHandler.Result.Cancelled)
        val cancelled = result as PayPalDeepLinkHandler.Result.Cancelled
        assertNull(cancelled.orderId)
    }

    @Test
    fun testParseRejectsMissingParametersOnReturn() {
        val emptyReturn = "visualcart://paypal-return"
        val result = PayPalDeepLinkHandler.parse(emptyReturn)

        assertTrue("Expected Result.Invalid for missing params", result is PayPalDeepLinkHandler.Result.Invalid)
        val invalid = result as PayPalDeepLinkHandler.Result.Invalid
        assertTrue(invalid.reason.contains("missing", ignoreCase = true))
    }

    @Test
    fun testSanitizesMaliciousParameters() {
        val maliciousUri =
            "visualcart://paypal-return?token=%3Cscript%3Ealert('xss')%3C/script%3EORDER_777&PayerID=PAYER_888%00"
        val result = PayPalDeepLinkHandler.parse(maliciousUri)

        assertTrue("Expected Result.Approved", result is PayPalDeepLinkHandler.Result.Approved)
        val approved = result as PayPalDeepLinkHandler.Result.Approved
        assertFalse("Script tag must be stripped", approved.orderId.contains("<script>"))
        assertEquals("alert('xss')ORDER_777", approved.orderId)
        assertFalse("Null byte must be stripped", approved.payerId.contains('\u0000'))
        assertEquals("PAYER_888", approved.payerId.trim())
    }

    @Test
    fun testBuildReturnAndCancelUrls() {
        val returnUrl = PayPalDeepLinkHandler.buildReturnUrl("ORD-42", "PAY-99")
        assertEquals("visualcart://paypal-return?token=ORD-42&PayerID=PAY-99", returnUrl)

        val cancelUrl = PayPalDeepLinkHandler.buildCancelUrl("ORD-42")
        assertEquals("visualcart://paypal-cancel?token=ORD-42", cancelUrl)
    }
}
