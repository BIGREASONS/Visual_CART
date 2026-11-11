package com.paypal.visualcart.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paypal.visualcart.core.designsystem.ButtonVariant
import com.paypal.visualcart.core.designsystem.ConfidenceIndicator
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.DemoDataBadge
import com.paypal.visualcart.core.designsystem.ForestGreen
import com.paypal.visualcart.core.designsystem.ForestGreenLight
import com.paypal.visualcart.core.designsystem.MerchantLogoBadge
import com.paypal.visualcart.core.designsystem.MutedSage
import com.paypal.visualcart.core.designsystem.MutedSlate
import com.paypal.visualcart.core.designsystem.PureWhite
import com.paypal.visualcart.core.designsystem.SageLight
import com.paypal.visualcart.core.designsystem.SpecTag
import com.paypal.visualcart.core.designsystem.Stone
import com.paypal.visualcart.core.designsystem.StoneLight
import com.paypal.visualcart.core.designsystem.Terracotta
import com.paypal.visualcart.core.designsystem.TerracottaLight
import com.paypal.visualcart.core.designsystem.VisualCartButton
import com.paypal.visualcart.core.designsystem.VisualCartCard
import com.paypal.visualcart.core.designsystem.VisualCartIcons
import com.paypal.visualcart.core.designsystem.VisualCartTypography
import com.paypal.visualcart.core.designsystem.WarmIvory
import com.paypal.visualcart.data.local.VisualCartDatabase
import com.paypal.visualcart.data.local.entity.SavedProductWithListings
import com.paypal.visualcart.data.repository.CartRepositoryImpl
import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import com.paypal.visualcart.feature.payment.PayPalCheckoutCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProductDetailScreen(
    productId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cartRepository = remember(context) {
        CartRepositoryImpl(VisualCartDatabase.getInstance(context))
    }
    var savedProduct by remember(productId) { mutableStateOf<SavedProductWithListings?>(null) }
    var isRefreshingLiveOffers by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(productId) {
        savedProduct = withContext(Dispatchers.IO) {
            cartRepository.getProductById(productId)
        }
    }

    val knownDemoIds = remember {
        setOf(
            "crucial-p3-plus-1tb",
            "sony-wh1000xm5",
            "apple-watch-ultra-2",
            "nike-air-jordan-1",
            "keychron-k2-v2"
        )
    }
    val isKnownDemoProduct = remember(productId, savedProduct) {
        savedProduct == null && productId in knownDemoIds
    }
    val isDemoProduct = remember(savedProduct, isKnownDemoProduct) {
        isKnownDemoProduct || (savedProduct?.listings?.any { it.sourceType == "DEMO" && it.hasVerifiedPrice } == true)
    }

    val productTitle = remember(productId, savedProduct) {
        savedProduct?.product?.title ?: when (productId) {
            "crucial-p3-plus-1tb" -> "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD"
            "sony-wh1000xm5" -> "Sony WH-1000XM5 Noise Cancelling Wireless Headphones"
            "apple-watch-ultra-2" -> "Apple Watch Ultra 2 GPS + Cellular 49mm Titanium"
            "nike-air-jordan-1" -> "Nike Air Jordan 1 Low Retro Neutral Grey"
            "keychron-k2-v2" -> "Keychron K2 V2 Wireless Mechanical Keyboard Hot-Swappable"
            else -> "Product Details Unavailable"
        }
    }

    val productBrand = remember(productId, savedProduct) {
        savedProduct?.product?.brand?.takeIf { it.isNotBlank() } ?: when (productId) {
            "crucial-p3-plus-1tb" -> "Crucial"
            "sony-wh1000xm5" -> "Sony"
            "apple-watch-ultra-2" -> "Apple"
            "nike-air-jordan-1" -> "Nike"
            "keychron-k2-v2" -> "Keychron"
            else -> "Unverified"
        }
    }

    val productCategory = remember(productId, savedProduct) {
        savedProduct?.product?.category ?: when (productId) {
            "crucial-p3-plus-1tb" -> "Internal SSD"
            "sony-wh1000xm5" -> "Electronics"
            "apple-watch-ultra-2" -> "Accessories"
            "nike-air-jordan-1" -> "Fashion"
            "keychron-k2-v2" -> "Gaming"
            else -> "General"
        }
    }

    val canonicalModelLabel = remember(productId, savedProduct) {
        savedProduct?.product?.canonicalProductId?.uppercase() ?: productId.uppercase()
    }

    val specPills = remember(productId, savedProduct, productBrand, productCategory) {
        val currentSaved = savedProduct
        if (currentSaved != null) {
            listOfNotNull(
                productBrand.takeIf { it != "Product" && it != "Unverified" },
                productCategory,
                "${currentSaved.listings.size} stores"
            )
        } else {
            when (productId) {
                "crucial-p3-plus-1tb" -> listOf("1TB", "NVMe 1.4", "PCIe 4.0", "M.2 2280", "5000 MB/s")
                "sony-wh1000xm5" -> listOf("ANC", "30hr Battery", "Bluetooth 5.2")
                "apple-watch-ultra-2" -> listOf("49mm", "Titanium", "Cellular")
                "nike-air-jordan-1" -> listOf("US 10", "Leather", "Low Cut")
                "keychron-k2-v2" -> listOf("Gateron Brown", "Wireless", "75% Layout")
                else -> emptyList()
            }
        }
    }

    val canonicalSpecs = remember(productId, savedProduct, productTitle, productBrand, productCategory) {
        val currentSaved = savedProduct
        if (currentSaved != null) {
            listOf(
                "Product" to productTitle,
                "Brand" to productBrand,
                "Category" to productCategory,
                "Captured From" to (currentSaved.product.originalSource ?: "Visual Cart"),
                "Tracked Stores" to "${currentSaved.listings.size} merchants"
            )
        } else if (productId == "crucial-p3-plus-1tb") {
            listOf(
                "Capacity" to "1 TB",
                "Interface" to "PCIe Gen 4.0 x4, NVMe 1.4",
                "Form Factor" to "M.2 2280",
                "Seq. Read Speed" to "Up to 5,000 MB/s",
                "Seq. Write Speed" to "Up to 4,200 MB/s",
                "NAND Technology" to "Micron 176-layer 3D NAND",
                "Endurance" to "220 TBW",
                "Warranty" to "5-Year Manufacturer Warranty",
                "Model / Part No." to "CT1000P3PSSD8"
            )
        } else {
            emptyList()
        }
    }

    val merchantListings = remember(productId, savedProduct) {
        val currentSaved = savedProduct
        if (currentSaved != null && currentSaved.listings.isNotEmpty()) {
            val dedupedEntities = currentSaved.listings.distinctBy {
                com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(it.merchant)
            }
            val pricedEntities = dedupedEntities.filter { it.hasVerifiedPrice }
            val comparableCurrencies = pricedEntities.map { it.currency.uppercase() }.distinct()
            val comparableMinPrice = if (comparableCurrencies.size == 1 && pricedEntities.size >= 2) {
                pricedEntities.minOfOrNull { it.price!! }
            } else {
                null
            }
            dedupedEntities.map { entity ->
                val validPrice = if (entity.hasVerifiedPrice) entity.price else null
                MerchantDetailListing(
                    name = entity.merchant,
                    price = validPrice,
                    originalPrice = entity.originalPrice?.takeIf { validPrice != null && it > validPrice },
                    currency = entity.currency,
                    hasVerifiedPrice = entity.hasVerifiedPrice,
                    listingOrigin = entity.listingOrigin,
                    status = if (entity.hasVerifiedPrice) entity.availability.replace('_', ' ') else "Check store",
                    delivery = entity.deliveryEstimate ?: if (entity.hasVerifiedPrice) "See merchant site" else "Open search results",
                    matchType = runCatching { MatchType.valueOf(entity.matchType) }.getOrDefault(MatchType.EXACT),
                    confidence = entity.confidence,
                    isLowest = comparableMinPrice != null && validPrice != null && validPrice == comparableMinPrice,
                    url = entity.productUrl,
                    providerProductId = entity.providerProductId,
                    offerSource = entity.offerSource
                )
            }
        } else if (productId == "crucial-p3-plus-1tb") {
            listOf(
                MerchantDetailListing(
                    name = "Flipkart",
                    price = 5499.0,
                    originalPrice = 6099.0,
                    status = "In Stock",
                    delivery = "Tomorrow, by 2 PM",
                    matchType = MatchType.LIKELY,
                    confidence = 0.92f,
                    isLowest = true,
                    url = "https://www.flipkart.com"
                ),
                MerchantDetailListing(
                    name = "Croma",
                    price = 5799.0,
                    originalPrice = 6199.0,
                    status = "In Stock",
                    delivery = "Thursday, Feb 12",
                    matchType = MatchType.EXACT,
                    confidence = 0.95f,
                    isLowest = false,
                    url = "https://www.croma.com"
                ),
                MerchantDetailListing(
                    name = "Amazon India",
                    price = 5999.0,
                    originalPrice = 6499.0,
                    status = "In Stock",
                    delivery = "Prime One-Day",
                    matchType = MatchType.EXACT,
                    confidence = 0.98f,
                    isLowest = false,
                    url = "https://www.amazon.in"
                ),
                MerchantDetailListing(
                    name = "Reliance Digital",
                    price = 6199.0,
                    originalPrice = 6599.0,
                    status = "Only 2 left",
                    delivery = "Friday, Feb 13",
                    matchType = MatchType.SIMILAR,
                    confidence = 0.82f,
                    isLowest = false,
                    url = "https://www.reliancedigital.in"
                )
            )
        } else {
            emptyList()
        }
    }

    val bestDeal = remember(merchantListings) {
        merchantListings.filter { it.hasVerifiedPrice && it.price != null }
            .minByOrNull { it.price ?: Double.MAX_VALUE }
            ?: merchantListings.firstOrNull()
    }
    val comparablePricedCount = remember(merchantListings) {
        val priced = merchantListings.filter { it.hasVerifiedPrice && it.price != null }
        if (priced.map { it.currency.uppercase() }.distinct().size == 1) priced.size else 0
    }
    val realObservations = remember(savedProduct) {
        savedProduct?.priceObservations?.filter { it.price > 0.0 }?.sortedBy { it.observedAt } ?: emptyList()
    }
    val highestObservedPrice = remember(merchantListings, realObservations, bestDeal) {
        realObservations.maxOfOrNull { it.price }
            ?: merchantListings.mapNotNull { if (it.hasVerifiedPrice) (it.originalPrice ?: it.price) else null }.maxOrNull()
            ?: bestDeal?.price
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = WarmIvory
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PureWhite)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = DeepInk,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Product Comparison",
                        style = VisualCartTypography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDemoProduct) {
                        DemoDataBadge()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = {
                            val shareText = if (bestDeal?.hasVerifiedPrice == true && bestDeal.price != null) {
                                "Comparing $productTitle on Visual Cart - ${com.paypal.visualcart.core.util.CurrencyFormatter.format(bestDeal.price, bestDeal.currency)} on ${bestDeal.name}!"
                            } else {
                                "Tracking $productTitle on Visual Cart."
                            }
                            val shareIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Comparison"))
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PureWhite)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = DeepInk,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Canonical Title & Brand Header
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "${productBrand.uppercase()} · ${productCategory.uppercase()}",
                    style = VisualCartTypography.labelSmall.copy(
                        color = MutedSlate,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = productTitle,
                    style = VisualCartTypography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk,
                        lineHeight = 28.sp
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Spec Pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    specPills.forEach { pill ->
                        SpecTag(spec = pill)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Hero Gallery / Visual Presentation Card
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Main product graphic representation
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(StoneLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = VisualCartIcons.ScanCamera,
                                    contentDescription = null,
                                    tint = ForestGreen,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "CANONICAL PRODUCT ID: $canonicalModelLabel",
                                    style = VisualCartTypography.labelSmall.copy(
                                        color = MutedSlate,
                                        fontSize = 9.sp,
                                        letterSpacing = 0.8.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Best Price / Search Status Banner
                        val activeBest = bestDeal
                        val bestPrice = activeBest?.takeIf { it.hasVerifiedPrice }?.price
                        val bestOriginal = activeBest?.originalPrice
                        val bannerSavings = if (bestPrice != null && bestOriginal != null && bestOriginal > bestPrice) {
                            bestOriginal - bestPrice
                        } else 0.0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = when {
                                        bestPrice != null && comparablePricedCount >= 2 -> "VERIFIED BEST PRICE"
                                        bestPrice != null && savedProduct != null -> "SELECTED MERCHANT PRICE"
                                        bestPrice != null -> "OFFER PRICE"
                                        else -> "LIVE STORE SEARCH LINKS"
                                    },
                                    style = VisualCartTypography.labelSmall.copy(
                                        color = if (bestPrice != null) ForestGreen else MutedSlate,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    )
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    if (bestPrice != null) {
                                        Text(
                                            text = com.paypal.visualcart.core.util.CurrencyFormatter.format(bestPrice, activeBest?.currency),
                                            style = VisualCartTypography.displayMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = DeepInk
                                            )
                                        )
                                        if (bestOriginal != null && bestOriginal > bestPrice) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = com.paypal.visualcart.core.util.CurrencyFormatter.format(bestOriginal, activeBest?.currency),
                                                style = VisualCartTypography.bodyMedium.copy(
                                                    textDecoration = TextDecoration.LineThrough,
                                                    color = MutedSlate
                                                )
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "Price unavailable",
                                            style = VisualCartTypography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = DeepInk
                                            )
                                        )
                                    }
                                }
                            }

                            if (bannerSavings > 0 && bestPrice != null) {
                                val pct = ((bannerSavings / (bestOriginal ?: bestPrice)) * 100).toInt()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = TerracottaLight,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Terracotta.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = "Save ${com.paypal.visualcart.core.util.CurrencyFormatter.format(bannerSavings, activeBest?.currency)} ($pct%)",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = VisualCartTypography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Terracotta
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // PayPal 1-Click Sandbox Checkout (gated strictly on verified price)
                        if (activeBest != null && bestPrice != null) {
                            VisualCartButton(
                                text = "Checkout with PayPal · ${com.paypal.visualcart.core.util.CurrencyFormatter.format(bestPrice, activeBest.currency)}",
                                onClick = {
                                    val originEnum = runCatching {
                                        com.paypal.visualcart.domain.model.ListingOrigin.valueOf(activeBest.listingOrigin)
                                    }.getOrDefault(com.paypal.visualcart.domain.model.ListingOrigin.VERIFIED_CATALOG_FIXTURE)
                                    val listing = MerchantListing(
                                        id = "${activeBest.name.lowercase().replace("\\s+".toRegex(), "_")}_$productId",
                                        merchantName = activeBest.name,
                                        merchantDomain = Uri.parse(activeBest.url).host ?: "store.example.com",
                                        title = productTitle,
                                        price = bestPrice,
                                        originalPrice = activeBest.originalPrice,
                                        currency = activeBest.currency,
                                        productUrl = activeBest.url,
                                        imageUrl = "",
                                        matchType = activeBest.matchType,
                                        confidence = activeBest.confidence,
                                        availability = AvailabilityStatus.IN_STOCK,
                                        sourceType = if (isDemoProduct) SourceType.DEMO else SourceType.LIVE,
                                        listingOrigin = originEnum,
                                        lastVerifiedAt = System.currentTimeMillis(),
                                        providerProductId = activeBest.providerProductId,
                                        offerSource = activeBest.offerSource
                                    )
                                    PayPalCheckoutCoordinator.instance.startCheckout(context, listing)
                                },
                                variant = ButtonVariant.PRIMARY,
                                icon = VisualCartIcons.ShieldCheck,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (activeBest != null) {
                            VisualCartButton(
                                text = "Open ${activeBest.name} for Live Price",
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(activeBest.url))
                                    context.startActivity(intent)
                                },
                                variant = ButtonVariant.SECONDARY,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = activeBest?.let {
                                    if (bestPrice != null) {
                                        "Covered by PayPal Buyer Protection · Merchant: ${it.name}"
                                    } else {
                                        "External store search link · Check live price on ${it.name}"
                                    }
                                } ?: "Search link only · Select a verified offer when available",
                                style = VisualCartTypography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = MutedSlate
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Merchant Comparison List
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Compare Stores & Availability",
                        style = VisualCartTypography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (savedProduct != null) {
                        TextButton(
                            enabled = !isRefreshingLiveOffers,
                            onClick = {
                                val currentSaved = savedProduct ?: return@TextButton
                                isRefreshingLiveOffers = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val c3Client = com.paypal.visualcart.feature.merchant.Channel3DiscoveryClient(context)
                                    val providerId = currentSaved.listings.firstOrNull { !it.providerProductId.isNullOrBlank() }?.providerProductId
                                    val resp = if (!providerId.isNullOrBlank()) {
                                        c3Client.refreshProduct(providerId)
                                    } else {
                                        c3Client.searchProducts(currentSaved.product.title)
                                    }
                                    if (resp.ok && resp.listings.isNotEmpty()) {
                                        val selectedMerchantKey = currentSaved.selectedListing?.let {
                                            com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(it.merchant)
                                        }
                                        val liveEntities = resp.listings.mapIndexed { idx, l ->
                                            val sameMerchant = selectedMerchantKey != null &&
                                                com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(l.merchantName) == selectedMerchantKey
                                            com.paypal.visualcart.data.local.entity.MerchantListingEntity(
                                                savedProductId = currentSaved.product.id,
                                                merchant = l.merchantName,
                                                title = l.title,
                                                price = l.price,
                                                originalPrice = l.originalPrice,
                                                currency = l.currency,
                                                productUrl = l.productUrl,
                                                imageUrl = l.imageUrl.takeIf { it.isNotBlank() },
                                                matchType = l.matchType.name,
                                                confidence = l.confidence,
                                                availability = l.availability.name,
                                                sourceType = l.sourceType.name,
                                                listingOrigin = l.listingOrigin.name,
                                                providerProductId = l.providerProductId,
                                                offerSource = l.offerSource,
                                                lastVerifiedAt = System.currentTimeMillis(),
                                                deliveryEstimate = l.deliveryEstimate,
                                                isSelectedForPurchase = sameMerchant || (selectedMerchantKey == null && idx == 0)
                                            )
                                        }
                                        val existingSearchLinks = currentSaved.listings.filter {
                                            it.listingOrigin == "DIRECT_SEARCH_URL"
                                        }.map { it.copy(isSelectedForPurchase = false) }
                                        cartRepository.replaceProductListings(
                                            currentSaved.product.id,
                                            liveEntities + existingSearchLinks
                                        )
                                        val updated = cartRepository.getProductById(currentSaved.product.id)
                                        withContext(Dispatchers.Main) {
                                            savedProduct = updated
                                            isRefreshingLiveOffers = false
                                            android.widget.Toast.makeText(
                                                context,
                                                "Refreshed ${liveEntities.size} live offer(s)",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            isRefreshingLiveOffers = false
                                            android.widget.Toast.makeText(
                                                context,
                                                resp.error ?: "No new live offers found right now",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = if (isRefreshingLiveOffers) "Refreshing…" else "Refresh Live Offers",
                                style = VisualCartTypography.labelMedium.copy(
                                    color = ForestGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    merchantListings.forEach { listing ->
                        VisualCartCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = PureWhite,
                            borderColor = if (listing.isLowest) ForestGreen.copy(alpha = 0.6f) else Stone,
                            borderWidth = if (listing.isLowest) 1.5.dp else 1.dp,
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MerchantLogoBadge(merchantName = listing.name)
                                        if (listing.isLowest) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = ForestGreenLight
                                            ) {
                                                Text(
                                                    text = "BEST DEAL",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = VisualCartTypography.labelSmall.copy(
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = ForestGreen
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(verticalAlignment = Alignment.Bottom) {
                                        val rowPrice = listing.price
                                        if (listing.hasVerifiedPrice && rowPrice != null) {
                                            Text(
                                                text = com.paypal.visualcart.core.util.CurrencyFormatter.format(rowPrice, listing.currency),
                                                style = VisualCartTypography.titleLarge.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = DeepInk
                                                )
                                            )
                                            if (listing.originalPrice != null && listing.originalPrice > rowPrice) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = com.paypal.visualcart.core.util.CurrencyFormatter.format(listing.originalPrice, listing.currency),
                                                    style = VisualCartTypography.bodySmall.copy(
                                                        textDecoration = TextDecoration.LineThrough,
                                                        color = MutedSlate
                                                    )
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Visit store for live price",
                                                style = VisualCartTypography.bodyMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MutedSlate
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "${listing.status} · ${listing.delivery}",
                                        style = VisualCartTypography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = MutedSlate
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(horizontalAlignment = Alignment.End) {
                                    ConfidenceIndicator(
                                        matchType = listing.matchType,
                                        confidence = listing.confidence
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    VisualCartButton(
                                        text = "Open Store",
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(listing.url))
                                            context.startActivity(intent)
                                        },
                                        variant = ButtonVariant.SECONDARY,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Price History Chart Section
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "30-Day Price Observation",
                    style = VisualCartTypography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tracking verified historical prices across all merchants",
                    style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                )

                Spacer(modifier = Modifier.height(14.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(18.dp)
                ) {
                    val hasRealChart = realObservations.size >= 2
                    val lowObs = realObservations.minOfOrNull { it.price } ?: bestDeal?.takeIf { it.hasVerifiedPrice }?.price
                    val highObs = highestObservedPrice
                    if (hasRealChart && lowObs != null && highObs != null) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "LOWEST RECORDED",
                                        style = VisualCartTypography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            color = ForestGreen,
                                            letterSpacing = 0.8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    val obsCurr = realObservations.firstOrNull()?.currency ?: bestDeal?.currency ?: "USD"
                                    Text(
                                        text = com.paypal.visualcart.core.util.CurrencyFormatter.format(lowObs, obsCurr),
                                        style = VisualCartTypography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = DeepInk
                                        )
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "OBSERVED HIGH",
                                        style = VisualCartTypography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            color = MutedSlate,
                                            letterSpacing = 0.8.sp
                                        )
                                    )
                                    val obsCurrHigh = realObservations.firstOrNull()?.currency ?: bestDeal?.currency ?: "USD"
                                    Text(
                                        text = com.paypal.visualcart.core.util.CurrencyFormatter.format(highObs, obsCurrHigh),
                                        style = VisualCartTypography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MutedSlate
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            PriceHistoryGraph(
                                prices = realObservations.map { it.price },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            )
                        }
                    } else if (productId == "crucial-p3-plus-1tb" && savedProduct == null) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "DEMO SAMPLE LOW",
                                        style = VisualCartTypography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            color = ForestGreen,
                                            letterSpacing = 0.8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "₹5499",
                                        style = VisualCartTypography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = DeepInk
                                        )
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "DEMO SAMPLE HIGH",
                                        style = VisualCartTypography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            color = MutedSlate,
                                            letterSpacing = 0.8.sp
                                        )
                                    )
                                    Text(
                                        text = "₹6499",
                                        style = VisualCartTypography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MutedSlate
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            PriceHistoryGraph(
                                prices = listOf(6499.0, 6199.0, 5999.0, 5799.0, 5499.0),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "NO VERIFIED PRICE HISTORY YET",
                                style = VisualCartTypography.labelSmall.copy(
                                    color = MutedSlate,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (realObservations.size == 1) {
                                    val firstObs = realObservations.first()
                                    "1 price observation recorded (${com.paypal.visualcart.core.util.CurrencyFormatter.format(firstObs.price, firstObs.currency)}). Revisit stores to build a trend."
                                } else {
                                    "Price trend will appear once at least 2 verified price observations are recorded for this item."
                                },
                                style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // AI Match Reasoning Card
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "AI Match Verification",
                    style = VisualCartTypography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(SageLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = VisualCartIcons.SparkleMatch,
                                    contentDescription = null,
                                    tint = ForestGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            val bestConfPct = ((bestDeal?.confidence ?: 0f) * 100).toInt()
                            Text(
                                text = when {
                                    isDemoProduct && productId == "crucial-p3-plus-1tb" && savedProduct == null ->
                                        "96% Confidence Demo Fixture"
                                    bestDeal != null && bestConfPct > 0 ->
                                        "$bestConfPct% Confidence Saved Match"
                                    else -> "Saved Product Identity"
                                },
                                style = VisualCartTypography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeepInk
                                )
                            )
                        }

                        Text(
                            text = if (isDemoProduct && productId == "crucial-p3-plus-1tb" && savedProduct == null) {
                                "Curated demo item showcasing cross-merchant catalog specs for Crucial P3 Plus 1TB:"
                            } else {
                                "Stored product metadata and store search links tracked for \"$productTitle\":"
                            },
                            style = VisualCartTypography.bodySmall.copy(
                                color = DeepInk,
                                lineHeight = 18.sp
                            )
                        )

                        // Matched attributes checklist
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isDemoProduct && productId == "crucial-p3-plus-1tb" && savedProduct == null) {
                                VerificationItem(label = "Capacity: 1TB (1000GB format confirmed)")
                                VerificationItem(label = "Interface: PCIe Gen4 x4 NVMe 1.4")
                                VerificationItem(label = "Form factor: M.2 2280 single-sided")
                            } else if (productBrand != "Product" && productBrand != "Unverified") {
                                VerificationItem(label = "Brand: $productBrand")
                            }
                            VerificationItem(label = "Product Identity: $productTitle (${productCategory})")
                            VerificationItem(
                                label = if (isDemoProduct) {
                                    "Source: Curated Catalog Demo Fixture"
                                } else {
                                    "Source: Saved Product Record · Open merchant link for live specs"
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Canonical Specifications Table
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Full Specifications",
                    style = VisualCartTypography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        canonicalSpecs.forEachIndexed { index, (key, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = key,
                                    style = VisualCartTypography.bodySmall.copy(color = MutedSlate),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = value,
                                    style = VisualCartTypography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = DeepInk
                                    ),
                                    modifier = Modifier.weight(1.3f)
                                )
                            }
                            if (index < canonicalSpecs.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(StoneLight)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun VerificationItem(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(ForestGreenLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = ForestGreen,
                modifier = Modifier.size(11.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = VisualCartTypography.bodySmall.copy(color = DeepInk)
        )
    }
}

@Composable
private fun PriceHistoryGraph(
    prices: List<Double>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Background horizontal grid lines
        val lineCount = 3
        for (i in 0..lineCount) {
            val y = (height / lineCount) * i
            drawLine(
                color = Stone,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f
            )
        }

        if (prices.size < 2) return@Canvas
        val minVal = prices.minOrNull() ?: return@Canvas
        val maxVal = prices.maxOrNull() ?: return@Canvas
        val range = (maxVal - minVal).takeIf { it > 0.0 } ?: 1.0
        val usableHeight = height * 0.75f
        val topPadding = height * 0.12f

        val points = prices.mapIndexed { idx, value ->
            val x = if (prices.size == 1) width / 2f else (width * idx) / (prices.size - 1)
            val norm = ((value - minVal) / range).toFloat()
            val y = topPadding + usableHeight * (1f - norm)
            Offset(x, y)
        }

        // Draw path
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (p in points) {
                lineTo(p.x, p.y)
            }
        }

        drawPath(
            path = path,
            color = ForestGreen,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw milestone dots
        for ((index, point) in points.withIndex()) {
            val isCurrent = index == points.lastIndex
            drawCircle(
                color = if (isCurrent) ForestGreen else PureWhite,
                radius = if (isCurrent) 6.dp.toPx() else 4.dp.toPx(),
                center = point
            )
            if (!isCurrent) {
                drawCircle(
                    color = ForestGreen,
                    radius = 4.dp.toPx(),
                    center = point,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

private data class MerchantDetailListing(
    val name: String,
    val price: Double?,
    val originalPrice: Double?,
    val currency: String = "INR",
    val hasVerifiedPrice: Boolean = price != null && price > 0.0,
    val listingOrigin: String = if (price != null && price > 0.0) "VERIFIED_CATALOG_FIXTURE" else "DIRECT_SEARCH_URL",
    val status: String,
    val delivery: String,
    val matchType: MatchType,
    val confidence: Float,
    val isLowest: Boolean,
    val url: String,
    val providerProductId: String? = null,
    val offerSource: String? = null
)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun ProductDetailScreenPreview() {
    com.paypal.visualcart.core.designsystem.VisualCartTheme {
        ProductDetailScreen(
            productId = "crucial-p3-plus-1tb",
            onNavigateBack = {}
        )
    }
}

