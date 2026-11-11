package com.paypal.visualcart.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for SafeImageDecoder security safeguards:
 * 1. Content URI validation & path traversal rejection.
 * 2. Unsafe scheme filtering.
 * 3. Automatic cache pruning, expiration (TTL), and maximum file count bounds.
 * 4. Safe failure handling for corrupted or missing images.
 */
class SafeImageDecoderSecurityTest {

    @Test
    fun testContentUriValidation() {
        val validContentUris = listOf(
            "content://media/external/images/media/42",
            "content://com.android.providers.media.documents/document/image%3A100",
            "content://com.paypal.visualcart.provider/captures/screenshot1.jpg"
        )

        for (uri in validContentUris) {
            assertTrue("Expected valid content URI: $uri", SecurityUtils.isValidContentUri(uri))
        }

        val dangerousUris = listOf(
            "file:///sdcard/Download/private.txt",
            "file:///etc/passwd",
            "http://malicious.com/exploit.png",
            "https://evil.com/image.jpg",
            "content://media/../../sensitive.db",
            "content://com.example.provider/../secrets.xml",
            "javascript:alert(1)",
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==",
            null,
            ""
        )

        for (uri in dangerousUris) {
            assertFalse("Expected rejection of dangerous URI: $uri", SecurityUtils.isValidContentUri(uri))
        }
    }

    @Test
    fun testCachePruningDeletesExpiredFiles() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_cart_cache_expire_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val now = System.currentTimeMillis()
            val oneDayMs = 24 * 60 * 60 * 1000L

            // Create 3 files: 2 expired (30 hours old), 1 recent (1 hour old)
            val oldFile1 = File(tempDir, "old1.jpg").apply {
                writeText("test_old_1")
                setLastModified(now - (30 * 60 * 60 * 1000L))
            }
            val oldFile2 = File(tempDir, "old2.jpg").apply {
                writeText("test_old_2")
                setLastModified(now - (26 * 60 * 60 * 1000L))
            }
            val newFile = File(tempDir, "recent.jpg").apply {
                writeText("test_recent")
                setLastModified(now - (1 * 60 * 60 * 1000L))
            }

            val deleted = SafeImageDecoder.pruneCache(
                cacheDir = tempDir,
                maxAgeMs = oneDayMs,
                maxFiles = 10,
                currentTime = now
            )

            assertEquals("Should delete exactly 2 expired files", 2, deleted)
            assertFalse("Old file 1 must be deleted", oldFile1.exists())
            assertFalse("Old file 2 must be deleted", oldFile2.exists())
            assertTrue("Recent file must be preserved", newFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCachePruningEnforcesMaxFileCount() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_cart_cache_count_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val now = System.currentTimeMillis()
            val maxFiles = 3

            // Create 6 recent files with staggered timestamps
            val createdFiles = (1..6).map { i ->
                File(tempDir, "capture_$i.jpg").apply {
                    writeText("capture data $i")
                    setLastModified(now - ((10 - i) * 60 * 1000L)) // 1 is oldest, 6 is newest
                }
            }

            val deleted = SafeImageDecoder.pruneCache(
                cacheDir = tempDir,
                maxAgeMs = 7 * 24 * 60 * 60 * 1000L, // 7 days (none expired by age)
                maxFiles = maxFiles,
                currentTime = now
            )

            assertEquals("Should delete 3 excess files", 3, deleted)
            val remaining = tempDir.listFiles() ?: emptyArray()
            assertEquals("Remaining files count must not exceed maxFiles", maxFiles, remaining.size)

            // The oldest files (1, 2, 3) must be deleted, newest (4, 5, 6) preserved
            assertFalse("Oldest file 1 should be pruned", createdFiles[0].exists())
            assertFalse("Old file 2 should be pruned", createdFiles[1].exists())
            assertFalse("Old file 3 should be pruned", createdFiles[2].exists())
            assertTrue("Newer file 4 should be retained", createdFiles[3].exists())
            assertTrue("Newer file 5 should be retained", createdFiles[4].exists())
            assertTrue("Newer file 6 should be retained", createdFiles[5].exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDecodeThumbnailHandlesMissingOrCorruptFileSafely() {
        val nonExistentFile = File("non_existent_image_${System.currentTimeMillis()}.jpg")
        val bitmap = SafeImageDecoder.decodeThumbnail(nonExistentFile)
        assertNull("Thumbnail decode must safely return null for missing file", bitmap)

        val tempEmptyFile = File.createTempFile("empty_image_", ".jpg").apply {
            writeBytes(ByteArray(0))
        }
        try {
            val emptyBitmap = SafeImageDecoder.decodeThumbnail(tempEmptyFile)
            assertNull("Thumbnail decode must safely return null for 0-byte file", emptyBitmap)
        } finally {
            tempEmptyFile.delete()
        }
    }

    @Test
    fun testMagicByteMimeDetectionAndBinaryHeaderDimensions() {
        val pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        assertEquals("image/png", SafeImageDecoder.detectMimeTypeFromBytes(pngBytes))
        assertEquals("image/png", SafeImageDecoder.resolveBestMimeType(pngBytes, "image/*"))
        assertEquals("png", SafeImageDecoder.extensionForMimeType("image/png"))

        val pngDims = SafeImageDecoder.parseImageDimensionsFromBytes(pngBytes)
        assertEquals(Pair(1, 1), pngDims)

        // HTML payload claiming image/* must be rejected (null MIME)
        val htmlBytes = "<html><body>Not an image</body></html>".toByteArray()
        assertNull(SafeImageDecoder.detectMimeTypeFromBytes(htmlBytes))
        assertNull(SafeImageDecoder.resolveBestMimeType(htmlBytes, "image/*"))
    }
}
