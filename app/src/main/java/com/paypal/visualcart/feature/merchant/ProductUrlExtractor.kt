package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.ParsedProductMetadata
import java.net.URI

/**
 * Universal e-commerce URL product metadata extractor.
 * Dispatches to specialized merchant adapters or applies generic heuristic extraction
 * (extracting domains, slugs, clean titles, product identifiers, and stripping tracking parameters).
 */
class ProductUrlExtractor(
    private val providers: List<MerchantSearchProvider> = emptyList()
) {

    private val trackingParamKeys = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "ref", "ref_", "tag", "ascsubtag", "gclid", "fbclid", "_ga", "sr_share"
    )

    fun extract(url: String): ParsedProductMetadata {
        val cleanInputUrl = url.trim()

        // 1. Try registered merchant adapters
        for (provider in providers) {
            val parsed = provider.parseProductUrl(cleanInputUrl)
            if (parsed != null) {
                return parsed
            }
        }

        // 2. Generic heuristic fallback for any arbitrary e-commerce URL
        return extractGeneric(cleanInputUrl)
    }

    private fun extractGeneric(url: String): ParsedProductMetadata {
        val domain = MerchantUrlHelper.extractDomain(url)

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            null
        }

        val pathSegments = uri?.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()

        // Look for slug: usually the longest path segment with letters and hyphens,
        // explicitly excluding image filenames or capture artifacts.
        val slugCandidate = pathSegments
            .filter { segment ->
                (segment.contains("-") || segment.length > 5) &&
                    !SearchQueryBuilder.isNonProductNoise(segment)
            }
            .maxByOrNull { it.length }

        val title = slugCandidate
            ?.let { SearchQueryBuilder.sanitizeComponent(MerchantUrlHelper.slugToTitle(it)) }
            ?.takeIf { it.isNotBlank() && !SearchQueryBuilder.isNonProductNoise(it) }

        // Look for ID segment: numeric or alphanumeric code
        val idCandidate = pathSegments.findLast { segment ->
            (segment.length in 6..20 && segment.all { it.isLetterOrDigit() }) ||
                segment.all { it.isDigit() }
        }

        val canonicalUrl = buildCleanCanonicalUrl(uri, url)

        return ParsedProductMetadata(
            url = url,
            domain = domain.ifBlank { "unknown" },
            title = title,
            productId = idCandidate,
            canonicalUrl = canonicalUrl,
            category = "General E-commerce"
        )
    }

    private fun buildCleanCanonicalUrl(uri: URI?, rawUrl: String): String {
        if (uri == null || uri.host == null) {
            return rawUrl.substringBefore("?")
        }

        val scheme = uri.scheme ?: "https"
        val host = uri.host
        val path = uri.path ?: ""
        val query = uri.query

        if (query.isNullOrBlank()) {
            return "$scheme://$host$path"
        }

        val cleanQueryParams = query.split("&")
            .filter { param ->
                val key = param.substringBefore("=").lowercase()
                key !in trackingParamKeys
            }

        return if (cleanQueryParams.isEmpty()) {
            "$scheme://$host$path"
        } else {
            "$scheme://$host$path?${cleanQueryParams.joinToString("&")}"
        }
    }
}
