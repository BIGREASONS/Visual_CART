package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.ProductCandidate
import com.paypal.visualcart.domain.model.RecognizedProduct
import java.util.Locale
import java.util.regex.Pattern

/**
 * Structured criteria extracted from natural-language cart queries.
 */
data class CartFilterCriteria(
    val rawQuery: String,
    val category: String? = null,
    val brand: String? = null,
    val merchant: String? = null,
    val targetKeyword: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val currency: String? = null,
    val priceDropsOnly: Boolean = false,
    val findCheaperAlternatives: Boolean = false,
    val sortByPriceAscending: Boolean = false
)

/**
 * Natural language cart search and filter parser.
 * Supports queries like:
 * - "Show me all electronics below 10000"
 * - "Find cheaper alternatives to my saved SSD"
 * - "Show me everything from Amazon"
 * - "Find products in my cart that have fallen in price"
 * - "Show Nike shoes under ₹5,000"
 */
object NaturalLanguageCartFilter {

    private val KNOWN_MERCHANTS = listOf("Amazon", "Flipkart", "Walmart", "BestBuy", "Target", "eBay", "Nike", "Apple")
    private val KNOWN_CATEGORIES = mapOf(
        "electronics" to "Electronics",
        "gadgets" to "Electronics",
        "audio" to "Audio",
        "headphone" to "Audio",
        "headphones" to "Audio",
        "earphones" to "Audio",
        "earbuds" to "Audio",
        "computers" to "Computers",
        "computer" to "Computers",
        "laptop" to "Computers",
        "laptops" to "Computers",
        "ssd" to "Computers",
        "shoes" to "Footwear",
        "shoe" to "Footwear",
        "footwear" to "Footwear",
        "sneakers" to "Footwear",
        "fashion" to "Fashion",
        "clothing" to "Fashion",
        "gaming" to "Gaming",
        "games" to "Gaming",
        "home" to "Home",
        "kitchen" to "Home"
    )

    private val KNOWN_KEYWORDS = listOf(
        "ssd", "headphone", "headphones", "mouse", "shoes", "sneakers",
        "laptop", "macbook", "monitor", "keyboard", "earbuds"
    )

    /**
     * Parses a natural-language query into structured CartFilterCriteria.
     */
    fun parse(query: String): CartFilterCriteria {
        val q = query.trim()
        val lower = q.lowercase(Locale.ROOT)

        // 1. Currency detection
        val currency = when {
            q.contains("₹") || lower.contains("inr") || lower.contains("rupees") -> "INR"
            q.contains("$") || lower.contains("usd") || lower.contains("dollars") -> "USD"
            q.contains("€") || lower.contains("eur") -> "EUR"
            q.contains("£") || lower.contains("gbp") -> "GBP"
            else -> null
        }

        // 2. Price Bounds parsing (e.g., "below 10000", "under ₹10,000", "between 1000 and 5000", "less than 50")
        var minPrice: Double? = null
        var maxPrice: Double? = null

        val betweenPattern = Pattern.compile("(?i)between\\s*([₹$€£]?\\s*\\d+(?:,\\d+)*(?:\\.\\d+)?)\\s*(?:and|-)\\s*([₹$€£]?\\s*\\d+(?:,\\d+)*(?:\\.\\d+)?)")
        val betweenMatch = betweenPattern.matcher(q)
        if (betweenMatch.find()) {
            minPrice = parseNumber(betweenMatch.group(1))
            maxPrice = parseNumber(betweenMatch.group(2))
        } else {
            val maxPricePattern = Pattern.compile("(?i)(?:below|under|less\\s+than|up\\s+to|max)\\s*([₹$€£]?\\s*\\d+(?:,\\d+)*(?:\\.\\d+)?)")
            val maxMatch = maxPricePattern.matcher(q)
            if (maxMatch.find()) {
                maxPrice = parseNumber(maxMatch.group(1))
            }

            val minPricePattern = Pattern.compile("(?i)(?:above|over|more\\s+than|min|minimum)\\s*([₹$€£]?\\s*\\d+(?:,\\d+)*(?:\\.\\d+)?)")
            val minMatch = minPricePattern.matcher(q)
            if (minMatch.find()) {
                minPrice = parseNumber(minMatch.group(1))
            }
        }

        // 3. Price drop intent
        val priceDropsOnly = lower.contains("fallen in price") ||
                lower.contains("price drop") ||
                lower.contains("price drops") ||
                lower.contains("fell in price") ||
                lower.contains("discounted") ||
                lower.contains("on sale")

        // 4. Cheaper alternatives intent
        val findCheaperAlternatives = lower.contains("cheaper alternative") ||
                lower.contains("cheaper alternatives") ||
                lower.contains("cheaper") ||
                lower.contains("budget alternative") ||
                lower.contains("lower price alternative")

        // 5. Sorting intent
        val sortByPriceAscending = lower.contains("cheapest") ||
                lower.contains("lowest price") ||
                lower.contains("price low to high") ||
                findCheaperAlternatives

        // 6. Merchant detection (e.g., "from Amazon", "on Flipkart")
        var detectedMerchant: String? = null
        for (m in KNOWN_MERCHANTS) {
            if (Pattern.compile("(?i)\\b(?:from|on|at|merchant)\\s+${Pattern.quote(m)}\\b").matcher(q).find() ||
                Pattern.compile("(?i)\\b${Pattern.quote(m)}\\b").matcher(q).find()) {
                detectedMerchant = m
                break
            }
        }

        // 7. Category detection
        var detectedCategory: String? = null
        for ((word, cat) in KNOWN_CATEGORIES) {
            if (Pattern.compile("(?i)\\b${Pattern.quote(word)}\\b").matcher(q).find()) {
                detectedCategory = cat
                break
            }
        }

        // 8. Brand detection via LocalProductParser
        val detectedBrand = LocalProductParser.detectBrand(q)

        // 9. Target keyword detection (e.g. "SSD", "headphone")
        var targetKeyword: String? = null
        for (kw in KNOWN_KEYWORDS) {
            if (Pattern.compile("(?i)\\b${Pattern.quote(kw)}\\b").matcher(q).find()) {
                targetKeyword = kw.uppercase()
                break
            }
        }

        return CartFilterCriteria(
            rawQuery = query,
            category = detectedCategory,
            brand = detectedBrand,
            merchant = detectedMerchant,
            targetKeyword = targetKeyword,
            minPrice = minPrice,
            maxPrice = maxPrice,
            currency = currency,
            priceDropsOnly = priceDropsOnly,
            findCheaperAlternatives = findCheaperAlternatives,
            sortByPriceAscending = sortByPriceAscending
        )
    }

    /**
     * Filters and sorts candidate products using natural-language query criteria.
     */
    fun filterCandidates(candidates: List<ProductCandidate>, query: String): List<ProductCandidate> {
        val criteria = parse(query)
        var result = candidates.filter { matchesCandidate(it, criteria) }

        if (criteria.sortByPriceAscending) {
            result = result.sortedBy { it.price ?: Double.MAX_VALUE }
        }

        return result
    }

    /**
     * Filters structured recognized products using natural-language query criteria.
     */
    fun filterRecognized(products: List<RecognizedProduct>, query: String): List<RecognizedProduct> {
        val criteria = parse(query)
        return products.filter { matchesRecognized(it, criteria) }
    }

    private fun matchesCandidate(candidate: ProductCandidate, criteria: CartFilterCriteria): Boolean {
        // Merchant filter
        if (criteria.merchant != null) {
            if (candidate.merchant == null || !candidate.merchant.equals(criteria.merchant, ignoreCase = true)) {
                return false
            }
        }

        // Category filter
        if (criteria.category != null) {
            val candidateCat = candidate.category ?: LocalProductParser.parse(candidate.title).category
            if (!candidateCat.equals(criteria.category, ignoreCase = true)) {
                return false
            }
        }

        // Brand filter
        if (criteria.brand != null) {
            val candidateBrand = candidate.brand ?: LocalProductParser.detectBrand(candidate.title)
            if (candidateBrand == null || !candidateBrand.equals(criteria.brand, ignoreCase = true)) {
                return false
            }
        }

        // Keyword filter
        if (criteria.targetKeyword != null) {
            val combined = "${candidate.title} ${candidate.rawText ?: ""}"
            if (!combined.contains(criteria.targetKeyword, ignoreCase = true)) {
                return false
            }
        }

        // Price Drops filter
        if (criteria.priceDropsOnly) {
            val hasDropped = candidate.priceDrop || (candidate.originalPrice != null && candidate.price != null && candidate.price < candidate.originalPrice)
            if (!hasDropped) return false
        }

        // Price range
        val price = candidate.price
        if (price != null) {
            if (criteria.minPrice != null && price < criteria.minPrice) return false
            if (criteria.maxPrice != null && price > criteria.maxPrice) return false
        }

        return true
    }

    private fun matchesRecognized(product: RecognizedProduct, criteria: CartFilterCriteria): Boolean {
        // Category
        if (criteria.category != null) {
            if (!product.category.equals(criteria.category, ignoreCase = true)) return false
        }

        // Brand
        if (criteria.brand != null) {
            if (product.brand == null || !product.brand.equals(criteria.brand, ignoreCase = true)) return false
        }

        // Keyword
        if (criteria.targetKeyword != null) {
            val combined = "${product.title} ${product.rawTextSnippet ?: ""}"
            if (!combined.contains(criteria.targetKeyword, ignoreCase = true)) return false
        }

        return true
    }

    private fun parseNumber(raw: String): Double? {
        val clean = raw.replace(Regex("[^0-9.]"), "")
        return clean.toDoubleOrNull()
    }
}
