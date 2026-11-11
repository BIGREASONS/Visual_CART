package com.paypal.visualcart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.paypal.visualcart.core.capture.CaptureEventBus
import com.paypal.visualcart.core.capture.CaptureIntentEvent
import com.paypal.visualcart.data.local.VisualCartDatabase
import com.paypal.visualcart.data.local.entity.MerchantListingEntity
import com.paypal.visualcart.data.repository.CartRepositoryImpl
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import com.paypal.visualcart.feature.payment.PayPalCheckoutCoordinator
import com.paypal.visualcart.feature.payment.PayPalCheckoutUiState
import com.paypal.visualcart.ui.cart.CartScreen
import com.paypal.visualcart.ui.checkout.PayPalCheckoutBottomSheet
import com.paypal.visualcart.ui.detail.ProductDetailScreen
import com.paypal.visualcart.ui.home.HomeScreen
import com.paypal.visualcart.ui.matching.DetectedProductMatchResult
import com.paypal.visualcart.ui.matching.MerchantListingItem
import com.paypal.visualcart.ui.matching.ProductMatchingBottomSheet
import com.paypal.visualcart.ui.matching.defaultDetectedResult
import com.paypal.visualcart.ui.matching.resolveManualRefinedMatch
import com.paypal.visualcart.ui.matching.sonyDemoDetectedResult
import com.paypal.visualcart.ui.onboarding.OnboardingScreen
import com.paypal.visualcart.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MainNavigation(
    onLaunchPhotoPicker: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cartRepository = remember { CartRepositoryImpl(VisualCartDatabase.getInstance(context)) }

    val backStack = rememberNavBackStack(HomeNavKey)
    var showMatchingSheet by remember { mutableStateOf(false) }
    var currentMatchResult by remember { mutableStateOf<DetectedProductMatchResult?>(null) }

    // Observe incoming capture events (Circle to Search shares, screenshot picker, URLs)
    LaunchedEffect(Unit) {
        CaptureEventBus.events.collect { event ->
            if (!CaptureEventBus.markConsumed(event.eventId)) {
                return@collect
            }
            when (event) {
                is CaptureIntentEvent.ShowProductMatch -> {
                    currentMatchResult = event.result
                    showMatchingSheet = true
                }
                is CaptureIntentEvent.TriggerCapture -> {
                    // MainActivity.ACTION_CAPTURE already launches the single Photo Picker instance.
                }
                is CaptureIntentEvent.OpenCart -> {
                    backStack.add(CartNavKey)
                }
                is CaptureIntentEvent.OpenSettings -> {
                    if (backStack.lastOrNull() != SettingsNavKey) {
                        backStack.add(SettingsNavKey)
                    }
                }
                is CaptureIntentEvent.PayPalCheckoutApproved -> {
                    backStack.add(CartNavKey)
                }
                is CaptureIntentEvent.PayPalCheckoutCancelled -> {
                    // Retain current position, user cancelled flow
                }
                else -> Unit
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<HomeNavKey> {
                    HomeScreen(
                        onNavigateToCart = { backStack.add(CartNavKey) },
                        onNavigateToDetail = { id -> backStack.add(ProductDetailNavKey(id)) },
                        onNavigateToSettings = { backStack.add(SettingsNavKey) },
                        onProductDetected = {
                            currentMatchResult = defaultDetectedResult()
                            showMatchingSheet = true
                        },
                        onDemoScan = { preset ->
                            currentMatchResult = if (preset == "sony") {
                                sonyDemoDetectedResult()
                            } else {
                                defaultDetectedResult()
                            }
                            showMatchingSheet = true
                        }
                    )
                }
                entry<CartNavKey> {
                    CartScreen(
                        onNavigateBack = { backStack.removeLastOrNull() },
                        onNavigateToDetail = { id -> backStack.add(ProductDetailNavKey(id)) },
                        onDiscoverMore = { backStack.removeLastOrNull() }
                    )
                }
                entry<ProductDetailNavKey> { key ->
                    ProductDetailScreen(
                        productId = key.productId,
                        onNavigateBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<SettingsNavKey> {
                    SettingsScreen(
                        onNavigateBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<OnboardingNavKey> {
                    OnboardingScreen(
                        onFinished = { backStack.removeLastOrNull() }
                    )
                }
            }
        )

        val activeMatch = currentMatchResult
        if (showMatchingSheet && activeMatch != null) {
            ProductMatchingBottomSheet(
                detectedMatch = activeMatch,
                onDismiss = {
                    showMatchingSheet = false
                    currentMatchResult = null
                },
                onRefineQuery = { refinedQuery ->
                    scope.launch {
                        val updated = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            resolveManualRefinedMatch(refinedQuery, activeMatch.imageUri, context)
                        }
                        currentMatchResult = updated
                    }
                },
                onRetryRecognition = {
                    showMatchingSheet = false
                    currentMatchResult = null
                    onLaunchPhotoPicker()
                },
                onAddToCart = { selectedItem: MerchantListingItem ->
                    scope.launch(Dispatchers.IO) {
                        val deduped = activeMatch.listings.distinctBy {
                            SearchQueryBuilder.normalizeMerchantKey(it.merchantName)
                        }
                        val listings = deduped.map { item ->
                            MerchantListingEntity(
                                savedProductId = "",
                                merchant = item.merchantName,
                                title = activeMatch.detectedTitle,
                                price = if (item.hasVerifiedPrice) item.price else null,
                                originalPrice = if (item.hasVerifiedPrice) item.originalPrice else null,
                                currency = item.currency,
                                productUrl = item.productUrl,
                                imageUrl = item.imageUrl,
                                matchType = item.matchType.name,
                                confidence = item.confidence,
                                availability = if (item.hasVerifiedPrice) "IN_STOCK" else "UNKNOWN",
                                sourceType = item.sourceType.name,
                                listingOrigin = item.listingOrigin.name,
                                providerProductId = item.providerProductId,
                                offerSource = item.offerSource,
                                lastVerifiedAt = System.currentTimeMillis(),
                                isSelectedForPurchase = SearchQueryBuilder.normalizeMerchantKey(item.merchantName) ==
                                    SearchQueryBuilder.normalizeMerchantKey(selectedItem.merchantName)
                            )
                        }

                        cartRepository.saveProductWithListings(
                            title = activeMatch.detectedTitle,
                            brand = activeMatch.brand,
                            category = activeMatch.category,
                            imageUri = activeMatch.imageUri,
                            originalSource = selectedItem.productUrl,
                            canonicalProductId = com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeProductIdentityKey(
                                activeMatch.detectedTitle
                            ),
                            listings = listings
                        )
                    }
                    showMatchingSheet = false
                    currentMatchResult = null
                    backStack.add(CartNavKey)
                },
                onCompare = { canonicalFallbackId ->
                    scope.launch {
                        val targetId = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            if (activeMatch.listings.isNotEmpty()) {
                                val deduped = activeMatch.listings.distinctBy {
                                    SearchQueryBuilder.normalizeMerchantKey(it.merchantName)
                                }
                                val listings = deduped.mapIndexed { idx, item ->
                                    MerchantListingEntity(
                                        savedProductId = "",
                                        merchant = item.merchantName,
                                        title = activeMatch.detectedTitle,
                                        price = if (item.hasVerifiedPrice) item.price else null,
                                        originalPrice = if (item.hasVerifiedPrice) item.originalPrice else null,
                                        currency = item.currency,
                                        productUrl = item.productUrl,
                                        imageUrl = item.imageUrl,
                                        matchType = item.matchType.name,
                                        confidence = item.confidence,
                                        availability = if (item.hasVerifiedPrice) "IN_STOCK" else "UNKNOWN",
                                        sourceType = item.sourceType.name,
                                        listingOrigin = item.listingOrigin.name,
                                        providerProductId = item.providerProductId,
                                        offerSource = item.offerSource,
                                        lastVerifiedAt = System.currentTimeMillis(),
                                        isSelectedForPurchase = idx == 0
                                    )
                                }
                                val saved = cartRepository.saveProductWithListings(
                                    title = activeMatch.detectedTitle,
                                    brand = activeMatch.brand,
                                    category = activeMatch.category,
                                    imageUri = activeMatch.imageUri,
                                    originalSource = deduped.firstOrNull()?.productUrl,
                                    canonicalProductId = canonicalFallbackId,
                                    listings = listings
                                )
                                saved.product.id
                            } else {
                                canonicalFallbackId
                            }
                        }
                        showMatchingSheet = false
                        currentMatchResult = null
                        backStack.add(ProductDetailNavKey(targetId))
                    }
                }
            )
        }

        val checkoutState by PayPalCheckoutCoordinator.instance.uiState.collectAsState()
        if (checkoutState !is PayPalCheckoutUiState.Idle) {
            PayPalCheckoutBottomSheet(
                coordinator = PayPalCheckoutCoordinator.instance,
                onDismiss = { PayPalCheckoutCoordinator.instance.dismiss() }
            )
        }
    }
}
