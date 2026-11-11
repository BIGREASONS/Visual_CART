package com.paypal.visualcart.core.util

import java.util.Locale
import kotlin.math.abs

/**
 * Shared currency symbol and decimal formatting utility across Capture, Cart, Detail, and Checkout screens.
 */
object CurrencyFormatter {

    fun symbolFor(currency: String?): String = when ((currency ?: "USD").trim().uppercase(Locale.US)) {
        "USD" -> "$"
        "INR" -> "₹"
        "GBP" -> "£"
        "EUR" -> "€"
        "CAD" -> "CA$"
        "AUD" -> "A$"
        "JPY" -> "¥"
        "SEK" -> "kr "
        "CZK" -> "Kč "
        "RON" -> "lei "
        else -> "${currency?.trim()?.uppercase(Locale.US) ?: "USD"} "
    }

    fun format(amount: Double?, currency: String?): String {
        if (amount == null || amount <= 0.0) return "See store"
        val curr = (currency ?: "USD").trim().uppercase(Locale.US)
        val sym = symbolFor(curr)
        return when (curr) {
            "JPY" -> "$sym${String.format(Locale.US, "%,.0f", amount)}"
            "INR" -> {
                if (abs(amount - amount.toLong().toDouble()) < 0.005) {
                    "$sym${String.format(Locale.US, "%,d", amount.toLong())}"
                } else {
                    "$sym${String.format(Locale.US, "%,.2f", amount)}"
                }
            }
            else -> "$sym${String.format(Locale.US, "%,.2f", amount)}"
        }
    }
}
