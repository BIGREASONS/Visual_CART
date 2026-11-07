package com.paypal.visualcart.domain.model

import com.paypal.visualcart.feature.merchant.ShippingEstimate
import com.paypal.visualcart.feature.merchant.ShippingEstimator

/**
 * Match type indicating how closely a merchant listing corresponds to the user's target query or scanned product.
 */
enum class MatchType {
    EXACT,
    LIKELY,
    SIMILAR
}

/**
 * Stock availability status for a merchant listing.
 */
enum class AvailabilityStatus {
    IN_STOCK,
    LIMITED,
    OUT_OF_STOCK,
    UNKNOWN
}

/**
 * Distinguishes verified live merchant data from local demo test fixtures.
 */
enum class SourceType {
    LIVE,
    DEMO
}

/**
 * Detailed origin / data source category of a merchant listing.
 * Distinguishes between live merchant API responses, direct search URL navigation links,
 * and verified demo catalog fixtures.
 */
enum class ListingOrigin {
    /** Real-time response from live merchant API / scrape gateway */
    LIVE_API,

    /** Direct merchant search URL generated for fallback navigation */
    DIRECT_SEARCH_URL,

    /** Verified pre-indexed benchmark fixture with validated specs and pricing */
    VERIFIED_CATALOG_FIXTURE
}

/**
 * Represents a single product listing discovered across supported e-commerce merchants.
 */
data class MerchantListing(
    val id: String,
    val merchantName: String,
    val merchantLogoRes: Int? = null,
    val merchantDomain: String,
    val title: String,
    val price: Double,
    val originalPrice: Double? = null,
    val currency: String = "INR",
    val productUrl: String,
    val imageUrl: String,
    val matchType: MatchType,
    val confidence: Float,
    val availability: AvailabilityStatus,
    val sourceType: SourceType,
    val lastVerifiedAt: Long,
    val deliveryEstimate: String? = null,
    val rating: Float? = null,
    val reviewCount: Int? = null,
    val sellerName: String? = null,
    val listingOrigin: ListingOrigin = if (sourceType == SourceType.LIVE) {
        ListingOrigin.LIVE_API
    } else if (productUrl.contains("/s?k=") || productUrl.contains("/search") || id.contains("_syn_") || id.contains("_search_")) {
        ListingOrigin.DIRECT_SEARCH_URL
    } else {
        ListingOrigin.VERIFIED_CATALOG_FIXTURE
    }
) {
    val discountPercentage: Int?
        get() = if (originalPrice != null && originalPrice > price) {
            (((originalPrice - price) / originalPrice) * 100).toInt()
        } else null

    val isDemo: Boolean
        get() = sourceType == SourceType.DEMO

    val isLive: Boolean
        get() = sourceType == SourceType.LIVE

    val isLiveApi: Boolean
        get() = listingOrigin == ListingOrigin.LIVE_API || sourceType == SourceType.LIVE

    val isDirectSearchUrl: Boolean
        get() = listingOrigin == ListingOrigin.DIRECT_SEARCH_URL

    val isVerifiedCatalogFixture: Boolean
        get() = listingOrigin == ListingOrigin.VERIFIED_CATALOG_FIXTURE

    val originLabel: String
        get() = when (listingOrigin) {
            ListingOrigin.LIVE_API -> "LIVE API"
            ListingOrigin.DIRECT_SEARCH_URL -> "DIRECT SEARCH"
            ListingOrigin.VERIFIED_CATALOG_FIXTURE -> "VERIFIED FIXTURE"
        }

    /**
     * Unmistakable provenance tag combining source type, match quality, and data origin.
     * E.g. "[DEMO | EXACT | VERIFIED FIXTURE]" or "[LIVE | EXACT | LIVE API]".
     */
    val provenanceTag: String
        get() = "[${sourceType.name} | ${matchType.name} | $originLabel]"

    /**
     * Structured delivery speed, free shipping eligibility, and estimated days.
     */
    val parsedShippingEstimate: ShippingEstimate
        get() = ShippingEstimator.estimate(
            deliveryText = deliveryEstimate,
            merchantId = id.substringBefore("_"),
            price = price
        )
}

/**
 * Normalized summary comparing listings across multiple merchants for a single canonical product.
 */
data class MerchantComparisonSummary(
    val canonicalTitle: String,
    val lowestPriceListing: MerchantListing,
    val listings: List<MerchantListing>
) {
    val highestPrice: Double
        get() = listings.maxOfOrNull { it.price } ?: lowestPriceListing.price

    val lowestPrice: Double
        get() = lowestPriceListing.price

    val maxSavings: Double
        get() = (highestPrice - lowestPrice).coerceAtLeast(0.0)

    val exactMatchCount: Int
        get() = listings.count { it.matchType == MatchType.EXACT }

    val hasDemoData: Boolean
        get() = listings.any { it.sourceType == SourceType.DEMO }

    val hasLiveMatches: Boolean
        get() = listings.any { it.sourceType == SourceType.LIVE }

    companion object {
        fun fromListings(canonicalTitle: String, listings: List<MerchantListing>): MerchantComparisonSummary? {
            if (listings.isEmpty()) return null

            // Prioritize lowest price within best match tier: EXACT first, then LIKELY, then SIMILAR
            val exactListings = listings.filter { it.matchType == MatchType.EXACT }
            val likelyListings = listings.filter { it.matchType == MatchType.LIKELY }
            val candidatePool = when {
                exactListings.isNotEmpty() -> exactListings
                likelyListings.isNotEmpty() -> likelyListings
                else -> listings
            }
            val lowest = candidatePool.minByOrNull { it.price } ?: listings.minByOrNull { it.price } ?: return null

            // Robust multi-criteria sort:
            // 1. Match type hierarchy (EXACT -> LIKELY -> SIMILAR)
            // 2. Availability (IN_STOCK -> LIMITED -> UNKNOWN -> OUT_OF_STOCK)
            // 3. Lowest price ascending
            // 4. Highest confidence descending
            val sortedListings = listings.sortedWith(
                compareBy<MerchantListing> {
                    when (it.matchType) {
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

            return MerchantComparisonSummary(
                canonicalTitle = canonicalTitle,
                lowestPriceListing = lowest,
                listings = sortedListings
            )
        }
    }
}

/**
 * Extracted product metadata parsed from a merchant URL.
 */
data class ParsedProductMetadata(
    val url: String,
    val domain: String,
    val title: String?,
    val productId: String?,
    val canonicalUrl: String,
    val category: String? = null,
    val extraSpecs: Map<String, String> = emptyMap()
)
