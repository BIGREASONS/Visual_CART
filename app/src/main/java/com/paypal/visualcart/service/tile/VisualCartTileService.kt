package com.paypal.visualcart.service.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.paypal.visualcart.MainActivity
import com.paypal.visualcart.R
import com.paypal.visualcart.service.overlay.VisualCartOverlayService

/**
 * Quick Settings TileService for PayPal Visual Cart.
 * Allows users to toggle the floating cart overlay directly from Quick Settings.
 */
class VisualCartTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        if (!hasOverlayPermission) {
            Log.d(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Prompting user.")
            launchOverlayPermissionRequest()
            updateTileState()
            return
        }

        if (VisualCartOverlayService.isRunning) {
            Log.d(TAG, "Stopping VisualCartOverlayService from QS Tile.")
            VisualCartOverlayService.stop(this)
            updateTileAppearance(active = false)
        } else {
            Log.d(TAG, "Starting VisualCartOverlayService from QS Tile.")
            VisualCartOverlayService.start(this)
            updateTileAppearance(active = true)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val active = VisualCartOverlayService.isRunning && Settings.canDrawOverlays(this)
        updateTileAppearance(active)
    }

    private fun updateTileAppearance(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_quick_settings_cart)
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "Active" else "Inactive"
        }
        tile.updateTile()
    }

    private fun launchOverlayPermissionRequest() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_OVERLAY_PERMISSION
            putExtra(MainActivity.EXTRA_REQUEST_OVERLAY_PERMISSION, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "VisualCartTileService"

        /**
         * Requests the system to sync and update the tile listening state.
         */
        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, VisualCartTileService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }
}
