package com.paypal.visualcart.ui.share

import com.paypal.visualcart.core.util.SecurityUtils
import com.paypal.visualcart.core.util.SharingShortcutsManager
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.SourceType
import com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider
import com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualCartShareDirectCaptureTest {

    private val recognitionProvider = DemoProductRecognitionProvider()
    private val merchantEngine = MerchantDiscoveryEngine()

    @Test
    fun testPolagoClothingRecognition_UserExampleA() {
        runBlocking {
            // User Example A from Chrome image long press: "MINIMAL MAXI M - ₹2,500.00 · Polago Clothing · Fabric : Chiffon (With Lining) Length ~ 125cms"
            val query = "MINIMAL MAXI M - Polago Clothing Chiffon 125cms"
            val recognized = recognitionProvider.recognizeFromText(query)

            assertEquals("Polago Clothing", recognized.brand)
            assertEquals("Polago Minimal Maxi Dress M", recognized.title)
            assertEquals("Fashion", recognized.category)
            assertEquals("Chiffon (With Lining)", recognized.specifications["fabric"])
            assertEquals("M", recognized.specifications["size"])
            assertEquals(MatchType.EXACT, recognized.matchType)
            assertTrue(recognized.isDemoFixture)
        }
    }

    @Test
    fun testPolagoMerchantDiscovery_LowestPrice() {
        runBlocking {
            val listings = merchantEngine.searchAll("Polago Minimal Maxi")

            assertTrue("Expected at least 3 store listings", listings.size >= 3)
            val lowest = listings.minByOrNull { it.price }
            assertNotNull(lowest)
            assertEquals("Amazon India", lowest?.merchantName)
            assertEquals(2500.0, lowest?.price ?: 0.0, 0.01)

            // Verify prices are ascending
            for (i in 0 until listings.size - 1) {
                assertTrue(
                    "Expected sorted prices: ${listings[i].price} <= ${listings[i + 1].price}",
                    listings[i].price <= listings[i + 1].price
                )
            }
        }
    }

    @Test
    fun testLogitechProX2Superlight_UserScreenshot1() {
        runBlocking {
            // User Screenshot 1 from Amazon: "Logitech G PRO X2 Superlight Wireless Gaming Mouse"
            val query = "Logitech G PRO X2 Superlight"
            val recognized = recognitionProvider.recognizeFromText(query)

            assertEquals("Logitech", recognized.brand)
            assertEquals("Logitech G PRO X2 Superlight Wireless Mouse", recognized.title)
            assertEquals("Gaming", recognized.category)
            assertEquals("PRO X2", recognized.specifications["model"])

            val listings = merchantEngine.searchAll(recognized.title)
            val lowest = listings.minByOrNull { it.price }
            assertNotNull(lowest)
            assertEquals("Flipkart", lowest?.merchantName)
            assertEquals(15499.0, lowest?.price ?: 0.0, 0.01)

            // Check Amazon India price from screenshot (₹15,995)
            val amazonListing = listings.find { it.merchantName.contains("Amazon") }
            assertNotNull(amazonListing)
            assertEquals(15995.0, amazonListing?.price ?: 0.0, 0.01)
        }
    }

    @Test
    fun testLogitechMXMaster3S_Recognition() {
        runBlocking {
            val query = "Logitech MX Master 3S Wireless Mouse"
            val recognized = recognitionProvider.recognizeFromText(query)

            assertEquals("Logitech", recognized.brand)
            assertEquals("Logitech MX Master 3S Wireless Mouse", recognized.title)
            assertEquals("8000 DPI", recognized.specifications["sensor"])

            val listings = merchantEngine.searchAll(recognized.title)
            val lowest = listings.minByOrNull { it.price }
            assertEquals("Flipkart", lowest?.merchantName)
            assertEquals(8299.0, lowest?.price ?: 0.0, 0.01)
        }
    }

    @Test
    fun testSharingShortcutsConfiguration() {
        assertEquals("shortcut_add_to_visual_cart", SharingShortcutsManager.SHORTCUT_ID_ADD_TO_CART)
        assertEquals("com.paypal.visualcart.category.DIRECT_SHARE_TARGET", SharingShortcutsManager.CATEGORY_DIRECT_SHARE)
    }

    @Test
    fun testSharedTextWithProductUrlExtraction() {
        val sharedText = "Check out this dress: https://www.amazon.in/dp/B0POLAGOMAXI Polago Minimal Maxi M"
        val extractedUrl = SecurityUtils.extractWebUrl(sharedText)

        assertNotNull(extractedUrl)
        assertEquals("https://www.amazon.in/dp/B0POLAGOMAXI", extractedUrl)
    }

    @Test
    fun testGenericSharedImageFilenameDoesNotHallucinateProduct() {
        val provider = com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider()
        assertTrue(com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider.isGenericCaptureText("shared image.jpg"))
        assertTrue(com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider.isGenericCaptureText("IMG_20261111_085200.jpg"))
        org.junit.Assert.assertFalse(com.paypal.visualcart.feature.ai.DemoProductRecognitionProvider.isGenericCaptureText("PVC Plastic Pipes"))

        val recognized = kotlinx.coroutines.runBlocking {
            provider.recognizeFromImage(
                imageUri = "/data/user/0/com.paypal.visualcart/cache/share_captures/share_123.jpg",
                contextText = "shared image.jpg"
            )
        }
        assertEquals("Unidentified Product", recognized.title)
        assertEquals("Unidentified", recognized.category)
        org.junit.Assert.assertFalse(recognized.isLiveInference)
    }

    @Test
    fun testMerchantListingPartitionHasNoDuplicateMerchants() {
        val engine = com.paypal.visualcart.feature.merchant.MerchantDiscoveryEngine()
        val listings = kotlinx.coroutines.runBlocking {
            engine.searchAll("PVC Plastic Pipes")
        }
        val unique = listings.distinctBy { it.id }
        val exact = unique.filter { it.matchType == com.paypal.visualcart.domain.model.MatchType.EXACT }
            .ifEmpty { unique.take(3) }
        val exactIds = exact.mapTo(mutableSetOf()) { it.id }
        val similar = unique.filterNot { it.id in exactIds }

        val combined = exact + similar
        assertEquals(unique.size, combined.size)
        assertEquals(combined.size, combined.map { it.id }.toSet().size)

        // Ensure synthesized fallback listings no longer have ₹10,000+ fake price spreads
        val minPrice = combined.minOf { it.price }
        val maxPrice = combined.maxOf { it.price }
        assertTrue("Expected tight synthetic estimate range, got spread ${maxPrice - minPrice}", (maxPrice - minPrice) < 1000.0)
    }
}
