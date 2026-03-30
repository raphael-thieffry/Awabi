package com.linguabubble.feature.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linguabubble.core.data.remote.GrammarApiService
import com.linguabubble.core.domain.usecase.AnalyzeGrammarUseCase
import com.linguabubble.core.util.LlmProvider
import com.linguabubble.databinding.ActivitySettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Paramètres utilisateur : clef API, niveau, lecture, description.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    @Inject lateinit var grammarApiService: GrammarApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences(AnalyzeGrammarUseCase.PREF_NAME, Context.MODE_PRIVATE)

        // ─── Clef API ────────────────────────────────────────────────────────

        val savedKey = prefs.getString(AnalyzeGrammarUseCase.KEY_API_KEY, "") ?: ""
        binding.etApiKey.setText(savedKey)
        updateKeyStatus(savedKey)

        binding.etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateKeyStatus(s?.toString() ?: "")
                // Réinitialise le résultat du test si la clef change
                binding.tvTestResult.visibility = View.GONE
            }
        })

        binding.btnTestApiKey.setOnClickListener { testApiKey() }

        // ─── Niveau ──────────────────────────────────────────────────────────

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            AnalyzeGrammarUseCase.LEVELS
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerLevel.adapter = adapter

        val savedLevel = prefs.getString(AnalyzeGrammarUseCase.KEY_LEVEL, AnalyzeGrammarUseCase.LEVELS[0])
        binding.spinnerLevel.setSelection(
            AnalyzeGrammarUseCase.LEVELS.indexOf(savedLevel).coerceAtLeast(0)
        )

        // ─── Lecture (romaji / hiragana) ──────────────────────────────────────

        val savedReading = prefs.getString(AnalyzeGrammarUseCase.KEY_READING, AnalyzeGrammarUseCase.READING_MODES[0])
        if (savedReading == AnalyzeGrammarUseCase.READING_MODES[1]) {
            binding.rbHiragana.isChecked = true
        } else {
            binding.rbRomaji.isChecked = true
        }

        // ─── Description ─────────────────────────────────────────────────────

        binding.etDescription.setText(prefs.getString(AnalyzeGrammarUseCase.KEY_DESC, ""))

        // ─── Sauvegarder ─────────────────────────────────────────────────────

        binding.btnSave.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""

            // Validation format avant sauvegarde
            if (key.isNotBlank() && LlmProvider.detect(key) == null) {
                binding.tvApiKeyStatus.text = "⚠ Format non reconnu — vérifie ta clef"
                binding.tvApiKeyStatus.setTextColor(Color.parseColor("#FF6B35"))
                binding.tvApiKeyStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val reading = if (binding.rbHiragana.isChecked)
                AnalyzeGrammarUseCase.READING_MODES[1]
            else
                AnalyzeGrammarUseCase.READING_MODES[0]

            prefs.edit()
                .putString(AnalyzeGrammarUseCase.KEY_API_KEY, key)
                .putString(AnalyzeGrammarUseCase.KEY_LEVEL,
                    AnalyzeGrammarUseCase.LEVELS[binding.spinnerLevel.selectedItemPosition])
                .putString(AnalyzeGrammarUseCase.KEY_DESC,
                    binding.etDescription.text.toString().trim())
                .putString(AnalyzeGrammarUseCase.KEY_READING, reading)
                .apply()
            finish()
        }
    }

    // ─── Détection du fournisseur sur saisie ─────────────────────────────────

    private fun updateKeyStatus(key: String) {
        val k = key.trim()
        if (k.isBlank()) {
            binding.tvApiKeyStatus.visibility = View.GONE
            return
        }
        val provider = LlmProvider.detect(k)
        if (provider == null) {
            binding.tvApiKeyStatus.text = "⚠ Format non reconnu"
            binding.tvApiKeyStatus.setTextColor(Color.parseColor("#FF6B35"))
        } else if (!LlmProvider.isLengthPlausible(k, provider)) {
            binding.tvApiKeyStatus.text = "⚠ ${provider.displayName} — clef peut-être tronquée"
            binding.tvApiKeyStatus.setTextColor(Color.parseColor("#FF6B35"))
        } else {
            binding.tvApiKeyStatus.text = "✓ ${provider.displayName} · ${provider.modelName}"
            binding.tvApiKeyStatus.setTextColor(Color.parseColor("#2E7D32"))
        }
        binding.tvApiKeyStatus.visibility = View.VISIBLE
    }

    // ─── Test réseau réel ────────────────────────────────────────────────────

    private fun testApiKey() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val provider = LlmProvider.detect(key)

        if (key.isBlank() || provider == null) {
            binding.tvTestResult.text = "Saisis une clef valide d'abord."
            binding.tvTestResult.setTextColor(Color.parseColor("#FF6B35"))
            binding.tvTestResult.visibility = View.VISIBLE
            return
        }

        binding.btnTestApiKey.isEnabled = false
        binding.tvTestResult.text = "Test en cours…"
        binding.tvTestResult.setTextColor(Color.GRAY)
        binding.tvTestResult.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    grammarApiService.complete(
                        systemPrompt = "Reply with exactly: OK",
                        userMessage  = "ping",
                        apiKey       = key,
                        provider     = provider
                    )
                }
            }
            binding.btnTestApiKey.isEnabled = true
            result.fold(
                onSuccess = {
                    binding.tvTestResult.text = "✓ Connexion réussie — ${provider.displayName}"
                    binding.tvTestResult.setTextColor(Color.parseColor("#2E7D32"))
                },
                onFailure = { e ->
                    binding.tvTestResult.text = "✗ Échec : ${e.message}"
                    binding.tvTestResult.setTextColor(Color.parseColor("#C62828"))
                }
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
