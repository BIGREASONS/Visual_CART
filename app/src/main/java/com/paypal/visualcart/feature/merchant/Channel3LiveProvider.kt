package com.paypal.visualcart.feature.merchant

import android.content.Context
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import java.net.URLEncoder

/**
 * Live [MerchantSearchProvider] backed by the FastAPI Channel3 + JSON-LD discovery pipeline.
 * Runs in parallel with regional store adapters and emits priced [ListingOrigin.LIVE_API] offers
 * whenever live pricing is returned by the backend.
 */
class Channel3LiveProvider(
    context: Context? = null,
    private val discoveryClient: Channel3DiscoveryClient = Channel3DiscoveryClient(context)
) : MerchantSearchProvider {

    override val merchantId: String = "channel3_live"
    override val merchantName: String = "Channel3 Live Catalog"
    override val domain: String = "trychannel3.com"
    override var isLiveMode: Boolean = true

    override suspend fun search(
        query: String,
        targetSpecs: Map<String, String>
    ): List<MerchantListing> {
        if (!isLiveMode) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val resp = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            discoveryClient.lookupUrl(trimmed)
        } else {
            discoveryClient.searchProducts(trimmed)
        }
        return resp.listings
    }

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return "https://www.google.com/search?tbm=shop&q=$encoded"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? = null
}
