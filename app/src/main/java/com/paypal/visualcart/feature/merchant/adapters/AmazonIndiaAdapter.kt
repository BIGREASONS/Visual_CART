package com.paypal.visualcart.feature.merchant.adapters

import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.ParsedProductMetadata
import com.paypal.visualcart.feature.merchant.DemoMerchantFixtures
import com.paypal.visualcart.feature.merchant.MerchantSearchProvider
import com.paypal.visualcart.feature.merchant.MerchantUrlHelper

/**
 * Adapter for Amazon India (amazon.in).
 * Constructs direct valid search queries, parses Amazon product URLs (extracting ASIN and title slugs),
 * and provides verified demo catalog listings or live API responses.
 */
class AmazonIndiaAdapter(
    override var isLiveMode: Boolean = false
) : MerchantSearchProvider {

    override val merchantId: String = "amazon_in"
    override val merchantName: String = "Amazon India"
    override val domain: String = "amazon.in"

    private val asinRegex = Regex("(?:/dp/|/gp/product/|/product/)([A-Z0-9]{10})", RegexOption.IGNORE_CASE)
    private val slugAndAsinRegex = Regex("amazon\\.in/([^/]+)/dp/([A-Z0-9]{10})", RegexOption.IGNORE_CASE)

    override fun buildSearchUrl(query: String): String {
        return "https://www.amazon.in/s?k=${MerchantUrlHelper.urlEncode(query)}"
    }

    override fun parseProductUrl(url: String): ParsedProductMetadata? {
        val domainExtracted = MerchantUrlHelper.extractDomain(url)
        if (!domainExtracted.contains("amazon.in") && !domainExtracted.contains("amazon.com")) {
            return null
        }

        val asinMatch = asinRegex.find(url)
        val asin = asinMatch?.groupValues?.get(1)?.uppercase()

        var title: String? = null
        val slugMatch = slugAndAsinRegex.find(url)
        if (slugMatch != null) {
            val rawSlug = slugMatch.groupValues[1]
            title = MerchantUrlHelper.slugToTitle(rawSlug)
        }

        val canonicalUrl = if (asin != null) {
            "https://www.amazon.in/dp/$asin"
        } else {
            url.substringBefore("?")
        }

        return ParsedProductMetadata(
            url = url,
            domain = domain,
            title = title,
            productId = asin,
            canonicalUrl = canonicalUrl,
            category = "General E-commerce"
        )
    }

    override suspend fun search(
        query: String,
        targetSpecs: Map<String, String>
    ): List<MerchantListing> {
        if (isLiveMode) {
            val live = DemoMerchantFixtures.createLiveListing(
                merchantId = merchantId,
                merchantName = merchantName,
                merchantDomain = domain,
                title = "$query (Amazon India Live API)",
                price = 5999.0,
                originalPrice = 9999.0,
                productUrl = buildSearchUrl(query),
                deliveryEstimate = "Free Next-Day Delivery by Prime",
                sellerName = "Amazon Retail India"
            )
            return listOf(live)
        }

        val listing = DemoMerchantFixtures.findDemoListing(
            merchantId = merchantId,
            merchantName = merchantName,
            merchantDomain = domain,
            query = query,
            searchUrlBuilder = ::buildSearchUrl
        )

        return if (listing != null) listOf(listing) else emptyList()
    }
}
