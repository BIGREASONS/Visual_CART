package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.domain.model.SourceType
import kotlin.math.abs

/**
 * Common contract for all e-commerce merchant discovery adapters.
 *
 * Each provider represents a specific merchant (e.g. Amazon India, Flipkart, Croma),
 * producing valid search URLs, parsing merchant product URLs (including IDs and titles),
 * and returning normalized [MerchantListing] results clearly tagged as LIVE, DEMO, or DIRECT SEARCH.
 */
interface MerchantSearchProvider {
    /**
     * Unique identifier for the merchant (e.g. "amazon_in", "flipkart").
     */
    val merchantId: String

    /**
     * Human-readable display name for the merchant (e.g. "Amazon India").
     */
    val merchantName: String

    /**
     * Merchant primary web domain (e.g. "amazon.in").
     */
    val domain: String

    /**
     * Flag indicating whether the adapter should query live merchant APIs or fallback to fixtures.
     */
    var isLiveMode: Boolean

    /**
     * Searches the merchant catalog for products matching [query].
     * Can optionally use [targetSpecs] to refine ranking, match types, or filters.
     * All returned listings are explicitly tagged with [SourceType], [MatchType], and [ListingOrigin].
     */
    suspend fun search(
        query: String,
        targetSpecs: Map<String, String> = emptyMap()
    ): List<MerchantListing>

    /**
     * Constructs a valid, clickable browser URL to search this merchant directly.
     */
    fun buildSearchUrl(query: String): String

    /**
     * Parses a merchant URL to extract canonical product ID (e.g. ASIN), slug title,
     * domain, and canonical URL. Returns null if the URL does not belong to this merchant.
     */
    fun parseProductUrl(url: String): ParsedProductMetadata?

    /**
     * Constructs a standalone direct merchant search URL listing for fallback navigation.
     * Unmistakably tagged with [ListingOrigin.DIRECT_SEARCH_URL], [SourceType.DEMO], and [MatchType.SIMILAR].
     */
    fun buildDirectSearchListing(query: String): MerchantListing {
        val cleanQuery = query.trim()
        val searchUrl = buildSearchUrl(cleanQuery)
        val title = "Search \"$cleanQuery\" on $merchantName"
        val hash = abs(cleanQuery.hashCode() xor merchantId.hashCode())

        return MerchantListing(
            id = "${merchantId}_search_${hash % 100000}",
            merchantName = merchantName,
            merchantDomain = domain,
            title = title,
            price = 0.0,
            originalPrice = null,
            currency = "INR",
            productUrl = searchUrl,
            imageUrl = "https://via.placeholder.com/600x600.png?text=${MerchantUrlHelper.urlEncode(merchantName)}",
            matchType = MatchType.SIMILAR,
            confidence = 0.70f,
            availability = AvailabilityStatus.UNKNOWN,
            sourceType = SourceType.DEMO,
            lastVerifiedAt = System.currentTimeMillis(),
            deliveryEstimate = "Varies by merchant seller",
            rating = null,
            reviewCount = null,
            sellerName = "$merchantName Search",
            listingOrigin = ListingOrigin.DIRECT_SEARCH_URL
        )
    }
}
