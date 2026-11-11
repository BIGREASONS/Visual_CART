package com.paypal.visualcart.feature.merchant.adapters

import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantSearchProvider
import com.paypal.visualcart.feature.merchant.MerchantUrlHelper

/**
 * Adapter for Myntra (myntra.com).
 * Constructs direct valid search queries, parses Myntra product URLs (extracting product ID and title slug),
 * and provides verified demo catalog listings or live API responses.
 */
class MyntraAdapter(
    override var isLiveMode: Boolean = false
) : MerchantSearchProvider {

    override val merchantId: String = "myntra"
    override val merchantName: String = "Myntra"
    override val domain: String = "myntra.com"

    private val productIdRegex = Regex("/([0-9]+)(?:/buy|[/?]|$)", RegexOption.IGNORE_CASE)
    private val slugRegex = Regex("myntra\\.com/(?:[^/]+/){1,3}([^/]+)/[0-9]+(?:/buy)?", RegexOption.IGNORE_CASE)

    override fun buildSearchUrl(query: String): String {
        return "https://www.myntra.com/${MerchantUrlHelper.urlEncode(query)}"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? {
        val domainExtracted = MerchantUrlHelper.extractDomain(url)
        if (!domainExtracted.contains("myntra.com")) {
            return null
        }

        val productId = productIdRegex.find(url)?.groupValues?.get(1)
        val slug = slugRegex.find(url)?.groupValues?.get(1)
        val title = slug?.let { MerchantUrlHelper.slugToTitle(it) }

        val canonicalUrl = if (productId != null) {
            "https://www.myntra.com/product/$productId/buy"
        } else {
            url.substringBefore("?")
        }

        return ParsedProductMetadata(
            url = url,
            domain = domain,
            title = title,
            productId = productId,
            canonicalUrl = canonicalUrl,
            category = "Fashion & Lifestyle"
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
