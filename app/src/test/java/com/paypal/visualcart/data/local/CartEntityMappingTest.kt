package com.paypal.visualcart.data.local

import com.paypal.visualcart.data.local.entity.MerchantListingEntity
import com.paypal.visualcart.data.local.entity.PriceObservationEntity
import com.paypal.visualcart.data.local.entity.SavedProductEntity
import com.paypal.visualcart.data.local.entity.SavedProductWithListings
import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantComparisonSummary
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Cart entity mapping, relations, and price observation logic.
 */
class CartEntityMappingTest {

    @Test
    fun testSavedProductWithListingsSelectedAndLowestPrice() {
        val product = SavedProductEntity(
            id = "prod_1",
            canonicalProductId = "canon_1",
            title = "Samsung 990 EVO 1TB SSD",
            brand = "Samsung",
            category = "Computers"
        )

        val listing1 = MerchantListingEntity(
            id = "list_1",
            savedProductId = "prod_1",
            merchant = "Flipkart",
            title = "Samsung 990 EVO",
            price = 8799.0,
            productUrl = "https://flipkart.com/p/1",
            isSelectedForPurchase = false
        )

        val listing2 = MerchantListingEntity(
            id = "list_2",
            savedProductId = "prod_1",
            merchant = "Amazon",
            title = "Samsung 990 EVO",
            price = 8499.0,
            productUrl = "https://amazon.in/dp/1",
            isSelectedForPurchase = true
        )

        val relation = SavedProductWithListings(
            product = product,
            listings = listOf(listing1, listing2),
            priceObservations = emptyList()
        )

        assertEquals("list_2", relation.selectedListing?.id)
        assertEquals(8499.0, relation.lowestPriceListing?.price ?: 0.0, 0.001)
    }

    @Test
    fun testPriceDropDetectionInSavedProductWithListings() {
        val product = SavedProductEntity(
            id = "prod_price_drop",
            canonicalProductId = "canon_drop",
            title = "Sony WH-1000XM5 Headphones",
            category = "Audio"
        )

        val listing = MerchantListingEntity(
            id = "list_sony",
            savedProductId = "prod_price_drop",
            merchant = "Amazon",
            title = "Sony WH-1000XM5",
            price = 24990.0, // Current price
            productUrl = "https://amazon.in/dp/sony",
            isSelectedForPurchase = true
        )

        val pastObservations = listOf(
            PriceObservationEntity(
                id = "obs_1",
                merchantListingId = "list_sony",
                price = 29990.0, // Past higher price
                observedAt = System.currentTimeMillis() - 86400000L
            ),
            PriceObservationEntity(
                id = "obs_2",
                merchantListingId = "list_sony",
                price = 26990.0,
                observedAt = System.currentTimeMillis() - 43200000L
            )
        )

        val relation = SavedProductWithListings(
            product = product,
            listings = listOf(listing),
            priceObservations = pastObservations
        )

        assertTrue("hasPriceDrop must be true when past price was 29990 and current is 24990", relation.hasPriceDrop)
    }

    @Test
    fun testMerchantListingDiscountAndSourceTypeMapping() {
        val demoListing = MerchantListing(
            id = "demo_1",
            merchantName = "Demo Store",
            merchantDomain = "demo.local",
            title = "Demo Item",
            price = 800.0,
            originalPrice = 1000.0,
            productUrl = "https://demo.local/item",
            imageUrl = "https://demo.local/item.jpg",
            matchType = MatchType.EXACT,
            confidence = 1.0f,
            availability = AvailabilityStatus.IN_STOCK,
            sourceType = SourceType.DEMO,
            lastVerifiedAt = System.currentTimeMillis()
        )

        assertEquals(20, demoListing.discountPercentage)
        assertTrue(demoListing.isDemo)
        assertFalse(demoListing.isLive)
    }

    @Test
    fun testMerchantComparisonSummaryCalculation() {
        val listings = listOf(
            MerchantListing(
                id = "1",
                merchantName = "Store A",
                merchantDomain = "a.com",
                title = "Product",
                price = 1000.0,
                productUrl = "https://a.com/p",
                imageUrl = "https://a.com/img.jpg",
                matchType = MatchType.EXACT,
                confidence = 0.9f,
                availability = AvailabilityStatus.IN_STOCK,
                sourceType = SourceType.DEMO,
                lastVerifiedAt = 0L
            ),
            MerchantListing(
                id = "2",
                merchantName = "Store B",
                merchantDomain = "b.com",
                title = "Product",
                price = 1500.0,
                productUrl = "https://b.com/p",
                imageUrl = "https://b.com/img.jpg",
                matchType = MatchType.LIKELY,
                confidence = 0.8f,
                availability = AvailabilityStatus.IN_STOCK,
                sourceType = SourceType.DEMO,
                lastVerifiedAt = 0L
            )
        )

        val summary = MerchantComparisonSummary.fromListings("Product", listings)
        assertNotNull(summary)
        assertEquals(1000.0, summary?.lowestPrice ?: 0.0, 0.001)
        assertEquals(1500.0, summary?.highestPrice ?: 0.0, 0.001)
        assertEquals(500.0, summary?.maxSavings ?: 0.0, 0.001)
        assertEquals(1, summary?.exactMatchCount)
        assertTrue(summary?.hasDemoData == true)
        assertTrue(summary?.hasComparableBestPrice == true)

        assertNull(MerchantComparisonSummary.fromListings("Empty", emptyList()))
    }

    @Test
    fun testUnpricedDirectSearchLinksExcludedFromLowestPriceAndComparableSavings() {
        val product = SavedProductEntity(
            id = "prod_pipes",
            canonicalProductId = "metal_pipes",
            title = "Galvanized Metal Pipes",
            category = "Industrial Materials"
        )
        val unpricedSearchRow = MerchantListingEntity(
            id = "search_amazon",
            savedProductId = "prod_pipes",
            merchant = "Amazon India",
            title = "Galvanized Metal Pipes",
            price = null,
            productUrl = "https://www.amazon.in/s?k=Galvanized+Metal+Pipes",
            listingOrigin = "DIRECT_SEARCH_URL",
            isSelectedForPurchase = true
        )
        val relation = SavedProductWithListings(
            product = product,
            listings = listOf(unpricedSearchRow),
            priceObservations = emptyList()
        )
        assertFalse(unpricedSearchRow.hasVerifiedPrice)
        assertNull("Search link with price=null must not qualify as lowestPriceListing", relation.lowestPriceListing)

        val domainSearchLinks = listOf(
            MerchantListing(
                id = "amazon_in_search_1",
                merchantName = "Amazon India",
                merchantDomain = "amazon.in",
                title = "Galvanized Metal Pipes",
                price = null,
                currency = "INR",
                productUrl = "https://www.amazon.in/s?k=Galvanized+Metal+Pipes",
                imageUrl = "",
                matchType = MatchType.SIMILAR,
                confidence = 0.5f,
                availability = AvailabilityStatus.UNKNOWN,
                sourceType = SourceType.LIVE,
                listingOrigin = com.paypal.visualcart.domain.model.ListingOrigin.DIRECT_SEARCH_URL,
                lastVerifiedAt = 0L
            ),
            MerchantListing(
                id = "flipkart_search_1",
                merchantName = "Flipkart",
                merchantDomain = "flipkart.com",
                title = "Galvanized Metal Pipes",
                price = null,
                currency = "INR",
                productUrl = "https://www.flipkart.com/search?q=Galvanized+Metal+Pipes",
                imageUrl = "",
                matchType = MatchType.SIMILAR,
                confidence = 0.5f,
                availability = AvailabilityStatus.UNKNOWN,
                sourceType = SourceType.LIVE,
                listingOrigin = com.paypal.visualcart.domain.model.ListingOrigin.DIRECT_SEARCH_URL,
                lastVerifiedAt = 0L
            )
        )
        val summary = MerchantComparisonSummary.fromListings("Galvanized Metal Pipes", domainSearchLinks)
        assertNotNull(summary)
        assertNull(summary?.lowestPriceListing)
        assertNull(summary?.lowestPrice)
        assertFalse(summary?.hasComparableBestPrice == true)
        assertEquals(0.0, summary?.maxSavings ?: -1.0, 0.001)
    }
}
