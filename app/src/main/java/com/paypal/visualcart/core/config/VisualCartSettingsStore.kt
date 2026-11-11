package com.paypal.visualcart.core.config

import android.content.Context
import java.net.URI
import java.util.Locale

/**
 * Local application configuration persisted in device-private [android.content.SharedPreferences].
 *
 * Stores user-selected AI recognition engine mode, demo fixture visibility, optional LAN/tunnel
 * FastAPI backend URL, and an optional Google AI Studio free-tier API key (`gemini-3.5-flash-lite`
 * only, zero billing fallback) for standalone mobile use when USB ADB reverse is disconnected.
 */
object VisualCartSettingsStore {

    private const val PREFS_NAME = "visual_cart_settings"
    private const val KEY_AI_MODE = "ai_provider_mode"
    private const val KEY_DEMO_ENABLED = "demo_data_enabled"
    private const val KEY_BACKEND_URL = "custom_backend_url"
    private const val KEY_GEMINI_API_KEY = "direct_gemini_api_key"
    private const val KEY_DISCOVERY_COUNTRY = "discovery_country"
    private const val KEY_DISCOVERY_CURRENCY = "discovery_currency"

    const val MODE_CLOUD_GEMINI = "CLOUD_GEMINI"
    const val MODE_ON_DEVICE = "ON_DEVICE_NANO"
    const val MODE_DEMO_FIXTURES = "DEMO_FIXTURES"

    const val DEFAULT_DISCOVERY_COUNTRY = "US"
    const val DEFAULT_DISCOVERY_CURRENCY = "USD"
    val SUPPORTED_DISCOVERY_LOCALES: List<Pair<String, String>> = listOf(
        "US" to "USD",
        "GB" to "GBP",
        "EU" to "EUR",
        "CA" to "CAD",
        "AU" to "AUD"
    )

    const val DEFAULT_USB_BACKEND_URL = "http://localhost:8000"
    const val DEFAULT_EMULATOR_BACKEND_URL = "http://10.0.2.2:8000"

    fun getDiscoveryCountry(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DISCOVERY_COUNTRY, DEFAULT_DISCOVERY_COUNTRY)
            ?.trim()
            ?.uppercase(java.util.Locale.US)
        return raw?.takeIf { c -> SUPPORTED_DISCOVERY_LOCALES.any { it.first == c } }
            ?: DEFAULT_DISCOVERY_COUNTRY
    }

    fun getDiscoveryCurrency(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val country = getDiscoveryCountry(context)
        val fallback = SUPPORTED_DISCOVERY_LOCALES.firstOrNull { it.first == country }?.second
            ?: DEFAULT_DISCOVERY_CURRENCY
        return prefs.getString(KEY_DISCOVERY_CURRENCY, fallback)
            ?.trim()
            ?.uppercase(java.util.Locale.US)
            ?.takeIf { it.length == 3 }
            ?: fallback
    }

    fun setDiscoveryLocale(context: Context, country: String, currency: String? = null) {
        val upperCountry = country.trim().uppercase(java.util.Locale.US)
        val matched = SUPPORTED_DISCOVERY_LOCALES.firstOrNull { it.first == upperCountry }
            ?: (DEFAULT_DISCOVERY_COUNTRY to DEFAULT_DISCOVERY_CURRENCY)
        val resolvedCurrency = currency?.trim()?.uppercase(java.util.Locale.US)
            ?.takeIf { it.length == 3 }
            ?: matched.second
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISCOVERY_COUNTRY, matched.first)
            .putString(KEY_DISCOVERY_CURRENCY, resolvedCurrency)
            .apply()
    }

    fun getAiProviderMode(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AI_MODE, MODE_CLOUD_GEMINI) ?: MODE_CLOUD_GEMINI
    }

    fun setAiProviderMode(context: Context, mode: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AI_MODE, mode)
            .apply()
    }

    fun isDemoDataEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEMO_ENABLED, true)
    }

    fun setDemoDataEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEMO_ENABLED, enabled)
            .apply()
    }

    fun getCustomBackendUrl(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalizeBackendBaseUrl(prefs.getString(KEY_BACKEND_URL, null))
    }

    fun setCustomBackendUrl(context: Context, rawUrl: String?): String? {
        val normalized = normalizeBackendBaseUrl(rawUrl)
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (normalized == null) {
            editor.remove(KEY_BACKEND_URL)
        } else {
            editor.putString(KEY_BACKEND_URL, normalized)
        }
        editor.apply()
        return normalized
    }

    fun getDirectGeminiApiKey(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setDirectGeminiApiKey(context: Context, rawKey: String?) {
        val cleaned = rawKey?.trim()?.takeIf { it.isNotBlank() }
        val editor = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (cleaned == null) {
            editor.remove(KEY_GEMINI_API_KEY)
        } else {
            editor.putString(KEY_GEMINI_API_KEY, cleaned)
        }
        editor.apply()
    }

    fun resolveCandidateBackendUrls(context: Context): List<String> {
        val ordered = linkedSetOf<String>()
        getCustomBackendUrl(context)?.let { ordered.add(it) }
        ordered.add(DEFAULT_USB_BACKEND_URL)
        ordered.add(DEFAULT_EMULATOR_BACKEND_URL)
        return ordered.toList()
    }

    fun normalizeBackendBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.any { it.isWhitespace() || it == '\u0000' }) return null

        val withScheme = if (!trimmed.contains("://")) {
            "http://$trimmed"
        } else {
            trimmed
        }

        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null) return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val cleanPath = (uri.path ?: "")
                .trimEnd('/')
                .removeSuffix("/api/v1/ai/recognize-product")
                .removeSuffix("/api/v1/ai/model-status")
                .removeSuffix("/health")
                .trimEnd('/')
            "$scheme://$host$portPart$cleanPath"
        } catch (_: Exception) {
            null
        }
    }

    fun maskApiKey(rawKey: String?): String {
        val clean = rawKey?.trim().orEmpty()
        if (clean.length <= 8) return if (clean.isEmpty()) "Not configured" else "Configured"
        return "${clean.take(6)}••••${clean.takeLast(4)}"
    }
}
