package com.linguabubble

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.linguabubble.accessibility.LinguaAccessibilityService
import com.linguabubble.core.data.local.WordProgressDao
import com.linguabubble.databinding.ActivityMainBinding
import com.linguabubble.feature.anki.DeckEntryAdapter
import com.linguabubble.feature.bubble.FloatingBubbleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Écran principal.
 * - Vérification des permissions + lancement de la bulle
 * - Gestion du deck courant : voir, supprimer les mots avant export
 * - Export .apkg avec nommage manuel
 * - Menu reset des compteurs de clics (global ou par mot)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var wordProgressDao: WordProgressDao

    private lateinit var deckAdapter: DeckEntryAdapter

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchBubbleWithProjection(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupDeckList()
        setupClickListeners()
        observeDeck()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
    }

    // ─── Toolbar + menu overflow ───────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, com.linguabubble.feature.settings.SettingsActivity::class.java))
                true
            }
            R.id.action_manage_progress -> {
                startActivity(Intent(this, com.linguabubble.feature.progress.WordProgressActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── RecyclerView deck ────────────────────────────────────────────────────

    private fun setupDeckList() {
        deckAdapter = DeckEntryAdapter(onDelete = { entry ->
            viewModel.removeEntry(entry.kanji)
        })
        binding.rvDeck.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter        = deckAdapter
            addItemDecoration(DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL))
        }
    }

    private fun observeDeck() {
        lifecycleScope.launch {
            viewModel.deckEntries.collect { entries ->
                deckAdapter.submitList(entries)
                val count = entries.size
                if (count > 0) {
                    binding.tvDeckTitle.text = getString(R.string.deck_title, count)
                    binding.rvDeck.visibility    = View.VISIBLE
                    binding.tvDeckEmpty.visibility = View.GONE
                    binding.btnExport.isEnabled = true
                } else {
                    binding.tvDeckTitle.text = getString(R.string.deck_title_empty)
                    binding.rvDeck.visibility    = View.GONE
                    binding.tvDeckEmpty.visibility = View.VISIBLE
                    binding.btnExport.isEnabled = false
                }
            }
        }
    }

    // ─── Listeners ────────────────────────────────────────────────────────────

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

        binding.btnExport.setOnClickListener {
            showExportDialog()
        }

        binding.btnManageProgress.setOnClickListener {
            startActivity(Intent(this, com.linguabubble.feature.progress.WordProgressActivity::class.java))
        }
    }

    // ─── Permissions UI ───────────────────────────────────────────────────────

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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceComponent = ComponentName(this, LinguaAccessibilityService::class.java)
        val enabledServices  = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceComponent.flattenToString())
    }

    // ─── Bulle ────────────────────────────────────────────────────────────────

    private fun toggleBubble() {
        if (FloatingBubbleService.isRunning) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            binding.root.postDelayed({ updatePermissionUI() }, 300)
        } else {
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

    // ─── Export .apkg ─────────────────────────────────────────────────────────

    private fun showExportDialog() {
        val count = viewModel.deckEntries.value.size
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.export_deck_name_hint)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            setText("Awabi_$dateStr")
            setPadding(48, 24, 48, 8)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_dialog_title, count))
            .setMessage(getString(R.string.export_dialog_message))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_export_confirm)) { _, _ ->
                val name = input.text.toString().trim().ifBlank {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                }
                doExport(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doExport(deckName: String) {
        val safeName = deckName.replace(Regex("[^a-zA-Z0-9_\\-\\u3040-\\u9FFF ]"), "_")
        val fileName = "${safeName}_${System.currentTimeMillis() / 1000}.apkg"
        val outputFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        binding.btnExport.isEnabled = false

        viewModel.exportAndClear(deckName, outputFile) { success ->
            binding.btnExport.isEnabled = true
            if (success) {
                Toast.makeText(
                    this,
                    getString(R.string.export_success, fileName),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.export_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

}
