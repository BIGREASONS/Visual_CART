package com.paypal.visualcart.data.repository

import com.paypal.visualcart.data.local.dao.CartDao
import com.paypal.visualcart.data.local.entity.CollectionEntity
import com.paypal.visualcart.data.local.entity.MerchantListingEntity
import com.paypal.visualcart.data.local.entity.PriceObservationEntity
import com.paypal.visualcart.data.local.entity.SavedProductEntity
import com.paypal.visualcart.data.local.entity.SavedProductWithListings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface CartRepository {
    fun getCartProductsFlow(): Flow<List<SavedProductWithListings>>
    suspend fun getCartProducts(): List<SavedProductWithListings>
    suspend fun getProductById(id: String): SavedProductWithListings?
    suspend fun saveProductWithListings(
        title: String,
        brand: String?,
        category: String,
        imageUri: String?,
        originalSource: String?,
        canonicalProductId: String = title.lowercase().replace("\\s+".toRegex(), "-"),
        listings: List<MerchantListingEntity>
    ): SavedProductWithListings
    suspend fun removeProduct(productId: String)
    suspend fun clearCart()
    suspend fun setSelectedListing(productId: String, listingId: String)
    suspend fun addAlternativeListing(productId: String, listing: MerchantListingEntity)
    suspend fun getCollections(): List<CollectionEntity>
    fun getCollectionsFlow(): Flow<List<CollectionEntity>>
    suspend fun addCollection(name: String)
    suspend fun assignToCollection(productId: String, collectionId: String?)
    suspend fun recordPriceObservation(listingId: String, newPrice: Double, sourceType: String = "LIVE")
    suspend fun searchCart(query: String): List<SavedProductWithListings>
}

class CartRepositoryImpl(private val cartDao: CartDao) : CartRepository {

    override fun getCartProductsFlow(): Flow<List<SavedProductWithListings>> {
        return cartDao.getAllSavedProductsFlow()
    }

    override suspend fun getCartProducts(): List<SavedProductWithListings> {
        return cartDao.getAllSavedProducts()
    }

    override suspend fun getProductById(id: String): SavedProductWithListings? {
        return cartDao.getProductById(id)
    }

    override suspend fun saveProductWithListings(
        title: String,
        brand: String?,
        category: String,
        imageUri: String?,
        originalSource: String?,
        canonicalProductId: String,
        listings: List<MerchantListingEntity>
    ): SavedProductWithListings {
        // Deduplication check: check if an equivalent product already exists
        val existing = cartDao.findProductByCanonicalId(canonicalProductId)
        val productEntity = if (existing != null) {
            existing.product.copy(
                updatedAt = System.currentTimeMillis(),
                imageUri = imageUri ?: existing.product.imageUri,
                originalSource = originalSource ?: existing.product.originalSource
            ).also { cartDao.updateProduct(it) }
        } else {
            SavedProductEntity(
                id = UUID.randomUUID().toString(),
                canonicalProductId = canonicalProductId,
                title = title,
                brand = brand,
                category = category,
                imageUri = imageUri,
                originalSource = originalSource,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ).also { cartDao.insertProduct(it) }
        }

        // Prepare listings associated with the saved product id
        val preparedListings = listings.map { listing ->
            listing.copy(
                id = if (listing.id.isBlank()) UUID.randomUUID().toString() else listing.id,
                savedProductId = productEntity.id,
                lastVerifiedAt = System.currentTimeMillis()
            )
        }
        cartDao.insertListings(preparedListings)

        return cartDao.getProductById(productEntity.id)!!
    }

    override suspend fun removeProduct(productId: String) {
        cartDao.deleteProduct(productId)
    }

    override suspend fun clearCart() {
        cartDao.deleteAllProducts()
    }

    override suspend fun setSelectedListing(productId: String, listingId: String) {
        cartDao.setSelectedListing(productId, listingId)
    }

    override suspend fun addAlternativeListing(productId: String, listing: MerchantListingEntity) {
        cartDao.insertListing(listing.copy(savedProductId = productId))
    }

    override suspend fun getCollections(): List<CollectionEntity> {
        return cartDao.getAllCollections()
    }

    override fun getCollectionsFlow(): Flow<List<CollectionEntity>> {
        return cartDao.getAllCollectionsFlow()
    }

    override suspend fun addCollection(name: String) {
        cartDao.insertCollection(CollectionEntity(name = name))
    }

    override suspend fun assignToCollection(productId: String, collectionId: String?) {
        cartDao.assignProductToCollection(productId, collectionId)
    }

    override suspend fun recordPriceObservation(listingId: String, newPrice: Double, sourceType: String) {
        cartDao.recordPriceObservation(
            PriceObservationEntity(
                merchantListingId = listingId,
                price = newPrice,
                observedAt = System.currentTimeMillis(),
                sourceType = sourceType
            )
        )
    }

    override suspend fun searchCart(query: String): List<SavedProductWithListings> {
        val q = query.trim().lowercase()
        return cartDao.getAllSavedProducts().filter { item ->
            item.product.title.lowercase().contains(q) ||
            item.product.brand?.lowercase()?.contains(q) == true ||
            item.product.category.lowercase().contains(q) ||
            item.listings.any { it.merchant.lowercase().contains(q) || it.title.lowercase().contains(q) }
        }
    }
}
