package com.paypal.visualcart.data.local.entity

import java.util.UUID

/**
 * SavedProduct entity representing a canonical product saved in the persistent cart.
 */
data class SavedProductEntity(
    val id: String = UUID.randomUUID().toString(),
    val canonicalProductId: String,
    val title: String,
    val brand: String? = null,
    val category: String,
    val imageUri: String? = null,
    val originalSource: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val collectionId: String? = null,
    val notes: String? = null
)

/**
 * MerchantListing entity representing a merchant-specific offering for a saved product.
 */
data class MerchantListingEntity(
    val id: String = UUID.randomUUID().toString(),
    val savedProductId: String,
    val merchant: String,
    val title: String,
    val price: Double? = null,
    val currency: String = "INR",
    val productUrl: String,
    val imageUrl: String? = null,
    val matchType: String = "EXACT", // EXACT, LIKELY, SIMILAR
    val confidence: Float = 1.0f,
    val availability: String = "IN_STOCK", // IN_STOCK, LIMITED, OUT_OF_STOCK, UNKNOWN
    val sourceType: String = "DEMO", // LIVE, DEMO
    val listingOrigin: String = "VERIFIED_CATALOG_FIXTURE", // LIVE_API, DIRECT_SEARCH_URL, VERIFIED_CATALOG_FIXTURE, DEMO_DATA, NO_RESULT
    val lastVerifiedAt: Long = System.currentTimeMillis(),
    val deliveryEstimate: String? = null,
    val isSelectedForPurchase: Boolean = true
) {
    val hasVerifiedPrice: Boolean
        get() = price != null && price > 0.0 &&
            listingOrigin != "DIRECT_SEARCH_URL" &&
            listingOrigin != "NO_RESULT"
}

/**
 * Collection entity for organizing saved products.
 */
data class CollectionEntity(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * PriceObservation entity tracking price fluctuations over time for a merchant listing.
 */
data class PriceObservationEntity(
    val id: String = UUID.randomUUID().toString(),
    val merchantListingId: String,
    val price: Double,
    val currency: String = "INR",
    val observedAt: Long = System.currentTimeMillis(),
    val sourceType: String = "DEMO"
)

/**
 * ShoppingPreference entity for persisting user settings and filters.
 */
data class ShoppingPreferenceEntity(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    val value: String
)

/**
 * Compound relation combining a SavedProduct with all its associated MerchantListings.
 */
data class SavedProductWithListings(
    val product: SavedProductEntity,
    val listings: List<MerchantListingEntity>,
    val priceObservations: List<PriceObservationEntity> = emptyList()
) {
    val selectedListing: MerchantListingEntity?
        get() = listings.firstOrNull { it.isSelectedForPurchase } ?: listings.firstOrNull()

    val lowestPriceListing: MerchantListingEntity?
        get() = listings
            .filter {
                val p = it.price
                p != null && p > 0.0 &&
                    it.listingOrigin != "DIRECT_SEARCH_URL" &&
                    it.listingOrigin != "NO_RESULT"
            }
            .minByOrNull { it.price!! }

    val hasPriceDrop: Boolean
        get() {
            val current = selectedListing?.price ?: return false
            val past = priceObservations.filter { it.merchantListingId == selectedListing?.id }
            if (past.isEmpty()) return false
            val highestPast = past.maxOfOrNull { it.price } ?: current
            return current < highestPast
        }
}
