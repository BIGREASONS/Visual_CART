package com.paypal.visualcart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.paypal.visualcart.ui.cart.CartScreen
import com.paypal.visualcart.ui.detail.ProductDetailScreen
import com.paypal.visualcart.ui.home.HomeScreen
import com.paypal.visualcart.ui.matching.DetectedProductMatchResult
import com.paypal.visualcart.ui.matching.MerchantListingItem
import com.paypal.visualcart.ui.matching.ProductMatchingBottomSheet
import com.paypal.visualcart.ui.matching.defaultDetectedResult
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
            when (event) {
                is CaptureIntentEvent.ShowProductMatch -> {
                    currentMatchResult = event.result
                    showMatchingSheet = true
                }
                is CaptureIntentEvent.TriggerCapture -> {
                    if (event.captureType == "screenshot") {
                        onLaunchPhotoPicker()
                    }
                }
                is CaptureIntentEvent.OpenCart -> {
                    backStack.add(CartNavKey)
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

        if (showMatchingSheet) {
            val match = currentMatchResult ?: defaultDetectedResult()
            ProductMatchingBottomSheet(
                detectedMatch = match,
                onDismiss = {
                    showMatchingSheet = false
                    currentMatchResult = null
                },
                onAddToCart = { selectedItem: MerchantListingItem ->
                    scope.launch(Dispatchers.IO) {
                        val listings = match.listings.map { item ->
                            MerchantListingEntity(
                                savedProductId = "",
                                merchant = item.merchantName,
                                title = match.detectedTitle,
                                price = item.price,
                                currency = "INR",
                                productUrl = item.productUrl,
                                matchType = item.matchType.name,
                                confidence = item.confidence,
                                availability = "IN_STOCK",
                                sourceType = "DEMO",
                                lastVerifiedAt = System.currentTimeMillis(),
                                isSelectedForPurchase = item == selectedItem
                            )
                        }

                        cartRepository.saveProductWithListings(
                            title = match.detectedTitle,
                            brand = match.brand,
                            category = match.category,
                            imageUri = null,
                            originalSource = selectedItem.productUrl,
                            listings = listings
                        )
                    }
                    showMatchingSheet = false
                    currentMatchResult = null
                    backStack.add(CartNavKey)
                },
                onCompare = { id ->
                    showMatchingSheet = false
                    currentMatchResult = null
                    backStack.add(ProductDetailNavKey(id))
                }
            )
        }
    }
}
