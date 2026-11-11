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
        val canonicalQuery = SearchQueryBuilder.buildFromRawQuery(query).validQueryOrNull
            ?: return@withContext emptyList()

        coroutineScope {
            val deferreds = providers.map { provider ->
                async {
                    try {
                        provider.search(canonicalQuery, targetSpecs)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }

            val flattened = deferreds.awaitAll().flatten()
            deduplicateListings(flattened)
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
        return MerchantComparisonSummary.sortCanonicalListings(listings)
    }

    fun deduplicateListings(listings: List<MerchantListing>): List<MerchantListing> {
        val sorted = sortListings(listings)
        return sorted.distinctBy { listing ->
            "${listing.normalizedMerchantId}::${SearchQueryBuilder.normalizeProductIdentityKey(listing.title)}"
        }
    }

    suspend fun discoverStructured(
        product: com.paypal.visualcart.domain.model.RecognizedProduct
    ): com.paypal.visualcart.domain.model.MerchantDiscoveryResult = withContext(Dispatchers.IO) {
        val queryResult = SearchQueryBuilder.build(
            SearchQueryBuilder.Input.fromRecognizedProduct(product)
        )
        val canonicalQuery = when (queryResult) {
            is SearchQueryBuilder.Result.Valid -> queryResult.query
            is SearchQueryBuilder.Result.Rejected -> {
                return@withContext com.paypal.visualcart.domain.model.MerchantDiscoveryResult(
                    canonicalQuery = null,
                    rejectedReason = queryResult.reason
                )
            }
        }

        val allListings = searchAll(canonicalQuery, product.specifications)
        val exactMatches = allListings.filter {
            it.hasVerifiedPrice && (it.matchType == MatchType.EXACT || it.matchType == MatchType.LIKELY)
        }
        val similarAlternatives = allListings.filter {
            it.hasVerifiedPrice && it.matchType == MatchType.SIMILAR
        }
        val searchLinks = allListings.filter {
            it.listingOrigin == com.paypal.visualcart.domain.model.ListingOrigin.DIRECT_SEARCH_URL
        }
        val unavailable = allListings.filter {
            it.listingOrigin == com.paypal.visualcart.domain.model.ListingOrigin.NO_RESULT ||
                it.availability == AvailabilityStatus.OUT_OF_STOCK
        }
        val summary = MerchantComparisonSummary.fromListings(canonicalQuery, exactMatches + similarAlternatives + searchLinks)
        com.paypal.visualcart.domain.model.MerchantDiscoveryResult(
            canonicalQuery = canonicalQuery,
            rejectedReason = null,
            exactMatches = exactMatches,
            similarAlternatives = similarAlternatives,
            searchLinks = searchLinks,
            unavailableMerchants = unavailable,
            comparisonSummary = summary
        )
    }

    suspend fun lookupSharedUrl(
        url: String,
        context: android.content.Context? = null
    ): Channel3DiscoveryResponse = withContext(Dispatchers.IO) {
        Channel3DiscoveryClient(context).lookupUrl(url)
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

        fun liveProviders(context: android.content.Context): List<MerchantSearchProvider> =
            listOf(Channel3LiveProvider(context)) + defaultProviders().onEach { it.isLiveMode = true }
    }
}
