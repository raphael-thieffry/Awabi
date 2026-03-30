package com.linguabubble.feature.bubble

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Activité transparente lancée par la tile Quick Settings.
 * Demande la permission MediaProjection puis démarre FloatingBubbleService.
 * Se ferme immédiatement après, sans laisser de trace dans le recents.
 */
class TileActivity : Activity() {

    private val REQUEST_MP = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MP)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MP && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, FloatingBubbleService::class.java).apply {
                putExtra(FloatingBubbleService.EXTRA_MP_RESULT_CODE, resultCode)
                putExtra(FloatingBubbleService.EXTRA_MP_DATA, data)
            }
            startForegroundService(intent)
        }
        finish()
    }
}
