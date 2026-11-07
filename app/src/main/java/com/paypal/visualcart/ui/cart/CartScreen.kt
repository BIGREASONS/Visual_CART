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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.paypal.visualcart.domain.model.MatchType

data class CartItemUiModel(
    val id: String,
    val title: String,
    val brand: String,
    val category: String,
    val specs: List<String>,
    val verifiedPrice: Double,
    val originalPrice: Double?,
    val bestMerchant: String,
    val productUrl: String,
    val matchType: MatchType,
    val confidence: Float,
    val otherMerchants: List<String> = emptyList(),
    val isDemo: Boolean = true
) {
    val savings: Double?
        get() = if (originalPrice != null && originalPrice > verifiedPrice) originalPrice - verifiedPrice else null
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
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedSort by remember { mutableStateOf(CartSortOption.RECENT) }
    var showSortMenu by remember { mutableStateOf(false) }

    val cartItems = remember {
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

    val filterOptions = listOf("All", "Amazon", "Flipkart", "Croma", "Electronics", "Fashion")

    // Filter and Sort logic
    val filteredItems = cartItems.filter { item ->
        when (selectedFilter) {
            "All" -> true
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
            CartSortOption.PRICE_LOW_HIGH -> a.verifiedPrice.compareTo(b.verifiedPrice)
            CartSortOption.PRICE_HIGH_LOW -> b.verifiedPrice.compareTo(a.verifiedPrice)
            CartSortOption.BIGGEST_SAVINGS -> (b.savings ?: 0.0).compareTo(a.savings ?: 0.0)
            CartSortOption.CATEGORY -> a.category.compareTo(b.category)
        }
    }

    val totalSavings = cartItems.sumOf { it.savings ?: 0.0 }
    val totalCartValue = cartItems.sumOf { it.verifiedPrice }

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
                        Text(
                            text = "${cartItems.size} items across 4 merchants",
                            style = VisualCartTypography.labelSmall.copy(color = MutedSlate)
                        )
                    }
                }

                DemoDataBadge()
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
                                    text = "₹${totalCartValue.toInt()}",
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
                                    text = "₹${totalSavings.toInt()}",
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
                            onViewListing = {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.productUrl))
                                context.startActivity(browserIntent)
                            },
                            onCompare = { onNavigateToDetail(item.id) },
                            onRemove = { cartItems.remove(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CartItemCard(
    item: CartItemUiModel,
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
                    Text(
                        text = item.category.uppercase(),
                        style = VisualCartTypography.labelSmall.copy(
                            color = MutedSlate,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (item.isDemo) {
                        DemoDataBadge()
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
                        Text(
                            text = "₹${item.verifiedPrice.toInt()}",
                            style = VisualCartTypography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepInk
                            )
                        )
                        if (item.originalPrice != null && item.originalPrice > item.verifiedPrice) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "₹${item.originalPrice.toInt()}",
                                style = VisualCartTypography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                    color = MutedSlate
                                )
                            )
                        }
                    }
                    Text(
                        text = "Verified best price at ${item.bestMerchant}",
                        style = VisualCartTypography.labelSmall.copy(
                            fontSize = 10.sp,
                            color = ForestGreen
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
                            text = "Save ₹${item.savings!!.toInt()}",
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
                    text = "View Listing",
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

