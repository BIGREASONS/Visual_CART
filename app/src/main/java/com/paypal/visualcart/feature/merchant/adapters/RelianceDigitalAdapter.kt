package com.paypal.visualcart.feature.merchant.adapters

import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantSearchProvider
import com.paypal.visualcart.feature.merchant.MerchantUrlHelper

/**
 * Adapter for Reliance Digital (reliancedigital.in).
 * Constructs direct valid search queries, parses Reliance Digital product URLs,
 * and provides verified demo catalog listings or live API responses.
 */
class RelianceDigitalAdapter(
    override var isLiveMode: Boolean = false
) : MerchantSearchProvider {

    override val merchantId: String = "reliance_digital"
    override val merchantName: String = "Reliance Digital"
    override val domain: String = "reliancedigital.in"

    private val productIdRegex = Regex("/p/([0-9]+)", RegexOption.IGNORE_CASE)
    private val slugRegex = Regex("reliancedigital\\.in/([^/]+)/p/[0-9]+", RegexOption.IGNORE_CASE)

    override fun buildSearchUrl(query: String): String {
        return "https://www.reliancedigital.in/search?q=${MerchantUrlHelper.urlEncode(query)}"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? {
        val domainExtracted = MerchantUrlHelper.extractDomain(url)
        if (!domainExtracted.contains("reliancedigital.in")) {
            return null
        }

        val productId = productIdRegex.find(url)?.groupValues?.get(1)
        val slug = slugRegex.find(url)?.groupValues?.get(1)
        val title = slug?.let { MerchantUrlHelper.slugToTitle(it) }

        val canonicalUrl = if (productId != null) {
            if (slug != null) "https://www.reliancedigital.in/$slug/p/$productId"
            else "https://www.reliancedigital.in/product/p/$productId"
        } else {
            url.substringBefore("?")
        }

        return ParsedProductMetadata(
            url = url,
            domain = domain,
            title = title,
            productId = productId,
            canonicalUrl = canonicalUrl,
            category = "Electronics & Appliances"
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
