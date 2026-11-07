package com.paypal.visualcart.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * Safely copies and decodes incoming shared images without reading raw filesystem paths directly,
 * preventing URI permission revocation issues, disk space exhaustion, and OutOfMemory errors.
 *
 * Implements:
 * - Content URI safety checks (rejection of file:, data:, path traversal)
 * - Strict stream copy bounds (protection against disk exhaustion DoS)
 * - Automatic cache expiration and eviction policy (TTL + maximum file count)
 * - Safe thumbnail downsampling to prevent OOM
 */
object SafeImageDecoder {

    const val DEFAULT_MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    const val DEFAULT_MAX_CACHE_FILES = 20
    const val DEFAULT_MAX_IMAGE_BYTES = 25 * 1024 * 1024L // 25 MB max image capture limit

    /**
     * Copies an incoming content URI to application-private cache directory.
     * Validates content URI scheme and authority, handles revoked permissions gracefully,
     * bounds total stream copy size to prevent disk exhaustion, and automatically prunes expired cache files.
     */
    fun copyUriToPrivateCache(
        context: Context,
        uri: Uri,
        maxByteSize: Long = DEFAULT_MAX_IMAGE_BYTES
    ): File? {
        val uriStr = uri.toString()
        if (!SecurityUtils.isValidContentUri(uriStr)) {
            return null
        }

        return try {
            val cacheDir = File(context.cacheDir, "shared_captures").apply { mkdirs() }

            // Prune expired or excess files to prevent unbounded storage growth
            pruneCache(cacheDir, DEFAULT_MAX_CACHE_AGE_MS, DEFAULT_MAX_CACHE_FILES)

            val destFile = File(cacheDir, "capture_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
            var bytesCopied = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        bytesCopied += read
                        if (bytesCopied > maxByteSize) {
                            // File exceeds safe limit: abort and delete
                            destFile.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            if (destFile.exists() && destFile.length() > 0) destFile else null
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

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

        // 1. Delete files exceeding maximum age
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

        // 2. If file count still exceeds maxFiles, remove oldest files first
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
     * Decodes a memory-efficient downsampled thumbnail from a file without loading full raw resolution.
     */
    fun decodeThumbnail(file: File, maxDimension: Int = 1024): Bitmap? {
        return try {
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
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (_: Throwable) {
            null
        }
    }
}
