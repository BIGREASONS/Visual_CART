package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchResult
import com.paypal.visualcart.domain.model.RecognizedProduct
import com.paypal.visualcart.domain.model.StructuredAiProduct
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
 * Pluggable multimodal cloud AI provider locked to Google AI Studio's free-tier
 * `gemini-3.5-flash-lite` model or the local backend `/api/v1/ai/recognize-product` gateway.
 *
 * Delegates all inference, zero-billing validation, image downscaling, rate-limit circuit breaking,
 * and structured JSON validation to [GeminiFreeVisionProvider].
 */
class CloudProductUnderstandingProvider(
    private val apiKey: String? = resolveApiKey(),
    private val endpointUrl: String? = resolveEndpointUrl(),
    private val modelName: String = resolveModelName(),
    private val fallbackProvider: ProductUnderstandingProvider = DemoProductRecognitionProvider(),
    private val networkTransport: NetworkTransport = DefaultHttpNetworkTransport(),
    private val autoDetectBackendEndpoint: Boolean = false
) : ProductUnderstandingProvider {

    private val delegate = GeminiFreeVisionProvider(
        apiKey = apiKey,
        endpointUrl = endpointUrl,
        modelName = modelName,
        fallbackProvider = fallbackProvider,
        networkTransport = networkTransport,
        autoDetectBackendEndpoint = autoDetectBackendEndpoint
    )

    companion object {
        const val PROVIDER_NAME = GeminiFreeVisionProvider.PROVIDER_NAME
        const val DEFAULT_MODEL = GeminiFreeVisionProvider.APPROVED_FREE_MODEL
        val FALLBACK_MODELS = listOf(DEFAULT_MODEL)
        const val BACKEND_PROXY_PATH = GeminiFreeVisionProvider.BACKEND_PROXY_PATH

        fun forBackend(): CloudProductUnderstandingProvider =
            CloudProductUnderstandingProvider(
                fallbackProvider = object : ProductUnderstandingProvider {
                    override suspend fun recognizeFromText(text: String) = LocalProductParser.parse(text)
                    override suspend fun recognizeFromImage(imageUri: String, contextText: String?) =
                        LocalProductParser.parse(contextText.orEmpty(), imageUrl = imageUri)
                    override suspend fun compareProducts(
                        candidate: RecognizedProduct,
                        targetTitle: String,
                        targetBrand: String?,
                        targetSpecs: Map<String, String>
                    ) = ProductMatchingEngine.compareProducts(
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
                    override fun getProviderName(): String = LocalProductParser::class.java.simpleName
                },
                autoDetectBackendEndpoint = true
            )

        private fun resolveApiKey(): String? =
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("gemini.api.key")?.takeIf { it.isNotBlank() }

        private fun resolveEndpointUrl(): String? =
            System.getenv("VISUAL_CART_AI_ENDPOINT")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("visualcart.ai.endpoint")?.takeIf { it.isNotBlank() }

        private fun resolveModelName(): String =
            System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("gemini.model")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_MODEL
    }

    typealias ImagePayload = GeminiFreeVisionProvider.ImagePayload

    override fun getProviderName(): String = delegate.getProviderName()

    fun isCloudConfigured(): Boolean = delegate.isCloudConfigured()

    fun verifyModelAvailability(): ModelAvailabilityResult = delegate.verifyModelAvailability()

    override suspend fun recognizeFromText(text: String): RecognizedProduct =
        delegate.recognizeFromText(text)

    override suspend fun recognizeFromImage(imageUri: String, contextText: String?): RecognizedProduct =
        delegate.recognizeFromImage(imageUri, contextText)

    override suspend fun compareProducts(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetSpecs: Map<String, String>
    ): MatchResult = delegate.compareProducts(candidate, targetTitle, targetBrand, targetSpecs)

    fun buildBackendProxyPayload(text: String?, contextText: String?, imagePayload: ImagePayload?): String =
        GeminiFreeVisionProvider.buildBackendProxyPayload(text, contextText, imagePayload, modelName)

    fun buildDirectGeminiPrompt(contextText: String?): String =
        GeminiFreeVisionProvider.buildDirectPrompt(contextText)

    fun buildDirectGeminiPayload(prompt: String, imagePayload: ImagePayload?): String =
        GeminiFreeVisionProvider.buildDirectGeminiPayload(prompt, imagePayload)

    fun resolveImagePayload(imageUri: String): ImagePayload =
        GeminiFreeVisionProvider.resolveImagePayload(imageUri)

    fun parseStructuredProduct(rawJson: String, fallbackText: String = ""): StructuredAiProduct? =
        GeminiFreeVisionProvider.parseStructuredProduct(rawJson, fallbackText, modelName)
}
