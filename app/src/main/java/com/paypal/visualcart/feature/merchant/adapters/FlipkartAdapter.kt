package com.paypal.visualcart.feature.merchant.adapters

import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantSearchProvider
import com.paypal.visualcart.feature.merchant.MerchantUrlHelper

/**
 * Adapter for Flipkart (flipkart.com).
 * Constructs valid search queries, parses Flipkart URLs (extracting PID and slug title),
 * and provides verified demo catalog listings or live API responses.
 */
class FlipkartAdapter(
    override var isLiveMode: Boolean = false
) : MerchantSearchProvider {

    override val merchantId: String = "flipkart"
    override val merchantName: String = "Flipkart"
    override val domain: String = "flipkart.com"

    private val pidParamRegex = Regex("[?&]pid=([A-Z0-9]+)", RegexOption.IGNORE_CASE)
    private val pathItmRegex = Regex("/p/(itm[a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
    private val slugRegex = Regex("flipkart\\.com/([^/]+)/p/", RegexOption.IGNORE_CASE)

    override fun buildSearchUrl(query: String): String {
        return "https://www.flipkart.com/search?q=${MerchantUrlHelper.urlEncode(query)}"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? {
        val domainExtracted = MerchantUrlHelper.extractDomain(url)
        if (!domainExtracted.contains("flipkart.com")) {
            return null
        }

        val pid = pidParamRegex.find(url)?.groupValues?.get(1)?.uppercase()
            ?: pathItmRegex.find(url)?.groupValues?.get(1)

        val slug = slugRegex.find(url)?.groupValues?.get(1)
        val title = slug?.let { MerchantUrlHelper.slugToTitle(it) }

        val canonicalUrl = when {
            slug != null && pid != null -> "https://www.flipkart.com/$slug/p/product?pid=$pid"
            pid != null -> "https://www.flipkart.com/product/p/itm?pid=$pid"
            else -> url.substringBefore("?")
        }

        return ParsedProductMetadata(
            url = url,
            domain = domain,
            title = title,
            productId = pid,
            canonicalUrl = canonicalUrl,
            category = "General E-commerce"
        )
    }

    override suspend fun search(
        query: String,
        targetSpecs: Map<String, String>
    ): List<MerchantListing> {
        val canonicalQuery = com.paypal.visualcart.feature.merchant.SearchQueryBuilder
            .buildFromRawQuery(query)
            .validQueryOrNull ?: return emptyList()

        if (isLiveMode) {
            return listOf(buildDirectSearchListing(canonicalQuery))
        }

        val listing = DemoMerchantFixtures.findDemoListing(
            merchantId = merchantId,
            merchantName = merchantName,
            merchantDomain = domain,
            query = canonicalQuery,
            searchUrlBuilder = ::buildSearchUrl
        )

        return when {
            listing != null -> listOf(listing)
            DemoMerchantFixtures.hasCatalogMatch(canonicalQuery) -> emptyList()
            else -> listOf(buildDirectSearchListing(canonicalQuery))
        }
    }
}
