package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantComparisonSummary
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.adapters.AmazonIndiaAdapter
import com.paypal.visualcart.feature.merchant.adapters.CromaAdapter
import com.paypal.visualcart.feature.merchant.adapters.FlipkartAdapter
import com.paypal.visualcart.feature.merchant.adapters.MyntraAdapter
import com.paypal.visualcart.feature.merchant.adapters.RelianceDigitalAdapter
import com.paypal.visualcart.feature.merchant.adapters.TataCliqAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Orchestrates multi-merchant product discovery, price comparison, and URL metadata extraction.
 *
 * Executes concurrent queries across registered providers (Amazon India, Flipkart, Croma,
 * Reliance Digital, Tata CLiQ, Myntra), normalizes currency & pricing in INR,
 * and sorts results by match quality and lowest price.
 */
class MerchantDiscoveryEngine(
    val providers: List<MerchantSearchProvider> = defaultProviders()
) {

    private val urlExtractor = ProductUrlExtractor(providers)

    /**
     * Executes parallel multi-merchant search across all configured providers.
     * Results are sorted by match accuracy (EXACT > LIKELY > SIMILAR), confidence, and price ascending.
     */
    suspend fun searchAll(
        query: String,
        targetSpecs: Map<String, String> = emptyMap()
    ): List<MerchantListing> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList()

        coroutineScope {
            val deferreds = providers.map { provider ->
                async {
                    try {
                        provider.search(trimmedQuery, targetSpecs)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }

            val flattened = deferreds.awaitAll().flatten()
            sortListings(flattened)
        }
    }

    /**
     * Discovers listings across merchants and constructs a [MerchantComparisonSummary]
     * identifying the lowest price, price range, and potential savings.
     */
    suspend fun comparePrices(
        canonicalTitle: String,
        targetSpecs: Map<String, String> = emptyMap()
    ): MerchantComparisonSummary? {
        val listings = searchAll(canonicalTitle, targetSpecs)
        return MerchantComparisonSummary.fromListings(canonicalTitle, listings)
    }

    /**
     * Parses an arbitrary product URL to extract canonical product ID, title slug,
     * and cleaned URL.
     */
    fun parseProductUrl(url: String): ParsedProductMetadata {
        return urlExtractor.extract(url)
    }

    /**
     * Finds the adapter associated with a given merchant ID or domain.
     */
    fun findProvider(merchantIdOrDomain: String): MerchantSearchProvider? {
        val lower = merchantIdOrDomain.lowercase()
        return providers.firstOrNull {
            it.merchantId.equals(lower, ignoreCase = true) ||
                it.domain.equals(lower, ignoreCase = true) ||
                lower.contains(it.domain)
        }
    }

    /**
     * Sorts listings:
     * 1. Match type hierarchy: EXACT first, then LIKELY, then SIMILAR
     * 2. Stock availability: IN_STOCK -> LIMITED -> UNKNOWN -> OUT_OF_STOCK
     * 3. Lowest price ascending (lowest price first within exact matches)
     * 4. Highest match confidence
     */
    fun sortListings(listings: List<MerchantListing>): List<MerchantListing> {
        return listings.sortedWith(
            compareBy<MerchantListing> { listing ->
                when (listing.matchType) {
                    MatchType.EXACT -> 0
                    MatchType.LIKELY -> 1
                    MatchType.SIMILAR -> 2
                }
            }
                .thenBy {
                    when (it.availability) {
                        AvailabilityStatus.IN_STOCK -> 0
                        AvailabilityStatus.LIMITED -> 1
                        AvailabilityStatus.UNKNOWN -> 2
                        AvailabilityStatus.OUT_OF_STOCK -> 3
                    }
                }
                .thenBy { it.price }
                .thenByDescending { it.confidence }
        )
    }

    companion object {
        fun defaultProviders(): List<MerchantSearchProvider> = listOf(
            AmazonIndiaAdapter(),
            FlipkartAdapter(),
            CromaAdapter(),
            RelianceDigitalAdapter(),
            TataCliqAdapter(),
            MyntraAdapter()
        )
    }
}
