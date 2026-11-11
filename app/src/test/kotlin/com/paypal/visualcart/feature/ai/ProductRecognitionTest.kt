package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.RecognizedProduct
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRecognitionTest {

    @Test
    fun testLocalParserBrandDetection() {
        assertEquals("Samsung", LocalProductParser.detectBrand("Samsung 990 EVO SSD"))
        assertEquals("Sony", LocalProductParser.detectBrand("Sony WH-1000XM5 Headphones"))
        assertEquals("Apple", LocalProductParser.detectBrand("Apple MacBook Air 13-inch"))
        assertEquals("Nike", LocalProductParser.detectBrand("Nike Air Max 270"))
        assertEquals("Logitech", LocalProductParser.detectBrand("Logitech MX Master 3S"))
        assertEquals("Western Digital", LocalProductParser.detectBrand("WD_BLACK SN850X NVMe"))
    }

    @Test
    fun testLocalParserSpecExtraction() {
        val ssdText = "Samsung 990 EVO 1TB NVMe PCIe 4.0 M.2 2280 Internal SSD"
        val specs = LocalProductParser.extractSpecifications(ssdText)
        assertEquals("1TB", specs["capacity"])
        assertTrue(specs["interface"]?.contains("NVMe") == true)
        assertEquals("M.2 2280", specs["form_factor"])

        val macbookText = "Apple MacBook Air 13-inch M3 chip, 16GB RAM, 512GB SSD Space Gray"
        val macSpecs = LocalProductParser.extractSpecifications(macbookText)
        assertEquals("Apple M3", macSpecs["chip"])
        assertEquals("16GB", macSpecs["memory"])
        assertEquals("512GB", macSpecs["capacity"])
        assertEquals("13-inch", macSpecs["display"])
        assertEquals("Space Gray", macSpecs["color"])

        val headphoneText = "Sony WH-1000XM5 Wireless Over-Ear Headphones with 30 hours battery, Bluetooth 5.2, Silver"
        val headSpecs = LocalProductParser.extractSpecifications(headphoneText)
        assertEquals("Over-Ear", headSpecs["type"])
        assertEquals("30 hours", headSpecs["battery"])
        assertTrue(headSpecs["connectivity"]?.contains("Bluetooth") == true)
        assertEquals("Silver", headSpecs["color"])

        val shoeText = "Nike Air Max 270 Men's Running Shoes Triple Black UK 9"
        val shoeSpecs = LocalProductParser.extractSpecifications(shoeText)
        assertEquals("UK 9", shoeSpecs["size"])
        assertEquals("Men", shoeSpecs["gender"])
        assertEquals("Triple Black", shoeSpecs["color"])
    }

    @Test
    fun testLocalParserTitleCleanup() {
        val raw = "Buy Online Free Shipping! Samsung 990 EVO 1TB SSD 30% Off Best Seller | Authentic"
        val cleaned = LocalProductParser.cleanCanonicalTitle(raw, "Samsung", emptyMap())
        assertFalse(cleaned.contains("Buy Online"))
        assertFalse(cleaned.contains("Free Shipping"))
        assertFalse(cleaned.contains("30% Off"))
        assertFalse(cleaned.contains("Best Seller"))
        assertTrue(cleaned.contains("Samsung 990 EVO 1TB SSD"))
    }

    @Test
    fun testDemoFixturesIntegrity() {
        val provider = DemoProductRecognitionProvider()
        val fixtures = provider.getDemoFixtures()
        assertEquals(7, fixtures.size)

        for (fixture in fixtures) {
            assertTrue(fixture.isDemoFixture)
            assertNotNull(fixture.brand)
            assertTrue(fixture.title.isNotBlank())
            assertTrue(fixture.specifications.isNotEmpty())
            assertTrue(fixture.confidence >= 0.90f)
            assertTrue(fixture.matchReason.isNotBlank())
        }
    }

    @Test
    fun testDemoProviderKeywordResolution() = runBlocking {
        val provider = DemoProductRecognitionProvider()

        val p1 = provider.recognizeFromText("Looking to buy Samsung 990 EVO SSD")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_SAMSUNG_SSD.id, p1.id)
        assertTrue(p1.isDemoFixture)

        val p2 = provider.recognizeFromText("Best price on Sony WH-1000XM5")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_SONY_HEADPHONES.id, p2.id)

        val p3 = provider.recognizeFromText("Nike Air Max 270 sale")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_NIKE_SHOES.id, p3.id)

        val p4 = provider.recognizeFromText("Apple MacBook Air M3 13-inch")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_APPLE_MACBOOK.id, p4.id)

        val p5 = provider.recognizeFromText("Logitech MX Master 3S mouse")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_LOGITECH_MOUSE.id, p5.id)
    }

    @Test
    fun testProductMatchingEngineExactMatch() {
        val target = DemoProductRecognitionProvider.FIXTURE_SAMSUNG_SSD
        val candidate = RecognizedProduct(
            id = "c1",
            title = "Samsung 990 EVO PCIe 4.0 NVMe 1TB M.2 SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "1TB",
                "interface" to "NVMe PCIe 4.0/5.0",
                "form_factor" to "M.2 2280"
            ),
            confidence = 0.9f
        )

        val result = ProductMatchingEngine.compareProducts(candidate, target)
        assertEquals(MatchType.EXACT, result.matchType)
        assertTrue(result.confidence >= 0.85f)
        assertTrue(result.explanation.summary.contains("Exact match"))
    }

    @Test
    fun testProductMatchingEngineSimilarAlternativeDifferingCapacity() {
        val target = DemoProductRecognitionProvider.FIXTURE_SAMSUNG_SSD // 1TB
        val candidate = RecognizedProduct(
            id = "c2",
            title = "Samsung 990 EVO 2TB NVMe M.2 SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "2TB",
                "interface" to "NVMe PCIe 4.0/5.0",
                "form_factor" to "M.2 2280"
            ),
            confidence = 0.9f
        )

        val result = ProductMatchingEngine.compareProducts(candidate, target)
        assertEquals(MatchType.SIMILAR, result.matchType)
        assertTrue(result.explanation.summary.contains("Similar alternative"))
        assertTrue(result.explanation.differingAttributes.any { it.contains("capacity") })
    }

    @Test
    fun testCloudProviderGracefulFallbackWithoutKeys() = runBlocking {
        val cloudProvider = CloudProductUnderstandingProvider(
            apiKey = null,
            endpointUrl = null
        )
        assertFalse(cloudProvider.isCloudConfigured())

        val product = cloudProvider.recognizeFromText("Samsung 990 EVO")
        assertEquals(DemoProductRecognitionProvider.FIXTURE_SAMSUNG_SSD.id, product.id)
        assertTrue(product.isDemoFixture)
    }

    @Test
    fun testNonElectronicsProductDoesNotDefaultToElectronicsCategory() {
        val parsed = LocalProductParser.parse("PVC Plastic Water Pipes 2 inch")
        assertEquals("General", parsed.category)
        assertEquals("PVC Plastic Water Pipes 2 inch", parsed.title)
    }

    @Test
    fun testCloudProviderTreatsNeedsGeminiKeyResponseAsUnidentified() {
        val provider = CloudProductUnderstandingProvider(
            apiKey = null,
            endpointUrl = "http://localhost:8000/api/v1/ai/recognize-product",
            networkTransport = NetworkTransport { _, _, _ ->
                NetworkResponse(
                    statusCode = 200,
                    body = """
                        {
                          "title": "Unidentified Product",
                          "brand": "",
                          "category": "Unidentified",
                          "estimated_price": null,
                          "confidence": 0.1,
                          "match_reason": "Visual AI requires GEMINI_API_KEY in backend .env.",
                          "source": "needs_gemini_key"
                        }
                    """.trimIndent()
                )
            }
        )
        val result = runBlocking {
            provider.recognizeFromImage("/tmp/share_1.jpg", contextText = null)
        }
        assertEquals("Unidentified Product", result.title)
        assertEquals("Unidentified", result.category)
        assertFalse(result.isLiveInference)
        assertFalse(result.isDemoFixture)
    }
}
