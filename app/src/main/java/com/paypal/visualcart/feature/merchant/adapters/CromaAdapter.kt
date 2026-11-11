package com.paypal.visualcart.feature.merchant.adapters

import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantSearchProvider
import com.paypal.visualcart.feature.merchant.MerchantUrlHelper

/**
 * Adapter for Croma (croma.com).
 * Constructs direct valid search queries, parses Croma product URLs (extracting product ID and title slug),
 * and provides verified demo catalog listings or live API responses.
 */
class CromaAdapter(
    override var isLiveMode: Boolean = false
) : MerchantSearchProvider {

    override val merchantId: String = "croma"
    override val merchantName: String = "Croma"
    override val domain: String = "croma.com"

    private val productIdRegex = Regex("/p/([0-9]+)", RegexOption.IGNORE_CASE)
    private val slugRegex = Regex("croma\\.com/([^/]+)/p/[0-9]+", RegexOption.IGNORE_CASE)

    override fun buildSearchUrl(query: String): String {
        return "https://www.croma.com/searchB?q=${MerchantUrlHelper.urlEncode(query)}"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? {
        val domainExtracted = MerchantUrlHelper.extractDomain(url)
        if (!domainExtracted.contains("croma.com")) {
            return null
        }

        val productId = productIdRegex.find(url)?.groupValues?.get(1)
        val slug = slugRegex.find(url)?.groupValues?.get(1)
        val title = slug?.let { MerchantUrlHelper.slugToTitle(it) }

        val canonicalUrl = if (productId != null) {
            if (slug != null) "https://www.croma.com/$slug/p/$productId"
            else "https://www.croma.com/product/p/$productId"
        } else {
            url.substringBefore("?")
        }

        return ParsedProductMetadata(
            url = url,
            domain = domain,
            title = title,
            productId = productId,
            canonicalUrl = canonicalUrl,
            category = "Consumer Electronics"
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
