package com.paypal.visualcart.feature.circle

import android.content.Intent
import com.paypal.visualcart.core.util.SafeImageDecoder
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.feature.ai.FreeTierPolicyException
import com.paypal.visualcart.feature.ai.GeminiFreeVisionProvider
import com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM security, lifecycle, and end-to-end pipeline integration tests for Circle to Shop:
 * - Single-use MediaProjection consent grants (zero token reuse, clear on denial, concurrency safety)
 * - Ephemeral cropped artifact tokens and bubble visibility state transitions
 * - Neutral UUID PNG verification, SafeImageDecoder magic-byte sniffing, and cache pruning
 * - Real local HttpServer integration with GeminiFreeVisionProvider (gemini-3.5-flash-lite, $0 policy)
 * - Clean SearchQueryBuilder + live MerchantDiscoveryEngine routing without filename leakage
 */
class CircleToShopPipelineTest {

    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        CircleToShopSessionStore.clearAll()
        tempCacheDir = File(
            System.getProperty("java.io.tmpdir"),
            "vc_circle_pipeline_test_${System.nanoTime()}"
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        CircleToShopSessionStore.clearAll()
        tempCacheDir.deleteRecursively()
    }

    private fun createValidPngBytes(width: Int, height: Int): ByteArray {
        val rawScanlines = ByteArray(height * (1 + width * 3))
        var pos = 0
        for (y in 0 until height) {
            rawScanlines[pos++] = 0 // PNG filter type 0
            for (x in 0 until width) {
                rawScanlines[pos++] = ((x * 255) / width.coerceAtLeast(1)).toByte()
                rawScanlines[pos++] = ((y * 255) / height.coerceAtLeast(1)).toByte()
                rawScanlines[pos++] = (((x + y) * 127) / (width + height).coerceAtLeast(1)).toByte()
            }
        }

        val deflater = Deflater()
        deflater.setInput(rawScanlines)
        deflater.finish()
        val compressedOut = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressedOut.write(buf, 0, count)
        }
        deflater.end()
        val idatBytes = compressedOut.toByteArray()

        val ihdrOut = ByteArrayOutputStream()
        DataOutputStream(ihdrOut).use { ihdr ->
            ihdr.writeInt(width)
            ihdr.writeInt(height)
            ihdr.writeByte(8) // bit depth
            ihdr.writeByte(2) // RGB
            ihdr.writeByte(0) // compression
            ihdr.writeByte(0) // filter
            ihdr.writeByte(0) // interlace
        }

        val pngOut = ByteArrayOutputStream()
        DataOutputStream(pngOut).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writePngChunk(out, "IHDR", ihdrOut.toByteArray())
            writePngChunk(out, "IDAT", idatBytes)
            writePngChunk(out, "IEND", ByteArray(0))
        }
        return pngOut.toByteArray()
    }

    private fun writePngChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        out.writeInt(crc.value.toInt())
    }

    @Test
    fun testSingleUseGrantCannotBeReusedClearedOrBlankConsumed() {
        val consentIntent = Intent()
        val grantId = CircleToShopSessionStore.storeSingleUseGrant(resultCode = -1, data = consentIntent)
        assertTrue(grantId.isNotBlank())

        // Blank grant IDs are ignored without touching the pending grant
        assertNull(CircleToShopSessionStore.consumeSingleUseGrant(""))
        assertNull(CircleToShopSessionStore.consumeSingleUseGrant("   "))

        // First valid consumption succeeds with exact resultCode and Intent reference
        val consumed = CircleToShopSessionStore.consumeSingleUseGrant(grantId)
        assertNotNull("Expected initial consumeSingleUseGrant to succeed", consumed)
        assertEquals(-1, consumed!!.resultCode)
        assertSame(consentIntent, consumed.resultData)

        // Second consumption with same token MUST return null (zero token reuse)
        assertNull(
            "Single-use MediaProjection grant must NEVER be reusable twice",
            CircleToShopSessionStore.consumeSingleUseGrant(grantId)
        )

        // Attempting to consume with an unknown/spoofed grant ID fails closed and clears pending grants
        val activeGrantId = CircleToShopSessionStore.storeSingleUseGrant(-1, Intent())
        assertNull(CircleToShopSessionStore.consumeSingleUseGrant("non-existent-grant"))
        assertNull(
            "Unknown grant probe must fail closed and invalidate pending grants",
            CircleToShopSessionStore.consumeSingleUseGrant(activeGrantId)
        )

        // Storing a fresh grant invalidates any unconsumed stale grant; fresh grant consumes once cleanly
        val staleIntent = Intent()
        val staleGrantId = CircleToShopSessionStore.storeSingleUseGrant(-1, staleIntent)
        val freshIntent = Intent()
        val freshGrantId = CircleToShopSessionStore.storeSingleUseGrant(-1, freshIntent)
        val freshConsumed = CircleToShopSessionStore.consumeSingleUseGrant(freshGrantId)
        assertNotNull(freshConsumed)
        assertSame(freshIntent, freshConsumed!!.resultData)
        assertNull("Stale grant must be invalidated when a new grant is stored", CircleToShopSessionStore.consumeSingleUseGrant(staleGrantId))

        // Consuming an overwritten stale grant while a fresh grant is pending fails closed
        val overwrittenStaleId = CircleToShopSessionStore.storeSingleUseGrant(-1, Intent())
        val activeFreshId = CircleToShopSessionStore.storeSingleUseGrant(-1, Intent())
        assertNull(CircleToShopSessionStore.consumeSingleUseGrant(overwrittenStaleId))
        assertNull(CircleToShopSessionStore.consumeSingleUseGrant(activeFreshId))

        // Clearing pending grants (on denial / cancellation / revocation) invalidates unconsumed grants
        val deniedGrantId = CircleToShopSessionStore.storeSingleUseGrant(-1, Intent())
        CircleToShopSessionStore.clearPendingGrants()
        assertNull(CircleToShopSessionStore.consumeSingleUseGrant(deniedGrantId))
    }

    @Test
    fun testConcurrentSingleUseGrantConsumptionAllowsExactlyOneWinner() {
        val grantIntent = Intent()
        val grantId = CircleToShopSessionStore.storeSingleUseGrant(-1, grantIntent)
        val threadCount = 8
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val futures = (0 until threadCount).map {
                executor.submit<PendingCaptureGrant?> {
                    readyLatch.countDown()
                    startLatch.await(3, TimeUnit.SECONDS)
                    CircleToShopSessionStore.consumeSingleUseGrant(grantId)
                }
            }
            assertTrue(readyLatch.await(3, TimeUnit.SECONDS))
            startLatch.countDown()

            val results = futures.map { it.get(3, TimeUnit.SECONDS) }
            val nonNullCount = results.count { it != null }
            assertEquals("Exactly one consumer thread may claim a single-use grant", 1, nonNullCount)
            assertNull(CircleToShopSessionStore.consumeSingleUseGrant(grantId))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun testCroppedArtifactTokenSingleConsumeAndMetadataPreservation() {
        val capturesDir = File(tempCacheDir, "shared_captures").apply { mkdirs() }
        val pngBytes = createValidPngBytes(320, 480)
        val artifactFile = File(capturesDir, "vc_circle_${UUID.randomUUID()}.png").apply {
            writeBytes(pngBytes)
        }

        val token = CircleToShopSessionStore.registerCroppedArtifact(
            file = artifactFile,
            mimeType = "image/png",
            width = 320,
            height = 480,
            byteSize = pngBytes.size.toLong()
        )
        assertTrue(token.isNotBlank())

        // Peek preserves artifact without consuming
        val peeked = CircleToShopSessionStore.peekCroppedArtifact(token)
        assertNotNull(peeked)
        assertEquals(artifactFile.absolutePath, peeked!!.file.absolutePath)

        // First consume succeeds with full metadata intact
        val consumed = CircleToShopSessionStore.consumeCroppedArtifact(token)
        assertNotNull(consumed)
        assertEquals(artifactFile.absolutePath, consumed!!.file.absolutePath)
        assertEquals("image/png", consumed.mimeType)
        assertEquals(320, consumed.width)
        assertEquals(480, consumed.height)
        assertEquals(pngBytes.size.toLong(), consumed.byteSize)
        assertTrue(consumed.createdAtMs > 0L)

        // Second consume and subsequent peek MUST return null
        assertNull("Artifact token must be single-consume", CircleToShopSessionStore.consumeCroppedArtifact(token))
        assertNull(CircleToShopSessionStore.peekCroppedArtifact(token))
        assertNull(CircleToShopSessionStore.consumeCroppedArtifact(""))
        assertNull(CircleToShopSessionStore.consumeCroppedArtifact("unknown-token"))
    }

    @Test
    fun testSessionStateTransitionsAndBubbleVisibilityFlag() {
        assertEquals(CircleProjectionState.IDLE, CircleToShopSessionStore.state.value)
        assertFalse(CircleToShopSessionStore.isBubbleHiddenForCapture.value)

        CircleToShopSessionStore.updateState(CircleProjectionState.REQUESTING_CONSENT)
        assertEquals(CircleProjectionState.REQUESTING_CONSENT, CircleToShopSessionStore.state.value)

        CircleToShopSessionStore.markBubbleHiddenForCapture(true)
        CircleToShopSessionStore.updateState(CircleProjectionState.SELECTING)
        assertTrue(CircleToShopSessionStore.isBubbleHiddenForCapture.value)
        assertEquals(CircleProjectionState.SELECTING, CircleToShopSessionStore.state.value)

        CircleToShopSessionStore.updateState(CircleProjectionState.CAPTURING)
        assertEquals(CircleProjectionState.CAPTURING, CircleToShopSessionStore.state.value)

        CircleToShopSessionStore.updateState(CircleProjectionState.REVOKED_OR_DENIED)
        assertEquals(CircleProjectionState.REVOKED_OR_DENIED, CircleToShopSessionStore.state.value)

        CircleToShopSessionStore.markBubbleHiddenForCapture(false)
        CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
        assertFalse(CircleToShopSessionStore.isBubbleHiddenForCapture.value)
        assertEquals(CircleProjectionState.IDLE, CircleToShopSessionStore.state.value)
    }

    @Test
    fun testRgbaBufferInvalidBoundsSafetyAndPngCachePruning() {
        val validBuffer = ByteBuffer.allocate(16)
        assertNull(CroppedImageProcessor.convertRgbaPlaneToBitmap(validBuffer, 0, 2, 4, 8))
        assertNull(CroppedImageProcessor.convertRgbaPlaneToBitmap(validBuffer, 2, -1, 4, 8))
        assertNull(CroppedImageProcessor.convertRgbaPlaneToBitmap(validBuffer, 2, 2, 3, 8))
        assertNull(CroppedImageProcessor.convertRgbaPlaneToBitmap(validBuffer, 2, 2, 4, 4)) // rowStride < width * 4
        assertNull(CroppedImageProcessor.convertRgbaPlaneToBitmap(ByteBuffer.allocate(8), 2, 2, 4, 8)) // truncated buffer

        val capturesDir = File(tempCacheDir, "shared_captures").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val pngBytes = createValidPngBytes(128, 192)
        assertEquals("image/png", SafeImageDecoder.detectMimeTypeFromBytes(pngBytes))
        assertEquals(Pair(128, 192), SafeImageDecoder.parseImageDimensionsFromBytes(pngBytes))

        val expired = File(capturesDir, "vc_circle_${UUID.randomUUID()}.png").apply {
            writeBytes(pngBytes)
            setLastModified(now - SafeImageDecoder.DEFAULT_MAX_CACHE_AGE_MS - 60_000L)
        }
        val active = File(capturesDir, "vc_circle_${UUID.randomUUID()}.png").apply {
            writeBytes(pngBytes)
            setLastModified(now - 5_000L)
        }

        val pruned = SafeImageDecoder.pruneCache(
            cacheDir = capturesDir,
            maxAgeMs = SafeImageDecoder.DEFAULT_MAX_CACHE_AGE_MS,
            maxFiles = SafeImageDecoder.DEFAULT_MAX_CACHE_FILES,
            currentTime = now
        )
        assertEquals(1, pruned)
        assertFalse(expired.exists())
        assertTrue(active.exists())
    }

    @Test
    fun testEndToEndCircleToShopPipelineWithLocalHttpServerAndLiveMerchantDiscovery() = runBlocking {
        // 1. Gesture analysis on FHD+ portrait overlay (1080x2400, density=3f)
        val stroke = (0 until 48).map { idx ->
            val angle = Math.toRadians((idx.toFloat() / 47f * 360f).toDouble())
            TouchPoint(
                x = 540f + (150f * cos(angle)).toFloat(),
                y = 1100f + (260f * sin(angle)).toFloat(),
                timestampMs = idx * 14L
            )
        }
        val gestureResult = CircleGestureAnalyzer.analyze(
            points = stroke,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 2180,
            density = 3f,
            paddingDp = 8f
        )
        assertTrue(gestureResult is GestureAnalysisResult.ValidCircle)
        val validCircle = gestureResult as GestureAnalysisResult.ValidCircle

        // 2. Map overlay selection to 0.5x scaled virtual display frame (540x1200) with 96px status bar inset
        val cropRect = ScreenCropCalculator.mapOverlaySelectionToFrameRect(
            selectionInOverlayPx = validCircle.boundingBox,
            overlayWidthPx = 1080,
            overlayHeightPx = 2180,
            frameWidthPx = 540,
            frameHeightPx = 1200,
            overlayOffsetXPx = 0,
            overlayOffsetYPx = 96,
            displayWidthPx = 1080,
            displayHeightPx = 2400
        )
        assertNotNull(cropRect)
        val targetSize = CroppedImageProcessor.computeTargetRecognitionSize(cropRect!!.width, cropRect.height)
        assertTrue(targetSize.first >= 256)
        assertTrue(targetSize.second <= 1024)

        // 3. Reject FLAG_SECURE uniform pixels, accept real varied product pixels
        assertTrue(CroppedImageProcessor.areSampledArgbPixelsUniform(IntArray(64) { 0xFF000000.toInt() }))
        assertFalse(
            CroppedImageProcessor.areSampledArgbPixelsUniform(
                IntArray(64) { idx -> if (idx % 2 == 0) 0xFFFAF6EE.toInt() else 0xFF205A3E.toInt() }
            )
        )

        // 4. Write valid PNG bytes to neutral vc_circle_<uuid>.png & hand off via single-consume token
        val capturesDir = File(tempCacheDir, "shared_captures").apply { mkdirs() }
        val pngBytes = createValidPngBytes(targetSize.first, targetSize.second)
        val uuidFileName = "vc_circle_${UUID.randomUUID()}.png"
        val croppedFile = File(capturesDir, uuidFileName).apply { writeBytes(pngBytes) }

        assertEquals("image/png", SafeImageDecoder.detectMimeTypeFromBytes(croppedFile.readBytes()))
        val captureToken = CircleToShopSessionStore.registerCroppedArtifact(
            file = croppedFile,
            mimeType = "image/png",
            width = targetSize.first,
            height = targetSize.second,
            byteSize = pngBytes.size.toLong()
        )
        val consumedArtifact = CircleToShopSessionStore.consumeCroppedArtifact(captureToken)
        assertNotNull(consumedArtifact)
        assertNull(CircleToShopSessionStore.consumeCroppedArtifact(captureToken))

        // 5. Verify GeminiFreeVisionProvider resolves PNG MIME and exact base64 bytes
        val payload = GeminiFreeVisionProvider.resolveImagePayload(consumedArtifact!!.file.absolutePath)
        assertNull(payload.corruptError)
        assertEquals("image/png", payload.mimeType)
        val expectedBase64 = Base64.getEncoder().encodeToString(pngBytes)
        assertEquals(expectedBase64, payload.base64Data)
        assertEquals(64, payload.sha256Hash?.length)

        // 6. Spin up real local HttpServer mimicking FastAPI /api/v1/ai/recognize-product & model-status
        var capturedRecognizeBody = ""
        val recognizeRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ai/model-status") { exchange ->
            val statusJson = """
                {
                  "available": true,
                  "model": "gemini-3.5-flash-lite",
                  "free_tier_only": true,
                  "ai_max_cost_usd": 0.0
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, statusJson.size.toLong())
            exchange.responseBody.use { it.write(statusJson) }
        }
        server.createContext("/api/v1/ai/recognize-product") { exchange ->
            recognizeRequests.incrementAndGet()
            capturedRecognizeBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
            val responseJson = """
                {
                  "brand": "Polago Clothing",
                  "product_name": "Polago Minimal Maxi Dress",
                  "category": "Fashion",
                  "colour": "Cream",
                  "specifications": {
                    "fabric": "Chiffon",
                    "length": "125cm"
                  },
                  "search_query": "Polago Minimal Maxi Dress Cream",
                  "confidence": 0.91,
                  "identification_status": "IDENTIFIED",
                  "recognition_source": "LIVE_AI",
                  "is_demo_fixture": false,
                  "is_live_inference": true,
                  "model_name": "gemini-3.5-flash-lite",
                  "ai_max_cost_usd": 0.0,
                  "match_reason": "Cream chiffon sleeveless maxi dress circled on screen."
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseJson.size.toLong())
            exchange.responseBody.use { it.write(responseJson) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val provider = GeminiFreeVisionProvider(
                apiKey = null,
                endpointUrl = "$baseUrl/api/v1/ai/recognize-product",
                modelName = GeminiFreeVisionProvider.APPROVED_FREE_MODEL,
                freeTierOnly = true,
                maxCostUsd = 0.0
            )

            val availability = provider.verifyModelAvailability()
            assertTrue(availability.available)
            assertEquals("gemini-3.5-flash-lite", availability.modelName)

            // Even if the neutral UUID filename is accidentally supplied as contextText, it must be stripped
            val recognized = provider.recognizeFromImage(
                imageUri = consumedArtifact.file.absolutePath,
                contextText = consumedArtifact.file.name
            )

            assertEquals(1, recognizeRequests.get())
            assertTrue(capturedRecognizeBody.contains("\"model\":\"gemini-3.5-flash-lite\""))
            assertTrue(capturedRecognizeBody.contains("\"mime_type\":\"image/png\""))
            assertTrue(capturedRecognizeBody.contains("\"image_base64\":\"$expectedBase64\""))
            assertFalse("Neutral vc_circle filename must NEVER leak into AI hints", capturedRecognizeBody.contains("vc_circle_"))
            assertFalse("PNG extension must NEVER leak into AI hints", capturedRecognizeBody.contains(".png"))
            assertFalse("Hint fields must be omitted when only filename noise existed", capturedRecognizeBody.contains("\"hint_text\""))

            assertEquals(RecognitionStatus.IDENTIFIED, recognized.status)
            assertEquals(RecognitionSource.LIVE_AI, recognized.recognitionSource)
            assertTrue(recognized.isLiveInference)
            assertFalse(recognized.isDemoFixture)
            assertEquals("gemini-3.5-flash-lite", recognized.aiModelName)
            assertEquals("Polago Clothing", recognized.brand)
            assertEquals("Polago Minimal Maxi Dress", recognized.title)
            assertEquals("Fashion", recognized.category)
            assertEquals("Polago Minimal Maxi Dress Cream", recognized.searchQuery)

            // 7. Feed recognized product cleanly into SearchQueryBuilder + live MerchantDiscoveryEngine
            val queryResult = SearchQueryBuilder.build(SearchQueryBuilder.Input.fromRecognizedProduct(recognized))
            assertTrue(queryResult is SearchQueryBuilder.Result.Valid)
            val canonicalQuery = (queryResult as SearchQueryBuilder.Result.Valid).query
            assertEquals("Polago Minimal Maxi Dress Cream", canonicalQuery)

            val liveEngine = MerchantDiscoveryEngine(
                MerchantDiscoveryEngine.defaultProviders().onEach { it.isLiveMode = true }
            )
            val discovery = liveEngine.discoverStructured(recognized)
            assertNull(discovery.rejectedReason)
            assertEquals("Polago Minimal Maxi Dress Cream", discovery.canonicalQuery)
            assertTrue(discovery.searchLinks.isNotEmpty())
            assertTrue(discovery.searchLinks.all { it.listingOrigin == ListingOrigin.DIRECT_SEARCH_URL })
            assertTrue(discovery.searchLinks.all { it.price == null && !it.hasVerifiedPrice })
            assertTrue(
                "All store URLs must contain sanitized product terms and zero capture filenames",
                discovery.searchLinks.all { listing ->
                    listing.productUrl.contains("Polago", ignoreCase = true) &&
                        !listing.productUrl.contains("vc_circle", ignoreCase = true) &&
                        !listing.productUrl.contains(".png", ignoreCase = true)
                }
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testZeroCostPolicyCorruptFileGateAndRateLimitCircuitBreaker() = runBlocking {
        // Strict zero-cost and approved-model enforcement
        val rejectedModels = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gpt-4o")
        for (model in rejectedModels) {
            try {
                GeminiFreeVisionProvider(modelName = model, freeTierOnly = true, maxCostUsd = 0.0)
                fail("Expected FreeTierPolicyException for unapproved model $model")
            } catch (_: FreeTierPolicyException) {
                // Expected
            }
        }
        try {
            GeminiFreeVisionProvider(freeTierOnly = false, maxCostUsd = 0.0)
            fail("Expected FreeTierPolicyException when freeTierOnly=false")
        } catch (_: FreeTierPolicyException) {
            // Expected
        }
        try {
            GeminiFreeVisionProvider(freeTierOnly = true, maxCostUsd = 0.01)
            fail("Expected FreeTierPolicyException when maxCostUsd > 0")
        } catch (_: FreeTierPolicyException) {
            // Expected
        }

        val capturesDir = File(tempCacheDir, "shared_captures").apply { mkdirs() }
        val disguisedHtml = File(capturesDir, "vc_circle_${UUID.randomUUID()}.png").apply {
            writeText("<html><body>Blocked</body></html>")
        }
        val validPng = File(capturesDir, "vc_circle_${UUID.randomUUID()}.png").apply {
            writeBytes(createValidPngBytes(256, 256))
        }

        val hitCounter = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ai/recognize-product") { exchange ->
            hitCounter.incrementAndGet()
            val body = """{"error":{"code":429,"message":"Rate limit exceeded"}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(429, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val provider = GeminiFreeVisionProvider(
                apiKey = null,
                endpointUrl = "http://127.0.0.1:${server.address.port}/api/v1/ai/recognize-product",
                modelName = GeminiFreeVisionProvider.APPROVED_FREE_MODEL,
                freeTierOnly = true,
                maxCostUsd = 0.0
            )

            // Corrupt non-image payload disguised as .png MUST be blocked locally with ZERO HTTP requests
            val corruptResult = provider.recognizeFromImage(disguisedHtml.absolutePath, contextText = disguisedHtml.name)
            assertEquals(0, hitCounter.get())
            assertEquals(RecognitionStatus.UNIDENTIFIED, corruptResult.status)
            assertFalse(corruptResult.isLiveInference)
            assertFalse(corruptResult.isDemoFixture)

            // HTTP 429 triggers circuit breaker after 1 attempt; subsequent call makes ZERO requests
            val rateLimitedFirst = provider.recognizeFromImage(validPng.absolutePath, contextText = null)
            assertEquals(1, hitCounter.get())
            assertTrue(rateLimitedFirst.rateLimited)
            assertFalse(rateLimitedFirst.isLiveInference)
            assertEquals(RecognitionSource.MANUAL_SEARCH, rateLimitedFirst.recognitionSource)
            assertTrue(rateLimitedFirst.diagnosticNote!!.contains(GeminiFreeVisionProvider.RATE_LIMIT_MESSAGE))

            val rateLimitedSecond = provider.recognizeFromImage(validPng.absolutePath, contextText = null)
            assertEquals("Circuit breaker must prevent retries during cooldown", 1, hitCounter.get())
            assertTrue(rateLimitedSecond.rateLimited)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testOverlayAutoStartAndFirstTimeDisclosureDecisions() {
        assertTrue(
            OverlayCapturePreferences.shouldAutoStartFloatingBubble(
                hasOverlayPermission = true,
                userAutoStartEnabled = true,
                isAlreadyRunning = false,
                isDirectCircleLaunch = false
            )
        )
        assertFalse(
            OverlayCapturePreferences.shouldAutoStartFloatingBubble(
                hasOverlayPermission = false,
                userAutoStartEnabled = true,
                isAlreadyRunning = false,
                isDirectCircleLaunch = false
            )
        )
        assertFalse(
            OverlayCapturePreferences.shouldAutoStartFloatingBubble(
                hasOverlayPermission = true,
                userAutoStartEnabled = false,
                isAlreadyRunning = false,
                isDirectCircleLaunch = false
            )
        )
        assertFalse(
            OverlayCapturePreferences.shouldAutoStartFloatingBubble(
                hasOverlayPermission = true,
                userAutoStartEnabled = true,
                isAlreadyRunning = true,
                isDirectCircleLaunch = false
            )
        )
        assertFalse(
            OverlayCapturePreferences.shouldAutoStartFloatingBubble(
                hasOverlayPermission = true,
                userAutoStartEnabled = true,
                isAlreadyRunning = false,
                isDirectCircleLaunch = true
            )
        )

        assertTrue(OverlayCapturePreferences.shouldShowFirstTimeDisclosure(disclosureAcknowledged = false))
        assertFalse(OverlayCapturePreferences.shouldShowFirstTimeDisclosure(disclosureAcknowledged = true))
    }
}
