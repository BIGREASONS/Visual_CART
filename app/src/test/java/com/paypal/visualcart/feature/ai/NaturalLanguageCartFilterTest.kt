package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.ProductCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NaturalLanguageCartFilter.
 * Validates conversational cart search, price bound extraction, merchant detection,
 * price drop filtering, and candidate product ranking.
 */
class NaturalLanguageCartFilterTest {

    @Test
    fun testParseElectronicsBelowPrice() {
        val query = "Show me all electronics below 10000"
        val criteria = NaturalLanguageCartFilter.parse(query)

        assertEquals("Expected category Electronics", "Electronics", criteria.category)
        assertEquals(10000.0, criteria.maxPrice)
        assertNull("minPrice should not be set", criteria.minPrice)
    }

    @Test
    fun testParseMerchantIntentAmazon() {
        val query = "Show me everything from Amazon"
        val criteria = NaturalLanguageCartFilter.parse(query)

        assertEquals("Expected merchant Amazon", "Amazon", criteria.merchant)
        assertFalse("Price drops should be false", criteria.priceDropsOnly)
    }

    @Test
    fun testParsePriceDropsIntent() {
        val queries = listOf(
            "Find products in my cart that have fallen in price",
            "Show me price drops",
            "Show discounted items",
            "What has fallen in price?"
        )

        for (q in queries) {
            val criteria = NaturalLanguageCartFilter.parse(q)
            assertTrue("Expected priceDropsOnly to be true for query: $q", criteria.priceDropsOnly)
        }
    }

    @Test
    fun testParseCheaperAlternativesIntent() {
        val query = "Find cheaper alternatives to my saved SSD"
        val criteria = NaturalLanguageCartFilter.parse(query)

        assertTrue("Expected findCheaperAlternatives true", criteria.findCheaperAlternatives)
        assertTrue("Expected sortByPriceAscending true", criteria.sortByPriceAscending)
        assertEquals("Expected targetKeyword SSD", "SSD", criteria.targetKeyword)
    }

    @Test
    fun testParseCurrencyAndPriceRange() {
        val query = "Items between ₹2,000 and ₹8,000 from Flipkart"
        val criteria = NaturalLanguageCartFilter.parse(query)

        assertEquals("INR", criteria.currency)
        assertEquals(2000.0, criteria.minPrice)
        assertEquals(8000.0, criteria.maxPrice)
        assertEquals("Flipkart", criteria.merchant)
    }

    @Test
    fun testParseBrandAndCategoryWithPriceCap() {
        val query = "Show Nike shoes under ₹5,000"
        val criteria = NaturalLanguageCartFilter.parse(query)

        assertEquals("Nike", criteria.brand)
        assertEquals("Footwear", criteria.category)
        assertEquals(5000.0, criteria.maxPrice)
        assertEquals("INR", criteria.currency)
    }

    @Test
    fun testFilterCandidatesByPriceAndMerchant() {
        val candidates = listOf(
            ProductCandidate(id = "1", title = "Logitech MX Master 3S", merchant = "Amazon", price = 8995.0),
            ProductCandidate(id = "2", title = "Logitech G502 Hero", merchant = "Flipkart", price = 3995.0),
            ProductCandidate(id = "3", title = "Sony WH-1000XM5", merchant = "Amazon", price = 26990.0),
            ProductCandidate(id = "4", title = "Samsung 990 EVO 1TB", merchant = "Amazon", price = 8499.0)
        )

        // Filter: from Amazon below 10000
        val filtered = NaturalLanguageCartFilter.filterCandidates(candidates, "Show items from Amazon below 10000")

        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.merchant == "Amazon" })
        assertTrue(filtered.all { (it.price ?: 0.0) <= 10000.0 })
        assertTrue(filtered.any { it.id == "1" })
        assertTrue(filtered.any { it.id == "4" })
    }

    @Test
    fun testFilterCandidatesByPriceDropsOnly() {
        val candidates = listOf(
            ProductCandidate(id = "1", title = "Sony Headphones", price = 19990.0, originalPrice = 24990.0, priceDrop = true),
            ProductCandidate(id = "2", title = "Samsung SSD", price = 8990.0, originalPrice = 8990.0, priceDrop = false),
            ProductCandidate(id = "3", title = "Apple Watch", price = 39900.0, originalPrice = 45900.0, priceDrop = true)
        )

        val priceDropResults = NaturalLanguageCartFilter.filterCandidates(candidates, "Show price drops")

        assertEquals(2, priceDropResults.size)
        assertTrue(priceDropResults.any { it.id == "1" })
        assertTrue(priceDropResults.any { it.id == "3" })
        assertFalse(priceDropResults.any { it.id == "2" })
    }

    @Test
    fun testCheapestSortOrder() {
        val candidates = listOf(
            ProductCandidate(id = "expensive", title = "Option A", price = 9500.0),
            ProductCandidate(id = "cheap", title = "Option B", price = 4500.0),
            ProductCandidate(id = "mid", title = "Option C", price = 6200.0)
        )

        val sorted = NaturalLanguageCartFilter.filterCandidates(candidates, "cheapest alternatives")

        assertEquals("cheap", sorted[0].id)
        assertEquals("mid", sorted[1].id)
        assertEquals("expensive", sorted[2].id)
    }
}
