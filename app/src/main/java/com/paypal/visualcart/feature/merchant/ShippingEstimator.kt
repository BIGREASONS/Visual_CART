package com.paypal.visualcart.feature.merchant

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
 * Robust utility for parsing textual shipping descriptions, determining delivery speed,
 * evaluating free shipping qualification, and estimating realistic delivery timelines
 * across the 6 supported merchants (Amazon India, Flipkart, Croma, Reliance Digital, Myntra, Tata CLiQ).
 */
object ShippingEstimator {

    /**
     * Parses an existing merchant delivery estimate string, or generates an accurate
     * estimated timeline for the given [merchantId] and order [price].
     */
    fun estimate(
        deliveryText: String?,
        merchantId: String,
        price: Double = 0.0
    ): ShippingEstimate {
        if (!deliveryText.isNullOrBlank()) {
            return parseText(deliveryText, price)
        }

        return defaultForMerchant(merchantId, price)
    }

    /**
     * Parses free-form merchant delivery strings into structured [ShippingEstimate].
     * Handles patterns like:
     * - "Free Next-Day Delivery by Prime"
     * - "Delivery by Tomorrow, 2 PM"
     * - "Standard Delivery in 24 hours"
     * - "Express Delivery in 3 hours in select cities"
     * - "Delivery in 2 business days"
     * - "Delivery in 3-4 days"
     */
    fun parseText(text: String, price: Double = 0.0): ShippingEstimate {
        val lower = text.lowercase(Locale.ROOT)

        val isFree = lower.contains("free") || price >= 499.0
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

        val fee = if (isFree) 0.0 else 49.0

        return ShippingEstimate(
            estimateText = text,
            estimatedDaysMin = minDays,
            estimatedDaysMax = maxDays,
            isFreeShipping = isFree,
            isFastDelivery = isFast,
            shippingFee = fee
        )
    }

    /**
     * Merchant-specific baseline policies for shipping estimates.
     */
    fun defaultForMerchant(merchantId: String, price: Double = 0.0): ShippingEstimate {
        return when (merchantId.lowercase(Locale.ROOT)) {
            "amazon_in" -> {
                val isFree = price >= 499.0
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
                val isFree = price >= 500.0
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
                val isFree = price >= 799.0
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
                val isFree = price >= 999.0
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
                ShippingEstimate(
                    estimateText = "Standard Delivery in 2-4 business days",
                    estimatedDaysMin = 2,
                    estimatedDaysMax = 4,
                    isFreeShipping = price >= 500.0,
                    isFastDelivery = false,
                    shippingFee = if (price >= 500.0) 0.0 else 50.0
                )
            }
        }
    }
}
