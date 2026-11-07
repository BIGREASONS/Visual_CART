package com.paypal.visualcart.domain.model

// MatchType is declared in MerchantModels.kt in this package


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
    val model: String? = null
)

/**
 * Structured product recognition schema returned by multimodal AI providers
 * (Gemini 1.5 Flash and backend proxy gateway).
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
    val isLiveInference: Boolean = !isDemoFixture
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
