package com.paypal.visualcart.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke Editorial Fintech Icons for Visual Cart.
 * Crafted using crisp geometric vectors without relying on heavy external icon bundles.
 */
object VisualCartIcons {

    val Cart: ImageVector by lazy {
        ImageVector.Builder(
            name = "VisualCart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Cart basket outline
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 4f)
                horizontalLineTo(5.2f)
                lineTo(7.6f, 15f)
                horizontalLineTo(18.5f)
                lineTo(20.5f, 7f)
                horizontalLineTo(6f)
            }
            // Left wheel
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 18.5f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
                close()
            }
            // Right wheel
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 18.5f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
                close()
            }
        }.build()
    }

    val QuickSettingsTile: ImageVector by lazy {
        ImageVector.Builder(
            name = "QuickSettingsTile",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
                verticalLineTo(19f)
                curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                close()
            }
            // Lightning / Fast shortcut bolt inside
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(13f, 6.5f)
                lineTo(9f, 13f)
                horizontalLineTo(14f)
                lineTo(11f, 18f)
            }
        }.build()
    }

    val FloatingBubble: ImageVector by lazy {
        ImageVector.Builder(
            name = "FloatingBubble",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Rounded bubble
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 2f)
                curveTo(6.5f, 2f, 2f, 6.5f, 2f, 12f)
                curveTo(2f, 14.5f, 2.9f, 16.8f, 4.4f, 18.5f)
                lineTo(3f, 22f)
                lineTo(6.8f, 20.8f)
                curveTo(8.4f, 21.6f, 10.1f, 22f, 12f, 22f)
                curveTo(17.5f, 22f, 22f, 17.5f, 22f, 12f)
                curveTo(22f, 6.5f, 17.5f, 2f, 12f, 2f)
                close()
            }
            // Crosshair dot in middle
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.5f, 12f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
                close()
            }
        }.build()
    }

    val ScanCamera: ImageVector by lazy {
        ImageVector.Builder(
            name = "ScanCamera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Corner reticles
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Top Left
                moveTo(8f, 4f)
                horizontalLineTo(5f)
                verticalLineTo(7f)
                // Top Right
                moveTo(16f, 4f)
                horizontalLineTo(19f)
                verticalLineTo(7f)
                // Bottom Left
                moveTo(5f, 17f)
                verticalLineTo(20f)
                horizontalLineTo(8f)
                // Bottom Right
                moveTo(19f, 17f)
                verticalLineTo(20f)
                horizontalLineTo(16f)
            }
            // Center camera circle
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f
            ) {
                moveTo(9f, 12f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                close()
            }
        }.build()
    }

    val SparkleMatch: ImageVector by lazy {
        ImageVector.Builder(
            name = "SparkleMatch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                curveTo(12f, 7.5f, 16.5f, 12f, 22f, 12f)
                curveTo(16.5f, 12f, 12f, 16.5f, 12f, 22f)
                curveTo(12f, 16.5f, 7.5f, 12f, 2f, 12f)
                curveTo(7.5f, 12f, 12f, 7.5f, 12f, 2f)
                close()
            }
        }.build()
    }

    val PriceDropArrow: ImageVector by lazy {
        ImageVector.Builder(
            name = "PriceDropArrow",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 4f)
                verticalLineTo(19f)
                moveTo(6f, 13f)
                lineTo(12f, 19f)
                lineTo(18f, 13f)
            }
        }.build()
    }

    val CompareArrows: ImageVector by lazy {
        ImageVector.Builder(
            name = "CompareArrows",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Arrow pointing right
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 8f)
                horizontalLineTo(19f)
                moveTo(15f, 4f)
                lineTo(19f, 8f)
                lineTo(15f, 12f)
            }
            // Arrow pointing left
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 16f)
                horizontalLineTo(5f)
                moveTo(9f, 12f)
                lineTo(5f, 16f)
                lineTo(9f, 20f)
            }
        }.build()
    }

    val ShieldCheck: ImageVector by lazy {
        ImageVector.Builder(
            name = "ShieldCheck",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f)
                lineTo(20f, 6.5f)
                verticalLineTo(11.5f)
                curveTo(20f, 16.5f, 16.5f, 20.5f, 12f, 21.8f)
                curveTo(7.5f, 20.5f, 4f, 16.5f, 4f, 11.5f)
                verticalLineTo(6.5f)
                lineTo(12f, 3f)
                close()
            }
            // Checkmark
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8.5f, 12f)
                lineTo(11f, 14.5f)
                lineTo(15.5f, 9.5f)
            }
        }.build()
    }
}
