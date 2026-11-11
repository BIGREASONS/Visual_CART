package com.paypal.visualcart.ui.checkout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paypal.visualcart.core.designsystem.ButtonVariant
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.ForestGreen
import com.paypal.visualcart.core.designsystem.ForestGreenLight
import com.paypal.visualcart.core.designsystem.MerchantLogoBadge
import com.paypal.visualcart.core.designsystem.MutedSlate
import com.paypal.visualcart.core.designsystem.PureWhite
import com.paypal.visualcart.core.designsystem.Stone
import com.paypal.visualcart.core.designsystem.StoneLight
import com.paypal.visualcart.core.designsystem.Terracotta
import com.paypal.visualcart.core.designsystem.TerracottaLight
import com.paypal.visualcart.core.designsystem.VisualCartButton
import com.paypal.visualcart.core.designsystem.VisualCartCard
import com.paypal.visualcart.core.designsystem.VisualCartIcons
import com.paypal.visualcart.core.designsystem.VisualCartTypography
import com.paypal.visualcart.core.designsystem.WarmIvory
import com.paypal.visualcart.feature.payment.PayPalCheckoutCoordinator
import com.paypal.visualcart.feature.payment.PayPalCheckoutUiState

/**
 * Editorial Fintech PayPal Sandbox Checkout Sheet.
 * Displays:
 * 1. Server-authoritative breakdown (Merchant, Product, Quantity, Subtotal, Tax, Shipping, Final Total, Currency)
 * 2. Developer Sandbox Indicator
 * 3. Primary Approval Action: "Continue to PayPal Sandbox" (Chrome Custom Tabs)
 * 4. Loading State: "Verifying payment…"
 * 5. Successful State: "Payment completed" with confirmed PayPal Capture ID and verified amount
 * 6. Failure State: "Payment could not be completed" with user-friendly error message & retry options
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayPalCheckoutBottomSheet(
    coordinator: PayPalCheckoutCoordinator = PayPalCheckoutCoordinator.instance,
    onDismiss: () -> Unit = { coordinator.dismiss() },
    modifier: Modifier = Modifier
) {
    val uiState by coordinator.uiState.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (uiState is PayPalCheckoutUiState.Idle) return

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
            // Sheet Header with Sandbox Indicator & Close Button
            CheckoutHeader(
                onClose = onDismiss,
                isLiveSandbox = (uiState as? PayPalCheckoutUiState.OrderReady)?.isLiveSandbox
                    ?: (uiState as? PayPalCheckoutUiState.Success)?.isLiveSandbox ?: true
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (val state = uiState) {
                is PayPalCheckoutUiState.Idle -> Unit

                is PayPalCheckoutUiState.Initializing -> {
                    CheckoutInitializingView(
                        productTitle = state.productTitle,
                        merchantName = state.merchantName
                    )
                }

                is PayPalCheckoutUiState.OrderReady -> {
                    CheckoutOrderReadyView(
                        state = state,
                        onContinueToSandbox = { coordinator.continueToPayPalSandbox(context) },
                        onCancel = onDismiss
                    )
                }

                is PayPalCheckoutUiState.Verifying -> {
                    CheckoutVerifyingView(
                        orderId = state.orderId,
                        message = state.message
                    )
                }

                is PayPalCheckoutUiState.Success -> {
                    CheckoutSuccessView(
                        state = state,
                        onDone = onDismiss
                    )
                }

                is PayPalCheckoutUiState.Failure -> {
                    val originalListing = state.originalListing
                    val canConvertToUsd = state.errorCode == "CURRENCY_NOT_SUPPORTED" &&
                        originalListing?.currency?.equals("INR", ignoreCase = true) == true
                    CheckoutFailureView(
                        state = state,
                        onRetry = { coordinator.retry(context) },
                        onCheckoutInUsd = if (canConvertToUsd && originalListing != null) {
                            { coordinator.checkoutInUsdExplicitly(context, originalListing) }
                        } else {
                            null
                        },
                        onCancel = onDismiss
                    )
                }

                is PayPalCheckoutUiState.Cancelled -> {
                    CheckoutCancelledView(
                        state = state,
                        onRetry = { coordinator.retry(context) },
                        onDismiss = onDismiss
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CheckoutHeader(
    onClose: () -> Unit,
    isLiveSandbox: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = VisualCartIcons.ShieldCheck,
                contentDescription = null,
                tint = ForestGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "PayPal Checkout",
                style = VisualCartTypography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeepInk
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            SandboxIndicatorBadge(isLiveSandbox = isLiveSandbox)
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(PureWhite)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = DeepInk,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SandboxIndicatorBadge(isLiveSandbox: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFFFFF3CD),
        border = BorderStroke(1.dp, Color(0xFFFFEEBA))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF856404))
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = if (isLiveSandbox) "PAYPAL SANDBOX" else "SANDBOX SIMULATION",
                style = VisualCartTypography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF856404),
                    letterSpacing = 0.6.sp
                )
            )
        }
    }
}

@Composable
private fun CheckoutInitializingView(
    productTitle: String,
    merchantName: String
) {
    VisualCartCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = PureWhite,
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                color = ForestGreen,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Preparing Order Breakdown…",
                style = VisualCartTypography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeepInk
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Calculating authoritative taxes and delivery via $merchantName.",
                style = VisualCartTypography.bodySmall.copy(
                    color = MutedSlate,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/**
 * Displays server-authoritative breakdown:
 * Merchant, Product, Quantity, Subtotal, Applicable Tax, Shipping, Final Total, Currency.
 * Primary button: "Continue to PayPal Sandbox".
 */
@Composable
private fun CheckoutOrderReadyView(
    state: PayPalCheckoutUiState.OrderReady,
    onContinueToSandbox: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VisualCartCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = PureWhite,
            contentPadding = PaddingValues(18.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Merchant & Quantity Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MerchantLogoBadge(merchantName = state.merchantName)

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StoneLight
                    ) {
                        Text(
                            text = "Qty: ${state.quantity}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepInk
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Product Title
                Text(
                    text = state.productTitle,
                    style = VisualCartTypography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk,
                        lineHeight = 20.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Stone, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Itemized Financial Rows (Server-Authoritative)
                BreakdownRow(label = "Subtotal", value = state.formattedSubtotal)
                Spacer(modifier = Modifier.height(8.dp))
                BreakdownRow(label = "Applicable Tax (GST @ 18%)", value = state.formattedTax)
                Spacer(modifier = Modifier.height(8.dp))
                BreakdownRow(
                    label = "Shipping & Handling",
                    value = state.formattedShipping,
                    valueColor = if (state.shippingFee == 0.0) ForestGreen else DeepInk
                )
                Spacer(modifier = Modifier.height(8.dp))
                BreakdownRow(label = "Currency", value = state.currency)

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Stone, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Final Total Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "FINAL TOTAL",
                            style = VisualCartTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MutedSlate,
                                letterSpacing = 0.8.sp
                            )
                        )
                        Text(
                            text = "Guaranteed with Buyer Protection",
                            style = VisualCartTypography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = ForestGreen
                            )
                        )
                    }

                    Text(
                        text = state.formattedTotal,
                        style = VisualCartTypography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ForestGreen
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Security / Buyer Protection reassurance
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = VisualCartIcons.ShieldCheck,
                contentDescription = null,
                tint = ForestGreen,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Safe sandbox purchase. Zero payment credentials stored on device.",
                style = VisualCartTypography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = MutedSlate
                )
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Primary Approval Button: "Continue to PayPal Sandbox"
        VisualCartButton(
            text = if (state.isAwaitingApproval) "Awaiting Approval in Browser…" else "Continue to PayPal Sandbox",
            onClick = onContinueToSandbox,
            variant = ButtonVariant.PRIMARY,
            icon = VisualCartIcons.ShieldCheck,
            loading = state.isAwaitingApproval,
            enabled = !state.isAwaitingApproval,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        VisualCartButton(
            text = "Cancel",
            onClick = onCancel,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Loading state: "Verifying payment…"
 */
@Composable
private fun CheckoutVerifyingView(
    orderId: String,
    message: String
) {
    VisualCartCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = PureWhite,
        contentPadding = PaddingValues(28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                color = ForestGreen,
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(44.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = message,
                style = VisualCartTypography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeepInk
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Contacting PayPal Sandbox to verify order $orderId and capture authorized funds.",
                style = VisualCartTypography.bodySmall.copy(
                    color = MutedSlate,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = StoneLight
            ) {
                Text(
                    text = "Please keep Visual Cart open while confirming settlement…",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = VisualCartTypography.labelSmall.copy(
                        color = MutedSlate,
                        fontSize = 10.sp
                    )
                )
            }
        }
    }
}

/**
 * Successful state: "Payment completed"
 * Displays real PayPal transaction/capture ID, verified amount, and currency.
 */
@Composable
private fun CheckoutSuccessView(
    state: PayPalCheckoutUiState.Success,
    onDone: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VisualCartCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = PureWhite,
            borderColor = ForestGreen.copy(alpha = 0.6f),
            borderWidth = 1.5.dp,
            contentPadding = PaddingValues(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success Green Badge
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(ForestGreenLight)
                        .border(1.5.dp, ForestGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Success",
                        tint = ForestGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Payment completed",
                    style = VisualCartTypography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = ForestGreen
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "PayPal Sandbox transaction captured successfully",
                    style = VisualCartTypography.bodySmall.copy(
                        color = MutedSlate,
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = Stone, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Transaction Verification Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StoneLight,
                    border = BorderStroke(1.dp, Stone),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        BreakdownRow(
                            label = "PayPal Capture ID",
                            value = state.captureId,
                            isMonospace = true,
                            valueColor = ForestGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BreakdownRow(
                            label = "Order ID",
                            value = state.orderId,
                            isMonospace = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BreakdownRow(
                            label = "Transaction ID",
                            value = state.transactionId,
                            isMonospace = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BreakdownRow(
                            label = "Verified Amount",
                            value = "${state.formattedAmount} ${state.currency}",
                            valueColor = DeepInk
                        )
                        if (!state.payerEmail.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            BreakdownRow(label = "Payer Email", value = state.payerEmail)
                        }
                        if (!state.payerName.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            BreakdownRow(label = "Payer Name", value = state.payerName)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        BreakdownRow(label = "Status", value = "COMPLETED", valueColor = ForestGreen)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Covered under PayPal Buyer Protection. Check developer.paypal.com dashboard to verify sandbox funds.",
                    style = VisualCartTypography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = MutedSlate,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        VisualCartButton(
            text = "Done",
            onClick = onDone,
            variant = ButtonVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Failure state: "Payment could not be completed"
 * Displays user-friendly error details and retry options.
 */
@Composable
private fun CheckoutFailureView(
    state: PayPalCheckoutUiState.Failure,
    onRetry: () -> Unit,
    onCheckoutInUsd: (() -> Unit)? = null,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VisualCartCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = PureWhite,
            borderColor = Terracotta.copy(alpha = 0.5f),
            borderWidth = 1.5.dp,
            contentPadding = PaddingValues(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TerracottaLight)
                        .border(1.5.dp, Terracotta, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Failed",
                        tint = Terracotta,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Payment could not be completed",
                    style = VisualCartTypography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Terracotta
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TerracottaLight.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, Terracotta.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.errorMessage,
                        modifier = Modifier.padding(12.dp),
                        style = VisualCartTypography.bodySmall.copy(
                            color = DeepInk,
                            lineHeight = 18.sp
                        )
                    )
                }

                if (!state.orderId.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Order Reference: ${state.orderId}",
                        style = VisualCartTypography.labelSmall.copy(
                            color = MutedSlate,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (onCheckoutInUsd != null && state.originalListing != null) {
            val previewUsd = kotlin.math.max(
                0.01,
                Math.round(state.originalListing.price * PayPalCheckoutCoordinator.DEFAULT_INR_TO_USD_RATE * 100.0) / 100.0
            )
            VisualCartButton(
                text = "Checkout in USD (~${PayPalCheckoutCoordinator.formatAmount(previewUsd, "USD")} USD)",
                onClick = onCheckoutInUsd,
                variant = ButtonVariant.PRIMARY,
                icon = VisualCartIcons.ShieldCheck,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.canRetry) {
            VisualCartButton(
                text = "Retry Payment",
                onClick = onRetry,
                variant = if (onCheckoutInUsd != null) ButtonVariant.SECONDARY else ButtonVariant.PRIMARY,
                icon = Icons.Default.Refresh,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        VisualCartButton(
            text = "Cancel",
            onClick = onCancel,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Cancelled state.
 */
@Composable
private fun CheckoutCancelledView(
    state: PayPalCheckoutUiState.Cancelled,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VisualCartCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = PureWhite,
            contentPadding = PaddingValues(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(StoneLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancelled",
                        tint = MutedSlate,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Checkout Cancelled",
                    style = VisualCartTypography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeepInk
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = state.message,
                    style = VisualCartTypography.bodySmall.copy(
                        color = MutedSlate,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        VisualCartButton(
            text = "Try Again",
            onClick = onRetry,
            variant = ButtonVariant.PRIMARY,
            icon = Icons.Default.Refresh,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        VisualCartButton(
            text = "Close",
            onClick = onDismiss,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: String,
    valueColor: Color = DeepInk,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = VisualCartTypography.bodySmall.copy(
                color = MutedSlate
            )
        )

        Text(
            text = value,
            style = VisualCartTypography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
            )
        )
    }
}
