package com.paypal.visualcart.domain.model

// MatchType is declared in MerchantModels.kt in this package

enum class RecognitionStatus {
    IDENTIFIED,
    PARTIAL,
    UNIDENTIFIED
}

enum class RecognitionSource {
    LIVE_AI,
    DEMO,
    LOCAL_OCR,
    MANUAL_SEARCH,
    FALLBACK
}

data class RecognizedProductCandidate(
    val brand: String? = null,
    val title: String,
    val category: String,
    val model: String? = null,
    val colour: String? = null,
    val specifications: Map<String, String> = emptyMap(),
    val searchQuery: String = "",
    val confidence: Float = 0.0f
)

/**
 * Represents a structured product recognized from image, screen capture, OCR, or text.
 */
data class RecognizedProduct(
    val id: String,
    val title: String,
    val brand: String?,
    val category: String,
    val specifications: Map<String, String> = emptyMap(),
    val confidence: Float, // 0.0 to 1.0
    val imageUrl: String? = null,
    val rawTextSnippet: String? = null,
    val matchType: MatchType = MatchType.EXACT,
    val matchReason: String = "",
    val isDemoFixture: Boolean = false,
    val isLiveInference: Boolean = false,
    val colour: String? = null,
    val model: String? = null,
    val status: RecognitionStatus = if (
        title.equals("Unidentified Product", ignoreCase = true) ||
        category.equals("Unidentified", ignoreCase = true)
    ) {
        RecognitionStatus.UNIDENTIFIED
    } else {
        RecognitionStatus.IDENTIFIED
    },
    val recognitionSource: RecognitionSource = when {
        isLiveInference -> RecognitionSource.LIVE_AI
        isDemoFixture -> RecognitionSource.DEMO
        else -> RecognitionSource.FALLBACK
    },
    val searchQuery: String = "",
    val aiModelName: String? = null,
    val candidates: List<RecognizedProductCandidate> = emptyList(),
    val diagnosticNote: String? = null,
    val rateLimited: Boolean = false,
    val cacheHit: Boolean = false
) {
    val isIdentified: Boolean
        get() = status == RecognitionStatus.IDENTIFIED

    val isPartial: Boolean
        get() = status == RecognitionStatus.PARTIAL

    val isUnidentified: Boolean
        get() = status == RecognitionStatus.UNIDENTIFIED

    val hasMultipleCandidates: Boolean
        get() = candidates.size > 1
}

/**
 * Structured product recognition schema returned by multimodal AI providers
 * (Gemini multimodal models and backend proxy gateway).
 */
data class StructuredAiProduct(
    val brand: String?,
    val productName: String,
    val category: String,
    val colour: String? = null,
    val model: String? = null,
    val specifications: Map<String, String> = emptyMap(),
    val confidence: Float = 0.9f,
    val matchReason: String = "",
    val isDemoFixture: Boolean = false,
    val isLiveInference: Boolean = !isDemoFixture,
    val status: RecognitionStatus = if (
        productName.equals("Unidentified Product", ignoreCase = true) ||
        category.equals("Unidentified", ignoreCase = true)
    ) {
        RecognitionStatus.UNIDENTIFIED
    } else {
        RecognitionStatus.IDENTIFIED
    },
    val recognitionSource: RecognitionSource = when {
        isDemoFixture -> RecognitionSource.DEMO
        isLiveInference -> RecognitionSource.LIVE_AI
        else -> RecognitionSource.FALLBACK
    },
    val searchQuery: String = "",
    val aiModelName: String? = null,
    val candidates: List<RecognizedProductCandidate> = emptyList(),
    val diagnosticNote: String? = null,
    val rateLimited: Boolean = false,
    val cacheHit: Boolean = false
)

/**
 * Candidate product found in cart, merchant catalog, or web search for comparison.
 */
data class ProductCandidate(
    val id: String,
    val title: String,
    val brand: String? = null,
    val category: String? = null,
    val price: Double? = null,
    val currency: String = "USD",
    val merchant: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val specifications: Map<String, String> = emptyMap(),
    val rawText: String? = null,
    val priceDrop: Boolean = false,
    val originalPrice: Double? = null
)

/**
 * Explainability breakdown of why two items matched or differed.
 */
data class MatchExplanation(
    val matchType: MatchType,
    val confidence: Float,
    val summary: String,
    val matchedAttributes: List<String> = emptyList(),
    val differingAttributes: List<String> = emptyList(),
    val missingAttributes: List<String> = emptyList()
)

/**
 * Result of comparing a recognized product candidate against a target product.
 */
data class MatchResult(
    val candidate: RecognizedProduct,
    val matchType: MatchType,
    val confidence: Float,
    val explanation: MatchExplanation
)
