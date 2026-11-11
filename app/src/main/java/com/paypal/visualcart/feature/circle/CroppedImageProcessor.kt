package com.paypal.visualcart.feature.circle

import android.graphics.Bitmap
import com.paypal.visualcart.core.util.SafeImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.roundToInt

data class CroppedCaptureArtifact(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long
)

sealed interface CropProcessingResult {
    data class Success(val artifact: CroppedCaptureArtifact) : CropProcessingResult
    data class Failure(
        val reason: String,
        val isBlankOrProtectedFrame: Boolean = false
    ) : CropProcessingResult
}

/**
 * Processes local MediaProjection RGBA screen frames for Circle to Shop:
 * converts stride-padded buffers, crops strictly inside screen bounds, immediately recycles
 * full-screen frames, detects blank/FLAG_SECURE captures, normalizes recognition dimensions,
 * writes neutral UUID cache files, and verifies image magic bytes via [SafeImageDecoder].
 */
object CroppedImageProcessor {

    private const val SHARED_CAPTURES_DIR = "shared_captures"
    private const val MAX_UNCOMPRESSED_PNG_BYTES = 500 * 1024
    private const val JPEG_FALLBACK_QUALITY = 92
    private const val MAX_UPSCALE_FACTOR = 3.0
    private const val UNIFORM_CHANNEL_DELTA_THRESHOLD = 3
    private const val SAMPLE_GRID_SIZE = 16

    /**
     * Returns true when [pixels] is empty, completely transparent, or has near-zero ARGB
     * variance across all samples (characteristic of FLAG_SECURE black/blank buffers).
     */
    fun areSampledArgbPixelsUniform(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true

        var minA = 255
        var maxA = 0
        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0

        for (argb in pixels) {
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            if (a < minA) minA = a
            if (a > maxA) maxA = a
            if (r < minR) minR = r
            if (r > maxR) maxR = r
            if (g < minG) minG = g
            if (g > maxG) maxG = g
            if (b < minB) minB = b
            if (b > maxB) maxB = b
        }

        if (maxA <= UNIFORM_CHANNEL_DELTA_THRESHOLD) return true

        return (maxR - minR) <= UNIFORM_CHANNEL_DELTA_THRESHOLD &&
            (maxG - minG) <= UNIFORM_CHANNEL_DELTA_THRESHOLD &&
            (maxB - minB) <= UNIFORM_CHANNEL_DELTA_THRESHOLD &&
            (maxA - minA) <= UNIFORM_CHANNEL_DELTA_THRESHOLD
    }

    /**
     * Computes target recognition dimensions preserving aspect ratio:
     * downscales oversized crops so long edge <= [maxLongEdgePx], and gently upscales
     * small crops when short edge < [minShortEdgePx] (capped at <= 3x and <= [maxLongEdgePx]).
     */
    fun computeTargetRecognitionSize(
        cropWidth: Int,
        cropHeight: Int,
        minShortEdgePx: Int = 256,
        maxLongEdgePx: Int = 1024
    ): Pair<Int, Int> {
        if (cropWidth <= 0 || cropHeight <= 0) {
            return Pair(cropWidth.coerceAtLeast(1), cropHeight.coerceAtLeast(1))
        }

        val safeMaxLong = maxLongEdgePx.coerceAtLeast(1)
        val safeMinShort = minShortEdgePx.coerceIn(1, safeMaxLong)
        val shortEdge = minOf(cropWidth, cropHeight)
        val longEdge = maxOf(cropWidth, cropHeight)

        val scale = when {
            longEdge > safeMaxLong -> safeMaxLong.toDouble() / longEdge.toDouble()
            shortEdge < safeMinShort -> {
                val desiredUpscale = safeMinShort.toDouble() / shortEdge.toDouble()
                val maxByLongEdge = safeMaxLong.toDouble() / longEdge.toDouble()
                minOf(desiredUpscale, MAX_UPSCALE_FACTOR, maxByLongEdge).coerceAtLeast(1.0)
            }
            else -> 1.0
        }

        if (scale == 1.0) {
            return Pair(cropWidth, cropHeight)
        }

        val targetW = (cropWidth * scale).roundToInt().coerceIn(1, safeMaxLong)
        val targetH = (cropHeight * scale).roundToInt().coerceIn(1, safeMaxLong)
        return Pair(targetW, targetH)
    }

    /**
     * Copies an RGBA_8888 [ByteBuffer] plane into a tight [Bitmap] of [width] x [height],
     * stripping per-row stride padding so right-edge stride artifacts cannot occur.
     */
    fun convertRgbaPlaneToBitmap(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): Bitmap? {
        if (width <= 0 || height <= 0 || pixelStride < 4) return null
        val rowPadding = rowStride - pixelStride * width
        if (rowPadding < 0) return null

        val tightRowBytesLong = width.toLong() * 4L
        val totalTightBytesLong = tightRowBytesLong * height.toLong()
        if (totalTightBytesLong <= 0L || totalTightBytesLong > Int.MAX_VALUE) return null

        val dup = buffer.duplicate().apply { rewind() }
        val minRequiredBytes = (height.toLong() - 1L) * rowStride.toLong() +
            (width.toLong() - 1L) * pixelStride.toLong() + 4L
        if (dup.remaining().toLong() < minRequiredBytes) {
            return null
        }

        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val tightRowBytes = tightRowBytesLong.toInt()
            if (pixelStride == 4 && rowPadding == 0) {
                dup.limit(dup.position() + totalTightBytesLong.toInt())
                bitmap.copyPixelsFromBuffer(dup)
            } else {
                val tightBytes = ByteArray(totalTightBytesLong.toInt())
                if (pixelStride == 4) {
                    for (y in 0 until height) {
                        dup.position(y * rowStride)
                        dup.get(tightBytes, y * tightRowBytes, tightRowBytes)
                    }
                } else {
                    for (y in 0 until height) {
                        val rowBase = y * rowStride
                        var dstOffset = y * tightRowBytes
                        for (x in 0 until width) {
                            dup.position(rowBase + x * pixelStride)
                            dup.get(tightBytes, dstOffset, 4)
                            dstOffset += 4
                        }
                    }
                }
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(tightBytes))
            }
            bitmap
        } catch (_: Throwable) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    /**
     * Samples a grid of points across [bitmap] and detects uniform blank or FLAG_SECURE frames.
     */
    fun isLikelyBlankOrProtectedBitmap(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return true

        val cols = minOf(SAMPLE_GRID_SIZE, bitmap.width)
        val rows = minOf(SAMPLE_GRID_SIZE, bitmap.height)
        val samples = IntArray(cols * rows)
        var index = 0

        for (row in 0 until rows) {
            val y = (((row + 0.5f) / rows) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            for (col in 0 until cols) {
                val x = (((col + 0.5f) / cols) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                samples[index++] = bitmap.getPixel(x, y)
            }
        }
        return areSampledArgbPixelsUniform(samples)
    }

    /**
     * Extracts [cropRect] from [fullFrameBitmap], immediately discards [fullFrameBitmap] when
     * [recycleFullFrame] is true, validates non-blank content, resizes for recognition, writes
     * a neutral UUID image under `shared_captures/`, and verifies magic bytes with [SafeImageDecoder].
     */
    fun cropSaveAndDiscardFullFrame(
        fullFrameBitmap: Bitmap,
        cropRect: PixelCropRect,
        cacheRootDir: File,
        recycleFullFrame: Boolean = true
    ): CropProcessingResult {
        if (fullFrameBitmap.isRecycled || fullFrameBitmap.width <= 0 || fullFrameBitmap.height <= 0) {
            return CropProcessingResult.Failure(
                reason = "Screen frame was empty or already released.",
                isBlankOrProtectedFrame = true
            )
        }

        val clampedLeft = cropRect.left.coerceIn(0, fullFrameBitmap.width)
        val clampedTop = cropRect.top.coerceIn(0, fullFrameBitmap.height)
        val clampedRight = cropRect.right.coerceIn(0, fullFrameBitmap.width)
        val clampedBottom = cropRect.bottom.coerceIn(0, fullFrameBitmap.height)
        val cropWidth = clampedRight - clampedLeft
        val cropHeight = clampedBottom - clampedTop

        var cropped: Bitmap? = null
        try {
            if (cropWidth <= 0 || cropHeight <= 0) {
                return CropProcessingResult.Failure("Selected region is empty or outside screen bounds.")
            }
            cropped = Bitmap.createBitmap(fullFrameBitmap, clampedLeft, clampedTop, cropWidth, cropHeight)
        } catch (e: Throwable) {
            return CropProcessingResult.Failure("Failed to crop screen region: ${e.message ?: "invalid bounds"}")
        } finally {
            if (recycleFullFrame && !fullFrameBitmap.isRecycled && cropped !== fullFrameBitmap) {
                fullFrameBitmap.recycle()
            }
        }

        val safeCropped: Bitmap = cropped

        if (isLikelyBlankOrProtectedBitmap(safeCropped)) {
            if (!safeCropped.isRecycled && (recycleFullFrame || safeCropped !== fullFrameBitmap)) {
                safeCropped.recycle()
            }
            return CropProcessingResult.Failure(
                reason = "Protected or blank screen frame (FLAG_SECURE screens cannot be captured).",
                isBlankOrProtectedFrame = true
            )
        }

        val (targetW, targetH) = computeTargetRecognitionSize(safeCropped.width, safeCropped.height)
        val finalBitmap = if (targetW != safeCropped.width || targetH != safeCropped.height) {
            val scaled = try {
                Bitmap.createScaledBitmap(safeCropped, targetW, targetH, true)
            } catch (_: Throwable) {
                safeCropped
            }
            if (scaled !== safeCropped && !safeCropped.isRecycled && (recycleFullFrame || safeCropped !== fullFrameBitmap)) {
                safeCropped.recycle()
            }
            scaled
        } else {
            safeCropped
        }

        return try {
            val encoded = encodeForRecognition(finalBitmap)
                ?: return CropProcessingResult.Failure("Failed to encode cropped product image.")

            val captureDir = File(cacheRootDir, SHARED_CAPTURES_DIR).apply { mkdirs() }
            SafeImageDecoder.pruneCache(captureDir)

            val outputFile = File(captureDir, "vc_circle_${UUID.randomUUID()}.${encoded.extension}")
            FileOutputStream(outputFile).use { out ->
                out.write(encoded.bytes)
                out.flush()
            }

            val detectedMime = readAndVerifyMimeType(outputFile)
            if (detectedMime == null || !outputFile.exists() || outputFile.length() <= 0L) {
                outputFile.delete()
                return CropProcessingResult.Failure("Cropped image failed MIME verification.")
            }

            CropProcessingResult.Success(
                CroppedCaptureArtifact(
                    file = outputFile,
                    mimeType = detectedMime,
                    width = finalBitmap.width,
                    height = finalBitmap.height,
                    byteSize = outputFile.length()
                )
            )
        } catch (e: Throwable) {
            CropProcessingResult.Failure("Failed to persist cropped image: ${e.message ?: "I/O error"}")
        } finally {
            if (!finalBitmap.isRecycled && (recycleFullFrame || finalBitmap !== fullFrameBitmap)) {
                finalBitmap.recycle()
            }
        }
    }

    private data class EncodedImage(val bytes: ByteArray, val extension: String)

    private fun encodeForRecognition(bitmap: Bitmap): EncodedImage? {
        val pngBytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        }
        if (pngBytes.isEmpty()) return null

        if (pngBytes.size <= MAX_UNCOMPRESSED_PNG_BYTES) {
            return EncodedImage(bytes = pngBytes, extension = "png")
        }

        val jpgBytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_FALLBACK_QUALITY, out)) {
                return EncodedImage(bytes = pngBytes, extension = "png")
            }
            out.toByteArray()
        }

        return if (jpgBytes.isNotEmpty() && jpgBytes.size < pngBytes.size) {
            EncodedImage(bytes = jpgBytes, extension = "jpg")
        } else {
            EncodedImage(bytes = pngBytes, extension = "png")
        }
    }

    private fun readAndVerifyMimeType(file: File): String? {
        val header = ByteArray(64)
        val readCount = file.inputStream().use { input -> input.read(header) }
        if (readCount <= 0) return null
        return SafeImageDecoder.detectMimeTypeFromBytes(header.copyOf(readCount))
    }
}
