package com.paypal.visualcart.feature.payment

import com.paypal.visualcart.domain.model.MerchantListing
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data structures for PayPal backend communication.
 */
data class CreateOrderServerResult(
    val orderId: String,
    val status: String,
    val approveUrl: String,
    val currency: String,
    val amount: Double,
    val isLiveSandbox: Boolean,
    val orderSummary: OrderSummary? = null,
    val errorMessage: String? = null
)

data class CaptureOrderServerResult(
    val orderId: String,
    val captureId: String,
    val status: String,
    val amount: Double,
    val currency: String,
    val payerEmail: String? = null,
    val payerName: String? = null,
    val payerId: String? = null,
    val createTime: String? = null,
    val isLiveSandbox: Boolean = false,
    val errorMessage: String? = null,
    val alreadyCaptured: Boolean = false,
    val duplicateCapturePrevented: Boolean = false,
    val debugId: String? = null,
    val issue: String? = null
)

data class PayPalServerConfig(
    val clientId: String,
    val mode: String,
    val defaultCurrency: String,
    val brandName: String,
    val isSandbox: Boolean,
    val isLiveConfigured: Boolean,
    val deepLinkScheme: String
)

/**
 * Client for PayPal Visual Cart FastAPI Backend service.
 * Follows zero-hardcoded-keys principle:
 * All secret credentials and PayPal OAuth tokens remain securely on the backend.
 * Uses standard HttpURLConnection without external library overhead.
 */
open class PayPalBackendClient(
    private val configuredBaseUrl: String? = null,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 15000
) {

    @Volatile
    private var detectedBaseUrl: String? = null

    @Volatile
    var lastErrorMessage: String? = null
        protected set

    val baseUrl: String
        get() = detectedBaseUrl ?: configuredBaseUrl?.takeIf { it.isNotBlank() } ?: resolveBaseUrl()

    companion object {
        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:8000"
        const val DEFAULT_LOCALHOST_URL = "http://localhost:8000"
        const val ENV_BACKEND_URL = "PAYPAL_BACKEND_URL"
        const val PROP_BACKEND_URL = "paypal.backend.url"
        const val DEFAULT_RETURN_URL = "visualcart://paypal-return"
        const val DEFAULT_CANCEL_URL = "visualcart://paypal-cancel"

        fun resolveBaseUrl(): String {
            return System.getenv(ENV_BACKEND_URL)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_BACKEND_URL)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_EMULATOR_URL
        }
    }

    /**
     * Checks if the backend server is reachable and responding.
     * Automatically adapts between Android emulator (10.0.2.2) and physical USB device (localhost via adb reverse).
     */
    open fun isBackendAvailable(): Boolean {
        val candidate = configuredBaseUrl?.takeIf { it.isNotBlank() } ?: resolveBaseUrl()
        if (checkHealth(candidate)) {
            detectedBaseUrl = candidate
            return true
        }
        if (candidate == DEFAULT_EMULATOR_URL && checkHealth(DEFAULT_LOCALHOST_URL)) {
            detectedBaseUrl = DEFAULT_LOCALHOST_URL
            return true
        }
        return false
    }

    private fun checkHealth(targetUrl: String): Boolean {
        return try {
            val url = URL("$targetUrl/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetches public PayPal configuration from the backend.
     */
    open fun getConfiguration(): PayPalServerConfig? {
        return try {
            val responseText = executeGet("$baseUrl/api/v1/payments/paypal/configuration") ?: return null
            PayPalServerConfig(
                clientId = extractString(responseText, "client_id") ?: "sb-client-id",
                mode = extractString(responseText, "mode") ?: "sandbox",
                defaultCurrency = extractString(responseText, "default_currency") ?: "USD",
                brandName = extractString(responseText, "brand_name") ?: "PayPal Visual Cart Demo Store",
                isSandbox = extractBoolean(responseText, "is_sandbox") ?: true,
                isLiveConfigured = extractBoolean(responseText, "is_live_configured") ?: false,
                deepLinkScheme = extractString(responseText, "deep_link_scheme") ?: "paypalvisualcart"
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Calls POST /api/v1/payments/paypal/create-order on the backend.
     */
    open fun createOrder(
        listing: MerchantListing,
        returnUrl: String = DEFAULT_RETURN_URL,
        cancelUrl: String = DEFAULT_CANCEL_URL,
        quantity: Int = 1,
        taxRate: Double = 0.18,
        deliveryFee: Double = 0.0
    ): CreateOrderServerResult? {
        val payload = """
            {
              "title": "${escapeJson(listing.title)}",
              "amount": ${listing.price},
              "currency": "${escapeJson(listing.currency)}",
              "listing_id": "${escapeJson(listing.id)}",
              "merchant_name": "${escapeJson(listing.merchantName)}",
              "quantity": $quantity,
              "tax_rate": $taxRate,
              "delivery_fee": $deliveryFee,
              "return_url": "${escapeJson(returnUrl)}",
              "cancel_url": "${escapeJson(cancelUrl)}"
            }
        """.trimIndent()

        val responseText = executePost("$baseUrl/api/v1/payments/paypal/create-order", payload)
        if (responseText == null) {
            return null
        }

        val orderId = extractString(responseText, "order_id") ?: return null
        val status = extractString(responseText, "status") ?: "CREATED"
        val approveUrl = extractString(responseText, "approve_url") ?: ""
        val currency = extractString(responseText, "currency") ?: listing.currency
        val amount = extractDouble(responseText, "amount") ?: listing.price
        val isLive = extractBoolean(responseText, "is_live_sandbox") ?: false

        val subtotal = extractNestedDouble(responseText, "order_summary", "subtotal") ?: (listing.price * quantity)
        val tax = extractNestedDouble(responseText, "order_summary", "tax") ?: (subtotal * taxRate)
        val delivery = extractNestedDouble(responseText, "order_summary", "delivery_fee") ?: deliveryFee
        val total = extractNestedDouble(responseText, "order_summary", "total") ?: (subtotal + tax + delivery)

        val summary = OrderSummary(
            listingId = listing.id,
            productTitle = listing.title,
            merchantName = listing.merchantName,
            subtotal = subtotal,
            gst = tax,
            deliveryFee = delivery,
            total = total,
            currency = currency
        )

        return CreateOrderServerResult(
            orderId = orderId,
            status = status,
            approveUrl = approveUrl,
            currency = currency,
            amount = amount,
            isLiveSandbox = isLive,
            orderSummary = summary
        )
    }

    /**
     * Calls POST /api/v1/payments/paypal/capture-order on the backend.
     */
    open fun captureOrder(orderId: String): CaptureOrderServerResult? {
        return captureOrder(orderId, null, null)
    }

    /**
     * Calls POST /api/v1/payments/paypal/capture-order on the backend.
     * Supports client idempotency via PayPal-Request-Id and negative testing simulation via PayPal-Mock-Response.
     */
    open fun captureOrder(
        orderId: String,
        idempotencyKey: String? = null,
        mockResponse: String? = null
    ): CaptureOrderServerResult? {
        val payload = buildString {
            append("""{"order_id": "${escapeJson(orderId)}"""")
            if (!idempotencyKey.isNullOrBlank()) {
                append(""", "idempotency_key": "${escapeJson(idempotencyKey)}"""")
            }
            if (!mockResponse.isNullOrBlank()) {
                append("""", "mock_response": "${escapeJson(mockResponse)}"""")
            }
            append("}")
        }
        val headers = mutableMapOf<String, String>()
        if (!idempotencyKey.isNullOrBlank()) {
            headers["PayPal-Request-Id"] = idempotencyKey
        }
        if (!mockResponse.isNullOrBlank()) {
            headers["PayPal-Mock-Response"] = mockResponse
        }

        val responseText = executePost("$baseUrl/api/v1/payments/paypal/capture-order", payload, headers)

        if (responseText == null) {
            val errorIssue = extractErrorIssue(lastErrorMessage)
            val errorDebugId = lastErrorMessage?.let { msg ->
                val match = Regex("""debug_id:\s*([a-zA-Z0-9_-]+)""").find(msg)
                match?.groupValues?.get(1)
            }

            return CaptureOrderServerResult(
                orderId = orderId,
                captureId = "",
                status = if (errorIssue != null) "DECLINED" else "FAILED",
                amount = 0.0,
                currency = "INR",
                errorMessage = lastErrorMessage ?: "Failed to connect to PayPal backend service",
                issue = errorIssue,
                debugId = errorDebugId
            )
        }

        val capOrderId = extractString(responseText, "order_id") ?: orderId
        val captureId = extractString(responseText, "capture_id") ?: ("CAP-" + orderId.takeLast(8))
        val status = extractString(responseText, "status") ?: "COMPLETED"
        val amount = extractDouble(responseText, "amount") ?: 0.0
        val currency = extractString(responseText, "currency") ?: "INR"
        val payerEmail = extractString(responseText, "payer_email")
        val payerName = extractString(responseText, "payer_name")
        val payerId = extractString(responseText, "payer_id")
        val createTime = extractString(responseText, "create_time")
        val isLive = extractBoolean(responseText, "is_live_sandbox") ?: false
        val errorMsg = extractString(responseText, "error") ?: extractString(responseText, "message")
        val alreadyCaptured = extractBoolean(responseText, "already_captured") ?: false
        val duplicatePrevented = extractBoolean(responseText, "duplicate_capture_prevented") ?: false
        val debugId = extractString(responseText, "debug_id")
        val issue = extractString(responseText, "issue")

        return CaptureOrderServerResult(
            orderId = capOrderId,
            captureId = captureId,
            status = status,
            amount = amount,
            currency = currency,
            payerEmail = payerEmail,
            payerName = payerName,
            payerId = payerId,
            createTime = createTime,
            isLiveSandbox = isLive,
            errorMessage = errorMsg,
            alreadyCaptured = alreadyCaptured,
            duplicateCapturePrevented = duplicatePrevented,
            debugId = debugId,
            issue = issue
        )
    }

    /**
     * Calls GET /api/v1/payments/paypal/order/{order_id}.
     */
    open fun getOrder(orderId: String): String? {
        return executeGet("$baseUrl/api/v1/payments/paypal/order/$orderId")
    }

    private fun executeGet(urlString: String): String? {
        lastErrorMessage = null
        return try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
            }
            readResponse(conn)
        } catch (e: Exception) {
            lastErrorMessage = e.message ?: "Network error"
            null
        }
    }

    private fun executePost(
        urlString: String,
        jsonPayload: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        lastErrorMessage = null
        return try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }
            readResponse(conn)
        } catch (e: Exception) {
            lastErrorMessage = e.message ?: "Network error"
            null
        }
    }

    private fun readResponse(conn: HttpURLConnection): String? {
        return if (conn.responseCode in 200..299) {
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } else {
            val errorBody = try {
                conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }
            } catch (_: Exception) {
                null
            }
            lastErrorMessage = parseErrorBody(errorBody, conn.responseCode, conn.responseMessage)
            null
        }
    }

    private fun parseErrorBody(errorBody: String?, responseCode: Int, responseMessage: String?): String {
        if (errorBody.isNullOrBlank()) {
            return "HTTP $responseCode: $responseMessage"
        }
        val detail = extractString(errorBody, "detail")
            ?: extractString(errorBody, "message")
            ?: extractString(errorBody, "error")
        val issue = extractString(errorBody, "issue") ?: extractErrorIssue(errorBody)
        return when {
            detail != null && issue != null && !detail.contains(issue) -> "PayPal error [$issue]: $detail"
            detail != null -> detail
            issue != null -> "PayPal error [$issue]: $errorBody"
            else -> "HTTP $responseCode: $responseMessage"
        }
    }

    private fun extractErrorIssue(message: String?): String? {
        if (message.isNullOrBlank()) return null
        return when {
            "CURRENCY_NOT_SUPPORTED" in message -> "CURRENCY_NOT_SUPPORTED"
            "INSTRUMENT_DECLINED" in message -> "INSTRUMENT_DECLINED"
            "DUPLICATE_INVOICE_ID" in message -> "DUPLICATE_INVOICE_ID"
            "ORDER_NOT_APPROVED" in message -> "ORDER_NOT_APPROVED"
            "PAYER_ACTION_REQUIRED" in message -> "PAYER_ACTION_REQUIRED"
            else -> Regex("""\[([A-Z0-9_]{3,})\]""").find(message)?.groupValues?.get(1)
        }
    }

    // ========================================================================
    // Lightweight Zero-Dependency JSON Extraction Helpers
    // ========================================================================
    private fun escapeJson(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun extractDouble(json: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractBoolean(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractNestedDouble(json: String, objectKey: String, key: String): Double? {
        val objIndex = json.indexOf("\"$objectKey\"")
        if (objIndex == -1) return null
        val sub = json.substring(objIndex)
        return extractDouble(sub, key)
    }
}
