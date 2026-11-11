package com.paypal.visualcart

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.paypal.visualcart.core.capture.CaptureEventBus
import com.paypal.visualcart.core.capture.CaptureIntentEvent
import com.paypal.visualcart.core.designsystem.VisualCartTheme
import com.paypal.visualcart.core.util.SafeImageDecoder
import com.paypal.visualcart.core.util.SecurityUtils
import com.paypal.visualcart.core.util.SharingShortcutsManager
import com.paypal.visualcart.domain.model.MerchantComparisonSummary
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.feature.ai.GeminiFreeVisionProvider
import com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine
import com.paypal.visualcart.feature.merchant.ProductUrlExtractor
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import com.paypal.visualcart.feature.payment.PayPalCheckoutCoordinator
import com.paypal.visualcart.feature.payment.PayPalDeepLinkHandler
import com.paypal.visualcart.feature.circle.OverlayCapturePreferences
import com.paypal.visualcart.service.capture.CircleToShopConsentActivity
import com.paypal.visualcart.service.overlay.VisualCartOverlayService
import com.paypal.visualcart.service.tile.VisualCartTileService
import com.paypal.visualcart.ui.matching.DetectedProductMatchResult
import com.paypal.visualcart.ui.matching.MerchantListingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val aiProvider by lazy { GeminiFreeVisionProvider.forBackend(this) }
    private val liveMerchantDiscoveryEngine by lazy {
        MerchantDiscoveryEngine(MerchantDiscoveryEngine.liveProviders(this))
    }
    private val demoMerchantDiscoveryEngine = MerchantDiscoveryEngine(
        MerchantDiscoveryEngine.defaultProviders().onEach { it.isLiveMode = false }
    )
    private val urlExtractor = ProductUrlExtractor()
    private var pendingCircleToShopAfterOverlay = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val launchCircle = pendingCircleToShopAfterOverlay
        pendingCircleToShopAfterOverlay = false
        if (Settings.canDrawOverlays(this)) {
            VisualCartOverlayService.start(this)
            VisualCartTileService.requestTileUpdate(this)
            if (launchCircle) {
                CaptureEventBus.emit(CaptureIntentEvent.StartCircleToShop)
                startActivity(CircleToShopConsentActivity.createLaunchIntent(this))
            }
        }
    }

    // Photo picker launcher for importing screenshots directly
    val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && SecurityUtils.isValidContentUri(uri.toString())) {
            processSharedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharingShortcutsManager.publishShareShortcut(this)
        handleIntent(intent)

        enableEdgeToEdge()
        setContent {
            VisualCartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(
                        onLaunchPhotoPicker = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val isDirectCircleLaunch =
            intent?.action == ACTION_CAPTURE &&
                intent?.getStringExtra(EXTRA_CAPTURE_TYPE) == CAPTURE_TYPE_CIRCLE_TO_SHOP
        val shouldAutoStart = OverlayCapturePreferences.shouldAutoStartFloatingBubble(
            hasOverlayPermission = Settings.canDrawOverlays(this),
            userAutoStartEnabled = OverlayCapturePreferences.isBubbleAutoStartEnabled(this),
            isAlreadyRunning = VisualCartOverlayService.isRunning,
            isDirectCircleLaunch = isDirectCircleLaunch
        )
        if (shouldAutoStart) {
            VisualCartOverlayService.start(this)
            VisualCartTileService.requestTileUpdate(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra("open_cart", false)) {
            CaptureEventBus.emit(CaptureIntentEvent.OpenCart)
        }
        if (intent.getBooleanExtra("open_settings", false)) {
            CaptureEventBus.emit(CaptureIntentEvent.OpenSettings)
        }

        val shouldRequestOverlay = intent.getBooleanExtra(EXTRA_REQUEST_OVERLAY_PERMISSION, false) ||
                intent.action == ACTION_REQUEST_OVERLAY_PERMISSION

        if (shouldRequestOverlay && !Settings.canDrawOverlays(this)) {
            promptOverlayPermission()
            return
        }

        // 1. Handle PayPal Sandbox custom deep links (Chrome Custom Tabs return/cancel)
        if (PayPalDeepLinkHandler.isPayPalDeepLink(intent)) {
            val deepLinkResult = PayPalDeepLinkHandler.parse(intent)
            if (deepLinkResult != null) {
                PayPalCheckoutCoordinator.instance.handleDeepLink(deepLinkResult)
            }
            when (deepLinkResult) {
                is PayPalDeepLinkHandler.Result.Approved -> {
                    Log.i(TAG, "PayPal sandbox checkout approved: orderId=${deepLinkResult.orderId}, payerId=${deepLinkResult.payerId}")
                    Toast.makeText(
                        this,
                        "PayPal Payment Approved (Order: ${deepLinkResult.orderId})",
                        Toast.LENGTH_LONG
                    ).show()
                    CaptureEventBus.emit(
                        CaptureIntentEvent.PayPalCheckoutApproved(
                            orderId = deepLinkResult.orderId,
                            payerId = deepLinkResult.payerId
                        )
                    )
                }
                is PayPalDeepLinkHandler.Result.Cancelled -> {
                    Log.i(TAG, "PayPal checkout cancelled by buyer: orderId=${deepLinkResult.orderId}")
                    Toast.makeText(this, "PayPal Checkout Cancelled", Toast.LENGTH_SHORT).show()
                    CaptureEventBus.emit(
                        CaptureIntentEvent.PayPalCheckoutCancelled(
                            orderId = deepLinkResult.orderId
                        )
                    )
                }
                is PayPalDeepLinkHandler.Result.Invalid -> {
                    Log.w(TAG, "Invalid PayPal deep link received: ${deepLinkResult.reason}")
                }
                null -> Unit
            }
            // Clear intent data to prevent re-triggering upon configuration change/lifecycle restoration
            intent.data = null
            return
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val viewUri = intent.data
                if (viewUri != null && SecurityUtils.isValidWebUrl(viewUri.toString())) {
                    processSharedText(viewUri.toString())
                }
            }
            Intent.ACTION_SEND -> {
                val type = intent.type ?: ""
                val extraStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

                when {
                    type.startsWith("image/") || ((type == "*/*" || type.isBlank()) && extraStream != null) -> {
                        if (extraStream != null && SecurityUtils.isValidContentUri(extraStream.toString())) {
                            processSharedImage(extraStream)
                        } else {
                            Log.w("VisualCartCapture", "MainActivity ignored invalid image stream uri")
                        }
                    }
                    type.startsWith("text/") || type.isEmpty() || type == "*/*" -> {
                        if (!sharedText.isNullOrBlank()) {
                            processSharedText(sharedText)
                        }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val imageUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                val firstUri = imageUris?.firstOrNull()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (firstUri != null && SecurityUtils.isValidContentUri(firstUri.toString())) {
                    processSharedImage(firstUri)
                }
            }
            ACTION_CAPTURE -> {
                val captureType = intent.getStringExtra(EXTRA_CAPTURE_TYPE) ?: CAPTURE_TYPE_SCREENSHOT
                CaptureEventBus.emit(CaptureIntentEvent.TriggerCapture(captureType))
                when (captureType) {
                    CAPTURE_TYPE_SCREENSHOT -> {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    CAPTURE_TYPE_CIRCLE_TO_SHOP -> {
                        launchCircleToShopFlow()
                    }
                }
            }
        }
    }

    private fun processSharedImage(imageUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val captureResult = SafeImageDecoder.copyUriToPrivateCacheDetailed(this@MainActivity, imageUri)
            if (captureResult == null || !captureResult.file.exists() || captureResult.byteSize <= 0L) {
                Log.w("VisualCartCapture", "MainActivity failed to read shared image scheme=${imageUri.scheme}")
                val failedResult = DetectedProductMatchResult(
                    detectedTitle = "Unidentified Image (Unreadable File)",
                    brand = "Needs Query",
                    category = "Unidentified",
                    specs = emptyList(),
                    matchReason = "Could not read original image bytes cleanly. Pick a local photo or share again.",
                    listings = emptyList(),
                    isDemoFixture = false,
                    isLiveInference = false,
                    isUncertain = true,
                    recognitionStatus = RecognitionStatus.UNIDENTIFIED
                )
                withContext(Dispatchers.Main) {
                    CaptureEventBus.emit(CaptureIntentEvent.ShowProductMatch(failedResult))
                }
                return@launch
            }

            Log.i(
                "VisualCartCapture",
                "MainActivity copied scheme=${imageUri.scheme} mime=${captureResult.mimeType} bytes=${captureResult.byteSize} dim=${captureResult.width}x${captureResult.height}"
            )

            val resolvedPath = captureResult.file.absolutePath
            val recognized = aiProvider.recognizeFromImage(resolvedPath)
            val engine = if (recognized.recognitionSource == RecognitionSource.DEMO && recognized.isDemoFixture) {
                demoMerchantDiscoveryEngine
            } else {
                liveMerchantDiscoveryEngine
            }

            val queryResult = SearchQueryBuilder.build(SearchQueryBuilder.Input.fromRecognizedProduct(recognized))
            val isUnidentified = recognized.status == RecognitionStatus.UNIDENTIFIED ||
                recognized.category.equals("Unidentified", ignoreCase = true) ||
                queryResult is SearchQueryBuilder.Result.Rejected

            val discovery = if (isUnidentified) null else engine.discoverStructured(recognized)
            val listings = if (discovery != null) {
                engine.deduplicateListings(
                    discovery.exactMatches + discovery.similarAlternatives + discovery.searchLinks
                )
            } else {
                emptyList()
            }
            val canonicalQuery = (queryResult as? SearchQueryBuilder.Result.Valid)?.query
                ?: recognized.searchQuery.ifBlank { recognized.title }
            val summary = MerchantComparisonSummary.fromListings(canonicalQuery, listings)

            val matchResult = DetectedProductMatchResult(
                detectedTitle = if (isUnidentified) "Unidentified Image (Enter Search Manually)" else recognized.title,
                brand = recognized.brand ?: if (isUnidentified) "Needs Query" else "Detected Product",
                category = recognized.category,
                specs = recognized.specifications.entries.map { "${it.key}: ${it.value}" },
                matchReason = recognized.diagnosticNote ?: recognized.matchReason,
                listings = listings.map { listing: MerchantListing ->
                    MerchantListingItem(
                        merchantName = listing.merchantName,
                        price = if (listing.hasVerifiedPrice) listing.price else null,
                        originalPrice = if (listing.hasVerifiedPrice) listing.originalPrice else null,
                        currency = listing.currency,
                        matchType = listing.matchType,
                        confidence = listing.confidence,
                        productUrl = listing.productUrl,
                        listingOrigin = listing.listingOrigin,
                        sourceType = listing.sourceType,
                        isLowestPrice = (summary?.hasComparableBestPrice == true) &&
                            listing.hasVerifiedPrice &&
                            listing.normalizedMerchantId == summary.lowestPriceListing?.normalizedMerchantId,
                        stockNote = when {
                            listing.hasVerifiedPrice && listing.availability.name == "IN_STOCK" -> "In Stock"
                            listing.hasVerifiedPrice -> "Check Merchant"
                            else -> "Open search results"
                        },
                        imageUrl = listing.imageUrl.takeIf { it.isNotBlank() },
                        providerProductId = listing.providerProductId,
                        offerSource = listing.offerSource
                    )
                },
                isDemoFixture = recognized.isDemoFixture,
                isLiveInference = recognized.isLiveInference,
                imageUri = resolvedPath,
                isUncertain = isUnidentified || recognized.status != RecognitionStatus.IDENTIFIED || recognized.candidates.size > 1,
                recognitionStatus = recognized.status,
                candidates = recognized.candidates,
                searchQuery = canonicalQuery,
                recognitionSource = recognized.recognitionSource,
                rateLimited = recognized.rateLimited
            )

            withContext(Dispatchers.Main) {
                CaptureEventBus.emit(CaptureIntentEvent.ShowProductMatch(matchResult))
            }
        }
    }

    private fun processSharedText(text: String) {
        val sanitized = SecurityUtils.sanitizeSearchQuery(text)
        val extractedUrl = SecurityUtils.extractWebUrl(text)

        lifecycleScope.launch(Dispatchers.IO) {
            val lookupResp = if (extractedUrl != null) {
                liveMerchantDiscoveryEngine.lookupSharedUrl(extractedUrl, this@MainActivity)
            } else null

            val queryTitle = when {
                !lookupResp?.title.isNullOrBlank() -> lookupResp!!.title!!
                extractedUrl != null -> {
                    val parsed = urlExtractor.extract(extractedUrl)
                    parsed.title ?: "Shared Product"
                }
                else -> sanitized
            }

            val cleanInput = SearchQueryBuilder.buildFromRawQuery(queryTitle)
            if (cleanInput is SearchQueryBuilder.Result.Rejected && lookupResp?.listings.isNullOrEmpty()) {
                val rejectedResult = DetectedProductMatchResult(
                    detectedTitle = "Unidentified Shared Link",
                    brand = "Needs Query",
                    category = "Unidentified",
                    specs = emptyList(),
                    matchReason = "Could not extract a clean product name from the shared text or link.",
                    listings = emptyList(),
                    isDemoFixture = false,
                    isLiveInference = false,
                    isUncertain = true,
                    recognitionStatus = RecognitionStatus.UNIDENTIFIED
                )
                withContext(Dispatchers.Main) {
                    CaptureEventBus.emit(CaptureIntentEvent.ShowProductMatch(rejectedResult))
                }
                return@launch
            }

            val verifiedQuery = (cleanInput as? SearchQueryBuilder.Result.Valid)?.query ?: queryTitle
            val parsedAttrs = aiProvider.recognizeFromText(verifiedQuery)
            val engine = if (parsedAttrs.recognitionSource == RecognitionSource.DEMO && parsedAttrs.isDemoFixture) {
                demoMerchantDiscoveryEngine
            } else {
                liveMerchantDiscoveryEngine
            }
            val discovery = engine.discoverStructured(parsedAttrs)
            val listings = engine.deduplicateListings(
                (lookupResp?.listings ?: emptyList()) +
                    discovery.exactMatches +
                    discovery.similarAlternatives +
                    discovery.searchLinks
            )
            val summary = MerchantComparisonSummary.fromListings(verifiedQuery, listings)
            val resolvedImg = lookupResp?.listings?.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl
                ?: listings.firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl

            val matchResult = DetectedProductMatchResult(
                detectedTitle = parsedAttrs.title,
                brand = parsedAttrs.brand ?: "Product",
                category = parsedAttrs.category,
                specs = parsedAttrs.specifications.entries.map { "${it.key}: ${it.value}" },
                matchReason = if (listings.any { it.hasVerifiedPrice }) {
                    "Found ${listings.count { it.hasVerifiedPrice }} live offer(s) across ${listings.size} stores for \"$verifiedQuery\"."
                } else {
                    "Prepared ${listings.size} store matches for \"$verifiedQuery\"."
                },
                listings = listings.map { listing: MerchantListing ->
                    MerchantListingItem(
                        merchantName = listing.merchantName,
                        price = if (listing.hasVerifiedPrice) listing.price else null,
                        originalPrice = if (listing.hasVerifiedPrice) listing.originalPrice else null,
                        currency = listing.currency,
                        matchType = listing.matchType,
                        confidence = listing.confidence,
                        productUrl = listing.productUrl,
                        listingOrigin = listing.listingOrigin,
                        sourceType = listing.sourceType,
                        isLowestPrice = (summary?.hasComparableBestPrice == true) &&
                            listing.hasVerifiedPrice &&
                            listing.normalizedMerchantId == summary.lowestPriceListing?.normalizedMerchantId,
                        stockNote = if (listing.hasVerifiedPrice) "In Stock" else "Open search results",
                        imageUrl = listing.imageUrl.takeIf { it.isNotBlank() },
                        providerProductId = listing.providerProductId,
                        offerSource = listing.offerSource
                    )
                },
                isDemoFixture = parsedAttrs.isDemoFixture,
                isLiveInference = parsedAttrs.isLiveInference || listings.any { it.hasVerifiedPrice },
                imageUri = resolvedImg,
                isUncertain = parsedAttrs.status != RecognitionStatus.IDENTIFIED,
                recognitionStatus = parsedAttrs.status,
                candidates = parsedAttrs.candidates,
                searchQuery = verifiedQuery,
                recognitionSource = parsedAttrs.recognitionSource,
                rateLimited = parsedAttrs.rateLimited
            )

            withContext(Dispatchers.Main) {
                CaptureEventBus.emit(CaptureIntentEvent.ShowProductMatch(matchResult))
            }
        }
    }

    fun launchCircleToShopFlow() {
        if (!Settings.canDrawOverlays(this)) {
            pendingCircleToShopAfterOverlay = true
            Toast.makeText(
                this,
                "Enable Display over other apps to use Circle to Shop",
                Toast.LENGTH_SHORT
            ).show()
            promptOverlayPermission()
            return
        }
        pendingCircleToShopAfterOverlay = false
        CaptureEventBus.emit(CaptureIntentEvent.StartCircleToShop)
        startActivity(CircleToShopConsentActivity.createLaunchIntent(this))
    }

    fun promptOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    companion object {
        private const val TAG = "MainActivity"

        const val ACTION_REQUEST_OVERLAY_PERMISSION = "com.paypal.visualcart.action.REQUEST_OVERLAY_PERMISSION"
        const val EXTRA_REQUEST_OVERLAY_PERMISSION = "extra_request_overlay_permission"

        const val ACTION_CAPTURE = "com.paypal.visualcart.action.CAPTURE"
        const val EXTRA_CAPTURE_TYPE = "extra_capture_type"
        const val CAPTURE_TYPE_SCREENSHOT = "screenshot"
        const val CAPTURE_TYPE_LINK = "link"
        const val CAPTURE_TYPE_CIRCLE_TO_SHOP = "circle_to_shop"
    }
}
