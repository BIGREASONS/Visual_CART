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
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.feature.ai.CloudProductUnderstandingProvider
import com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider
import com.paypal.visualcart.feature.ai.LocalProductParser
import com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine
import com.paypal.visualcart.feature.merchant.ProductUrlExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VisualCartShareActivity : ComponentActivity() {

    private val recognitionProvider = CloudProductUnderstandingProvider.forBackend()
    private val merchantEngine = MerchantDiscoveryEngine()
    private val urlExtractor = ProductUrlExtractor()
    private lateinit var cartRepository: CartRepositoryImpl

    private var lastCapturedImageUri: String? = null
    private var lastCapturedThumbnail: Bitmap? = null

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
                        val currentTitle = if (current is ShareCaptureUiState.Matched) current.product.title else ""
                        val thumb = (current as? ShareCaptureUiState.Matched)?.thumbnail ?: lastCapturedThumbnail
                        uiState = ShareCaptureUiState.NeedsQuery(
                            thumbnail = thumb,
                            message = "Enter or refine the product name to search live merchant stores.",
                            suggestedQuery = currentTitle,
                            imageUri = lastCapturedImageUri
                        )
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
                when {
                    type.startsWith("image/") -> {
                        val uri = extractImageUri(intent)
                        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                            ?: intent.clipData?.getItemAt(0)?.text?.toString()
                        if (uri != null) {
                            handleSharedImage(uri, extraText)
                        } else {
                            uiState = ShareCaptureUiState.Error("Unable to read image stream.")
                        }
                    }
                    type.startsWith("text/") || type.isEmpty() -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                            ?: intent.clipData?.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            handleSharedText(text)
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
                val firstUri = uris?.firstOrNull() ?: intent.clipData?.getItemAt(0)?.uri
                if (firstUri != null) {
                    handleSharedImage(firstUri, null)
                } else {
                    uiState = ShareCaptureUiState.Error("No image found in selection.")
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
        } ?: intent.clipData?.getItemAt(0)?.uri
    }

    private fun handleSharedImage(uri: Uri, contextText: String?) {
        val cleanHint = contextText?.takeIf { !DemoProductRecognitionProvider.isGenericCaptureText(it) }
        uiState = ShareCaptureUiState.Loading(
            statusMessage = "Analyzing visual features…",
            detectedHint = cleanHint?.take(60)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Safely copy to private app cache to guard against revoked URI permissions
            val privateFile = SafeImageDecoder.copyUriToPrivateCache(this@VisualCartShareActivity, uri)
            val thumbnail: Bitmap? = if (privateFile != null) {
                SafeImageDecoder.decodeThumbnail(privateFile, maxDimension = 720)
            } else null
            val resolvedUri = privateFile?.absolutePath ?: uri.toString()
            lastCapturedImageUri = resolvedUri
            lastCapturedThumbnail = thumbnail

            withContext(Dispatchers.Main) {
                uiState = ShareCaptureUiState.Loading(
                    thumbnail = thumbnail,
                    statusMessage = "Identifying product & stores…",
                    detectedHint = cleanHint
                )
            }

            // 2. Query product understanding provider without feeding raw filenames as product titles
            val recognized = recognitionProvider.recognizeFromImage(
                imageUri = resolvedUri,
                contextText = cleanHint
            )

            val isUnidentified = recognized.title.equals("Unidentified Product", ignoreCase = true) ||
                recognized.category.equals("Unidentified", ignoreCase = true) ||
                DemoProductRecognitionProvider.isGenericCaptureText(recognized.title)

            if (isUnidentified) {
                withContext(Dispatchers.Main) {
                    uiState = ShareCaptureUiState.NeedsQuery(
                        thumbnail = thumbnail,
                        message = recognized.matchReason.ifBlank {
                            "Could not auto-identify this image. Enter the product name below (or configure GEMINI_API_KEY in backend .env)."
                        },
                        suggestedQuery = cleanHint.orEmpty(),
                        imageUri = resolvedUri
                    )
                }
                return@launch
            }

            // 3. Search multi-merchant discovery engine
            val listings = merchantEngine.searchAll(
                query = recognized.title,
                targetSpecs = recognized.specifications
            )

            val (bestListing, exactListings, similarListings) = partitionListings(listings)

            withContext(Dispatchers.Main) {
                if (bestListing != null) {
                    uiState = ShareCaptureUiState.Matched(
                        product = recognized,
                        thumbnail = thumbnail,
                        bestListing = bestListing,
                        exactListings = exactListings,
                        similarListings = similarListings,
                        selectedListing = bestListing
                    )
                } else {
                    uiState = ShareCaptureUiState.Error("No matching store listings found for ${recognized.title}")
                }
            }
        }
    }

    private fun handleManualProductQuery(rawQuery: String) {
        val cleanQuery = SecurityUtils.sanitizeSearchQuery(rawQuery).trim()
        if (cleanQuery.isBlank() || DemoProductRecognitionProvider.isGenericCaptureText(cleanQuery)) {
            uiState = ShareCaptureUiState.NeedsQuery(
                thumbnail = lastCapturedThumbnail,
                message = "Please enter a specific product name (for example: PVC Plastic Pipes, Sony headphones, Nike shoes).",
                suggestedQuery = "",
                imageUri = lastCapturedImageUri
            )
            return
        }

        uiState = ShareCaptureUiState.Loading(
            thumbnail = lastCapturedThumbnail,
            statusMessage = "Searching stores for \"$cleanQuery\"…",
            detectedHint = cleanQuery.take(60)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val baseRecognized = recognitionProvider.recognizeFromText(cleanQuery)
            val recognized = baseRecognized.copy(
                title = baseRecognized.title.ifBlank { cleanQuery },
                imageUrl = lastCapturedImageUri ?: baseRecognized.imageUrl
            )
            val listings = merchantEngine.searchAll(
                query = recognized.title,
                targetSpecs = recognized.specifications
            )
            val (bestListing, exactListings, similarListings) = partitionListings(listings)

            withContext(Dispatchers.Main) {
                if (bestListing != null) {
                    uiState = ShareCaptureUiState.Matched(
                        product = recognized,
                        thumbnail = lastCapturedThumbnail,
                        bestListing = bestListing,
                        exactListings = exactListings,
                        similarListings = similarListings,
                        selectedListing = bestListing
                    )
                } else {
                    uiState = ShareCaptureUiState.Error("No matching store listings found for $cleanQuery")
                }
            }
        }
    }

    private fun handleSharedText(rawText: String) {
        val sanitized = SecurityUtils.sanitizeSearchQuery(rawText)
        val extractedUrl = SecurityUtils.extractWebUrl(rawText)

        lastCapturedImageUri = null
        lastCapturedThumbnail = null

        uiState = ShareCaptureUiState.Loading(
            statusMessage = "Analyzing product link…",
            detectedHint = extractedUrl ?: sanitized.take(50)
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val queryTitle = if (extractedUrl != null) {
                val parsed = urlExtractor.extract(extractedUrl)
                parsed.title ?: "Shared Product"
            } else {
                sanitized
            }

            if (DemoProductRecognitionProvider.isGenericCaptureText(queryTitle)) {
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

            val recognized = recognitionProvider.recognizeFromText(queryTitle)

            val listings = merchantEngine.searchAll(
                query = recognized.title,
                targetSpecs = recognized.specifications
            )

            val (bestListing, exactListings, similarListings) = partitionListings(listings)

            withContext(Dispatchers.Main) {
                if (bestListing != null) {
                    uiState = ShareCaptureUiState.Matched(
                        product = recognized,
                        thumbnail = null,
                        bestListing = bestListing,
                        exactListings = exactListings,
                        similarListings = similarListings,
                        selectedListing = bestListing
                    )
                } else {
                    uiState = ShareCaptureUiState.Error("No merchant listings found for $queryTitle")
                }
            }
        }
    }

    private fun partitionListings(
        listings: List<MerchantListing>
    ): Triple<MerchantListing?, List<MerchantListing>, List<MerchantListing>> {
        val unique = listings.distinctBy { it.id }
        val exact = unique.filter { it.matchType == MatchType.EXACT }
            .ifEmpty { unique.take(3) }
        val exactIds = exact.mapTo(mutableSetOf()) { it.id }
        val similar = unique.filterNot { it.id in exactIds }
        val best = unique.minByOrNull { it.price } ?: unique.firstOrNull()
        return Triple(best, exact, similar)
    }

    private fun saveListingToCart(selectedListing: MerchantListing) {
        val current = uiState as? ShareCaptureUiState.Matched ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val allListings = (current.exactListings + current.similarListings).distinctBy { it.id }
            val listingEntities = allListings.map { listing ->
                MerchantListingEntity(
                    savedProductId = "",
                    merchant = listing.merchantName,
                    title = current.product.title,
                    price = listing.price,
                    currency = "INR",
                    productUrl = listing.productUrl,
                    imageUrl = listing.imageUrl,
                    matchType = listing.matchType.name,
                    confidence = listing.confidence,
                    availability = listing.availability.name,
                    sourceType = listing.sourceType.name,
                    lastVerifiedAt = System.currentTimeMillis(),
                    deliveryEstimate = listing.deliveryEstimate,
                    isSelectedForPurchase = listing.id == selectedListing.id
                )
            }

            cartRepository.saveProductWithListings(
                title = current.product.title,
                brand = current.product.brand ?: "Product",
                category = current.product.category,
                imageUri = selectedListing.imageUrl.ifBlank { current.product.imageUrl },
                originalSource = selectedListing.merchantName,
                canonicalProductId = current.product.title.lowercase().replace("\\s+".toRegex(), "-"),
                listings = listingEntities
            )
            val allProducts = cartRepository.getCartProducts()

            withContext(Dispatchers.Main) {
                uiState = ShareCaptureUiState.Saved(
                    productTitle = current.product.title,
                    merchantName = selectedListing.merchantName,
                    price = selectedListing.price,
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
