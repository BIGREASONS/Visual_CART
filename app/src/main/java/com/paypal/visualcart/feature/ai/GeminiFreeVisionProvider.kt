package com.paypal.visualcart.feature.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.paypal.visualcart.domain.model.MatchResult
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.domain.model.RecognizedProduct
import com.paypal.visualcart.domain.model.RecognizedProductCandidate
import com.paypal.visualcart.domain.model.StructuredAiProduct
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Raised when AI configuration attempts to use a paid model, paid fallback, or non-zero budget.
 */
class FreeTierPolicyException(message: String) : IllegalArgumentException(message)

/**
 * Result of checking approved free-tier Gemini model availability without spending generation quota.
 */
data class ModelAvailabilityResult(
    val available: Boolean,
    val modelName: String,
    val statusCode: Int? = null,
    val reason: String? = null
)

/**
 * Modular, zero-billing multimodal vision provider locked to Google AI Studio's free-tier
 * `gemini-3.5-flash-lite` model.
 *
 * Guarantees:
 * - Strictly enforces the approved free-tier allowlist (`gemini-3.5-flash-lite`), `freeTierOnly = true`,
 *   and `maxCostUsd = 0.0` (never falls back to paid Flash/Pro models or search grounding).
 * - Validates and downscales oversized images (`<= 1024px`, `<= 512 KB`) and rejects corrupt binaries
 *   locally before making any API call.
 * - Caches successful live identifications by SHA-256 content hash to avoid duplicate quota usage.
 * - On HTTP 429 rate-limit / quota exhaustion, opens a cooldown circuit breaker immediately,
 *   never retries indefinitely, surfaces `"Free AI limit reached. Try again later."`,
 *   and degrades cleanly to [LocalProductParser] (`LOCAL_OCR`) or `MANUAL_SEARCH`.
 */
class GeminiFreeVisionProvider(
    private val apiKey: String? = resolveApiKey(),
    private val endpointUrl: String? = resolveEndpointUrl(),
    private val modelName: String = resolveModelName(),
    private val freeTierOnly: Boolean = resolveFreeTierOnly(),
    private val maxCostUsd: Double = resolveMaxCostUsd(),
    private val fallbackProvider: ProductUnderstandingProvider? = null,
    private val networkTransport: NetworkTransport = DefaultHttpNetworkTransport(
        connectTimeoutMs = 5000,
        readTimeoutMs = 15000
    ),
    private val getTransport: ((String, Map<String, String>) -> NetworkResponse)? = null,
    private val autoDetectBackendEndpoint: Boolean = false
) : ProductUnderstandingProvider {

    @Volatile
    private var cachedBackendEndpointUrl: String? = null

    @Volatile
    private var rateLimitUntilMs: Long = 0L

    private val imageResultCache = object : LinkedHashMap<String, RecognizedProduct>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecognizedProduct>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    init {
        validateFreeTierPolicy(modelName, freeTierOnly, maxCostUsd)
    }

    companion object {
        const val PROVIDER_NAME = "Google Gemini 3.5 Flash-Lite (Free Tier)"
        const val APPROVED_FREE_MODEL = "gemini-3.5-flash-lite"
        val APPROVED_FREE_MODELS = setOf(APPROVED_FREE_MODEL)
        const val RATE_LIMIT_MESSAGE = "Free AI limit reached. Try again later."
        const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        const val MAX_IMAGE_DIM = 1024
        const val MAX_IMAGE_BYTES = 524_288L
        const val MAX_OUTPUT_TOKENS = 350
        const val THINKING_LEVEL = "MINIMAL"
        const val BACKEND_PROXY_PATH = "/api/v1/ai/recognize-product"
        const val BACKEND_STATUS_PATH = "/api/v1/ai/model-status"
        private const val MAX_CACHE_ENTRIES = 32
        private val BACKEND_CANDIDATE_URLS = listOf(
            "http://localhost:8000",
            "http://10.0.2.2:8000"
        )

        private const val ENV_GEMINI_KEY = "GEMINI_API_KEY"
        private const val ENV_PROXY_ENDPOINT = "VISUAL_CART_AI_ENDPOINT"
        private const val ENV_GEMINI_MODEL = "GEMINI_MODEL"
        private const val ENV_FREE_TIER_ONLY = "GEMINI_FREE_TIER_ONLY"
        private const val ENV_MAX_COST_USD = "AI_MAX_COST_USD"
        private const val PROP_GEMINI_KEY = "gemini.api.key"
        private const val PROP_PROXY_ENDPOINT = "visualcart.ai.endpoint"
        private const val PROP_GEMINI_MODEL = "gemini.model"
        private const val PROP_FREE_TIER_ONLY = "gemini.freeTierOnly"
        private const val PROP_MAX_COST_USD = "ai.maxCostUsd"

        private val SYSTEM_INSTRUCTION =
            "Extract e-commerce product JSON from pixels. " +
                "Never invent brands, models, prices, or Electronics for non-electronics. " +
                "Use Industrial Materials for pipes/hardware and Fashion for dresses/apparel. " +
                "Use identification_status='PARTIAL' if exact model is uncertain, or 'UNIDENTIFIED' if unreadable."

        fun forBackend(): GeminiFreeVisionProvider =
            GeminiFreeVisionProvider(autoDetectBackendEndpoint = true)

        fun validateFreeTierPolicy(
            modelName: String,
            freeTierOnly: Boolean = true,
            maxCostUsd: Double = 0.0
        ) {
            if (!freeTierOnly) {
                throw FreeTierPolicyException(
                    "GEMINI_FREE_TIER_ONLY must be true. Paid-tier AI inference is disabled."
                )
            }
            if (maxCostUsd > 0.0) {
                throw FreeTierPolicyException(
                    "AI_MAX_COST_USD=$maxCostUsd rejected; Visual Cart requires $0 spending budget."
                )
            }
            if (modelName !in APPROVED_FREE_MODELS) {
                throw FreeTierPolicyException(
                    "Model '$modelName' rejected; only $APPROVED_FREE_MODELS are approved under free-tier policy."
                )
            }
        }

        fun isValidApiKeyFormat(rawKey: String?): Boolean {
            val cleaned = rawKey?.trim().orEmpty()
            if (cleaned.length < 12 || cleaned.any { it.isWhitespace() }) return false
            return cleaned.lowercase() !in setOf(
                "your_gemini_api_key_here",
                "changeme",
                "placeholder",
                "none",
                "null"
            )
        }

        private fun resolveApiKey(): String? {
            return System.getenv(ENV_GEMINI_KEY)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_GEMINI_KEY)?.takeIf { it.isNotBlank() }
        }

        private fun resolveEndpointUrl(): String? {
            return System.getenv(ENV_PROXY_ENDPOINT)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_PROXY_ENDPOINT)?.takeIf { it.isNotBlank() }
        }

        private fun resolveModelName(): String {
            return System.getenv(ENV_GEMINI_MODEL)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_GEMINI_MODEL)?.takeIf { it.isNotBlank() }
                ?: APPROVED_FREE_MODEL
        }

        private fun resolveFreeTierOnly(): Boolean {
            val raw = System.getenv(ENV_FREE_TIER_ONLY)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_FREE_TIER_ONLY)?.takeIf { it.isNotBlank() }
                ?: return true
            return raw.trim().lowercase() !in setOf("0", "false", "no", "off")
        }

        private fun resolveMaxCostUsd(): Double {
            val raw = System.getenv(ENV_MAX_COST_USD)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_MAX_COST_USD)?.takeIf { it.isNotBlank() }
                ?: return 0.0
            return raw.trim().toDoubleOrNull()
                ?: throw FreeTierPolicyException("Invalid AI_MAX_COST_USD '$raw'; must be 0.")
        }

        fun sniffMimeTypeFromBytes(bytes: ByteArray): String? {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) return "image/png"
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            ) return "image/jpeg"
            if (bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
            ) return "image/webp"
            if (bytes.size >= 6 &&
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()
            ) return "image/gif"
            return null
        }

        fun resolveImagePayload(imageUri: String): ImagePayload {
            val trimmed = imageUri.trim()

            // 1. Data URI format: data:image/png;base64,...
            if (trimmed.startsWith("data:image/", ignoreCase = true)) {
                val declaredMime = trimmed.substringAfter("data:").substringBefore(";base64,")
                val b64 = trimmed.substringAfter(";base64,")
                val decoded = try {
                    Base64.getDecoder().decode(b64)
                } catch (_: IllegalArgumentException) {
                    return ImagePayload(corruptError = "Invalid base64 image encoding.")
                }
                val sniffed = sniffMimeTypeFromBytes(decoded)
                if (decoded.isEmpty() || sniffed == null) {
                    return ImagePayload(corruptError = "Unrecognized or corrupt image bytes in data URI.")
                }
                return ImagePayload(
                    base64Data = b64,
                    mimeType = sniffed.ifBlank { declaredMime.ifBlank { "image/jpeg" } },
                    sha256Hash = sha256Hex(decoded)
                )
            }

            // 2. Remote HTTP / HTTPS URL
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return ImagePayload(remoteUrl = trimmed)
            }

            // 3. Local file path (file:///path or absolute path)
            val filePath = if (trimmed.startsWith("file://", ignoreCase = true)) {
                trimmed.removePrefix("file://")
            } else {
                trimmed
            }

            val file = File(filePath)
            if (file.exists() && file.isFile && file.length() > 0) {
                return try {
                    val rawBytes = file.readBytes()
                    val sniffedMime = sniffMimeTypeFromBytes(rawBytes)
                        ?: return ImagePayload(corruptError = "Corrupt or non-image file bytes.")
                    val compressed = compressLargeImageIfPossible(file)
                    val finalBytes = compressed ?: rawBytes
                    val b64 = Base64.getEncoder().encodeToString(finalBytes)
                    val finalMime = if (compressed != null) "image/jpeg" else sniffedMime
                    ImagePayload(
                        base64Data = b64,
                        mimeType = finalMime,
                        sha256Hash = sha256Hex(finalBytes),
                        compressed = compressed != null
                    )
                } catch (_: Exception) {
                    ImagePayload(corruptError = "Failed to read image bytes.")
                }
            }

            // 4. Raw base64 string
            if (trimmed.length > 24 && !trimmed.contains(" ") && !trimmed.contains("/") && !trimmed.contains("\\")) {
                val decoded = try {
                    Base64.getDecoder().decode(trimmed)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (decoded != null) {
                    val sniffed = sniffMimeTypeFromBytes(decoded)
                        ?: return ImagePayload(corruptError = "Corrupt or unrecognized base64 image payload.")
                    return ImagePayload(
                        base64Data = trimmed,
                        mimeType = sniffed,
                        sha256Hash = sha256Hex(decoded)
                    )
                }
            }

            return ImagePayload(base64Data = null, mimeType = "image/jpeg", remoteUrl = null)
        }

        private fun compressLargeImageIfPossible(
            file: File,
            maxBytes: Long = MAX_IMAGE_BYTES,
            maxDim: Int = MAX_IMAGE_DIM
        ): ByteArray? {
            if (file.length() <= maxBytes) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val width = bounds.outWidth
                val height = bounds.outHeight
                if (width <= 0 || height <= 0) return null

                var sampleSize = 1
                while (width / sampleSize > maxDim * 2 || height / sampleSize > maxDim * 2) {
                    sampleSize *= 2
                }

                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                val longest = maxOf(decoded.width, decoded.height)
                val scaled = if (longest > maxDim) {
                    val scale = maxDim.toFloat() / longest.toFloat()
                    val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }

                var quality = 82
                var outputBytes: ByteArray
                while (true) {
                    outputBytes = ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        out.toByteArray()
                    }
                    if (outputBytes.size <= maxBytes || quality <= 55) break
                    quality -= 10
                }
                scaled.recycle()
                outputBytes
            } catch (_: Throwable) {
                null
            }
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun buildDirectPrompt(contextText: String?): String {
            val cleanContext = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }
            val hintPart = if (!cleanContext.isNullOrBlank()) {
                " Secondary text hint: \"$cleanContext\"."
            } else {
                ""
            }
            return """
                Identify the product from the visual image.$hintPart
                Return ONLY valid JSON:
                {
                  "brand": "string or null",
                  "product_name": "string or null",
                  "category": "Industrial Materials|Hardware & Tools|Electronics|Computers|Audio|Gaming|Footwear|Fashion|Home|Accessories|Other|Unidentified",
                  "model": "string or null",
                  "colour": "string or null",
                  "specifications": ["Feature: Value"],
                  "search_query": "clean shopping query or null",
                  "confidence": 0.0,
                  "identification_status": "IDENTIFIED|PARTIAL|UNIDENTIFIED",
                  "match_reason": "concise visual evidence"
                }
                Never invent brands, models, or Electronics for non-electronics.
            """.trimIndent()
        }

        fun buildDirectGeminiPayload(prompt: String, imagePayload: ImagePayload?): String {
            val parts = mutableListOf<String>()
            if (imagePayload?.base64Data != null) {
                parts.add("""{"inlineData":{"mimeType":"${escapeJson(imagePayload.mimeType)}","data":"${imagePayload.base64Data}"}}""")
            }
            parts.add("""{"text":"${escapeJson(prompt)}"}""")

            return """
                {
                  "systemInstruction": {
                    "parts": [
                      {"text": "${escapeJson(SYSTEM_INSTRUCTION)}"}
                    ]
                  },
                  "contents": [
                    {
                      "parts": [
                        ${parts.joinToString(",")}
                      ]
                    }
                  ],
                  "generationConfig": {
                    "responseMimeType": "application/json",
                    "maxOutputTokens": $MAX_OUTPUT_TOKENS,
                    "thinkingConfig": {
                      "thinkingLevel": "$THINKING_LEVEL"
                    }
                  }
                }
            """.trimIndent()
        }

        fun buildBackendProxyPayload(
            text: String?,
            contextText: String?,
            imagePayload: ImagePayload?,
            modelName: String = APPROVED_FREE_MODEL
        ): String {
            val fields = mutableListOf<String>()
            val effectiveHint = listOfNotNull(
                text?.takeIf { it.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(it) },
                contextText?.takeIf { it.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(it) }
            ).distinct().joinToString(" - ")
            if (effectiveHint.isNotBlank()) {
                fields.add("\"hint_text\":\"${escapeJson(effectiveHint)}\"")
            }
            if (!text.isNullOrBlank() && !SearchQueryBuilder.isNonProductNoise(text)) {
                fields.add("\"text\":\"${escapeJson(text)}\"")
            }
            if (!contextText.isNullOrBlank() && !SearchQueryBuilder.isNonProductNoise(contextText)) {
                fields.add("\"context_text\":\"${escapeJson(contextText)}\"")
            }
            if (imagePayload != null) {
                if (!imagePayload.base64Data.isNullOrBlank()) {
                    fields.add("\"image_base64\":\"${escapeJson(imagePayload.base64Data)}\"")
                    fields.add("\"mime_type\":\"${escapeJson(imagePayload.mimeType)}\"")
                }
                if (!imagePayload.remoteUrl.isNullOrBlank()) {
                    fields.add("\"image_url\":\"${escapeJson(imagePayload.remoteUrl)}\"")
                }
            }
            fields.add("\"model\":\"${escapeJson(modelName)}\"")
            return "{${fields.joinToString(",")}}"
        }

        fun parseStructuredProduct(
            rawJson: String,
            fallbackText: String = "",
            defaultModelName: String = APPROVED_FREE_MODEL
        ): StructuredAiProduct? {
            return try {
                val parsedRoot = MiniJson.parse(rawJson) ?: return null
                var targetMap: Map<String, Any?>? = null

                if (parsedRoot is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val rootMap = parsedRoot as Map<String, Any?>

                    val geminiCandidates = rootMap["candidates"] as? List<*>
                    val firstGeminiCandidate = geminiCandidates?.firstOrNull() as? Map<*, *>
                    if (firstGeminiCandidate != null && firstGeminiCandidate.containsKey("content")) {
                        val content = firstGeminiCandidate["content"] as? Map<*, *>
                        val parts = content?.get("parts") as? List<*>
                        val firstPart = parts?.firstOrNull() as? Map<*, *>
                        val candidateText = firstPart?.get("text") as? String
                        if (!candidateText.isNullOrBlank()) {
                            var inner = candidateText.trim()
                            if (inner.startsWith("```json")) inner = inner.removePrefix("```json")
                            if (inner.startsWith("```")) inner = inner.removePrefix("```")
                            if (inner.endsWith("```")) inner = inner.removeSuffix("```")
                            inner = inner.trim()
                            val innerParsed = MiniJson.parse(inner)
                            if (innerParsed is Map<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                targetMap = innerParsed as Map<String, Any?>
                            } else {
                                val cleanInner = inner.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }.orEmpty()
                                if (cleanInner.isBlank()) return null
                                val local = LocalProductParser.parse(cleanInner)
                                return StructuredAiProduct(
                                    brand = local.brand,
                                    productName = local.title,
                                    category = local.category,
                                    colour = local.specifications["color"],
                                    model = local.specifications["model"],
                                    specifications = local.specifications,
                                    confidence = local.confidence,
                                    matchReason = "Extracted from Gemini multimodal response: ${local.matchReason}",
                                    isDemoFixture = false,
                                    isLiveInference = true,
                                    status = local.status,
                                    recognitionSource = RecognitionSource.LIVE_AI,
                                    searchQuery = local.searchQuery,
                                    aiModelName = defaultModelName
                                )
                            }
                        }
                    }

                    if (targetMap == null && rootMap.containsKey("data") && rootMap["data"] is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        targetMap = rootMap["data"] as Map<String, Any?>
                    }

                    if (targetMap == null && (
                            rootMap.containsKey("product_name") ||
                                rootMap.containsKey("productName") ||
                                rootMap.containsKey("title") ||
                                rootMap.containsKey("name") ||
                                !rootMap.containsKey("candidates")
                            )
                    ) {
                        targetMap = rootMap
                    }
                }

                if (targetMap == null) return null

                val brand = targetMap["brand"]?.toString()?.takeIf {
                    it.isNotBlank() && it != "null" && !SearchQueryBuilder.isNonProductNoise(it)
                }
                val rawCategory = targetMap["category"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val rawProductName = (targetMap["product_name"] ?: targetMap["productName"] ?: targetMap["title"] ?: targetMap["name"])
                    ?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: rawCategory?.takeIf { !it.equals("Unidentified", ignoreCase = true) }
                    ?: fallbackText.takeIf { it.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(it) }
                    ?: "Unidentified Product"

                val productName = if (SearchQueryBuilder.isNonProductNoise(rawProductName) &&
                    !rawProductName.equals("Unidentified Product", ignoreCase = true)
                ) {
                    "Unidentified Product"
                } else {
                    rawProductName
                }

                val category = when {
                    productName.equals("Unidentified Product", ignoreCase = true) -> "Unidentified"
                    rawCategory.isNullOrBlank() || rawCategory.equals("General", ignoreCase = true) -> "Other"
                    else -> rawCategory
                }
                val colour = (targetMap["colour"] ?: targetMap["color"])?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val model = (targetMap["model"] ?: targetMap["model_number"] ?: targetMap["modelNumber"])?.toString()?.takeIf { it.isNotBlank() && it != "null" }

                val specsMap = parseSpecifications(targetMap["specifications"] ?: targetMap["specs"], targetMap["specifications_list"])
                if (colour != null && !specsMap.containsKey("color") && !specsMap.containsKey("colour")) {
                    specsMap["color"] = colour
                }
                if (model != null && !specsMap.containsKey("model")) {
                    specsMap["model"] = model
                }

                val confNum = targetMap["confidence"]
                val confidence = when (confNum) {
                    is Number -> confNum.toFloat()
                    is String -> confNum.toFloatOrNull() ?: 0.90f
                    else -> 0.90f
                }.coerceIn(0.0f, 1.0f)

                val matchReason = targetMap["match_reason"]?.toString()
                    ?: "Multimodal AI identified ${brand ?: ""} $productName with ${(confidence * 100).toInt()}% confidence."

                val source = targetMap["source"]?.toString()?.lowercase()
                val recognitionSourceStr = targetMap["recognition_source"]?.toString()?.uppercase()
                val statusStr = (targetMap["identification_status"] ?: targetMap["status"])?.toString()?.uppercase()
                val rateLimited = targetMap["rate_limited"] as? Boolean ?: (source == "rate_limited")
                val cacheHit = targetMap["cache_hit"] as? Boolean ?: false

                val isUnidentified = statusStr == "UNIDENTIFIED" ||
                    source == "needs_gemini_key" ||
                    source == "invalid_api_key" ||
                    source == "corrupt_image" ||
                    source == "vision_unidentified" ||
                    source == "rejected_noise" ||
                    source == "rate_limited" ||
                    productName.equals("Unidentified Product", ignoreCase = true) ||
                    category.equals("Unidentified", ignoreCase = true)

                val parsedCandidates = parseCandidateList(targetMap["candidates"])
                val parsedStatus = when {
                    isUnidentified -> RecognitionStatus.UNIDENTIFIED
                    statusStr == "PARTIAL" || parsedCandidates.size > 1 -> RecognitionStatus.PARTIAL
                    else -> RecognitionStatus.IDENTIFIED
                }

                val isDemo = targetMap["is_demo_fixture"] as? Boolean
                    ?: (recognitionSourceStr == "DEMO" || source == "demo")
                val isLive = when {
                    isUnidentified || rateLimited -> false
                    recognitionSourceStr == "LIVE_AI" -> true
                    recognitionSourceStr in setOf("FALLBACK", "LOCAL_OCR", "MANUAL_SEARCH") || source == "local_fallback" -> false
                    else -> targetMap["is_live_inference"] as? Boolean ?: (!isDemo)
                }
                val recognitionSource = when {
                    recognitionSourceStr == "LIVE_AI" && !rateLimited -> RecognitionSource.LIVE_AI
                    recognitionSourceStr == "DEMO" || isDemo -> RecognitionSource.DEMO
                    recognitionSourceStr == "LOCAL_OCR" -> RecognitionSource.LOCAL_OCR
                    recognitionSourceStr == "MANUAL_SEARCH" -> RecognitionSource.MANUAL_SEARCH
                    recognitionSourceStr == "FALLBACK" || source in setOf("needs_gemini_key", "local_fallback", "vision_unidentified", "invalid_api_key", "corrupt_image", "rate_limited") ->
                        if (rateLimited) RecognitionSource.MANUAL_SEARCH else RecognitionSource.FALLBACK
                    isLive -> RecognitionSource.LIVE_AI
                    else -> RecognitionSource.LOCAL_OCR
                }

                val rawSearchQuery = targetMap["search_query"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val cleanSearchQuery = if (parsedStatus == RecognitionStatus.UNIDENTIFIED) {
                    ""
                } else {
                    SearchQueryBuilder.buildFromRawQuery(rawSearchQuery).validQueryOrNull
                        ?: SearchQueryBuilder.build(
                            SearchQueryBuilder.Input(
                                productName = productName,
                                brand = brand,
                                model = model,
                                category = category,
                                colour = colour,
                                specifications = specsMap
                            )
                        ).validQueryOrNull.orEmpty()
                }

                val parsedModelName = (targetMap["model_name"] ?: targetMap["ai_model_name"])
                    ?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: defaultModelName
                val diagnosticNote = targetMap["diagnostic_note"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

                StructuredAiProduct(
                    brand = brand,
                    productName = productName,
                    category = category,
                    colour = colour,
                    model = model,
                    specifications = specsMap,
                    confidence = confidence,
                    matchReason = matchReason,
                    isDemoFixture = isDemo,
                    isLiveInference = isLive,
                    status = parsedStatus,
                    recognitionSource = recognitionSource,
                    searchQuery = cleanSearchQuery,
                    aiModelName = parsedModelName,
                    candidates = parsedCandidates,
                    diagnosticNote = diagnosticNote,
                    rateLimited = rateLimited,
                    cacheHit = cacheHit
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseSpecifications(rawSpecs: Any?, specsList: Any? = null): MutableMap<String, String> {
            val specsMap = mutableMapOf<String, String>()
            val source = rawSpecs ?: specsList
            if (source is Map<*, *>) {
                for ((k, v) in source) {
                    if (k != null && v != null) {
                        val key = k.toString().trim()
                        val value = v.toString().trim()
                        if (key.isNotEmpty() && value.isNotEmpty() && value != "null") {
                            specsMap[key] = value
                        }
                    }
                }
            } else if (source is List<*>) {
                var featureIdx = 1
                for (item in source) {
                    when (item) {
                        is Map<*, *> -> {
                            val name = (item["name"] ?: item["key"])?.toString()?.trim().orEmpty()
                            val value = item["value"]?.toString()?.trim().orEmpty()
                            if (name.isNotBlank() && value.isNotBlank()) {
                                specsMap[name] = value
                            }
                        }
                        else -> {
                            val text = item?.toString()?.trim().orEmpty()
                            if (text.isNotBlank() && text != "null") {
                                if (":" in text) {
                                    val idx = text.indexOf(':')
                                    val key = text.substring(0, idx).trim()
                                    val value = text.substring(idx + 1).trim()
                                    if (key.isNotEmpty() && value.isNotEmpty()) {
                                        specsMap[key] = value
                                        continue
                                    }
                                }
                                specsMap["feature_${featureIdx++}"] = text
                            }
                        }
                    }
                }
            }
            return specsMap
        }

        private fun parseCandidateList(raw: Any?): List<RecognizedProductCandidate> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val rawTitle = (m["title"] ?: m["product_name"] ?: m["name"])?.toString()?.trim().orEmpty()
                if (rawTitle.isBlank() || SearchQueryBuilder.isNonProductNoise(rawTitle)) return@mapNotNull null
                val candBrand = m["brand"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val candCategory = m["category"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "Other"
                val candModel = m["model"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val candColour = (m["colour"] ?: m["color"])?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val candQuery = SearchQueryBuilder.buildFromRawQuery(m["search_query"]?.toString()).validQueryOrNull
                    ?: SearchQueryBuilder.build(
                        SearchQueryBuilder.Input(
                            productName = rawTitle,
                            brand = candBrand,
                            model = candModel,
                            category = candCategory,
                            colour = candColour
                        )
                    ).validQueryOrNull.orEmpty()
                val conf = when (val c = m["confidence"]) {
                    is Number -> c.toFloat()
                    is String -> c.toFloatOrNull() ?: 0.5f
                    else -> 0.5f
                }.coerceIn(0.0f, 1.0f)
                RecognizedProductCandidate(
                    brand = candBrand,
                    title = rawTitle,
                    category = candCategory,
                    model = candModel,
                    colour = candColour,
                    searchQuery = candQuery,
                    confidence = conf
                )
            }.take(3)
        }

        fun toRecognizedProduct(
            structured: StructuredAiProduct,
            imageUri: String?,
            contextText: String?,
            defaultModelName: String = APPROVED_FREE_MODEL
        ): RecognizedProduct {
            val mergedSpecs = structured.specifications.toMutableMap()
            if (structured.colour != null && !mergedSpecs.containsKey("color") && !mergedSpecs.containsKey("colour")) {
                mergedSpecs["color"] = structured.colour
            }
            if (structured.model != null && !mergedSpecs.containsKey("model")) {
                mergedSpecs["model"] = structured.model
            }

            val effectiveStatus = when {
                structured.status == RecognitionStatus.UNIDENTIFIED ||
                    structured.productName.equals("Unidentified Product", ignoreCase = true) ||
                    structured.category.equals("Unidentified", ignoreCase = true) -> RecognitionStatus.UNIDENTIFIED
                structured.status == RecognitionStatus.PARTIAL || structured.candidates.size > 1 -> RecognitionStatus.PARTIAL
                else -> RecognitionStatus.IDENTIFIED
            }

            val confidence = if (effectiveStatus == RecognitionStatus.UNIDENTIFIED) {
                structured.confidence.coerceIn(0.0f, 0.25f)
            } else {
                structured.confidence.coerceIn(0.1f, 1.0f)
            }
            val brandPart = structured.brand?.let { "$it " } ?: ""

            val sanitizedSearchQuery = when (effectiveStatus) {
                RecognitionStatus.UNIDENTIFIED -> ""
                else -> {
                    SearchQueryBuilder.buildFromRawQuery(structured.searchQuery).validQueryOrNull
                        ?: SearchQueryBuilder.build(
                            SearchQueryBuilder.Input(
                                productName = structured.productName,
                                brand = structured.brand,
                                model = structured.model,
                                category = structured.category,
                                colour = structured.colour,
                                specifications = mergedSpecs
                            )
                        ).validQueryOrNull.orEmpty()
                }
            }

            return RecognizedProduct(
                id = UUID.randomUUID().toString(),
                title = structured.productName,
                brand = structured.brand,
                category = structured.category,
                specifications = mergedSpecs,
                confidence = confidence,
                imageUrl = imageUri,
                rawTextSnippet = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }
                    ?: if (effectiveStatus == RecognitionStatus.UNIDENTIFIED) "" else structured.productName,
                matchType = when (effectiveStatus) {
                    RecognitionStatus.IDENTIFIED -> MatchType.EXACT
                    RecognitionStatus.PARTIAL -> MatchType.LIKELY
                    RecognitionStatus.UNIDENTIFIED -> MatchType.SIMILAR
                },
                matchReason = structured.matchReason.ifBlank {
                    "Multimodal AI recognized $brandPart${structured.productName} (${(confidence * 100).toInt()}% confidence)."
                },
                isDemoFixture = structured.isDemoFixture,
                isLiveInference = structured.isLiveInference,
                colour = structured.colour,
                model = structured.model,
                status = effectiveStatus,
                recognitionSource = structured.recognitionSource,
                searchQuery = sanitizedSearchQuery,
                aiModelName = structured.aiModelName ?: defaultModelName,
                candidates = structured.candidates,
                diagnosticNote = structured.diagnosticNote,
                rateLimited = structured.rateLimited,
                cacheHit = structured.cacheHit
            )
        }

        fun extractPlainTextSnippet(json: String): String {
            val textMarker = "\"text\":"
            val index = json.indexOf(textMarker)
            if (index == -1) return ""
            val start = json.indexOf("\"", index + textMarker.length) + 1
            val end = json.indexOf("\"", start)
            return if (start > 0 && end > start) {
                json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"")
            } else {
                ""
            }
        }

        fun escapeJson(input: String): String {
            val sb = StringBuilder()
            for (c in input) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> {
                        if (c < ' ') {
                            sb.append(String.format("\\u%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            return sb.toString()
        }
    }

    data class ImagePayload(
        val base64Data: String? = null,
        val mimeType: String = "image/jpeg",
        val remoteUrl: String? = null,
        val sha256Hash: String? = null,
        val compressed: Boolean = false,
        val corruptError: String? = null
    )

    override fun getProviderName(): String = PROVIDER_NAME

    fun isCloudConfigured(): Boolean {
        return isValidApiKeyFormat(apiKey) || !endpointUrl.isNullOrBlank() || autoDetectBackendEndpoint
    }

    fun isRateLimitedNow(): Boolean = rateLimitUntilMs > System.currentTimeMillis()

    fun resetRateLimit() {
        rateLimitUntilMs = 0L
    }

    internal fun resolveActiveEndpointUrl(): String? {
        if (!endpointUrl.isNullOrBlank()) return endpointUrl
        if (!autoDetectBackendEndpoint) return null
        cachedBackendEndpointUrl?.let { return it }

        for (baseUrl in BACKEND_CANDIDATE_URLS) {
            try {
                val url = URL("$baseUrl/health")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 900
                    readTimeout = 900
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    val resolved = "$baseUrl$BACKEND_PROXY_PATH"
                    cachedBackendEndpointUrl = resolved
                    return resolved
                }
            } catch (_: Throwable) {
                // Probe next backend candidate
            }
        }
        return null
    }

    /**
     * Checks approved free-tier model endpoint availability via GET without spending generation tokens.
     */
    fun verifyModelAvailability(): ModelAvailabilityResult {
        validateFreeTierPolicy(modelName, freeTierOnly, maxCostUsd)
        val proxy = resolveActiveEndpointUrl()
        if (!proxy.isNullOrBlank()) {
            val statusUrl = proxy.removeSuffix(BACKEND_PROXY_PATH).trimEnd('/') + BACKEND_STATUS_PATH
            val resp = executeGet(statusUrl, mapOf("Accept" to "application/json"))
            val available = resp?.isSuccessful == true && (resp.body?.contains("\"available\":true") == true || resp.body?.contains("\"available\": true") == true)
            return ModelAvailabilityResult(
                available = available,
                modelName = modelName,
                statusCode = resp?.statusCode,
                reason = if (available) "Backend free-tier model reachable" else "Backend model unavailable (${resp?.statusCode})"
            )
        }

        if (!isValidApiKeyFormat(apiKey)) {
            return ModelAvailabilityResult(
                available = false,
                modelName = modelName,
                reason = "Missing or invalid GEMINI_API_KEY format"
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName"
        val resp = executeGet(
            url,
            mapOf(
                "x-goog-api-key" to apiKey!!.trim(),
                "Accept" to "application/json"
            )
        )
        return ModelAvailabilityResult(
            available = resp?.isSuccessful == true,
            modelName = modelName,
            statusCode = resp?.statusCode,
            reason = if (resp?.isSuccessful == true) "Approved free-tier model verified" else "Model check HTTP ${resp?.statusCode}"
        )
    }

    override suspend fun recognizeFromText(text: String): RecognizedProduct {
        validateFreeTierPolicy(modelName, freeTierOnly, maxCostUsd)
        if (SearchQueryBuilder.isNonProductNoise(text)) {
            return localFallback(text, imageUri = null, reason = "Rejected non-product text noise.")
        }
        if (isRateLimitedNow()) {
            return localFallback(text, imageUri = null, rateLimited = true, reason = RATE_LIMIT_MESSAGE)
        }
        if (!isCloudConfigured()) {
            return localFallback(text, imageUri = null)
        }

        return try {
            executeInference(imageUri = null, contextText = text) ?: localFallback(text, imageUri = null)
        } catch (_: Exception) {
            localFallback(text, imageUri = null)
        }
    }

    override suspend fun recognizeFromImage(imageUri: String, contextText: String?): RecognizedProduct {
        validateFreeTierPolicy(modelName, freeTierOnly, maxCostUsd)
        val cleanContext = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }

        val imagePayload = resolveImagePayload(imageUri)
        if (imagePayload.corruptError != null && cleanContext.isNullOrBlank()) {
            return unidentifiedResult(
                imageUri = imageUri,
                reason = imagePayload.corruptError,
                diagnosticNote = "Corrupt image rejected before calling free-tier AI."
            )
        }

        val cacheKey = buildCacheKey(imagePayload, cleanContext)
        if (cacheKey != null) {
            synchronized(imageResultCache) {
                imageResultCache[cacheKey]?.let { cached ->
                    return cached.copy(
                        id = UUID.randomUUID().toString(),
                        imageUrl = imageUri,
                        cacheHit = true
                    )
                }
            }
        }

        if (isRateLimitedNow()) {
            return localFallback(cleanContext, imageUri = imageUri, rateLimited = true, reason = RATE_LIMIT_MESSAGE)
        }

        if (!isCloudConfigured()) {
            return localFallback(cleanContext, imageUri = imageUri)
        }

        return try {
            val result = executeInference(imageUri = imageUri, contextText = cleanContext, preResolvedImage = imagePayload)
                ?: localFallback(cleanContext, imageUri = imageUri)
            if (cacheKey != null && result.isLiveInference && !result.isUnidentified && !result.rateLimited) {
                synchronized(imageResultCache) {
                    imageResultCache[cacheKey] = result
                }
            }
            result
        } catch (_: Exception) {
            localFallback(cleanContext, imageUri = imageUri)
        }
    }

    override suspend fun compareProducts(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetSpecs: Map<String, String>
    ): MatchResult {
        return fallbackProvider?.compareProducts(candidate, targetTitle, targetBrand, targetSpecs)
            ?: ProductMatchingEngine.compareProducts(
                candidate,
                RecognizedProduct(
                    id = "target",
                    title = targetTitle,
                    brand = targetBrand,
                    category = candidate.category,
                    specifications = targetSpecs,
                    confidence = 1.0f
                )
            )
    }

    private fun buildCacheKey(imagePayload: ImagePayload, cleanContext: String?): String? {
        val imgHash = imagePayload.sha256Hash ?: return null
        val hintHash = cleanContext?.takeIf { it.isNotBlank() }?.let { sha256Hex(it.toByteArray()) } ?: "nohint"
        return "$modelName:$imgHash:$hintHash"
    }

    private suspend fun executeInference(
        imageUri: String?,
        contextText: String?,
        preResolvedImage: ImagePayload? = null
    ): RecognizedProduct? {
        val cleanContext = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }
        val imagePayload = preResolvedImage ?: imageUri?.let { resolveImagePayload(it) }
        val hasImageContent = !imagePayload?.base64Data.isNullOrBlank() || !imagePayload?.remoteUrl.isNullOrBlank()
        if (!hasImageContent && cleanContext.isNullOrBlank()) {
            return null
        }

        val proxy = resolveActiveEndpointUrl()
        val response: NetworkResponse?
        if (!proxy.isNullOrBlank()) {
            val requestBody = buildBackendProxyPayload(
                text = cleanContext,
                contextText = cleanContext,
                imagePayload = imagePayload,
                modelName = modelName
            )
            response = postEndpoint(proxy, emptyMap(), requestBody)
        } else if (isValidApiKeyFormat(apiKey)) {
            val prompt = buildDirectPrompt(cleanContext)
            val requestBody = buildDirectGeminiPayload(prompt, imagePayload = imagePayload)
            // Single approved free-tier request ONLY — key passed securely in x-goog-api-key header, never URL param
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            response = postEndpoint(url, mapOf("x-goog-api-key" to apiKey!!.trim()), requestBody)
        } else {
            return null
        }

        if (response == null) return null

        // Strict zero-billing 429 handling: open circuit breaker immediately, never retry or upgrade model
        if (response.statusCode == 429) {
            rateLimitUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            return localFallback(cleanContext, imageUri = imageUri, rateLimited = true, reason = RATE_LIMIT_MESSAGE)
        }

        if (!response.isSuccessful || response.body.isNullOrBlank()) {
            return null
        }

        val structured = parseStructuredProduct(response.body, fallbackText = cleanContext.orEmpty(), defaultModelName = modelName)
        if (structured != null) {
            if (structured.rateLimited) {
                rateLimitUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                return localFallback(cleanContext, imageUri = imageUri, rateLimited = true, reason = RATE_LIMIT_MESSAGE)
            }
            return toRecognizedProduct(structured, imageUri = imageUri, contextText = cleanContext, defaultModelName = modelName)
        }

        val rawSnippet = extractPlainTextSnippet(response.body)
        if (rawSnippet.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(rawSnippet)) {
            val local = LocalProductParser.parse(rawSnippet, imageUrl = imageUri)
            return local.copy(
                isDemoFixture = false,
                isLiveInference = true,
                recognitionSource = RecognitionSource.LIVE_AI,
                aiModelName = modelName
            )
        }
        return null
    }

    private fun postEndpoint(urlStr: String, extraHeaders: Map<String, String>, requestBody: String): NetworkResponse? {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        ).apply { putAll(extraHeaders) }
        return try {
            networkTransport.post(urlStr, headers, requestBody)
        } catch (_: Exception) {
            null
        }
    }

    private fun executeGet(urlStr: String, headers: Map<String, String>): NetworkResponse? {
        getTransport?.let { transport ->
            return try {
                transport(urlStr, headers)
            } catch (_: Exception) {
                null
            }
        }
        return try {
            val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 10000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                connection.errorStream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
            }
            connection.disconnect()
            NetworkResponse(statusCode = code, body = body)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun localFallback(
        cleanHint: String?,
        imageUri: String?,
        rateLimited: Boolean = false,
        reason: String? = null
    ): RecognizedProduct {
        if (fallbackProvider != null && !cleanHint.isNullOrBlank()) {
            val delegated = fallbackProvider.recognizeFromText(cleanHint)
            return if (rateLimited) {
                delegated.copy(
                    rateLimited = true,
                    isLiveInference = false,
                    diagnosticNote = reason ?: RATE_LIMIT_MESSAGE,
                    matchReason = reason ?: RATE_LIMIT_MESSAGE
                )
            } else {
                delegated
            }
        }

        if (!cleanHint.isNullOrBlank() && !SearchQueryBuilder.isNonProductNoise(cleanHint)) {
            val parsed = LocalProductParser.parse(cleanHint, imageUrl = imageUri)
            return parsed.copy(
                rateLimited = rateLimited,
                diagnosticNote = reason ?: parsed.diagnosticNote,
                matchReason = if (rateLimited) RATE_LIMIT_MESSAGE else parsed.matchReason
            )
        }

        return unidentifiedResult(
            imageUri = imageUri,
            rateLimited = rateLimited,
            reason = reason ?: "Could not identify product offline. Enter product name manually or use store search links.",
            diagnosticNote = reason ?: "Local heuristic fallback requires product text or configured free-tier AI."
        )
    }

    private fun unidentifiedResult(
        imageUri: String?,
        rateLimited: Boolean = false,
        reason: String,
        diagnosticNote: String
    ): RecognizedProduct = RecognizedProduct(
        id = UUID.randomUUID().toString(),
        title = "Unidentified Product",
        brand = null,
        category = "Unidentified",
        specifications = emptyMap(),
        confidence = 0.1f,
        imageUrl = imageUri,
        rawTextSnippet = "",
        matchType = MatchType.SIMILAR,
        matchReason = reason,
        isDemoFixture = false,
        isLiveInference = false,
        status = RecognitionStatus.UNIDENTIFIED,
        recognitionSource = RecognitionSource.MANUAL_SEARCH,
        searchQuery = "",
        diagnosticNote = diagnosticNote,
        rateLimited = rateLimited
    )

    internal object MiniJson {
        fun parse(jsonStr: String): Any? = MiniJsonParser(jsonStr.trim()).parseValue()

        private class MiniJsonParser(private val s: String) {
            private var idx = 0

            fun parseValue(): Any? {
                skipWhitespace()
                if (idx >= s.length) return null
                return when (val c = s[idx]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    else -> if (c == '-' || c.isDigit()) parseNumber() else null
                }
            }

            private fun skipWhitespace() {
                while (idx < s.length && s[idx].isWhitespace()) idx++
            }

            private fun parseObject(): Map<String, Any?> {
                idx++
                val map = LinkedHashMap<String, Any?>()
                while (idx < s.length) {
                    skipWhitespace()
                    if (idx < s.length && s[idx] == '}') {
                        idx++
                        break
                    }
                    val key = parseString()
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ':') idx++
                    val value = parseValue()
                    map[key] = value
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ',') {
                        idx++
                    } else if (idx < s.length && s[idx] == '}') {
                        idx++
                        break
                    } else {
                        idx++
                    }
                }
                return map
            }

            private fun parseArray(): List<Any?> {
                idx++
                val list = ArrayList<Any?>()
                while (idx < s.length) {
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ']') {
                        idx++
                        break
                    }
                    list.add(parseValue())
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ',') {
                        idx++
                    } else if (idx < s.length && s[idx] == ']') {
                        idx++
                        break
                    } else {
                        idx++
                    }
                }
                return list
            }

            private fun parseString(): String {
                if (idx >= s.length || s[idx] != '"') return ""
                idx++
                val sb = StringBuilder()
                while (idx < s.length) {
                    val c = s[idx++]
                    if (c == '"') break
                    if (c == '\\' && idx < s.length) {
                        val esc = s[idx++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (idx + 4 <= s.length) {
                                    val hex = s.substring(idx, idx + 4)
                                    idx += 4
                                    sb.append(hex.toInt(16).toChar())
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return sb.toString()
            }

            private fun parseNumber(): Number {
                val start = idx
                if (idx < s.length && s[idx] == '-') idx++
                while (idx < s.length && (s[idx].isDigit() || s[idx] == '.' || s[idx] == 'e' || s[idx] == 'E' || s[idx] == '+' || s[idx] == '-')) {
                    idx++
                }
                val numStr = s.substring(start, idx)
                return if (numStr.contains('.') || numStr.contains('e', ignoreCase = true)) {
                    numStr.toDoubleOrNull() ?: 0.0
                } else {
                    numStr.toLongOrNull() ?: 0L
                }
            }

            private fun parseBoolean(): Boolean {
                return if (s.startsWith("true", idx)) {
                    idx += 4
                    true
                } else if (s.startsWith("false", idx)) {
                    idx += 5
                    false
                } else {
                    idx++
                    false
                }
            }

            private fun parseNull(): Any? {
                if (s.startsWith("null", idx)) {
                    idx += 4
                } else {
                    idx++
                }
                return null
            }
        }
    }
}
