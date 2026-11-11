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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

/**
 * Network abstraction for executing HTTP requests against Google Gemini or backend AI proxy.
 * Enables zero-dependency production networking and mock injection in unit tests.
 */
fun interface NetworkTransport {
    fun post(url: String, headers: Map<String, String>, body: String): NetworkResponse
}

data class NetworkResponse(
    val statusCode: Int,
    val body: String?
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * Default production HTTP transport using HttpURLConnection.
 */
class DefaultHttpNetworkTransport(
    private val connectTimeoutMs: Int = 5000,
    private val readTimeoutMs: Int = 25000
) : NetworkTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): NetworkResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val code = connection.responseCode
        val responseBody = if (code in 200..299) {
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
        } else {
            connection.errorStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }
        }
        return NetworkResponse(statusCode = code, body = responseBody)
    }
}

/**
 * Pluggable multimodal cloud AI provider for Google Gemini 1.5 Flash and backend AI proxy gateway.
 *
 * Security & Zero-Hardcoded-Keys:
 * - Direct Gemini API keys remain secure server-side behind the /api/v1/ai/recognize-product endpoint.
 * - If direct client Gemini execution is desired, keys are injected strictly via runtime environment
 *   variable (GEMINI_API_KEY) or system property (gemini.api.key).
 * - Gracefully falls back to on-device DemoProductRecognitionProvider and LocalProductParser
 *   when offline, unconfigured, or when endpoints return non-200 responses.
 * - Results are clearly tagged as isLiveInference vs isDemoFixture.
 */
class CloudProductUnderstandingProvider(
    private val apiKey: String? = resolveApiKey(),
    private val endpointUrl: String? = resolveEndpointUrl(),
    private val modelName: String = resolveModelName(),
    private val fallbackProvider: ProductUnderstandingProvider = DemoProductRecognitionProvider(),
    private val networkTransport: NetworkTransport = DefaultHttpNetworkTransport(),
    private val autoDetectBackendEndpoint: Boolean = false
) : ProductUnderstandingProvider {

    @Volatile
    private var cachedBackendEndpointUrl: String? = null

    companion object {
        const val PROVIDER_NAME = "PayPal Cloud Multimodal AI (Gemini Vision / Proxy)"
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        val FALLBACK_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        const val BACKEND_PROXY_PATH = "/api/v1/ai/recognize-product"
        private val BACKEND_CANDIDATE_URLS = listOf(
            "http://localhost:8000",
            "http://10.0.2.2:8000"
        )

        fun forBackend(): CloudProductUnderstandingProvider =
            CloudProductUnderstandingProvider(autoDetectBackendEndpoint = true)

        private const val ENV_GEMINI_KEY = "GEMINI_API_KEY"
        private const val ENV_PROXY_ENDPOINT = "VISUAL_CART_AI_ENDPOINT"
        private const val ENV_GEMINI_MODEL = "GEMINI_MODEL"
        private const val PROP_GEMINI_KEY = "gemini.api.key"
        private const val PROP_PROXY_ENDPOINT = "visualcart.ai.endpoint"
        private const val PROP_GEMINI_MODEL = "gemini.model"

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
                ?: DEFAULT_MODEL
        }
    }

    override fun getProviderName(): String = PROVIDER_NAME

    /**
     * Checks if the cloud provider has credentials or endpoint configured.
     */
    fun isCloudConfigured(): Boolean {
        return !apiKey.isNullOrBlank() || !endpointUrl.isNullOrBlank() || autoDetectBackendEndpoint
    }

    internal fun resolveActiveEndpointUrl(): String? {
        if (!endpointUrl.isNullOrBlank()) return endpointUrl
        if (!autoDetectBackendEndpoint) return null
        cachedBackendEndpointUrl?.let { return it }

        for (baseUrl in BACKEND_CANDIDATE_URLS) {
            try {
                val url = java.net.URL("$baseUrl/health")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
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
                // Probe next candidate
            }
        }
        return null
    }

    override suspend fun recognizeFromText(text: String): RecognizedProduct {
        if (!isCloudConfigured()) {
            return fallbackProvider.recognizeFromText(text)
        }

        return try {
            val result = executeTextInference(text)
            result ?: fallbackProvider.recognizeFromText(text)
        } catch (_: Exception) {
            // Graceful fallback on network timeout, 4xx, 5xx, or parsing errors
            fallbackProvider.recognizeFromText(text)
        }
    }

    override suspend fun recognizeFromImage(imageUri: String, contextText: String?): RecognizedProduct {
        if (!isCloudConfigured()) {
            return fallbackProvider.recognizeFromImage(imageUri, contextText)
        }

        return try {
            val result = executeImageInference(imageUri, contextText)
            result ?: fallbackProvider.recognizeFromImage(imageUri, contextText)
        } catch (_: Exception) {
            fallbackProvider.recognizeFromImage(imageUri, contextText)
        }
    }

    override suspend fun compareProducts(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetSpecs: Map<String, String>
    ): MatchResult {
        // Fast, deterministic equivalence matching on-device
        return fallbackProvider.compareProducts(candidate, targetTitle, targetBrand, targetSpecs)
    }

    // ---------------------------------------------------------------------------------------------
    // Inference Execution
    // ---------------------------------------------------------------------------------------------

    private fun executeTextInference(text: String): RecognizedProduct? {
        if (SearchQueryBuilder.isNonProductNoise(text)) return null
        val proxy = resolveActiveEndpointUrl()
        val response: NetworkResponse?
        if (!proxy.isNullOrBlank()) {
            val requestBody = buildBackendProxyPayload(
                text = text,
                contextText = text,
                imagePayload = null
            )
            response = callEndpoint(proxy, requestBody)
        } else if (!apiKey.isNullOrBlank()) {
            val prompt = buildDirectGeminiPrompt(contextText = text)
            val requestBody = buildDirectGeminiPayload(prompt, imagePayload = null)
            val candidateModels = (listOf(modelName) + FALLBACK_MODELS).distinct()
            var best: NetworkResponse? = null
            for (candidateModel in candidateModels) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"
                val attempt = callEndpoint(url, requestBody)
                if (attempt != null && attempt.isSuccessful && !attempt.body.isNullOrBlank()) {
                    best = attempt
                    break
                }
                best = attempt
                val code = attempt?.statusCode ?: 0
                if (code != 404 && code != 400 && code != 503 && code != 429) {
                    break
                }
            }
            response = best
        } else {
            return null
        }

        if (response == null || !response.isSuccessful || response.body.isNullOrBlank()) {
            return null
        }

        val structured = parseStructuredProduct(response.body, fallbackText = text)
        if (structured != null) {
            return toRecognizedProduct(structured, imageUri = null, contextText = text)
        }

        // Secondary fallback: extract raw text from LLM response and parse locally
        val rawSnippet = extractPlainTextSnippet(response.body)
        if (rawSnippet.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(rawSnippet)) {
            val local = LocalProductParser.parse(rawSnippet)
            return local.copy(
                isDemoFixture = false,
                isLiveInference = true,
                recognitionSource = RecognitionSource.LIVE_AI,
                aiModelName = modelName
            )
        }

        return null
    }

    private fun redactUrlForLog(url: String): String =
        url.replace(Regex("([?&]key=)[^&]+"), "$1***")

    private fun logAiDiag(msg: String) {
        try {
            println("VisualCartAI: $msg")
        } catch (_: Throwable) {
            // Safe no-op
        }
    }

    private fun executeImageInference(imageUri: String, contextText: String?): RecognizedProduct? {
        val cleanContext = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }
        val imagePayload = resolveImagePayload(imageUri)
        val hasImageContent = !imagePayload.base64Data.isNullOrBlank() || !imagePayload.remoteUrl.isNullOrBlank()
        if (!hasImageContent && cleanContext.isNullOrBlank()) {
            logAiDiag("skipped inference: no decodable image bytes and no clean text hint")
            return null
        }

        val proxy = resolveActiveEndpointUrl()
        val requestId = UUID.randomUUID().toString().take(8)
        logAiDiag(
            "request[$requestId] mime=${imagePayload.mimeType} base64_chars=${imagePayload.base64Data?.length ?: 0} " +
                "has_remote_url=${!imagePayload.remoteUrl.isNullOrBlank()} mode=${if (!proxy.isNullOrBlank()) "proxy" else "direct"}"
        )

        val response: NetworkResponse?
        var resolvedTargetUrl: String? = null
        if (!proxy.isNullOrBlank()) {
            resolvedTargetUrl = proxy
            val requestBody = buildBackendProxyPayload(
                text = cleanContext,
                contextText = cleanContext,
                imagePayload = imagePayload
            )
            response = callEndpoint(proxy, requestBody)
        } else if (!apiKey.isNullOrBlank()) {
            val prompt = buildDirectGeminiPrompt(contextText = cleanContext)
            val requestBody = buildDirectGeminiPayload(prompt, imagePayload = imagePayload)
            val candidateModels = (listOf(modelName) + FALLBACK_MODELS).distinct()
            var bestResponse: NetworkResponse? = null
            for (candidateModel in candidateModels) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"
                resolvedTargetUrl = url
                val attempt = callEndpoint(url, requestBody)
                if (attempt != null && attempt.isSuccessful && !attempt.body.isNullOrBlank()) {
                    bestResponse = attempt
                    break
                }
                bestResponse = attempt
                val code = attempt?.statusCode ?: 0
                if (code != 404 && code != 400 && code != 503 && code != 429) {
                    break
                }
            }
            response = bestResponse
        } else {
            return null
        }

        if (response == null || !response.isSuccessful || response.body.isNullOrBlank()) {
            logAiDiag("request[$requestId] failed status=${response?.statusCode} endpoint=${resolvedTargetUrl?.let(::redactUrlForLog)}")
            return null
        }

        val structured = parseStructuredProduct(response.body, fallbackText = cleanContext.orEmpty())
        if (structured != null) {
            logAiDiag(
                "request[$requestId] parsed status=${structured.status} source=${structured.recognitionSource} " +
                    "category=${structured.category} confidence=${structured.confidence} query='${structured.searchQuery}'"
            )
            return toRecognizedProduct(structured, imageUri = imageUri, contextText = cleanContext)
        }

        // Secondary fallback
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

    private fun callEndpoint(urlStr: String, requestBody: String): NetworkResponse? {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
        return try {
            networkTransport.post(urlStr, headers, requestBody)
        } catch (_: Exception) {
            null
        }
    }

    private fun toRecognizedProduct(
        structured: StructuredAiProduct,
        imageUri: String?,
        contextText: String?
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
            aiModelName = structured.aiModelName ?: modelName,
            candidates = structured.candidates,
            diagnosticNote = structured.diagnosticNote
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Payload Construction
    // ---------------------------------------------------------------------------------------------

    fun buildBackendProxyPayload(
        text: String?,
        contextText: String?,
        imagePayload: ImagePayload?
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

    fun buildDirectGeminiPrompt(contextText: String?): String {
        val cleanContext = contextText?.takeIf { !SearchQueryBuilder.isNonProductNoise(it) }
        val contextPart = if (!cleanContext.isNullOrBlank()) {
            "\nOptional clean user text hint (treat only as secondary context, inspect the actual image first): \"$cleanContext\"."
        } else {
            ""
        }
        return """
            You are a zero-hallucination multimodal e-commerce product recognition engine for PayPal Visual Cart.
            Inspect the actual pixels, logos, labels, shape, material, and visible text in the image.$contextPart
            CRITICAL ANTI-HALLUCINATION RULES:
            - NEVER invent a brand, model number, price, or electronics category if it is not clearly legible or visually certain.
            - NEVER copy filenames, capture timestamps (e.g. capture_*, shared image.jpg), or storage paths as product_name or search_query.
            - If the image shows industrial or raw materials (such as PVC/metal pipes, fasteners, cables, timber), set category to "Industrial Materials" or "Hardware & Tools" — NEVER default to "Electronics".
            - If the product category/material is visible (e.g. "Metal pipes") without an exact brand/SKU, set status to "PARTIAL", include `"Material": "Uncertain from image alone"` or `"Specifications": "Not verified"` in specifications, and give a clean generic search_query like "metal pipe" or "galvanized steel pipe".
            - If 2 or 3 distinct candidate matches are plausible, populate "candidates" (up to 3 items) and set status to "PARTIAL".
            - If the image is blank, unreadable, or ambiguous without a clear product, set status to "UNIDENTIFIED", product_name to "Unidentified Product", category to "Unidentified", search_query to "", confidence <= 0.25.

            Respond strictly with valid JSON matching:
            {
              "status": "IDENTIFIED | PARTIAL | UNIDENTIFIED",
              "brand": "string or null",
              "product_name": "Canonical human-readable product name or descriptive object phrase",
              "category": "Industrial Materials | Hardware & Tools | Electronics | Computers | Audio | Gaming | Footwear | Fashion | Home | Accessories | Other | Unidentified",
              "model": "string or null",
              "colour": "string or null",
              "search_query": "Clean merchant search phrase without filenames or timestamps (empty if UNIDENTIFIED)",
              "specifications": {"key": "value"},
              "candidates": [
                {
                  "brand": "string or null",
                  "title": "Candidate product name",
                  "category": "Category",
                  "model": "string or null",
                  "colour": "string or null",
                  "search_query": "Clean search phrase",
                  "confidence": 0.55
                }
              ],
              "confidence": 0.85,
              "match_reason": "Concise visual evidence explaining what is visible vs uncertain"
            }
        """.trimIndent()
    }

    fun buildDirectGeminiPayload(
        prompt: String,
        imagePayload: ImagePayload?
    ): String {
        val parts = mutableListOf<String>()
        if (imagePayload?.base64Data != null) {
            parts.add("""{"inlineData":{"mimeType":"${escapeJson(imagePayload.mimeType)}","data":"${imagePayload.base64Data}"}}""")
        }
        parts.add("""{"text":"${escapeJson(prompt)}"}""")

        return """
            {
              "contents": [
                {
                  "parts": [
                    ${parts.joinToString(",")}
                  ]
                }
              ],
              "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": 0.1
              }
            }
        """.trimIndent()
    }

    data class ImagePayload(
        val base64Data: String? = null,
        val mimeType: String = "image/jpeg",
        val remoteUrl: String? = null
    )

    fun resolveImagePayload(imageUri: String): ImagePayload {
        val trimmed = imageUri.trim()

        // 1. Data URI format: data:image/png;base64,iVBORw0...
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val mime = trimmed.substringAfter("data:").substringBefore(";base64,")
            val b64 = trimmed.substringAfter(";base64,")
            return ImagePayload(base64Data = b64, mimeType = mime.ifBlank { "image/jpeg" })
        }

        // 2. Remote HTTP / HTTPS URL
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return ImagePayload(remoteUrl = trimmed)
        }

        // 3. Local file path (file:///path or standard absolute path)
        val filePath = if (trimmed.startsWith("file://", ignoreCase = true)) {
            trimmed.removePrefix("file://")
        } else {
            trimmed
        }

        val file = File(filePath)
        if (file.exists() && file.isFile && file.length() > 0) {
            try {
                val rawBytes = file.readBytes()
                val sniffedMime = sniffMimeTypeFromBytes(rawBytes)
                val compressed = compressLargeImageIfPossible(file)
                val finalBytes = compressed ?: rawBytes
                val b64 = Base64.getEncoder().encodeToString(finalBytes)
                val finalMime = if (compressed != null) {
                    "image/jpeg"
                } else {
                    sniffedMime ?: when (file.extension.lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        "heic", "heif" -> "image/heic"
                        else -> "image/jpeg"
                    }
                }
                return ImagePayload(base64Data = b64, mimeType = finalMime)
            } catch (_: Exception) {
                // Ignore read errors
            }
        }

        // 4. Raw base64 string
        if (trimmed.length > 100 && !trimmed.contains(" ") && !trimmed.contains("/") && !trimmed.contains("\\")) {
            return ImagePayload(base64Data = trimmed, mimeType = "image/jpeg")
        }

        // Never pass raw local/content URIs or temp filenames as remoteUrl
        return ImagePayload(base64Data = null, mimeType = "image/jpeg", remoteUrl = null)
    }

    private fun sniffMimeTypeFromBytes(bytes: ByteArray): String? {
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

    private fun compressLargeImageIfPossible(file: File, maxBytes: Long = 900_000L, maxDim: Int = 1536): ByteArray? {
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

            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                scaled.recycle()
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Structured JSON Response Parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses structured AI product data from backend proxy or direct Gemini JSON responses.
     */
    fun parseStructuredProduct(rawJson: String, fallbackText: String = ""): StructuredAiProduct? {
        return try {
            val parsedRoot = MiniJson.parse(rawJson) ?: return null
            var targetMap: Map<String, Any?>? = null

            if (parsedRoot is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val rootMap = parsedRoot as Map<String, Any?>

                // Case 1: Gemini generateContent envelope (candidates[0].content.parts)
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
                            // Plain text candidate: reject raw capture filenames before falling back to local parser
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
                                aiModelName = modelName
                            )
                        }
                    }
                }

                // Case 2: Backend proxy wrapped in "data"
                if (targetMap == null && rootMap.containsKey("data") && rootMap["data"] is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    targetMap = rootMap["data"] as Map<String, Any?>
                }

                // Case 3: Flat structured object (including proxy payloads that carry disambiguation candidates)
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
            val rawProductName = (targetMap["product_name"] ?: targetMap["productName"] ?: targetMap["title"] ?: targetMap["name"])
                ?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: fallbackText.takeIf { it.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(it) }
                ?: return null

            val productName = if (SearchQueryBuilder.isNonProductNoise(rawProductName) &&
                !rawProductName.equals("Unidentified Product", ignoreCase = true)
            ) {
                "Unidentified Product"
            } else {
                rawProductName
            }

            val rawCategory = targetMap["category"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            val category = when {
                productName.equals("Unidentified Product", ignoreCase = true) -> "Unidentified"
                rawCategory.isNullOrBlank() || rawCategory.equals("General", ignoreCase = true) -> "Other"
                else -> rawCategory
            }
            val colour = (targetMap["colour"] ?: targetMap["color"])?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            val model = (targetMap["model"] ?: targetMap["model_number"] ?: targetMap["modelNumber"])?.toString()?.takeIf { it.isNotBlank() && it != "null" }

            val rawSpecs = targetMap["specifications"] ?: targetMap["specs"]
            val specsMap = mutableMapOf<String, String>()
            if (rawSpecs is Map<*, *>) {
                for ((k, v) in rawSpecs) {
                    if (k != null && v != null) {
                        specsMap[k.toString()] = v.toString()
                    }
                }
            }
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
            val statusStr = targetMap["status"]?.toString()?.uppercase()

            val isUnidentified = statusStr == "UNIDENTIFIED" ||
                source == "needs_gemini_key" ||
                source == "vision_unidentified" ||
                source == "rejected_noise" ||
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
                isUnidentified -> false
                recognitionSourceStr == "LIVE_AI" -> true
                recognitionSourceStr == "FALLBACK" || source == "local_fallback" -> false
                else -> targetMap["is_live_inference"] as? Boolean ?: (!isDemo)
            }
            val recognitionSource = when {
                recognitionSourceStr == "LIVE_AI" -> RecognitionSource.LIVE_AI
                recognitionSourceStr == "DEMO" || isDemo -> RecognitionSource.DEMO
                recognitionSourceStr == "FALLBACK" || source == "needs_gemini_key" || source == "local_fallback" || source == "vision_unidentified" -> RecognitionSource.FALLBACK
                isLive -> RecognitionSource.LIVE_AI
                else -> RecognitionSource.FALLBACK
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
                ?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: modelName
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
                diagnosticNote = diagnosticNote
            )
        } catch (_: Exception) {
            null
        }
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

    private fun extractPlainTextSnippet(json: String): String {
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

    private fun escapeJson(input: String): String {
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

    // ---------------------------------------------------------------------------------------------
    // Pure Kotlin Lightweight JSON Parser (Runs without Android stub errors in JVM tests)
    // ---------------------------------------------------------------------------------------------

    internal object MiniJson {
        fun parse(jsonStr: String): Any? {
            val parser = MiniJsonParser(jsonStr.trim())
            return parser.parseValue()
        }

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
                val map = mutableMapOf<String, Any?>()
                idx++ // skip '{'
                skipWhitespace()
                if (idx < s.length && s[idx] == '}') {
                    idx++
                    return map
                }
                while (idx < s.length) {
                    skipWhitespace()
                    val key = parseString()
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ':') idx++
                    val value = parseValue()
                    map[key] = value
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ',') {
                        idx++
                        continue
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
                val list = mutableListOf<Any?>()
                idx++ // skip '['
                skipWhitespace()
                if (idx < s.length && s[idx] == ']') {
                    idx++
                    return list
                }
                while (idx < s.length) {
                    val value = parseValue()
                    list.add(value)
                    skipWhitespace()
                    if (idx < s.length && s[idx] == ',') {
                        idx++
                        continue
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
                idx++ // skip opening quote
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
