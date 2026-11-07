package com.paypal.visualcart.core.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.paypal.visualcart.R
import com.paypal.visualcart.ui.share.VisualCartShareActivity

/**
 * Publishes and maintains Android Sharing Shortcuts via ShortcutManagerCompat
 * to make "Add to Visual Cart" prominently available in Android's Direct Share row.
 * Aligned with Android Platform Samples and Direct Share guidance.
 */
object SharingShortcutsManager {

    const val SHORTCUT_ID_ADD_TO_CART = "shortcut_add_to_visual_cart"
    const val CATEGORY_DIRECT_SHARE = "com.paypal.visualcart.category.DIRECT_SHARE_TARGET"

    fun buildShareIntent(context: Context): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            setClass(context, VisualCartShareActivity::class.java)
            type = "image/*"
            putExtra("from_shortcut", true)
        }
    }

    fun buildShortcutInfo(context: Context): ShortcutInfoCompat {
        val shareIntent = buildShareIntent(context)
        return ShortcutInfoCompat.Builder(context, SHORTCUT_ID_ADD_TO_CART)
            .setActivity(ComponentName(context, VisualCartShareActivity::class.java))
            .setShortLabel(context.getString(R.string.share_target_label))
            .setLongLabel(context.getString(R.string.share_target_label))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(shareIntent)
            .setCategories(setOf(CATEGORY_DIRECT_SHARE))
            .setLongLived(true)
            .build()
    }

    fun publishShareShortcut(context: Context) {
        try {
            val shortcut = buildShortcutInfo(context)
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (_: Exception) {
            // Graceful fallback on devices that restrict dynamic shortcuts
        }
    }
}
