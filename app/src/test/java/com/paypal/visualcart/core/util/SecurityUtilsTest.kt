package com.paypal.visualcart.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SecurityUtils.
 * Validates URL filtering, malicious scheme rejection, Content URI verification,
 * and user input sanitization.
 */
class SecurityUtilsTest {

    @Test
    fun testValidWebUrlsAccepted() {
        val validUrls = listOf(
            "https://www.amazon.in/dp/B0CX23VFP2",
            "http://www.flipkart.com/search?q=samsung+ssd",
            "https://croma.com/p/304402",
            "https://reliancedigital.in/item/12345",
            "https://subdomain.store.co.uk/product/shoe-99?ref=app&size=10",
            "http://localhost:8080/test/endpoint"
        )

        for (url in validUrls) {
            assertTrue("Expected valid: $url", SecurityUtils.isValidWebUrl(url))
            assertTrue("Expected safe for browser: $url", SecurityUtils.isSafeForBrowserIntent(url))
            assertNotNull("Sanitized URL should not be null for: $url", SecurityUtils.sanitizeWebUrl(url))
        }
    }

    @Test
    fun testMaliciousAndForbiddenSchemesRejected() {
        val maliciousUrls = listOf(
            "javascript:alert('XSS')",
            "JAVASCRIPT:void(0)",
            "javascript://alert(1)",
            "file:///etc/passwd",
            "file:///C:/Windows/System32/cmd.exe",
            "content://media/external/images/media/1",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "data:text/html,<script>alert(1)</script>",
            "intent://example.com#Intent;scheme=http;package=com.example;end",
            "vbscript:msgbox('malicious')",
            "about:blank",
            "blob:https://example.com/1234-uuid"
        )

        for (url in maliciousUrls) {
            assertTrue("Should detect unsafe scheme for: $url", SecurityUtils.hasUnsafeScheme(url))
            assertFalse("Should reject unsafe URL: $url", SecurityUtils.isValidWebUrl(url))
            assertFalse("Browser intent must reject: $url", SecurityUtils.isSafeForBrowserIntent(url))
            assertNull("Sanitize should return null for: $url", SecurityUtils.sanitizeWebUrl(url))
        }
    }

    @Test
    fun testMalformedAndInvalidUrlsRejected() {
        val invalidUrls = listOf(
            null,
            "",
            "   ",
            "not_a_url",
            "ftp://files.example.com/app.apk",
            "https://",
            "http://",
            "https:///path/without/host",
            "https://attacker:secretpassword@legitimate-site.com/login", // Embedded userinfo credentials
            "https://example.com\r\nSet-Cookie: malicious=true",         // CRLF injection
            "https://example.com\u0000/nullbyte",                        // Embedded null byte
            "http://invalid_domain"                                      // Missing top-level domain
        )

        for (url in invalidUrls) {
            assertFalse("Expected rejection for: $url", SecurityUtils.isValidWebUrl(url))
            assertFalse("Browser intent must reject: $url", SecurityUtils.isSafeForBrowserIntent(url))
            assertNull("Sanitizer should return null for: $url", SecurityUtils.sanitizeWebUrl(url))
        }
    }

    @Test
    fun testValidContentUrisAccepted() {
        val validUris = listOf(
            "content://media/external/images/media/1024",
            "content://com.android.providers.media.documents/document/image%3A123",
            "content://com.paypal.visualcart.provider/captures/shot1.png",
            "content://a.b.c_provider/items/1"
        )

        for (uri in validUris) {
            assertTrue("Expected valid content URI: $uri", SecurityUtils.isValidContentUri(uri))
        }
    }

    @Test
    fun testInvalidAndTraversalContentUrisRejected() {
        val dangerousUris = listOf(
            null,
            "",
            "   ",
            "file:///sdcard/Download/invoice.pdf",                     // File scheme instead of content
            "https://example.com/image.png",                           // HTTP instead of content
            "content://media/../secrets.txt",                          // Path traversal
            "content://media/..\\private.db",                          // Windows-style traversal
            "content://media/%2e%2e/data",                             // URL-encoded traversal
            "content://media/%2e%2e%2froot",                           // Encoded slash traversal
            "content://invalid!authority#bad/path",                    // Malformed authority characters
            "content://media/image\u0000.png"                          // Embedded null byte
        )

        for (uri in dangerousUris) {
            assertFalse("Expected rejection for: $uri", SecurityUtils.isValidContentUri(uri))
        }
    }

    @Test
    fun testUserInputSanitizationStripsHtmlAndScript() {
        val dirtyInput = "<script>alert('XSS')</script><b>Samsung</b> 990 EVO 1TB SSD"
        val clean = SecurityUtils.sanitizeUserInput(dirtyInput)

        assertFalse("Clean string must not contain script tags", clean.contains("<script>"))
        assertFalse("Clean string must not contain HTML tags", clean.contains("<b>"))
        assertEquals("alert('XSS')Samsung 990 EVO 1TB SSD", clean)
    }

    @Test
    fun testUserInputSanitizationStripsControlCharsAndNulls() {
        val dirtyInput = "Apple\u0000AirPods\u0007Pro\u001b[31mWireless"
        val clean = SecurityUtils.sanitizeUserInput(dirtyInput)

        assertFalse("Clean string must not contain null bytes", clean.contains('\u0000'))
        assertEquals("Apple AirPods Pro [31mWireless", clean)
    }

    @Test
    fun testUserInputSanitizationNormalizesWhitespaceAndTruncates() {
        val multiSpace = "   Nike   Air    Max   90   "
        assertEquals("Nike Air Max 90", SecurityUtils.sanitizeUserInput(multiSpace))

        val longInput = "a".repeat(1000)
        val truncated = SecurityUtils.sanitizeUserInput(longInput, maxLength = 100)
        assertEquals(100, truncated.length)
    }

    @Test
    fun testSearchQuerySanitizationStripsDisruptiveCharacters() {
        val injectionQuery = "Samsung 990'; DROP TABLE products; <script> --"
        val clean = SecurityUtils.sanitizeSearchQuery(injectionQuery)

        assertFalse("Clean query must not contain quotes", clean.contains("'"))
        assertFalse("Clean query must not contain semicolons", clean.contains(";"))
        assertFalse("Clean query must not contain brackets", clean.contains("<"))
        assertEquals("Samsung 990 DROP TABLE products --", clean)
    }
}
