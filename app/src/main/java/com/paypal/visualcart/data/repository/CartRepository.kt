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
    suspend fun replaceProductListings(productId: String, listings: List<MerchantListingEntity>)
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
        val effectiveCanonicalId = canonicalProductId.takeIf { it.isNotBlank() }
            ?: com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeProductIdentityKey(title)
        val safeImageUri = imageUri?.takeIf { it.isNotBlank() }
        val safeOriginalSource = originalSource?.takeIf { it.isNotBlank() }

        // Deduplication check: check if an equivalent product already exists
        val existing = cartDao.findProductByCanonicalId(effectiveCanonicalId)
        val productEntity = if (existing != null) {
            existing.product.copy(
                updatedAt = System.currentTimeMillis(),
                imageUri = safeImageUri ?: existing.product.imageUri,
                originalSource = safeOriginalSource ?: existing.product.originalSource
            ).also { cartDao.updateProduct(it) }
        } else {
            SavedProductEntity(
                id = UUID.randomUUID().toString(),
                canonicalProductId = effectiveCanonicalId,
                title = title,
                brand = brand,
                category = category,
                imageUri = safeImageUri,
                originalSource = safeOriginalSource,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ).also { cartDao.insertProduct(it) }
        }

        // Deduplicate incoming listings by normalized merchant key, preferring priced/selected/confident rows
        val now = System.currentTimeMillis()
        val dedupedByMerchant = linkedMapOf<String, MerchantListingEntity>()
        for (rawListing in listings) {
            val merchantKey = com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(rawListing.merchant)
            val normalizedPrice = rawListing.price?.takeIf {
                it > 0.0 &&
                    rawListing.listingOrigin != "DIRECT_SEARCH_URL" &&
                    rawListing.listingOrigin != "NO_RESULT"
            }
            val cleaned = rawListing.copy(
                id = if (rawListing.id.isBlank()) UUID.randomUUID().toString() else rawListing.id,
                savedProductId = productEntity.id,
                price = normalizedPrice,
                lastVerifiedAt = now
            )
            val currentBest = dedupedByMerchant[merchantKey]
            if (currentBest == null) {
                dedupedByMerchant[merchantKey] = cleaned
            } else {
                val replace = (!currentBest.hasVerifiedPrice && cleaned.hasVerifiedPrice) ||
                    (currentBest.hasVerifiedPrice == cleaned.hasVerifiedPrice && (
                        (!currentBest.isSelectedForPurchase && cleaned.isSelectedForPurchase) ||
                            (cleaned.confidence > currentBest.confidence) ||
                            ((cleaned.price ?: Double.MAX_VALUE) < (currentBest.price ?: Double.MAX_VALUE))
                        ))
                if (replace) {
                    dedupedByMerchant[merchantKey] = cleaned.copy(
                        isSelectedForPurchase = cleaned.isSelectedForPurchase || currentBest.isSelectedForPurchase
                    )
                } else if (cleaned.isSelectedForPurchase && !currentBest.isSelectedForPurchase) {
                    dedupedByMerchant[merchantKey] = currentBest.copy(isSelectedForPurchase = true)
                }
            }
        }

        // Ensure at most one listing is marked selected per saved product
        var seenSelected = false
        val preparedListings = dedupedByMerchant.values.map { item ->
            if (item.isSelectedForPurchase) {
                if (seenSelected) {
                    item.copy(isSelectedForPurchase = false)
                } else {
                    seenSelected = true
                    item
                }
            } else {
                item
            }
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

    override suspend fun replaceProductListings(productId: String, listings: List<MerchantListingEntity>) {
        cartDao.replaceListingsForProduct(productId, listings)
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
