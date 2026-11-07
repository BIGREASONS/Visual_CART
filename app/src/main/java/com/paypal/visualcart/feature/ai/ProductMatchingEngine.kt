package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchExplanation
import com.paypal.visualcart.domain.model.MatchResult
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.ProductCandidate
import com.paypal.visualcart.domain.model.RecognizedProduct
import java.util.Locale

/**
 * Intelligent canonical product matching and explainability engine.
 * Computes brand equivalence, model number alignment, and key specification overlaps,
 * assigning a normalized confidence score (0.0 to 1.0) and human-readable explanation.
 */
object ProductMatchingEngine {

    private val BRAND_SYNONYMS = mapOf(
        "wd" to "western digital",
        "western digital" to "western digital",
        "wd black" to "western digital",
        "wd_black" to "western digital",
        "hp" to "hp",
        "hewlett packard" to "hp",
        "omen" to "hp",
        "apple inc" to "apple",
        "apple" to "apple",
        "logi" to "logitech",
        "logitech" to "logitech",
        "logitech g" to "logitech",
        "samsung" to "samsung",
        "samsung electronics" to "samsung",
        "sony" to "sony",
        "sony corp" to "sony",
        "sony corporation" to "sony",
        "nike" to "nike",
        "nike inc" to "nike",
        "adidas" to "adidas",
        "adidas ag" to "adidas",
        "crucial" to "crucial",
        "crucial by micron" to "crucial",
        "micron" to "crucial",
        "sandisk" to "sandisk",
        "sandisk corp" to "sandisk",
        "dell" to "dell",
        "alienware" to "dell",
        "lenovo" to "lenovo",
        "thinkpad" to "lenovo",
        "asus" to "asus",
        "asus rog" to "asus",
        "bose" to "bose",
        "anker" to "anker",
        "soundcore" to "anker",
        "polago" to "polago",
        "polago clothing" to "polago",
        "tatacliq" to "tatacliq",
        "tata cliq" to "tatacliq",
        "croma" to "croma",
        "croma retail" to "croma",
        "reliance" to "reliance digital",
        "reliance digital" to "reliance digital"
    )

    /**
     * Compares a recognized product candidate against target attributes.
     */
    fun compare(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetSpecs: Map<String, String>
    ): MatchResult {
        val targetParsed = LocalProductParser.parse(targetTitle)
        val effectiveTargetBrand = targetBrand ?: targetParsed.brand
        val combinedTargetSpecs = targetSpecs.toMutableMap().apply {
            targetParsed.specifications.forEach { (k, v) -> putIfAbsent(k, v) }
        }

        return evaluateMatch(
            candidate = candidate,
            targetTitle = targetTitle,
            targetBrand = effectiveTargetBrand,
            targetCategory = targetParsed.category,
            targetSpecs = combinedTargetSpecs
        )
    }

    /**
     * Compares two recognized products directly.
     */
    fun compareProducts(candidate: RecognizedProduct, target: RecognizedProduct): MatchResult {
        return evaluateMatch(
            candidate = candidate,
            targetTitle = target.title,
            targetBrand = target.brand,
            targetCategory = target.category,
            targetSpecs = target.specifications
        )
    }

    /**
     * Compares a raw ProductCandidate with a target RecognizedProduct.
     */
    fun compareCandidateWithTarget(candidate: ProductCandidate, target: RecognizedProduct): MatchResult {
        val recognizedCandidate = if (candidate.specifications.isNotEmpty() && candidate.brand != null) {
            RecognizedProduct(
                id = candidate.id,
                title = candidate.title,
                brand = candidate.brand,
                category = candidate.category ?: target.category,
                specifications = candidate.specifications,
                confidence = 0.9f
            )
        } else {
            LocalProductParser.parse(
                rawText = "${candidate.title} ${candidate.rawText ?: ""}",
                imageUrl = candidate.imageUrl
            ).copy(id = candidate.id)
        }

        return compareProducts(recognizedCandidate, target)
    }

    private fun evaluateMatch(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetCategory: String,
        targetSpecs: Map<String, String>
    ): MatchResult {
        val matchedAttributes = mutableListOf<String>()
        val differingAttributes = mutableListOf<String>()
        val missingAttributes = mutableListOf<String>()

        var score = 0.0f

        // 1. Brand Evaluation (Weight: up to 0.30)
        val candidateBrandNorm = normalizeBrand(candidate.brand)
        val targetBrandNorm = normalizeBrand(targetBrand)

        val brandMatch: Boolean
        if (candidateBrandNorm != null && targetBrandNorm != null) {
            if (candidateBrandNorm == targetBrandNorm) {
                brandMatch = true
                score += 0.30f
                matchedAttributes.add("brand: ${candidate.brand}")
            } else {
                brandMatch = false
                differingAttributes.add("brand: '${candidate.brand}' vs '${targetBrand}'")
            }
        } else if (candidateBrandNorm != null && targetTitle.lowercase().contains(candidateBrandNorm)) {
            brandMatch = true
            score += 0.25f
            matchedAttributes.add("brand: ${candidate.brand}")
        } else if (targetBrandNorm != null && candidate.title.lowercase().contains(targetBrandNorm)) {
            brandMatch = true
            score += 0.25f
            matchedAttributes.add("brand: $targetBrand")
        } else {
            // Unspecified brand
            brandMatch = true
            score += 0.10f
            missingAttributes.add("brand: not specified")
        }

        // 2. Title & Model Overlap Evaluation (Weight: up to 0.40)
        val candidateTokens = extractSalientTokens(candidate.title)
        val targetTokens = extractSalientTokens(targetTitle)

        val commonTokens = candidateTokens.intersect(targetTokens)
        val tokenOverlap = if (targetTokens.isNotEmpty()) {
            commonTokens.size.toFloat() / targetTokens.size.toFloat()
        } else {
            0.5f
        }

        val modelScore = (tokenOverlap * 0.40f).coerceIn(0.0f, 0.40f)
        score += modelScore

        if (commonTokens.isNotEmpty()) {
            matchedAttributes.add("model keywords: [${commonTokens.joinToString(", ")}]")
        }

        // 3. Key Specifications Evaluation (Weight: up to 0.30)
        val specComparison = compareSpecs(candidate.specifications, targetSpecs)
        matchedAttributes.addAll(specComparison.matched)
        differingAttributes.addAll(specComparison.differing)
        missingAttributes.addAll(specComparison.missing)

        score += specComparison.specScore

        // Penalties for conflicting brands or differing key specs
        if (!brandMatch) {
            score = (score * 0.4f).coerceAtMost(0.45f) // Hard cap if brands clash
        }

        if (differingAttributes.any { it.startsWith("capacity:") || it.startsWith("size:") || it.startsWith("chip:") }) {
            // Critical spec difference downgrades to SIMILAR alternative
            score = score.coerceAtMost(0.64f)
        }

        val finalConfidence = score.coerceIn(0.05f, 1.0f)

        // Determine Match Type
        val matchType = when {
            finalConfidence >= 0.85f && brandMatch && differingAttributes.isEmpty() -> MatchType.EXACT
            finalConfidence >= 0.65f && brandMatch -> MatchType.LIKELY
            else -> MatchType.SIMILAR
        }

        val explanation = buildExplanation(
            matchType = matchType,
            confidence = finalConfidence,
            brand = candidate.brand ?: targetBrand,
            category = candidate.category.ifBlank { targetCategory },
            matchedAttributes = matchedAttributes,
            differingAttributes = differingAttributes,
            missingAttributes = missingAttributes
        )

        return MatchResult(
            candidate = candidate.copy(
                matchType = matchType,
                matchReason = explanation.summary,
                confidence = finalConfidence
            ),
            matchType = matchType,
            confidence = finalConfidence,
            explanation = explanation
        )
    }

    private fun compareSpecs(
        candidateSpecs: Map<String, String>,
        targetSpecs: Map<String, String>
    ): SpecComparisonResult {
        val matched = mutableListOf<String>()
        val differing = mutableListOf<String>()
        val missing = mutableListOf<String>()

        if (targetSpecs.isEmpty() && candidateSpecs.isEmpty()) {
            return SpecComparisonResult(0.20f, matched, differing, missing)
        }

        val allKeys = candidateSpecs.keys + targetSpecs.keys
        var matchedCount = 0
        var differingCount = 0

        for (key in allKeys) {
            val cVal = candidateSpecs[key]
            val tVal = targetSpecs[key]

            if (cVal != null && tVal != null) {
                if (normalizeSpecValue(cVal) == normalizeSpecValue(tVal)) {
                    matched.add("$key: $cVal")
                    matchedCount++
                } else {
                    differing.add("$key: '$cVal' vs '$tVal'")
                    differingCount++
                }
            } else if (tVal != null) {
                missing.add("target $key: '$tVal' not found in candidate")
            }
        }

        val totalEvaluated = matchedCount + differingCount
        val specScore = if (totalEvaluated > 0) {
            val ratio = matchedCount.toFloat() / totalEvaluated.toFloat()
            ratio * 0.30f
        } else {
            0.15f
        }

        return SpecComparisonResult(specScore, matched, differing, missing)
    }

    private data class SpecComparisonResult(
        val specScore: Float,
        val matched: List<String>,
        val differing: List<String>,
        val missing: List<String>
    )

    private fun normalizeBrand(brand: String?): String? {
        if (brand == null) return null
        val lower = brand.lowercase(Locale.ROOT).trim()
        return BRAND_SYNONYMS[lower] ?: lower
    }

    private fun normalizeSpecValue(value: String): String {
        val raw = value.lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .replace("\"", "inch")

        // 1. Capacity Equivalence (e.g. 1TB == 1000GB == 1024GB)
        if (raw == "1tb" || raw == "1000gb" || raw == "1024gb") return "1000gb"
        if (raw == "2tb" || raw == "2000gb" || raw == "2048gb") return "2000gb"
        if (raw == "4tb" || raw == "4000gb") return "4000gb"
        if (raw == "500gb" || raw == "512gb") return "512gb"
        if (raw == "250gb" || raw == "256gb") return "256gb"
        if (raw == "128gb") return "128gb"
        if (raw == "64gb") return "64gb"

        // 2. Bus / Interface Equivalence
        if (raw.contains("pcie") && (raw.contains("4.0") || raw.contains("gen4") || raw.contains("4x4"))) return "pcie4"
        if (raw.contains("pcie") && (raw.contains("5.0") || raw.contains("gen5") || raw.contains("5x2"))) return "pcie5"
        if (raw.contains("nvme")) return "nvme"

        // 3. Apparel Size Equivalence
        if (raw == "sizem" || raw == "medium" || raw == "m") return "m"
        if (raw == "sizes" || raw == "small" || raw == "s") return "s"
        if (raw == "sizel" || raw == "large" || raw == "l") return "l"
        if (raw == "sizexl" || raw == "xlarge" || raw == "extralarge" || raw == "xl") return "xl"
        if (raw == "sizexxl" || raw == "2xl" || raw == "xxlarge" || raw == "xxl") return "xxl"

        // 4. Color Equivalence
        if (raw.contains("black") || raw == "charcoal") return "black"
        if (raw.contains("silver") || raw == "starlight") return "silver"
        if (raw.contains("gray") || raw.contains("grey") || raw == "graphite") return "gray"

        return raw
    }

    private fun extractSalientTokens(text: String): Set<String> {
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "with", "for", "in", "on", "by", "of",
            "at", "to", "buy", "online", "free", "shipping", "new", "original"
        )
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]"))
            .filter { it.length > 1 && it !in stopWords }
            .toSet()
    }

    private fun buildExplanation(
        matchType: MatchType,
        confidence: Float,
        brand: String?,
        category: String,
        matchedAttributes: List<String>,
        differingAttributes: List<String>,
        missingAttributes: List<String>
    ): MatchExplanation {
        val summary = when (matchType) {
            MatchType.EXACT -> {
                val matchedStr = matchedAttributes.take(3).joinToString(", ")
                "Exact match: matching ${brand?.let { "brand '$it', " } ?: ""}$matchedStr."
            }
            MatchType.LIKELY -> {
                val matchedStr = matchedAttributes.take(3).joinToString(", ")
                "Likely same product: matching ${brand?.let { "brand '$it', " } ?: ""}$matchedStr."
            }
            MatchType.SIMILAR -> {
                if (differingAttributes.isNotEmpty()) {
                    val diffStr = differingAttributes.take(2).joinToString(" and ")
                    "Similar alternative: same brand/category but differing $diffStr."
                } else {
                    "Similar alternative in $category category."
                }
            }
        }

        return MatchExplanation(
            matchType = matchType,
            confidence = confidence,
            summary = summary,
            matchedAttributes = matchedAttributes,
            differingAttributes = differingAttributes,
            missingAttributes = missingAttributes
        )
    }
}
