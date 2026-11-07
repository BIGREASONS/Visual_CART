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
    val orderSummary: OrderSummary? = null
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
    val isLiveSandbox: Boolean = false
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
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 4000
) {

    val baseUrl: String by lazy {
        configuredBaseUrl?.takeIf { it.isNotBlank() }
            ?: resolveBaseUrl()
    }

    companion object {
        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:8000"
        const val DEFAULT_LOCALHOST_URL = "http://localhost:8000"
        const val ENV_BACKEND_URL = "PAYPAL_BACKEND_URL"
        const val PROP_BACKEND_URL = "paypal.backend.url"
        const val DEFAULT_RETURN_URL = "paypalvisualcart://checkout/success"
        const val DEFAULT_CANCEL_URL = "paypalvisualcart://checkout/cancel"

        fun resolveBaseUrl(): String {
            return System.getenv(ENV_BACKEND_URL)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROP_BACKEND_URL)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_EMULATOR_URL
        }
    }

    /**
     * Checks if the backend server is reachable and responding.
     */
    open fun isBackendAvailable(): Boolean {
        return try {
            val url = URL("$baseUrl/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            // Also test localhost fallback if default was 10.0.2.2 and on JVM
            if (baseUrl == DEFAULT_EMULATOR_URL) {
                try {
                    val localConn = (URL("$DEFAULT_LOCALHOST_URL/health").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 1000
                        readTimeout = 1000
                    }
                    localConn.responseCode in 200..299
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
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
        cancelUrl: String = DEFAULT_CANCEL_URL
    ): CreateOrderServerResult? {
        val payload = """
            {
              "title": "${escapeJson(listing.title)}",
              "amount": ${listing.price},
              "currency": "${escapeJson(listing.currency)}",
              "listing_id": "${escapeJson(listing.id)}",
              "merchant_name": "${escapeJson(listing.merchantName)}",
              "quantity": 1,
              "tax_rate": 0.18,
              "delivery_fee": 0.0,
              "return_url": "${escapeJson(returnUrl)}",
              "cancel_url": "${escapeJson(cancelUrl)}"
            }
        """.trimIndent()

        val responseText = executePost("$baseUrl/api/v1/payments/paypal/create-order", payload) ?: return null

        val orderId = extractString(responseText, "order_id") ?: return null
        val status = extractString(responseText, "status") ?: "CREATED"
        val approveUrl = extractString(responseText, "approve_url") ?: ""
        val currency = extractString(responseText, "currency") ?: listing.currency
        val amount = extractDouble(responseText, "amount") ?: listing.price
        val isLive = extractBoolean(responseText, "is_live_sandbox") ?: false

        val subtotal = extractNestedDouble(responseText, "order_summary", "subtotal") ?: listing.price
        val tax = extractNestedDouble(responseText, "order_summary", "tax") ?: (subtotal * 0.18)
        val delivery = extractNestedDouble(responseText, "order_summary", "delivery_fee") ?: 0.0
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
        val payload = """{"order_id": "${escapeJson(orderId)}"}"""
        val responseText = executePost("$baseUrl/api/v1/payments/paypal/capture-order", payload) ?: return null

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
            isLiveSandbox = isLive
        )
    }

    /**
     * Calls GET /api/v1/payments/paypal/order/{order_id}.
     */
    open fun getOrder(orderId: String): String? {
        return executeGet("$baseUrl/api/v1/payments/paypal/order/$orderId")
    }

    private fun executeGet(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executePost(urlString: String, jsonPayload: String): String? {
        return try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
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
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
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
