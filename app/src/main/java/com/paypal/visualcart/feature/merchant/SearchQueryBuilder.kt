package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.domain.model.RecognizedProduct
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Canonical merchant search query constructor and non-product noise gatekeeper.
 *
 * Strictly guarantees that internal capture filenames (`capture_1794369896240_4816.jpg`,
 * `shared image.jpg`, `vc_img_*.png`), capture timestamps, raw content/file URIs,
 * internal IDs, and fallback noise are NEVER passed to merchant search URLs.
 */
object SearchQueryBuilder {

    data class Input(
        val productName: String? = null,
        val brand: String? = null,
        val model: String? = null,
        val category: String? = null,
        val colour: String? = null,
        val preferredSearchQuery: String? = null,
        val specifications: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun fromRecognizedProduct(product: RecognizedProduct): Input = Input(
                productName = product.title,
                brand = product.brand,
                model = product.model ?: product.specifications["model"],
                category = product.category,
                colour = product.colour ?: product.specifications["color"] ?: product.specifications["colour"],
                preferredSearchQuery = product.searchQuery.takeIf { it.isNotBlank() },
                specifications = product.specifications
            )
        }
    }

    enum class RejectionReason {
        EMPTY_OR_BLANK,
        URI_OR_PATH,
        FILENAME_OR_EXTENSION,
        CAPTURE_TIMESTAMP,
        INTERNAL_OR_NUMERIC_ID,
        FALLBACK_NOISE_ONLY,
        NO_PRODUCT_IDENTITY
    }

    sealed class Result {
        data class Valid(
            val query: String,
            val tokens: List<String>
        ) : Result()

        data class Rejected(
            val reason: RejectionReason,
            val rawInput: String
        ) : Result()

        val validQueryOrNull: String?
            get() = (this as? Valid)?.query
    }

    private val URI_SCHEME_REGEX = Regex(
        """^(?:https?|content|file|data|intent|blob|javascript|vbscript|android\.resource):""",
        RegexOption.IGNORE_CASE
    )
    private val FILE_PATH_REGEX = Regex(
        """(?:^/(?:storage|sdcard|data|cache|mnt|tmp|var|home|dcim|pictures|download|downloads)/|^[A-Za-z]:\\|\\shared_captures\\|/shared_captures/)""",
        RegexOption.IGNORE_CASE
    )
    private val MEDIA_EXTENSION_REGEX = Regex(
        """^.+\.(?:jpe?g|png|webp|gif|bmp|heic|heif|avif|tiff?|svg|raw|dng|mp4|mov|mkv|pdf|tmp|bin)(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val EMBEDDED_FILENAME_TOKEN_REGEX = Regex(
        """^[A-Za-z0-9_()\[\]-]+\.(?:jpe?g|png|webp|gif|bmp|heic|heif|avif|tiff?|svg|raw|dng|tmp|bin)$""",
        RegexOption.IGNORE_CASE
    )
    private val CAPTURE_STEM_REGEX = Regex(
        """^(?:capture|vc[\s_-]*img|shared[\s_-]*image|share|img|pxl|screenshot|screen[\s_-]*shot|photo|pic|picture|media|dcim|dsc|vid|snap|wa|received|attachment|tmp|temp)(?:[\s_-]*\d+|[\s_-]*\(\d+\)|[\s_-]*copy|[\s_-]*edited|[\s_-]*thumb)*$""",
        RegexOption.IGNORE_CASE
    )
    private val EMBEDDED_CAPTURE_PHRASE_REGEX = Regex(
        """\b(?:capture|vc_img|shared[\s_-]+image|img|pxl|screenshot|dsc)[\s_-]+\d{4,}(?:[\s_-]+\d+)*\b""",
        RegexOption.IGNORE_CASE
    )
    private val TIMESTAMP_OR_LONG_NUM_REGEX = Regex(
        """^(?:\d{8,}|20\d{2}[-_]?\d{2}[-_]?\d{2}(?:[-_\s]?\d{4,6})?)$"""
    )
    private val UUID_REGEX = Regex(
        """^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""",
        RegexOption.IGNORE_CASE
    )
    private val INTERNAL_ID_REGEX = Regex(
        """^(?:demo-fix-|unidentified_|list_|obs_|prod_|canon_|sbx-ord-|pp-sbx-|pp-ind-sbx-|msf:|document:|image:|primary:|raw:|vc_img_).*$|^[0-9a-f]{16,}$""",
        RegexOption.IGNORE_CASE
    )
    private val CLUTTER_SUFFIX_REGEX = Regex(
        """\s*\((?:amazon india live api|flipkart live api|croma live api|reliance digital live api|myntra live api|tata cliq live api|[^)]*search result)\)""",
        RegexOption.IGNORE_CASE
    )
    private val BUYING_CLUTTER_REGEX = Regex(
        """\b(?:buy online|best price|lowest price|free shipping|cash on delivery|special offer|today's deal|verified)\b""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_PLACEHOLDERS = setOf(
        "unidentified product", "unidentified", "unidentified image",
        "unidentified image (enter search manually)", "needs query",
        "shared product", "shared image", "identified product", "analyzed product",
        "detected product", "recognized product", "product", "unknown",
        "unknown product", "generic", "shared content", "n/a", "na", "none",
        "null", "undefined", "error", "uncertain from image alone"
    )

    private val GENERIC_CATEGORIES = setOf(
        "general", "general e-commerce", "other", "unidentified", "electronics",
        "accessories", "fashion", "home", "gaming", "audio", "computers", "footwear",
        "industrial materials", "industrial", "hardware"
    )

    private val DISCRIMINATING_SPEC_KEYS = listOf(
        "model", "capacity", "storage", "memory", "ram", "chip", "size", "material", "color", "colour", "type"
    )

    fun classifyRejection(raw: String?): RejectionReason? {
        val rawTrimmed = raw?.trim().orEmpty()
        if (rawTrimmed.isBlank()) return RejectionReason.EMPTY_OR_BLANK

        val decoded = if (rawTrimmed.contains("%")) {
            try {
                URLDecoder.decode(rawTrimmed, StandardCharsets.UTF_8.name()).trim()
            } catch (_: Exception) {
                rawTrimmed
            }
        } else {
            rawTrimmed
        }

        if (URI_SCHEME_REGEX.containsMatchIn(decoded) || FILE_PATH_REGEX.containsMatchIn(decoded)) {
            return RejectionReason.URI_OR_PATH
        }

        if (MEDIA_EXTENSION_REGEX.matches(decoded)) {
            return RejectionReason.FILENAME_OR_EXTENSION
        }

        val normalizedForStem = decoded
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (EMBEDDED_CAPTURE_PHRASE_REGEX.containsMatchIn(decoded) ||
            CAPTURE_STEM_REGEX.matches(decoded) ||
            CAPTURE_STEM_REGEX.matches(normalizedForStem)
        ) {
            return RejectionReason.CAPTURE_TIMESTAMP
        }

        if (UUID_REGEX.matches(decoded) || INTERNAL_ID_REGEX.matches(decoded)) {
            return RejectionReason.INTERNAL_OR_NUMERIC_ID
        }

        if (TIMESTAMP_OR_LONG_NUM_REGEX.matches(decoded)) {
            return RejectionReason.CAPTURE_TIMESTAMP
        }

        if (decoded.lowercase(Locale.ROOT) in GENERIC_PLACEHOLDERS) {
            return RejectionReason.FALLBACK_NOISE_ONLY
        }

        return null
    }

    fun isNonProductNoise(raw: String?): Boolean = classifyRejection(raw) != null

    fun sanitizeComponent(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (classifyRejection(trimmed) != null) return ""

        var cleaned = trimmed
            .replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), " ")
            .replace(CLUTTER_SUFFIX_REGEX, " ")
            .replace(BUYING_CLUTTER_REGEX, " ")
            .replace(Regex("[|•~]"), " ")

        val cleanTokens = cleaned
            .split(Regex("\\s+"))
            .map { it.trim(',', ';') }
            .filter { token ->
                token.isNotBlank() &&
                    !EMBEDDED_FILENAME_TOKEN_REGEX.matches(token) &&
                    !UUID_REGEX.matches(token) &&
                    !INTERNAL_ID_REGEX.matches(token) &&
                    !TIMESTAMP_OR_LONG_NUM_REGEX.matches(token) &&
                    !URI_SCHEME_REGEX.containsMatchIn(token)
            }

        cleaned = cleanTokens.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (classifyRejection(cleaned) != null) "" else cleaned
    }

    fun build(input: Input): Result {
        // 1. Prefer an explicit verified searchQuery if it is clean & non-generic
        val explicitPreferred = sanitizeComponent(input.preferredSearchQuery)
        val cleanTitle = sanitizeComponent(input.productName)
        val cleanBrand = sanitizeComponent(input.brand)
            .takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in GENERIC_CATEGORIES }
        val cleanModel = sanitizeComponent(input.model)
            .takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in GENERIC_PLACEHOLDERS }
        val cleanColour = sanitizeComponent(input.colour)
            .takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in GENERIC_PLACEHOLDERS }

        if (explicitPreferred.isNotBlank()) {
            val tokens = explicitPreferred.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isNotEmpty() &&
                !(tokens.size == 1 && explicitPreferred.lowercase(Locale.ROOT) in GENERIC_CATEGORIES && cleanTitle.isBlank())
            ) {
                return Result.Valid(
                    query = tokens.take(10).joinToString(" ").take(100),
                    tokens = tokens.take(10)
                )
            }
        }

        if (cleanTitle.isBlank() && cleanModel.isNullOrBlank()) {
            val rawCandidate = listOfNotNull(input.preferredSearchQuery, input.productName, input.model)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty()
            val rejection = classifyRejection(rawCandidate) ?: RejectionReason.NO_PRODUCT_IDENTITY
            return Result.Rejected(rejection, rawCandidate)
        }

        // Prevent bare generic category names ("Electronics", "General") from masquerading as product queries
        if (cleanTitle.isNotBlank() &&
            cleanTitle.lowercase(Locale.ROOT) in GENERIC_CATEGORIES &&
            cleanBrand.isNullOrBlank() &&
            cleanModel.isNullOrBlank()
        ) {
            return Result.Rejected(RejectionReason.NO_PRODUCT_IDENTITY, input.productName.orEmpty())
        }

        val accumulatedTokens = mutableListOf<String>()
        val normalizedSeen = mutableSetOf<String>()

        fun addTokens(phrase: String) {
            for (rawTok in phrase.split(Regex("\\s+"))) {
                val tok = rawTok.trim()
                if (tok.isBlank()) continue
                val norm = tok.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                if (norm.isNotEmpty() && !normalizedSeen.contains(norm)) {
                    normalizedSeen.add(norm)
                    accumulatedTokens.add(tok)
                }
            }
        }

        if (!cleanBrand.isNullOrBlank()) {
            val brandNorm = cleanBrand.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            val titleNorm = cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            if (brandNorm.isNotEmpty() && !titleNorm.contains(brandNorm)) {
                addTokens(cleanBrand)
            }
        }

        if (cleanTitle.isNotBlank()) {
            addTokens(cleanTitle)
        }

        if (!cleanModel.isNullOrBlank()) {
            val modelNorm = cleanModel.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            val alreadyNorm = accumulatedTokens.joinToString("").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            if (modelNorm.isNotEmpty() && !alreadyNorm.contains(modelNorm)) {
                addTokens(cleanModel)
            }
        }

        if (accumulatedTokens.size < 8 && input.specifications.isNotEmpty()) {
            for (specKey in DISCRIMINATING_SPEC_KEYS) {
                if (accumulatedTokens.size >= 8) break
                val matchEntry = input.specifications.entries.firstOrNull { (k, _) ->
                    k.lowercase(Locale.ROOT).trim() == specKey
                } ?: continue
                val cleanSpecVal = sanitizeComponent(matchEntry.value)
                if (cleanSpecVal.isBlank() ||
                    cleanSpecVal.equals("Generic", ignoreCase = true) ||
                    cleanSpecVal.contains("uncertain", ignoreCase = true) ||
                    cleanSpecVal.contains("not verified", ignoreCase = true) ||
                    cleanSpecVal.lowercase(Locale.ROOT) in GENERIC_PLACEHOLDERS
                ) {
                    continue
                }
                val specNorm = cleanSpecVal.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                val alreadyNorm = accumulatedTokens.joinToString("").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                if (specNorm.isNotEmpty() && !alreadyNorm.contains(specNorm)) {
                    addTokens(cleanSpecVal)
                }
            }
        }

        if (accumulatedTokens.size < 8 && !cleanColour.isNullOrBlank()) {
            val colourNorm = cleanColour.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            val alreadyNorm = accumulatedTokens.joinToString("").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            if (colourNorm.isNotEmpty() && !alreadyNorm.contains(colourNorm)) {
                addTokens(cleanColour)
            }
        }

        val finalTokens = accumulatedTokens.take(10)
        val assembled = finalTokens.joinToString(" ").take(100).trim()
        val finalRejection = classifyRejection(assembled)
        return if (finalRejection != null || assembled.isBlank()) {
            Result.Rejected(finalRejection ?: RejectionReason.NO_PRODUCT_IDENTITY, assembled)
        } else {
            Result.Valid(query = assembled, tokens = finalTokens)
        }
    }

    fun buildFromRawQuery(rawQuery: String?): Result {
        val raw = rawQuery?.trim().orEmpty()
        val preCheck = classifyRejection(raw)
        if (preCheck != null) {
            return Result.Rejected(preCheck, raw)
        }
        return build(Input(productName = raw))
    }

    /**
     * Normalizes merchant names and domains into a canonical key so variants like
     * "Amazon", "Amazon India", and "amazon.in" never create duplicate rows.
     */
    fun normalizeMerchantKey(merchantNameOrDomain: String): String {
        val lower = merchantNameOrDomain.lowercase(Locale.ROOT).trim()
        return when {
            lower.contains("amazon") -> "amazon_in"
            lower.contains("flipkart") -> "flipkart"
            lower.contains("croma") -> "croma"
            lower.contains("reliance") -> "reliance_digital"
            lower.contains("tatacliq") || lower.contains("tata cliq") -> "tatacliq"
            lower.contains("myntra") -> "myntra"
            else -> lower.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        }
    }

    /**
     * Normalizes product identity for deduplicating identical offers within the same merchant.
     */
    fun normalizeProductIdentityKey(title: String, model: String? = null): String {
        val cleaned = sanitizeComponent(title)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val modelClean = model?.let {
            it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        }.orEmpty()
        return if (modelClean.isNotEmpty() && !cleaned.replace(" ", "").contains(modelClean)) {
            "$cleaned-$modelClean"
        } else {
            cleaned
        }
    }
}
