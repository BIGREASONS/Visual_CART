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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.paypal.visualcart.core.designsystem.LiveInferenceBadge
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
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.domain.model.RecognizedProductCandidate
import com.paypal.visualcart.domain.model.SourceType
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder

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
    val isLiveInference: Boolean = !isDemoFixture,
    val imageUri: String? = null,
    val isUncertain: Boolean = false,
    val recognitionStatus: RecognitionStatus = RecognitionStatus.IDENTIFIED,
    val candidates: List<RecognizedProductCandidate> = emptyList(),
    val searchQuery: String = detectedTitle,
    val recognitionSource: RecognitionSource = when {
        isLiveInference -> RecognitionSource.LIVE_AI
        isDemoFixture -> RecognitionSource.DEMO
        else -> RecognitionSource.LOCAL_OCR
    },
    val rateLimited: Boolean = false
)

data class MerchantListingItem(
    val merchantName: String,
    val price: Double? = null,
    val originalPrice: Double? = null,
    val currency: String = "INR",
    val matchType: MatchType,
    val confidence: Float,
    val productUrl: String,
    val listingOrigin: ListingOrigin = if (price != null && price > 0.0) {
        ListingOrigin.VERIFIED_CATALOG_FIXTURE
    } else {
        ListingOrigin.DIRECT_SEARCH_URL
    },
    val sourceType: SourceType = if (listingOrigin == ListingOrigin.LIVE_API) {
        SourceType.LIVE
    } else {
        SourceType.DEMO
    },
    val isLowestPrice: Boolean = false,
    val stockNote: String = if (price != null && price > 0.0) "In Stock" else "Open search results",
    val imageUrl: String? = null,
    val providerProductId: String? = null,
    val offerSource: String? = null
) {
    val hasVerifiedPrice: Boolean
        get() = price != null && price > 0.0 &&
            listingOrigin != ListingOrigin.DIRECT_SEARCH_URL &&
            listingOrigin != ListingOrigin.NO_RESULT
}

private fun currencySymbolFor(currency: String): String = when (currency.uppercase()) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    else -> "₹"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductMatchingBottomSheet(
    onDismiss: () -> Unit,
    onAddToCart: (MerchantListingItem) -> Unit,
    onCompare: (String) -> Unit,
    modifier: Modifier = Modifier,
    detectedMatch: DetectedProductMatchResult = defaultDetectedResult(),
    onRefineQuery: ((String) -> Unit)? = null,
    onRetryRecognition: (() -> Unit)? = null
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
            val (headerLabel, headerColor) = when (detectedMatch.recognitionStatus) {
                RecognitionStatus.UNIDENTIFIED -> "NO CLEAR MATCH" to Terracotta
                RecognitionStatus.PARTIAL -> "POSSIBLE MATCH" to Terracotta
                RecognitionStatus.IDENTIFIED -> "PRODUCT FOUND" to ForestGreen
            }
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
                            .background(headerColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = headerLabel,
                        style = VisualCartTypography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = headerColor,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ProductOriginBadge(
                        isDemoFixture = detectedMatch.isDemoFixture,
                        isLiveInference = detectedMatch.isLiveInference,
                        recognitionSource = detectedMatch.recognitionSource
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
            val dedupedListings = remember(detectedMatch.listings) {
                detectedMatch.listings.distinctBy { SearchQueryBuilder.normalizeMerchantKey(it.merchantName) }
            }
            val pricedListings = dedupedListings.filter { it.hasVerifiedPrice }
            val distinctPricedCurrencies = pricedListings.map { it.currency.uppercase() }.distinct()
            val comparableMerchants = if (distinctPricedCurrencies.size == 1) {
                pricedListings.map { SearchQueryBuilder.normalizeMerchantKey(it.merchantName) }.distinct().size
            } else {
                0
            }

            if (detectedMatch.rateLimited || detectedMatch.isUncertain || detectedMatch.recognitionStatus != RecognitionStatus.IDENTIFIED) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TerracottaLight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = when {
                                detectedMatch.rateLimited -> "FREE AI LIMIT REACHED"
                                detectedMatch.recognitionStatus == RecognitionStatus.UNIDENTIFIED -> "UNIDENTIFIED PRODUCT"
                                else -> "UNCERTAIN VISUAL MATCH"
                            },
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Terracotta
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (detectedMatch.rateLimited && !detectedMatch.matchReason.contains("Free AI limit", ignoreCase = true)) {
                                "Free AI limit reached. Try again later. Enter a product query manually or use direct merchant search links."
                            } else {
                                detectedMatch.matchReason
                            },
                            style = VisualCartTypography.bodySmall.copy(color = DeepInk)
                        )
                        if (detectedMatch.candidates.isNotEmpty() && onRefineQuery != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                detectedMatch.candidates.take(4).forEach { candidate ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = PureWhite,
                                        modifier = Modifier.clickable { onRefineQuery(candidate.searchQuery) }
                                    ) {
                                        Text(
                                            text = candidate.title,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = VisualCartTypography.labelSmall.copy(color = ForestGreen)
                                        )
                                    }
                                }
                            }
                        }
                        if (onRefineQuery != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            var refinedQueryText by remember(detectedMatch.detectedTitle, detectedMatch.searchQuery) {
                                mutableStateOf(
                                    detectedMatch.searchQuery.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }.orEmpty()
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = refinedQueryText,
                                    onValueChange = { refinedQueryText = it },
                                    singleLine = true,
                                    placeholder = { Text("Enter product name (e.g. metal pipe)") },
                                    modifier = Modifier.weight(1f)
                                )
                                VisualCartButton(
                                    text = "Search",
                                    onClick = {
                                        val clean = refinedQueryText.trim()
                                        if (clean.isNotEmpty()) {
                                            onRefineQuery(clean)
                                        }
                                    },
                                    variant = ButtonVariant.PRIMARY,
                                    enabled = refinedQueryText.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                        if (onRetryRecognition != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                VisualCartButton(
                                    text = "Retry Scan",
                                    onClick = onRetryRecognition,
                                    variant = ButtonVariant.SECONDARY,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

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
                    text = when {
                        dedupedListings.isEmpty() -> "No store links yet"
                        comparableMerchants >= 2 -> "$comparableMerchants comparable priced offers"
                        pricedListings.isNotEmpty() -> "${pricedListings.size} priced offer"
                        else -> "${dedupedListings.size} live search links (unpriced)"
                    },
                    style = VisualCartTypography.labelSmall.copy(color = ForestGreen, fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Available Listings List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                dedupedListings.forEach { listing ->
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
            val bestListing = pricedListings.minByOrNull { it.price ?: Double.MAX_VALUE }
                ?: dedupedListings.firstOrNull()
            val primaryButtonText = when {
                bestListing != null && bestListing.hasVerifiedPrice && bestListing.price != null && comparableMerchants >= 2 ->
                    "Add Best Price to Cart (${com.paypal.visualcart.core.util.CurrencyFormatter.format(bestListing.price, bestListing.currency)})"
                bestListing != null && bestListing.hasVerifiedPrice && bestListing.price != null ->
                    "Add Offer to Cart (${com.paypal.visualcart.core.util.CurrencyFormatter.format(bestListing.price, bestListing.currency)})"
                bestListing != null ->
                    "Save Search Links to Cart"
                else -> "Close"
            }
            val canonicalId = SearchQueryBuilder.normalizeProductIdentityKey(detectedMatch.detectedTitle)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VisualCartButton(
                    text = primaryButtonText,
                    onClick = {
                        if (bestListing != null) {
                            onAddToCart(bestListing)
                        } else {
                            onDismiss()
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
                    if (dedupedListings.isNotEmpty()) {
                        VisualCartButton(
                            text = "Compare All Stores",
                            onClick = { onCompare(canonicalId) },
                            variant = ButtonVariant.SECONDARY,
                            icon = VisualCartIcons.CompareArrows,
                            modifier = Modifier.weight(1f)
                        )
                    }

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
                    if (listing.isLowestPrice && listing.hasVerifiedPrice) {
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
                    } else if (listing.listingOrigin == ListingOrigin.LIVE_API) {
                        Spacer(modifier = Modifier.width(6.dp))
                        LiveInferenceBadge(text = "LIVE")
                    } else if (listing.listingOrigin == ListingOrigin.DIRECT_SEARCH_URL) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SpecTag(spec = "SEARCH LINK")
                    } else if (listing.listingOrigin == ListingOrigin.VERIFIED_CATALOG_FIXTURE ||
                        listing.listingOrigin == ListingOrigin.DEMO_DATA
                    ) {
                        Spacer(modifier = Modifier.width(6.dp))
                        DemoDataBadge()
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    val priceVal = listing.price
                    if (listing.hasVerifiedPrice && priceVal != null) {
                        Text(
                            text = com.paypal.visualcart.core.util.CurrencyFormatter.format(priceVal, listing.currency),
                            style = VisualCartTypography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepInk
                            )
                        )
                        if (listing.originalPrice != null && listing.originalPrice > priceVal) {
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
                            text = if (listing.listingOrigin == ListingOrigin.NO_RESULT) {
                                "No verified offer"
                            } else {
                                "Visit store for live price"
                            },
                            style = VisualCartTypography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MutedSlate
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (listing.listingOrigin == ListingOrigin.DIRECT_SEARCH_URL) {
                    Text(
                        text = listing.stockNote.ifBlank { "Open live search in browser" },
                        style = VisualCartTypography.bodySmall.copy(color = MutedSlate, fontSize = 11.sp)
                    )
                } else {
                    ConfidenceIndicator(
                        matchType = listing.matchType,
                        confidence = listing.confidence
                    )
                }
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
        matchReason = "Sample demonstration item: matching brand, model, capacity and interface across catalog fixtures.",
        isDemoFixture = true,
        isLiveInference = false,
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

fun sonyDemoDetectedResult(): DetectedProductMatchResult {
    return DetectedProductMatchResult(
        detectedTitle = "Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
        brand = "Sony",
        category = "Audio",
        specs = listOf("Over-Ear", "Bluetooth 5.2", "30 Hours Battery", "ANC"),
        matchReason = "Sample demonstration item: Sony WH-1000XM5 catalog fixture across verified stores.",
        isDemoFixture = true,
        isLiveInference = false,
        listings = listOf(
            MerchantListingItem(
                merchantName = "Flipkart",
                price = 24990.0,
                originalPrice = 34990.0,
                matchType = MatchType.EXACT,
                confidence = 0.98f,
                productUrl = "https://www.flipkart.com/search?q=Sony+WH-1000XM5",
                isLowestPrice = true
            ),
            MerchantListingItem(
                merchantName = "Amazon India",
                price = 25990.0,
                originalPrice = 34990.0,
                matchType = MatchType.EXACT,
                confidence = 0.97f,
                productUrl = "https://www.amazon.in/s?k=Sony+WH-1000XM5",
                isLowestPrice = false
            ),
            MerchantListingItem(
                merchantName = "Croma",
                price = 26990.0,
                originalPrice = 34990.0,
                matchType = MatchType.EXACT,
                confidence = 0.95f,
                productUrl = "https://www.croma.com/searchB?q=Sony+WH-1000XM5",
                isLowestPrice = false
            )
        )
    )
}

suspend fun resolveManualRefinedMatch(
    rawQuery: String,
    existingImageUri: String?,
    context: android.content.Context? = null
): DetectedProductMatchResult {
    val cleanQuery = com.paypal.visualcart.core.util.SecurityUtils.sanitizeSearchQuery(rawQuery).trim()
    val queryResult = SearchQueryBuilder.buildFromRawQuery(cleanQuery)
    if (queryResult is SearchQueryBuilder.Result.Rejected) {
        return DetectedProductMatchResult(
            detectedTitle = "Unidentified Image (Enter Search Manually)",
            brand = "Needs Query",
            category = "Unidentified",
            specs = emptyList(),
            matchReason = "Please enter a clearer product name (not a filename or timestamp).",
            listings = emptyList(),
            isDemoFixture = false,
            isLiveInference = false,
            imageUri = existingImageUri,
            isUncertain = true,
            recognitionStatus = RecognitionStatus.UNIDENTIFIED,
            searchQuery = ""
        )
    }

    val validQuery = (queryResult as SearchQueryBuilder.Result.Valid).query
    val parsed = com.paypal.visualcart.feature.ai.LocalProductParser.parse(validQuery, imageUrl = existingImageUri)
    val providers = if (context != null) {
        com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine.liveProviders(context)
    } else {
        com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine.defaultProviders().map {
            it.apply { isLiveMode = true }
        }
    }
    val liveEngine = com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine(providers)
    val discovery = liveEngine.discoverStructured(parsed)
    val allListings = liveEngine.deduplicateListings(
        discovery.exactMatches + discovery.similarAlternatives + discovery.searchLinks
    )
    val summary = com.paypal.visualcart.domain.model.MerchantComparisonSummary.fromListings(validQuery, allListings)
    val liveCount = allListings.count { it.hasVerifiedPrice }
    val resolvedImage = existingImageUri?.takeIf { it.isNotBlank() }
        ?: allListings.firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl

    return DetectedProductMatchResult(
        detectedTitle = parsed.title.ifBlank { validQuery },
        brand = parsed.brand ?: "Detected Product",
        category = parsed.category,
        specs = parsed.specifications.entries.map { "${it.key}: ${it.value}" },
        matchReason = if (liveCount > 0) {
            "Found $liveCount live priced offer(s) across ${allListings.size} store matches for \"$validQuery\"."
        } else {
            "Prepared ${allListings.size} store search links for \"$validQuery\"."
        },
        listings = allListings.map { listing ->
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
                    listing.hasVerifiedPrice && listing.availability == com.paypal.visualcart.domain.model.AvailabilityStatus.IN_STOCK -> "In Stock"
                    listing.hasVerifiedPrice -> "Check Store"
                    else -> "Open search results"
                },
                imageUrl = listing.imageUrl.takeIf { it.isNotBlank() },
                providerProductId = listing.providerProductId,
                offerSource = listing.offerSource
            )
        },
        isDemoFixture = false,
        isLiveInference = liveCount > 0,
        imageUri = resolvedImage,
        isUncertain = false,
        recognitionStatus = RecognitionStatus.IDENTIFIED,
        searchQuery = validQuery,
        recognitionSource = RecognitionSource.MANUAL_SEARCH,
        rateLimited = false
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

