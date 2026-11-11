package com.paypal.visualcart.feature.circle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Single pointer sample captured during a Circle to Shop stroke.
 */
data class TouchPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long = 0L
)

/**
 * Axis-aligned selection rectangle in overlay pixel coordinates.
 */
data class NormalizedRectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Reason a touch gesture was rejected before triggering screen capture.
 */
enum class InvalidSelectionReason {
    EMPTY_OR_TOO_FEW_POINTS,
    STROKE_TOO_SHORT,
    TOO_SMALL,
    NOT_ENCLOSING_AREA
}

/**
 * Outcome of evaluating a user stroke as a Circle to Shop selection gesture.
 */
sealed interface GestureAnalysisResult {
    data class ValidCircle(
        val boundingBox: NormalizedRectF,
        val pathLengthPx: Float,
        val angularSweepDegrees: Float,
        val pointCount: Int
    ) : GestureAnalysisResult

    data class InvalidSelection(
        val reason: InvalidSelectionReason,
        val message: String
    ) : GestureAnalysisResult
}

/**
 * Pure-Kotlin geometric recognizer that distinguishes intentional circles, ovals,
 * rounded boxes, and C-shaped lassos from taps, micro-strokes, and straight swipes.
 */
object CircleGestureAnalyzer {

    private const val MIN_POINTS = 6
    private const val MIN_STROKE_DP = 48f
    private const val MIN_BOX_DIM_DP = 36f
    private const val MIN_ENCLOSING_THICKNESS_DP = 28f
    private const val MIN_SWEEP_DEGREES = 135f
    private const val MIN_TANGENT_SWEEP_DEGREES = 105f
    private const val MIN_EXTREME_ASPECT_SWEEP_DEGREES = 200f
    private const val MIN_EXTREME_ASPECT_TANGENT_DEGREES = 150f
    private const val MAX_ENDPOINT_GAP_RATIO = 0.55f
    private const val EXTREME_ASPECT_RATIO = 10f
    private const val SWIPE_VS_TINY_ASPECT_RATIO = 3f
    private const val MIN_EFFECTIVE_RIBBON_THICKNESS_DP = 6.0
    private const val DENSE_SAMPLE_THRESHOLD = 16
    private const val RESAMPLE_STEP_DP = 4f
    private const val EPSILON = 1e-3f
    private const val RAD_TO_DEG = 180.0 / PI

    fun analyze(
        points: List<TouchPoint>,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        density: Float = 1f,
        paddingDp: Float = 8f
    ): GestureAnalysisResult {
        val validPoints = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (validPoints.size < MIN_POINTS) {
            return GestureAnalysisResult.InvalidSelection(
                reason = InvalidSelectionReason.EMPTY_OR_TOO_FEW_POINTS,
                message = "Draw a continuous circle around the item"
            )
        }

        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) {
            return GestureAnalysisResult.InvalidSelection(
                reason = InvalidSelectionReason.TOO_SMALL,
                message = "Selection surface is too small"
            )
        }

        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val safePaddingDp = if (paddingDp.isFinite() && paddingDp >= 0f) paddingDp else 0f
        val surfaceW = surfaceWidthPx.toFloat()
        val surfaceH = surfaceHeightPx.toFloat()

        val bounds = computeClampedBounds(validPoints, surfaceW, surfaceH)
        val pathLength = computePathLength(validPoints)
        if (pathLength < MIN_STROKE_DP * safeDensity) {
            return GestureAnalysisResult.InvalidSelection(
                reason = InvalidSelectionReason.STROKE_TOO_SHORT,
                message = "Draw a larger circle around the item"
            )
        }

        val rawWidth = bounds.width
        val rawHeight = bounds.height
        val minDim = min(rawWidth, rawHeight)
        val maxDim = max(rawWidth, rawHeight)

        if (maxDim < MIN_BOX_DIM_DP * safeDensity) {
            return GestureAnalysisResult.InvalidSelection(
                reason = InvalidSelectionReason.TOO_SMALL,
                message = "Selection area is too small — circle the whole item"
            )
        }

        val centerX = (bounds.left + bounds.right) * 0.5f
        val centerY = (bounds.top + bounds.bottom) * 0.5f
        val angularSweepDegrees = computeAngularSweepDegrees(validPoints, centerX, centerY)
        val tangentSweepDegrees = computeTangentSweepDegrees(validPoints, safeDensity, pathLength)
        val aspectRatio = if (minDim > EPSILON) maxDim / minDim else Float.POSITIVE_INFINITY

        if (minDim < MIN_ENCLOSING_THICKNESS_DP * safeDensity) {
            val isLinearSwipe = aspectRatio > SWIPE_VS_TINY_ASPECT_RATIO ||
                abs(angularSweepDegrees) < MIN_SWEEP_DEGREES
            return if (isLinearSwipe) {
                GestureAnalysisResult.InvalidSelection(
                    reason = InvalidSelectionReason.NOT_ENCLOSING_AREA,
                    message = "Loop around the item instead of swiping in a line"
                )
            } else {
                GestureAnalysisResult.InvalidSelection(
                    reason = InvalidSelectionReason.TOO_SMALL,
                    message = "Selection area is too small — circle the whole item"
                )
            }
        }

        if (rawWidth < MIN_BOX_DIM_DP * safeDensity || rawHeight < MIN_BOX_DIM_DP * safeDensity) {
            return GestureAnalysisResult.InvalidSelection(
                reason = InvalidSelectionReason.TOO_SMALL,
                message = "Selection area is too small — circle the whole item"
            )
        }

        val first = validPoints.first()
        val last = validPoints.last()
        val endpointGap = hypot(last.x - first.x, last.y - first.y)
        val enclosedArea = computeEnclosedArea(validPoints, centerX, centerY)
        val closedPerimeter = pathLength + endpointGap
        val effectiveThicknessDp = if (closedPerimeter > EPSILON) {
            (2.0 * enclosedArea / closedPerimeter) / safeDensity
        } else {
            0.0
        }

        val isOpenShallowStroke = (abs(angularSweepDegrees) < MIN_SWEEP_DEGREES ||
            abs(tangentSweepDegrees) < MIN_TANGENT_SWEEP_DEGREES) &&
            endpointGap > MAX_ENDPOINT_GAP_RATIO * maxDim

        val isExtremeFlatAspect = aspectRatio > EXTREME_ASPECT_RATIO &&
            (abs(angularSweepDegrees) < MIN_EXTREME_ASPECT_SWEEP_DEGREES ||
                abs(tangentSweepDegrees) < MIN_EXTREME_ASPECT_TANGENT_DEGREES)

        if (effectiveThicknessDp < MIN_EFFECTIVE_RIBBON_THICKNESS_DP ||
            isOpenShallowStroke ||
            isExtremeFlatAspect
        ) {
            return GestureAnalysisResult.InvalidSelection(
                reason = InvalidSelectionReason.NOT_ENCLOSING_AREA,
                message = "Loop all the way around the item to select it"
            )
        }

        val padPx = safePaddingDp * safeDensity
        val paddedBox = NormalizedRectF(
            left = (bounds.left - padPx).coerceIn(0f, surfaceW),
            top = (bounds.top - padPx).coerceIn(0f, surfaceH),
            right = (bounds.right + padPx).coerceIn(0f, surfaceW),
            bottom = (bounds.bottom + padPx).coerceIn(0f, surfaceH)
        )

        return GestureAnalysisResult.ValidCircle(
            boundingBox = paddedBox,
            pathLengthPx = pathLength,
            angularSweepDegrees = angularSweepDegrees,
            pointCount = validPoints.size
        )
    }

    private fun computeClampedBounds(
        points: List<TouchPoint>,
        surfaceW: Float,
        surfaceH: Float
    ): NormalizedRectF {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            val cx = p.x.coerceIn(0f, surfaceW)
            val cy = p.y.coerceIn(0f, surfaceH)
            if (cx < minX) minX = cx
            if (cy < minY) minY = cy
            if (cx > maxX) maxX = cx
            if (cy > maxY) maxY = cy
        }
        return NormalizedRectF(minX, minY, maxX, maxY)
    }

    private fun computePathLength(points: List<TouchPoint>): Float {
        var length = 0f
        for (i in 0 until points.lastIndex) {
            length += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        return length
    }

    private fun computeAngularSweepDegrees(
        points: List<TouchPoint>,
        centerX: Float,
        centerY: Float
    ): Float {
        var prevDx = 0f
        var prevDy = 0f
        var hasPrev = false
        var sweepRad = 0.0

        for (p in points) {
            val dx = p.x - centerX
            val dy = p.y - centerY
            if (hypot(dx, dy) < EPSILON) continue
            if (hasPrev) {
                val cross = prevDx.toDouble() * dy - prevDy.toDouble() * dx
                val dot = prevDx.toDouble() * dx + prevDy.toDouble() * dy
                sweepRad += atan2(cross, dot)
            }
            prevDx = dx
            prevDy = dy
            hasPrev = true
        }
        return (sweepRad * RAD_TO_DEG).toFloat()
    }

    private fun computeTangentSweepDegrees(
        points: List<TouchPoint>,
        density: Float,
        pathLength: Float
    ): Float {
        val sampled = downsampleForTangent(points, density, pathLength)
        var prevDx = 0f
        var prevDy = 0f
        var hasPrev = false
        var turnRad = 0.0

        for (i in 0 until sampled.lastIndex) {
            val dx = sampled[i + 1].x - sampled[i].x
            val dy = sampled[i + 1].y - sampled[i].y
            if (hypot(dx, dy) < EPSILON) continue
            if (hasPrev) {
                val cross = prevDx.toDouble() * dy - prevDy.toDouble() * dx
                val dot = prevDx.toDouble() * dx + prevDy.toDouble() * dy
                turnRad += atan2(cross, dot)
            }
            prevDx = dx
            prevDy = dy
            hasPrev = true
        }
        return (turnRad * RAD_TO_DEG).toFloat()
    }

    private fun downsampleForTangent(
        points: List<TouchPoint>,
        density: Float,
        pathLength: Float
    ): List<TouchPoint> {
        val minStep = if (points.size > DENSE_SAMPLE_THRESHOLD) {
            max(RESAMPLE_STEP_DP * density, pathLength * 0.025f)
        } else {
            EPSILON
        }
        val result = ArrayList<TouchPoint>(points.size)
        var lastKept = points.first()
        result.add(lastKept)
        for (i in 1 until points.lastIndex) {
            val candidate = points[i]
            if (hypot(candidate.x - lastKept.x, candidate.y - lastKept.y) >= minStep) {
                result.add(candidate)
                lastKept = candidate
            }
        }
        val tail = points.last()
        if (hypot(tail.x - lastKept.x, tail.y - lastKept.y) >= EPSILON) {
            result.add(tail)
        }
        return result
    }

    private fun computeEnclosedArea(
        points: List<TouchPoint>,
        originX: Float,
        originY: Float
    ): Double {
        var doubleSignedArea = 0.0
        for (i in points.indices) {
            val curr = points[i]
            val next = points[(i + 1) % points.size]
            val x1 = (curr.x - originX).toDouble()
            val y1 = (curr.y - originY).toDouble()
            val x2 = (next.x - originX).toDouble()
            val y2 = (next.y - originY).toDouble()
            doubleSignedArea += (x1 * y2) - (x2 * y1)
        }
        return abs(doubleSignedArea) * 0.5
    }
}
