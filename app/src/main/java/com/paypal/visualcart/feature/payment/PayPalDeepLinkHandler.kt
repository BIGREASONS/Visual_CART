package com.paypal.visualcart.feature.payment

import android.content.Intent
import android.net.Uri
import com.paypal.visualcart.core.util.SecurityUtils
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Handles incoming custom deep-link intents for PayPal Sandbox Checkout:
 * - visualcart://paypal-return?token={orderId}&PayerID={payerId}
 * - visualcart://paypal-cancel?token={orderId}
 *
 * Provides safe parameter extraction, XSS/injection sanitization, and structured results.
 * Supports both raw URI strings and Android Intent/Uri objects for robust testing and execution.
 */
object PayPalDeepLinkHandler {

    const val SCHEME = "visualcart"
    const val HOST_RETURN = "paypal-return"
    const val HOST_CANCEL = "paypal-cancel"

    const val RETURN_URL_PREFIX = "$SCHEME://$HOST_RETURN"
    const val CANCEL_URL_PREFIX = "$SCHEME://$HOST_CANCEL"

    sealed class Result {
        data class Approved(
            val orderId: String,
            val payerId: String,
            val rawUri: String
        ) : Result()

        data class Cancelled(
            val orderId: String?,
            val rawUri: String
        ) : Result()

        data class Invalid(
            val reason: String,
            val rawUri: String?
        ) : Result()
    }

    /**
     * Checks if the intent is a PayPal deep link intent (ACTION_VIEW with visualcart scheme).
     */
    fun isPayPalDeepLink(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return false
        val uriStr = intent.dataString ?: intent.data?.toString() ?: return false
        return isPayPalDeepLink(uriStr)
    }

    /**
     * Checks if the given URI string matches the visualcart:// deep link scheme and PayPal return/cancel hosts.
     * Also supports backwards-compatible paypalvisualcart:// scheme.
     */
    fun isPayPalDeepLink(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val (scheme, host) = extractSchemeAndHost(uriString) ?: return false
        return (scheme == SCHEME && (host == HOST_RETURN || host == HOST_CANCEL)) ||
            (scheme == "paypalvisualcart" && (host == "checkout" || host == HOST_RETURN || host == HOST_CANCEL))
    }

    /**
     * Checks if the given Android Uri matches the PayPal deep link format.
     */
    fun isPayPalDeepLink(uri: Uri?): Boolean {
        if (uri == null) return false
        return isPayPalDeepLink(uri.toString())
    }

    /**
     * Parses an incoming intent into a structured PayPalDeepLinkResult.
     */
    fun parse(intent: Intent?): Result? {
        if (!isPayPalDeepLink(intent)) return null
        val uriStr = intent?.dataString ?: intent?.data?.toString() ?: return null
        return parse(uriStr)
    }

    /**
     * Parses an incoming Android Uri into a structured PayPalDeepLinkResult.
     */
    fun parse(uri: Uri?): Result {
        if (uri == null) return Result.Invalid("URI is null", null)
        return parse(uri.toString())
    }

    /**
     * Parses an incoming URI string into a structured PayPalDeepLinkResult.
     * Sanitizes all extracted parameters against script injection, control characters, and length limits.
     */
    fun parse(uriString: String): Result {
        val (scheme, host) = extractSchemeAndHost(uriString)
            ?: return Result.Invalid("Malformed deep link URI", uriString)

        val isVisualCart = scheme == SCHEME
        val isPayPalVisualCart = scheme == "paypalvisualcart"

        if (!isVisualCart && !isPayPalVisualCart) {
            return Result.Invalid("Invalid scheme: $scheme (expected $SCHEME)", uriString)
        }

        val effectiveHost = if (isPayPalVisualCart && host == "checkout") {
            if (uriString.contains("/cancel", ignoreCase = true)) HOST_CANCEL else HOST_RETURN
        } else {
            host
        }

        val queryParams = extractQueryParams(uriString)

        return when (effectiveHost) {
            HOST_RETURN -> {
                // Support PayPal standard parameters (token, PayerID) as well as common variations
                val rawOrderId = queryParams["token"]
                    ?: queryParams["order_id"]
                    ?: queryParams["orderId"]
                    ?: queryParams["orderID"]
                    ?: ""

                val rawPayerId = queryParams["PayerID"]
                    ?: queryParams["payer_id"]
                    ?: queryParams["payerId"]
                    ?: queryParams["payerID"]
                    ?: ""

                val sanitizedOrderId = SecurityUtils.sanitizeUserInput(rawOrderId, maxLength = 100)
                val sanitizedPayerId = SecurityUtils.sanitizeUserInput(rawPayerId, maxLength = 100)

                if (sanitizedOrderId.isBlank() && sanitizedPayerId.isBlank()) {
                    Result.Invalid("PayPal return URL missing order ID and payer ID parameters", uriString)
                } else {
                    Result.Approved(
                        orderId = sanitizedOrderId,
                        payerId = sanitizedPayerId,
                        rawUri = uriString
                    )
                }
            }

            HOST_CANCEL -> {
                val rawOrderId = queryParams["token"]
                    ?: queryParams["order_id"]
                    ?: queryParams["orderId"]
                    ?: queryParams["orderID"]

                val sanitizedOrderId = rawOrderId?.let { SecurityUtils.sanitizeUserInput(it, maxLength = 100) }
                Result.Cancelled(
                    orderId = sanitizedOrderId,
                    rawUri = uriString
                )
            }

            else -> Result.Invalid("Unsupported deep link host: $host", uriString)
        }
    }

    /**
     * Constructs a standard return URL for PayPal Sandbox orders.
     */
    fun buildReturnUrl(orderId: String, payerId: String): String {
        val cleanOrder = URLEncoder.encode(orderId.trim(), StandardCharsets.UTF_8.name())
        val cleanPayer = URLEncoder.encode(payerId.trim(), StandardCharsets.UTF_8.name())
        return "$RETURN_URL_PREFIX?token=$cleanOrder&PayerID=$cleanPayer"
    }

    /**
     * Constructs a standard cancel URL for PayPal Sandbox orders.
     */
    fun buildCancelUrl(orderId: String): String {
        val cleanOrder = URLEncoder.encode(orderId.trim(), StandardCharsets.UTF_8.name())
        return "$CANCEL_URL_PREFIX?token=$cleanOrder"
    }

    private fun extractSchemeAndHost(uriString: String): Pair<String, String>? {
        val trimmed = uriString.trim()
        val schemeDelimiter = "://"
        val schemeEnd = trimmed.indexOf(schemeDelimiter)
        if (schemeEnd == -1) return null

        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val afterScheme = trimmed.substring(schemeEnd + schemeDelimiter.length)
        if (afterScheme.isEmpty()) return null

        val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = if (hostEnd == -1) afterScheme else afterScheme.substring(0, hostEnd)
        if (host.isEmpty()) return null

        return Pair(scheme, host.lowercase())
    }

    private fun extractQueryParams(uriString: String): Map<String, String> {
        val queryIndex = uriString.indexOf('?')
        if (queryIndex == -1 || queryIndex >= uriString.length - 1) return emptyMap()

        val querySubstring = uriString.substring(queryIndex + 1).takeWhile { it != '#' }
        val params = mutableMapOf<String, String>()

        for (pair in querySubstring.split('&')) {
            if (pair.isBlank()) continue
            val parts = pair.split('=', limit = 2)
            val key = try {
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                parts[0]
            }
            val value = if (parts.size > 1) {
                try {
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    parts[1]
                }
            } else ""
            params[key] = value
        }

        return params
    }
}
