package com.paypal.visualcart.feature.merchant.adapters

import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantSearchProvider
import com.paypal.visualcart.feature.merchant.MerchantUrlHelper

/**
 * Adapter for Tata CLiQ (tatacliq.com).
 * Constructs direct valid search queries, parses Tata CLiQ product URLs,
 * and provides verified demo catalog listings or live API responses.
 */
class TataCliqAdapter(
    override var isLiveMode: Boolean = false
) : MerchantSearchProvider {

    override val merchantId: String = "tatacliq"
    override val merchantName: String = "Tata CLiQ"
    override val domain: String = "tatacliq.com"

    private val productIdRegex = Regex("/p-([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
    private val slugRegex = Regex("tatacliq\\.com/([^/]+)/p-[a-zA-Z0-9]+", RegexOption.IGNORE_CASE)

    override fun buildSearchUrl(query: String): String {
        return "https://www.tatacliq.com/search/?searchCategory=all&text=${MerchantUrlHelper.urlEncode(query)}"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? {
        val domainExtracted = MerchantUrlHelper.extractDomain(url)
        if (!domainExtracted.contains("tatacliq.com")) {
            return null
        }

        val productId = productIdRegex.find(url)?.groupValues?.get(1)
        val slug = slugRegex.find(url)?.groupValues?.get(1)
        val title = slug?.let { MerchantUrlHelper.slugToTitle(it) }

        val canonicalUrl = if (productId != null) {
            if (slug != null) "https://www.tatacliq.com/$slug/p-$productId"
            else "https://www.tatacliq.com/product/p-$productId"
        } else {
            url.substringBefore("?")
        }

        return ParsedProductMetadata(
            url = url,
            domain = domain,
            title = title,
            productId = productId,
            canonicalUrl = canonicalUrl,
            category = "Omnichannel E-commerce"
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
