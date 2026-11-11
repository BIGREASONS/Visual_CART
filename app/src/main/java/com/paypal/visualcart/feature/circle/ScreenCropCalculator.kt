package com.paypal.visualcart.feature.circle

import kotlin.math.roundToInt

/**
 * Integer pixel crop rectangle inside a captured screen frame buffer.
 */
data class PixelCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Pure-Kotlin coordinate mapper translating overlay selection bounds into
 * pixel coordinates within a captured MediaProjection frame buffer.
 */
object ScreenCropCalculator {

    private const val MIN_MAPPED_EDGE_PX = 2

    fun mapOverlaySelectionToFrameRect(
        selectionInOverlayPx: NormalizedRectF,
        overlayWidthPx: Int,
        overlayHeightPx: Int,
        frameWidthPx: Int,
        frameHeightPx: Int,
        overlayOffsetXPx: Int = 0,
        overlayOffsetYPx: Int = 0,
        displayWidthPx: Int = overlayWidthPx + overlayOffsetXPx,
        displayHeightPx: Int = overlayHeightPx + overlayOffsetYPx
    ): PixelCropRect? {
        if (overlayWidthPx <= 0 ||
            overlayHeightPx <= 0 ||
            frameWidthPx <= 0 ||
            frameHeightPx <= 0 ||
            displayWidthPx <= 0 ||
            displayHeightPx <= 0
        ) {
            return null
        }

        if (!selectionInOverlayPx.left.isFinite() ||
            !selectionInOverlayPx.top.isFinite() ||
            !selectionInOverlayPx.right.isFinite() ||
            !selectionInOverlayPx.bottom.isFinite()
        ) {
            return null
        }

        val overlayW = overlayWidthPx.toDouble()
        val overlayH = overlayHeightPx.toDouble()
        val displayW = displayWidthPx.toDouble()
        val displayH = displayHeightPx.toDouble()

        val selLeft = selectionInOverlayPx.left.toDouble().coerceIn(0.0, overlayW)
        val selTop = selectionInOverlayPx.top.toDouble().coerceIn(0.0, overlayH)
        val selRight = selectionInOverlayPx.right.toDouble().coerceIn(0.0, overlayW)
        val selBottom = selectionInOverlayPx.bottom.toDouble().coerceIn(0.0, overlayH)

        if (selRight <= selLeft || selBottom <= selTop) {
            return null
        }

        val displayLeft = (selLeft + overlayOffsetXPx).coerceIn(0.0, displayW)
        val displayTop = (selTop + overlayOffsetYPx).coerceIn(0.0, displayH)
        val displayRight = (selRight + overlayOffsetXPx).coerceIn(0.0, displayW)
        val displayBottom = (selBottom + overlayOffsetYPx).coerceIn(0.0, displayH)

        val scaleX = frameWidthPx.toDouble() / displayW
        val scaleY = frameHeightPx.toDouble() / displayH

        val scaledWidth = (displayRight - displayLeft) * scaleX
        val scaledHeight = (displayBottom - displayTop) * scaleY
        if (scaledWidth < MIN_MAPPED_EDGE_PX || scaledHeight < MIN_MAPPED_EDGE_PX) {
            return null
        }

        val mappedLeft = (displayLeft * scaleX).roundToInt().coerceIn(0, frameWidthPx)
        val mappedTop = (displayTop * scaleY).roundToInt().coerceIn(0, frameHeightPx)
        val mappedRight = (displayRight * scaleX).roundToInt().coerceIn(0, frameWidthPx)
        val mappedBottom = (displayBottom * scaleY).roundToInt().coerceIn(0, frameHeightPx)

        if (mappedRight - mappedLeft < MIN_MAPPED_EDGE_PX ||
            mappedBottom - mappedTop < MIN_MAPPED_EDGE_PX
        ) {
            return null
        }

        return PixelCropRect(
            left = mappedLeft,
            top = mappedTop,
            right = mappedRight,
            bottom = mappedBottom
        )
    }
}
