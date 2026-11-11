package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.core.config.VisualCartSettingsStore
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for [GeminiFreeVisionProvider] enforcing V1.3 zero-billing free-tier policy:
 * - Approved model allowlist (`gemini-3.5-flash-lite` only; zero paid fallback/upgrades)
 * - API-key validation (supports AQ.* & AIza* formats, rejects placeholders)
 * - Model availability check via header auth
 * - Single-call HTTP 429 rate-limit circuit breaker + Local OCR / Manual fallback
 * - SHA-256 content-hash caching of duplicate images
 * - Corrupt image rejection before spending quota
 * - Structured JSON validation (`identification_status`, list specifications, non-demo provenance)
 */
class GeminiFreeVisionProviderTest {

    companion object {
        private const val VALID_1X1_PNG_DATA_URI =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }

    @Test
    fun testFreeTierPolicyRejectsPaidModelsAndNonZeroCost() {
        GeminiFreeVisionProvider.validateFreeTierPolicy(
            modelName = "gemini-3.5-flash-lite",
            freeTierOnly = true,
            maxCostUsd = 0.0
        )

        val forbiddenModels = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.8-flash", "gemini-1.5-pro")
        for (model in forbiddenModels) {
            try {
                GeminiFreeVisionProvider(apiKey = "AQ.Ab8RN6TestFreeTierKey12345", modelName = model)
                fail("Expected FreeTierPolicyException for paid/unapproved model $model")
            } catch (expected: FreeTierPolicyException) {
                assertTrue(expected.message!!.contains(model))
            }
        }

        try {
            GeminiFreeVisionProvider(
                apiKey = "AQ.Ab8RN6TestFreeTierKey12345",
                freeTierOnly = false
            )
            fail("Expected FreeTierPolicyException when freeTierOnly=false")
        } catch (_: FreeTierPolicyException) {
            // Expected
        }

        try {
            GeminiFreeVisionProvider(
                apiKey = "AQ.Ab8RN6TestFreeTierKey12345",
                maxCostUsd = 5.0
            )
            fail("Expected FreeTierPolicyException when maxCostUsd > 0")
        } catch (_: FreeTierPolicyException) {
            // Expected
        }
    }

    @Test
    fun testApiKeyValidationSupportsAqAndAizaAndRejectsPlaceholders() {
        assertTrue(GeminiFreeVisionProvider.isValidApiKeyFormat("AQ.Ab8RN6ExampleFreeTierKey999"))
        assertTrue(GeminiFreeVisionProvider.isValidApiKeyFormat("AIzaSyExampleFreeTierKey999"))
        assertFalse(GeminiFreeVisionProvider.isValidApiKeyFormat(null))
        assertFalse(GeminiFreeVisionProvider.isValidApiKeyFormat(""))
        assertFalse(GeminiFreeVisionProvider.isValidApiKeyFormat("short"))
        assertFalse(GeminiFreeVisionProvider.isValidApiKeyFormat("changeme"))
        assertFalse(GeminiFreeVisionProvider.isValidApiKeyFormat("YOUR_GEMINI_API_KEY_HERE"))
        assertFalse(GeminiFreeVisionProvider.isValidApiKeyFormat("key with spaces"))
    }

    @Test
    fun testModelAvailabilityCheckUsesHeaderAuthWithoutQueryKey() {
        var recordedUrl: String? = null
        var recordedHeaders: Map<String, String> = emptyMap()

        val provider = GeminiFreeVisionProvider(
            apiKey = "AQ.Ab8RN6TestFreeTierKey12345",
            endpointUrl = null,
            getTransport = { url, headers ->
                recordedUrl = url
                recordedHeaders = headers
                NetworkResponse(statusCode = 200, body = """{"name":"models/gemini-3.5-flash-lite"}""")
            }
        )

        val status = provider.verifyModelAvailability()
        assertTrue(status.available)
        assertEquals("gemini-3.5-flash-lite", status.modelName)
        assertTrue(recordedUrl!!.endsWith("/models/gemini-3.5-flash-lite"))
        assertFalse(recordedUrl!!.contains("key="))
        assertEquals("AQ.Ab8RN6TestFreeTierKey12345", recordedHeaders["x-goog-api-key"])
    }

    @Test
    fun testRateLimit429StopsRequestsAndFallsBackWithoutPaidRetry() = runBlocking {
        var callCount = 0
        val provider = GeminiFreeVisionProvider(
            apiKey = "AQ.Ab8RN6TestFreeTierKey12345",
            endpointUrl = null,
            networkTransport = NetworkTransport { _, _, _ ->
                callCount++
                NetworkResponse(statusCode = 429, body = """{"error":{"code":429,"message":"Quota exceeded"}}""")
            }
        )

        // Call 1: hits HTTP 429 once -> circuit breaker opens, zero retries/fallback models
        val first = provider.recognizeFromImage(VALID_1X1_PNG_DATA_URI, contextText = null)
        assertEquals(1, callCount)
        assertTrue(first.rateLimited)
        assertFalse(first.isLiveInference)
        assertFalse(first.isDemoFixture)
        assertEquals(RecognitionSource.MANUAL_SEARCH, first.recognitionSource)
        assertTrue(first.diagnosticNote!!.contains("Free AI limit reached. Try again later."))

        // Call 2 during cooldown: must make ZERO network requests and use LOCAL_OCR if text hint exists
        val second = provider.recognizeFromImage(VALID_1X1_PNG_DATA_URI, contextText = "Logitech MX Master 3S Mouse")
        assertEquals(1, callCount)
        assertTrue(second.rateLimited)
        assertFalse(second.isLiveInference)
        assertEquals(RecognitionSource.LOCAL_OCR, second.recognitionSource)
        assertTrue(second.diagnosticNote!!.contains("Free AI limit reached. Try again later."))
    }

    @Test
    fun testDuplicateImageHashCachePreventsDuplicateApiCalls() = runBlocking {
        var callCount = 0
        val mockResponse = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "{\"brand\":\"Logitech\",\"product_name\":\"Logitech M185 Wireless Mouse\",\"category\":\"Electronics\",\"model\":\"M185\",\"colour\":\"Black\",\"specifications\":[\"Connectivity: 2.4GHz USB\"],\"search_query\":\"Logitech M185 Wireless Mouse\",\"confidence\":0.92,\"identification_status\":\"IDENTIFIED\",\"match_reason\":\"Logitech M185 USB wireless mouse visible.\"}"
                  }]
                }
              }]
            }
        """.trimIndent()

        val provider = GeminiFreeVisionProvider(
            apiKey = "AQ.Ab8RN6TestFreeTierKey12345",
            endpointUrl = null,
            networkTransport = NetworkTransport { _, _, _ ->
                callCount++
                NetworkResponse(statusCode = 200, body = mockResponse)
            }
        )

        val first = provider.recognizeFromImage(VALID_1X1_PNG_DATA_URI, contextText = null)
        val second = provider.recognizeFromImage(VALID_1X1_PNG_DATA_URI, contextText = null)

        assertEquals(1, callCount)
        assertFalse(first.cacheHit)
        assertTrue(second.cacheHit)
        assertEquals("Logitech M185 Wireless Mouse", second.title)
        assertEquals(RecognitionSource.LIVE_AI, second.recognitionSource)
        assertEquals("2.4GHz USB", second.specifications["Connectivity"])
    }

    @Test
    fun testCorruptImageRejectedLocallyWithoutCallingApiAndNeverUsesDemoFixtures() = runBlocking {
        var callCount = 0
        val provider = GeminiFreeVisionProvider(
            apiKey = "AQ.Ab8RN6TestFreeTierKey12345",
            endpointUrl = null,
            networkTransport = NetworkTransport { _, _, _ ->
                callCount++
                NetworkResponse(statusCode = 200, body = "{}")
            }
        )

        val corruptHtmlDataUri = "data:image/jpeg;base64," +
            Base64.getEncoder().encodeToString("<html>404 Not Found</html>".toByteArray())
        val result = provider.recognizeFromImage(corruptHtmlDataUri, contextText = null)

        assertEquals(0, callCount)
        assertEquals(RecognitionStatus.UNIDENTIFIED, result.status)
        assertEquals(RecognitionSource.MANUAL_SEARCH, result.recognitionSource)
        assertFalse(result.isDemoFixture)
        assertFalse(result.isLiveInference)

        // Even offline without key, known fixture text NEVER turns into DEMO fixture on GeminiFreeVisionProvider
        val offline = GeminiFreeVisionProvider(apiKey = null, endpointUrl = null)
        val parsedOffline = offline.recognizeFromText("Samsung 990 EVO 1TB SSD")
        assertFalse(parsedOffline.isDemoFixture)
        assertFalse(parsedOffline.isLiveInference)
        assertEquals(RecognitionSource.LOCAL_OCR, parsedOffline.recognitionSource)
    }

    @Test
    fun testStructuredJsonParsingHandlesListSpecificationsAndDressAndPipes() {
        val dressJson = """
            {
              "brand": null,
              "product_name": "Sequined Evening Midi Dress",
              "category": "Fashion",
              "model": null,
              "colour": "Cream",
              "specifications": ["Style: Sleeveless sequined bodice", "Material: Unverified from image alone"],
              "search_query": "cream sequined evening midi dress",
              "confidence": 0.78,
              "identification_status": "PARTIAL",
              "match_reason": "Cream sequined sleeveless dress on display form; brand label not visible."
            }
        """.trimIndent()
        val parsedDress = GeminiFreeVisionProvider.parseStructuredProduct(dressJson)!!
        assertEquals("Fashion", parsedDress.category)
        assertEquals(RecognitionStatus.PARTIAL, parsedDress.status)
        assertEquals("Sleeveless sequined bodice", parsedDress.specifications["Style"])
        assertEquals("Cream", parsedDress.colour)
        assertEquals("cream sequined evening midi dress", parsedDress.searchQuery)
    }

    @Test
    fun testBackendFailureFallsBackToDirectFreeTierKeyOnGemini35FlashLite() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val directJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "{\"brand\":\"PowerX\",\"product_name\":\"PowerX Wired Optical Mouse\",\"category\":\"Electronics\",\"model\":null,\"colour\":\"Black\",\"specifications\":[\"Connectivity: Wired USB\"],\"search_query\":\"PowerX wired optical mouse\",\"confidence\":0.89,\"identification_status\":\"IDENTIFIED\",\"match_reason\":\"PowerX logo visible on black wired mouse.\"}"
                  }]
                }
              }]
            }
        """.trimIndent()

        val provider = GeminiFreeVisionProvider(
            apiKey = null,
            endpointUrl = "http://127.0.0.1:59999/api/v1/ai/recognize-product",
            dynamicApiKeySupplier = { "AQ.Ab8RN6TestFreeTierKey12345" },
            networkTransport = NetworkTransport { url, _, _ ->
                requestedUrls.add(url)
                if (url.contains("127.0.0.1:59999")) {
                    throw java.net.ConnectException("Connection refused")
                }
                NetworkResponse(statusCode = 200, body = directJson)
            }
        )

        val result = provider.recognizeFromImage(VALID_1X1_PNG_DATA_URI, contextText = null)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls[1].contains("/models/gemini-3.5-flash-lite:generateContent"))
        assertEquals(RecognitionStatus.IDENTIFIED, result.status)
        assertEquals("PowerX Wired Optical Mouse", result.title)
        assertEquals("PowerX", result.brand)
        assertTrue(result.isLiveInference)
    }

    @Test
    fun testBackendUnreachableWithoutKeyReturnsActionableDiagnostic() = runBlocking {
        val provider = GeminiFreeVisionProvider(
            apiKey = null,
            endpointUrl = "http://localhost:8000/api/v1/ai/recognize-product",
            networkTransport = NetworkTransport { _, _, _ ->
                throw java.net.ConnectException("ECONNREFUSED")
            }
        )

        val result = provider.recognizeFromImage(VALID_1X1_PNG_DATA_URI, contextText = null)
        assertEquals(RecognitionStatus.UNIDENTIFIED, result.status)
        assertFalse(result.isDemoFixture)
        val note = result.diagnosticNote.orEmpty()
        assertTrue("Expected actionable message, got: $note", note.contains("Settings"))
        assertTrue("Expected adb reverse or Backend URL hint, got: $note", note.contains("adb reverse"))
    }

    @Test
    fun testSettingsStoreUrlNormalizationAndKeyMasking() {
        assertEquals(
            "http://192.168.1.8:8000",
            VisualCartSettingsStore.normalizeBackendBaseUrl("192.168.1.8:8000/api/v1/ai/recognize-product")
        )
        assertEquals(
            "https://demo.trycloudflare.com",
            VisualCartSettingsStore.normalizeBackendBaseUrl("https://demo.trycloudflare.com/health/")
        )
        assertEquals(null, VisualCartSettingsStore.normalizeBackendBaseUrl("javascript:alert(1)"))
        assertEquals(null, VisualCartSettingsStore.normalizeBackendBaseUrl("   "))
        assertEquals("AIzaSy••••9999", VisualCartSettingsStore.maskApiKey("AIzaSyExampleKey123459999"))
    }
}
