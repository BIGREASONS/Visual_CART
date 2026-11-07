package com.paypal.visualcart.data.local.dao

import com.paypal.visualcart.data.local.entity.CollectionEntity
import com.paypal.visualcart.data.local.entity.MerchantListingEntity
import com.paypal.visualcart.data.local.entity.PriceObservationEntity
import com.paypal.visualcart.data.local.entity.SavedProductEntity
import com.paypal.visualcart.data.local.entity.SavedProductWithListings
import com.paypal.visualcart.data.local.entity.ShoppingPreferenceEntity
import kotlinx.coroutines.flow.Flow

interface CartDao {
    fun getAllSavedProductsFlow(): Flow<List<SavedProductWithListings>>
    suspend fun getAllSavedProducts(): List<SavedProductWithListings>
    suspend fun getProductById(id: String): SavedProductWithListings?
    suspend fun findProductByCanonicalId(canonicalId: String): SavedProductWithListings?
    suspend fun insertProduct(product: SavedProductEntity): Long
    suspend fun updateProduct(product: SavedProductEntity)
    suspend fun deleteProduct(id: String)
    suspend fun deleteAllProducts()

    // Listings
    suspend fun insertListing(listing: MerchantListingEntity): Long
    suspend fun insertListings(listings: List<MerchantListingEntity>)
    suspend fun getListingsForProduct(productId: String): List<MerchantListingEntity>
    suspend fun setSelectedListing(productId: String, listingId: String)
    suspend fun deleteListing(id: String)

    // Price observations
    suspend fun recordPriceObservation(observation: PriceObservationEntity): Long
    suspend fun getPriceObservations(listingId: String): List<PriceObservationEntity>

    // Collections
    fun getAllCollectionsFlow(): Flow<List<CollectionEntity>>
    suspend fun getAllCollections(): List<CollectionEntity>
    suspend fun insertCollection(collection: CollectionEntity): Long
    suspend fun deleteCollection(id: String)
    suspend fun assignProductToCollection(productId: String, collectionId: String?)

    // Preferences
    suspend fun getPreference(key: String): String?
    suspend fun setPreference(key: String, value: String)
}
