package com.paypal.visualcart.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paypal.visualcart.R
import com.paypal.visualcart.core.designsystem.ButtonVariant
import com.paypal.visualcart.core.designsystem.ConfidenceIndicator
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.DemoDataBadge
import com.paypal.visualcart.core.designsystem.ForestGreen
import com.paypal.visualcart.core.designsystem.ForestGreenLight
import com.paypal.visualcart.core.designsystem.LiveInferenceBadge
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
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantComparisonSummary
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.domain.model.RecognizedProduct
import com.paypal.visualcart.domain.model.RecognizedProductCandidate
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder

private fun shareCurrencySymbol(currency: String): String = when (currency.uppercase()) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    else -> "₹"
}

sealed interface ShareCaptureUiState {
    data class Loading(
        val thumbnail: Bitmap? = null,
        val statusMessage: String = "Finding your product…",
        val detectedHint: String? = null
    ) : ShareCaptureUiState

    data class Matched(
        val product: RecognizedProduct,
        val thumbnail: Bitmap? = null,
        val bestListing: MerchantListing,
        val exactListings: List<MerchantListing>,
        val similarListings: List<MerchantListing>,
        val selectedListing: MerchantListing,
        val comparisonSummary: MerchantComparisonSummary? = MerchantComparisonSummary.fromListings(
            product.searchQuery.ifBlank { product.title },
            exactListings + similarListings
        ),
        val isUncertain: Boolean = false
    ) : ShareCaptureUiState

    data class Saved(
        val productTitle: String,
        val merchantName: String,
        val price: Double? = null,
        val totalCartCount: Int,
        val currency: String = "INR"
    ) : ShareCaptureUiState

    data class Error(val message: String) : ShareCaptureUiState

    data class NeedsQuery(
        val thumbnail: Bitmap? = null,
        val message: String,
        val suggestedQuery: String = "",
        val imageUri: String? = null,
        val candidates: List<RecognizedProductCandidate> = emptyList(),
        val canRetry: Boolean = false,
        val detectedCategory: String? = null,
        val rateLimited: Boolean = false
    ) : ShareCaptureUiState
}

@Composable
fun ShareCaptureDialog(
    uiState: ShareCaptureUiState,
    onDismiss: () -> Unit,
    onSelectListing: (MerchantListing) -> Unit,
    onAddToCart: (MerchantListing) -> Unit,
    onViewInStore: (String) -> Unit,
    onOpenFullCart: () -> Unit,
    onSubmitManualQuery: (String) -> Unit = {},
    onEditQuery: () -> Unit = {},
    onRetryRecognition: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Dimmed background scrim
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Main floating dialog card
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}, // Prevent dismiss when tapping card content
            color = WarmIvory,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Bar
                ShareDialogHeader(onDismiss = onDismiss)

                Spacer(modifier = Modifier.height(14.dp))

                when (uiState) {
                    is ShareCaptureUiState.Loading -> {
                        ShareLoadingContent(state = uiState)
                    }
                    is ShareCaptureUiState.NeedsQuery -> {
                        ShareNeedsQueryContent(
                            state = uiState,
                            onSubmitQuery = onSubmitManualQuery,
                            onRetryRecognition = onRetryRecognition,
                            onDismiss = onDismiss
                        )
                    }
                    is ShareCaptureUiState.Matched -> {
                        ShareMatchedContent(
                            state = uiState,
                            onSelectListing = onSelectListing,
                            onAddToCart = onAddToCart,
                            onViewInStore = onViewInStore,
                            onSubmitCandidateQuery = onSubmitManualQuery,
                            onEditQuery = onEditQuery,
                            onRetryRecognition = onRetryRecognition
                        )
                    }
                    is ShareCaptureUiState.Saved -> {
                        ShareSavedContent(
                            state = uiState,
                            onOpenFullCart = onOpenFullCart,
                            onDone = onDismiss
                        )
                    }
                    is ShareCaptureUiState.Error -> {
                        ShareErrorContent(message = uiState.message, onDismiss = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareDialogHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ic_visual_cart_icon),
                contentDescription = "Visual Cart",
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "ADD TO VISUAL CART",
                    style = VisualCartTypography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = ForestGreen,
                        letterSpacing = 1.1.sp
                    )
                )
                Text(
                    text = "Universal Cross-Store Matcher",
                    style = VisualCartTypography.labelSmall.copy(
                        color = MutedSlate,
                        fontSize = 11.sp
                    )
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(StoneLight)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = DeepInk,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ShareLoadingContent(state: ShareCaptureUiState.Loading) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.thumbnail != null) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, Stone, RoundedCornerShape(16.dp))
                    .background(PureWhite),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = state.thumbnail.asImageBitmap(),
                    contentDescription = "Shared image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        CircularProgressIndicator(
            color = ForestGreen,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = state.statusMessage,
            style = VisualCartTypography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = DeepInk
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Analyzing visual features across Amazon, Flipkart, Croma, Reliance Digital & more…",
            style = VisualCartTypography.bodySmall.copy(
                color = MutedSlate,
                lineHeight = 16.sp
            ),
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (state.detectedHint != null) {
            Spacer(modifier = Modifier.height(12.dp))
            SpecTag(spec = state.detectedHint)
        }
    }
}

@Composable
private fun ShareMatchedContent(
    state: ShareCaptureUiState.Matched,
    onSelectListing: (MerchantListing) -> Unit,
    onAddToCart: (MerchantListing) -> Unit,
    onViewInStore: (String) -> Unit,
    onSubmitCandidateQuery: (String) -> Unit = {},
    onEditQuery: () -> Unit = {},
    onRetryRecognition: () -> Unit = {}
) {
    val allListings = remember(state.exactListings, state.similarListings) {
        (state.exactListings + state.similarListings).distinctBy { it.normalizedMerchantId }
    }
    val displayQuery = state.product.searchQuery.ifBlank { state.product.title }
    val summary = remember(allListings, displayQuery) {
        MerchantComparisonSummary.fromListings(displayQuery, allListings)
    }
    val lowestListing = summary?.lowestPriceListing
    val isUncertainMatch = state.isUncertain ||
        state.product.status != RecognitionStatus.IDENTIFIED ||
        state.product.candidates.size > 1

    Column(modifier = Modifier.fillMaxWidth()) {
        // Product Hero Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PureWhite)
                .border(1.dp, Stone, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.thumbnail != null) {
                Image(
                    bitmap = state.thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.product.title,
                        style = VisualCartTypography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onEditQuery,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit product query",
                            tint = ForestGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.product.brand?.let { SpecTag(spec = it) }
                    SpecTag(spec = state.product.category)
                    ProductOriginBadge(
                        isDemoFixture = state.product.isDemoFixture,
                        isLiveInference = state.product.isLiveInference,
                        recognitionSource = state.product.recognitionSource
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.product.rateLimited || isUncertainMatch) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TerracottaLight)
                    .border(1.dp, Terracotta.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when {
                        state.product.rateLimited -> "FREE AI LIMIT REACHED"
                        state.product.candidates.size > 1 -> "MULTIPLE VISUAL MATCHES · CONFIRM PRODUCT"
                        else -> "PARTIAL IDENTIFICATION · VERIFY SEARCH QUERY"
                    },
                    style = VisualCartTypography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Terracotta,
                        letterSpacing = 0.6.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.product.matchReason.ifBlank {
                        "Visual recognition is uncertain. Confirm a candidate or edit the search query before opening stores."
                    },
                    style = VisualCartTypography.bodySmall.copy(color = DeepInk)
                )
                if (state.product.candidates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.product.candidates.take(4)) { candidate ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(PureWhite)
                                    .border(1.dp, ForestGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .clickable { onSubmitCandidateQuery(candidate.searchQuery) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = candidate.title,
                                    style = VisualCartTypography.labelSmall.copy(
                                        color = ForestGreen,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Edit product name",
                        modifier = Modifier.clickable(onClick = onEditQuery),
                        style = VisualCartTypography.labelSmall.copy(
                            color = ForestGreen,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Try again",
                        modifier = Modifier.clickable(onClick = onRetryRecognition),
                        style = VisualCartTypography.labelSmall.copy(
                            color = DeepInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (summary != null && summary.hasComparableBestPrice && lowestListing != null && lowestListing.price != null) {
            val savings = (summary.verifiedSavings ?: summary.maxSavings).coerceAtLeast(0.0)
            val sym = shareCurrencySymbol(lowestListing.currency)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ForestGreenLight)
                    .border(1.dp, ForestGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "BEST PRICE FOUND",
                        style = VisualCartTypography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Text(
                        text = "${lowestListing.merchantName} · $sym${lowestListing.price.toInt()}",
                        style = VisualCartTypography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        )
                    )
                }

                if (savings > 0.0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Terracotta)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Save $sym${savings.toInt()}",
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                        )
                    }
                }
            }
        } else if (lowestListing != null && lowestListing.price != null) {
            val sym = shareCurrencySymbol(lowestListing.currency)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SageLight)
                    .border(1.dp, Stone, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SINGLE OFFER LISTED",
                        style = VisualCartTypography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Text(
                        text = "${lowestListing.merchantName} · $sym${lowestListing.price.toInt()}",
                        style = VisualCartTypography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        )
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StoneLight)
                    .border(1.dp, Stone, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LIVE STORE SEARCH LINKS",
                        style = VisualCartTypography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Text(
                        text = "Tap any ↗ icon to view real results for \"$displayQuery\"",
                        style = VisualCartTypography.bodySmall.copy(color = MutedSlate),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scrollable Merchant Listings
        Text(
            text = "Available Store Listings",
            style = VisualCartTypography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MutedSlate
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allListings, key = { it.id }) { listing ->
                ShareMerchantCard(
                    listing = listing,
                    isSelected = listing.id == state.selectedListing.id,
                    isLowest = (summary?.hasComparableBestPrice == true) &&
                        listing.hasVerifiedPrice &&
                        listing.id == lowestListing?.id,
                    onSelect = { onSelectListing(listing) },
                    onViewInStore = { onViewInStore(listing.productUrl) }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Primary Action: Add to Visual Cart
        val selectedPrice = state.selectedListing.price
        val selectedSym = shareCurrencySymbol(state.selectedListing.currency)
        VisualCartButton(
            text = if (state.selectedListing.hasVerifiedPrice && selectedPrice != null) {
                "ADD TO VISUAL CART ($selectedSym${selectedPrice.toInt()})"
            } else {
                "SAVE STORE SEARCH LINK"
            },
            onClick = { onAddToCart(state.selectedListing) },
            variant = ButtonVariant.PRIMARY,
            icon = VisualCartIcons.Cart,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ShareMerchantCard(
    listing: MerchantListing,
    isSelected: Boolean,
    isLowest: Boolean,
    onSelect: () -> Unit,
    onViewInStore: () -> Unit
) {
    val borderColor = if (isSelected) ForestGreen else Stone
    val bg = if (isSelected) PureWhite else PureWhite.copy(alpha = 0.85f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(if (isSelected) 1.8.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listing.merchantName,
                    style = VisualCartTypography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (isLowest) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ForestGreen)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LOWEST",
                            style = VisualCartTypography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                when (listing.listingOrigin) {
                    ListingOrigin.LIVE_API -> LiveInferenceBadge(text = "LIVE")
                    ListingOrigin.DIRECT_SEARCH_URL -> SpecTag(spec = "SEARCH LINK")
                    ListingOrigin.VERIFIED_CATALOG_FIXTURE, ListingOrigin.DEMO_DATA -> DemoDataBadge()
                    ListingOrigin.NO_RESULT -> SpecTag(spec = "UNAVAILABLE")
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                val verifiedPrice = listing.price
                if (listing.hasVerifiedPrice && verifiedPrice != null) {
                    val sym = shareCurrencySymbol(listing.currency)
                    Text(
                        text = "$sym${verifiedPrice.toInt()}",
                        style = VisualCartTypography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isLowest) ForestGreen else DeepInk
                        )
                    )
                    if (listing.originalPrice != null && listing.originalPrice > verifiedPrice) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$sym${listing.originalPrice.toInt()}",
                            style = VisualCartTypography.labelSmall.copy(
                                color = MutedSlate,
                                textDecoration = TextDecoration.LineThrough
                            )
                        )
                    }
                } else {
                    Text(
                        text = if (listing.listingOrigin == ListingOrigin.NO_RESULT) {
                            "No verified offer found"
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
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onViewInStore,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(StoneLight)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Open in store",
                    tint = DeepInk,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, if (isSelected) ForestGreen else MutedSage, CircleShape)
                    .background(if (isSelected) ForestGreen else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareSavedContent(
    state: ShareCaptureUiState.Saved,
    onOpenFullCart: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(ForestGreenLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = ForestGreen,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Saved to Visual Cart!",
            style = VisualCartTypography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color = DeepInk
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        val savedSummary = if (state.price != null && state.price > 0.0) {
            "${state.productTitle} from ${state.merchantName} (${shareCurrencySymbol(state.currency)}${state.price.toInt()})"
        } else {
            "${state.productTitle} search link saved for ${state.merchantName}"
        }
        Text(
            text = savedSummary,
            style = VisualCartTypography.bodyMedium.copy(
                color = MutedSlate
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "You have ${state.totalCartCount} item(s) in your persistent cart.",
            style = VisualCartTypography.bodySmall.copy(
                color = ForestGreen,
                fontWeight = FontWeight.SemiBold
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VisualCartButton(
                text = "Done",
                onClick = onDone,
                variant = ButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f)
            )
            VisualCartButton(
                text = "View in Cart",
                onClick = onOpenFullCart,
                variant = ButtonVariant.PRIMARY,
                icon = VisualCartIcons.Cart,
                modifier = Modifier.weight(1.2f)
            )
        }
    }
}

@Composable
private fun ShareNeedsQueryContent(
    state: ShareCaptureUiState.NeedsQuery,
    onSubmitQuery: (String) -> Unit,
    onRetryRecognition: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var query by remember(state.suggestedQuery) { mutableStateOf(state.suggestedQuery) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.thumbnail != null) {
            Image(
                bitmap = state.thumbnail.asImageBitmap(),
                contentDescription = "Captured product preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, ForestGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            text = if (state.rateLimited) "Free AI Limit Reached" else "Identify This Product",
            style = VisualCartTypography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = if (state.rateLimited) Terracotta else DeepInk
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.message,
            style = VisualCartTypography.bodySmall.copy(
                color = MutedSlate,
                lineHeight = 16.sp
            )
        )

        if (state.candidates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "SUGGESTED CANDIDATES",
                    style = VisualCartTypography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.candidates.take(3).forEach { candidate ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(ForestGreenLight)
                                .border(1.dp, ForestGreen.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .clickable { onSubmitQuery(candidate.searchQuery) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = candidate.title,
                                style = VisualCartTypography.labelSmall.copy(
                                    color = ForestGreen,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (!state.detectedCategory.isNullOrBlank() &&
            !state.detectedCategory.equals("Unidentified", ignoreCase = true)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(StoneLight)
                    .clickable { onSubmitQuery(state.detectedCategory) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Search category: ${state.detectedCategory}",
                    style = VisualCartTypography.labelSmall.copy(color = DeepInk)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Product name or keywords") },
            placeholder = { Text("e.g. PVC Plastic Pipes, Sony headphones") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (query.isNotBlank()) onSubmitQuery(query)
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ForestGreen,
                focusedLabelColor = ForestGreen,
                cursorColor = ForestGreen
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VisualCartButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.SECONDARY,
                modifier = Modifier.weight(0.8f)
            )
            if (state.canRetry) {
                VisualCartButton(
                    text = "Try Again",
                    onClick = onRetryRecognition,
                    variant = ButtonVariant.SECONDARY,
                    modifier = Modifier.weight(0.9f)
                )
            }
            VisualCartButton(
                text = "Search Stores",
                onClick = { if (query.isNotBlank()) onSubmitQuery(query) },
                variant = ButtonVariant.PRIMARY,
                icon = Icons.Default.Search,
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun ShareErrorContent(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Could Not Identify Product",
            style = VisualCartTypography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Terracotta
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
        )
        Spacer(modifier = Modifier.height(16.dp))
        VisualCartButton(
            text = "Close",
            onClick = onDismiss,
            variant = ButtonVariant.SECONDARY,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
