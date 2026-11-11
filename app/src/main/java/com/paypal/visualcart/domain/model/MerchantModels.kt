package com.paypal.visualcart.domain.model

import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import com.paypal.visualcart.feature.merchant.ShippingEstimate
import com.paypal.visualcart.feature.merchant.ShippingEstimator
import java.util.Locale

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
 * Detailed provenance of a merchant listing.
 */
enum class ListingOrigin {
    /** Real-time response from a live merchant API / scrape gateway */
    LIVE_API,

    /** Direct clickable merchant search URL; carries NO price, MRP, stock, or delivery claim */
    DIRECT_SEARCH_URL,

    /** Verified pre-indexed benchmark catalog fixture with validated pricing */
    VERIFIED_CATALOG_FIXTURE,

    /** Illustrative UI showcase / preview demo entry */
    DEMO_DATA,

    /** Merchant returned no verified offer for the canonical product */
    NO_RESULT
}

/**
 * Represents a single product listing or store search link across supported e-commerce merchants.
 */
data class MerchantListing(
    val id: String,
    val merchantName: String,
    val merchantLogoRes: Int? = null,
    val merchantDomain: String,
    val title: String,
    val price: Double?,
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
    val listingOrigin: ListingOrigin = when {
        sourceType == SourceType.LIVE -> ListingOrigin.LIVE_API
        price == null && productUrl.isBlank() -> ListingOrigin.NO_RESULT
        price == null -> ListingOrigin.DIRECT_SEARCH_URL
        id.contains("_demo_", ignoreCase = true) -> ListingOrigin.DEMO_DATA
        else -> ListingOrigin.VERIFIED_CATALOG_FIXTURE
    },
    val isReferencePriceVerified: Boolean = false
) {
    init {
        when (listingOrigin) {
            ListingOrigin.DIRECT_SEARCH_URL, ListingOrigin.NO_RESULT -> {
                require(price == null) {
                    "Listings with origin $listingOrigin must not carry a fabricated price (found $price)."
                }
                require(originalPrice == null) {
                    "Listings with origin $listingOrigin must not carry a fabricated originalPrice."
                }
                require(availability == AvailabilityStatus.UNKNOWN) {
                    "Listings with origin $listingOrigin must use AvailabilityStatus.UNKNOWN."
                }
                require(deliveryEstimate == null) {
                    "Listings with origin $listingOrigin must not claim delivery estimates."
                }
                require(rating == null && reviewCount == null) {
                    "Listings with origin $listingOrigin must not invent ratings or reviews."
                }
            }
            ListingOrigin.LIVE_API -> {
                require(sourceType == SourceType.LIVE) {
                    "LIVE_API listing must use SourceType.LIVE."
                }
                require(price != null && price > 0.0 && price.isFinite()) {
                    "LIVE_API listing requires positive finite price."
                }
            }
            ListingOrigin.VERIFIED_CATALOG_FIXTURE, ListingOrigin.DEMO_DATA -> {
                require(sourceType == SourceType.DEMO) {
                    "$listingOrigin listing must use SourceType.DEMO."
                }
                require(price != null && price > 0.0 && price.isFinite()) {
                    "$listingOrigin listing requires positive finite price."
                }
            }
        }
    }

    val normalizedMerchantId: String
        get() = SearchQueryBuilder.normalizeMerchantKey(
            merchantDomain.ifBlank { merchantName }
        )

    val hasVerifiedPrice: Boolean
        get() = price != null &&
            price > 0.0 &&
            price.isFinite() &&
            listingOrigin != ListingOrigin.DIRECT_SEARCH_URL &&
            listingOrigin != ListingOrigin.NO_RESULT

    val discountPercentage: Int?
        get() {
            val currentPrice = price
            if (!hasVerifiedPrice || currentPrice == null || originalPrice == null || originalPrice <= currentPrice) {
                return null
            }
            return (((originalPrice - currentPrice) / originalPrice) * 100).toInt()
        }

    val isDemo: Boolean
        get() = sourceType == SourceType.DEMO

    val isLive: Boolean
        get() = sourceType == SourceType.LIVE && listingOrigin == ListingOrigin.LIVE_API

    val isLiveApi: Boolean
        get() = listingOrigin == ListingOrigin.LIVE_API

    val isDirectSearchUrl: Boolean
        get() = listingOrigin == ListingOrigin.DIRECT_SEARCH_URL

    val isVerifiedCatalogFixture: Boolean
        get() = listingOrigin == ListingOrigin.VERIFIED_CATALOG_FIXTURE

    val isDemoData: Boolean
        get() = listingOrigin == ListingOrigin.DEMO_DATA

    val isNoResult: Boolean
        get() = listingOrigin == ListingOrigin.NO_RESULT

    val originLabel: String
        get() = when (listingOrigin) {
            ListingOrigin.LIVE_API -> "LIVE API"
            ListingOrigin.DIRECT_SEARCH_URL -> "DIRECT SEARCH"
            ListingOrigin.VERIFIED_CATALOG_FIXTURE -> "VERIFIED FIXTURE"
            ListingOrigin.DEMO_DATA -> "DEMO DATA"
            ListingOrigin.NO_RESULT -> "NO RESULT"
        }

    /**
     * Unmistakable provenance tag combining source type, match quality, and data origin.
     */
    val provenanceTag: String
        get() = "[${sourceType.name} | ${matchType.name} | $originLabel]"

    /**
     * Structured delivery speed, free shipping eligibility, and estimated days.
     * Returns null when pricing or shipping evidence is absent (such as search links).
     */
    val parsedShippingEstimate: ShippingEstimate?
        get() = ShippingEstimator.estimateOrNull(
            deliveryText = deliveryEstimate,
            merchantId = normalizedMerchantId,
            price = price,
            currency = currency,
            listingOrigin = listingOrigin
        )
}

/**
 * Normalized summary comparing listings across multiple merchants for a single canonical product.
 */
data class MerchantComparisonSummary(
    val canonicalTitle: String,
    val lowestPriceListing: MerchantListing?,
    val listings: List<MerchantListing>,
    val comparableCurrency: String? = lowestPriceListing?.currency,
    val comparableMerchantCount: Int = 0,
    val hasVerifiedReferenceSavings: Boolean = false
) {
    val pricedListings: List<MerchantListing>
        get() = listings.filter { it.hasVerifiedPrice && it.availability != AvailabilityStatus.OUT_OF_STOCK }

    val comparablePricedListings: List<MerchantListing>
        get() {
            val baseCurrency = comparableCurrency ?: return emptyList()
            val validCurrencyPool = pricedListings.filter {
                it.currency.equals(baseCurrency, ignoreCase = true)
            }
            return selectComparableTierPool(validCurrencyPool)
        }

    val hasComparableBestPrice: Boolean
        get() = lowestPriceListing != null && comparableMerchantCount >= 2

    val highestPrice: Double?
        get() {
            val pool = comparablePricedListings
            return if (pool.size >= 2) pool.mapNotNull { it.price }.maxOrNull() else lowestPriceListing?.price
        }

    val lowestPrice: Double?
        get() = lowestPriceListing?.price

    val maxSavings: Double
        get() {
            if (!hasComparableBestPrice) return 0.0
            val low = lowestPrice ?: return 0.0
            val high = highestPrice ?: return 0.0
            return (high - low).coerceAtLeast(0.0)
        }

    val verifiedSavings: Double?
        get() {
            if (hasComparableBestPrice && maxSavings > 0.0) return maxSavings
            val best = lowestPriceListing ?: return null
            if (best.isReferencePriceVerified && best.originalPrice != null && best.price != null && best.originalPrice > best.price) {
                return best.originalPrice - best.price
            }
            return null
        }

    val exactMatchCount: Int
        get() = listings.count { it.matchType == MatchType.EXACT && it.hasVerifiedPrice }

    val hasDemoData: Boolean
        get() = listings.any { it.sourceType == SourceType.DEMO }

    val hasLiveMatches: Boolean
        get() = listings.any { it.isLiveApi }

    val searchLinksOnly: Boolean
        get() = listings.isNotEmpty() && pricedListings.isEmpty()

    companion object {
        fun fromListings(canonicalTitle: String, listings: List<MerchantListing>): MerchantComparisonSummary? {
            if (listings.isEmpty()) return null

            val sortedListings = sortCanonicalListings(listings)
            val eligiblePriced = sortedListings.filter {
                it.hasVerifiedPrice && it.availability != AvailabilityStatus.OUT_OF_STOCK
            }

            if (eligiblePriced.isEmpty()) {
                return MerchantComparisonSummary(
                    canonicalTitle = canonicalTitle,
                    lowestPriceListing = null,
                    listings = sortedListings,
                    comparableCurrency = null,
                    comparableMerchantCount = 0,
                    hasVerifiedReferenceSavings = false
                )
            }

            // Pick comparable match tier (prefer >=2 EXACT merchants, else EXACT+LIKELY; never let cheap SIMILAR accessories skew exact comparisons)
            val tierPool = selectComparableTierPool(eligiblePriced)

            // Restrict comparison to a single consistent currency so USD & INR are never subtracted
            val dominantCurrency = tierPool
                .groupBy { it.currency.uppercase(Locale.ROOT) }
                .maxByOrNull { it.value.size }
                ?.key ?: "INR"

            val sameCurrencyPool = tierPool.filter { it.currency.equals(dominantCurrency, ignoreCase = true) }
            val lowest = sameCurrencyPool.minByOrNull { it.price ?: Double.MAX_VALUE }

            val distinctComparableMerchants = sameCurrencyPool
                .map { it.normalizedMerchantId }
                .distinct()
                .size

            val refVerified = lowest != null && (
                distinctComparableMerchants >= 2 ||
                    (lowest.isReferencePriceVerified && lowest.originalPrice != null && (lowest.price ?: 0.0) < lowest.originalPrice)
                )

            return MerchantComparisonSummary(
                canonicalTitle = canonicalTitle,
                lowestPriceListing = lowest,
                listings = sortedListings,
                comparableCurrency = dominantCurrency,
                comparableMerchantCount = distinctComparableMerchants,
                hasVerifiedReferenceSavings = refVerified
            )
        }

        private fun selectComparableTierPool(eligiblePriced: List<MerchantListing>): List<MerchantListing> {
            val exactListings = eligiblePriced.filter { it.matchType == MatchType.EXACT }
            val hasTwoSameCurrencyExactMerchants = exactListings
                .groupBy { it.currency.uppercase(Locale.ROOT) }
                .values
                .any { pool -> pool.map { it.normalizedMerchantId }.distinct().size >= 2 }
            if (hasTwoSameCurrencyExactMerchants) {
                return exactListings
            }
            val exactOrLikely = eligiblePriced.filter {
                it.matchType == MatchType.EXACT || it.matchType == MatchType.LIKELY
            }
            return exactOrLikely.ifEmpty { eligiblePriced }
        }

        fun sortCanonicalListings(listings: List<MerchantListing>): List<MerchantListing> {
            return listings.sortedWith(
                compareByDescending<MerchantListing> { it.hasVerifiedPrice }
                    .thenBy {
                        when (it.listingOrigin) {
                            ListingOrigin.LIVE_API -> 0
                            ListingOrigin.VERIFIED_CATALOG_FIXTURE -> 1
                            ListingOrigin.DEMO_DATA -> 2
                            ListingOrigin.DIRECT_SEARCH_URL -> 3
                            ListingOrigin.NO_RESULT -> 4
                        }
                    }
                    .thenBy {
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
                    .thenBy { it.price ?: Double.MAX_VALUE }
                    .thenByDescending { it.confidence }
            )
        }
    }
}

/**
 * Structured output from multi-merchant discovery separating verified offers, alternatives,
 * store search links, and unavailable merchants.
 */
data class MerchantDiscoveryResult(
    val canonicalQuery: String?,
    val rejectedReason: SearchQueryBuilder.RejectionReason? = null,
    val exactMatches: List<MerchantListing> = emptyList(),
    val similarAlternatives: List<MerchantListing> = emptyList(),
    val searchLinks: List<MerchantListing> = emptyList(),
    val unavailableMerchants: List<MerchantListing> = emptyList(),
    val comparisonSummary: MerchantComparisonSummary? = null
) {
    val allInteractiveListings: List<MerchantListing>
        get() = (exactMatches + similarAlternatives + searchLinks)
            .distinctBy { "${it.normalizedMerchantId}::${SearchQueryBuilder.normalizeProductIdentityKey(it.title)}" }
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
