package com.paypal.visualcart.ui.cart

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import com.paypal.visualcart.data.repository.CartRepositoryImpl
import com.paypal.visualcart.domain.model.MatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CartItemUiModel(
    val id: String,
    val title: String,
    val brand: String,
    val category: String,
    val specs: List<String>,
    val verifiedPrice: Double?,
    val originalPrice: Double?,
    val bestMerchant: String,
    val productUrl: String,
    val matchType: MatchType,
    val confidence: Float,
    val otherMerchants: List<String> = emptyList(),
    val isDemo: Boolean = true,
    val isSavedFromDb: Boolean = false,
    val currency: String = "INR",
    val isBestPriceComparable: Boolean = !isDemo.not() && originalPrice != null,
    val comparableSavings: Double? = null,
    val listingId: String? = null,
    val providerProductId: String? = null,
    val offerSource: String? = null
) {
    val hasVerifiedPrice: Boolean
        get() = verifiedPrice != null && verifiedPrice > 0.0

    val savings: Double?
        get() {
            val vp = verifiedPrice?.takeIf { it > 0.0 } ?: return null
            comparableSavings?.takeIf { it > 0.0 }?.let { return it }
            return if (originalPrice != null && originalPrice > vp) originalPrice - vp else null
        }
}

enum class CartSortOption(val label: String) {
    RECENT("Recently Added"),
    PRICE_LOW_HIGH("Lowest Price"),
    PRICE_HIGH_LOW("Highest Price"),
    BIGGEST_SAVINGS("Biggest Savings"),
    CATEGORY("Category")
}

@Composable
fun CartScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onDiscoverMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cartRepository = remember(context) {
        CartRepositoryImpl(VisualCartDatabase.getInstance(context))
    }
    val dbProducts by cartRepository.getCartProductsFlow().collectAsState(initial = emptyList())

    var selectedFilter by remember { mutableStateOf("All") }
    var selectedSort by remember { mutableStateOf(CartSortOption.RECENT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var isRefreshingLivePrices by remember { mutableStateOf(false) }
    val selectedCheckoutIds = remember { mutableStateMapOf<String, Boolean>() }
    val demoDataEnabled = remember(context) {
        com.paypal.visualcart.core.config.VisualCartSettingsStore.isDemoDataEnabled(context)
    }
    val preferredDiscoveryCurrency = remember(context) {
        com.paypal.visualcart.core.config.VisualCartSettingsStore.getDiscoveryCurrency(context)
    }

    val demoItems = remember {
        mutableStateListOf(
            CartItemUiModel(
                id = "crucial-p3-plus-1tb",
                title = "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
                brand = "Crucial",
                category = "Electronics",
                specs = listOf("1TB", "NVMe", "M.2 2280", "PCIe 4.0"),
                verifiedPrice = 5499.0,
                originalPrice = 6099.0,
                bestMerchant = "Flipkart",
                productUrl = "https://www.flipkart.com",
                matchType = MatchType.EXACT,
                confidence = 0.96f,
                otherMerchants = listOf("Amazon", "Croma"),
                isDemo = true
            ),
            CartItemUiModel(
                id = "sony-wh1000xm5",
                title = "Sony WH-1000XM5 Noise Cancelling Wireless Headphones",
                brand = "Sony",
                category = "Electronics",
                specs = listOf("ANC", "30hr Battery", "Multipoint Bluetooth"),
                verifiedPrice = 26990.0,
                originalPrice = 29990.0,
                bestMerchant = "Amazon",
                productUrl = "https://www.amazon.in",
                matchType = MatchType.EXACT,
                confidence = 0.98f,
                otherMerchants = listOf("Croma", "Reliance"),
                isDemo = true
            ),
            CartItemUiModel(
                id = "apple-watch-ultra-2",
                title = "Apple Watch Ultra 2 GPS + Cellular 49mm Titanium",
                brand = "Apple",
                category = "Accessories",
                specs = listOf("49mm", "Titanium Case", "Cellular"),
                verifiedPrice = 79900.0,
                originalPrice = 89900.0,
                bestMerchant = "Croma",
                productUrl = "https://www.croma.com",
                matchType = MatchType.EXACT,
                confidence = 0.95f,
                otherMerchants = listOf("Amazon", "Flipkart"),
                isDemo = true
            ),
            CartItemUiModel(
                id = "nike-air-jordan-1",
                title = "Nike Air Jordan 1 Low Retro Neutral Grey",
                brand = "Nike",
                category = "Fashion",
                specs = listOf("US 10", "Leather", "Low Cut"),
                verifiedPrice = 8995.0,
                originalPrice = 10295.0,
                bestMerchant = "Myntra",
                productUrl = "https://www.myntra.com",
                matchType = MatchType.LIKELY,
                confidence = 0.89f,
                otherMerchants = listOf("Flipkart"),
                isDemo = true
            ),
            CartItemUiModel(
                id = "keychron-k2-v2",
                title = "Keychron K2 V2 Wireless Mechanical Keyboard Hot-Swappable",
                brand = "Keychron",
                category = "Gaming",
                specs = listOf("Gateron Brown", "Wireless", "75% Layout"),
                verifiedPrice = 7499.0,
                originalPrice = 8299.0,
                bestMerchant = "Amazon",
                productUrl = "https://www.amazon.in",
                matchType = MatchType.LIKELY,
                confidence = 0.88f,
                otherMerchants = listOf("Flipkart"),
                isDemo = true
            )
        )
    }

    val savedCartItems = remember(dbProducts) {
        dbProducts.map { saved ->
            val pricedListings = saved.listings.filter { it.hasVerifiedPrice }
            val selected = saved.selectedListing?.takeIf { it.hasVerifiedPrice }
                ?: saved.lowestPriceListing
                ?: saved.listings.firstOrNull()
            val price = selected?.takeIf { it.hasVerifiedPrice }?.price
            val currency = selected?.currency ?: "INR"
            val sameCurrencyPriced = pricedListings.filter { it.currency.equals(currency, ignoreCase = true) }
            val distinctMerchants = sameCurrencyPriced.map {
                com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(it.merchant)
            }.distinct()
            val lowestPriced = sameCurrencyPriced.minByOrNull { it.price ?: Double.MAX_VALUE }
            val highestPriced = sameCurrencyPriced.maxByOrNull { it.price ?: 0.0 }
            val multiMerchantSavings = if (distinctMerchants.size >= 2 && lowestPriced?.price != null && highestPriced?.price != null) {
                (highestPriced.price - lowestPriced.price).takeIf { it > 0.0 }
            } else {
                null
            }
            val matchEnum = runCatching {
                MatchType.valueOf(selected?.matchType ?: "EXACT")
            }.getOrDefault(MatchType.EXACT)
            val otherNames = saved.listings
                .filter { it.id != selected?.id }
                .map { it.merchant }
                .distinct()
            val specsSummary = buildList {
                saved.product.brand?.takeIf { it.isNotBlank() }?.let { add(it) }
                add("${saved.listings.size.coerceAtLeast(1)} stores")
            }
            val verifiedOrig = selected?.originalPrice?.takeIf { price != null && it > price }
            CartItemUiModel(
                id = saved.product.id,
                title = saved.product.title,
                brand = saved.product.brand ?: "Product",
                category = saved.product.category,
                specs = specsSummary,
                verifiedPrice = price,
                originalPrice = verifiedOrig,
                bestMerchant = selected?.merchant ?: saved.product.originalSource ?: "Merchant",
                productUrl = selected?.productUrl ?: "https://www.amazon.in",
                matchType = matchEnum,
                confidence = selected?.confidence ?: 0.85f,
                otherMerchants = otherNames,
                isDemo = selected?.sourceType == "DEMO" && (selected?.hasVerifiedPrice == true),
                isSavedFromDb = true,
                currency = currency,
                isBestPriceComparable = distinctMerchants.size >= 2,
                comparableSavings = multiMerchantSavings,
                listingId = selected?.id,
                providerProductId = selected?.providerProductId,
                offerSource = selected?.offerSource
            )
        }
    }

    LaunchedEffect(savedCartItems) {
        savedCartItems.forEach { item ->
            if (item.hasVerifiedPrice && !selectedCheckoutIds.containsKey(item.id)) {
                selectedCheckoutIds[item.id] = true
            }
        }
        val validIds = savedCartItems.map { it.id }.toSet() + demoItems.map { it.id }.toSet()
        selectedCheckoutIds.keys.toList().forEach { id ->
            if (id !in validIds) selectedCheckoutIds.remove(id)
        }
    }

    val cartItems = remember(savedCartItems, demoItems.toList(), demoDataEnabled) {
        if (demoDataEnabled) savedCartItems + demoItems else savedCartItems
    }

    val filterOptions = listOf("All", "Saved", "Amazon", "Flipkart", "Croma", "Electronics", "Fashion")

    // Filter and Sort logic
    val filteredItems = cartItems.filter { item ->
        when (selectedFilter) {
            "All" -> true
            "Saved" -> item.isSavedFromDb
            "Amazon" -> item.bestMerchant.contains("Amazon", ignoreCase = true) || item.otherMerchants.any { it.contains("Amazon", ignoreCase = true) }
            "Flipkart" -> item.bestMerchant.contains("Flipkart", ignoreCase = true) || item.otherMerchants.any { it.contains("Flipkart", ignoreCase = true) }
            "Croma" -> item.bestMerchant.contains("Croma", ignoreCase = true) || item.otherMerchants.any { it.contains("Croma", ignoreCase = true) }
            "Electronics" -> item.category.equals("Electronics", ignoreCase = true)
            "Fashion" -> item.category.equals("Fashion", ignoreCase = true)
            else -> true
        }
    }.sortedWith { a, b ->
        when (selectedSort) {
            CartSortOption.RECENT -> 0
            CartSortOption.PRICE_LOW_HIGH -> (a.verifiedPrice ?: Double.MAX_VALUE).compareTo(b.verifiedPrice ?: Double.MAX_VALUE)
            CartSortOption.PRICE_HIGH_LOW -> (b.verifiedPrice ?: -1.0).compareTo(a.verifiedPrice ?: -1.0)
            CartSortOption.BIGGEST_SAVINGS -> (b.savings ?: 0.0).compareTo(a.savings ?: 0.0)
            CartSortOption.CATEGORY -> a.category.compareTo(b.category)
        }
    }

    val valueByCurrency = cartItems
        .filter { it.hasVerifiedPrice && it.verifiedPrice != null }
        .groupBy { it.currency.uppercase() }
        .mapValues { (_, list) -> list.sumOf { it.verifiedPrice!! } }
    val savingsByCurrency = cartItems
        .filter { (it.savings ?: 0.0) > 0.0 }
        .groupBy { it.currency.uppercase() }
        .mapValues { (_, list) -> list.sumOf { it.savings ?: 0.0 } }
    val formattedCartValue = if (valueByCurrency.isEmpty()) {
        com.paypal.visualcart.core.util.CurrencyFormatter.format(0.0, preferredDiscoveryCurrency)
    } else {
        valueByCurrency.entries.sortedBy { it.key }.joinToString(" + ") { (curr, total) ->
            com.paypal.visualcart.core.util.CurrencyFormatter.format(total, curr)
        }
    }
    val formattedTotalSavings = if (savingsByCurrency.isEmpty()) {
        "—"
    } else {
        savingsByCurrency.entries.sortedBy { it.key }.joinToString(" + ") { (curr, total) ->
            com.paypal.visualcart.core.util.CurrencyFormatter.format(total, curr)
        }
    }
    val selectedCheckoutItems = cartItems.filter { item ->
        item.hasVerifiedPrice && selectedCheckoutIds[item.id] == true
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
        ) {
            // Header Bar
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
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Universal Cart",
                            style = VisualCartTypography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepInk
                            )
                        )
                        val distinctMerchantsCount = cartItems.map { it.bestMerchant }.distinct().size.coerceAtLeast(1)
                        Text(
                            text = "${cartItems.size} items across $distinctMerchantsCount store(s)",
                            style = VisualCartTypography.labelSmall.copy(color = MutedSlate)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (savedCartItems.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isRefreshingLivePrices) StoneLight else ForestGreenLight,
                            modifier = Modifier.clickable(enabled = !isRefreshingLivePrices) {
                                isRefreshingLivePrices = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val c3Client = com.paypal.visualcart.feature.merchant.Channel3DiscoveryClient(context)
                                    var refreshedCount = 0
                                    for (saved in dbProducts) {
                                        val providerId = saved.listings.firstOrNull {
                                            !it.providerProductId.isNullOrBlank()
                                        }?.providerProductId
                                        val resp = if (!providerId.isNullOrBlank()) {
                                            c3Client.refreshProduct(providerId)
                                        } else {
                                            c3Client.searchProducts(saved.product.title)
                                        }
                                        if (resp.ok && resp.listings.isNotEmpty()) {
                                            val selKey = saved.selectedListing?.let {
                                                com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(it.merchant)
                                            }
                                            val newLive = resp.listings.mapIndexed { idx, l ->
                                                val matchSel = selKey != null &&
                                                    com.paypal.visualcart.feature.merchant.SearchQueryBuilder.normalizeMerchantKey(l.merchantName) == selKey
                                                com.paypal.visualcart.data.local.entity.MerchantListingEntity(
                                                    savedProductId = saved.product.id,
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
                                                    isSelectedForPurchase = matchSel || (selKey == null && idx == 0)
                                                )
                                            }
                                            val existingSearchLinks = saved.listings.filter {
                                                it.listingOrigin == "DIRECT_SEARCH_URL"
                                            }.map { it.copy(isSelectedForPurchase = false) }
                                            cartRepository.replaceProductListings(
                                                saved.product.id,
                                                newLive + existingSearchLinks
                                            )
                                            refreshedCount++
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        isRefreshingLivePrices = false
                                        android.widget.Toast.makeText(
                                            context,
                                            if (refreshedCount > 0) "Updated live prices for $refreshedCount item(s)"
                                            else "Prices already up to date",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = if (isRefreshingLivePrices) "Updating…" else "Refresh Prices",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = VisualCartTypography.labelSmall.copy(
                                    color = ForestGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    if (savedCartItems.any { !it.isDemo && it.hasVerifiedPrice }) {
                        com.paypal.visualcart.core.designsystem.LiveInferenceBadge(text = "LIVE CART")
                    } else if (demoDataEnabled && savedCartItems.isEmpty()) {
                        DemoDataBadge()
                    }
                }
            }

            // Summary Metric Pill
            if (cartItems.isNotEmpty()) {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PureWhite,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "TOTAL TRACKED VALUE",
                                    style = VisualCartTypography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        letterSpacing = 0.8.sp,
                                        color = MutedSlate
                                    )
                                )
                                Text(
                                    text = formattedCartValue,
                                    style = VisualCartTypography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepInk
                                    )
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .height(30.dp)
                                    .width(1.dp)
                                    .background(Stone)
                            )

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "TOTAL POTENTIAL SAVINGS",
                                    style = VisualCartTypography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        letterSpacing = 0.8.sp,
                                        color = Terracotta
                                    )
                                )
                                Text(
                                    text = formattedTotalSavings,
                                    style = VisualCartTypography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Terracotta
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Filter Chips Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.width(12.dp))
                filterOptions.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) ForestGreen else PureWhite,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) ForestGreen else Stone
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedFilter = filter }
                    ) {
                        Text(
                            text = filter,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) PureWhite else DeepInk
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Sort Selector Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Showing ${filteredItems.size} items",
                    style = VisualCartTypography.labelSmall.copy(color = MutedSlate)
                )

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showSortMenu = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sort: ${selectedSort.label}",
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = DeepInk
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = DeepInk,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        CartSortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        style = VisualCartTypography.bodySmall.copy(
                                            fontWeight = if (selectedSort == option) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedSort == option) ForestGreen else DeepInk
                                        )
                                    )
                                },
                                onClick = {
                                    selectedSort = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main List / Empty State
            if (filteredItems.isEmpty()) {
                CartEmptyState(
                    onDiscover = onDiscoverMore,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        CartItemCard(
                            item = item,
                            isSelectedForCheckout = item.hasVerifiedPrice && (selectedCheckoutIds[item.id] == true),
                            onToggleCheckout = { checked ->
                                if (item.hasVerifiedPrice) {
                                    selectedCheckoutIds[item.id] = checked
                                }
                            },
                            onViewListing = {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.productUrl))
                                context.startActivity(browserIntent)
                            },
                            onCompare = { onNavigateToDetail(item.id) },
                            onRemove = {
                                selectedCheckoutIds.remove(item.id)
                                if (item.isSavedFromDb) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        cartRepository.removeProduct(item.id)
                                    }
                                } else {
                                    demoItems.remove(item)
                                }
                            }
                        )
                    }
                }
            }

            if (selectedCheckoutItems.isNotEmpty()) {
                val selByCurr = selectedCheckoutItems
                    .groupBy { it.currency.uppercase() }
                    .mapValues { (_, list) -> list.sumOf { it.verifiedPrice ?: 0.0 } }
                val checkoutTotalLabel = selByCurr.entries.sortedBy { it.key }.joinToString(" + ") { (c, amt) ->
                    com.paypal.visualcart.core.util.CurrencyFormatter.format(amt, c)
                }
                val merchantCount = selectedCheckoutItems.map { it.bestMerchant }.distinct().size
                val hasInrConversionNeeded = selByCurr.keys.any { it == "INR" } && selByCurr.keys.size > 1
                Surface(
                    color = PureWhite,
                    shadowElevation = 12.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Stone),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "${selectedCheckoutItems.size} SELECTED · $merchantCount STORE(S)",
                                    style = VisualCartTypography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = ForestGreen,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.6.sp
                                    )
                                )
                                Text(
                                    text = checkoutTotalLabel,
                                    style = VisualCartTypography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepInk
                                    )
                                )
                                if (hasInrConversionNeeded) {
                                    Text(
                                        text = "INR items convert to USD at checkout",
                                        style = VisualCartTypography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            color = MutedSlate
                                        )
                                    )
                                }
                            }
                            VisualCartButton(
                                text = "Pay Selected with PayPal",
                                onClick = {
                                    val orderItems = selectedCheckoutItems.map { item ->
                                        com.paypal.visualcart.feature.payment.PayPalBackendClient.CartOrderRequestItem(
                                            title = item.title,
                                            unitPrice = item.verifiedPrice ?: 0.0,
                                            quantity = 1,
                                            currency = item.currency,
                                            merchantName = item.bestMerchant,
                                            listingId = item.listingId,
                                            productId = item.providerProductId,
                                            productUrl = item.productUrl,
                                            offerSource = item.offerSource
                                        )
                                    }
                                    com.paypal.visualcart.feature.payment.PayPalCheckoutCoordinator.instance.startCartCheckout(
                                        context = context,
                                        items = orderItems
                                    )
                                },
                                variant = ButtonVariant.PRIMARY,
                                icon = VisualCartIcons.ShieldCheck
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartItemCard(
    item: CartItemUiModel,
    isSelectedForCheckout: Boolean,
    onToggleCheckout: (Boolean) -> Unit,
    onViewListing: () -> Unit,
    onCompare: () -> Unit,
    onRemove: () -> Unit
) {
    VisualCartCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = PureWhite,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Category, Demo Badge, Remove
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.hasVerifiedPrice) {
                        androidx.compose.material3.Checkbox(
                            checked = isSelectedForCheckout,
                            onCheckedChange = onToggleCheckout,
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = ForestGreen,
                                uncheckedColor = MutedSlate
                            ),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.category.uppercase(),
                        style = VisualCartTypography.labelSmall.copy(
                            color = MutedSlate,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    when {
                        item.isDemo -> DemoDataBadge()
                        item.offerSource == "CHANNEL3_API" -> com.paypal.visualcart.core.designsystem.LiveInferenceBadge(text = "LIVE OFFER")
                        item.offerSource == "PRODUCT_PAGE_JSONLD" -> com.paypal.visualcart.core.designsystem.LiveInferenceBadge(text = "PAGE PRICE")
                        !item.hasVerifiedPrice -> com.paypal.visualcart.core.designsystem.SpecTag(spec = "SEARCH LINK")
                    }
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MutedSlate.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title & Brand
            Text(
                text = item.title,
                style = VisualCartTypography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp
                ),
                color = DeepInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Specs row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                item.specs.forEach { spec ->
                    SpecTag(spec = spec)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Price & Savings Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        val verifiedPrice = item.verifiedPrice
                        if (item.hasVerifiedPrice && verifiedPrice != null) {
                            Text(
                                text = com.paypal.visualcart.core.util.CurrencyFormatter.format(verifiedPrice, item.currency),
                                style = VisualCartTypography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeepInk
                                )
                            )
                            if (item.originalPrice != null && item.originalPrice > verifiedPrice) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = com.paypal.visualcart.core.util.CurrencyFormatter.format(item.originalPrice, item.currency),
                                    style = VisualCartTypography.bodySmall.copy(
                                        textDecoration = TextDecoration.LineThrough,
                                        color = MutedSlate
                                    )
                                )
                            }
                        } else {
                            Text(
                                text = "Check store price",
                                style = VisualCartTypography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MutedSlate
                                )
                            )
                        }
                    }
                    Text(
                        text = when {
                            item.hasVerifiedPrice && item.isBestPriceComparable -> "Verified best price at ${item.bestMerchant}"
                            item.hasVerifiedPrice -> "Listed at ${item.bestMerchant}"
                            else -> "Live search link on ${item.bestMerchant}"
                        },
                        style = VisualCartTypography.labelSmall.copy(
                            fontSize = 10.sp,
                            color = if (item.hasVerifiedPrice) ForestGreen else MutedSlate
                        )
                    )
                }

                if (item.savings != null && item.savings!! > 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = TerracottaLight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Terracotta.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "Save ${com.paypal.visualcart.core.util.CurrencyFormatter.format(item.savings!!, item.currency)}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Terracotta
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Merchant Badges & Confidence Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MerchantLogoBadge(merchantName = item.bestMerchant)
                    item.otherMerchants.forEach { other ->
                        MerchantLogoBadge(merchantName = other, compact = true)
                    }
                }

                ConfidenceIndicator(
                    matchType = item.matchType,
                    confidence = item.confidence
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row: View Listing, Compare Alternatives
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VisualCartButton(
                    text = if (item.hasVerifiedPrice) "Buy at Store" else "View Listing",
                    onClick = onViewListing,
                    variant = ButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                )

                VisualCartButton(
                    text = "Compare Stores",
                    onClick = onCompare,
                    variant = ButtonVariant.PRIMARY,
                    icon = VisualCartIcons.CompareArrows,
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun CartEmptyState(
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(PureWhite)
                .border(1.dp, Stone, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = VisualCartIcons.Cart,
                contentDescription = null,
                tint = MutedSage,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Your Universal Cart is Empty",
            style = VisualCartTypography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = DeepInk
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Whenever you're browsing Amazon, Flipkart, or any shopping app, tap the Visual Cart Quick Settings tile or floating bubble to save products here instantly.",
            style = VisualCartTypography.bodyMedium.copy(
                color = MutedSlate,
                lineHeight = 22.sp
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        VisualCartButton(
            text = "Discover products anywhere",
            onClick = onDiscover,
            variant = ButtonVariant.PRIMARY
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun CartScreenPreview() {
    com.paypal.visualcart.core.designsystem.VisualCartTheme {
        CartScreen(
            onNavigateBack = {},
            onNavigateToDetail = {},
            onDiscoverMore = {}
        )
    }
}

