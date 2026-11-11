package com.paypal.visualcart.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.paypal.visualcart.data.local.dao.CartDao
import com.paypal.visualcart.data.local.entity.CollectionEntity
import com.paypal.visualcart.data.local.entity.MerchantListingEntity
import com.paypal.visualcart.data.local.entity.PriceObservationEntity
import com.paypal.visualcart.data.local.entity.SavedProductEntity
import com.paypal.visualcart.data.local.entity.SavedProductWithListings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * SQLite Database implementation for Visual Cart providing full Room-equivalent persistence.
 */
class VisualCartDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), CartDao {

    companion object {
        const val DATABASE_NAME = "visual_cart.db"
        const val DATABASE_VERSION = 3

        @Volatile
        private var INSTANCE: VisualCartDatabase? = null

        fun getInstance(context: Context): VisualCartDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VisualCartDatabase(context).also { INSTANCE = it }
            }
        }
    }

    private val productsFlow = MutableStateFlow<List<SavedProductWithListings>>(emptyList())
    private val collectionsFlow = MutableStateFlow<List<CollectionEntity>>(emptyList())

    init {
        // Pre-populate default collections if needed and initialize flows
        refreshFlows()
    }

    private fun refreshFlows() {
        val allProducts = loadAllSavedProductsInternal()
        productsFlow.value = allProducts

        val allCollections = loadAllCollectionsInternal()
        collectionsFlow.value = allCollections
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE saved_products (
                id TEXT PRIMARY KEY,
                canonical_product_id TEXT NOT NULL,
                title TEXT NOT NULL,
                brand TEXT,
                category TEXT NOT NULL,
                image_uri TEXT,
                original_source TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                collection_id TEXT,
                notes TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE merchant_listings (
                id TEXT PRIMARY KEY,
                saved_product_id TEXT NOT NULL,
                merchant TEXT NOT NULL,
                title TEXT NOT NULL,
                price REAL,
                original_price REAL,
                currency TEXT NOT NULL,
                product_url TEXT NOT NULL,
                image_url TEXT,
                match_type TEXT NOT NULL,
                confidence REAL NOT NULL,
                availability TEXT NOT NULL,
                source_type TEXT NOT NULL,
                listing_origin TEXT NOT NULL DEFAULT 'VERIFIED_CATALOG_FIXTURE',
                provider_product_id TEXT,
                offer_source TEXT,
                last_verified_at INTEGER NOT NULL,
                delivery_estimate TEXT,
                is_selected INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (saved_product_id) REFERENCES saved_products (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE collections (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE price_observations (
                id TEXT PRIMARY KEY,
                merchant_listing_id TEXT NOT NULL,
                price REAL NOT NULL,
                currency TEXT NOT NULL,
                observed_at INTEGER NOT NULL,
                source_type TEXT NOT NULL,
                FOREIGN KEY (merchant_listing_id) REFERENCES merchant_listings (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE shopping_preferences (
                id TEXT PRIMARY KEY,
                pref_key TEXT NOT NULL UNIQUE,
                pref_value TEXT NOT NULL
            )
            """.trimIndent()
        )

        // Seed default collections
        val defaultCollections = listOf("Electronics", "Fashion", "Gaming", "Home", "Accessories")
        for (col in defaultCollections) {
            val cv = ContentValues().apply {
                put("id", java.util.UUID.randomUUID().toString())
                put("name", col)
                put("created_at", System.currentTimeMillis())
            }
            db.insert("collections", null, cv)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migrate merchant_listings so price is nullable and listing_origin is persisted
            db.execSQL(
                """
                CREATE TABLE merchant_listings_v2 (
                    id TEXT PRIMARY KEY,
                    saved_product_id TEXT NOT NULL,
                    merchant TEXT NOT NULL,
                    title TEXT NOT NULL,
                    price REAL,
                    currency TEXT NOT NULL,
                    product_url TEXT NOT NULL,
                    image_url TEXT,
                    match_type TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    availability TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    listing_origin TEXT NOT NULL DEFAULT 'VERIFIED_CATALOG_FIXTURE',
                    last_verified_at INTEGER NOT NULL,
                    delivery_estimate TEXT,
                    is_selected INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY (saved_product_id) REFERENCES saved_products (id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO merchant_listings_v2 (
                    id, saved_product_id, merchant, title, price, currency, product_url,
                    image_url, match_type, confidence, availability, source_type,
                    listing_origin, last_verified_at, delivery_estimate, is_selected
                )
                SELECT
                    id, saved_product_id, merchant, title,
                    CASE WHEN price <= 0.0 THEN NULL ELSE price END,
                    currency, product_url, image_url, match_type, confidence, availability,
                    source_type,
                    CASE WHEN price IS NULL OR price <= 0.0 THEN 'DIRECT_SEARCH_URL' ELSE 'VERIFIED_CATALOG_FIXTURE' END,
                    last_verified_at, delivery_estimate, is_selected
                FROM merchant_listings
                """.trimIndent()
            )
            db.execSQL("DROP TABLE merchant_listings")
            db.execSQL("ALTER TABLE merchant_listings_v2 RENAME TO merchant_listings")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE merchant_listings ADD COLUMN original_price REAL")
            db.execSQL("ALTER TABLE merchant_listings ADD COLUMN provider_product_id TEXT")
            db.execSQL("ALTER TABLE merchant_listings ADD COLUMN offer_source TEXT")
        }
    }

    override fun getAllSavedProductsFlow(): Flow<List<SavedProductWithListings>> {
        refreshFlows()
        return productsFlow.asStateFlow()
    }

    override suspend fun getAllSavedProducts(): List<SavedProductWithListings> = withContext(Dispatchers.IO) {
        loadAllSavedProductsInternal()
    }

    private fun loadAllSavedProductsInternal(): List<SavedProductWithListings> {
        val result = mutableListOf<SavedProductWithListings>()
        val db = readableDatabase
        val cursor = db.query(
            "saved_products",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val product = cursorToSavedProduct(c)
                val listings = loadListingsForProductInternal(db, product.id)
                val listingIds = listings.map { it.id }
                val observations = loadPriceObservationsInternal(db, listingIds)
                result.add(SavedProductWithListings(product, listings, observations))
            }
        }
        return result
    }

    override suspend fun getProductById(id: String): SavedProductWithListings? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            "saved_products",
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null
        )
        cursor.use { c ->
            if (c.moveToNext()) {
                val product = cursorToSavedProduct(c)
                val listings = loadListingsForProductInternal(db, product.id)
                val listingIds = listings.map { it.id }
                val observations = loadPriceObservationsInternal(db, listingIds)
                return@withContext SavedProductWithListings(product, listings, observations)
            }
        }
        null
    }

    override suspend fun findProductByCanonicalId(canonicalId: String): SavedProductWithListings? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            "saved_products",
            null,
            "canonical_product_id = ?",
            arrayOf(canonicalId),
            null,
            null,
            null
        )
        cursor.use { c ->
            if (c.moveToNext()) {
                val product = cursorToSavedProduct(c)
                val listings = loadListingsForProductInternal(db, product.id)
                val listingIds = listings.map { it.id }
                val observations = loadPriceObservationsInternal(db, listingIds)
                return@withContext SavedProductWithListings(product, listings, observations)
            }
        }
        null
    }

    override suspend fun insertProduct(product: SavedProductEntity): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", product.id)
            put("canonical_product_id", product.canonicalProductId)
            put("title", product.title)
            put("brand", product.brand)
            put("category", product.category)
            put("image_uri", product.imageUri)
            put("original_source", product.originalSource)
            put("created_at", product.createdAt)
            put("updated_at", product.updatedAt)
            put("collection_id", product.collectionId)
            put("notes", product.notes)
        }
        val rowId = db.insertWithOnConflict("saved_products", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        refreshFlows()
        rowId
    }

    override suspend fun updateProduct(product: SavedProductEntity) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("canonical_product_id", product.canonicalProductId)
            put("title", product.title)
            put("brand", product.brand)
            put("category", product.category)
            put("image_uri", product.imageUri)
            put("original_source", product.originalSource)
            put("updated_at", System.currentTimeMillis())
            put("collection_id", product.collectionId)
            put("notes", product.notes)
        }
        db.update("saved_products", cv, "id = ?", arrayOf(product.id))
        refreshFlows()
        Unit
    }

    override suspend fun deleteProduct(id: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("saved_products", "id = ?", arrayOf(id))
        db.delete("merchant_listings", "saved_product_id = ?", arrayOf(id))
        refreshFlows()
        Unit
    }

    override suspend fun deleteAllProducts() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("saved_products", null, null)
        db.delete("merchant_listings", null, null)
        db.delete("price_observations", null, null)
        refreshFlows()
        Unit
    }

    override suspend fun insertListing(listing: MerchantListingEntity): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val rowId = insertListingInternal(db, listing)
        refreshFlows()
        rowId
    }

    override suspend fun insertListings(listings: List<MerchantListingEntity>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (listing in listings) {
                insertListingInternal(db, listing)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlows()
    }

    override suspend fun replaceListingsForProduct(
        productId: String,
        listings: List<MerchantListingEntity>
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("merchant_listings", "saved_product_id = ?", arrayOf(productId))
            for (listing in listings) {
                insertListingInternal(db, listing.copy(savedProductId = productId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlows()
    }

    private fun insertListingInternal(db: SQLiteDatabase, listing: MerchantListingEntity): Long {
        val targetMerchantKey = com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(listing.merchant)
        // Deduplicate normalized merchant within the same saved product
        val existingCursor = db.query(
            "merchant_listings",
            arrayOf("id", "merchant"),
            "saved_product_id = ?",
            arrayOf(listing.savedProductId),
            null,
            null,
            null
        )
        val duplicateIds = mutableListOf<String>()
        existingCursor.use { c ->
            while (c.moveToNext()) {
                val existingId = c.getString(0)
                val existingMerchant = c.getString(1)
                if (existingId != listing.id &&
                    com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(existingMerchant) == targetMerchantKey
                ) {
                    duplicateIds.add(existingId)
                }
            }
        }
        for (dupId in duplicateIds) {
            db.delete("merchant_listings", "id = ?", arrayOf(dupId))
        }
        if (listing.isSelectedForPurchase) {
            db.execSQL(
                "UPDATE merchant_listings SET is_selected = 0 WHERE saved_product_id = ? AND id != ?",
                arrayOf(listing.savedProductId, listing.id)
            )
        }

        val validPrice = listing.price?.takeIf {
            it > 0.0 &&
                listing.listingOrigin != "DIRECT_SEARCH_URL" &&
                listing.listingOrigin != "NO_RESULT"
        }
        val validOriginalPrice = listing.originalPrice?.takeIf {
            validPrice != null && it > validPrice
        }

        val cv = ContentValues().apply {
            put("id", listing.id)
            put("saved_product_id", listing.savedProductId)
            put("merchant", listing.merchant)
            put("title", listing.title)
            if (validPrice != null) {
                put("price", validPrice)
            } else {
                putNull("price")
            }
            if (validOriginalPrice != null) {
                put("original_price", validOriginalPrice)
            } else {
                putNull("original_price")
            }
            put("currency", listing.currency)
            put("product_url", listing.productUrl)
            put("image_url", listing.imageUrl)
            put("match_type", listing.matchType)
            put("confidence", listing.confidence)
            put("availability", listing.availability)
            put("source_type", listing.sourceType)
            put("listing_origin", listing.listingOrigin)
            put("provider_product_id", listing.providerProductId)
            put("offer_source", listing.offerSource)
            put("last_verified_at", listing.lastVerifiedAt)
            put("delivery_estimate", listing.deliveryEstimate)
            put("is_selected", if (listing.isSelectedForPurchase) 1 else 0)
        }
        val rowId = db.insertWithOnConflict("merchant_listings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        // Record price observation ONLY for priced listings (never unpriced search URLs)
        if (validPrice != null) {
            val obsCv = ContentValues().apply {
                put("id", java.util.UUID.randomUUID().toString())
                put("merchant_listing_id", listing.id)
                put("price", validPrice)
                put("currency", listing.currency)
                put("observed_at", listing.lastVerifiedAt)
                put("source_type", listing.sourceType)
            }
            db.insert("price_observations", null, obsCv)
        }
        return rowId
    }

    override suspend fun getListingsForProduct(productId: String): List<MerchantListingEntity> = withContext(Dispatchers.IO) {
        loadListingsForProductInternal(readableDatabase, productId)
    }

    private fun loadListingsForProductInternal(db: SQLiteDatabase, productId: String): List<MerchantListingEntity> {
        val result = mutableListOf<MerchantListingEntity>()
        val cursor = db.query(
            "merchant_listings",
            null,
            "saved_product_id = ?",
            arrayOf(productId),
            null,
            null,
            "is_selected DESC, CASE WHEN price IS NULL OR price <= 0 THEN 1 ELSE 0 END ASC, price ASC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(cursorToMerchantListing(c))
            }
        }
        return result
    }

    override suspend fun setSelectedListing(productId: String, listingId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val deselectCv = ContentValues().apply { put("is_selected", 0) }
            db.update("merchant_listings", deselectCv, "saved_product_id = ?", arrayOf(productId))

            val selectCv = ContentValues().apply { put("is_selected", 1) }
            db.update("merchant_listings", selectCv, "id = ?", arrayOf(listingId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshFlows()
    }

    override suspend fun deleteListing(id: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("merchant_listings", "id = ?", arrayOf(id))
        refreshFlows()
        Unit
    }

    override suspend fun recordPriceObservation(observation: PriceObservationEntity): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", observation.id)
            put("merchant_listing_id", observation.merchantListingId)
            put("price", observation.price)
            put("currency", observation.currency)
            put("observed_at", observation.observedAt)
            put("source_type", observation.sourceType)
        }
        val rowId = db.insert("price_observations", null, cv)
        refreshFlows()
        rowId
    }

    override suspend fun getPriceObservations(listingId: String): List<PriceObservationEntity> = withContext(Dispatchers.IO) {
        loadPriceObservationsInternal(readableDatabase, listOf(listingId))
    }

    private fun loadPriceObservationsInternal(db: SQLiteDatabase, listingIds: List<String>): List<PriceObservationEntity> {
        if (listingIds.isEmpty()) return emptyList()
        val result = mutableListOf<PriceObservationEntity>()
        val placeholders = listingIds.joinToString(",") { "?" }
        val cursor = db.query(
            "price_observations",
            null,
            "merchant_listing_id IN ($placeholders)",
            listingIds.toTypedArray(),
            null,
            null,
            "observed_at ASC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(
                    PriceObservationEntity(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        merchantListingId = c.getString(c.getColumnIndexOrThrow("merchant_listing_id")),
                        price = c.getDouble(c.getColumnIndexOrThrow("price")),
                        currency = c.getString(c.getColumnIndexOrThrow("currency")),
                        observedAt = c.getLong(c.getColumnIndexOrThrow("observed_at")),
                        sourceType = c.getString(c.getColumnIndexOrThrow("source_type"))
                    )
                )
            }
        }
        return result
    }

    override fun getAllCollectionsFlow(): Flow<List<CollectionEntity>> {
        val allCollections = loadAllCollectionsInternal()
        collectionsFlow.value = allCollections
        return collectionsFlow.asStateFlow()
    }

    override suspend fun getAllCollections(): List<CollectionEntity> = withContext(Dispatchers.IO) {
        loadAllCollectionsInternal()
    }

    private fun loadAllCollectionsInternal(): List<CollectionEntity> {
        val result = mutableListOf<CollectionEntity>()
        val cursor = readableDatabase.query(
            "collections",
            null,
            null,
            null,
            null,
            null,
            "name ASC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(
                    CollectionEntity(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return result
    }

    override suspend fun insertCollection(collection: CollectionEntity): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", collection.id)
            put("name", collection.name)
            put("created_at", collection.createdAt)
        }
        val rowId = db.insertWithOnConflict("collections", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        collectionsFlow.value = loadAllCollectionsInternal()
        rowId
    }

    override suspend fun deleteCollection(id: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete("collections", "id = ?", arrayOf(id))
        collectionsFlow.value = loadAllCollectionsInternal()
        Unit
    }

    override suspend fun assignProductToCollection(productId: String, collectionId: String?) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("collection_id", collectionId)
        }
        db.update("saved_products", cv, "id = ?", arrayOf(productId))
        refreshFlows()
        Unit
    }

    override suspend fun getPreference(key: String): String? = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.query(
            "shopping_preferences",
            arrayOf("pref_value"),
            "pref_key = ?",
            arrayOf(key),
            null,
            null,
            null
        )
        cursor.use { c ->
            if (c.moveToNext()) {
                c.getString(0)
            } else null
        }
    }

    override suspend fun setPreference(key: String, value: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("pref_key", key)
            put("pref_value", value)
        }
        db.insertWithOnConflict("shopping_preferences", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    private fun cursorToSavedProduct(c: Cursor): SavedProductEntity {
        return SavedProductEntity(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            canonicalProductId = c.getString(c.getColumnIndexOrThrow("canonical_product_id")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            brand = c.getString(c.getColumnIndexOrThrow("brand")),
            category = c.getString(c.getColumnIndexOrThrow("category")),
            imageUri = c.getString(c.getColumnIndexOrThrow("image_uri")),
            originalSource = c.getString(c.getColumnIndexOrThrow("original_source")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            collectionId = c.getString(c.getColumnIndexOrThrow("collection_id")),
            notes = c.getString(c.getColumnIndexOrThrow("notes"))
        )
    }

    private fun cursorToMerchantListing(c: Cursor): MerchantListingEntity {
        val priceIdx = c.getColumnIndexOrThrow("price")
        val rawPrice = if (c.isNull(priceIdx)) null else c.getDouble(priceIdx).takeIf { it > 0.0 }
        val originIdx = c.getColumnIndex("listing_origin")
        val origin = if (originIdx >= 0 && !c.isNull(originIdx)) {
            c.getString(originIdx)
        } else {
            if (rawPrice == null) "DIRECT_SEARCH_URL" else "VERIFIED_CATALOG_FIXTURE"
        }
        val sanitizedPrice = if (origin == "DIRECT_SEARCH_URL" || origin == "NO_RESULT") null else rawPrice

        val origIdx = c.getColumnIndex("original_price")
        val rawOrig = if (origIdx >= 0 && !c.isNull(origIdx)) c.getDouble(origIdx).takeIf { it > 0.0 } else null
        val provIdx = c.getColumnIndex("provider_product_id")
        val providerProdId = if (provIdx >= 0 && !c.isNull(provIdx)) c.getString(provIdx) else null
        val srcIdx = c.getColumnIndex("offer_source")
        val offerSource = if (srcIdx >= 0 && !c.isNull(srcIdx)) c.getString(srcIdx) else null

        return MerchantListingEntity(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            savedProductId = c.getString(c.getColumnIndexOrThrow("saved_product_id")),
            merchant = c.getString(c.getColumnIndexOrThrow("merchant")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            price = sanitizedPrice,
            originalPrice = rawOrig?.takeIf { sanitizedPrice != null && it > sanitizedPrice },
            currency = c.getString(c.getColumnIndexOrThrow("currency")),
            productUrl = c.getString(c.getColumnIndexOrThrow("product_url")),
            imageUrl = c.getString(c.getColumnIndexOrThrow("image_url")),
            matchType = c.getString(c.getColumnIndexOrThrow("match_type")),
            confidence = c.getFloat(c.getColumnIndexOrThrow("confidence")),
            availability = c.getString(c.getColumnIndexOrThrow("availability")),
            sourceType = c.getString(c.getColumnIndexOrThrow("source_type")),
            listingOrigin = origin,
            providerProductId = providerProdId,
            offerSource = offerSource,
            lastVerifiedAt = c.getLong(c.getColumnIndexOrThrow("last_verified_at")),
            deliveryEstimate = c.getString(c.getColumnIndexOrThrow("delivery_estimate")),
            isSelectedForPurchase = c.getInt(c.getColumnIndexOrThrow("is_selected")) == 1
        )
    }
}
