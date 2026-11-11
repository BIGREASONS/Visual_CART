package com.paypal.visualcart.feature.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM unit tests for Circle to Shop gesture validation, density-aware bounding box
 * calculation, multi-resolution/orientation/inset screen crop coordinate mapping,
 * blank / FLAG_SECURE uniform frame rejection, and AI target recognition sizing.
 */
class CircleGestureAndCropTest {

    private fun generateEllipseStroke(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float = radiusX,
        pointCount: Int = 48,
        startAngleDeg: Float = 0f,
        sweepDeg: Float = 360f,
        jitterPx: Float = 0f
    ): List<TouchPoint> {
        return (0 until pointCount).map { i ->
            val progress = if (pointCount == 1) 0f else i.toFloat() / (pointCount - 1).toFloat()
            val angleRad = Math.toRadians((startAngleDeg + progress * sweepDeg).toDouble())
            val jitter = if (jitterPx == 0f) 0f else ((i % 3) - 1) * jitterPx
            TouchPoint(
                x = centerX + ((radiusX + jitter) * cos(angleRad)).toFloat(),
                y = centerY + ((radiusY - jitter) * sin(angleRad)).toFloat(),
                timestampMs = i * 16L
            )
        }
    }

    private fun generateLinearSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        pointCount: Int = 32,
        wobblePx: Float = 1.5f
    ): List<TouchPoint> {
        return (0 until pointCount).map { i ->
            val t = i.toFloat() / (pointCount - 1).toFloat()
            val wobble = ((i % 2) * 2 - 1) * wobblePx
            TouchPoint(
                x = startX + (endX - startX) * t,
                y = startY + (endY - startY) * t + wobble,
                timestampMs = i * 12L
            )
        }
    }

    @Test
    fun testValidHandDrawnFullCircleRecognized() {
        val stroke = generateEllipseStroke(
            centerX = 540f,
            centerY = 1200f,
            radiusX = 180f,
            radiusY = 180f,
            pointCount = 48,
            sweepDeg = 360f,
            jitterPx = 3f
        )

        val result = CircleGestureAnalyzer.analyze(
            points = stroke,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2400,
            density = 2f,
            paddingDp = 8f
        )

        assertTrue("Expected ValidCircle for hand-drawn circle, got $result", result is GestureAnalysisResult.ValidCircle)
        val valid = result as GestureAnalysisResult.ValidCircle
        assertEquals(48, valid.pointCount)
        assertTrue("Path length should reflect full circle circumference", valid.pathLengthPx > 950f)
        assertTrue("Angular sweep should cover most of circle", valid.angularSweepDegrees >= 270f)
        assertTrue("Bounding box left should be near 344", valid.boundingBox.left in 330f..365f)
        assertTrue("Bounding box right should be near 736", valid.boundingBox.right in 715f..750f)
        assertTrue("Bounding box top should be near 1004", valid.boundingBox.top in 990f..1025f)
        assertTrue("Bounding box bottom should be near 1396", valid.boundingBox.bottom in 1375f..1410f)
        assertTrue(valid.boundingBox.width > 340f)
        assertTrue(valid.boundingBox.height > 340f)
    }

    @Test
    fun testValidTallOvalLassoAroundDressRecognized() {
        // Tall vertical lasso around a dress on portrait FHD+ screen
        val dressLasso = generateEllipseStroke(
            centerX = 540f,
            centerY = 1260f,
            radiusX = 120f,
            radiusY = 360f,
            pointCount = 56,
            startAngleDeg = -90f,
            sweepDeg = 350f,
            jitterPx = 2f
        )

        val result = CircleGestureAnalyzer.analyze(
            points = dressLasso,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2400,
            density = 3f,
            paddingDp = 8f
        )

        assertTrue("Expected ValidCircle for tall dress oval lasso, got $result", result is GestureAnalysisResult.ValidCircle)
        val valid = result as GestureAnalysisResult.ValidCircle
        assertTrue(
            "Tall oval lasso must preserve vertical proportions (${valid.boundingBox.height} vs ${valid.boundingBox.width})",
            valid.boundingBox.height > valid.boundingBox.width * 2.0f
        )
        assertTrue(valid.angularSweepDegrees >= 240f)
    }

    @Test
    fun testEmptyAndTooFewPointsRejected() {
        val emptyResult = CircleGestureAnalyzer.analyze(emptyList(), 1080, 2400)
        assertTrue(emptyResult is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.EMPTY_OR_TOO_FEW_POINTS,
            (emptyResult as GestureAnalysisResult.InvalidSelection).reason
        )
        assertTrue(emptyResult.message.isNotBlank())

        val singleTap = CircleGestureAnalyzer.analyze(
            listOf(TouchPoint(540f, 1200f, 0L)),
            1080,
            2400
        )
        assertTrue(singleTap is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.EMPTY_OR_TOO_FEW_POINTS,
            (singleTap as GestureAnalysisResult.InvalidSelection).reason
        )

        val twoPoints = CircleGestureAnalyzer.analyze(
            listOf(TouchPoint(540f, 1200f, 0L), TouchPoint(545f, 1205f, 16L)),
            1080,
            2400
        )
        assertTrue(twoPoints is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.EMPTY_OR_TOO_FEW_POINTS,
            (twoPoints as GestureAnalysisResult.InvalidSelection).reason
        )
    }

    @Test
    fun testTapMicroStrokeRejectedAsStrokeTooShort() {
        val microTap = generateEllipseStroke(
            centerX = 500f,
            centerY = 1000f,
            radiusX = 2f,
            radiusY = 2f,
            pointCount = 10
        )

        val result = CircleGestureAnalyzer.analyze(microTap, 1080, 2400, density = 3f)
        assertTrue("Expected InvalidSelection for tap micro-stroke, got $result", result is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.STROKE_TOO_SHORT,
            (result as GestureAnalysisResult.InvalidSelection).reason
        )
    }

    @Test
    fun testTooSmallEnclosedCircleRejected() {
        // Long enough stroke length by looping slightly, or small radius box below minimum crop threshold
        val tinyCircle = generateEllipseStroke(
            centerX = 500f,
            centerY = 1000f,
            radiusX = 14f,
            radiusY = 14f,
            pointCount = 32,
            sweepDeg = 720f
        )

        val result = CircleGestureAnalyzer.analyze(tinyCircle, 1080, 2400, density = 1f, paddingDp = 0f)
        assertTrue("Expected InvalidSelection for tiny 28x28 selection, got $result", result is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.TOO_SMALL,
            (result as GestureAnalysisResult.InvalidSelection).reason
        )
    }

    @Test
    fun testStraightHorizontalAndVerticalSwipesRejectedAsNotEnclosingArea() {
        val horizontalSwipe = generateLinearSwipe(
            startX = 100f,
            startY = 1100f,
            endX = 950f,
            endY = 1100f,
            pointCount = 36,
            wobblePx = 2f
        )
        val horizontalResult = CircleGestureAnalyzer.analyze(horizontalSwipe, 1080, 2400, density = 2f)
        assertTrue(
            "Straight horizontal swipe must be rejected, got $horizontalResult",
            horizontalResult is GestureAnalysisResult.InvalidSelection
        )
        assertEquals(
            InvalidSelectionReason.NOT_ENCLOSING_AREA,
            (horizontalResult as GestureAnalysisResult.InvalidSelection).reason
        )

        val verticalSwipe = generateLinearSwipe(
            startX = 540f,
            startY = 300f,
            endX = 540f,
            endY = 1800f,
            pointCount = 36,
            wobblePx = 2f
        )
        val verticalResult = CircleGestureAnalyzer.analyze(verticalSwipe, 1080, 2400, density = 2f)
        assertTrue(
            "Straight vertical swipe must be rejected, got $verticalResult",
            verticalResult is GestureAnalysisResult.InvalidSelection
        )
        assertEquals(
            InvalidSelectionReason.NOT_ENCLOSING_AREA,
            (verticalResult as GestureAnalysisResult.InvalidSelection).reason
        )

        val diagonalSwipe = generateLinearSwipe(
            startX = 120f,
            startY = 400f,
            endX = 920f,
            endY = 1700f,
            pointCount = 36,
            wobblePx = 2f
        )
        val diagonalResult = CircleGestureAnalyzer.analyze(diagonalSwipe, 1080, 2400, density = 2f)
        assertTrue(
            "Diagonal swipe must be rejected, got $diagonalResult",
            diagonalResult is GestureAnalysisResult.InvalidSelection
        )
        assertEquals(
            InvalidSelectionReason.NOT_ENCLOSING_AREA,
            (diagonalResult as GestureAnalysisResult.InvalidSelection).reason
        )
    }

    @Test
    fun testNonFinitePointsAndInvalidSurfaceDimensionsHandledSafely() {
        val nanStroke = listOf(
            TouchPoint(Float.NaN, 100f),
            TouchPoint(200f, Float.POSITIVE_INFINITY),
            TouchPoint(300f, 300f),
            TouchPoint(350f, 350f)
        )
        val nanResult = CircleGestureAnalyzer.analyze(nanStroke, 1080, 2400)
        assertTrue(nanResult is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.EMPTY_OR_TOO_FEW_POINTS,
            (nanResult as GestureAnalysisResult.InvalidSelection).reason
        )

        val validCircle = generateEllipseStroke(540f, 1200f, 150f, 150f, pointCount = 36)
        val zeroSurfaceResult = CircleGestureAnalyzer.analyze(validCircle, 0, 2400)
        assertTrue(zeroSurfaceResult is GestureAnalysisResult.InvalidSelection)
        assertEquals(
            InvalidSelectionReason.TOO_SMALL,
            (zeroSurfaceResult as GestureAnalysisResult.InvalidSelection).reason
        )
    }

    @Test
    fun testBoundingBoxDensityPaddingAndBoundaryClamping() {
        val centerCircle = generateEllipseStroke(
            centerX = 540f,
            centerY = 1200f,
            radiusX = 150f,
            radiusY = 150f,
            pointCount = 48
        )

        val noPadding = CircleGestureAnalyzer.analyze(
            points = centerCircle,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2400,
            density = 1f,
            paddingDp = 0f
        ) as GestureAnalysisResult.ValidCircle

        val densePadding = CircleGestureAnalyzer.analyze(
            points = centerCircle,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2400,
            density = 3f,
            paddingDp = 8f // 24px on each edge
        ) as GestureAnalysisResult.ValidCircle

        assertEquals(noPadding.boundingBox.left - 24f, densePadding.boundingBox.left, 0.5f)
        assertEquals(noPadding.boundingBox.top - 24f, densePadding.boundingBox.top, 0.5f)
        assertEquals(noPadding.boundingBox.right + 24f, densePadding.boundingBox.right, 0.5f)
        assertEquals(noPadding.boundingBox.bottom + 24f, densePadding.boundingBox.bottom, 0.5f)

        // Circle extending beyond top-left and bottom-right boundaries must clamp strictly to surface edges
        val topLeftEdgeCircle = generateEllipseStroke(
            centerX = 40f,
            centerY = 40f,
            radiusX = 80f,
            radiusY = 80f,
            pointCount = 40
        )
        val clampedTopLeft = CircleGestureAnalyzer.analyze(
            points = topLeftEdgeCircle,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2400,
            density = 3f,
            paddingDp = 12f
        ) as GestureAnalysisResult.ValidCircle
        assertEquals(0f, clampedTopLeft.boundingBox.left, 0.001f)
        assertEquals(0f, clampedTopLeft.boundingBox.top, 0.001f)

        val bottomRightEdgeCircle = generateEllipseStroke(
            centerX = 1040f,
            centerY = 2360f,
            radiusX = 80f,
            radiusY = 80f,
            pointCount = 40
        )
        val clampedBottomRight = CircleGestureAnalyzer.analyze(
            points = bottomRightEdgeCircle,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2400,
            density = 3f,
            paddingDp = 12f
        ) as GestureAnalysisResult.ValidCircle
        assertEquals(1080f, clampedBottomRight.boundingBox.right, 0.001f)
        assertEquals(2400f, clampedBottomRight.boundingBox.bottom, 0.001f)
    }

    @Test
    fun testCropCoordinateMappingPortraitFhdQhdAndScaledBuffer() {
        val selectionFhd = NormalizedRectF(left = 200f, top = 400f, right = 800f, bottom = 1200f)

        // 1. Portrait FHD+ 1080x2400 1:1 frame
        val fhdCrop = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = selectionFhd,
            overlayWidthPx = 1080,
            overlayHeightPx = 2400,
            frameWidthPx = 1080,
            frameHeightPx = 2400
        )
        assertNotNull(fhdCrop)
        assertEquals(PixelCropRect(200, 400, 800, 1200), fhdCrop)
        assertEquals(600, fhdCrop!!.width)
        assertEquals(800, fhdCrop.height)

        // 2. Portrait QHD+ 1440x3120 1:1 frame
        val selectionQhd = NormalizedRectF(left = 300f, top = 600f, right = 1140f, bottom = 2100f)
        val qhdCrop = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = selectionQhd,
            overlayWidthPx = 1440,
            overlayHeightPx = 3120,
            frameWidthPx = 1440,
            frameHeightPx = 3120
        )
        assertEquals(PixelCropRect(300, 600, 1140, 2100), qhdCrop)

        // 3. Scaled virtual display capture buffer (0.5x: 540x1200)
        val scaledCrop = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = selectionFhd,
            overlayWidthPx = 1080,
            overlayHeightPx = 2400,
            frameWidthPx = 540,
            frameHeightPx = 1200
        )
        assertEquals(PixelCropRect(100, 200, 400, 600), scaledCrop)
        assertEquals(300, scaledCrop!!.width)
        assertEquals(400, scaledCrop.height)
    }

    @Test
    fun testCropCoordinateMappingLandscapeAndInsetOffsets() {
        // 1. Landscape 2400x1080 -> 2400x1080 and 1200x540
        val landscapeSelection = NormalizedRectF(left = 400f, top = 160f, right = 1400f, bottom = 920f)
        val landscapeFull = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = landscapeSelection,
            overlayWidthPx = 2400,
            overlayHeightPx = 1080,
            frameWidthPx = 2400,
            frameHeightPx = 1080
        )
        assertEquals(PixelCropRect(400, 160, 1400, 920), landscapeFull)

        val landscapeHalf = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = landscapeSelection,
            overlayWidthPx = 2400,
            overlayHeightPx = 1080,
            frameWidthPx = 1200,
            frameHeightPx = 540
        )
        assertEquals(PixelCropRect(200, 80, 700, 460), landscapeHalf)

        // 2. Portrait with status bar (96px top) and navigation bar (124px bottom) insets
        // Overlay covers y=[96..2276] of 1080x2400 display; frame is full 1080x2400 or scaled 540x1200
        val insetSelection = NormalizedRectF(left = 100f, top = 200f, right = 700f, bottom = 1000f)
        val fullWithInset = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = insetSelection,
            overlayWidthPx = 1080,
            overlayHeightPx = 2180,
            frameWidthPx = 1080,
            frameHeightPx = 2400,
            overlayOffsetXPx = 0,
            overlayOffsetYPx = 96,
            displayWidthPx = 1080,
            displayHeightPx = 2400
        )
        assertEquals(PixelCropRect(100, 296, 700, 1096), fullWithInset)

        val scaledWithInset = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = insetSelection,
            overlayWidthPx = 1080,
            overlayHeightPx = 2180,
            frameWidthPx = 540,
            frameHeightPx = 1200,
            overlayOffsetXPx = 0,
            overlayOffsetYPx = 96,
            displayWidthPx = 1080,
            displayHeightPx = 2400
        )
        assertEquals(PixelCropRect(50, 148, 350, 548), scaledWithInset)

        // 3. Landscape with horizontal camera cutout offset (88px left inset)
        val cutoutSelection = NormalizedRectF(left = 200f, top = 100f, right = 800f, bottom = 700f)
        val landscapeWithCutout = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = cutoutSelection,
            overlayWidthPx = 2224,
            overlayHeightPx = 1080,
            frameWidthPx = 2400,
            frameHeightPx = 1080,
            overlayOffsetXPx = 88,
            overlayOffsetYPx = 0,
            displayWidthPx = 2400,
            displayHeightPx = 1080
        )
        assertEquals(PixelCropRect(288, 100, 888, 700), landscapeWithCutout)
    }

    @Test
    fun testScreenCropCalculatorInvalidDimensionsAndEmptySelectionsReturnNull() {
        val validSelection = NormalizedRectF(100f, 100f, 500f, 500f)

        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                validSelection, overlayWidthPx = 1080, overlayHeightPx = 2400, frameWidthPx = 0, frameHeightPx = 2400
            )
        )
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                validSelection, overlayWidthPx = 1080, overlayHeightPx = 2400, frameWidthPx = 1080, frameHeightPx = -10
            )
        )
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                validSelection, overlayWidthPx = 0, overlayHeightPx = 2400, frameWidthPx = 1080, frameHeightPx = 2400
            )
        )
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                validSelection, overlayWidthPx = 1080, overlayHeightPx = 0, frameWidthPx = 1080, frameHeightPx = 2400
            )
        )
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                validSelection,
                overlayWidthPx = 1080,
                overlayHeightPx = 2400,
                frameWidthPx = 1080,
                frameHeightPx = 2400,
                displayWidthPx = 0,
                displayHeightPx = 2400
            )
        )

        // Zero-area or inverted selections must safely return null
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                NormalizedRectF(300f, 300f, 300f, 600f),
                overlayWidthPx = 1080,
                overlayHeightPx = 2400,
                frameWidthPx = 1080,
                frameHeightPx = 2400
            )
        )
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                NormalizedRectF(500f, 500f, 200f, 800f),
                overlayWidthPx = 1080,
                overlayHeightPx = 2400,
                frameWidthPx = 1080,
                frameHeightPx = 2400
            )
        )
        assertNull(
            ScreenCropCalculator.mapOverlaySelectionToFrameRect(
                NormalizedRectF(Float.NaN, 100f, 500f, 500f),
                overlayWidthPx = 1080,
                overlayHeightPx = 2400,
                frameWidthPx = 1080,
                frameHeightPx = 2400
            )
        )
    }

    @Test
    fun testUniformAndFlagSecureBlankPixelFrameRejection() {
        // Empty pixel array -> treated as blank/uniform
        assertTrue(CroppedImageProcessor.areSampledArgbPixelsUniform(IntArray(0)))

        // All-black frame (e.g., FLAG_SECURE protected banking/DRM surface)
        val allBlack = IntArray(64) { 0xFF000000.toInt() }
        assertTrue(CroppedImageProcessor.areSampledArgbPixelsUniform(allBlack))

        // All-transparent blank frame (uninitialized ImageReader buffer)
        val allTransparent = IntArray(64) { 0x00000000 }
        assertTrue(CroppedImageProcessor.areSampledArgbPixelsUniform(allTransparent))

        // Solid white / solid slate uniform canvas
        val allWhite = IntArray(64) { 0xFFFFFFFF.toInt() }
        assertTrue(CroppedImageProcessor.areSampledArgbPixelsUniform(allWhite))

        // Near-uniform 1-bit compression noise (channel delta <= 3) still rejected as blank
        val nearUniformDark = IntArray(64) { idx ->
            if (idx % 2 == 0) 0xFF080808.toInt() else 0xFF0A0A0A.toInt()
        }
        assertTrue(CroppedImageProcessor.areSampledArgbPixelsUniform(nearUniformDark))

        // Subtle real contrast edge (>3 channel delta) is recognized as non-uniform content
        val subtleEdge = IntArray(64) { idx ->
            if (idx < 32) 0xFF101010.toInt() else 0xFF282828.toInt()
        }
        assertFalse(CroppedImageProcessor.areSampledArgbPixelsUniform(subtleEdge))

        // Varied product image pixels (foreground product + background contrast)
        val productPixels = IntArray(64) { idx ->
            when {
                idx % 4 == 0 -> 0xFFF5F5F5.toInt() // light background
                idx % 4 == 1 -> 0xFF1A237E.toInt() // navy shoe/dress accent
                idx % 4 == 2 -> 0xFFD84315.toInt() // warm product detail
                else -> 0xFF212121.toInt() // dark shadow/label
            }
        }
        assertFalse(CroppedImageProcessor.areSampledArgbPixelsUniform(productPixels))
    }

    @Test
    fun testTargetRecognitionSizingUpscalesTinyCropsAndDownscalesLargeCrops() {
        // 1. Small sharp crop (100x150) -> upscaled cleanly so short edge reaches 256 while preserving 2:3 ratio
        val upscaledToMinShortEdge = CroppedImageProcessor.computeTargetRecognitionSize(
            cropWidth = 100,
            cropHeight = 150,
            minShortEdgePx = 256,
            maxLongEdgePx = 1024
        )
        assertEquals(256, upscaledToMinShortEdge.first)
        assertEquals(384, upscaledToMinShortEdge.second)

        // 2. Very tiny crop (80x120) -> upscaling is capped at 3x (240x360) preserving 2:3 ratio without over-blurring
        val cappedUpscale = CroppedImageProcessor.computeTargetRecognitionSize(
            cropWidth = 80,
            cropHeight = 120,
            minShortEdgePx = 256,
            maxLongEdgePx = 1024
        )
        assertEquals(240, cappedUpscale.first)
        assertEquals(360, cappedUpscale.second)

        // 3. Crop already within [256, 1024] bounds stays unchanged
        val unchanged = CroppedImageProcessor.computeTargetRecognitionSize(
            cropWidth = 420,
            cropHeight = 640,
            minShortEdgePx = 256,
            maxLongEdgePx = 1024
        )
        assertEquals(Pair(420, 640), unchanged)

        // 4. Oversized portrait crop (2048x3072) -> downscaled so long edge <= 1024 preserving 2:3 ratio
        val downscaledPortrait = CroppedImageProcessor.computeTargetRecognitionSize(
            cropWidth = 2048,
            cropHeight = 3072,
            minShortEdgePx = 256,
            maxLongEdgePx = 1024
        )
        assertEquals(1024, downscaledPortrait.second)
        assertEquals(683, downscaledPortrait.first)

        // 5. Oversized landscape crop (2400x1200) -> downscaled to 1024x512
        val downscaledLandscape = CroppedImageProcessor.computeTargetRecognitionSize(
            cropWidth = 2400,
            cropHeight = 1200,
            minShortEdgePx = 256,
            maxLongEdgePx = 1024
        )
        assertEquals(Pair(1024, 512), downscaledLandscape)

        // 6. Extreme narrow crop (100x800): upscaling capped by maxLongEdgePx=1024 -> 128x1024
        val extremeNarrow = CroppedImageProcessor.computeTargetRecognitionSize(
            cropWidth = 100,
            cropHeight = 800,
            minShortEdgePx = 256,
            maxLongEdgePx = 1024
        )
        assertEquals(Pair(128, 1024), extremeNarrow)

        // 7. Zero / negative crop sizes clamp safely without throwing
        assertEquals(Pair(1, 1), CroppedImageProcessor.computeTargetRecognitionSize(0, -10))
    }
}
