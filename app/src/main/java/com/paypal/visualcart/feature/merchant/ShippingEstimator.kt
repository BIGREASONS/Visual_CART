package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.ListingOrigin
import java.util.Locale

/**
 * Structured shipping and delivery estimation details.
 */
data class ShippingEstimate(
    val estimateText: String,
    val estimatedDaysMin: Int,
    val estimatedDaysMax: Int,
    val isFreeShipping: Boolean,
    val isFastDelivery: Boolean,
    val shippingFee: Double = 0.0
) {
    val deliverySpeedTier: DeliverySpeedTier
        get() = when {
            estimatedDaysMax <= 1 -> DeliverySpeedTier.SAME_OR_NEXT_DAY
            estimatedDaysMax <= 2 -> DeliverySpeedTier.EXPRESS_2_DAY
            estimatedDaysMax <= 4 -> DeliverySpeedTier.STANDARD_3_4_DAY
            else -> DeliverySpeedTier.EXTENDED
        }
}

enum class DeliverySpeedTier {
    SAME_OR_NEXT_DAY,
    EXPRESS_2_DAY,
    STANDARD_3_4_DAY,
    EXTENDED
}

/**
 * Utility for parsing textual shipping descriptions and evaluating verified shipping policies.
 * Never fabricates shipping fees or promises for missing prices or direct search URLs.
 */
object ShippingEstimator {

    fun estimateOrNull(
        deliveryText: String?,
        merchantId: String,
        price: Double?,
        currency: String = "INR",
        listingOrigin: ListingOrigin = ListingOrigin.VERIFIED_CATALOG_FIXTURE
    ): ShippingEstimate? {
        if (listingOrigin == ListingOrigin.DIRECT_SEARCH_URL ||
            listingOrigin == ListingOrigin.NO_RESULT ||
            price == null ||
            price <= 0.0 ||
            !price.isFinite()
        ) {
            return null
        }

        val normalizedId = SearchQueryBuilder.normalizeMerchantKey(merchantId)
        if (!deliveryText.isNullOrBlank()) {
            val lower = deliveryText.lowercase(Locale.ROOT)
            if (lower.contains("varies") || lower.contains("check merchant")) {
                return null
            }
            return parseText(deliveryText, price, currency)
        }

        if (listingOrigin == ListingOrigin.LIVE_API) {
            // Do not synthesize delivery timelines for live API results when omitted
            return null
        }

        return defaultForMerchant(normalizedId, price, currency)
    }

    /**
     * Legacy-compatible helper returning verified shipping estimates for priced catalog fixtures.
     */
    fun estimate(
        deliveryText: String?,
        merchantId: String,
        price: Double = 0.0
    ): ShippingEstimate {
        return estimateOrNull(
            deliveryText = deliveryText,
            merchantId = merchantId,
            price = price.takeIf { it > 0.0 },
            listingOrigin = ListingOrigin.VERIFIED_CATALOG_FIXTURE
        ) ?: ShippingEstimate(
            estimateText = "Shipping varies by seller",
            estimatedDaysMin = 2,
            estimatedDaysMax = 5,
            isFreeShipping = false,
            isFastDelivery = false,
            shippingFee = 0.0
        )
    }

    fun parseText(
        text: String,
        price: Double? = null,
        currency: String = "INR"
    ): ShippingEstimate {
        val lower = text.lowercase(Locale.ROOT)
        val isInr = currency.equals("INR", ignoreCase = true)
        val hasPositivePrice = price != null && price > 0.0

        val explicitFree = lower.contains("free")
        val thresholdFree = isInr && hasPositivePrice && (price ?: 0.0) >= 499.0
        val isFree = explicitFree || thresholdFree

        val isFast = lower.contains("next-day") ||
            lower.contains("next day") ||
            lower.contains("tomorrow") ||
            lower.contains("24 hours") ||
            lower.contains("3 hours") ||
            lower.contains("same day") ||
            lower.contains("same-day") ||
            lower.contains("express")

        val (minDays, maxDays) = when {
            lower.contains("3 hours") || lower.contains("same day") || lower.contains("same-day") -> Pair(0, 0)
            lower.contains("tomorrow") || lower.contains("next-day") || lower.contains("next day") || lower.contains("24 hours") -> Pair(1, 1)
            lower.contains("24-48 hours") || lower.contains("2-day") || lower.contains("2 days") || lower.contains("2 business days") -> Pair(1, 2)
            lower.contains("3-4") || lower.contains("3 to 4") -> Pair(3, 4)
            lower.contains("2-3") || lower.contains("2 to 3") -> Pair(2, 3)
            lower.contains("3 days") -> Pair(2, 3)
            lower.contains("4 days") -> Pair(3, 4)
            lower.contains("5-7") -> Pair(5, 7)
            else -> Pair(2, 4)
        }

        val fee = when {
            isFree -> 0.0
            hasPositivePrice && isInr -> 49.0
            else -> 0.0
        }

        return ShippingEstimate(
            estimateText = text,
            estimatedDaysMin = minDays,
            estimatedDaysMax = maxDays,
            isFreeShipping = isFree,
            isFastDelivery = isFast,
            shippingFee = fee
        )
    }

    fun defaultForMerchant(
        merchantId: String,
        price: Double = 0.0,
        currency: String = "INR"
    ): ShippingEstimate {
        val normalizedId = SearchQueryBuilder.normalizeMerchantKey(merchantId)
        val isInr = currency.equals("INR", ignoreCase = true)
        val hasPrice = price > 0.0
        return when (normalizedId) {
            "amazon_in" -> {
                val isFree = !isInr || !hasPrice || price >= 499.0
                ShippingEstimate(
                    estimateText = if (isFree) "Free Delivery by Tomorrow (Amazon Prime)" else "Standard Delivery in 1-2 days (₹40)",
                    estimatedDaysMin = 1,
                    estimatedDaysMax = 2,
                    isFreeShipping = isFree,
                    isFastDelivery = true,
                    shippingFee = if (isFree) 0.0 else 40.0
                )
            }
            "flipkart" -> {
                val isFree = !isInr || !hasPrice || price >= 500.0
                ShippingEstimate(
                    estimateText = if (isFree) "Free Delivery by Tomorrow, 2 PM (Flipkart Assured)" else "Standard Delivery in 2 days (₹40)",
                    estimatedDaysMin = 1,
                    estimatedDaysMax = 2,
                    isFreeShipping = isFree,
                    isFastDelivery = true,
                    shippingFee = if (isFree) 0.0 else 40.0
                )
            }
            "croma" -> {
                ShippingEstimate(
                    estimateText = "Express Delivery in 24 hours (Free Croma Delivery)",
                    estimatedDaysMin = 1,
                    estimatedDaysMax = 2,
                    isFreeShipping = true,
                    isFastDelivery = true,
                    shippingFee = 0.0
                )
            }
            "reliance_digital" -> {
                ShippingEstimate(
                    estimateText = "Standard 2-Day Delivery or Free In-Store Pickup",
                    estimatedDaysMin = 2,
                    estimatedDaysMax = 3,
                    isFreeShipping = true,
                    isFastDelivery = false,
                    shippingFee = 0.0
                )
            }
            "myntra" -> {
                val isFree = !isInr || !hasPrice || price >= 799.0
                ShippingEstimate(
                    estimateText = if (isFree) "Free Delivery in 2-3 business days" else "Standard Delivery in 2-3 days (₹99)",
                    estimatedDaysMin = 2,
                    estimatedDaysMax = 3,
                    isFreeShipping = isFree,
                    isFastDelivery = false,
                    shippingFee = if (isFree) 0.0 else 99.0
                )
            }
            "tatacliq" -> {
                val isFree = !isInr || !hasPrice || price >= 999.0
                ShippingEstimate(
                    estimateText = if (isFree) "Free Standard Delivery in 3-4 business days" else "Delivery in 3-4 business days (₹49)",
                    estimatedDaysMin = 3,
                    estimatedDaysMax = 4,
                    isFreeShipping = isFree,
                    isFastDelivery = false,
                    shippingFee = if (isFree) 0.0 else 49.0
                )
            }
            else -> {
                val isFree = !isInr || !hasPrice || price >= 500.0
                ShippingEstimate(
                    estimateText = "Standard Delivery in 2-4 business days",
                    estimatedDaysMin = 2,
                    estimatedDaysMax = 4,
                    isFreeShipping = isFree,
                    isFastDelivery = false,
                    shippingFee = if (isFree) 0.0 else 50.0
                )
            }
        }
    }
}
