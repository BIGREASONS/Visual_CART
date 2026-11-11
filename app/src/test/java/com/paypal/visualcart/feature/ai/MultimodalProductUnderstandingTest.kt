package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.RecognizedProduct
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Unit tests for multimodal product recognition, structured AI parsing,
 * backend proxy gateway integration, direct Gemini 1.5 Flash communication,
 * seamless offline/error fallback, and equivalence matching.
 */
class MultimodalProductUnderstandingTest {

    @Test
    fun testStructuredBackendProxyJsonParsing() {
        val sampleBackendJson = """
            {
              "status": "success",
              "data": {
                "brand": "Samsung",
                "product_name": "Samsung 990 EVO 1TB NVMe M.2 SSD",
                "category": "Computers",
                "colour": "Black",
                "model": "990 EVO",
                "specifications": {
                  "capacity": "1TB",
                  "interface": "NVMe PCIe 4.0/5.0",
                  "form_factor": "M.2 2280"
                },
                "confidence": 0.98,
                "match_reason": "High-confidence model & capacity identification (Samsung 990 EVO 1TB)",
                "is_demo_fixture": false,
                "is_live_inference": true
              }
            }
        """.trimIndent()

        val provider = CloudProductUnderstandingProvider()
        val structured = provider.parseStructuredProduct(sampleBackendJson)

        assertNotNull("Structured product should not be null", structured)
        assertEquals("Samsung", structured?.brand)
        assertEquals("Samsung 990 EVO 1TB NVMe M.2 SSD", structured?.productName)
        assertEquals("Computers", structured?.category)
        assertEquals("Black", structured?.colour)
        assertEquals("990 EVO", structured?.model)
        assertEquals("1TB", structured?.specifications?.get("capacity"))
        assertEquals("NVMe PCIe 4.0/5.0", structured?.specifications?.get("interface"))
        assertEquals("M.2 2280", structured?.specifications?.get("form_factor"))
        assertEquals("Black", structured?.specifications?.get("color"))
        assertEquals("990 EVO", structured?.specifications?.get("model"))
        assertEquals(0.98f, structured?.confidence ?: 0f, 0.001f)
        assertTrue(structured?.isLiveInference == true)
        assertFalse(structured?.isDemoFixture == true)
    }

    @Test
    fun testDirectGeminiMultimodalJsonParsingWithMarkdownFences() {
        val geminiCandidateJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "```json\n{\n  \"brand\": \"Sony\",\n  \"product_name\": \"Sony WH-1000XM5 Wireless Headphones\",\n  \"category\": \"Audio\",\n  \"colour\": \"Silver\",\n  \"model\": \"WH-1000XM5\",\n  \"specifications\": {\n    \"type\": \"Over-Ear\",\n    \"battery\": \"30 hours\",\n    \"connectivity\": \"Bluetooth 5.2\"\n  },\n  \"confidence\": 0.97,\n  \"match_reason\": \"Exact audio model match for Sony WH-1000XM5\"\n}\n```"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val provider = CloudProductUnderstandingProvider()
        val structured = provider.parseStructuredProduct(geminiCandidateJson)

        assertNotNull("Structured product from Gemini candidates should not be null", structured)
        assertEquals("Sony", structured?.brand)
        assertEquals("Sony WH-1000XM5 Wireless Headphones", structured?.productName)
        assertEquals("Audio", structured?.category)
        assertEquals("Silver", structured?.colour)
        assertEquals("WH-1000XM5", structured?.model)
        assertEquals("Over-Ear", structured?.specifications?.get("type"))
        assertEquals("30 hours", structured?.specifications?.get("battery"))
        assertEquals("Bluetooth 5.2", structured?.specifications?.get("connectivity"))
        assertEquals(0.97f, structured?.confidence ?: 0f, 0.001f)
        assertTrue(structured?.isLiveInference == true)
        assertFalse(structured?.isDemoFixture == true)
    }

    @Test
    fun testRecognizeFromImageWithBackendProxyTransport() = runBlocking {
        var recordedUrl: String? = null
        var recordedBody: String? = null

        val mockBackendResponse = """
            {
              "status": "success",
              "brand": "Sony",
              "product_name": "Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
              "category": "Audio",
              "colour": "Silver",
              "model": "WH-1000XM5",
              "specifications": {
                "type": "Over-Ear",
                "battery": "30 hours"
              },
              "confidence": 0.97,
              "is_demo_fixture": false,
              "is_live_inference": true
            }
        """.trimIndent()

        val mockTransport = NetworkTransport { url, _, body ->
            recordedUrl = url
            recordedBody = body
            NetworkResponse(statusCode = 200, body = mockBackendResponse)
        }

        val provider = CloudProductUnderstandingProvider(
            endpointUrl = "http://10.0.2.2:8000/api/v1/ai/recognize-product",
            networkTransport = mockTransport
        )

        assertTrue(provider.isCloudConfigured())

        val recognized = provider.recognizeFromImage(
            imageUri = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD",
            contextText = "Sony WH-1000XM5 Silver"
        )

        assertEquals("http://10.0.2.2:8000/api/v1/ai/recognize-product", recordedUrl)
        assertNotNull(recordedBody)
        assertTrue("Body should contain image_base64", recordedBody!!.contains("\"image_base64\""))
        assertTrue("Body should contain context_text", recordedBody!!.contains("\"context_text\""))

        assertEquals("Sony", recognized.brand)
        assertEquals("Sony WH-1000XM5 Wireless Noise Cancelling Headphones", recognized.title)
        assertEquals("Audio", recognized.category)
        assertEquals("Silver", recognized.colour)
        assertEquals("WH-1000XM5", recognized.model)
        assertTrue(recognized.isLiveInference)
        assertFalse(recognized.isDemoFixture)
    }

    @Test
    fun testRecognizeFromImageWithDirectGeminiKey() = runBlocking {
        var recordedUrl: String? = null
        var recordedBody: String? = null

        val mockGeminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\n  \"brand\": \"Apple\",\n  \"product_name\": \"Apple MacBook Air 13-inch M3\",\n  \"category\": \"Computers\",\n  \"colour\": \"Space Gray\",\n  \"model\": \"MacBook Air M3\",\n  \"specifications\": {\n    \"chip\": \"Apple M3\",\n    \"memory\": \"16GB\",\n    \"capacity\": \"512GB SSD\"\n  },\n  \"confidence\": 0.99\n}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val mockTransport = NetworkTransport { url, _, body ->
            recordedUrl = url
            recordedBody = body
            NetworkResponse(statusCode = 200, body = mockGeminiResponse)
        }

        val provider = CloudProductUnderstandingProvider(
            apiKey = "AIzaSyFakeGeminiApiKey12345",
            endpointUrl = null,
            modelName = "gemini-2.5-flash",
            networkTransport = mockTransport
        )

        assertTrue(provider.isCloudConfigured())

        val recognized = provider.recognizeFromImage(
            imageUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
            contextText = "MacBook Air M3 Space Gray"
        )

        assertNotNull(recordedUrl)
        assertTrue("URL should hit Google Generative Language API", recordedUrl!!.contains("generativelanguage.googleapis.com"))
        assertTrue("URL should specify gemini-2.5-flash", recordedUrl!!.contains("gemini-2.5-flash"))
        assertTrue("URL should contain api key parameter", recordedUrl!!.contains("key=AIzaSyFakeGeminiApiKey12345"))

        assertNotNull(recordedBody)
        assertTrue("Body should contain inlineData", recordedBody!!.contains("\"inlineData\""))
        assertTrue("Body should contain image/png", recordedBody!!.contains("\"mimeType\":\"image/png\""))
        assertTrue("Body should specify application/json response", recordedBody!!.contains("responseMimeType") && recordedBody!!.contains("application/json"))

        assertEquals("Apple", recognized.brand)
        assertEquals("Apple MacBook Air 13-inch M3", recognized.title)
        assertEquals("Computers", recognized.category)
        assertEquals("Space Gray", recognized.colour)
        assertEquals("MacBook Air M3", recognized.model)
        assertEquals("Apple M3", recognized.specifications["chip"])
        assertEquals("16GB", recognized.specifications["memory"])
        assertTrue(recognized.isLiveInference)
        assertFalse(recognized.isDemoFixture)
    }

    @Test
    fun testSeamlessFallbackWhenBackendReturns500Error() = runBlocking {
        val mockTransport = NetworkTransport { _, _, _ ->
            NetworkResponse(statusCode = 500, body = "{\"error\": \"Internal Server Error\"}")
        }

        val provider = CloudProductUnderstandingProvider(
            endpointUrl = "http://10.0.2.2:8000/api/v1/ai/recognize-product",
            networkTransport = mockTransport
        )

        val product = provider.recognizeFromText("Looking to buy Samsung 990 EVO SSD")

        // Must seamlessly fall back to on-device demo fixture without crashing
        assertNotNull(product)
        assertEquals(DemoProductRecognitionProvider.FIXTURE_SAMSUNG_SSD.id, product.id)
        assertTrue("Fallback result must be tagged isDemoFixture = true", product.isDemoFixture)
        assertFalse("Fallback result must not be live inference", product.isLiveInference)
    }

    @Test
    fun testSeamlessFallbackWhenBackendNetworkTimesOut() = runBlocking {
        val mockTransport = NetworkTransport { _, _, _ ->
            throw SocketTimeoutException("Connection timed out to AI proxy")
        }

        val provider = CloudProductUnderstandingProvider(
            endpointUrl = "http://10.0.2.2:8000/api/v1/ai/recognize-product",
            networkTransport = mockTransport
        )

        val product = provider.recognizeFromText("Best deals on Sony WH-1000XM5 headphones")

        // Must seamlessly fall back to Sony demo fixture
        assertEquals(DemoProductRecognitionProvider.FIXTURE_SONY_HEADPHONES.id, product.id)
        assertTrue(product.isDemoFixture)
        assertFalse(product.isLiveInference)
    }

    @Test
    fun testSeamlessFallbackWhenUnconfigured() = runBlocking {
        val provider = CloudProductUnderstandingProvider(
            apiKey = null,
            endpointUrl = null
        )

        assertFalse(provider.isCloudConfigured())

        val product = provider.recognizeFromText("Nike Air Max 270 sale")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_NIKE_SHOES.id, product.id)
        assertTrue(product.isDemoFixture)
        assertFalse(product.isLiveInference)
    }

    @Test
    fun testLocalParserFallbackWhenNonFixtureQueryOffline() = runBlocking {
        val provider = CloudProductUnderstandingProvider(
            apiKey = null,
            endpointUrl = null
        )

        // Non-fixture product query
        val product = provider.recognizeFromText("Bose QuietComfort 45 Over-Ear Bluetooth Headphones Black 24 hours battery")

        assertNotNull(product)
        assertEquals("Bose", product.brand)
        assertEquals("Audio", product.category)
        assertEquals("Over-Ear", product.specifications["type"])
        assertEquals("Black", product.specifications["color"])
        assertFalse("Heuristic parse is not a hardcoded demo fixture", product.isDemoFixture)
        assertFalse("Heuristic parse is not live cloud inference", product.isLiveInference)
    }

    @Test
    fun testMultimodalEquivalenceMatchingWithProductMatchingEngine() {
        val multimodalCandidate = RecognizedProduct(
            id = "ai-cand-990",
            title = "Samsung 990 EVO 1TB NVMe M.2 SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "1TB",
                "interface" to "NVMe PCIe 4.0/5.0",
                "form_factor" to "M.2 2280",
                "color" to "Black",
                "model" to "990 EVO"
            ),
            confidence = 0.98f,
            isDemoFixture = false,
            isLiveInference = true
        )

        // 1. Exact match with same specifications
        val targetIdentical = DemoProductRecognitionProvider.FIXTURE_SAMSUNG_SSD
        val exactMatchResult = ProductMatchingEngine.compareProducts(multimodalCandidate, targetIdentical)

        assertEquals(MatchType.EXACT, exactMatchResult.matchType)
        assertTrue("Confidence must be >= 0.85 for EXACT match, was ${exactMatchResult.confidence}", exactMatchResult.confidence >= 0.85f)
        assertTrue(exactMatchResult.explanation.summary.contains("Exact match", ignoreCase = true))
        assertTrue(exactMatchResult.explanation.differingAttributes.isEmpty())

        // 2. Differing capacity alternative (1TB vs 2TB)
        val target2Tb = RecognizedProduct(
            id = "target-2tb",
            title = "Samsung 990 EVO 2TB NVMe M.2 SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf("capacity" to "2TB", "model" to "990 EVO"),
            confidence = 0.95f
        )
        val specDiffResult = ProductMatchingEngine.compareProducts(multimodalCandidate, target2Tb)

        assertEquals(MatchType.SIMILAR, specDiffResult.matchType)
        assertTrue("Differing specs must be recorded in explanation", specDiffResult.explanation.differingAttributes.any { it.contains("capacity") })
        assertTrue("Confidence must be capped <= 0.65 for differing capacity", specDiffResult.confidence <= 0.65f)

        // 3. Differing brand alternative (Samsung vs Crucial)
        val targetCrucial = RecognizedProduct(
            id = "target-crucial",
            title = "Crucial P3 Plus 1TB NVMe SSD",
            brand = "Crucial",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB"),
            confidence = 0.95f
        )
        val brandClashResult = ProductMatchingEngine.compareProducts(multimodalCandidate, targetCrucial)

        assertEquals(MatchType.SIMILAR, brandClashResult.matchType)
        assertTrue("Differing brand must be recorded", brandClashResult.explanation.differingAttributes.any { it.contains("brand") })
        assertTrue("Confidence must be capped <= 0.50 for brand mismatch", brandClashResult.confidence <= 0.50f)
    }

    @Test
    fun testUnstructuredTextFromCloudParsedResiliently() = runBlocking {
        // Simulates Gemini returning unformatted text rather than pure JSON
        val mockTextGeminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Logitech MX Master 3S Wireless Performance Mouse 8000 DPI Sensor Graphite"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val mockTransport = NetworkTransport { _, _, _ ->
            NetworkResponse(statusCode = 200, body = mockTextGeminiResponse)
        }

        val provider = CloudProductUnderstandingProvider(
            apiKey = "DUMMY_KEY",
            networkTransport = mockTransport
        )

        val product = provider.recognizeFromText("What mouse is this?")

        assertNotNull(product)
        assertEquals("Logitech", product.brand)
        assertEquals("Electronics", product.category)
        assertEquals("8000 DPI", product.specifications["sensor"])
        assertEquals("Graphite", product.specifications["color"])
        assertTrue(product.isLiveInference)
        assertFalse(product.isDemoFixture)
    }

    @Test
    fun testPartialMultiCandidateAndUnidentifiedStructuredParsing() {
        val partialJson = """
            {
              "status": "PARTIAL",
              "brand": null,
              "product_name": "Galvanized Metal Pipes",
              "category": "Industrial Materials",
              "search_query": "metal pipes",
              "specifications": {
                "Material": "Uncertain from image alone",
                "Specifications": "Not verified"
              },
              "candidates": [
                {
                  "title": "Galvanized Steel Pipe",
                  "category": "Industrial Materials",
                  "search_query": "galvanized steel pipe",
                  "confidence": 0.61
                },
                {
                  "title": "Rigid PVC Conduit Pipe",
                  "category": "Industrial Materials",
                  "search_query": "pvc conduit pipe",
                  "confidence": 0.54
                }
              ],
              "confidence": 0.61,
              "match_reason": "Visible hollow cylindrical pipes; material uncertain."
            }
        """.trimIndent()

        val provider = CloudProductUnderstandingProvider()
        val parsedPartial = provider.parseStructuredProduct(partialJson)
        assertNotNull(parsedPartial)
        assertEquals(com.paypal.visualcart.domain.model.RecognitionStatus.PARTIAL, parsedPartial?.status)
        assertEquals("Industrial Materials", parsedPartial?.category)
        assertEquals(2, parsedPartial?.candidates?.size)
        assertEquals("Uncertain from image alone", parsedPartial?.specifications?.get("Material"))

        val unidentifiedJson = """
            {
              "status": "UNIDENTIFIED",
              "brand": null,
              "product_name": "Unidentified Product",
              "category": "Unidentified",
              "search_query": "shared image.jpg",
              "confidence": 0.12,
              "match_reason": "Blurry image without identifiable item"
            }
        """.trimIndent()
        val parsedUnknown = provider.parseStructuredProduct(unidentifiedJson)
        assertNotNull(parsedUnknown)
        assertEquals(com.paypal.visualcart.domain.model.RecognitionStatus.UNIDENTIFIED, parsedUnknown?.status)
    }

    @Test
    fun testRecognizeFromRealTempPngFileForwardsActualBytesAndSniffedMime() = runBlocking {
        val pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        val tempFile = java.io.File.createTempFile("real_capture_", ".tmp").apply {
            writeBytes(pngBytes)
        }
        try {
            var capturedBody: String? = null
            val mockTransport = NetworkTransport { _, _, body ->
                capturedBody = body
                NetworkResponse(
                    statusCode = 200,
                    body = """
                        {
                          "status": "PARTIAL",
                          "brand": null,
                          "product_name": "Industrial Metal Pipes",
                          "category": "Industrial Materials",
                          "search_query": "metal pipes",
                          "specifications": {"Material": "Uncertain from image alone"},
                          "confidence": 0.65,
                          "match_reason": "Inspecting real PNG binary bytes."
                        }
                    """.trimIndent()
                )
            }
            val provider = CloudProductUnderstandingProvider(
                endpointUrl = "http://127.0.0.1:8000/api/v1/ai/recognize-product",
                networkTransport = mockTransport
            )
            val result = provider.recognizeFromImage(tempFile.absolutePath, contextText = "shared image.jpg")
            assertNotNull(capturedBody)
            assertTrue("Expected image/png sniffed MIME in request", capturedBody!!.contains("\"mime_type\":\"image/png\""))
            val expectedB64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
            assertTrue("Expected exact image_base64 bytes forwarded", capturedBody!!.contains(expectedB64))
            assertFalse("Generic filename hint must never be forwarded", capturedBody!!.contains("shared image.jpg"))
            assertEquals("Industrial Materials", result.category)
            assertTrue(result.isLiveInference)
        } finally {
            tempFile.delete()
        }
    }
}
