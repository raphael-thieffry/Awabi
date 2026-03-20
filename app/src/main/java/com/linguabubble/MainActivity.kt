package com.linguabubble

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.linguabubble.accessibility.LinguaAccessibilityService
import com.linguabubble.databinding.ActivityMainBinding
import com.linguabubble.feature.bubble.FloatingBubbleService
import dagger.hilt.android.AndroidEntryPoint

/**
 * Écran principal : vérification des permissions et lancement de la bulle.
 *
 * Permissions nécessaires :
 *   1. SYSTEM_ALERT_WINDOW  — overlay flottant (obligatoire)
 *   2. AccessibilityService — lecture du texte des vues (optionnel, fallback)
 *   3. MediaProjection      — capture écran pour l'OCR ML Kit (demandée au lancement)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Lance le dialogue système de confirmation de capture d'écran. */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchBubbleWithProjection(result.resultCode, result.data!!)
        }
        // Si l'utilisateur refuse, on ne lance pas la bulle
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
    }

    // ─── UI ───────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnOverlayPermission.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnLaunchBubble.setOnClickListener {
            toggleBubble()
        }
    }

    private fun updatePermissionUI() {
        val overlayGranted       = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        val green = getColor(R.color.status_granted)
        val red   = getColor(R.color.status_denied)

        binding.dotOverlay.backgroundTintList =
            ColorStateList.valueOf(if (overlayGranted) green else red)
        binding.dotAccessibility.backgroundTintList =
            ColorStateList.valueOf(if (accessibilityEnabled) green else red)

        binding.btnOverlayPermission.visibility =
            if (overlayGranted) View.GONE else View.VISIBLE
        binding.btnAccessibilityPermission.visibility =
            if (accessibilityEnabled) View.GONE else View.VISIBLE

        binding.btnLaunchBubble.isEnabled = overlayGranted
        binding.btnLaunchBubble.text = if (FloatingBubbleService.isRunning)
            getString(R.string.btn_stop_bubble)
        else
            getString(R.string.btn_launch_bubble)
    }

    // ─── Logique permissions ──────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceComponent = ComponentName(this, LinguaAccessibilityService::class.java)
        val enabledServices  = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceComponent.flattenToString())
    }

    // ─── Démarrage / arrêt du service ─────────────────────────────────────────

    private fun toggleBubble() {
        if (FloatingBubbleService.isRunning) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            binding.root.postDelayed({ updatePermissionUI() }, 300)
        } else {
            // Demander la permission de capture d'écran avant de lancer la bulle
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun launchBubbleWithProjection(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_MP_RESULT_CODE, resultCode)
            putExtra(FloatingBubbleService.EXTRA_MP_DATA, data)
        }
        startForegroundService(intent)
        binding.root.postDelayed({ updatePermissionUI() }, 300)
    }
}
