package com.linguabubble.feature.bubble

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Tile Quick Settings "Awabi".
 * - Bulle active  → clic arrête le service, tile en ACTIVE
 * - Bulle inactive → clic ouvre TileActivity (permission MediaProjection), tile en INACTIVE
 */
class BubbleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingBubbleService.isRunning) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            updateTile(active = false)
        } else {
            // Android 14+ : startActivityAndCollapse(Intent) interdit → PendingIntent obligatoire
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, TileActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        }
    }

    private fun updateTile(active: Boolean = FloatingBubbleService.isRunning) {
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Awabi"
            updateTile()
        }
    }
}
