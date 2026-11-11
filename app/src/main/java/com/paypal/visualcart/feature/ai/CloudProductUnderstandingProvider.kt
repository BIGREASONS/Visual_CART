package com.paypal.visualcart.feature.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.paypal.visualcart.domain.model.MatchResult
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.RecognizedProduct
import com.paypal.visualcart.domain.model.StructuredAiProduct
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
    private val readTimeoutMs: Int = 15000
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
        const val PROVIDER_NAME = "PayPal Cloud Multimodal AI (Gemini 2.5 / Proxy)"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
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
        val proxy = resolveActiveEndpointUrl()
        val targetUrl = if (!proxy.isNullOrBlank()) {
            proxy
        } else if (!apiKey.isNullOrBlank()) {
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        } else {
            return null
        }

        val requestBody = if (!proxy.isNullOrBlank()) {
            buildBackendProxyPayload(
                text = text,
                contextText = text,
                imagePayload = null
            )
        } else {
            val prompt = buildDirectGeminiPrompt(contextText = text)
            buildDirectGeminiPayload(prompt, imagePayload = null)
        }

        val response = callEndpoint(targetUrl, requestBody)
        if (response == null || !response.isSuccessful || response.body.isNullOrBlank()) {
            return null
        }

        val structured = parseStructuredProduct(response.body, fallbackText = text)
        if (structured != null) {
            return toRecognizedProduct(structured, imageUri = null, contextText = text)
        }

        // Secondary fallback: extract raw text from LLM response and parse locally
        val rawSnippet = extractPlainTextSnippet(response.body)
        if (rawSnippet.isNotBlank()) {
            val local = LocalProductParser.parse(rawSnippet)
            return local.copy(isDemoFixture = false, isLiveInference = true)
        }

        return null
    }

    private fun executeImageInference(imageUri: String, contextText: String?): RecognizedProduct? {
        val imagePayload = resolveImagePayload(imageUri)
        val proxy = resolveActiveEndpointUrl()

        val targetUrl = if (!proxy.isNullOrBlank()) {
            proxy
        } else if (!apiKey.isNullOrBlank()) {
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        } else {
            return null
        }

        val requestBody = if (!proxy.isNullOrBlank()) {
            buildBackendProxyPayload(
                text = contextText,
                contextText = contextText,
                imagePayload = imagePayload
            )
        } else {
            val prompt = buildDirectGeminiPrompt(contextText = contextText)
            buildDirectGeminiPayload(prompt, imagePayload = imagePayload)
        }

        val response = callEndpoint(targetUrl, requestBody)
        if (response == null || !response.isSuccessful || response.body.isNullOrBlank()) {
            return null
        }

        val structured = parseStructuredProduct(response.body, fallbackText = contextText.orEmpty())
        if (structured != null) {
            return toRecognizedProduct(structured, imageUri = imageUri, contextText = contextText)
        }

        // Secondary fallback
        val rawSnippet = extractPlainTextSnippet(response.body)
        if (rawSnippet.isNotBlank()) {
            val local = LocalProductParser.parse(rawSnippet, imageUrl = imageUri)
            return local.copy(isDemoFixture = false, isLiveInference = true)
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

        val confidence = structured.confidence.coerceIn(0.1f, 1.0f)
        val brandPart = structured.brand?.let { "$it " } ?: ""

        return RecognizedProduct(
            id = UUID.randomUUID().toString(),
            title = structured.productName,
            brand = structured.brand,
            category = structured.category,
            specifications = mergedSpecs,
            confidence = confidence,
            imageUrl = imageUri,
            rawTextSnippet = contextText ?: structured.productName,
            matchType = MatchType.EXACT,
            matchReason = structured.matchReason.ifBlank {
                "Multimodal AI recognized $brandPart${structured.productName} (${(confidence * 100).toInt()}% confidence)."
            },
            isDemoFixture = structured.isDemoFixture,
            isLiveInference = structured.isLiveInference,
            colour = structured.colour,
            model = structured.model
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
            text?.takeIf { it.isNotBlank() },
            contextText?.takeIf { it.isNotBlank() }
        ).distinct().joinToString(" - ")
        if (effectiveHint.isNotBlank()) {
            fields.add("\"hint_text\":\"${escapeJson(effectiveHint)}\"")
        }
        if (!text.isNullOrBlank()) {
            fields.add("\"text\":\"${escapeJson(text)}\"")
        }
        if (!contextText.isNullOrBlank()) {
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
        val contextPart = if (!contextText.isNullOrBlank()) " Context/Hint: $contextText." else ""
        return """
            You are an expert multimodal e-commerce product recognition engine for PayPal Visual Cart.
            Analyze the product in this image and/or text context.$contextPart
            Extract structured specifications and respond strictly with valid JSON conforming to this schema:
            {
              "brand": "Brand or manufacturer name, or null",
              "product_name": "Canonical product name without marketing hype",
              "category": "Electronics | Footwear | Audio | Computers | Gaming | Fashion | Home | Accessories | Other",
              "colour": "Color or finish (e.g., Space Gray, Silver, Triple Black, Graphite) or null",
              "model": "Model identifier or series (e.g., 990 EVO, WH-1000XM5, Air Max 270) or null",
              "specifications": {
                "key": "value"
              },
              "confidence": 0.95,
              "match_reason": "Brief human-readable explanation"
            }
        """.trimIndent()
    }

    fun buildDirectGeminiPayload(
        prompt: String,
        imagePayload: ImagePayload?
    ): String {
        val parts = mutableListOf<String>()
        if (imagePayload?.base64Data != null) {
            parts.add("""{"inlineData":{"mimeType":"${imagePayload.mimeType}","data":"${imagePayload.base64Data}"}}""")
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
                val compressed = compressLargeImageIfPossible(file)
                val bytes = compressed ?: file.readBytes()
                val b64 = Base64.getEncoder().encodeToString(bytes)
                val ext = file.extension.lowercase()
                val mime = if (compressed != null) {
                    "image/jpeg"
                } else {
                    when (ext) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "heic", "heif" -> "image/heic"
                        else -> "image/jpeg"
                    }
                }
                return ImagePayload(base64Data = b64, mimeType = mime)
            } catch (_: Exception) {
                // Ignore read errors and treat as raw identifier
            }
        }

        // 4. Raw base64 string
        if (trimmed.length > 100 && !trimmed.contains(" ") && !trimmed.contains("/") && !trimmed.contains("\\")) {
            return ImagePayload(base64Data = trimmed, mimeType = "image/jpeg")
        }

        return ImagePayload(remoteUrl = trimmed)
    }

    private fun compressLargeImageIfPossible(file: File, maxBytes: Long = 512_000L, maxDim: Int = 1024): ByteArray? {
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

                // Case 1: Gemini candidates response
                val candidates = rootMap["candidates"] as? List<*>
                if (!candidates.isNullOrEmpty()) {
                    val firstCandidate = candidates[0] as? Map<*, *>
                    val content = firstCandidate?.get("content") as? Map<*, *>
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
                            // Plain text candidate: parse locally via LocalProductParser
                            val local = LocalProductParser.parse(inner)
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
                                isLiveInference = true
                            )
                        }
                    }
                }

                // Case 2: Backend proxy wrapped in "data"
                if (targetMap == null && rootMap.containsKey("data") && rootMap["data"] is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    targetMap = rootMap["data"] as Map<String, Any?>
                }

                // Case 3: Flat structured object (only if not an unparsed candidates envelope)
                if (targetMap == null && !rootMap.containsKey("candidates")) {
                    targetMap = rootMap
                }
            }

            if (targetMap == null) return null

            val brand = targetMap["brand"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            val productName = (targetMap["product_name"] ?: targetMap["productName"] ?: targetMap["title"] ?: targetMap["name"])
                ?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: fallbackText.takeIf { it.isNotBlank() }
                ?: return null

            val category = targetMap["category"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "General"
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
            }.coerceIn(0.1f, 1.0f)

            val matchReason = targetMap["match_reason"]?.toString()
                ?: "Multimodal AI identified ${brand ?: ""} $productName with ${(confidence * 100).toInt()}% confidence."

            val source = targetMap["source"]?.toString()?.lowercase()
            val isUnidentified = source == "needs_gemini_key" ||
                source == "vision_unidentified" ||
                productName.equals("Unidentified Product", ignoreCase = true) ||
                category.equals("Unidentified", ignoreCase = true)

            val isDemo = targetMap["is_demo_fixture"] as? Boolean ?: false
            val isLive = if (isUnidentified) {
                false
            } else {
                targetMap["is_live_inference"] as? Boolean ?: (!isDemo)
            }

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
                isLiveInference = isLive
            )
        } catch (_: Exception) {
            null
        }
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
