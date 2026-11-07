package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchResult
import com.paypal.visualcart.domain.model.RecognizedProduct

/**
 * Core interface for multimodal and local AI product recognition, attribute extraction,
 * and canonical comparison.
 */
interface ProductUnderstandingProvider {
    /**
     * Recognizes and extracts structured product attributes from text or OCR string.
     */
    suspend fun recognizeFromText(text: String): RecognizedProduct

    /**
     * Recognizes structured product details from an image URI (local file, screenshot, or remote)
     * with optional surrounding context text.
     */
    suspend fun recognizeFromImage(imageUri: String, contextText: String? = null): RecognizedProduct

    /**
     * Compares a candidate recognized product against a target product definition,
     * providing categorization (EXACT, LIKELY, SIMILAR), confidence score, and explainability.
     */
    suspend fun compareProducts(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetSpecs: Map<String, String>
    ): MatchResult

    /**
     * Returns the human-readable identifier of this recognition provider.
     */
    fun getProviderName(): String
}
