package com.paypal.visualcart.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paypal.visualcart.core.designsystem.ButtonVariant
import com.paypal.visualcart.core.designsystem.ConfidenceIndicator
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.DeepInkSurface
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
import com.paypal.visualcart.core.designsystem.VisualCartLogo
import com.paypal.visualcart.core.designsystem.VisualCartTypography
import com.paypal.visualcart.core.designsystem.WarmIvory
import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import com.paypal.visualcart.service.overlay.VisualCartOverlayService

/**
 * Editorial data model for Home carousel and price observations.
 */
data class SavedProductUiModel(
    val id: String,
    val title: String,
    val brand: String,
    val category: String,
    val specs: List<String>,
    val bestPrice: Double,
    val originalPrice: Double?,
    val storeCount: Int,
    val matchType: MatchType,
    val confidence: Float,
    val priceDropPercent: Int? = null,
    val isDemo: Boolean = true,
    val currency: String = "INR"
)

@Composable
fun HomeScreen(
    onNavigateToCart: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onProductDetected: () -> Unit,
    onDemoScan: (String) -> Unit = { _ -> onProductDetected() },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAddCaptureDialog by remember { mutableStateOf(false) }
    var floatingBubbleEnabled by remember { mutableStateOf(VisualCartOverlayService.isRunning) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                floatingBubbleEnabled = VisualCartOverlayService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }

    val recentItems = remember {
        listOf(
            SavedProductUiModel(
                id = "crucial-p3-plus-1tb",
                title = "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
                brand = "Crucial",
                category = "Electronics",
                specs = listOf("1TB", "NVMe", "M.2 2280", "5000 MB/s"),
                bestPrice = 5499.0,
                originalPrice = 6099.0,
                storeCount = 3,
                matchType = MatchType.EXACT,
                confidence = 0.96f,
                priceDropPercent = 10,
                isDemo = true
            ),
            SavedProductUiModel(
                id = "sony-wh1000xm5",
                title = "Sony WH-1000XM5 Noise Cancelling Wireless Headphones",
                brand = "Sony",
                category = "Electronics",
                specs = listOf("ANC", "30hr Battery", "Multipoint"),
                bestPrice = 26990.0,
                originalPrice = 29990.0,
                storeCount = 4,
                matchType = MatchType.EXACT,
                confidence = 0.98f,
                priceDropPercent = 10,
                isDemo = true
            ),
            SavedProductUiModel(
                id = "apple-watch-ultra-2",
                title = "Apple Watch Ultra 2 GPS + Cellular 49mm Titanium",
                brand = "Apple",
                category = "Accessories",
                specs = listOf("49mm", "Titanium", "Cellular"),
                bestPrice = 79900.0,
                originalPrice = 89900.0,
                storeCount = 3,
                matchType = MatchType.EXACT,
                confidence = 0.95f,
                priceDropPercent = 11,
                isDemo = true
            ),
            SavedProductUiModel(
                id = "keychron-k2-v2",
                title = "Keychron K2 V2 Wireless Mechanical Keyboard Hot-Swappable",
                brand = "Keychron",
                category = "Gaming",
                specs = listOf("Gateron Brown", "RGB", "75%"),
                bestPrice = 7499.0,
                originalPrice = 8299.0,
                storeCount = 2,
                matchType = MatchType.LIKELY,
                confidence = 0.88f,
                priceDropPercent = 9,
                isDemo = true
            )
        )
    }

    val priceDropAlerts = remember {
        listOf(
            "Crucial P3 Plus 1TB dropped ₹600 on Flipkart" to "₹5,499 vs ₹6,099",
            "Sony WH-1000XM5 ₹3,000 cheaper on Amazon" to "₹26,990 vs ₹29,990"
        )
    }

    val categories = remember {
        listOf(
            "Electronics" to "14 items",
            "Fashion" to "8 items",
            "Gaming" to "5 items",
            "Home" to "6 items",
            "Accessories" to "9 items"
        )
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
            // Editorial Header Bar with Official Brand Logo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VisualCartLogo(iconSize = 30.dp, textSize = 22.sp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(PureWhite)
                    ) {
                        Icon(
                            imageVector = VisualCartIcons.QuickSettingsTile,
                            contentDescription = "Settings",
                            tint = DeepInk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onNavigateToCart,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(PureWhite)
                    ) {
                        Icon(
                            imageVector = VisualCartIcons.Cart,
                            contentDescription = "Cart",
                            tint = DeepInk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Welcome Subtitle
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Good morning.",
                    style = VisualCartTypography.headlineSmall.copy(
                        color = MutedSlate,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Your next great find\nstarts anywhere.",
                    style = VisualCartTypography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk,
                        lineHeight = 30.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Prominent "Add something you found" Hero Capture Box
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(ForestGreenLight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = VisualCartIcons.ScanCamera,
                                        contentDescription = null,
                                        tint = ForestGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "SCAN & COMPARE",
                                    style = VisualCartTypography.labelSmall.copy(
                                        color = ForestGreen,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }
                            DemoDataBadge()
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Found a product you want to track?",
                            style = VisualCartTypography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepInk
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Save screenshots, camera snaps, or product links. Visual Cart discovers matching listings and alerts you to price drops.",
                            style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        VisualCartButton(
                            text = "Add something you found",
                            onClick = { showAddCaptureDialog = true },
                            variant = ButtonVariant.PRIMARY,
                            icon = Icons.Default.Add,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quick launch toggle for Floating Cart Bubble
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = if (floatingBubbleEnabled) SageLight else PureWhite
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (floatingBubbleEnabled) ForestGreen else StoneLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = VisualCartIcons.FloatingBubble,
                                    contentDescription = null,
                                    tint = if (floatingBubbleEnabled) PureWhite else DeepInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Floating Cart Bubble",
                                    style = VisualCartTypography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepInk
                                    )
                                )
                                Text(
                                    text = if (floatingBubbleEnabled) "Active on screen · Tap any time" else "Fast one-tap scan in shopping apps",
                                    style = VisualCartTypography.bodySmall.copy(
                                        color = if (floatingBubbleEnabled) ForestGreen else MutedSlate,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }

                        Switch(
                            checked = floatingBubbleEnabled,
                            onCheckedChange = { isChecked ->
                                if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } else {
                                    floatingBubbleEnabled = isChecked
                                    if (isChecked) {
                                        VisualCartOverlayService.start(context)
                                    } else {
                                        VisualCartOverlayService.stop(context)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = ForestGreen,
                                uncheckedThumbColor = MutedSlate,
                                uncheckedTrackColor = StoneLight
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Price Observation / Drop Alerts Section
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Terracotta)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PRICE DROP ALERTS",
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Terracotta,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                    Text(
                        text = "Updated 12m ago",
                        style = VisualCartTypography.labelSmall.copy(color = MutedSlate)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    priceDropAlerts.forEach { (alert, priceDiff) ->
                        VisualCartCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = PureWhite,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(TerracottaLight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = VisualCartIcons.PriceDropArrow,
                                            contentDescription = null,
                                            tint = Terracotta,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = alert,
                                            style = VisualCartTypography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = DeepInk
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = priceDiff,
                                            style = VisualCartTypography.labelSmall.copy(
                                                color = Terracotta,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    tint = MutedSlate,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Horizontal Carousel: "Recently Saved" items
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Recently Saved",
                        style = VisualCartTypography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        )
                    )
                    Text(
                        text = "See all (${recentItems.size})",
                        style = VisualCartTypography.labelMedium.copy(
                            color = ForestGreen,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.clickable { onNavigateToCart() }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(recentItems, key = { it.id }) { item ->
                        RecentSavedProductCard(
                            item = item,
                            onClick = { onNavigateToDetail(item.id) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Category Grid
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = "Explore by Category",
                    style = VisualCartTypography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    categories.take(3).forEach { (category, count) ->
                        CategoryCard(
                            name = category,
                            itemCount = count,
                            isSelected = selectedCategoryFilter == category,
                            onClick = {
                                selectedCategoryFilter = if (selectedCategoryFilter == category) null else category
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    categories.drop(3).forEach { (category, count) ->
                        CategoryCard(
                            name = category,
                            itemCount = count,
                            isSelected = selectedCategoryFilter == category,
                            onClick = {
                                selectedCategoryFilter = if (selectedCategoryFilter == category) null else category
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // Add Product / Capture Options Dialog
        if (showAddCaptureDialog) {
            AlertDialog(
                onDismissRequest = { showAddCaptureDialog = false },
                title = {
                    Text(
                        text = "Add Something You Found",
                        style = VisualCartTypography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Simulate scanning a real-world deal or product listing:",
                            style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                        )
                        VisualCartButton(
                            text = "Scan Crucial P3 Plus SSD (Demo)",
                            onClick = {
                                showAddCaptureDialog = false
                                onDemoScan("crucial")
                            },
                            variant = ButtonVariant.PRIMARY,
                            icon = VisualCartIcons.ScanCamera,
                            modifier = Modifier.fillMaxWidth()
                        )
                        VisualCartButton(
                            text = "Scan Sony WH-1000XM5 (Demo)",
                            onClick = {
                                showAddCaptureDialog = false
                                onDemoScan("sony")
                            },
                            variant = ButtonVariant.SECONDARY,
                            icon = VisualCartIcons.ScanCamera,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    VisualCartButton(
                        text = "Close",
                        onClick = { showAddCaptureDialog = false },
                        variant = ButtonVariant.GHOST
                    )
                },
                containerColor = PureWhite,
                shape = RoundedCornerShape(18.dp)
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    com.paypal.visualcart.core.designsystem.VisualCartTheme {
        HomeScreen(
            onNavigateToCart = {},
            onNavigateToDetail = {},
            onNavigateToSettings = {},
            onProductDetected = {}
        )
    }
}

@Composable
private fun RecentSavedProductCard(
    item: SavedProductUiModel,
    onClick: () -> Unit
) {
    VisualCartCard(
        modifier = Modifier
            .width(260.dp)
            .clickable(onClick = onClick),
        backgroundColor = PureWhite,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Category and Demo Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.category.uppercase(),
                    style = VisualCartTypography.labelSmall.copy(
                        color = MutedSlate,
                        letterSpacing = 0.8.sp
                    )
                )
                if (item.isDemo) {
                    DemoDataBadge()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = item.title,
                style = VisualCartTypography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = DeepInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Specs row
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item.specs.take(3).forEach { spec ->
                    SpecTag(spec = spec)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Price Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (item.isDemo) "Demo Sample Price" else "Best Price",
                        style = VisualCartTypography.labelSmall.copy(
                            fontSize = 10.sp,
                            color = MutedSlate
                        )
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = com.paypal.visualcart.core.util.CurrencyFormatter.format(item.bestPrice, item.currency),
                            style = VisualCartTypography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepInk
                            )
                        )
                        if (item.originalPrice != null && item.originalPrice > item.bestPrice) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = com.paypal.visualcart.core.util.CurrencyFormatter.format(item.originalPrice, item.currency),
                                style = VisualCartTypography.bodySmall.copy(
                                    textDecoration = TextDecoration.LineThrough,
                                    color = MutedSlate
                                )
                            )
                        }
                    }
                }

                if (item.priceDropPercent != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = TerracottaLight
                    ) {
                        Text(
                            text = "-${item.priceDropPercent}%",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Terracotta
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Store Count & Confidence
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ConfidenceIndicator(
                    matchType = item.matchType,
                    confidence = item.confidence
                )
                Text(
                    text = "Found in ${item.storeCount} stores",
                    style = VisualCartTypography.labelSmall.copy(
                        fontSize = 10.sp,
                        color = MutedSlate
                    )
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    name: String,
    itemCount: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    VisualCartCard(
        modifier = modifier.clickable(onClick = onClick),
        backgroundColor = if (isSelected) ForestGreen else PureWhite,
        borderColor = if (isSelected) ForestGreen else Stone,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column {
            Text(
                text = name,
                style = VisualCartTypography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) PureWhite else DeepInk
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = itemCount,
                style = VisualCartTypography.labelSmall.copy(
                    color = if (isSelected) PureWhite.copy(alpha = 0.8f) else MutedSlate
                )
            )
        }
    }
}

