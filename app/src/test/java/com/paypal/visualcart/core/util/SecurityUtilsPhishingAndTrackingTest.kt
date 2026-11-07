package com.paypal.visualcart.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive security unit tests for SecurityUtils.
 * Covers:
 * 1. Tracking query parameter stripping and tracking script neutralization.
 * 2. Phishing and lookalike domain detection (brand impersonation, homographs, suspicious TLDs, IP SSRF).
 * 3. Merchant domain authenticity verification.
 * 4. Safe browser intent validation.
 */
class SecurityUtilsPhishingAndTrackingTest {

    @Test
    fun testStripTrackingParametersRemovesUtmAndClickIds() {
        val rawUrl = "https://www.amazon.in/dp/B0CX23VFP2?utm_source=newsletter&utm_medium=email&utm_campaign=spring_sale&utm_term=ssd&utm_content=banner&gclid=Cj0KCQjw&fbclid=IwAR2&ref=deals_widget&tag=affil-21&_ga=GA1.2.123"
        val cleanUrl = SecurityUtils.stripTrackingParameters(rawUrl)

        assertFalse("Must not contain utm_source", cleanUrl.contains("utm_source"))
        assertFalse("Must not contain utm_medium", cleanUrl.contains("utm_medium"))
        assertFalse("Must not contain utm_campaign", cleanUrl.contains("utm_campaign"))
        assertFalse("Must not contain gclid", cleanUrl.contains("gclid"))
        assertFalse("Must not contain fbclid", cleanUrl.contains("fbclid"))
        assertFalse("Must not contain ref", cleanUrl.contains("ref="))
        assertFalse("Must not contain tag", cleanUrl.contains("tag="))
        assertFalse("Must not contain _ga", cleanUrl.contains("_ga="))
        assertEquals("https://www.amazon.in/dp/B0CX23VFP2", cleanUrl)
    }

    @Test
    fun testStripTrackingParametersPreservesFunctionalQueryParams() {
        val productUrl = "https://www.flipkart.com/item/p?pid=SSD12345&size=1TB&color=Black&utm_source=google&msclkid=98765"
        val cleanUrl = SecurityUtils.stripTrackingParameters(productUrl)

        assertTrue("Must preserve product ID parameter", cleanUrl.contains("pid=SSD12345"))
        assertTrue("Must preserve size parameter", cleanUrl.contains("size=1TB"))
        assertTrue("Must preserve color parameter", cleanUrl.contains("color=Black"))
        assertFalse("Must strip utm_source", cleanUrl.contains("utm_source"))
        assertFalse("Must strip msclkid", cleanUrl.contains("msclkid"))
    }

    @Test
    fun testStripTrackingParametersNeutralizesScriptInjectionsInQuery() {
        val dangerousUrl = "https://croma.com/search?q=<script>alert('xss')</script>&category=laptops"
        val cleanUrl = SecurityUtils.stripTrackingParameters(dangerousUrl)

        assertFalse("Must strip <script> tag from query value", cleanUrl.contains("<script>"))
        assertFalse("Must strip </script> tag from query value", cleanUrl.contains("</script>"))
        assertTrue("Must preserve legitimate query", cleanUrl.contains("category=laptops"))
    }

    @Test
    fun testPhishingBrandImpersonationRejected() {
        val phishingUrls = listOf(
            "https://paypal.com.attacker.com/login",
            "https://paypal-security-update.net/verify",
            "https://paypal-verification.com/checkout",
            "https://amazon-deals.top/win-iphone",
            "https://amazon.in.phishingsite.co/login",
            "https://flipkart-rewards.xyz/claim",
            "https://croma-discount-festival.click/pay",
            "https://reliancedigital-promo.top/gift",
            "https://myntra-big-sale.buzz/cart"
        )

        for (url in phishingUrls) {
            assertTrue("Expected phishing detection for: $url", SecurityUtils.isPhishingUrl(url))
            assertFalse("Phishing URL must NOT be considered valid: $url", SecurityUtils.isValidWebUrl(url))
            assertFalse("Phishing URL must NOT be safe for browser: $url", SecurityUtils.isSafeForBrowserIntent(url))
            assertNull("Sanitizer must reject phishing URL: $url", SecurityUtils.sanitizeWebUrl(url))
        }
    }

    @Test
    fun testHomographPunycodePhishingRejected() {
        // Punycode e.g. xn--pypal-4ve.com (impersonating paypal using Cyrillic a)
        val punycodeHost = "https://xn--pypal-4ve.com/signin"
        assertTrue("Must detect punycode homograph phishing", SecurityUtils.isPhishingUrl(punycodeHost))
        assertFalse("Must reject homograph URL", SecurityUtils.isValidWebUrl(punycodeHost))
    }

    @Test
    fun testDirectIpAddressesRejectedForWebUrls() {
        val ipUrls = listOf(
            "http://192.168.1.1/admin",
            "http://10.0.0.1/internal",
            "http://127.0.0.1:8080/secret",
            "http://169.254.169.254/latest/meta-data/"
        )

        for (url in ipUrls) {
            assertTrue("Direct IP address should be detected as unsafe/phishing: $url", SecurityUtils.isPhishingUrl(url))
            assertFalse("Direct IP address must not be considered valid web URL: $url", SecurityUtils.isValidWebUrl(url))
        }
    }

    @Test
    fun testLegitimateMerchantUrlsAccepted() {
        val genuineUrls = listOf(
            "https://www.paypal.com/signin",
            "https://sandbox.paypal.com/checkoutnow?token=123",
            "https://paypal.me/user123",
            "https://www.amazon.in/dp/B0CX23VFP2",
            "https://amazon.com/product/123",
            "https://www.flipkart.com/item/123",
            "https://croma.com/p/304402",
            "https://reliancedigital.in/item/12345",
            "https://www.tatacliq.com/deals/1",
            "https://myntra.com/tshirt/456"
        )

        for (url in genuineUrls) {
            assertFalse("Genuine URL should not be marked as phishing: $url", SecurityUtils.isPhishingUrl(url))
            assertTrue("Genuine URL must be valid: $url", SecurityUtils.isValidWebUrl(url))
            assertTrue("Genuine URL must be safe for browser intent: $url", SecurityUtils.isSafeForBrowserIntent(url))
            assertNotNull("Sanitized URL must not be null: $url", SecurityUtils.sanitizeWebUrl(url))
        }
    }

    @Test
    fun testSuspiciousMerchantDomainVerification() {
        // Amazon expected
        assertTrue(
            "Should detect suspicious Amazon lookalike",
            SecurityUtils.isSuspiciousMerchantDomain("amazon-deals.xyz", "Amazon India")
        )
        assertFalse(
            "Genuine Amazon domain should not be suspicious",
            SecurityUtils.isSuspiciousMerchantDomain("amazon.in", "Amazon India")
        )

        // PayPal expected
        assertTrue(
            "Should detect suspicious PayPal lookalike",
            SecurityUtils.isSuspiciousMerchantDomain("paypal.com.attacker.com", "PayPal")
        )
        assertFalse(
            "Genuine PayPal domain should not be suspicious",
            SecurityUtils.isSuspiciousMerchantDomain("paypal.com", "PayPal")
        )

        // Flipkart expected
        assertTrue(
            "Should detect suspicious Flipkart lookalike",
            SecurityUtils.isSuspiciousMerchantDomain("flipkart-lottery.top", "Flipkart")
        )
        assertFalse(
            "Genuine Flipkart domain should not be suspicious",
            SecurityUtils.isSuspiciousMerchantDomain("flipkart.com", "Flipkart")
        )
    }

    @Test
    fun testSanitizeWebUrlStripsTrackingAndReturnsCleanAscii() {
        val dirtyUrl = "https://www.amazon.in/dp/B0CX23VFP2?utm_source=twitter&ref=share_btn"
        val clean = SecurityUtils.sanitizeWebUrl(dirtyUrl)

        assertNotNull(clean)
        assertEquals("https://www.amazon.in/dp/B0CX23VFP2", clean)
    }

    @Test
    fun testInjectedScriptTagsInUrlRejected() {
        val xssUrl = "https://example.com/search?q=<script>alert('xss')</script>"
        assertFalse("Must reject URL with script tags", SecurityUtils.isValidWebUrl(xssUrl))
        assertNull("Sanitizer must return null for script injection", SecurityUtils.sanitizeWebUrl(xssUrl))
    }
}
