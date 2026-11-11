package com.paypal.visualcart.feature.circle

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user-facing convenience preferences for the Floating Cart Bubble and
 * first-time Circle to Shop disclosure acknowledgement.
 *
 * Note: Android 14+ (API 34+) prohibits persisting or reusing MediaProjection screen-capture
 * tokens across sessions, so Android's system screen-share confirmation is always requested
 * fresh per capture session while our in-app explanation card is shown once on first use.
 */
object OverlayCapturePreferences {

    private const val PREFS_NAME = "visual_cart_capture_prefs"
    private const val KEY_BUBBLE_AUTO_START = "bubble_auto_start_enabled"
    private const val KEY_CIRCLE_DISCLOSURE_ACK = "circle_disclosure_acknowledged"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBubbleAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_AUTO_START, true)

    fun setBubbleAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_AUTO_START, enabled).apply()
    }

    fun isCircleDisclosureAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CIRCLE_DISCLOSURE_ACK, false)

    fun setCircleDisclosureAcknowledged(context: Context, acknowledged: Boolean) {
        prefs(context).edit().putBoolean(KEY_CIRCLE_DISCLOSURE_ACK, acknowledged).apply()
    }

    /**
     * Pure decision helper for auto-starting the floating cart bubble on app launch.
     */
    fun shouldAutoStartFloatingBubble(
        hasOverlayPermission: Boolean,
        userAutoStartEnabled: Boolean,
        isAlreadyRunning: Boolean,
        isDirectCircleLaunch: Boolean
    ): Boolean {
        return hasOverlayPermission &&
            userAutoStartEnabled &&
            !isAlreadyRunning &&
            !isDirectCircleLaunch
    }

    /**
     * Pure decision helper controlling whether Visual Cart displays its first-time
     * disclosure sheet prior to launching Android's MediaProjection system prompt.
     */
    fun shouldShowFirstTimeDisclosure(disclosureAcknowledged: Boolean): Boolean =
        !disclosureAcknowledged
}
