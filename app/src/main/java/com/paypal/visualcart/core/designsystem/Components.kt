package com.paypal.visualcart.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paypal.visualcart.domain.model.MatchType

enum class ButtonVariant {
    PRIMARY,
    SECONDARY,
    TERRACOTTA,
    GHOST
}

/**
 * VisualCartButton:
 * Primary: Forest Green background, Pure White text.
 * Secondary: Pure White / Warm Ivory with subtle Stone border, Deep Ink text.
 * Terracotta: High-emphasis price drop / checkout simulation.
 * Ghost: Understated text-only action.
 */
@Composable
fun VisualCartButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    shape: RoundedCornerShape = RoundedCornerShape(12.dp)
) {
    when (variant) {
        ButtonVariant.PRIMARY -> {
            Button(
                onClick = onClick,
                modifier = modifier.defaultMinSize(minHeight = 46.dp),
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForestGreen,
                    contentColor = PureWhite,
                    disabledContainerColor = MutedSage.copy(alpha = 0.4f),
                    disabledContentColor = PureWhite.copy(alpha = 0.7f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(text, icon, trailingIcon, loading, PureWhite)
            }
        }
        ButtonVariant.SECONDARY -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.defaultMinSize(minHeight = 46.dp),
                enabled = enabled && !loading,
                shape = shape,
                border = BorderStroke(1.dp, Stone),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = PureWhite,
                    contentColor = DeepInk,
                    disabledContainerColor = WarmIvory,
                    disabledContentColor = MutedSlate
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(text, icon, trailingIcon, loading, DeepInk)
            }
        }
        ButtonVariant.TERRACOTTA -> {
            Button(
                onClick = onClick,
                modifier = modifier.defaultMinSize(minHeight = 46.dp),
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Terracotta,
                    contentColor = PureWhite,
                    disabledContainerColor = Terracotta.copy(alpha = 0.4f),
                    disabledContentColor = PureWhite.copy(alpha = 0.7f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(text, icon, trailingIcon, loading, PureWhite)
            }
        }
        ButtonVariant.GHOST -> {
            TextButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled && !loading,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = ForestGreen,
                    disabledContentColor = MutedSlate
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(text, icon, trailingIcon, loading, ForestGreen)
            }
        }
    }
}

@Composable
private fun ButtonInnerContent(
    text: String,
    icon: ImageVector?,
    trailingIcon: ImageVector?,
    loading: Boolean,
    tint: Color
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = tint
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = VisualCartTypography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * VisualCartCard:
 * Pure white card surface, subtle stone border, 16dp rounded corners, editorial depth.
 */
@Composable
fun VisualCartCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = Stone.copy(alpha = 0.75f),
    borderWidth: Dp = 1.dp,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clip(shape).clickable(onClick = onClick)
                } else Modifier
            ),
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(borderWidth, borderColor),
        shadowElevation = 0.dp
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/**
 * DemoDataBadge:
 * Small subtle tag "DEMO DATA" so hackathon test data is clearly identified
 * without breaking the editorial fintech aesthetic.
 */
@Composable
fun DemoDataBadge(
    modifier: Modifier = Modifier,
    text: String = "DEMO DATA"
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = StoneLight,
        border = BorderStroke(1.dp, Stone)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Terracotta)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = VisualCartTypography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedSlate
                )
            )
        }
    }
}

/**
 * LiveInferenceBadge:
 * Editorial badge "LIVE AI" indicating real-time multimodal inference.
 */
@Composable
fun LiveInferenceBadge(
    modifier: Modifier = Modifier,
    text: String = "LIVE AI"
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = ForestGreenLight,
        border = BorderStroke(1.dp, ForestGreen.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(ForestGreen)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = VisualCartTypography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = ForestGreenDark
                )
            )
        }
    }
}

/**
 * ProductOriginBadge:
 * Renders either LiveInferenceBadge or DemoDataBadge based on product provenance.
 */
@Composable
fun ProductOriginBadge(
    isDemoFixture: Boolean,
    isLiveInference: Boolean = !isDemoFixture,
    modifier: Modifier = Modifier
) {
    when {
        isLiveInference -> LiveInferenceBadge(modifier = modifier)
        isDemoFixture -> DemoDataBadge(modifier = modifier)
    }
}

/**
 * ConfidenceIndicator:
 * Editorial pill showing "Exact match 94%", "Likely match 82%", or "Similar alternative".
 */
@Composable
fun ConfidenceIndicator(
    matchType: MatchType,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    val percent = (confidence * 100).toInt().coerceIn(0, 100)

    val (bg, textColor, label) = when (matchType) {
        MatchType.EXACT -> Triple(
            ForestGreenLight,
            ForestGreenDark,
            "Exact match $percent%"
        )
        MatchType.LIKELY -> Triple(
            SageLight,
            DeepInk,
            "Likely match $percent%"
        )
        MatchType.SIMILAR -> Triple(
            StoneLight,
            MutedSlate,
            "Similar alternative ${if (percent > 0) "$percent%" else ""}".trim()
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(0.5.dp, Stone.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor = when (matchType) {
                MatchType.EXACT -> ForestGreen
                MatchType.LIKELY -> Terracotta
                MatchType.SIMILAR -> MutedSlate
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = VisualCartTypography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            )
        }
    }
}

/**
 * SpecTag:
 * Structured pill for technical and product specifications (e.g. "1TB", "NVMe", "M.2").
 */
@Composable
fun SpecTag(
    spec: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = StoneLight,
        border = BorderStroke(0.5.dp, Stone)
    ) {
        Text(
            text = spec,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = VisualCartTypography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                color = DeepInk
            )
        )
    }
}

/**
 * MerchantLogoBadge:
 * Branded monogram or identifier for merchants (Amazon, Flipkart, Croma, etc.)
 */
@Composable
fun MerchantLogoBadge(
    merchantName: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val cleanName = merchantName.lowercase()
    val (brandColor, initials, displayName) = when {
        cleanName.contains("amazon") -> Triple(AmazonOrange, "AMZ", "Amazon India")
        cleanName.contains("flipkart") -> Triple(FlipkartBlue, "FK", "Flipkart")
        cleanName.contains("croma") -> Triple(ForestGreen, "CR", "Croma")
        cleanName.contains("reliance") -> Triple(RelianceRed, "RD", "Reliance Digital")
        cleanName.contains("myntra") -> Triple(Terracotta, "MYN", "Myntra")
        else -> Triple(DeepInk, "MKT", merchantName)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Stone)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(brandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials.take(1),
                    style = VisualCartTypography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = brandColor
                    )
                )
            }
            if (!compact) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayName,
                    style = VisualCartTypography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = DeepInk
                    )
                )
            }
        }
    }
}
