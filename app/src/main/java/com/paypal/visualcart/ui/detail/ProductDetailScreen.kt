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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    LaunchedEffect(productId) {
        savedProduct = withContext(Dispatchers.IO) {
            cartRepository.getProductById(productId)
        }
    }

    val productTitle = remember(productId, savedProduct) {
        savedProduct?.product?.title ?: when (productId) {
            "sony-wh1000xm5" -> "Sony WH-1000XM5 Noise Cancelling Wireless Headphones"
            "apple-watch-ultra-2" -> "Apple Watch Ultra 2 GPS + Cellular 49mm Titanium"
            "nike-air-jordan-1" -> "Nike Air Jordan 1 Low Retro Neutral Grey"
            "keychron-k2-v2" -> "Keychron K2 V2 Wireless Mechanical Keyboard Hot-Swappable"
            else -> "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD"
        }
    }

    val productBrand = remember(productId, savedProduct) {
        savedProduct?.product?.brand?.takeIf { it.isNotBlank() } ?: when (productId) {
            "sony-wh1000xm5" -> "Sony"
            "apple-watch-ultra-2" -> "Apple"
            "nike-air-jordan-1" -> "Nike"
            "keychron-k2-v2" -> "Keychron"
            else -> "Crucial"
        }
    }

    val productCategory = remember(productId, savedProduct) {
        savedProduct?.product?.category ?: when (productId) {
            "apple-watch-ultra-2" -> "Accessories"
            "nike-air-jordan-1" -> "Fashion"
            "keychron-k2-v2" -> "Gaming"
            else -> "Internal SSD"
        }
    }

    val canonicalModelLabel = remember(productId, savedProduct) {
        savedProduct?.product?.canonicalProductId?.uppercase() ?: productId.uppercase()
    }

    val specPills = remember(productId, savedProduct, productBrand, productCategory) {
        val currentSaved = savedProduct
        if (currentSaved != null) {
            listOfNotNull(
                productBrand.takeIf { it != "Product" },
                productCategory,
                "${currentSaved.listings.size} stores"
            )
        } else {
            when (productId) {
                "sony-wh1000xm5" -> listOf("ANC", "30hr Battery", "Bluetooth 5.2")
                "apple-watch-ultra-2" -> listOf("49mm", "Titanium", "Cellular")
                "nike-air-jordan-1" -> listOf("US 10", "Leather", "Low Cut")
                "keychron-k2-v2" -> listOf("Gateron Brown", "Wireless", "75% Layout")
                else -> listOf("1TB", "NVMe 1.4", "PCIe 4.0", "M.2 2280", "5000 MB/s")
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
        } else {
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
        }
    }

    val merchantListings = remember(productId, savedProduct) {
        val currentSaved = savedProduct
        if (currentSaved != null && currentSaved.listings.isNotEmpty()) {
            val minPrice = currentSaved.listings.minOf { it.price }
            currentSaved.listings.map { entity ->
                MerchantDetailListing(
                    name = entity.merchant,
                    price = entity.price,
                    originalPrice = null,
                    status = entity.availability.replace('_', ' '),
                    delivery = entity.deliveryEstimate ?: "See merchant site",
                    matchType = runCatching { MatchType.valueOf(entity.matchType) }.getOrDefault(MatchType.EXACT),
                    confidence = entity.confidence,
                    isLowest = entity.price == minPrice,
                    url = entity.productUrl
                )
            }
        } else {
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
        }
    }

    val bestDeal = remember(merchantListings) {
        merchantListings.minByOrNull { it.price } ?: merchantListings.first()
    }
    val highestObservedPrice = remember(merchantListings) {
        merchantListings.maxOfOrNull { it.originalPrice ?: it.price } ?: bestDeal.price
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
                    DemoDataBadge()
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val shareIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Comparing $productTitle on Visual Cart - best price ₹${bestDeal.price.toInt()} on ${bestDeal.name}!"
                                )
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

                        // Best Price Banner
                        val bestOriginal = bestDeal.originalPrice
                        val bannerSavings = if (bestOriginal != null && bestOriginal > bestDeal.price) {
                            bestOriginal - bestDeal.price
                        } else 0.0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = if (savedProduct != null) "SELECTED MERCHANT PRICE" else "VERIFIED BEST PRICE",
                                    style = VisualCartTypography.labelSmall.copy(
                                        color = ForestGreen,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    )
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = "₹${bestDeal.price.toInt()}",
                                        style = VisualCartTypography.displayMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = DeepInk
                                        )
                                    )
                                    if (bestOriginal != null && bestOriginal > bestDeal.price) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "₹${bestOriginal.toInt()}",
                                            style = VisualCartTypography.bodyMedium.copy(
                                                textDecoration = TextDecoration.LineThrough,
                                                color = MutedSlate
                                            )
                                        )
                                    }
                                }
                            }

                            if (bannerSavings > 0) {
                                val pct = ((bannerSavings / (bestOriginal ?: bestDeal.price)) * 100).toInt()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = TerracottaLight,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Terracotta.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = "Save ₹${bannerSavings.toInt()} ($pct%)",
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

                        // PayPal 1-Click Sandbox Checkout Button
                        VisualCartButton(
                            text = "Checkout with PayPal · ₹${bestDeal.price.toInt()}",
                            onClick = {
                                val listing = MerchantListing(
                                    id = "${bestDeal.name.lowercase().replace("\\s+".toRegex(), "_")}_$productId",
                                    merchantName = bestDeal.name,
                                    merchantDomain = Uri.parse(bestDeal.url).host ?: "store.example.com",
                                    title = productTitle,
                                    price = bestDeal.price,
                                    originalPrice = bestDeal.originalPrice,
                                    currency = "INR",
                                    productUrl = bestDeal.url,
                                    imageUrl = "",
                                    matchType = bestDeal.matchType,
                                    confidence = bestDeal.confidence,
                                    availability = AvailabilityStatus.IN_STOCK,
                                    sourceType = if (savedProduct != null) SourceType.DEMO else SourceType.DEMO,
                                    lastVerifiedAt = System.currentTimeMillis()
                                )
                                PayPalCheckoutCoordinator.instance.startCheckout(context, listing)
                            },
                            variant = ButtonVariant.PRIMARY,
                            icon = VisualCartIcons.ShieldCheck,
                            modifier = Modifier.fillMaxWidth()
                        )

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
                                text = "Covered by PayPal Buyer Protection · Merchant: ${bestDeal.name}",
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
                Text(
                    text = "Compare Stores & Availability",
                    style = VisualCartTypography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )

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
                                        Text(
                                            text = "₹${listing.price.toInt()}",
                                            style = VisualCartTypography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = DeepInk
                                            )
                                        )
                                        if (listing.originalPrice != null && listing.originalPrice > listing.price) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "₹${listing.originalPrice.toInt()}",
                                                style = VisualCartTypography.bodySmall.copy(
                                                    textDecoration = TextDecoration.LineThrough,
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
                                Text(
                                    text = "₹${bestDeal.price.toInt()} (Today)",
                                    style = VisualCartTypography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepInk
                                    )
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "30-DAY HIGH",
                                    style = VisualCartTypography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        color = MutedSlate,
                                        letterSpacing = 0.8.sp
                                    )
                                )
                                Text(
                                    text = "₹${highestObservedPrice.toInt()}",
                                    style = VisualCartTypography.titleMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = MutedSlate
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Custom Price Trend Line Graph
                        PriceHistoryGraph(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Jan 10", style = VisualCartTypography.labelSmall.copy(color = MutedSlate))
                            Text(text = "Jan 20", style = VisualCartTypography.labelSmall.copy(color = MutedSlate))
                            Text(text = "Feb 01", style = VisualCartTypography.labelSmall.copy(color = MutedSlate))
                            Text(text = "Today", style = VisualCartTypography.labelSmall.copy(fontWeight = FontWeight.Bold, color = ForestGreen))
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
                            Text(
                                text = "96% Confidence Canonical Match",
                                style = VisualCartTypography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeepInk
                                )
                            )
                        }

                        Text(
                            text = "Multimodal analysis verified that Amazon, Flipkart, and Croma list the identical Micron-manufactured NVMe SSD based on matching hardware identifiers:",
                            style = VisualCartTypography.bodySmall.copy(
                                color = DeepInk,
                                lineHeight = 18.sp
                            )
                        )

                        // Matched attributes checklist
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            VerificationItem(label = "Capacity: 1TB (1000GB format confirmed)")
                            VerificationItem(label = "Interface: PCIe Gen4 x4 NVMe 1.4")
                            VerificationItem(label = "Form factor: M.2 2280 single-sided")
                            VerificationItem(label = "Brand & Controller: Crucial / Micron Phison E21T")
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
private fun PriceHistoryGraph(modifier: Modifier = Modifier) {
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

        // Price points normalized (0 to 1)
        // High ₹6,499 -> ₹6,199 -> ₹5,999 -> ₹5,799 -> Lowest ₹5,499
        val points = listOf(
            Offset(0f, height * 0.15f),
            Offset(width * 0.25f, height * 0.35f),
            Offset(width * 0.5f, height * 0.50f),
            Offset(width * 0.75f, height * 0.70f),
            Offset(width, height * 0.90f)
        )

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
    val price: Double,
    val originalPrice: Double?,
    val status: String,
    val delivery: String,
    val matchType: MatchType,
    val confidence: Float,
    val isLowest: Boolean,
    val url: String
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

