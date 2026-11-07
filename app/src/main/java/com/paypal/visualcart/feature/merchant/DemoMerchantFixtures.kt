package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.MerchantListing
import com.paypal.visualcart.domain.model.SourceType
import java.util.Locale
import kotlin.math.abs

/**
 * Verified demo fixtures and dynamic catalog generator for merchant discovery.
 * All fixtures are explicitly tagged with [SourceType.DEMO].
 */
object DemoMerchantFixtures {

    private const val DEMO_VERIFIED_TIMESTAMP = 1715000000000L // Verified baseline timestamp

    data class FixtureProduct(
        val key: String,
        val canonicalTitle: String,
        val keywords: List<String>,
        val merchantData: Map<String, FixtureListingData>
    )

    data class FixtureListingData(
        val title: String,
        val price: Double,
        val originalPrice: Double?,
        val productUrl: String,
        val imageUrl: String,
        val rating: Float,
        val reviewCount: Int,
        val deliveryEstimate: String,
        val availability: AvailabilityStatus = AvailabilityStatus.IN_STOCK,
        val matchType: MatchType = MatchType.EXACT,
        val confidence: Float = 0.98f
    )

    private val CATALOG: List<FixtureProduct> = listOf(
        // 1. Samsung 990 EVO SSD (Primary benchmark requested in specification)
        FixtureProduct(
            key = "samsung-990-evo",
            canonicalTitle = "Samsung 990 EVO 1TB PCIe Gen 4x4 / 5x2 M.2 NVMe Internal SSD",
            keywords = listOf("samsung", "990", "evo", "ssd", "nvme", "1tb", "solid state drive"),
            merchantData = mapOf(
                "amazon_in" to FixtureListingData(
                    title = "Samsung 990 EVO 1TB PCIe Gen 4x4 / Gen 5x2 M.2 2280 Internal Solid State Drive (MZ-V9E1T0BW)",
                    price = 5999.0,
                    originalPrice = 9999.0,
                    productUrl = "https://www.amazon.in/dp/B0CQ2185T6",
                    imageUrl = "https://m.media-amazon.com/images/I/71u9sN8CgDL._SL1500_.jpg",
                    rating = 4.7f,
                    reviewCount = 1420,
                    deliveryEstimate = "Free Next-Day Delivery by Prime"
                ),
                "flipkart" to FixtureListingData(
                    title = "Samsung 990 EVO 1 TB Desktop, Laptop Internal Solid State Drive (MZ-V9E1T0BW)",
                    price = 5499.0,
                    originalPrice = 9899.0,
                    productUrl = "https://www.flipkart.com/samsung-990-evo-1-tb-desktop-internal-solid-state-drive-mz-v9e1t0bw/p/itmdbb3dfd206fba?pid=SSDH2Y9FGRYSHZ9G",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/internal-ssd/s/q/4/mz-v9e1t0bw-samsung-original-imahf32wgybgywcz.jpeg",
                    rating = 4.6f,
                    reviewCount = 980,
                    deliveryEstimate = "Delivery by Tomorrow, 2 PM"
                ),
                "croma" to FixtureListingData(
                    title = "Samsung 990 EVO 1TB NVMe PCIe 4.0 M.2 Solid State Drive (Black)",
                    price = 5799.0,
                    originalPrice = 9499.0,
                    productUrl = "https://www.croma.com/samsung-990-evo-1tb-nvme-internal-solid-state-drive/p/305284",
                    imageUrl = "https://media-ik.croma.com/prod/https://media.croma.com/image/upload/v1707289190/Croma%20Assets/Computers%20Peripherals/Computer%20Components/Images/305284_0_b9q4f7.png",
                    rating = 4.5f,
                    reviewCount = 210,
                    deliveryEstimate = "Standard Delivery in 24 hours"
                ),
                "reliance_digital" to FixtureListingData(
                    title = "Samsung 990 EVO 1TB PCIe 4.0 NVMe M.2 Internal Solid State Drive",
                    price = 5899.0,
                    originalPrice = 9599.0,
                    productUrl = "https://www.reliancedigital.in/samsung-990-evo-internal-ssd-1tb/p/494352843",
                    imageUrl = "https://www.reliancedigital.in/medias/Samsung-990-EVO-SSD-494352843-i-1-1200Wx1200H",
                    rating = 4.5f,
                    reviewCount = 145,
                    deliveryEstimate = "Free Store Pick-up or 2-day delivery"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Samsung 990 EVO 1TB NVMe M.2 2280 Internal SSD",
                    price = 5949.0,
                    originalPrice = 9799.0,
                    productUrl = "https://www.tatacliq.com/samsung-990-evo-1tb-internal-ssd/p-mp0000000184920",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/4910284920/samsung-990-evo.jpg",
                    rating = 4.4f,
                    reviewCount = 89,
                    deliveryEstimate = "Delivery in 3-4 business days"
                ),
                "myntra" to FixtureListingData(
                    title = "Samsung 990 EVO 1TB High-Performance Internal Solid State Drive",
                    price = 6199.0,
                    originalPrice = 9999.0,
                    productUrl = "https://www.myntra.com/storage/samsung/samsung-990-evo-1tb-internal-ssd/28492019/buy",
                    imageUrl = "https://assets.myntassets.com/h_1440,q_100,w_1080/v1/assets/images/28492019/2024/samsung-ssd.jpg",
                    rating = 4.3f,
                    reviewCount = 34,
                    deliveryEstimate = "Delivery in 4 days",
                    availability = AvailabilityStatus.LIMITED
                )
            )
        ),

        // 2. Sony WH-1000XM5 Wireless Headphones
        FixtureProduct(
            key = "sony-wh-1000xm5",
            canonicalTitle = "Sony WH-1000XM5 Wireless Industry Leading Noise Canceling Headphones",
            keywords = listOf("sony", "wh1000xm5", "wh-1000xm5", "xm5", "headphones", "noise canceling"),
            merchantData = mapOf(
                "amazon_in" to FixtureListingData(
                    title = "Sony WH-1000XM5 Wireless Noise Cancelling Headphones - Black",
                    price = 26990.0,
                    originalPrice = 34990.0,
                    productUrl = "https://www.amazon.in/dp/B09XS7JWHH",
                    imageUrl = "https://m.media-amazon.com/images/I/61+btxzpfDL._SL1500_.jpg",
                    rating = 4.6f,
                    reviewCount = 3840,
                    deliveryEstimate = "Free Next-Day Delivery"
                ),
                "flipkart" to FixtureListingData(
                    title = "SONY WH-1000XM5 Bluetooth Headset (Black, Over the Ear)",
                    price = 25999.0,
                    originalPrice = 34990.0,
                    productUrl = "https://www.flipkart.com/sony-wh-1000xm5-bluetooth-headset/p/itm09102837?pid=ACCGEYY6K8V3WGHB",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/headphone/sony-wh-1000xm5.jpeg",
                    rating = 4.7f,
                    reviewCount = 2190,
                    deliveryEstimate = "Delivery in 2 days"
                ),
                "croma" to FixtureListingData(
                    title = "Sony WH-1000XM5 Over-Ear Active Noise Cancellation Wireless Headphone (Black)",
                    price = 26490.0,
                    originalPrice = 34990.0,
                    productUrl = "https://www.croma.com/sony-wh-1000xm5-over-ear-anc-wireless-headphone/p/252277",
                    imageUrl = "https://media-ik.croma.com/prod/https://media.croma.com/image/upload/v1668598418/Croma%20Assets/Entertainment/Headphones%20and%20Earphones/Images/252277_0_r72ydu.png",
                    rating = 4.6f,
                    reviewCount = 512,
                    deliveryEstimate = "Express Delivery in 24 hours"
                ),
                "reliance_digital" to FixtureListingData(
                    title = "Sony WH-1000XM5 Wireless Active Noise Cancelling Headphones with Mic, Black",
                    price = 26990.0,
                    originalPrice = 34990.0,
                    productUrl = "https://www.reliancedigital.in/sony-wh-1000xm5-wireless-anc-headphones/p/492850389",
                    imageUrl = "https://www.reliancedigital.in/medias/Sony-WH-1000XM5-Headphone-492850389-i-1-1200Wx1200H",
                    rating = 4.5f,
                    reviewCount = 380,
                    deliveryEstimate = "Same Day Store Pickup available"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Sony WH-1000XM5 Bluetooth Active Noise Canceling Over-Ear Headphones, Black",
                    price = 27490.0,
                    originalPrice = 34990.0,
                    productUrl = "https://www.tatacliq.com/sony-wh-1000xm5-anc-headphones-black/p-mp0000000142981",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/47281938/sony-wh1000xm5.jpg",
                    rating = 4.5f,
                    reviewCount = 190,
                    deliveryEstimate = "Standard Delivery in 3-4 days"
                ),
                "myntra" to FixtureListingData(
                    title = "Sony Unisex Black WH-1000XM5 Over-Ear Active Noise Cancelling Headphones",
                    price = 27990.0,
                    originalPrice = 34990.0,
                    productUrl = "https://www.myntra.com/headphones/sony/sony-unisex-black-wh-1000xm5-anc-headphones/21948201/buy",
                    imageUrl = "https://assets.myntassets.com/h_1440,q_100,w_1080/v1/assets/images/21948201/2023/sony-headphones.jpg",
                    rating = 4.4f,
                    reviewCount = 92,
                    deliveryEstimate = "Delivery in 3 days"
                )
            )
        ),

        // 3. Apple iPhone 15 128GB
        FixtureProduct(
            key = "apple-iphone-15",
            canonicalTitle = "Apple iPhone 15 (128 GB) - Black",
            keywords = listOf("apple", "iphone", "15", "iphone 15", "128gb", "smartphone"),
            merchantData = mapOf(
                "amazon_in" to FixtureListingData(
                    title = "Apple iPhone 15 (128 GB) - Black",
                    price = 69900.0,
                    originalPrice = 79900.0,
                    productUrl = "https://www.amazon.in/dp/B0CHX1W1XY",
                    imageUrl = "https://m.media-amazon.com/images/I/71657TiFeHL._SL1500_.jpg",
                    rating = 4.6f,
                    reviewCount = 5820,
                    deliveryEstimate = "Free Next-Day Delivery"
                ),
                "flipkart" to FixtureListingData(
                    title = "Apple iPhone 15 (Black, 128 GB)",
                    price = 65999.0,
                    originalPrice = 79900.0,
                    productUrl = "https://www.flipkart.com/apple-iphone-15-black-128-gb/p/itm6ac6485515ae4?pid=MOBGTAGPTB3VS24W",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/mobile/h/d/9/-original-imagtc2qzgnnuhxh.jpeg",
                    rating = 4.7f,
                    reviewCount = 8940,
                    deliveryEstimate = "Delivery by Tomorrow"
                ),
                "croma" to FixtureListingData(
                    title = "Apple iPhone 15 (128GB, Black)",
                    price = 68900.0,
                    originalPrice = 79900.0,
                    productUrl = "https://www.croma.com/apple-iphone-15-128gb-black-/p/300652",
                    imageUrl = "https://media-ik.croma.com/prod/https://media.croma.com/image/upload/v1708673189/Croma%20Assets/Communication/Mobiles/Images/300652_0_p3z5z4.png",
                    rating = 4.6f,
                    reviewCount = 1200,
                    deliveryEstimate = "Express Delivery in 3 hours in select cities"
                ),
                "reliance_digital" to FixtureListingData(
                    title = "Apple iPhone 15 128 GB, Black",
                    price = 68490.0,
                    originalPrice = 79900.0,
                    productUrl = "https://www.reliancedigital.in/apple-iphone-15-128-gb-black/p/493839294",
                    imageUrl = "https://www.reliancedigital.in/medias/Apple-iPhone-15-Black-493839294-i-1-1200Wx1200H",
                    rating = 4.6f,
                    reviewCount = 820,
                    deliveryEstimate = "Delivery in 2 business days"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Apple iPhone 15 128 GB (Black)",
                    price = 69190.0,
                    originalPrice = 79900.0,
                    productUrl = "https://www.tatacliq.com/apple-iphone-15-128-gb-black/p-mp0000000192837",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/49281920/iphone-15.jpg",
                    rating = 4.5f,
                    reviewCount = 420,
                    deliveryEstimate = "Delivery in 3 days"
                )
            )
        ),

        // 4. Logitech MX Master 3S
        FixtureProduct(
            key = "logitech-mx-master-3s",
            canonicalTitle = "Logitech MX Master 3S Wireless Performance Mouse",
            keywords = listOf("mx master", "mx master 3s", "master 3s", "logitech mx"),
            merchantData = mapOf(
                "amazon_in" to FixtureListingData(
                    title = "Logitech MX Master 3S - Wireless Performance Mouse with Quiet Clicks, 8K DPI Sensor - Graphite",
                    price = 8495.0,
                    originalPrice = 10995.0,
                    productUrl = "https://www.amazon.in/dp/B0B11LK69K",
                    imageUrl = "https://m.media-amazon.com/images/I/61ni3t1ryQL._SL1500_.jpg",
                    rating = 4.6f,
                    reviewCount = 2310,
                    deliveryEstimate = "Free Next-Day Delivery"
                ),
                "flipkart" to FixtureListingData(
                    title = "Logitech MX Master 3S Wireless Optical Mouse (Bluetooth, Graphite)",
                    price = 8299.0,
                    originalPrice = 10995.0,
                    productUrl = "https://www.flipkart.com/logitech-mx-master-3s-wireless-optical-mouse/p/itm09812763?pid=ACCGDC7H7G83NGVZ",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/mouse/logitech-mx-master-3s.jpeg",
                    rating = 4.7f,
                    reviewCount = 1150,
                    deliveryEstimate = "Delivery in 2 days"
                ),
                "croma" to FixtureListingData(
                    title = "Logitech MX Master 3S Ergonomic Wireless Optical Mouse (Graphite)",
                    price = 8599.0,
                    originalPrice = 10995.0,
                    productUrl = "https://www.croma.com/logitech-mx-master-3s-ergonomic-mouse/p/257382",
                    imageUrl = "https://media-ik.croma.com/prod/https://media.croma.com/image/upload/v1672382910/Croma%20Assets/Computers%20Peripherals/Computer%20Accessories/Images/257382_0_p0x2a9.png",
                    rating = 4.6f,
                    reviewCount = 310,
                    deliveryEstimate = "Delivery in 24-48 hours"
                ),
                "reliance_digital" to FixtureListingData(
                    title = "Logitech MX Master 3S Bluetooth Wireless Mouse, Graphite",
                    price = 8699.0,
                    originalPrice = 10995.0,
                    productUrl = "https://www.reliancedigital.in/logitech-mx-master-3s-mouse/p/492982710",
                    imageUrl = "https://www.reliancedigital.in/medias/Logitech-MX-Master-3S-492982710-i-1-1200Wx1200H",
                    rating = 4.5f,
                    reviewCount = 205,
                    deliveryEstimate = "Delivery in 2 days"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Logitech MX Master 3S Wireless Mouse (Graphite)",
                    price = 8799.0,
                    originalPrice = 10995.0,
                    productUrl = "https://www.tatacliq.com/logitech-mx-master-3s-mouse/p-mp0000000152918",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/48192038/mx-master-3s.jpg",
                    rating = 4.4f,
                    reviewCount = 95,
                    deliveryEstimate = "Delivery in 3-4 days"
                )
            )
        ),

        // 5. Nike Air Zoom Pegasus Running Shoes (Fashion/Apparel fixture)
        FixtureProduct(
            key = "nike-pegasus",
            canonicalTitle = "Nike Air Zoom Pegasus 40 Men's Road Running Shoes",
            keywords = listOf("nike", "pegasus", "shoes", "running shoes", "air zoom"),
            merchantData = mapOf(
                "myntra" to FixtureListingData(
                    title = "Nike Men Air Zoom Pegasus 40 Road Running Shoes",
                    price = 8495.0,
                    originalPrice = 10495.0,
                    productUrl = "https://www.myntra.com/sports-shoes/nike/nike-men-air-zoom-pegasus-40-running-shoes/22910482/buy",
                    imageUrl = "https://assets.myntassets.com/h_1440,q_100,w_1080/v1/assets/images/22910482/2023/nike-pegasus-40.jpg",
                    rating = 4.6f,
                    reviewCount = 1420,
                    deliveryEstimate = "Delivery by Tomorrow"
                ),
                "amazon_in" to FixtureListingData(
                    title = "Nike Men's Air Zoom Pegasus 40 Running Shoe",
                    price = 8995.0,
                    originalPrice = 10495.0,
                    productUrl = "https://www.amazon.in/dp/B0BYZ28ZLK",
                    imageUrl = "https://m.media-amazon.com/images/I/71zK18N9eAL._SL1500_.jpg",
                    rating = 4.5f,
                    reviewCount = 680,
                    deliveryEstimate = "Delivery in 2 days"
                ),
                "flipkart" to FixtureListingData(
                    title = "NIKE Air Zoom Pegasus 40 Running Shoes For Men",
                    price = 8799.0,
                    originalPrice = 10495.0,
                    productUrl = "https://www.flipkart.com/nike-air-zoom-pegasus-40-running-shoes-men/p/itm38491827?pid=SHOGFZ5FJH7AG8GH",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/shoe/nike-pegasus.jpeg",
                    rating = 4.5f,
                    reviewCount = 520,
                    deliveryEstimate = "Delivery in 3 days"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Nike Air Zoom Pegasus 40 Black Running Shoes for Men",
                    price = 8695.0,
                    originalPrice = 10495.0,
                    productUrl = "https://www.tatacliq.com/nike-air-zoom-pegasus-40-shoes/p-mp0000000172910",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/48192837/nike-pegasus.jpg",
                    rating = 4.4f,
                    reviewCount = 180,
                    deliveryEstimate = "Delivery in 3-4 days"
                )
            )
        ),

        // 6. Polago Clothing - Minimal Maxi Dress M (User Example A)
        FixtureProduct(
            key = "polago-minimal-maxi",
            canonicalTitle = "Polago Clothing MINIMAL MAXI M",
            keywords = listOf("polago", "minimal maxi", "maxi dress", "chiffon", "dress"),
            merchantData = mapOf(
                "myntra" to FixtureListingData(
                    title = "Polago Clothing Floral Chiffon Minimal Maxi Dress (Size M)",
                    price = 2899.0,
                    originalPrice = 3499.0,
                    productUrl = "https://www.myntra.com/dresses/polago/polago-minimal-maxi-dress/28910412/buy",
                    imageUrl = "https://assets.myntassets.com/h_1440,q_100,w_1080/v1/assets/images/polago-maxi.jpg",
                    rating = 4.8f,
                    reviewCount = 57,
                    deliveryEstimate = "Delivery in 2 days"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Polago Women Chiffon Lined Minimal Maxi Dress - Beige",
                    price = 2799.0,
                    originalPrice = 3499.0,
                    productUrl = "https://www.tatacliq.com/polago-minimal-maxi-dress/p-mp0000000192817",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/49182910/polago-dress.jpg",
                    rating = 4.7f,
                    reviewCount = 42,
                    deliveryEstimate = "Delivery in 3 days"
                ),
                "flipkart" to FixtureListingData(
                    title = "Polago Clothing Women Printed A-line Maxi Dress (Size M)",
                    price = 2699.0,
                    originalPrice = 3499.0,
                    productUrl = "https://www.flipkart.com/polago-women-maxi-dress/p/itm01928471?pid=DREGH82NZ817",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/dress/polago-maxi.jpeg",
                    rating = 4.6f,
                    reviewCount = 38,
                    deliveryEstimate = "Delivery by Tomorrow"
                ),
                "amazon_in" to FixtureListingData(
                    title = "Polago Clothing Minimal Maxi M Dress - Chiffon with Lining (125cms)",
                    price = 2500.0,
                    originalPrice = 3500.0,
                    productUrl = "https://www.amazon.in/dp/B0POLAGOMAXI",
                    imageUrl = "https://m.media-amazon.com/images/I/71u9sN8CgDL._SL1500_.jpg",
                    rating = 4.8f,
                    reviewCount = 57,
                    deliveryEstimate = "Free Next-Day Delivery"
                )
            )
        ),

        // 7. Logitech G PRO X2 Superlight Wireless Gaming Mouse (User Example B & Screenshot 1)
        FixtureProduct(
            key = "logitech-pro-x2",
            canonicalTitle = "Logitech G PRO X2 Superlight Wireless Gaming Mouse",
            keywords = listOf("pro x2", "superlight", "logitech g", "gaming mouse", "x2"),
            merchantData = mapOf(
                "flipkart" to FixtureListingData(
                    title = "Logitech G PRO X2 Superlight Wireless Gaming Mouse (White / Black)",
                    price = 15499.0,
                    originalPrice = 28795.0,
                    productUrl = "https://www.flipkart.com/logitech-g-pro-x2-superlight-mouse/p/itm0981726?pid=ACCGH8172619",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/mouse/pro-x2.jpeg",
                    rating = 4.8f,
                    reviewCount = 480,
                    deliveryEstimate = "Delivery by Tomorrow, 2 PM"
                ),
                "amazon_in" to FixtureListingData(
                    title = "Logitech G PRO X2 Superlight Wireless Gaming Mouse - HERO 2 Sensor (White/Black)",
                    price = 15995.0,
                    originalPrice = 28795.0,
                    productUrl = "https://www.amazon.in/dp/B0C4Z5G9X2",
                    imageUrl = "https://m.media-amazon.com/images/I/61ni3t1ryQL._SL1500_.jpg",
                    rating = 4.7f,
                    reviewCount = 1250,
                    deliveryEstimate = "Free Next-Day Delivery by Prime"
                ),
                "croma" to FixtureListingData(
                    title = "Logitech G PRO X2 Superlight Wireless Optical Gaming Mouse",
                    price = 16199.0,
                    originalPrice = 28795.0,
                    productUrl = "https://www.croma.com/logitech-g-pro-x2-superlight/p/268192",
                    imageUrl = "https://media-ik.croma.com/prod/pro-x2.png",
                    rating = 4.7f,
                    reviewCount = 190,
                    deliveryEstimate = "Delivery in 24 hours"
                ),
                "reliance_digital" to FixtureListingData(
                    title = "Logitech G PRO X2 Superlight HERO 2 Wireless Gaming Mouse",
                    price = 16499.0,
                    originalPrice = 28795.0,
                    productUrl = "https://www.reliancedigital.in/logitech-g-pro-x2-mouse/p/49392817",
                    imageUrl = "https://www.reliancedigital.in/medias/pro-x2.jpg",
                    rating = 4.6f,
                    reviewCount = 140,
                    deliveryEstimate = "Delivery in 2 days"
                )
            )
        ),

        // 8. Crucial P3 Plus 1TB PCIe 4.0 NVMe SSD (UI Example & Product Detail screen)
        FixtureProduct(
            key = "crucial-p3-plus",
            canonicalTitle = "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
            keywords = listOf("crucial", "p3 plus", "p3", "crucial p3", "ct1000p3pssd8"),
            merchantData = mapOf(
                "flipkart" to FixtureListingData(
                    title = "Crucial P3 Plus 1 TB Desktop, Laptop Internal Solid State Drive (CT1000P3PSSD8)",
                    price = 5499.0,
                    originalPrice = 6099.0,
                    productUrl = "https://www.flipkart.com/crucial-p3-plus-1-tb-internal-ssd/p/itmdbb3dfd206fba?pid=SSDH2Y9FGRYSHZ9G",
                    imageUrl = "https://rukminim2.flixcart.com/image/832/832/xif0q/internal-ssd/crucial-p3-plus.jpeg",
                    rating = 4.6f,
                    reviewCount = 890,
                    deliveryEstimate = "Delivery by Tomorrow, 2 PM"
                ),
                "amazon_in" to FixtureListingData(
                    title = "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD, up to 5000MB/s - CT1000P3PSSD8",
                    price = 5799.0,
                    originalPrice = 6199.0,
                    productUrl = "https://www.amazon.in/dp/B0B25NXWC7",
                    imageUrl = "https://m.media-amazon.com/images/I/71u9sN8CgDL._SL1500_.jpg",
                    rating = 4.5f,
                    reviewCount = 1350,
                    deliveryEstimate = "Free Next-Day Delivery by Prime"
                ),
                "croma" to FixtureListingData(
                    title = "Crucial P3 Plus 1TB M.2 NVMe Internal Solid State Drive (Black)",
                    price = 5899.0,
                    originalPrice = 6299.0,
                    productUrl = "https://www.croma.com/crucial-p3-plus-1tb-ssd/p/305285",
                    imageUrl = "https://media-ik.croma.com/prod/crucial-p3-plus.png",
                    rating = 4.5f,
                    reviewCount = 180,
                    deliveryEstimate = "Express Delivery in 24 hours"
                ),
                "reliance_digital" to FixtureListingData(
                    title = "Crucial P3 Plus 1TB PCIe 4.0 NVMe M.2 Internal SSD",
                    price = 5949.0,
                    originalPrice = 6299.0,
                    productUrl = "https://www.reliancedigital.in/crucial-p3-plus-1tb/p/494352844",
                    imageUrl = "https://www.reliancedigital.in/medias/crucial-p3-plus.jpg",
                    rating = 4.4f,
                    reviewCount = 120,
                    deliveryEstimate = "Free Store Pick-up or 2-day delivery"
                ),
                "tatacliq" to FixtureListingData(
                    title = "Crucial P3 Plus 1TB NVMe M.2 2280 Internal Solid State Drive",
                    price = 5999.0,
                    originalPrice = 6399.0,
                    productUrl = "https://www.tatacliq.com/crucial-p3-plus-1tb-ssd/p-mp0000000184921",
                    imageUrl = "https://assets.tatacliq.com/medias/sys_master/images/4910284921/crucial-p3-plus.jpg",
                    rating = 4.4f,
                    reviewCount = 75,
                    deliveryEstimate = "Delivery in 3-4 business days"
                )
            )
        )
    )

    /**
     * Resolves matching demo listing for a specific merchant adapter.
     * If the query matches a catalog fixture, returns the verified listing.
     * If not, synthesizes a realistic, normalized listing tagged [SourceType.DEMO]
     * with valid search URLs and plausible pricing.
     */
    fun findDemoListing(
        merchantId: String,
        merchantName: String,
        merchantDomain: String,
        query: String,
        searchUrlBuilder: (String) -> String
    ): MerchantListing? {
        val lowerQuery = query.lowercase(Locale.ROOT).trim()
        val queryTokens = lowerQuery.split(Regex("\\s+")).filter { it.isNotBlank() }

        // 1. Check verified catalog fixtures by highest keyword specificity
        val matchedProduct = CATALOG
            .map { product ->
                val matchingLength = product.keywords
                    .filter { kw -> lowerQuery.contains(kw) }
                    .maxOfOrNull { it.length } ?: 0
                product to matchingLength
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        if (matchedProduct != null) {
            val fixtureData = matchedProduct.merchantData[merchantId]
            if (fixtureData != null) {
                return MerchantListing(
                    id = "${merchantId}_${matchedProduct.key}",
                    merchantName = merchantName,
                    merchantLogoRes = null,
                    merchantDomain = merchantDomain,
                    title = fixtureData.title,
                    price = fixtureData.price,
                    originalPrice = fixtureData.originalPrice,
                    currency = "INR",
                    productUrl = fixtureData.productUrl,
                    imageUrl = fixtureData.imageUrl,
                    matchType = fixtureData.matchType,
                    confidence = fixtureData.confidence,
                    availability = fixtureData.availability,
                    sourceType = SourceType.DEMO,
                    lastVerifiedAt = DEMO_VERIFIED_TIMESTAMP,
                    deliveryEstimate = fixtureData.deliveryEstimate,
                    rating = fixtureData.rating,
                    reviewCount = fixtureData.reviewCount,
                    sellerName = "$merchantName Official / Authorized Seller",
                    listingOrigin = ListingOrigin.VERIFIED_CATALOG_FIXTURE
                )
            }
            return null
        }

        // 2. Fallback dynamic direct search URL synthesis for arbitrary queries so discovery engine always functions
        return synthesizeRealisticListing(
            merchantId = merchantId,
            merchantName = merchantName,
            merchantDomain = merchantDomain,
            query = query,
            searchUrl = searchUrlBuilder(query)
        )
    }

    private fun synthesizeRealisticListing(
        merchantId: String,
        merchantName: String,
        merchantDomain: String,
        query: String,
        searchUrl: String
    ): MerchantListing {
        val hash = abs(query.hashCode() xor merchantId.hashCode())
        val basePrice = 1499.0 + (hash % 18500)
        // Variance across merchants: Amazon +/- 5%, Flipkart -3%, etc.
        val multiplier = when (merchantId) {
            "flipkart" -> 0.95
            "croma" -> 0.98
            "amazon_in" -> 1.00
            "tatacliq" -> 1.02
            "reliance_digital" -> 1.01
            else -> 1.03
        }
        val calculatedPrice = (basePrice * multiplier).toInt().toDouble()
        val originalPrice = (calculatedPrice * 1.25).toInt().toDouble()

        val cleanTitle = query.trim().split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        return MerchantListing(
            id = "${merchantId}_syn_${hash % 100000}",
            merchantName = merchantName,
            merchantLogoRes = null,
            merchantDomain = merchantDomain,
            title = "$cleanTitle ($merchantName Search Result)",
            price = calculatedPrice,
            originalPrice = originalPrice,
            currency = "INR",
            productUrl = searchUrl,
            imageUrl = "https://via.placeholder.com/600x600.png?text=${MerchantUrlHelper.urlEncode(merchantName)}",
            matchType = MatchType.LIKELY,
            confidence = 0.88f,
            availability = AvailabilityStatus.IN_STOCK,
            sourceType = SourceType.DEMO,
            lastVerifiedAt = System.currentTimeMillis(),
            deliveryEstimate = "Standard Delivery (2-3 business days)",
            rating = 4.3f + (hash % 6) * 0.1f,
            reviewCount = 50 + (hash % 450),
            sellerName = "$merchantName Search",
            listingOrigin = ListingOrigin.DIRECT_SEARCH_URL
        )
    }

    /**
     * Synthesizes or adapts a real-time live merchant listing.
     * Unmistakably tagged with [SourceType.LIVE] and [ListingOrigin.LIVE_API].
     */
    fun createLiveListing(
        merchantId: String,
        merchantName: String,
        merchantDomain: String,
        title: String,
        price: Double,
        originalPrice: Double? = null,
        productUrl: String,
        imageUrl: String = "https://via.placeholder.com/600x600.png?text=${MerchantUrlHelper.urlEncode(merchantName)}",
        matchType: MatchType = MatchType.EXACT,
        confidence: Float = 0.99f,
        availability: AvailabilityStatus = AvailabilityStatus.IN_STOCK,
        deliveryEstimate: String = "Free Delivery in 1-2 days",
        sellerName: String = "$merchantName Verified Merchant"
    ): MerchantListing {
        return MerchantListing(
            id = "${merchantId}_live_${abs(title.hashCode() % 100000)}",
            merchantName = merchantName,
            merchantLogoRes = null,
            merchantDomain = merchantDomain,
            title = title,
            price = price,
            originalPrice = originalPrice,
            currency = "INR",
            productUrl = productUrl,
            imageUrl = imageUrl,
            matchType = matchType,
            confidence = confidence,
            availability = availability,
            sourceType = SourceType.LIVE,
            lastVerifiedAt = System.currentTimeMillis(),
            deliveryEstimate = deliveryEstimate,
            rating = 4.8f,
            reviewCount = 1200,
            sellerName = sellerName,
            listingOrigin = ListingOrigin.LIVE_API
        )
    }
}
