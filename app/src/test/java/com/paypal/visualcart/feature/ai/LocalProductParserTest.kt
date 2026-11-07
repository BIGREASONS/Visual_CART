package com.paypal.visualcart.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LocalProductParser.
 * Validates on-device specification extraction (SSD, Audio, Footwear, Computers),
 * brand detection, clutter stripping, and canonical title normalization.
 */
class LocalProductParserTest {

    @Test
    fun testBrandExtraction() {
        val brandsToTest = mapOf(
            "Samsung 990 EVO SSD 1TB M.2 2280" to "Samsung",
            "Sony WH-1000XM5 Wireless Noise Canceling Headphones" to "Sony",
            "Apple MacBook Pro 14-inch M3 Pro 18GB 512GB" to "Apple",
            "Nike Air Max 90 Running Shoes White" to "Nike",
            "Bose QuietComfort 45 Over-Ear Headphones" to "Bose",
            "Dell Alienware m16 R2 Gaming Laptop" to "Dell",
            "Western Digital WD_BLACK SN850X 2TB NVMe" to "Western Digital",
            "Logitech MX Master 3S Wireless Performance Mouse" to "Logitech",
            "Adidas Ultraboost Light Running Shoes" to "Adidas"
        )

        for ((input, expectedBrand) in brandsToTest) {
            val detected = LocalProductParser.detectBrand(input)
            assertEquals("Failed brand detection for: $input", expectedBrand, detected)
        }
    }

    @Test
    fun testSsdSpecificationParsing() {
        val rawText = "Samsung 990 EVO SSD 1TB, PCIe Gen 4.0 x4, NVMe M.2 2280, Internal Solid State Drive (MZ-V9E1T0B)"
        val specs = LocalProductParser.extractSpecifications(rawText)

        assertEquals("Expected 1TB capacity", "1TB", specs["capacity"])
        assertNotNull("Expected interface extracted", specs["interface"])
        assertTrue("Interface should indicate NVMe", specs["interface"]?.contains("NVMe") == true)
        assertEquals("Expected M.2 2280 form factor", "M.2 2280", specs["form_factor"])

        val parsedProduct = LocalProductParser.parse(rawText)
        assertEquals("Samsung", parsedProduct.brand)
        assertEquals("Computers", parsedProduct.category)
        assertTrue(parsedProduct.confidence >= 0.70f)
    }

    @Test
    fun testAudioHeadphoneSpecificationParsing() {
        val rawText = "Sony WH-1000XM5 Wireless Noise Cancelling Over-Ear Headphones with 30 Hours Battery Life Bluetooth 5.2 - Black"
        val specs = LocalProductParser.extractSpecifications(rawText)

        assertEquals("Expected Over-Ear type", "Over-Ear", specs["type"])
        assertEquals("Expected 30 hours battery", "30 hours", specs["battery"])
        assertEquals("Expected Bluetooth 5.2 connectivity", "Bluetooth 5.2", specs["connectivity"])
        assertEquals("Expected Black color", "Black", specs["color"])

        val parsedProduct = LocalProductParser.parse(rawText)
        assertEquals("Sony", parsedProduct.brand)
        assertEquals("Audio", parsedProduct.category)
    }

    @Test
    fun testComputerRamVsStorageDisambiguation() {
        val rawText = "Apple MacBook Pro 14-inch Liquid Retina XDR display Apple M3 Pro chip 18GB Unified Memory 512GB SSD Storage - Space Gray"
        val specs = LocalProductParser.extractSpecifications(rawText)

        assertEquals("Expected 18GB RAM / memory", "18GB", specs["memory"])
        assertEquals("Expected 512GB storage capacity", "512GB", specs["capacity"])
        assertEquals("Expected 14-inch display", "14-inch", specs["display"])
        assertEquals("Expected Apple M3 Pro chip", "Apple M3 Pro", specs["chip"])
        assertEquals("Expected Space Gray color", "Space Gray", specs["color"])

        val parsed = LocalProductParser.parse(rawText)
        assertEquals("Apple", parsed.brand)
        assertEquals("Computers", parsed.category)
    }

    @Test
    fun testFootwearSpecificationParsing() {
        val rawText = "Nike Air Max 90 Men's Running Shoes UK 9 Triple Black"
        val specs = LocalProductParser.extractSpecifications(rawText)

        assertEquals("Expected UK 9 shoe size", "UK 9", specs["size"])
        assertEquals("Expected Men gender", "Men", specs["gender"])
        assertEquals("Expected Triple Black color", "Triple Black", specs["color"])

        val parsed = LocalProductParser.parse(rawText)
        assertEquals("Nike", parsed.brand)
        assertEquals("Footwear", parsed.category)
    }

    @Test
    fun testMarketingClutterRemovalFromTitle() {
        val noisyTitle = "Buy Online Samsung 990 EVO 1TB SSD Free Delivery Best Seller Amazon's Choice 20% off"
        val cleanTitle = LocalProductParser.cleanCanonicalTitle(noisyTitle, "Samsung", emptyMap())

        assertFalse("Clean title must not contain 'Buy Online'", cleanTitle.contains("Buy Online", ignoreCase = true))
        assertFalse("Clean title must not contain 'Free Delivery'", cleanTitle.contains("Free Delivery", ignoreCase = true))
        assertFalse("Clean title must not contain 'Best Seller'", cleanTitle.contains("Best Seller", ignoreCase = true))
        assertFalse("Clean title must not contain 'Amazon's Choice'", cleanTitle.contains("Amazon's Choice", ignoreCase = true))
        assertFalse("Clean title must not contain '20% off'", cleanTitle.contains("20% off", ignoreCase = true))

        assertTrue("Clean title must retain product core name", cleanTitle.contains("Samsung 990 EVO 1TB SSD"))
    }

    @Test
    fun testParseFullProductPipeline() {
        val ocrSnippet = "Sony WH-1000XM4 Wireless Premium Noise Canceling Overhead Headphones with Mic Black Free Shipping Top Rated"
        val recognized = LocalProductParser.parse(ocrSnippet, imageUrl = "https://example.com/sony.jpg")

        assertEquals("Sony", recognized.brand)
        assertEquals("Audio", recognized.category)
        assertEquals("https://example.com/sony.jpg", recognized.imageUrl)
        assertTrue("Confidence should be high (> 0.7)", recognized.confidence >= 0.7f)
        assertTrue("Match reason should explain detected attributes", recognized.matchReason.contains("Sony"))
    }
}
