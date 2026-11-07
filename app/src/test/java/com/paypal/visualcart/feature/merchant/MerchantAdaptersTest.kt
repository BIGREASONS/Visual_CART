package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantComparisonSummary
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import com.paypal.visualcart.feature.merchant.adapters.AmazonIndiaAdapter
import com.paypal.visualcart.feature.merchant.adapters.CromaAdapter
import com.paypal.visualcart.feature.merchant.adapters.FlipkartAdapter
import com.paypal.visualcart.feature.merchant.adapters.MyntraAdapter
import com.paypal.visualcart.feature.merchant.adapters.RelianceDigitalAdapter
import com.paypal.visualcart.feature.merchant.adapters.TataCliqAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MerchantAdapters, URL processing, and listing provenance tagging.
 * Validates search URL construction for all 6 merchants (Amazon India, Flipkart, Croma,
 * Myntra, Reliance Digital, Tata CLiQ), product metadata parsing (ASIN, PID, Slug, Canonical URLs),
 * unmistakable distinction between LIVE API, DIRECT SEARCH URL, and VERIFIED CATALOG FIXTURE,
 * robust price sorting with mixed match types, and shipping estimation.
 */
class MerchantAdaptersTest {

    private val amazonAdapter = AmazonIndiaAdapter()
    private val flipkartAdapter = FlipkartAdapter()
    private val cromaAdapter = CromaAdapter()
    private val myntraAdapter = MyntraAdapter()
    private val relianceAdapter = RelianceDigitalAdapter()
    private val tataCliqAdapter = TataCliqAdapter()

    @Test
    fun testAmazonIndiaSearchUrlGeneration() {
        val query = "Samsung 990 EVO 1TB NVMe"
        val searchUrl = amazonAdapter.buildSearchUrl(query)

        assertEquals(
            "https://www.amazon.in/s?k=Samsung%20990%20EVO%201TB%20NVMe",
            searchUrl
        )
    }

    @Test
    fun testFlipkartSearchUrlGeneration() {
        val query = "Sony WH-1000XM5 Headphones"
        val searchUrl = flipkartAdapter.buildSearchUrl(query)

        assertEquals(
            "https://www.flipkart.com/search?q=Sony%20WH-1000XM5%20Headphones",
            searchUrl
        )
    }

    @Test
    fun testCromaSearchUrlGeneration() {
        val query = "Apple MacBook Air M3"
        val searchUrl = cromaAdapter.buildSearchUrl(query)

        assertEquals(
            "https://www.croma.com/searchB?q=Apple%20MacBook%20Air%20M3",
            searchUrl
        )
    }

    @Test
    fun testMyntraSearchUrlGeneration() {
        val query = "Nike Air Max 90"
        val searchUrl = myntraAdapter.buildSearchUrl(query)

        assertEquals(
            "https://www.myntra.com/Nike%20Air%20Max%2090",
            searchUrl
        )
    }

    @Test
    fun testRelianceDigitalSearchUrlGeneration() {
        val query = "Dell XPS 15 Laptop"
        val searchUrl = relianceAdapter.buildSearchUrl(query)

        assertEquals(
            "https://www.reliancedigital.in/search?q=Dell%20XPS%2015%20Laptop",
            searchUrl
        )
    }

    @Test
    fun testTataCliqSearchUrlGeneration() {
        val query = "Samsung 990 EVO"
        val searchUrl = tataCliqAdapter.buildSearchUrl(query)

        assertEquals(
            "https://www.tatacliq.com/search/?searchCategory=all&text=Samsung%20990%20EVO",
            searchUrl
        )
    }

    @Test
    fun testAmazonUrlParsingExtractsAsinAndCanonicalUrl() {
        val rawUrl = "https://www.amazon.in/Samsung-990-EVO-Internal-MZ-V9E1T0B/dp/B0CSB4C57W?ref=sr_1_1&tag=affiliate01"
        val parsed = amazonAdapter.parseProductUrl(rawUrl)

        assertNotNull("Parsed metadata should not be null", parsed)
        assertEquals("amazon.in", parsed?.domain)
        assertEquals("B0CSB4C57W", parsed?.productId)
        assertEquals("https://www.amazon.in/dp/B0CSB4C57W", parsed?.canonicalUrl)
        assertNotNull("Extracted title slug should be non-null", parsed?.title)
        assertTrue("Title should contain Samsung", parsed?.title?.contains("Samsung") == true)
    }

    @Test
    fun testAmazonIndiaSearchListingVerifiedFixtureTagging() {
        runBlocking {
            val results = amazonAdapter.search("Samsung 990 EVO")

            assertEquals(1, results.size)
            val listing = results.first()
            assertTrue(listing.id.startsWith("amazon_in_"))
            assertEquals("INR", listing.currency)
            assertEquals(SourceType.DEMO, listing.sourceType)
            assertEquals(MatchType.EXACT, listing.matchType)
            assertEquals(ListingOrigin.VERIFIED_CATALOG_FIXTURE, listing.listingOrigin)
            assertTrue(listing.isVerifiedCatalogFixture)
            assertTrue(listing.isDemo)
            assertFalse(listing.isLive)
            assertEquals("[DEMO | EXACT | VERIFIED FIXTURE]", listing.provenanceTag)
            assertEquals(AvailabilityStatus.IN_STOCK, listing.availability)
        }
    }

    @Test
    fun testFlipkartUrlParsingExtractsPidAndCleanTitle() {
        val rawUrl = "https://www.flipkart.com/samsung-990-evo-1-tb-desktop-internal-solid-state-drive/p/itmd5b3c4?pid=SSDH2345678&lid=LST123"
        val parsed = flipkartAdapter.parseProductUrl(rawUrl)

        assertNotNull(parsed)
        assertEquals("flipkart.com", parsed?.domain)
        assertEquals("SSDH2345678", parsed?.productId)
        assertTrue(parsed?.title?.contains("Samsung 990 Evo") == true)
    }

    @Test
    fun testFlipkartSearchListingVerifiedFixtureTagging() = runBlocking {
        val results = flipkartAdapter.search("Samsung 990 EVO")

        assertEquals(1, results.size)
        val listing = results.first()
        assertEquals("INR", listing.currency)
        assertEquals(SourceType.DEMO, listing.sourceType)
        assertEquals(MatchType.EXACT, listing.matchType)
        assertEquals(ListingOrigin.VERIFIED_CATALOG_FIXTURE, listing.listingOrigin)
        assertEquals("[DEMO | EXACT | VERIFIED FIXTURE]", listing.provenanceTag)
    }

    @Test
    fun testCromaUrlParsingExtractsNumericId() {
        val rawUrl = "https://www.croma.com/sony-wh-1000xm5-bluetooth-noise-cancelling-headphone/p/254421"
        val parsed = cromaAdapter.parseProductUrl(rawUrl)

        assertNotNull(parsed)
        assertEquals("croma.com", parsed?.domain)
        assertEquals("254421", parsed?.productId)
        assertTrue(parsed?.canonicalUrl?.contains("254421") == true)
    }

    @Test
    fun testRelianceDigitalUrlParsingExtractsNumericId() {
        val rawUrl = "https://www.reliancedigital.in/samsung-990-evo-internal-ssd-1tb/p/494352843"
        val parsed = relianceAdapter.parseProductUrl(rawUrl)

        assertNotNull(parsed)
        assertEquals("494352843", parsed?.productId)
    }

    @Test
    fun testTataCliqUrlParsingExtractsPid() {
        val rawUrl = "https://www.tatacliq.com/samsung-990-evo-1tb-internal-ssd/p-mp0000000184920"
        val parsed = tataCliqAdapter.parseProductUrl(rawUrl)

        assertNotNull(parsed)
        assertEquals("mp0000000184920", parsed?.productId)
    }

    @Test
    fun testMyntraUrlParsingExtractsProductId() {
        val rawUrl = "https://www.myntra.com/sports-shoes/nike/nike-men-air-zoom-pegasus-40-running-shoes/22910482/buy"
        val parsed = myntraAdapter.parseProductUrl(rawUrl)

        assertNotNull(parsed)
        assertEquals("22910482", parsed?.productId)
    }

    @Test
    fun testNonMatchingDomainReturnsNull() {
        assertNull(amazonAdapter.parseProductUrl("https://www.flipkart.com/item/p/123"))
        assertNull(flipkartAdapter.parseProductUrl("https://www.croma.com/p/123"))
    }

    @Test
    fun testUniversalProductUrlExtractorStripsTrackingParameters() {
        val providers = listOf(amazonAdapter, flipkartAdapter, cromaAdapter)
        val extractor = ProductUrlExtractor(providers)

        val dirtyAmazonUrl = "https://www.amazon.in/dp/B0CX23VFP2?utm_source=google&utm_medium=cpc&ref=xyz&gclid=123"
        val extracted = extractor.extract(dirtyAmazonUrl)

        assertEquals("B0CX23VFP2", extracted.productId)
        assertFalse("Canonical URL must not contain utm_source", extracted.canonicalUrl.contains("utm_source"))
        assertFalse("Canonical URL must not contain gclid", extracted.canonicalUrl.contains("gclid"))
    }

    @Test
    fun testLiveApiListingUnmistakablyTagged() = runBlocking {
        val liveAmazonAdapter = AmazonIndiaAdapter(isLiveMode = true)
        val results = liveAmazonAdapter.search("Samsung 990 EVO")

        assertEquals(1, results.size)
        val listing = results.first()
        assertEquals(SourceType.LIVE, listing.sourceType)
        assertEquals(ListingOrigin.LIVE_API, listing.listingOrigin)
        assertTrue(listing.isLive)
        assertTrue(listing.isLiveApi)
        assertFalse(listing.isDemo)
        assertEquals("[LIVE | EXACT | LIVE API]", listing.provenanceTag)
    }

    @Test
    fun testDirectSearchUrlListingUnmistakablyTagged() {
        val directListing = cromaAdapter.buildDirectSearchListing("Mechanical Gaming Keyboard")

        assertEquals(SourceType.DEMO, directListing.sourceType)
        assertEquals(MatchType.SIMILAR, directListing.matchType)
        assertEquals(ListingOrigin.DIRECT_SEARCH_URL, directListing.listingOrigin)
        assertTrue(directListing.isDirectSearchUrl)
        assertFalse(directListing.isLiveApi)
        assertEquals("[DEMO | SIMILAR | DIRECT SEARCH]", directListing.provenanceTag)
        assertTrue("URL must be a valid Croma search URL", directListing.productUrl.contains("croma.com/searchB?q="))
    }

    @Test
    fun testCrucialP3PlusCatalogFixtureInAllAdapters() = runBlocking {
        val amazonListings = amazonAdapter.search("Crucial P3 Plus 1TB")
        val flipkartListings = flipkartAdapter.search("Crucial P3 Plus 1TB")
        val cromaListings = cromaAdapter.search("Crucial P3 Plus 1TB")

        assertTrue(amazonListings.isNotEmpty())
        assertTrue(flipkartListings.isNotEmpty())
        assertTrue(cromaListings.isNotEmpty())

        assertEquals(5499.0, flipkartListings.first().price, 0.001)
        assertEquals(5799.0, amazonListings.first().price, 0.001)
        assertEquals(5899.0, cromaListings.first().price, 0.001)
    }

    @Test
    fun testRobustPriceSortingProtectsCanonicalPriceFromAccessoryClutter() {
        // Suppose a search for Samsung 990 EVO returns 3 exact drives and 1 cheap cable (SIMILAR match)
        val listings = listOf(
            MerchantListing(
                id = "list_cable",
                merchantName = "Cheap Store",
                merchantDomain = "cheap.com",
                title = "NVMe M.2 Thermal Pad & Cable",
                price = 299.0, // Low price accessory
                productUrl = "https://cheap.com/cable",
                imageUrl = "https://cheap.com/cable.jpg",
                matchType = MatchType.SIMILAR,
                confidence = 0.50f,
                availability = AvailabilityStatus.IN_STOCK,
                sourceType = SourceType.DEMO,
                lastVerifiedAt = System.currentTimeMillis()
            ),
            MerchantListing(
                id = "list_amazon",
                merchantName = "Amazon India",
                merchantDomain = "amazon.in",
                title = "Samsung 990 EVO 1TB SSD",
                price = 5999.0,
                originalPrice = 9999.0,
                productUrl = "https://amazon.in/dp/1",
                imageUrl = "https://example.com/2.jpg",
                matchType = MatchType.EXACT,
                confidence = 0.98f,
                availability = AvailabilityStatus.IN_STOCK,
                sourceType = SourceType.DEMO,
                lastVerifiedAt = System.currentTimeMillis()
            ),
            MerchantListing(
                id = "list_flipkart",
                merchantName = "Flipkart",
                merchantDomain = "flipkart.com",
                title = "Samsung 990 EVO 1TB SSD",
                price = 5499.0,
                originalPrice = 9899.0,
                productUrl = "https://flipkart.com/p/1",
                imageUrl = "https://example.com/3.jpg",
                matchType = MatchType.EXACT,
                confidence = 0.98f,
                availability = AvailabilityStatus.IN_STOCK,
                sourceType = SourceType.DEMO,
                lastVerifiedAt = System.currentTimeMillis()
            )
        )

        val summary = MerchantComparisonSummary.fromListings("Samsung 990 EVO 1TB SSD", listings)
        assertNotNull(summary)

        // The lowest price must NOT be the 299.0 cable, but the lowest EXACT match (Flipkart 5499.0)!
        assertEquals("Flipkart", summary!!.lowestPriceListing.merchantName)
        assertEquals(5499.0, summary.lowestPrice, 0.001)
        assertEquals(5999.0, summary.highestPrice, 0.001)
        assertEquals(500.0, summary.maxSavings, 0.001)
        assertEquals(2, summary.exactMatchCount)

        // Sorted listings must place EXACT matches first
        assertEquals(MatchType.EXACT, summary.listings[0].matchType)
        assertEquals(5499.0, summary.listings[0].price, 0.001)
        assertEquals(MatchType.EXACT, summary.listings[1].matchType)
        assertEquals(5999.0, summary.listings[1].price, 0.001)
        assertEquals(MatchType.SIMILAR, summary.listings[2].matchType)
        assertEquals(299.0, summary.listings[2].price, 0.001)
    }

    @Test
    fun testShippingEstimatorParsingAndEvaluation() {
        val primeShipping = ShippingEstimator.parseText("Free Next-Day Delivery by Prime", price = 5999.0)
        assertTrue(primeShipping.isFreeShipping)
        assertTrue(primeShipping.isFastDelivery)
        assertEquals(1, primeShipping.estimatedDaysMin)
        assertEquals(1, primeShipping.estimatedDaysMax)

        val cromaShipping = ShippingEstimator.parseText("Standard Delivery in 24 hours", price = 5799.0)
        assertTrue(cromaShipping.isFastDelivery)
        assertEquals(1, cromaShipping.estimatedDaysMin)

        val tataShipping = ShippingEstimator.parseText("Delivery in 3-4 business days", price = 200.0)
        assertFalse(tataShipping.isFastDelivery)
        assertEquals(3, tataShipping.estimatedDaysMin)
        assertEquals(4, tataShipping.estimatedDaysMax)
    }
}
