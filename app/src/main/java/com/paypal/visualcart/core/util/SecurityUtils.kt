package com.paypal.visualcart.core.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Security and privacy utilities for PayPal Visual Cart.
 *
 * Provides:
 * - Strict URL validation & sanitization (rejects javascript:, file:, content:, data:, etc.)
 * - Tracking parameter and script stripping (UTM, Facebook/Google ad click IDs, referral tags)
 * - Phishing and lookalike domain protection (brand impersonation, suspicious TLDs, homograph attacks)
 * - Content URI validation for incoming shares (path traversal & malicious authority rejection)
 * - User input sanitization (XSS, control characters, null byte stripping)
 * - Safe browser intent URL verification
 */
object SecurityUtils {

    private val ALLOWED_WEB_SCHEMES = setOf("http", "https")

    private val DANGEROUS_SCHEMES = listOf(
        "javascript", "file", "content", "data", "intent", "vbscript", "blob", "about"
    )

    private val HTML_TAG_PATTERN = Pattern.compile("<[^>]*>", Pattern.CASE_INSENSITIVE)
    private val CONTROL_CHARS_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]")
    private val PATH_TRAVERSAL_PATTERN = Pattern.compile("(\\.\\./|\\.\\.\\\\|%2e%2e|%2e%2e%2f)", Pattern.CASE_INSENSITIVE)

    /**
     * Standard tracking and marketing attribution parameters to strip from clean URLs.
     */
    val TRACKING_PARAM_KEYS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
        "ref", "ref_", "tag", "ascsubtag", "sr_share",
        "gclid", "gclsrc", "dclid", "wbraid", "gbraid",
        "fbclid",
        "_ga", "_gl", "_hsenc", "_hsmi",
        "mc_cid", "mc_eid",
        "msclkid", "zanpid", "igshid", "ttclid", "twclid", "yclid"
    )

    /**
     * High-risk TLDs commonly abused in automated phishing campaigns.
     */
    private val SUSPICIOUS_TLDS = setOf(
        "xyz", "top", "tk", "ml", "ga", "cf", "gq", "buzz", "work", "click", "loan", "country"
    )

    /**
     * Trusted legitimate domains for key e-commerce and payment platforms.
     * Hostnames containing these brand names MUST end with one of their genuine root domains.
     */
    private val BRAND_DOMAIN_ALLOWLIST = mapOf(
        "paypal" to listOf("paypal.com", "paypal.me"),
        "amazon" to listOf("amazon.in", "amazon.com", "amazon.co.uk", "amazon.de", "amazon.co.jp"),
        "flipkart" to listOf("flipkart.com"),
        "croma" to listOf("croma.com"),
        "reliancedigital" to listOf("reliancedigital.in"),
        "tatacliq" to listOf("tatacliq.com"),
        "myntra" to listOf("myntra.com")
    )

    /**
     * Checks if the given URL string has a dangerous or forbidden scheme.
     */
    fun hasUnsafeScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase(Locale.ROOT)
        for (scheme in DANGEROUS_SCHEMES) {
            if (trimmed.startsWith("$scheme:") || trimmed.startsWith("$scheme%3a")) {
                return true
            }
        }
        return false
    }

    /**
     * Validates whether a URL is a legitimate, safe HTTP or HTTPS web URL.
     * Rejects javascript:, file:, content:, data:, malformed URIs, credentials, phishing/lookalikes, and script injections.
     */
    fun isValidWebUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()

        if (hasUnsafeScheme(trimmed)) return false

        // Check for embedded null bytes or CRLF
        if (trimmed.contains('\u0000') || trimmed.contains('\r') || trimmed.contains('\n')) {
            return false
        }

        // Check for injected scripts or html in URL
        if (trimmed.contains("<script", ignoreCase = true) || trimmed.contains("%3cscript", ignoreCase = true)) {
            return false
        }

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host

            if (scheme !in ALLOWED_WEB_SCHEMES) {
                return false
            }

            if (host.isNullOrBlank()) {
                return false
            }

            // Reject URLs with embedded credentials (e.g., https://attacker:secret@legit.com)
            if (uri.userInfo != null) {
                return false
            }

            // Host must contain valid characters and at least one dot or be localhost
            if (!isValidHost(host)) {
                return false
            }

            // Phishing domain check
            if (isPhishingHost(host)) {
                return false
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether a URL is safe to pass into a browser Intent (e.g. Intent.ACTION_VIEW).
     * Strictly requires http/https, valid host, and no malicious schemes or phishing indicators.
     */
    fun isSafeForBrowserIntent(url: String?): Boolean {
        return isValidWebUrl(url)
    }

    /**
     * Detects potential phishing URLs using brand impersonation, homograph attacks,
     * suspicious TLDs, and IP address host checks.
     */
    fun isPhishingUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url.trim())
            val host = uri.host ?: return false
            isPhishingHost(host)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Inspects a hostname for phishing characteristics:
     * - Punycode (IDN homograph attack e.g. xn--)
     * - Brand impersonation / typosquatting (e.g. paypal.com.attacker.com, amazon-deals.xyz)
     * - Private or direct IP address as host
     * - Suspicious TLD paired with e-commerce / payment brand names
     */
    fun isPhishingHost(host: String): Boolean {
        val lowerHost = host.lowercase(Locale.ROOT).removePrefix("www.")

        // 1. Localhost is permitted for local development/testing
        if (lowerHost == "localhost") return false

        // 2. Homograph / Punycode attack detection
        if (lowerHost.contains("xn--") || lowerHost.any { it.code > 127 }) {
            return true
        }

        // 3. Reject direct IP addresses (prevent private network SSRF & port scanning)
        if (isIpAddress(lowerHost)) {
            return true
        }

        // 4. Brand impersonation detection: If the host mentions a known brand,
        // it must end with one of that brand's legitimate root domains.
        for ((brand, allowedDomains) in BRAND_DOMAIN_ALLOWLIST) {
            if (lowerHost.contains(brand)) {
                val matchesAllowed = allowedDomains.any { allowed ->
                    lowerHost == allowed || lowerHost.endsWith(".$allowed")
                }
                if (!matchesAllowed) {
                    return true
                }
            }
        }

        // 5. Check for suspicious TLDs on subdomains imitating payment or login keywords
        val parts = lowerHost.split(".")
        if (parts.size >= 2) {
            val tld = parts.last()
            if (tld in SUSPICIOUS_TLDS) {
                val namePart = parts.dropLast(1).joinToString(".")
                if (namePart.contains("pay") || namePart.contains("shop") || namePart.contains("bank") ||
                    namePart.contains("login") || namePart.contains("secure") || namePart.contains("account")
                ) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Checks whether a domain claiming to be [expectedMerchant] actually belongs to its legitimate root domain.
     */
    fun isSuspiciousMerchantDomain(domain: String, expectedMerchant: String? = null): Boolean {
        if (isPhishingHost(domain)) return true
        if (expectedMerchant.isNullOrBlank()) return false

        val cleanDomain = domain.lowercase(Locale.ROOT).removePrefix("www.")
        val cleanExpected = expectedMerchant.lowercase(Locale.ROOT)

        for ((brand, allowedDomains) in BRAND_DOMAIN_ALLOWLIST) {
            if (cleanExpected.contains(brand)) {
                val matches = allowedDomains.any { cleanDomain == it || cleanDomain.endsWith(".$it") }
                if (!matches) return true
            }
        }
        return false
    }

    /**
     * Strips tracking parameters (e.g. utm_*, fbclid, gclid, ref, etc.) and tracking scripts
     * from the query string while preserving legitimate functional e-commerce query parameters.
     */
    fun stripTrackingParameters(url: String): String {
        return try {
            val trimmed = url.trim()
            val fragmentIdx = trimmed.indexOf('#')
            val withoutFragment = if (fragmentIdx != -1) trimmed.substring(0, fragmentIdx) else trimmed
            val fragment = if (fragmentIdx != -1) trimmed.substring(fragmentIdx) else ""

            val questionIdx = withoutFragment.indexOf('?')
            if (questionIdx == -1) return trimmed

            val base = withoutFragment.substring(0, questionIdx)
            val query = withoutFragment.substring(questionIdx + 1)
            if (query.isBlank()) return "$base$fragment"

            val cleanParams = query.split("&")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { param ->
                    val key = param.substringBefore("=").lowercase(Locale.ROOT)
                    key !in TRACKING_PARAM_KEYS && !key.startsWith("utm_")
                }
                .map { param ->
                    val key = param.substringBefore("=")
                    val value = if (param.contains("=")) param.substringAfter("=") else ""
                    val cleanVal = sanitizeUserInput(value)
                    if (cleanVal.isNotEmpty()) "$key=$cleanVal" else key
                }

            val cleanQuery = if (cleanParams.isEmpty()) "" else "?${cleanParams.joinToString("&")}"
            "$base$cleanQuery$fragment"
        } catch (_: Exception) {
            url.substringBefore("?")
        }
    }

    /**
     * Validates and sanitizes a web URL:
     * - Rejects unsafe schemes, phishing domains, malformed URIs
     * - Strips tracking scripts & tracking query parameters
     * - Returns normalized ASCII URL string, or null if unsafe.
     */
    fun sanitizeWebUrl(url: String?): String? {
        if (!isValidWebUrl(url)) return null
        return try {
            val stripped = stripTrackingParameters(url!!.trim())
            val uri = URI(stripped)
            uri.toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates incoming Android Content URIs (e.g. from ACTION_SEND or ACTION_SEND_MULTIPLE).
     * Must use the 'content' scheme, have a non-empty authority, and contain no path traversal sequences.
     */
    fun isValidContentUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val trimmed = uriString.trim()

        // Null bytes or control chars check
        if (trimmed.contains('\u0000') || CONTROL_CHARS_PATTERN.matcher(trimmed).find()) {
            return false
        }

        // Must start with content://
        if (!trimmed.startsWith("content://", ignoreCase = true)) {
            return false
        }

        // Path traversal rejection
        if (PATH_TRAVERSAL_PATTERN.matcher(trimmed).find()) {
            return false
        }

        return try {
            val uri = URI(trimmed)
            if (uri.scheme?.lowercase(Locale.ROOT) != "content") {
                return false
            }

            val authority = uri.authority
            if (authority.isNullOrBlank()) {
                return false
            }

            // Authority must contain valid package or provider name chars
            val validAuthPattern = Pattern.compile("^[a-zA-Z0-9_.-]+$")
            if (!validAuthPattern.matcher(authority).matches()) {
                return false
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sanitizes user input or search queries:
     * - Strips HTML/XML tags
     * - Strips null bytes and control characters
     * - Trims whitespace
     * - Truncates to [maxLength] (defaults to 500 characters)
     */
    fun sanitizeUserInput(input: String?, maxLength: Int = 500): String {
        if (input.isNullOrBlank()) return ""

        // 1. Remove null bytes and non-printable control characters
        var sanitized = input.replace("\u0000", " ")
        sanitized = CONTROL_CHARS_PATTERN.matcher(sanitized).replaceAll(" ")

        // 2. Remove HTML/Script tags
        sanitized = HTML_TAG_PATTERN.matcher(sanitized).replaceAll("")

        // 3. Normalize whitespace
        sanitized = sanitized.replace(Regex("\\s+"), " ").trim()

        // 4. Enforce max length
        return sanitized.take(maxLength)
    }

    /**
     * Sanitizes search queries specifically, removing special characters that may break query building.
     */
    fun sanitizeSearchQuery(query: String?, maxLength: Int = 200): String {
        val base = sanitizeUserInput(query, maxLength)
        // Strip characters that may disrupt URL generation or query parsers
        return base.replace(Regex("[<>\"'`;\u0000]"), "").trim()
    }

    /**
     * Extracts the first valid HTTP/HTTPS URL from arbitrary text (e.g. shared from browsers or social apps).
     */
    fun extractWebUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val urlRegex = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        val match = urlRegex.find(text) ?: return null
        val candidate = match.value.trim().trimEnd('.', ',', ';', '!', ')')
        return if (isValidWebUrl(candidate)) candidate else null
    }

    private fun isValidHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        // Allow valid domain names
        val hostPattern = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        return hostPattern.matcher(host).matches()
    }

    private fun isIpAddress(host: String): Boolean {
        val ipv4Pattern = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        return ipv4Pattern.matcher(host).matches() || host.startsWith("[") || host.contains(":")
    }
}
