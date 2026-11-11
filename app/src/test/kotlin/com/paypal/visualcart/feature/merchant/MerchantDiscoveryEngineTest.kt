package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantDiscoveryEngineTest {

    private val engine = MerchantDiscoveryEngine()

    @Test
    fun testParallelMultiMerchantSearch_Samsung990EVO() {
        runBlocking {
            val listings = engine.searchAll("Samsung 990 EVO")

            // 6 merchant adapters registered
            assertEquals(6, listings.size)

            // All should be marked SourceType.DEMO
            assertTrue(listings.all { it.sourceType == SourceType.DEMO })
            assertTrue(listings.all { it.currency == "INR" })

            // Check lowest price first
            assertEquals("flipkart", listings[0].id.substringBefore("_"))
            assertEquals(5499.0, listings[0].price ?: 0.0, 0.01)

            // Check prices are ascending for exact matches
            for (i in 0 until listings.size - 1) {
                assertTrue(
                    "Expected listing ${listings[i].price} <= ${listings[i + 1].price}",
                    (listings[i].price ?: Double.MAX_VALUE) <= (listings[i + 1].price ?: Double.MAX_VALUE)
                )
            }
        }
    }

    @Test
    fun testComparePrices_SummaryCalculations() {
        runBlocking {
            val summary = engine.comparePrices("Samsung 990 EVO")

            assertNotNull(summary)
            summary?.let {
                assertEquals("Samsung 990 EVO", it.canonicalTitle)
                assertEquals("flipkart", it.lowestPriceListing?.id?.substringBefore("_"))
                assertEquals(5499.0, it.lowestPrice ?: 0.0, 0.01)
                assertEquals(6199.0, it.highestPrice ?: 0.0, 0.01)
                assertEquals(700.0, it.maxSavings, 0.01)
                assertTrue(it.hasDemoData)
                assertTrue(it.hasComparableBestPrice)
            }
        }
    }

    @Test
    fun testUniversalProductUrlExtractor() {
        val amazonUrl = "https://www.amazon.in/dp/B0CQ2185T6?tag=affiliate_tracker&ref=sr_1_1"
        val parsedAmazon = engine.parseProductUrl(amazonUrl)
        assertEquals("amazon.in", parsedAmazon.domain)
        assertEquals("B0CQ2185T6", parsedAmazon.productId)
        assertEquals("https://www.amazon.in/dp/B0CQ2185T6", parsedAmazon.canonicalUrl)

        val flipkartUrl = "https://www.flipkart.com/samsung-ssd/p/itm123?pid=SSDH2Y9FGRYSHZ9G&utm_source=google"
        val parsedFlipkart = engine.parseProductUrl(flipkartUrl)
        assertEquals("flipkart.com", parsedFlipkart.domain)
        assertEquals("SSDH2Y9FGRYSHZ9G", parsedFlipkart.productId)

        val genericUrl = "https://example-shop.com/products/wireless-gaming-mouse-rgb-12345?ref=homepage"
        val parsedGeneric = engine.parseProductUrl(genericUrl)
        assertEquals("example-shop.com", parsedGeneric.domain)
        assertTrue(parsedGeneric.title?.contains("Wireless Gaming Mouse") == true)
        // Clean canonical url strips ref
        assertEquals("https://example-shop.com/products/wireless-gaming-mouse-rgb-12345", parsedGeneric.canonicalUrl)
    }

    @Test
    fun testArbitraryQueryReturnsHonestSearchLinksWithoutFakePrices() {
        runBlocking {
            // Query that is not in the hardcoded demo catalog must NOT invent synthetic INR prices
            val listings = engine.searchAll("Ergonomic Mechanical Keyboard")

            assertEquals(6, listings.size)
            assertTrue(listings.all { it.listingOrigin == ListingOrigin.DIRECT_SEARCH_URL })
            assertTrue(listings.all { it.price == null && !it.hasVerifiedPrice })
            assertTrue(listings.all { it.productUrl.isNotBlank() && !it.productUrl.contains("shared", ignoreCase = true) })

            val summary = engine.comparePrices("Ergonomic Mechanical Keyboard")
            assertNotNull(summary)
            assertNull(summary?.lowestPrice)
            assertFalse(summary?.hasComparableBestPrice == true)
            assertEquals(0.0, summary?.maxSavings ?: -1.0, 0.001)
        }
    }

    @Test
    fun testLiveModeNeverContaminatesWithDemoFixturesAndRejectsCaptureFilenames() {
        runBlocking {
            val liveEngine = MerchantDiscoveryEngine(
                MerchantDiscoveryEngine.defaultProviders().map { provider ->
                    provider.apply { isLiveMode = true }
                }
            )

            // Even for a product in DemoMerchantFixtures, live mode returns real store search links (no fake demo price)
            val liveListings = liveEngine.searchAll("Samsung 990 EVO 1TB SSD")
            assertTrue(liveListings.isNotEmpty())
            assertTrue(liveListings.all { it.listingOrigin == ListingOrigin.DIRECT_SEARCH_URL })
            assertTrue(liveListings.all { it.price == null && !it.hasVerifiedPrice })

            // Capture filename/timestamp noise must be blocked before reaching any store search URL
            val noiseProduct = com.paypal.visualcart.domain.model.RecognizedProduct(
                id = "noise-1",
                title = "capture 1794369896240 4816",
                brand = null,
                category = "Unidentified",
                confidence = 0.1f,
                searchQuery = "capture 1794369896240 4816"
            )
            val noiseResult = liveEngine.discoverStructured(noiseProduct)
            assertNotNull(noiseResult.rejectedReason)
            assertNull(noiseResult.canonicalQuery)
            assertTrue(noiseResult.exactMatches.isEmpty())
            assertTrue(noiseResult.searchLinks.isEmpty())
        }
    }
}
