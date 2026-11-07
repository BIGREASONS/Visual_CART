package com.paypal.visualcart.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Editorial Fintech Shapes
 * 16-22dp rounded corners for tactile, premium cards and modals,
 * aligned to an 8dp spatial rhythm.
 */
val VisualCartShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),  // Standard product card
    large = RoundedCornerShape(22.dp),   // Featured cards and bottom sheets
    extraLarge = RoundedCornerShape(28.dp) // Floating bubble and pill tags
)

object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}
