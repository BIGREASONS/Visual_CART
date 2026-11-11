package com.paypal.visualcart.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/**
 * Safely copies and decodes incoming shared images without reading raw filesystem paths directly,
 * preventing URI permission revocation issues, disk space exhaustion, and OutOfMemory errors.
 *
 * Implements:
 * - Content URI safety checks (rejection of file:, data:, path traversal)
 * - Strict stream copy bounds (protection against disk exhaustion DoS)
 * - Magic-byte MIME sniffing + ContentResolver fallback (never hardcodes .jpg on PNG/WebP/HEIC)
 * - Dimension & header validation + safe EXIF rotation in thumbnail decoding
 * - Neutral UUID cache naming so filenames never leak into product search queries
 * - Automatic cache expiration and eviction policy (TTL + maximum file count)
 */
object SafeImageDecoder {

    private const val TAG = "VisualCartCapture"
    const val DEFAULT_MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    const val DEFAULT_MAX_CACHE_FILES = 20
    const val DEFAULT_MAX_IMAGE_BYTES = 25 * 1024 * 1024L // 25 MB max image capture limit

    data class DecodedCaptureResult(
        val file: File,
        val mimeType: String,
        val byteSize: Long,
        val width: Int,
        val height: Int,
        val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        val wasExifNormalized: Boolean = false
    )

    /**
     * Copies an incoming content URI to application-private cache directory and validates
     * MIME signature, byte count, dimensions, and EXIF metadata.
     */
    fun copyUriToPrivateCacheDetailed(
        context: Context,
        uri: Uri,
        maxByteSize: Long = DEFAULT_MAX_IMAGE_BYTES
    ): DecodedCaptureResult? {
        val uriStr = uri.toString()
        if (!SecurityUtils.isValidContentUri(uriStr)) {
            safeLogWarn("Rejected unsafe content URI for image capture.")
            return null
        }

        val resolver = context.contentResolver
        val resolverMime = try {
            resolver.getType(uri)?.trim()?.lowercase()
        } catch (_: Throwable) {
            null
        }

        var tempFile: File? = null
        return try {
            val cacheDir = File(context.cacheDir, "shared_captures").apply { mkdirs() }
            pruneCache(cacheDir, DEFAULT_MAX_CACHE_AGE_MS, DEFAULT_MAX_CACHE_FILES)

            tempFile = File(cacheDir, "tmp_${UUID.randomUUID()}.bin")
            var bytesCopied = 0L
            val headerProbe = ByteArray(64)
            var headerLen = 0

            val openedStream = resolver.openInputStream(uri)
            if (openedStream == null) {
                tempFile.delete()
                safeLogWarn("ContentResolver returned null InputStream for shared URI.")
                return null
            }

            openedStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (headerLen < headerProbe.size) {
                            val toCopy = minOf(read, headerProbe.size - headerLen)
                            System.arraycopy(buffer, 0, headerProbe, headerLen, toCopy)
                            headerLen += toCopy
                        }
                        bytesCopied += read
                        if (bytesCopied > maxByteSize) {
                            tempFile?.delete()
                            safeLogWarn("Shared image exceeded maxByteSize ($bytesCopied > $maxByteSize).")
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                safeLogWarn("Shared image stream was empty (0 bytes).")
                return null
            }

            val detectedMime = resolveBestMimeType(
                header = headerProbe.copyOf(headerLen),
                resolverMime = resolverMime
            )
            if (detectedMime == null) {
                tempFile.delete()
                safeLogWarn("Shared payload is not a supported image MIME/signature (resolverMime=$resolverMime).")
                return null
            }

            // Probe image dimensions (BitmapFactory first on Android, pure header parser fallback)
            val rawHeaderBytes = tempFile.readBytes().let {
                if (it.size > 65_536) it.copyOf(65_536) else it
            }
            val dims = extractDimensions(tempFile, rawHeaderBytes)
            val width = dims?.first ?: 0
            val height = dims?.second ?: 0
            if (width <= 0 || height <= 0) {
                tempFile.delete()
                safeLogWarn("Failed to decode valid image dimensions from shared payload (mime=$detectedMime, bytes=$bytesCopied).")
                return null
            }

            val ext = extensionForMimeType(detectedMime)
            // Neutral UUID filename so cache filenames never masquerade as product search queries
            val finalFile = File(cacheDir, "vc_img_${UUID.randomUUID()}.$ext")
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            val orientation = readExifOrientation(finalFile)
            val rotatedSize = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            ) {
                Pair(height, width)
            } else {
                Pair(width, height)
            }

            safeLogInfo(
                "Captured image ready: mime=$detectedMime (resolver=${resolverMime ?: "none"}), " +
                    "bytes=$bytesCopied, dimensions=${rotatedSize.first}x${rotatedSize.second}, exifOrientation=$orientation"
            )

            DecodedCaptureResult(
                file = finalFile,
                mimeType = detectedMime,
                byteSize = bytesCopied,
                width = rotatedSize.first,
                height = rotatedSize.second,
                exifOrientation = orientation,
                wasExifNormalized = orientation != ExifInterface.ORIENTATION_NORMAL &&
                    orientation != ExifInterface.ORIENTATION_UNDEFINED
            )
        } catch (e: SecurityException) {
            tempFile?.delete()
            safeLogWarn("SecurityException reading shared URI: ${e.message}")
            null
        } catch (e: IOException) {
            tempFile?.delete()
            safeLogWarn("IOException reading shared URI: ${e.message}")
            null
        } catch (e: Exception) {
            tempFile?.delete()
            safeLogWarn("Unexpected error decoding shared URI: ${e.message}")
            null
        }
    }

    /**
     * Backwards-compatible wrapper returning only the sandboxed cache [File].
     */
    fun copyUriToPrivateCache(
        context: Context,
        uri: Uri,
        maxByteSize: Long = DEFAULT_MAX_IMAGE_BYTES
    ): File? = copyUriToPrivateCacheDetailed(context, uri, maxByteSize)?.file

    /**
     * Cleans up expired cache files older than [maxAgeMs] and ensures no more than [maxFiles] are retained.
     * Returns the number of pruned files.
     */
    fun cleanExpiredCache(
        context: Context,
        maxAgeMs: Long = DEFAULT_MAX_CACHE_AGE_MS,
        maxFiles: Int = DEFAULT_MAX_CACHE_FILES
    ): Int {
        val cacheDir = File(context.cacheDir, "shared_captures")
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0
        return pruneCache(cacheDir, maxAgeMs, maxFiles)
    }

    /**
     * Internal testable prune logic operating directly on a cache directory.
     * 1. Deletes any files older than [maxAgeMs].
     * 2. If remaining file count exceeds [maxFiles], deletes oldest files first.
     */
    fun pruneCache(
        cacheDir: File,
        maxAgeMs: Long = DEFAULT_MAX_CACHE_AGE_MS,
        maxFiles: Int = DEFAULT_MAX_CACHE_FILES,
        currentTime: Long = System.currentTimeMillis()
    ): Int {
        var deletedCount = 0
        val files = cacheDir.listFiles() ?: return 0

        val remaining = mutableListOf<File>()
        for (file in files) {
            val age = currentTime - file.lastModified()
            if (age > maxAgeMs) {
                if (file.delete()) {
                    deletedCount++
                }
            } else {
                remaining.add(file)
            }
        }

        if (remaining.size > maxFiles) {
            remaining.sortBy { it.lastModified() }
            val excessCount = remaining.size - maxFiles
            for (i in 0 until excessCount) {
                if (remaining[i].delete()) {
                    deletedCount++
                }
            }
        }

        return deletedCount
    }

    /**
     * Decodes a memory-efficient downsampled ARGB_8888 thumbnail with EXIF rotation applied.
     */
    fun decodeThumbnail(file: File, maxDimension: Int = 1024): Bitmap? {
        return try {
            if (!file.exists() || file.length() <= 0L) return null
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight
            if (width <= 0 || height <= 0) return null

            var inSampleSize = 1
            val maxEdge = max(width, height)
            while ((maxEdge / inSampleSize) > maxDimension) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            applyExifOrientation(decoded, readExifOrientation(file))
        } catch (_: Throwable) {
            null
        }
    }

    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (_: Throwable) {
            bitmap
        }
    }

    fun readExifOrientation(file: File): Int {
        return try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Detects MIME type strictly from image magic bytes first, falling back to a trusted
     * concrete image MIME subtype (not wildcard) when specific binary containers (e.g., HEIC) match.
     */
    fun detectMimeTypeFromBytes(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }

        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }

        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }

        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return "image/gif"
        }

        if (bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) {
            return "image/bmp"
        }

        // ISO Base Media File Format: HEIC / HEIF / AVIF
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            val brand = String(bytes, 8, 4, Charsets.US_ASCII).lowercase()
            return when (brand) {
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "image/heic"
                "avif", "avis" -> "image/avif"
                else -> null
            }
        }

        return null
    }

    fun resolveBestMimeType(header: ByteArray, resolverMime: String?): String? {
        val sniffed = detectMimeTypeFromBytes(header)
        if (sniffed != null) return sniffed
        if (resolverMime != null && resolverMime.startsWith("image/") && resolverMime != "image/*") {
            return when (resolverMime) {
                "image/jpg" -> "image/jpeg"
                "image/heif" -> "image/heic"
                else -> resolverMime
            }
        }
        return null
    }

    fun extensionForMimeType(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }
    }

    /**
     * Extracts image width x height using Android BitmapFactory when available, and falls back
     * cleanly to pure binary header parsing (PNG/JPEG/WebP/GIF) so dimensions are always verified
     * consistently across both device and JVM unit tests.
     */
    fun extractDimensions(file: File, bytesHint: ByteArray? = null): Pair<Int, Int>? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                return Pair(bounds.outWidth, bounds.outHeight)
            }
        } catch (_: Throwable) {
            // Fall through to binary parser
        }

        val data = bytesHint ?: try {
            file.readBytes()
        } catch (_: Throwable) {
            return null
        }
        return parseImageDimensionsFromBytes(data)
    }

    fun parseImageDimensionsFromBytes(bytes: ByteArray): Pair<Int, Int>? {
        val mime = detectMimeTypeFromBytes(bytes) ?: return null
        return when (mime) {
            "image/png" -> {
                if (bytes.size >= 24 &&
                    bytes[12] == 'I'.code.toByte() &&
                    bytes[13] == 'H'.code.toByte() &&
                    bytes[14] == 'D'.code.toByte() &&
                    bytes[15] == 'R'.code.toByte()
                ) {
                    val w = ((bytes[16].toInt() and 0xFF) shl 24) or
                        ((bytes[17].toInt() and 0xFF) shl 16) or
                        ((bytes[18].toInt() and 0xFF) shl 8) or
                        (bytes[19].toInt() and 0xFF)
                    val h = ((bytes[20].toInt() and 0xFF) shl 24) or
                        ((bytes[21].toInt() and 0xFF) shl 16) or
                        ((bytes[22].toInt() and 0xFF) shl 8) or
                        (bytes[23].toInt() and 0xFF)
                    if (w > 0 && h > 0) Pair(w, h) else null
                } else null
            }
            "image/gif" -> {
                if (bytes.size >= 10) {
                    val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
                    val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
                    if (w > 0 && h > 0) Pair(w, h) else null
                } else null
            }
            "image/jpeg" -> parseJpegDimensions(bytes)
            "image/webp" -> parseWebpDimensions(bytes)
            else -> null
        }
    }

    private fun parseJpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) break
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x00 || marker in 0xD0..0xD7) {
                continue
            }
            if (i + 1 >= bytes.size) break
            val segLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (segLen < 2 || i + segLen > bytes.size) break
            if (marker in 0xC0..0xC3 && marker != 0xC4 && segLen >= 7 && i + 6 < bytes.size) {
                val h = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                val w = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                if (w > 0 && h > 0) return Pair(w, h)
            }
            i += segLen
        }
        return null
    }

    private fun parseWebpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30) return null
        val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
        return when (chunk) {
            "VP8 " -> {
                val w = ((bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)) and 0x3FFF
                val h = ((bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)) and 0x3FFF
                if (w > 0 && h > 0) Pair(w, h) else null
            }
            "VP8L" -> {
                val b0 = bytes[21].toInt() and 0xFF
                val b1 = bytes[22].toInt() and 0xFF
                val b2 = bytes[23].toInt() and 0xFF
                val b3 = bytes[24].toInt() and 0xFF
                val w = 1 + (((b1 and 0x3F) shl 8) or b0)
                val h = 1 + (((b3 and 0x0F) shl 10) or (b2 shl 2) or ((b1 and 0xC0) shr 6))
                if (w > 0 && h > 0) Pair(w, h) else null
            }
            "VP8X" -> {
                val w = 1 + ((bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16))
                val h = 1 + ((bytes[27].toInt() and 0xFF) or ((bytes[28].toInt() and 0xFF) shl 8) or ((bytes[29].toInt() and 0xFF) shl 16))
                if (w > 0 && h > 0) Pair(w, h) else null
            }
            else -> null
        }
    }

    private fun safeLogInfo(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
            // Ignored on JVM unit tests
        }
    }

    private fun safeLogWarn(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Ignored on JVM unit tests
        }
    }
}
