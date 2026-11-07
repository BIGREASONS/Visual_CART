package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchResult
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.RecognizedProduct

/**
 * Demo and local AI product recognition provider offering realistic high-fidelity
 * product fixtures for hackathons and offline evaluations, with fallback to on-device parsing.
 */
class DemoProductRecognitionProvider(
    private val localParser: LocalProductParser = LocalProductParser,
    private val matchingEngine: ProductMatchingEngine = ProductMatchingEngine
) : ProductUnderstandingProvider {

    companion object {
        const val PROVIDER_NAME = "PayPal Demo & On-Device AI Provider"

        val FIXTURE_SAMSUNG_SSD = RecognizedProduct(
            id = "demo-fix-samsung-990-evo",
            title = "Samsung 990 EVO 1TB NVMe M.2 SSD",
            brand = "Samsung",
            category = "Computers",
            specifications = mapOf(
                "capacity" to "1TB",
                "interface" to "NVMe PCIe 4.0/5.0",
                "form_factor" to "M.2 2280"
            ),
            confidence = 0.98f,
            imageUrl = "https://images.samsung.com/is/image/samsung/p6pim/levant/mz-v9e1t0bw/gallery/levant-990-evo-nvme-m2-ssd-mz-v9e1t0bw-539827365",
            rawTextSnippet = "Samsung 990 EVO 1TB PCIe 4.0/5.0 NVMe M.2 2280 Internal SSD",
            matchType = MatchType.EXACT,
            matchReason = "Likely same product: matching brand, model, capacity and interface.",
            isDemoFixture = true
        )

        val FIXTURE_SONY_HEADPHONES = RecognizedProduct(
            id = "demo-fix-sony-wh1000xm5",
            title = "Sony WH-1000XM5 Wireless Noise Cancelling Headphones",
            brand = "Sony",
            category = "Audio",
            specifications = mapOf(
                "type" to "Over-Ear",
                "battery" to "30 hours",
                "connectivity" to "Bluetooth 5.2",
                "color" to "Silver"
            ),
            confidence = 0.97f,
            imageUrl = "https://m.media-amazon.com/images/I/61+El0CuG-L._AC_SL1500_.jpg",
            rawTextSnippet = "Sony WH-1000XM5 Premium Noise Canceling Wireless Over-Ear Headphones Silver",
            matchType = MatchType.EXACT,
            matchReason = "Exact match: matching brand 'Sony', model 'WH-1000XM5', audio type Over-Ear and Bluetooth 5.2.",
            isDemoFixture = true
        )

        val FIXTURE_NIKE_SHOES = RecognizedProduct(
            id = "demo-fix-nike-airmax270",
            title = "Nike Air Max 270 Running Shoes",
            brand = "Nike",
            category = "Footwear",
            specifications = mapOf(
                "size" to "UK 9",
                "color" to "Triple Black",
                "gender" to "Men"
            ),
            confidence = 0.96f,
            imageUrl = "https://static.nike.com/a/images/t_PDP_1280_v1/f_auto,q_auto:eco/awjogtdnqxniqqgahunq/air-max-270-shoes-V4DGTQ.png",
            rawTextSnippet = "Nike Men's Air Max 270 Running Shoes - Triple Black - UK 9",
            matchType = MatchType.EXACT,
            matchReason = "Exact match: matching brand 'Nike', model 'Air Max 270', size UK 9 and Triple Black.",
            isDemoFixture = true
        )

        val FIXTURE_APPLE_MACBOOK = RecognizedProduct(
            id = "demo-fix-apple-macbook-m3",
            title = "Apple MacBook Air 13-inch M3",
            brand = "Apple",
            category = "Computers",
            specifications = mapOf(
                "chip" to "Apple M3",
                "memory" to "16GB",
                "storage" to "512GB SSD",
                "color" to "Space Gray",
                "display" to "13-inch"
            ),
            confidence = 0.99f,
            imageUrl = "https://store.storeimages.cdn-apple.com/4982/as-images.apple.com/is/macbook-air-space-gray-select-202402",
            rawTextSnippet = "Apple 2024 MacBook Air 13-inch M3 chip, 16GB Unified Memory, 512GB SSD - Space Gray",
            matchType = MatchType.EXACT,
            matchReason = "Exact match: matching brand 'Apple', model 'MacBook Air 13-inch', M3 chip and 16GB RAM.",
            isDemoFixture = true
        )

        val FIXTURE_LOGITECH_MOUSE = RecognizedProduct(
            id = "demo-fix-logitech-mxmaster3s",
            title = "Logitech MX Master 3S Wireless Mouse",
            brand = "Logitech",
            category = "Electronics",
            specifications = mapOf(
                "sensor" to "8000 DPI",
                "connectivity" to "Bluetooth/Bolt",
                "color" to "Graphite"
            ),
            confidence = 0.95f,
            imageUrl = "https://resource.logitech.com/content/dam/logitech/en/products/mice/mx-master-3s/gallery/mx-master-3s-mouse-top-view-graphite.png",
            rawTextSnippet = "Logitech MX Master 3S Performance Wireless Mouse with 8K DPI Sensor - Graphite",
            matchType = MatchType.EXACT,
            matchReason = "Exact match: matching brand 'Logitech', model 'MX Master 3S', 8000 DPI sensor and Bluetooth/Bolt.",
            isDemoFixture = true
        )

        val FIXTURE_POLAGO_DRESS = RecognizedProduct(
            id = "demo-fix-polago-minimal-maxi",
            title = "Polago Minimal Maxi Dress M",
            brand = "Polago Clothing",
            category = "Fashion",
            specifications = mapOf(
                "fabric" to "Chiffon (With Lining)",
                "length" to "125 cms",
                "size" to "M",
                "style" to "Maxi Dress"
            ),
            confidence = 0.97f,
            imageUrl = "https://example.com/polago_minimal_maxi.jpg",
            rawTextSnippet = "MINIMAL MAXI M - ₹2,500.00 · Polago Clothing · Fabric : Chiffon (With Lining) Length ~ 125cms",
            matchType = MatchType.EXACT,
            matchReason = "Exact match: Polago Clothing Minimal Maxi M, Chiffon with Lining, 125 cms.",
            isDemoFixture = true
        )

        val FIXTURE_LOGITECH_PRO_X2 = RecognizedProduct(
            id = "demo-fix-logitech-pro-x2",
            title = "Logitech G PRO X2 Superlight Wireless Mouse",
            brand = "Logitech",
            category = "Gaming",
            specifications = mapOf(
                "model" to "PRO X2",
                "sensor" to "HERO 2 32K DPI",
                "connectivity" to "LIGHTSPEED Wireless",
                "color" to "White / Black"
            ),
            confidence = 0.98f,
            imageUrl = "https://m.media-amazon.com/images/I/61+El0CuG-L._AC_SL1500_.jpg",
            rawTextSnippet = "Logitech G PRO X2 Superlight Wireless Gaming Mouse White/Black",
            matchType = MatchType.EXACT,
            matchReason = "Exact match: Logitech G PRO X2 Superlight, White/Black, LIGHTSPEED Wireless.",
            isDemoFixture = true
        )

        val ALL_FIXTURES = listOf(
            FIXTURE_SAMSUNG_SSD,
            FIXTURE_SONY_HEADPHONES,
            FIXTURE_NIKE_SHOES,
            FIXTURE_APPLE_MACBOOK,
            FIXTURE_LOGITECH_MOUSE,
            FIXTURE_POLAGO_DRESS,
            FIXTURE_LOGITECH_PRO_X2
        )
    }

    override fun getProviderName(): String = PROVIDER_NAME

    /**
     * Returns the full list of realistic hackathon demo fixtures.
     */
    fun getDemoFixtures(): List<RecognizedProduct> = ALL_FIXTURES

    override suspend fun recognizeFromText(text: String): RecognizedProduct {
        val matchedFixture = findDemoFixture(text)
        if (matchedFixture != null) {
            return matchedFixture
        }
        return localParser.parse(text)
    }

    override suspend fun recognizeFromImage(imageUri: String, contextText: String?): RecognizedProduct {
        val combinedQuery = "${imageUri.lowercase()} ${contextText ?: ""}".trim()
        val matchedFixture = findDemoFixture(combinedQuery)
        if (matchedFixture != null) {
            return matchedFixture.copy(
                imageUrl = if (imageUri.startsWith("http") || imageUri.startsWith("content") || imageUri.startsWith("file")) {
                    imageUri
                } else {
                    matchedFixture.imageUrl
                }
            )
        }

        return localParser.parse(
            rawText = contextText ?: imageUri.substringAfterLast("/").substringBeforeLast("."),
            imageUrl = imageUri
        )
    }

    override suspend fun compareProducts(
        candidate: RecognizedProduct,
        targetTitle: String,
        targetBrand: String?,
        targetSpecs: Map<String, String>
    ): MatchResult {
        return matchingEngine.compare(
            candidate = candidate,
            targetTitle = targetTitle,
            targetBrand = targetBrand,
            targetSpecs = targetSpecs
        )
    }

    /**
     * Matches text query against the 5 primary demo fixtures.
     */
    fun findDemoFixture(query: String): RecognizedProduct? {
        val q = query.lowercase()

        // Samsung 990 EVO SSD
        if (q.contains("990 evo") || (q.contains("samsung") && (q.contains("ssd") || q.contains("990")))) {
            return FIXTURE_SAMSUNG_SSD
        }

        // Sony WH-1000XM5
        if (q.contains("1000xm5") || q.contains("wh-1000xm") || (q.contains("sony") && (q.contains("headphone") || q.contains("xm5")))) {
            return FIXTURE_SONY_HEADPHONES
        }

        // Nike Air Max 270
        if (q.contains("air max 270") || (q.contains("nike") && (q.contains("air max") || q.contains("shoe")))) {
            return FIXTURE_NIKE_SHOES
        }

        // Apple MacBook Air M3
        if (q.contains("macbook") || (q.contains("apple") && q.contains("m3")) || q.contains("air 13-inch m3")) {
            return FIXTURE_APPLE_MACBOOK
        }

        // Logitech MX Master 3S
        if (q.contains("mx master") || q.contains("master 3s") || (q.contains("logitech") && q.contains("mx"))) {
            return FIXTURE_LOGITECH_MOUSE
        }

        // Logitech PRO X2 Superlight (User Screenshot 1)
        if (q.contains("pro x2") || q.contains("superlight") || (q.contains("logitech") && (q.contains("mouse") || q.contains("gaming")))) {
            return FIXTURE_LOGITECH_PRO_X2
        }

        // Polago Clothing Minimal Maxi (User Screenshot 2)
        if (q.contains("polago") || q.contains("minimal maxi") || q.contains("maxi dress") || q.contains("dress") || q.contains("chiffon")) {
            return FIXTURE_POLAGO_DRESS
        }

        return null
    }
}
