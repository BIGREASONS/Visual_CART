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
import com.paypal.visualcart.core.util.SecurityUtils
import com.paypal.visualcart.core.util.SharingShortcutsManager
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.feature.ai.CloudProductUnderstandingProvider
import com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider
import com.paypal.visualcart.feature.ai.LocalProductParser
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine
import com.paypal.visualcart.feature.merchant.ProductUrlExtractor
import com.paypal.visualcart.feature.payment.PayPalDeepLinkHandler
import com.paypal.visualcart.service.overlay.VisualCartOverlayService
import com.paypal.visualcart.service.tile.VisualCartTileService
import com.paypal.visualcart.ui.matching.DetectedProductMatchResult
import com.paypal.visualcart.ui.matching.MerchantListingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val demoAiProvider = CloudProductUnderstandingProvider()
    private val merchantDiscoveryEngine = MerchantDiscoveryEngine()
    private val urlExtractor = ProductUrlExtractor()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            VisualCartOverlayService.start(this)
            VisualCartTileService.requestTileUpdate(this)
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

        val shouldRequestOverlay = intent.getBooleanExtra(EXTRA_REQUEST_OVERLAY_PERMISSION, false) ||
                intent.action == ACTION_REQUEST_OVERLAY_PERMISSION

        if (shouldRequestOverlay && !Settings.canDrawOverlays(this)) {
            promptOverlayPermission()
            return
        }

        // 1. Handle PayPal Sandbox custom deep links (Chrome Custom Tabs return/cancel)
        if (PayPalDeepLinkHandler.isPayPalDeepLink(intent)) {
            when (val deepLinkResult = PayPalDeepLinkHandler.parse(intent)) {
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
                when {
                    type.startsWith("image/") -> {
                        val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        } ?: intent.clipData?.getItemAt(0)?.uri

                        if (imageUri != null && SecurityUtils.isValidContentUri(imageUri.toString())) {
                            processSharedImage(imageUri)
                        }
                    }
                    type.startsWith("text/") || type.isEmpty() -> {
                        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                            ?: intent.clipData?.getItemAt(0)?.text?.toString()
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
                val firstUri = imageUris?.firstOrNull() ?: intent.clipData?.getItemAt(0)?.uri
                if (firstUri != null && SecurityUtils.isValidContentUri(firstUri.toString())) {
                    processSharedImage(firstUri)
                }
            }
            ACTION_CAPTURE -> {
                val captureType = intent.getStringExtra(EXTRA_CAPTURE_TYPE) ?: CAPTURE_TYPE_SCREENSHOT
                CaptureEventBus.emit(CaptureIntentEvent.TriggerCapture(captureType))
                if (captureType == CAPTURE_TYPE_SCREENSHOT) {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
        }
    }

    private fun processSharedImage(imageUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Visual product extraction: query demo fixtures or local recognition
            val recognized = demoAiProvider.recognizeFromImage(imageUri.toString())
            val listings = merchantDiscoveryEngine.searchAll(recognized.title, recognized.specifications)
            val minPrice = listings.minOfOrNull { it.price }

            val matchResult = DetectedProductMatchResult(
                detectedTitle = recognized.title,
                brand = recognized.brand ?: "Detected Product",
                category = recognized.category,
                specs = recognized.specifications.entries.map { "${it.key}: ${it.value}" },
                matchReason = recognized.matchReason,
                listings = listings.map { listing: MerchantListing ->
                    MerchantListingItem(
                        merchantName = listing.merchantName,
                        price = listing.price,
                        originalPrice = listing.originalPrice,
                        matchType = listing.matchType,
                        confidence = listing.confidence,
                        productUrl = listing.productUrl,
                        isLowestPrice = minPrice != null && listing.price == minPrice,
                        stockNote = if (listing.availability.name == "IN_STOCK") "In Stock" else "Check Merchant"
                    )
                },
                isDemoFixture = recognized.isDemoFixture,
                isLiveInference = recognized.isLiveInference
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
            val queryTitle = if (extractedUrl != null) {
                val parsed = urlExtractor.extract(extractedUrl)
                parsed.title ?: "Shared Product"
            } else {
                sanitized
            }

            val parsedAttrs = demoAiProvider.recognizeFromText(queryTitle)
            val listings = merchantDiscoveryEngine.searchAll(parsedAttrs.title, parsedAttrs.specifications)
            val minPrice = listings.minOfOrNull { it.price }

            val matchResult = DetectedProductMatchResult(
                detectedTitle = parsedAttrs.title,
                brand = parsedAttrs.brand ?: "Product",
                category = parsedAttrs.category,
                specs = parsedAttrs.specifications.entries.map { "${it.key}: ${it.value}" },
                matchReason = "Matched ${listings.size} stores based on shared product details.",
                listings = listings.map { listing: MerchantListing ->
                    MerchantListingItem(
                        merchantName = listing.merchantName,
                        price = listing.price,
                        originalPrice = listing.originalPrice,
                        matchType = listing.matchType,
                        confidence = listing.confidence,
                        productUrl = listing.productUrl,
                        isLowestPrice = minPrice != null && listing.price == minPrice,
                        stockNote = "In Stock"
                    )
                },
                isDemoFixture = parsedAttrs.isDemoFixture,
                isLiveInference = parsedAttrs.isLiveInference
            )

            withContext(Dispatchers.Main) {
                CaptureEventBus.emit(CaptureIntentEvent.ShowProductMatch(matchResult))
            }
        }
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
    }
}
