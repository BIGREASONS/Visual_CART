package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            assertEquals(5499.0, listings[0].price, 0.01)

            // Check prices are ascending for exact matches
            for (i in 0 until listings.size - 1) {
                assertTrue(
                    "Expected listing ${listings[i].price} <= ${listings[i + 1].price}",
                    listings[i].price <= listings[i + 1].price
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
                assertEquals("flipkart", it.lowestPriceListing.id.substringBefore("_"))
                assertEquals(5499.0, it.lowestPrice, 0.01)
                assertEquals(6199.0, it.highestPrice, 0.01)
                assertEquals(700.0, it.maxSavings, 0.01)
                assertTrue(it.hasDemoData)
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
    fun testArbitraryQuerySynthesis() {
        runBlocking {
            // Query that is not in the hardcoded catalog
            val listings = engine.searchAll("Ergonomic Mechanical Keyboard")

            assertEquals(6, listings.size)
            assertTrue(listings.all { it.sourceType == SourceType.DEMO })
            assertTrue(listings.all { it.price > 0.0 })
            assertTrue(listings.all { it.productUrl.isNotBlank() })
        }
    }
}
