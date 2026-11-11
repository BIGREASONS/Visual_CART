package com.paypal.visualcart.ui.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.paypal.visualcart.MainActivity
import com.paypal.visualcart.core.designsystem.VisualCartTheme
import com.paypal.visualcart.core.util.SafeImageDecoder
import com.paypal.visualcart.core.util.SecurityUtils
import com.paypal.visualcart.core.util.SharingShortcutsManager
import com.paypal.visualcart.data.local.VisualCartDatabase
import com.paypal.visualcart.data.local.entity.MerchantListingEntity
import com.paypal.visualcart.data.local.entity.SavedProductEntity
import com.paypal.visualcart.data.repository.CartRepositoryImpl
import android.util.Log
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantComparisonSummary
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.feature.ai.GeminiFreeVisionProvider
import com.paypal.visualcart.feature.ai.LocalProductParser
import com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine
import com.paypal.visualcart.feature.merchant.ProductUrlExtractor
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VisualCartShareActivity : ComponentActivity() {

    private val recognitionProvider by lazy { GeminiFreeVisionProvider.forBackend(this) }
    private val liveMerchantEngine by lazy {
        MerchantDiscoveryEngine(MerchantDiscoveryEngine.liveProviders(this))
    }
    private val demoMerchantEngine = MerchantDiscoveryEngine(
        MerchantDiscoveryEngine.defaultProviders().onEach { it.isLiveMode = false }
    )
    private val urlExtractor = ProductUrlExtractor()
    private lateinit var cartRepository: CartRepositoryImpl

    private var lastCapturedImageUri: String? = null
    private var lastCapturedThumbnail: Bitmap? = null
    private var lastRawShareUri: Uri? = null
    private var lastShareContextText: String? = null

    private var uiState by mutableStateOf<ShareCaptureUiState>(
        ShareCaptureUiState.Loading(statusMessage = "Receiving shared product…")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cartRepository = CartRepositoryImpl(VisualCartDatabase.getInstance(this))
        SharingShortcutsManager.publishShareShortcut(this)

        setContent {
            VisualCartTheme {
                ShareCaptureDialog(
                    uiState = uiState,
                    onDismiss = { finish() },
                    onSelectListing = { selected ->
                        val current = uiState
                        if (current is ShareCaptureUiState.Matched) {
                            uiState = current.copy(selectedListing = selected)
                        }
                    },
                    onAddToCart = { listing ->
                        saveListingToCart(listing)
                    },
                    onViewInStore = { url ->
                        openUrlInBrowser(url)
                    },
                    onSubmitManualQuery = { query ->
                        handleManualProductQuery(query)
                    },
                    onEditQuery = {
                        val current = uiState
                        val currentTitle = if (current is ShareCaptureUiState.Matched) {
                            current.product.searchQuery.ifBlank { current.product.title }
                        } else ""
                        val thumb = (current as? ShareCaptureUiState.Matched)?.thumbnail ?: lastCapturedThumbnail
                        uiState = ShareCaptureUiState.NeedsQuery(
                            thumbnail = thumb,
                            message = "Enter or refine the product name to search live merchant stores.",
                            suggestedQuery = currentTitle,
                            imageUri = lastCapturedImageUri,
                            candidates = (current as? ShareCaptureUiState.Matched)?.product?.candidates ?: emptyList(),
                            canRetry = lastRawShareUri != null,
                            detectedCategory = (current as? ShareCaptureUiState.Matched)?.product?.category
                        )
                    },
                    onRetryRecognition = {
                        val retryUri = lastRawShareUri
                        if (retryUri != null) {
                            handleSharedImage(retryUri, lastShareContextText)
                        }
                    },
                    onOpenSettings = {
                        val intent = Intent(this@VisualCartShareActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("open_settings", true)
                        }
                        startActivity(intent)
                        finish()
                    },
                    onOpenFullCart = {
                        val intent = Intent(this@VisualCartShareActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("open_cart", true)
                        }
                        startActivity(intent)
                        finish()
                    }
                )
            }
        }

        processIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingIntent(intent)
    }

    private fun processIncomingIntent(intent: Intent?) {
        if (intent == null) {
            uiState = ShareCaptureUiState.Error("No content received.")
            return
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type ?: ""
                val streamUri = extractImageUri(intent)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                when {
                    type.startsWith("image/") || ((type == "*/*" || type.isBlank()) && streamUri != null) -> {
                        if (streamUri != null && SecurityUtils.isValidContentUri(streamUri.toString())) {
                            handleSharedImage(streamUri, sharedText)
                        } else {
                            Log.w("VisualCartCapture", "Rejected invalid or missing shared image stream uri")
                            uiState = ShareCaptureUiState.Error("Unable to read image stream.")
                        }
                    }
                    type.startsWith("text/") || type.isEmpty() || type == "*/*" -> {
                        if (!sharedText.isNullOrBlank()) {
                            handleSharedText(sharedText)
                        } else {
                            uiState = ShareCaptureUiState.Error("No text or link shared.")
                        }
                    }
                    else -> {
                        uiState = ShareCaptureUiState.Error("Unsupported content type: $type")
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                val firstUri = uris?.firstOrNull()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (firstUri != null && SecurityUtils.isValidContentUri(firstUri.toString())) {
                    handleSharedImage(firstUri, null)
                } else {
                    uiState = ShareCaptureUiState.Error("No valid image found in selection.")
                }
            }
            else -> {
                uiState = ShareCaptureUiState.Error("Unsupported action: ${intent.action}")
            }
        }
    }

    private fun extractImageUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun handleSharedImage(uri: Uri, contextText: String?) {
        val cleanHint = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }
        lastRawShareUri = uri
        lastShareContextText = cleanHint

        uiState = ShareCaptureUiState.Loading(
            statusMessage = "Reading shared image bytes…",
            detectedHint = cleanHint?.take(60)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Copy original bytes to app-private cache and verify MIME/dimensions before inference
            val captureResult = SafeImageDecoder.copyUriToPrivateCacheDetailed(this@VisualCartShareActivity, uri)
            if (captureResult == null || !captureResult.file.exists() || captureResult.byteSize <= 0L) {
                Log.w("VisualCartCapture", "Failed to decode shared image bytes from scheme=${uri.scheme}")
                withContext(Dispatchers.Main) {
                    lastCapturedImageUri = null
                    lastCapturedThumbnail = null
                    uiState = ShareCaptureUiState.NeedsQuery(
                        thumbnail = null,
                        message = "Could not read the shared image bytes safely. Enter the product name manually below.",
                        suggestedQuery = cleanHint.orEmpty(),
                        imageUri = null,
                        canRetry = true
                    )
                }
                return@launch
            }

            Log.i(
                "VisualCartCapture",
                "Copied capture scheme=${uri.scheme} mime=${captureResult.mimeType} bytes=${captureResult.byteSize} dim=${captureResult.width}x${captureResult.height}"
            )

            val thumbnail: Bitmap? = SafeImageDecoder.decodeThumbnail(captureResult.file, maxDimension = 720)
            val resolvedPath = captureResult.file.absolutePath
            lastCapturedImageUri = resolvedPath
            lastCapturedThumbnail = thumbnail

            withContext(Dispatchers.Main) {
                uiState = ShareCaptureUiState.Loading(
                    thumbnail = thumbnail,
                    statusMessage = "Identifying product visually…",
                    detectedHint = cleanHint
                )
            }

            // 2. Query product understanding provider with real file path (never raw filename hints)
            val recognized = recognitionProvider.recognizeFromImage(
                imageUri = resolvedPath,
                contextText = cleanHint
            )
            Log.i(
                "VisualCartAI",
                "Recognized status=${recognized.status} source=${recognized.recognitionSource} model=${recognized.aiModelName} category=${recognized.category} query=${recognized.searchQuery.ifBlank { recognized.title }}"
            )

            val queryBuildResult = SearchQueryBuilder.build(
                SearchQueryBuilder.Input.fromRecognizedProduct(recognized)
            )
            val isUnidentified = recognized.status == RecognitionStatus.UNIDENTIFIED ||
                recognized.category.equals("Unidentified", ignoreCase = true) ||
                queryBuildResult is SearchQueryBuilder.Result.Rejected

            if (isUnidentified) {
                val suggested = recognized.candidates.firstOrNull()?.searchQuery
                    ?: cleanHint.orEmpty()
                withContext(Dispatchers.Main) {
                    uiState = ShareCaptureUiState.NeedsQuery(
                        thumbnail = thumbnail,
                        message = recognized.diagnosticNote ?: recognized.matchReason.ifBlank {
                            "Could not reliably identify this product from the image. Confirm a candidate or enter the product name."
                        },
                        suggestedQuery = suggested,
                        imageUri = resolvedPath,
                        candidates = recognized.candidates,
                        canRetry = true,
                        detectedCategory = recognized.category,
                        rateLimited = recognized.rateLimited,
                        showSettingsAction = !recognized.rateLimited
                    )
                }
                return@launch
            }

            // 3. Search multi-merchant discovery engine (live captures never mix DemoMerchantFixtures)
            val engine = if (recognized.recognitionSource == RecognitionSource.DEMO && recognized.isDemoFixture) {
                demoMerchantEngine
            } else {
                liveMerchantEngine
            }
            val discovery = engine.discoverStructured(recognized)
            val combined = engine.deduplicateListings(
                discovery.exactMatches + discovery.similarAlternatives + discovery.searchLinks
            )
            val (bestListing, exactListings, similarListings, summary) = partitionListings(
                recognized.searchQuery.ifBlank { recognized.title },
                combined,
                engine
            )
            val isUncertain = recognized.status == RecognitionStatus.PARTIAL ||
                recognized.candidates.size > 1 ||
                recognized.confidence < 0.65f

            withContext(Dispatchers.Main) {
                if (bestListing != null) {
                    uiState = ShareCaptureUiState.Matched(
                        product = recognized.copy(imageUrl = resolvedPath),
                        thumbnail = thumbnail,
                        bestListing = bestListing,
                        exactListings = exactListings,
                        similarListings = similarListings,
                        selectedListing = bestListing,
                        comparisonSummary = summary,
                        isUncertain = isUncertain
                    )
                } else {
                    val reasonMessage = when (discovery.rejectedReason) {
                        SearchQueryBuilder.RejectionReason.FILENAME_OR_EXTENSION,
                        SearchQueryBuilder.RejectionReason.CAPTURE_TIMESTAMP,
                        SearchQueryBuilder.RejectionReason.INTERNAL_OR_NUMERIC_ID,
                        SearchQueryBuilder.RejectionReason.URI_OR_PATH ->
                            "Only a generic capture filename or internal ID was found. Enter the product name to search real stores."
                        SearchQueryBuilder.RejectionReason.EMPTY_OR_BLANK,
                        SearchQueryBuilder.RejectionReason.FALLBACK_NOISE_ONLY,
                        SearchQueryBuilder.RejectionReason.NO_PRODUCT_IDENTITY ->
                            "Could not determine a specific product name from this image. Enter the product name manually."
                        null ->
                            "No clean store search query could be formed for this item. Enter the product name manually."
                    }
                    uiState = ShareCaptureUiState.NeedsQuery(
                        thumbnail = thumbnail,
                        message = recognized.diagnosticNote?.takeIf { recognized.rateLimited } ?: reasonMessage,
                        suggestedQuery = cleanHint.orEmpty(),
                        imageUri = resolvedPath,
                        candidates = recognized.candidates,
                        canRetry = true,
                        detectedCategory = recognized.category,
                        rateLimited = recognized.rateLimited,
                        showSettingsAction = !recognized.rateLimited
                    )
                }
            }
        }
    }

    private fun handleManualProductQuery(rawQuery: String) {
        val cleanQuery = SecurityUtils.sanitizeSearchQuery(rawQuery).trim()
        val canonicalInput = SearchQueryBuilder.buildFromRawQuery(cleanQuery)
        if (canonicalInput is SearchQueryBuilder.Result.Rejected) {
            uiState = ShareCaptureUiState.NeedsQuery(
                thumbnail = lastCapturedThumbnail,
                message = "Please enter a specific product name (for example: PVC Plastic Pipes, Sony headphones, Nike shoes).",
                suggestedQuery = "",
                imageUri = lastCapturedImageUri,
                canRetry = lastRawShareUri != null
            )
            return
        }
        val verifiedQuery = (canonicalInput as SearchQueryBuilder.Result.Valid).query

        uiState = ShareCaptureUiState.Loading(
            thumbnail = lastCapturedThumbnail,
            statusMessage = "Searching stores for \"$verifiedQuery\"…",
            detectedHint = verifiedQuery.take(60)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val baseRecognized = LocalProductParser.parse(verifiedQuery, imageUrl = lastCapturedImageUri)
            val recognized = baseRecognized.copy(
                title = baseRecognized.title.ifBlank { verifiedQuery },
                searchQuery = verifiedQuery,
                imageUrl = lastCapturedImageUri ?: baseRecognized.imageUrl,
                isDemoFixture = false,
                isLiveInference = false,
                recognitionSource = RecognitionSource.MANUAL_SEARCH
            )
            val engine = if (recognized.recognitionSource == RecognitionSource.DEMO && recognized.isDemoFixture) {
                demoMerchantEngine
            } else {
                liveMerchantEngine
            }
            val discovery = engine.discoverStructured(recognized)
            val combined = engine.deduplicateListings(
                discovery.exactMatches + discovery.similarAlternatives + discovery.searchLinks
            )
            val (bestListing, exactListings, similarListings, summary) = partitionListings(
                verifiedQuery,
                combined,
                engine
            )

            withContext(Dispatchers.Main) {
                if (bestListing != null) {
                    uiState = ShareCaptureUiState.Matched(
                        product = recognized,
                        thumbnail = lastCapturedThumbnail,
                        bestListing = bestListing,
                        exactListings = exactListings,
                        similarListings = similarListings,
                        selectedListing = bestListing,
                        comparisonSummary = summary,
                        isUncertain = false
                    )
                } else {
                    uiState = ShareCaptureUiState.Error("No matching store listings found for $verifiedQuery")
                }
            }
        }
    }

    private fun handleSharedText(rawText: String) {
        val sanitized = SecurityUtils.sanitizeSearchQuery(rawText)
        val extractedUrl = SecurityUtils.extractWebUrl(rawText)

        lastCapturedImageUri = null
        lastCapturedThumbnail = null
        lastRawShareUri = null

        uiState = ShareCaptureUiState.Loading(
            statusMessage = "Analyzing product link…",
            detectedHint = extractedUrl ?: sanitized.take(50)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val lookupResp = if (extractedUrl != null) {
                liveMerchantEngine.lookupSharedUrl(extractedUrl, this@VisualCartShareActivity)
            } else null

            val queryTitle = when {
                !lookupResp?.title.isNullOrBlank() -> lookupResp!!.title!!
                extractedUrl != null -> {
                    val parsed = urlExtractor.extract(extractedUrl)
                    parsed.title ?: "Shared Product"
                }
                else -> sanitized
            }

            if (SearchQueryBuilder.isNonProductNoise(queryTitle) && lookupResp?.listings.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    uiState = ShareCaptureUiState.NeedsQuery(
                        thumbnail = null,
                        message = "Could not extract a product name from the shared link. Enter the product name below.",
                        suggestedQuery = "",
                        imageUri = null
                    )
                }
                return@launch
            }

            val recognized = recognitionProvider.recognizeFromText(queryTitle).let { rec ->
                val lookupImg = lookupResp?.listings?.firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl
                if (lookupImg != null && rec.imageUrl.isNullOrBlank()) rec.copy(imageUrl = lookupImg) else rec
            }
            val engine = if (recognized.recognitionSource == RecognitionSource.DEMO && recognized.isDemoFixture) {
                demoMerchantEngine
            } else {
                liveMerchantEngine
            }
            val discovery = engine.discoverStructured(recognized)
            val combined = engine.deduplicateListings(
                (lookupResp?.listings ?: emptyList()) +
                    discovery.exactMatches +
                    discovery.similarAlternatives +
                    discovery.searchLinks
            )
            val (bestListing, exactListings, similarListings, summary) = partitionListings(
                recognized.searchQuery.ifBlank { recognized.title },
                combined,
                engine
            )

            withContext(Dispatchers.Main) {
                if (bestListing != null) {
                    uiState = ShareCaptureUiState.Matched(
                        product = recognized,
                        thumbnail = null,
                        bestListing = bestListing,
                        exactListings = exactListings,
                        similarListings = similarListings,
                        selectedListing = bestListing,
                        comparisonSummary = summary,
                        isUncertain = recognized.status != RecognitionStatus.IDENTIFIED
                    )
                } else {
                    uiState = ShareCaptureUiState.NeedsQuery(
                        thumbnail = null,
                        message = "Could not build a store search query from the shared link. Enter the product name below.",
                        suggestedQuery = "",
                        imageUri = null
                    )
                }
            }
        }
    }

    private data class PartitionedShareListings(
        val bestListing: MerchantListing?,
        val exactListings: List<MerchantListing>,
        val similarListings: List<MerchantListing>,
        val summary: MerchantComparisonSummary?
    )

    private fun partitionListings(
        canonicalTitle: String,
        listings: List<MerchantListing>,
        engine: MerchantDiscoveryEngine
    ): PartitionedShareListings {
        val unique = engine.deduplicateListings(listings)
        val exact = unique.filter { it.matchType == MatchType.EXACT || it.matchType == MatchType.LIKELY }
            .ifEmpty { unique.take(3) }
        val exactIds = exact.mapTo(mutableSetOf()) { it.normalizedMerchantId }
        val similar = unique.filterNot { it.normalizedMerchantId in exactIds }
        val summary = MerchantComparisonSummary.fromListings(canonicalTitle, unique)
        val best = summary?.lowestPriceListing ?: unique.firstOrNull()
        return PartitionedShareListings(best, exact, similar, summary)
    }

    private fun saveListingToCart(selectedListing: MerchantListing) {
        val current = uiState as? ShareCaptureUiState.Matched ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val allListings = liveMerchantEngine.deduplicateListings(current.exactListings + current.similarListings)
            val listingEntities = allListings.map { listing ->
                MerchantListingEntity(
                    savedProductId = "",
                    merchant = listing.merchantName,
                    title = current.product.title,
                    price = listing.price,
                    originalPrice = listing.originalPrice,
                    currency = listing.currency,
                    productUrl = listing.productUrl,
                    imageUrl = listing.imageUrl,
                    matchType = listing.matchType.name,
                    confidence = listing.confidence,
                    availability = listing.availability.name,
                    sourceType = listing.sourceType.name,
                    listingOrigin = listing.listingOrigin.name,
                    providerProductId = listing.providerProductId,
                    offerSource = listing.offerSource,
                    lastVerifiedAt = System.currentTimeMillis(),
                    deliveryEstimate = listing.deliveryEstimate,
                    isSelectedForPurchase = listing.normalizedMerchantId == selectedListing.normalizedMerchantId
                )
            }

            val preservedImageUri = lastCapturedImageUri?.takeIf { it.isNotBlank() }
                ?: selectedListing.imageUrl.ifBlank { current.product.imageUrl }
            val canonicalId = SearchQueryBuilder.normalizeProductIdentityKey(current.product.title)

            cartRepository.saveProductWithListings(
                title = current.product.title,
                brand = current.product.brand ?: "Product",
                category = current.product.category,
                imageUri = preservedImageUri,
                originalSource = selectedListing.merchantName,
                canonicalProductId = canonicalId,
                listings = listingEntities
            )
            val allProducts = cartRepository.getCartProducts()

            withContext(Dispatchers.Main) {
                uiState = ShareCaptureUiState.Saved(
                    productTitle = current.product.title,
                    merchantName = selectedListing.merchantName,
                    price = if (selectedListing.hasVerifiedPrice) selectedListing.price else null,
                    currency = selectedListing.currency,
                    totalCartCount = allProducts.size
                )
            }
        }
    }

    private fun openUrlInBrowser(url: String) {
        val safeUrl = SecurityUtils.sanitizeWebUrl(url) ?: return
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(browserIntent)
        } catch (_: Exception) {
            // Browser unavailable
        }
    }
}
