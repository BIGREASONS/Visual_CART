package com.paypal.visualcart.feature.merchant

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Utility functions for URL encoding, normalization, domain extraction, and slug parsing.
 */
object MerchantUrlHelper {

    fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name())
                .replace("+", "%20")
        } catch (_: Exception) {
            value.replace(" ", "%20")
        }
    }

    fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    fun extractDomain(url: String): String {
        return try {
            val uri = URI(url.trim())
            val host = uri.host ?: ""
            host.removePrefix("www.").lowercase()
        } catch (_: Exception) {
            val trimmed = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            trimmed.substringBefore("/").substringBefore("?").lowercase()
        }
    }

    /**
     * Converts a URL slug like "samsung-990-evo-1-tb-desktop-internal-solid-state-drive"
     * into a human-readable title like "Samsung 990 Evo 1 Tb Desktop Internal Solid State Drive".
     */
    fun slugToTitle(slug: String): String {
        val cleanSlug = urlDecode(slug)
            .replace(Regex("[-_+]+"), " ")
            .trim()

        if (cleanSlug.isEmpty()) return ""

        return cleanSlug.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                if (word.all { it.isDigit() }) {
                    word
                } else {
                    word.lowercase().replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
                }
            }
    }
}
