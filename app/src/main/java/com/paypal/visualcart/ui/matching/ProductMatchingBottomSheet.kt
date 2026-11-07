package com.paypal.visualcart.ui.matching

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.paypal.visualcart.core.designsystem.ProductOriginBadge
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
import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType

/**
 * Data structure representing a detected product candidate and its multi-merchant matches.
 */
data class DetectedProductMatchResult(
    val detectedTitle: String,
    val brand: String,
    val category: String,
    val specs: List<String>,
    val matchReason: String,
    val listings: List<MerchantListingItem>,
    val isDemoFixture: Boolean = false,
    val isLiveInference: Boolean = !isDemoFixture
)

data class MerchantListingItem(
    val merchantName: String,
    val price: Double,
    val originalPrice: Double? = null,
    val matchType: MatchType,
    val confidence: Float,
    val productUrl: String,
    val isLowestPrice: Boolean = false,
    val stockNote: String = "In Stock"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductMatchingBottomSheet(
    onDismiss: () -> Unit,
    onAddToCart: (MerchantListingItem) -> Unit,
    onCompare: (String) -> Unit,
    modifier: Modifier = Modifier,
    detectedMatch: DetectedProductMatchResult = defaultDetectedResult()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = WarmIvory,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(44.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Stone)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Status + Badge + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ForestGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PRODUCT FOUND",
                        style = VisualCartTypography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ProductOriginBadge(
                        isDemoFixture = detectedMatch.isDemoFixture,
                        isLiveInference = detectedMatch.isLiveInference
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(PureWhite)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = DeepInk,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Detected Product Summary Card
            VisualCartCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = PureWhite,
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Product Thumbnail Placeholder / Camera Box
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(StoneLight)
                                .border(1.dp, Stone, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = VisualCartIcons.ScanCamera,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = detectedMatch.brand.uppercase(),
                                style = VisualCartTypography.labelSmall.copy(
                                    color = MutedSlate,
                                    letterSpacing = 0.8.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = detectedMatch.detectedTitle,
                                style = VisualCartTypography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 18.sp
                                ),
                                color = DeepInk,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Specification Pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        detectedMatch.specs.forEach { spec ->
                            SpecTag(spec = spec)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // AI Match Reasoning Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SageLight,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Stone),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = VisualCartIcons.ShieldCheck,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = detectedMatch.matchReason,
                                style = VisualCartTypography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = DeepInk,
                                    lineHeight = 16.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Available Listings Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AVAILABLE LISTINGS",
                    style = VisualCartTypography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate,
                        letterSpacing = 1.sp
                    )
                )
                Text(
                    text = "${detectedMatch.listings.size} verified matches",
                    style = VisualCartTypography.labelSmall.copy(color = ForestGreen, fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Available Listings List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                detectedMatch.listings.forEach { listing ->
                    MerchantListingMatchRow(
                        listing = listing,
                        onView = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(listing.productUrl))
                            context.startActivity(intent)
                        },
                        onAdd = { onAddToCart(listing) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            val bestListing = detectedMatch.listings.minByOrNull { it.price }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VisualCartButton(
                    text = "Add Best Price to Cart (₹${bestListing?.price?.toInt() ?: 5499})",
                    onClick = {
                        if (bestListing != null) {
                            onAddToCart(bestListing)
                        }
                    },
                    variant = ButtonVariant.PRIMARY,
                    icon = Icons.Default.ShoppingCart,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VisualCartButton(
                        text = "Compare All Stores",
                        onClick = { onCompare("crucial-p3-plus-1tb") },
                        variant = ButtonVariant.SECONDARY,
                        icon = VisualCartIcons.CompareArrows,
                        modifier = Modifier.weight(1f)
                    )

                    VisualCartButton(
                        text = "Dismiss",
                        onClick = onDismiss,
                        variant = ButtonVariant.GHOST,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MerchantListingMatchRow(
    listing: MerchantListingItem,
    onView: () -> Unit,
    onAdd: () -> Unit
) {
    VisualCartCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = PureWhite,
        borderColor = if (listing.isLowestPrice) ForestGreen.copy(alpha = 0.5f) else Stone,
        borderWidth = if (listing.isLowestPrice) 1.5.dp else 1.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MerchantLogoBadge(merchantName = listing.merchantName)
                    if (listing.isLowestPrice) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ForestGreenLight
                        ) {
                            Text(
                                text = "LOWEST",
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

                ConfidenceIndicator(
                    matchType = listing.matchType,
                    confidence = listing.confidence
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action Buttons [VIEW] [ADD]
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VisualCartButton(
                    text = "VIEW",
                    onClick = onView,
                    variant = ButtonVariant.SECONDARY,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                )

                VisualCartButton(
                    text = "ADD",
                    onClick = onAdd,
                    variant = ButtonVariant.PRIMARY,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }
}

fun defaultDetectedResult(): DetectedProductMatchResult {
    return DetectedProductMatchResult(
        detectedTitle = "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
        brand = "Crucial",
        category = "Electronics",
        specs = listOf("1TB", "NVMe", "PCIe 4.0", "M.2 2280", "5000 MB/s"),
        matchReason = "Likely same product: matching brand, model, capacity and interface across merchants.",
        listings = listOf(
            MerchantListingItem(
                merchantName = "Flipkart",
                price = 5499.0,
                originalPrice = 6099.0,
                matchType = MatchType.LIKELY,
                confidence = 0.92f,
                productUrl = "https://www.flipkart.com",
                isLowestPrice = true
            ),
            MerchantListingItem(
                merchantName = "Croma",
                price = 5799.0,
                originalPrice = 6199.0,
                matchType = MatchType.EXACT,
                confidence = 0.95f,
                productUrl = "https://www.croma.com",
                isLowestPrice = false
            ),
            MerchantListingItem(
                merchantName = "Amazon",
                price = 5999.0,
                originalPrice = 6499.0,
                matchType = MatchType.EXACT,
                confidence = 0.98f,
                productUrl = "https://www.amazon.in",
                isLowestPrice = false
            ),
            MerchantListingItem(
                merchantName = "Reliance Digital",
                price = 6199.0,
                originalPrice = 6599.0,
                matchType = MatchType.SIMILAR,
                confidence = 0.82f,
                productUrl = "https://www.reliancedigital.in",
                isLowestPrice = false
            )
        )
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun ProductMatchingBottomSheetPreview() {
    com.paypal.visualcart.core.designsystem.VisualCartTheme {
        ProductMatchingBottomSheet(
            onDismiss = {},
            onAddToCart = {},
            onCompare = {}
        )
    }
}

