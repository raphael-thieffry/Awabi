package com.linguabubble.core.domain.usecase

import android.content.Context
import com.linguabubble.core.data.remote.GrammarApiService
import com.linguabubble.core.util.LlmProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class GrammarResult(
    val corrected: String,
    val main: String,
    val grammar: String,
    val translation: String
)

/**
 * Envoie le texte OCR brut à gpt-4o-mini via GitHub Models.
 * Retourne un [GrammarResult] structuré. Retry automatique si le format est invalide.
 */
@Singleton
class AnalyzeGrammarUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: GrammarApiService
) {

    companion object {
        const val PREF_NAME = "awabi_settings"
        const val KEY_LEVEL = "user_level"
        const val KEY_DESC  = "user_description"
        val LEVELS = listOf("Débutant", "Intermédiaire", "Avancé", "Expert")

        const val KEY_READING  = "reading_mode"
        val READING_MODES = listOf("Romaji", "Hiragana")

        const val KEY_API_KEY = "api_key"

        private const val TAG_CORRECTED   = "[CORRECTED]"
        private const val TAG_MAIN        = "[MAIN]"
        private const val TAG_GRAMMAR     = "[GRAMMAR]"
        private const val TAG_TRANSLATION = "[TRANSLATION]"
    }

    /** Analyse avec retry automatique (max 2 tentatives) si le format LLM est incorrect. */
    suspend fun analyze(rawText: String): GrammarResult {
        val prefs   = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val level   = prefs.getString(KEY_LEVEL, LEVELS[0]) ?: LEVELS[0]
        val desc    = prefs.getString(KEY_DESC, "") ?: ""
        val reading = prefs.getString(KEY_READING, READING_MODES[0]) ?: READING_MODES[0]
        val apiKey  = prefs.getString(KEY_API_KEY, "") ?: ""

        if (apiKey.isBlank())
            throw IllegalStateException("Aucune clef API configurée — va dans ⚙ Paramètres.")

        val provider = LlmProvider.detect(apiKey)
            ?: throw IllegalStateException("Format de clef API non reconnu.")

        val prompt = buildSystemPrompt(level, desc, reading)

        repeat(2) { attempt ->
            try {
                val raw = apiService.complete(prompt, rawText, apiKey, provider)
                return parse(raw)
            } catch (e: Exception) {
                if (attempt == 1) throw e
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun parse(raw: String): GrammarResult {
        val ci = raw.indexOf(TAG_CORRECTED)
        val mi = raw.indexOf(TAG_MAIN)
        val gi = raw.indexOf(TAG_GRAMMAR)
        val ti = raw.indexOf(TAG_TRANSLATION)
        if (ci < 0 || mi < 0 || gi < 0 || ti < 0 || !(ci < mi && mi < gi && gi < ti))
            throw IllegalArgumentException("Format LLM invalide : balises manquantes")

        val corrected   = raw.substring(ci + TAG_CORRECTED.length, mi).trim()
        val main        = raw.substring(mi + TAG_MAIN.length, gi).trim()
        val grammar     = raw.substring(gi + TAG_GRAMMAR.length, ti).trim()
        val translation = raw.substring(ti + TAG_TRANSLATION.length).trim()

        if (main.isBlank() || grammar.isBlank() || translation.isBlank())
            throw IllegalArgumentException("Format LLM invalide : sections vides")

        return GrammarResult(corrected, main, grammar, translation)
    }

    private fun buildSystemPrompt(level: String, desc: String, reading: String): String {
        val descPart = if (desc.isNotBlank()) "L'utilisateur se décrit : \"$desc\"." else ""
        val readingInstr = if (reading == "Hiragana")
            "Pour chaque terme japonais cité dans l'analyse, ajoute la lecture en hiragana entre parenthèses juste après, ex : 置かれた（おかれた）."
        else
            "Pour chaque terme japonais cité dans l'analyse, ajoute la lecture en romaji entre parenthèses juste après, ex : 置かれた (okareta)."
        return """
Tu es un tuteur de japonais expert. Niveau de l'utilisateur : $level. $descPart

Tu reçois du texte brut capturé par OCR depuis un écran (jeu, manga, série...).
L'OCR peut avoir des erreurs sur des kanjis visuellement proches (ex: 膝↔藤, 己↔已).

$readingInstr

Réponds EXACTEMENT dans ce format (les balises doivent être présentes telles quelles) :

[CORRECTED]
Texte corrigé (ou "(aucune correction)" si rien à changer).

[MAIN]
Phrase ou expression principale, en japonais.

[GRAMMAR]
Explication grammaticale en français adaptée au niveau $level. Utilise du **gras** pour les termes-clés. Pas de section vocabulaire.

[TRANSLATION]
Traduction française de la phrase principale (section [MAIN]).

Conserve les mots en romaji/anglais tels quels dans tes réponses.
        """.trimIndent()
    }
}
