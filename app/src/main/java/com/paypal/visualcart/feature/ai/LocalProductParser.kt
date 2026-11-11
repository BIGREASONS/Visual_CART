package com.paypal.visualcart.feature.ai

import com.paypal.visualcart.domain.model.MatchType
import com.paypal.visualcart.domain.model.RecognitionSource
import com.paypal.visualcart.domain.model.RecognitionStatus
import com.paypal.visualcart.domain.model.RecognizedProduct
import com.paypal.visualcart.feature.merchant.SearchQueryBuilder
import java.util.UUID
import java.util.regex.Pattern

/**
 * On-device local parser that extracts structured product information, specifications,
 * brand, category, and canonical title from raw OCR or scraped page text without requiring
 * external network calls.
 */
object LocalProductParser {

    private val BRAND_PATTERNS = mapOf(
        "Samsung" to listOf("\\bsamsung\\b"),
        "Sony" to listOf("\\bsony\\b"),
        "Apple" to listOf("\\bapple\\b", "\\bmacbook\\b", "\\biphone\\b", "\\bipad\\b"),
        "Nike" to listOf("\\bnike\\b"),
        "Adidas" to listOf("\\badidas\\b"),
        "Dell" to listOf("\\bdell\\b", "\\balienware\\b"),
        "HP" to listOf("\\bhp\\b", "\\bhewlett[- ]packard\\b", "\\bomen\\b", "\\bpavilion\\b"),
        "Lenovo" to listOf("\\blenovo\\b", "\\bthinkpad\\b", "\\blegion\\b"),
        "Bose" to listOf("\\bbose\\b", "\\bquietcomfort\\b"),
        "Asus" to listOf("\\basus\\b", "\\brog\\b", "\\bzenbook\\b"),
        "Logitech" to listOf("\\blogitech\\b", "\\blogi\\b"),
        "Puma" to listOf("\\bpuma\\b"),
        "Google" to listOf("\\bgoogle\\b", "\\bpixel\\b"),
        "Microsoft" to listOf("\\bmicrosoft\\b", "\\bsurface\\b"),
        "Anker" to listOf("\\banker\\b", "\\bsoundcore\\b"),
        "SanDisk" to listOf("\\bsandisk\\b"),
        "Western Digital" to listOf("\\bwestern digital\\b", "\\bwd\\b", "\\bwd_black\\b"),
        "Crucial" to listOf("\\bcrucial\\b"),
        "Kingston" to listOf("\\bkingston\\b"),
        "OnePlus" to listOf("\\boneplus\\b"),
        "LG" to listOf("\\blg\\b")
    )

    private val CLUTTER_PATTERNS = listOf(
        "(?i)\\bbuy\\s+online\\b",
        "(?i)\\bfree\\s+shipping\\b",
        "(?i)\\bfree\\s+delivery\\b",
        "(?i)\\bbest\\s+seller\\b",
        "(?i)\\bbest\\s+price\\b",
        "(?i)\\bdiscount\\b",
        "(?i)\\bsale\\b",
        "(?i)\\bhot\\s+deal\\b",
        "(?i)\\blimited\\s+time\\b",
        "(?i)\\bbrand\\s+new\\b",
        "(?i)\\bauthentic\\b",
        "(?i)\\bofficial\\s+store\\b",
        "(?i)\\btop\\s+rated\\b",
        "(?i)\\bwith\\s+warranty\\b",
        "(?i)\\bpromo\\b",
        "(?i)\\d{1,2}%\\s*off\\b",
        "(?i)\\bcash\\s+on\\s+delivery\\b",
        "(?i)\\bcod\\s+available\\b",
        "(?i)\\bprime\\s+delivery\\b",
        "(?i)\\bamazon['’]?s?\\s+choice\\b"
    )

    private val COLOR_KEYWORDS = listOf(
        "Midnight Black", "Platinum Silver", "Triple Black", "Space Gray", "Graphite",
        "Starlight", "Silver", "Midnight", "Matte Black", "Rose Gold", "Charcoal",
        "Black", "White", "Gray", "Grey", "Blue", "Red", "Green", "Gold", "Navy"
    )

    /**
     * Parses unstructured OCR text, web title, or product description into a structured RecognizedProduct.
     */
    fun parse(rawText: String, imageUrl: String? = null): RecognizedProduct {
        val trimmed = rawText.trim()
        if (SearchQueryBuilder.isNonProductNoise(trimmed)) {
            return RecognizedProduct(
                id = UUID.randomUUID().toString(),
                title = "Unidentified Product",
                brand = null,
                category = "Unidentified",
                specifications = emptyMap(),
                confidence = 0.0f,
                imageUrl = imageUrl,
                rawTextSnippet = "",
                matchType = MatchType.SIMILAR,
                matchReason = "Input contained no recognizable product text (filename, timestamp, or URI rejected).",
                isDemoFixture = false,
                isLiveInference = false,
                status = RecognitionStatus.UNIDENTIFIED,
                recognitionSource = RecognitionSource.MANUAL_SEARCH,
                searchQuery = ""
            )
        }

        val brand = detectBrand(trimmed)
        val specs = extractSpecifications(trimmed)
        val category = detectCategory(trimmed, specs)
        val cleanTitle = cleanCanonicalTitle(trimmed, brand, specs)
        val finalTitle = cleanTitle.ifBlank { trimmed }

        val canonicalSearch = SearchQueryBuilder.build(
            SearchQueryBuilder.Input(
                productName = finalTitle,
                brand = brand,
                category = category,
                specifications = specs
            )
        ).validQueryOrNull.orEmpty()

        val hasMinimalContext = brand == null && specs.isEmpty() && finalTitle.split(Regex("\\s+")).size <= 2
        var score = if (hasMinimalContext) 0.45f else 0.5f
        if (brand != null) score += 0.2f
        if (specs.isNotEmpty()) score += minOf(0.25f, specs.size * 0.08f)
        if (cleanTitle.isNotBlank()) score += 0.05f
        val confidence = score.coerceIn(0.1f, 0.95f)

        val status = when {
            category.equals("Unidentified", ignoreCase = true) || canonicalSearch.isBlank() -> RecognitionStatus.UNIDENTIFIED
            hasMinimalContext -> RecognitionStatus.PARTIAL
            else -> RecognitionStatus.IDENTIFIED
        }

        val reason = buildExplanationReason(brand, category, specs)

        return RecognizedProduct(
            id = UUID.randomUUID().toString(),
            title = finalTitle,
            brand = brand,
            category = category,
            specifications = specs,
            confidence = confidence,
            imageUrl = imageUrl,
            rawTextSnippet = trimmed.take(200),
            matchType = if (status == RecognitionStatus.IDENTIFIED) MatchType.EXACT else MatchType.SIMILAR,
            matchReason = reason,
            isDemoFixture = false,
            isLiveInference = false,
            status = status,
            recognitionSource = RecognitionSource.LOCAL_OCR,
            searchQuery = canonicalSearch
        )
    }

    /**
     * Detects brand from text.
     */
    fun detectBrand(text: String): String? {
        val lower = text.lowercase()
        for ((brandName, patterns) in BRAND_PATTERNS) {
            for (pattern in patterns) {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(lower).find()) {
                    return brandName
                }
            }
        }
        return null
    }

    /**
     * Extracts structured tech specifications like capacity, interface, RAM, size, colors, etc.
     */
    fun extractSpecifications(text: String): Map<String, String> {
        val specs = mutableMapOf<String, String>()

        // 1. Storage Capacity vs RAM
        // RAM pattern e.g. "16GB RAM", "16 GB Unified Memory", "32GB DDR5"
        val ramRegex = Pattern.compile("(?i)\\b(\\d{1,3}\\s*GB)\\s*(?:RAM|Memory|Unified Memory|DDR[45])\\b")
        val ramMatcher = ramRegex.matcher(text)
        if (ramMatcher.find()) {
            specs["memory"] = ramMatcher.group(1).replace(" ", "")
        }

        // Storage pattern e.g. "1TB SSD", "500GB", "2TB NVMe", "512GB"
        val storageRegex = Pattern.compile("(?i)\\b(\\d{1,4}\\s*(?:TB|GB))\\s*(?:SSD|NVMe|Storage|ROM|M\\.2|PCIe)?\\b")
        val storageMatcher = storageRegex.matcher(text)
        while (storageMatcher.find()) {
            val candidate = storageMatcher.group(1).replace(" ", "").uppercase()
            // If candidate isn't already assigned to RAM
            if (specs["memory"] == null || !candidate.equals(specs["memory"], ignoreCase = true)) {
                // If text explicitly qualifies as SSD or high capacity (>= 128GB or TB)
                if (candidate.endsWith("TB") || candidate.replace("GB", "").toIntOrNull()?.let { it >= 128 } == true) {
                    specs["capacity"] = candidate
                    break
                }
            }
        }

        // 2. Interface (NVMe, PCIe 4.0/5.0, SATA, Bluetooth, Bolt)
        if (text.contains("NVMe", ignoreCase = true)) {
            val pcieRegex = Pattern.compile("(?i)PCIe\\s*([345]\\.0|Gen\\s*[345])")
            val pcieMatch = pcieRegex.matcher(text)
            specs["interface"] = if (pcieMatch.find()) "NVMe ${pcieMatch.group(0)}" else "NVMe"
        } else if (text.contains("PCIe", ignoreCase = true)) {
            val pcieRegex = Pattern.compile("(?i)PCIe\\s*([345]\\.0|Gen\\s*[345])")
            val pcieMatch = pcieRegex.matcher(text)
            specs["interface"] = if (pcieMatch.find()) pcieMatch.group(0) else "PCIe"
        } else if (text.contains("SATA III", ignoreCase = true) || text.contains("SATA 3", ignoreCase = true)) {
            specs["interface"] = "SATA III"
        } else if (text.contains("SATA", ignoreCase = true)) {
            specs["interface"] = "SATA"
        }

        // Connectivity (Bluetooth, Bolt, Wireless)
        val btRegex = Pattern.compile("(?i)Bluetooth\\s*([45]\\.\\d)?")
        val btMatch = btRegex.matcher(text)
        if (btMatch.find()) {
            specs["connectivity"] = btMatch.group(0)
        } else if (text.contains("Bolt", ignoreCase = true) && text.contains("Bluetooth", ignoreCase = true)) {
            specs["connectivity"] = "Bluetooth / Bolt"
        } else if (text.contains("Wireless", ignoreCase = true)) {
            specs["connectivity"] = "Wireless"
        }

        // 3. Form Factor (M.2 2280, 2.5", 3.5")
        if (Pattern.compile("(?i)\\bM\\.2\\s*2280\\b").matcher(text).find()) {
            specs["form_factor"] = "M.2 2280"
        } else if (Pattern.compile("(?i)\\bM\\.2\\b").matcher(text).find()) {
            specs["form_factor"] = "M.2"
        } else if (Pattern.compile("(?i)\\b2\\.5(?:[- ]inch|\")\\b").matcher(text).find()) {
            specs["form_factor"] = "2.5-inch"
        }

        // 4. Processor / Chip
        val chipRegex = Pattern.compile("(?i)\\b(Apple\\s*M[123](?:\\s*(?:Pro|Max))?|M[123](?:\\s*(?:Pro|Max))?|Intel\\s*Core\\s*i[3579]|i[3579]-\\d{4,5}[A-Z]*|Ryzen\\s*[3579])\\b")
        val chipMatch = chipRegex.matcher(text)
        if (chipMatch.find()) {
            val found = chipMatch.group(1).trim()
            specs["chip"] = if (found.startsWith("M", ignoreCase = true) && !found.startsWith("Mac", ignoreCase = true) && !found.startsWith("Intel", ignoreCase = true)) {
                "Apple $found"
            } else {
                found
            }
        }

        // 5. Display Size (skip for industrial pipes/conduit measurements such as "2 inch")
        val isIndustrialMeasurement = Regex("(?i)\\b(?:pipes?|piping|pvc|upvc|cpvc|hdpe|conduit|valve|tubing)\\b")
            .containsMatchIn(text)
        val displayRegex = Pattern.compile("(?i)\\b(\\d{1,2}(?:\\.\\d)?)[- ]?(?:inch|\")\\b")
        val displayMatch = displayRegex.matcher(text)
        if (!isIndustrialMeasurement && displayMatch.find()) {
            specs["display"] = "${displayMatch.group(1)}-inch"
        }

        // 6. Audio Headphone Type & Battery Life
        if (text.contains("Over-Ear", ignoreCase = true) || text.contains("Over Ear", ignoreCase = true)) {
            specs["type"] = "Over-Ear"
        } else if (text.contains("In-Ear", ignoreCase = true) || text.contains("In Ear", ignoreCase = true) || text.contains("Earbuds", ignoreCase = true)) {
            specs["type"] = "In-Ear"
        }

        val batteryRegex = Pattern.compile("(?i)\\b(\\d{1,3})\\s*(?:hours|hrs|hr)\\b(?:\\s*battery)?")
        val batteryMatch = batteryRegex.matcher(text)
        if (batteryMatch.find()) {
            specs["battery"] = "${batteryMatch.group(1)} hours"
        }

        // 7. Sensor / DPI (for mice)
        val dpiRegex = Pattern.compile("(?i)\\b(\\d{3,5})\\s*DPI\\b")
        val dpiMatch = dpiRegex.matcher(text)
        if (dpiMatch.find()) {
            specs["sensor"] = "${dpiMatch.group(1)} DPI"
        }

        // 8. Shoe Sizes
        val shoeSizeRegex = Pattern.compile("(?i)\\b(UK|US|EU)\\s*(\\d{1,2}(?:\\.\\d)?)\\b")
        val shoeSizeMatch = shoeSizeRegex.matcher(text)
        if (shoeSizeMatch.find()) {
            specs["size"] = "${shoeSizeMatch.group(1).uppercase()} ${shoeSizeMatch.group(2)}"
        }

        // 9. Gender (Apparel/Shoes)
        if (Pattern.compile("(?i)\\b(?:Men's|Mens|Men)\\b").matcher(text).find()) {
            specs["gender"] = "Men"
        } else if (Pattern.compile("(?i)\\b(?:Women's|Womens|Women)\\b").matcher(text).find()) {
            specs["gender"] = "Women"
        }

        // 10. Colors
        for (color in COLOR_KEYWORDS) {
            val colorPattern = Pattern.compile("(?i)\\b${Pattern.quote(color)}\\b")
            if (colorPattern.matcher(text).find()) {
                specs["color"] = color
                break
            }
        }

        return specs
    }

    /**
     * Determines product category based on text keywords and detected specifications.
     */
    fun detectCategory(text: String, specs: Map<String, String>): String {
        val lower = text.lowercase()

        // Footwear
        if (lower.contains("shoe") || lower.contains("sneaker") || lower.contains("running") ||
            lower.contains("boots") || lower.contains("air max") || specs.containsKey("size")) {
            return "Footwear"
        }

        // Audio
        if (lower.contains("headphone") || lower.contains("earphone") || lower.contains("earbuds") ||
            lower.contains("noise cancelling") || lower.contains("wh-1000xm") || lower.contains("airpods") ||
            lower.contains("soundcore") || specs["type"] == "Over-Ear" || specs["type"] == "In-Ear") {
            return "Audio"
        }

        // Computers
        if (lower.contains("ssd") || lower.contains("nvme") || lower.contains("macbook") ||
            lower.contains("laptop") || lower.contains("thinkpad") || specs.containsKey("form_factor") ||
            specs.containsKey("chip") || lower.contains("m.2") || lower.contains("pcie")) {
            return "Computers"
        }

        // Gaming
        if (lower.contains("playstation") || lower.contains("xbox") || lower.contains("nintendo") ||
            lower.contains("geforce") || lower.contains("rtx") || lower.contains("gaming")) {
            return "Gaming"
        }

        // Fashion
        if (lower.contains("t-shirt") || lower.contains("hoodie") || lower.contains("jacket") ||
            lower.contains("jeans") || lower.contains("dress") || lower.contains("shirt")) {
            return "Fashion"
        }

        // Home
        if (lower.contains("kitchen") || lower.contains("vacuum") || lower.contains("blender") ||
            lower.contains("coffee maker") || lower.contains("lamp")) {
            return "Home"
        }

        // Industrial Materials & Hardware (Checked BEFORE electronics so metal/PVC pipes never turn into Electronics)
        if (Regex("(?i)\\b(?:pipes?|piping|pvc|upvc|cpvc|hdpe|steel\\s+pipe|metal\\s+pipe|flange|valve|conduit|rebar|tubing)\\b").containsMatchIn(lower)) {
            return "Industrial Materials"
        }

        if (Regex("(?i)\\b(?:wrench|drill|hammer|screwdriver|plywood|lumber|hardware\\s+fitting)\\b").containsMatchIn(lower)) {
            return "Hardware & Tools"
        }

        // Default electronics ONLY on clear electronics signals
        if (lower.contains("mouse") || lower.contains("keyboard") || lower.contains("charger") ||
            lower.contains("usb") || specs.containsKey("sensor") || specs.containsKey("connectivity")
        ) {
            return "Electronics"
        }

        return if (SearchQueryBuilder.isNonProductNoise(text)) "Unidentified" else "Other"
    }

    /**
     * Cleans canonical product title, stripping out marketing noise and e-commerce clutter.
     */
    fun cleanCanonicalTitle(
        rawText: String,
        brand: String?,
        specs: Map<String, String>
    ): String {
        var cleaned = rawText

        // Remove clutter phrases
        for (pattern in CLUTTER_PATTERNS) {
            cleaned = cleaned.replace(Regex(pattern), " ")
        }

        // Remove multiple spaces, pipes, special prefixes
        cleaned = cleaned.replace(Regex("[|•~_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Remove trailing commas, hyphens, periods
        cleaned = cleaned.replace(Regex("^[-:,\\s]+|[-:,\\s]+$"), "")

        return cleaned
    }

    private fun buildExplanationReason(brand: String?, category: String, specs: Map<String, String>): String {
        val brandPart = brand?.let { "brand '$it'" } ?: "category '$category'"
        val specParts = specs.entries.take(3).joinToString(", ") { "${it.key} '${it.value}'" }
        return if (specParts.isNotBlank()) {
            "Detected $brandPart with $specParts"
        } else {
            "Detected $brandPart in $category"
        }
    }
}
