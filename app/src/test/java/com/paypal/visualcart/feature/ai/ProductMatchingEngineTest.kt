package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.ProductCandidate
import com.paypal.visualcart.domain.model.RecognizedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ProductMatchingEngine.
 * Validates canonical product comparison, exact match identification,
 * attribute mismatch penalties, and explainability summary generation.
 */
class ProductMatchingEngineTest {

    @Test
    fun testExactMatchDetectionForIdenticalSsd() {
        val target = RecognizedProduct(
            id = "target_1",
            title = "Samsung 990 EVO SSD 1TB M.2 NVMe Internal Solid State Drive",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "1TB",
                "interface" to "NVMe",
                "form_factor" to "M.2"
            ),
            confidence = 0.95f
        )

        val candidate = RecognizedProduct(
            id = "candidate_1",
            title = "Samsung 990 EVO 1TB NVMe M.2 Internal SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "1TB",
                "interface" to "NVMe",
                "form_factor" to "M.2"
            ),
            confidence = 0.92f
        )

        val result = ProductMatchingEngine.compareProducts(candidate, target)

        assertEquals("Expected EXACT match type", MatchType.EXACT, result.matchType)
        assertTrue("Confidence score must be >= 0.85 for EXACT match, was ${result.confidence}", result.confidence >= 0.85f)
        assertTrue("Differing attributes must be empty for exact match", result.explanation.differingAttributes.isEmpty())
        assertTrue("Explanation summary should mention exact match", result.explanation.summary.contains("Exact match", ignoreCase = true))
        assertTrue("Matched attributes should include capacity", result.explanation.matchedAttributes.any { it.contains("capacity: 1TB") })
    }

    @Test
    fun testAttributeMismatchDowngradesFromExact() {
        // Target is 1TB Samsung 990 EVO
        val target = RecognizedProduct(
            id = "target_ssd_1tb",
            title = "Samsung 990 EVO SSD 1TB NVMe",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "1TB",
                "interface" to "NVMe"
            ),
            confidence = 0.90f
        )

        // Candidate is 2TB variant of Samsung 990 EVO
        val candidate2Tb = RecognizedProduct(
            id = "candidate_ssd_2tb",
            title = "Samsung 990 EVO SSD 2TB NVMe",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "2TB",
                "interface" to "NVMe"
            ),
            confidence = 0.90f
        )

        val result = ProductMatchingEngine.compareProducts(candidate2Tb, target)

        assertNotEquals("Capacity mismatch must NOT produce EXACT match", MatchType.EXACT, result.matchType)
        assertTrue(
            "Capacity mismatch must produce SIMILAR or LIKELY",
            result.matchType == MatchType.SIMILAR || result.matchType == MatchType.LIKELY
        )
        assertTrue(
            "Differing attributes must record capacity difference",
            result.explanation.differingAttributes.any { it.contains("capacity") }
        )
        assertTrue(
            "Confidence must be penalized for critical spec mismatch (<= 0.65), was ${result.confidence}",
            result.confidence <= 0.65f
        )
    }

    @Test
    fun testBrandMismatchSeverePenalty() {
        val samsungTarget = RecognizedProduct(
            id = "samsung_ssd",
            title = "Samsung 990 EVO SSD 1TB NVMe",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB"),
            confidence = 0.90f
        )

        val crucialCandidate = RecognizedProduct(
            id = "crucial_ssd",
            title = "Crucial P3 Plus 1TB PCIe Gen4 NVMe M.2 SSD",
            brand = "Crucial",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB"),
            confidence = 0.90f
        )

        val result = ProductMatchingEngine.compareProducts(crucialCandidate, samsungTarget)

        assertEquals("Differing brands must produce SIMILAR match type", MatchType.SIMILAR, result.matchType)
        assertTrue("Confidence must be capped below 0.50 when brands clash, was ${result.confidence}", result.confidence <= 0.50f)
        assertTrue(
            "Differing attributes must record brand clash",
            result.explanation.differingAttributes.any { it.contains("brand") }
        )
    }

    @Test
    fun testBrandSynonymEquivalence() {
        val target = RecognizedProduct(
            id = "wd_target",
            title = "Western Digital SN850X 1TB NVMe SSD",
            brand = "Western Digital",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB"),
            confidence = 0.90f
        )

        val candidate = RecognizedProduct(
            id = "wd_candidate",
            title = "WD Black SN850X 1TB NVMe SSD",
            brand = "WD",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB"),
            confidence = 0.90f
        )

        val result = ProductMatchingEngine.compareProducts(candidate, target)

        // "WD" and "Western Digital" are recognized as brand synonyms
        assertFalse(
            "Brand synonym should not be listed in differing attributes",
            result.explanation.differingAttributes.any { it.contains("brand") }
        )
        assertTrue(
            "Matched attributes should include brand",
            result.explanation.matchedAttributes.any { it.contains("brand") }
        )
    }

    @Test
    fun testCandidateWithTargetComparison() {
        val target = RecognizedProduct(
            id = "sony_target",
            title = "Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
            brand = "Sony",
            category = "Audio",
            specifications = mapOf("type" to "Over-Ear"),
            confidence = 0.95f
        )

        val candidate = ProductCandidate(
            id = "sony_cand_1",
            title = "Sony WH-1000XM5 Noise Cancelling Over-Ear Headphones - Black",
            brand = "Sony",
            category = "Audio",
            price = 26990.0,
            merchant = "Amazon",
            specifications = mapOf("type" to "Over-Ear", "color" to "Black")
        )

        val result = ProductMatchingEngine.compareCandidateWithTarget(candidate, target)

        assertEquals("Expected EXACT or LIKELY match for identical headphone model", MatchType.EXACT, result.matchType)
        assertNotNull(result.explanation.summary)
        assertTrue(result.confidence >= 0.80f)
    }

    @Test
    fun testConfidenceCalculationBoundsAndExplanations() {
        val target = RecognizedProduct(
            id = "target_generic",
            title = "Wireless Optical Mouse 2.4GHz",
            brand = null,
            category = "Electronics",
            specifications = emptyMap(),
            confidence = 0.5f
        )

        val candidate = RecognizedProduct(
            id = "cand_generic",
            title = "Wireless Optical Mouse 2.4GHz USB",
            brand = null,
            category = "Electronics",
            specifications = emptyMap(),
            confidence = 0.5f
        )

        val result = ProductMatchingEngine.compareProducts(candidate, target)

        assertTrue("Confidence must be between 0.0 and 1.0", result.confidence in 0.0f..1.0f)
        assertNotNull("Explanation summary must not be null", result.explanation.summary)
        assertTrue("Explanation must be non-empty", result.explanation.summary.isNotBlank())
    }

    @Test
    fun testCapacityEquivalenceTbAndGb() {
        val target = RecognizedProduct(
            id = "ssd_1tb",
            title = "Samsung 990 EVO 1TB NVMe M.2 SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB", "interface" to "PCIe 4.0"),
            confidence = 0.95f
        )

        val candidate1000Gb = RecognizedProduct(
            id = "ssd_1000gb",
            title = "Samsung 990 EVO 1000GB NVMe M.2 SSD",
            brand = "Samsung Electronics",
            category = "Computers",
            specifications = mapOf("capacity" to "1000GB", "interface" to "PCIe Gen 4x4"),
            confidence = 0.95f
        )

        val result = ProductMatchingEngine.compareProducts(candidate1000Gb, target)

        assertEquals("1TB and 1000GB should be treated as EXACT equivalence", MatchType.EXACT, result.matchType)
        assertTrue("Differing attributes should be empty", result.explanation.differingAttributes.isEmpty())
        assertTrue("Matched attributes must include capacity", result.explanation.matchedAttributes.any { it.contains("capacity") })
    }

    @Test
    fun testExtendedBrandSynonymsAndBusEquivalence() {
        val target = RecognizedProduct(
            id = "crucial_target",
            title = "Crucial P3 Plus 1TB PCIe Gen4 NVMe M.2 SSD",
            brand = "Crucial",
            category = "Computers",
            specifications = mapOf("capacity" to "1TB", "interface" to "PCIe 4.0"),
            confidence = 0.95f
        )

        val candidateMicron = RecognizedProduct(
            id = "crucial_cand",
            title = "Crucial by Micron P3 Plus 1TB NVMe M.2 SSD",
            brand = "Crucial by Micron",
            category = "Computers",
            specifications = mapOf("capacity" to "1024GB", "interface" to "PCIe Gen 4x4"),
            confidence = 0.95f
        )

        val result = ProductMatchingEngine.compareProducts(candidateMicron, target)

        assertEquals(MatchType.EXACT, result.matchType)
        assertFalse(result.explanation.differingAttributes.any { it.contains("brand") })
    }
}
