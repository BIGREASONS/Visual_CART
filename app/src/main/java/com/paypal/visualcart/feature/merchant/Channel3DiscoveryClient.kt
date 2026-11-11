package com.paypal.visualcart.feature.merchant

import android.content.Context
import com.paypal.visualcart.core.config.VisualCartSettingsStore
import com.paypal.visualcart.core.util.SimpleJson
import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Channel3DiscoveryResponse(
    val ok: Boolean,
    val query: String? = null,
    val title: String? = null,
    val channel3Configured: Boolean = false,
    val channel3Used: Boolean = false,
    val listings: List<MerchantListing> = emptyList(),
    val lowestPrice: Double? = null,
    val currency: String? = null,
    val offerSource: String? = null,
    val error: String? = null
)

/**
 * Calls the Visual Cart FastAPI backend's Channel3 discovery endpoints
 * (`/api/v1/products/search`, `/api/v1/products/lookup`, `/api/v1/products/{id}`).
 * Never embeds or touches API keys on the Android client.
 */
class Channel3DiscoveryClient(
    private val context: Context? = null,
    private val candidateUrlsSupplier: () -> List<String> = {
        if (context != null) {
            VisualCartSettingsStore.resolveCandidateBackendUrls(context)
        } else {
            listOf(
                VisualCartSettingsStore.DEFAULT_USB_BACKEND_URL,
                VisualCartSettingsStore.DEFAULT_EMULATOR_BACKEND_URL
            )
        }
    }
) {

    fun searchProducts(
        query: String,
        country: String? = null,
        currency: String? = null,
        limit: Int = 8
    ): Channel3DiscoveryResponse {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return Channel3DiscoveryResponse(ok = false, error = "Query cannot be blank")
        }
        val locCountry = country ?: context?.let { VisualCartSettingsStore.getDiscoveryCountry(it) } ?: "US"
        val locCurrency = currency ?: context?.let { VisualCartSettingsStore.getDiscoveryCurrency(it) } ?: "USD"

        val body = SimpleJson.encode(
            mapOf(
                "query" to cleanQuery,
                "country" to locCountry,
                "currency" to locCurrency,
                "limit" to limit,
                "include_search_links" to false
            )
        )
        val raw = postFirstReachable("/api/v1/products/search", body)
            ?: return Channel3DiscoveryResponse(ok = false, query = cleanQuery, error = "Backend unreachable")
        return parseDiscoveryResponse(raw)
    }

    fun lookupUrl(
        productUrl: String,
        country: String? = null,
        currency: String? = null
    ): Channel3DiscoveryResponse {
        val cleanUrl = productUrl.trim()
        if (cleanUrl.isBlank()) {
            return Channel3DiscoveryResponse(ok = false, error = "URL cannot be blank")
        }
        val locCountry = country ?: context?.let { VisualCartSettingsStore.getDiscoveryCountry(it) } ?: "US"
        val locCurrency = currency ?: context?.let { VisualCartSettingsStore.getDiscoveryCurrency(it) } ?: "USD"

        val body = SimpleJson.encode(
            mapOf(
                "url" to cleanUrl,
                "country" to locCountry,
                "currency" to locCurrency,
                "include_search_links" to false
            )
        )
        val raw = postFirstReachable("/api/v1/products/lookup", body)
            ?: return Channel3DiscoveryResponse(ok = false, error = "Backend unreachable")
        return parseDiscoveryResponse(raw)
    }

    /**
     * Calls free Channel3 `GET /api/v1/products/{productId}` through our backend
     * to hydrate fresh offer prices and affiliate URLs before cart checkout.
     */
    fun refreshProduct(
        productId: String,
        country: String? = null,
        currency: String? = null
    ): Channel3DiscoveryResponse {
        val cleanId = productId.trim()
        if (cleanId.isBlank()) {
            return Channel3DiscoveryResponse(ok = false, error = "Product ID cannot be blank")
        }
        val locCountry = country ?: context?.let { VisualCartSettingsStore.getDiscoveryCountry(it) } ?: "US"
        val locCurrency = currency ?: context?.let { VisualCartSettingsStore.getDiscoveryCurrency(it) } ?: "USD"
        val encodedId = URLEncoder.encode(cleanId, "UTF-8")
        val path = "/api/v1/products/$encodedId?country=${URLEncoder.encode(locCountry, "UTF-8")}&currency=${URLEncoder.encode(locCurrency, "UTF-8")}"
        val raw = getFirstReachable(path)
            ?: return Channel3DiscoveryResponse(ok = false, error = "Backend unreachable")
        return parseDiscoveryResponse(raw)
    }

    private fun postFirstReachable(path: String, jsonBody: String): String? {
        for (base in candidateUrlsSupplier()) {
            val urlStr = "${base.trimEnd('/')}$path"
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 16000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                conn.disconnect()
                if (code in 200..299 && !text.isNullOrBlank()) {
                    return text
                }
            } catch (_: Exception) {
                // Cascade to next candidate URL (e.g. localhost -> 10.0.2.2)
            }
        }
        return null
    }

    private fun getFirstReachable(path: String): String? {
        for (base in candidateUrlsSupplier()) {
            val urlStr = "${base.trimEnd('/')}$path"
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                conn.disconnect()
                if (code in 200..299 && !text.isNullOrBlank()) {
                    return text
                }
            } catch (_: Exception) {
                // Cascade to next candidate
            }
        }
        return null
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parseDiscoveryResponse(rawJson: String): Channel3DiscoveryResponse {
            val root = try {
                SimpleJson.parseObject(rawJson)
            } catch (_: Exception) {
                null
            } ?: return Channel3DiscoveryResponse(ok = false, error = "Invalid JSON response")

            val rawOffers = (root["offers"] as? List<*>) ?: (root["listings"] as? List<*>) ?: emptyList<Any?>()
            val parsedOffers = rawOffers.mapNotNull { item ->
                (item as? Map<String, Any?>)?.let { mapOfferToMerchantListing(it) }
            }

            val topProducts = (root["products"] as? List<*>)
            val firstProductTitle = (topProducts?.firstOrNull() as? Map<*, *>)?.get("title") as? String
            val resolvedTitle = (root["title"] as? String)?.takeIf { it.isNotBlank() }
                ?: firstProductTitle?.takeIf { it.isNotBlank() }
                ?: (root["query"] as? String)?.takeIf { it.isNotBlank() }

            val okFlag = (root["ok"] as? Boolean) ?: (parsedOffers.isNotEmpty() || (root["resolved"] as? Boolean) == true)

            return Channel3DiscoveryResponse(
                ok = okFlag,
                query = root["query"] as? String,
                title = resolvedTitle,
                channel3Configured = (root["channel3_configured"] as? Boolean) ?: false,
                channel3Used = (root["channel3_used"] as? Boolean) ?: parsedOffers.any { it.offerSource == "CHANNEL3_API" },
                listings = parsedOffers,
                lowestPrice = (root["lowest_price"] as? Number)?.toDouble(),
                currency = root["currency"] as? String,
                offerSource = root["offer_source"] as? String,
                error = root["error"] as? String
            )
        }

        private fun mapOfferToMerchantListing(map: Map<String, Any?>): MerchantListing? {
            val originStr = (map["listing_origin"] as? String)?.trim()?.uppercase()
            if (originStr == "DIRECT_SEARCH_URL" || originStr == "NO_RESULT") return null

            val price = (map["price"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
                ?: return null
            val merchantName = (map["merchant"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val domain = (map["merchant_domain"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["merchant_id"] as? String)?.takeIf { it.isNotBlank() }
                ?: merchantName.lowercase().replace(Regex("[^a-z0-9.]"), "")
            val title = (map["title"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["product_title"] as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            val buyUrl = (map["url"] as? String)?.takeIf { it.startsWith("http") } ?: return null

            val rawCompareAt = (map["originalPrice"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > price }
            val currency = (map["currency"] as? String)?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: "USD"
            val matchType = when ((map["match_type"] as? String)?.uppercase()) {
                "EXACT" -> MatchType.EXACT
                "SIMILAR" -> MatchType.SIMILAR
                else -> MatchType.LIKELY
            }
            val availability = when ((map["availability"] as? String ?: map["status"] as? String)?.uppercase()) {
                "OUT_OF_STOCK" -> AvailabilityStatus.OUT_OF_STOCK
                "LIMITED" -> AvailabilityStatus.LIMITED
                else -> AvailabilityStatus.IN_STOCK
            }
            val confidence = ((map["confidence"] as? Number)?.toFloat() ?: 0.85f).coerceIn(0f, 1f)
            val obsAt = (map["observed_at"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val offerSource = (map["offer_source"] as? String)?.takeIf { it.isNotBlank() } ?: "CHANNEL3_API"
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: "c3_${domain}_${title.hashCode()}"

            return try {
                MerchantListing(
                    id = id,
                    merchantName = merchantName,
                    merchantDomain = domain,
                    title = title,
                    price = price,
                    originalPrice = rawCompareAt,
                    currency = currency,
                    availability = availability,
                    matchType = matchType,
                    confidence = confidence,
                    productUrl = buyUrl,
                    imageUrl = (map["image_url"] as? String)?.trim().orEmpty(),
                    sourceType = SourceType.LIVE,
                    lastVerifiedAt = obsAt,
                    deliveryEstimate = (map["delivery"] as? String)?.takeIf { it.isNotBlank() },
                    sellerName = (map["seller_name"] as? String)?.takeIf { it.isNotBlank() } ?: merchantName,
                    listingOrigin = ListingOrigin.LIVE_API,
                    isReferencePriceVerified = rawCompareAt != null,
                    providerProductId = (map["provider_product_id"] as? String)?.takeIf { it.isNotBlank() },
                    offerSource = offerSource,
                    commissionRate = (map["max_commission_rate"] as? Number)?.toDouble()
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
