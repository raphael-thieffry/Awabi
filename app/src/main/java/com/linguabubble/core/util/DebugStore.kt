package com.linguabubble.core.util

import com.linguabubble.BuildConfig

/**
 * Stockage des données de debug pour l'overlay de diagnostic.
 * N'est peuplé que si BuildConfig.DEBUG est vrai — zéro impact en prod.
 */
object DebugStore {

    var rawText: String = ""
    var tokens: List<String> = emptyList()

    /** Dernières réponses brutes Jisho (token → body JSON tronqué). */
    private val _jishoLog = mutableListOf<Pair<String, String>>()
    val jishoLog: List<Pair<String, String>> get() = _jishoLog.toList()

    fun reset() {
        if (!BuildConfig.DEBUG) return
        rawText = ""
        tokens  = emptyList()
        _jishoLog.clear()
    }

    fun logJisho(token: String, responseBody: String) {
        if (!BuildConfig.DEBUG) return
        // Garder les 5 dernières réponses max pour pas saturer l'affichage
        if (_jishoLog.size >= 5) _jishoLog.removeAt(0)
        _jishoLog.add(token to responseBody.take(400))
    }

    fun summary(): String {
        val sb = StringBuilder()
        sb.appendLine("══ TEXTE BRUT (${rawText.length} chars) ══")
        sb.appendLine(rawText.take(300).ifBlank { "(vide)" })
        sb.appendLine()
        sb.appendLine("══ TOKENS KUROMOJI (${tokens.size}) ══")
        sb.appendLine(tokens.joinToString(", ").ifBlank { "(aucun)" })
        sb.appendLine()
        sb.appendLine("══ RÉPONSES JISHO (${_jishoLog.size} échantillons) ══")
        _jishoLog.forEach { (token, body) ->
            sb.appendLine("▶ $token")
            sb.appendLine(body)
            sb.appendLine()
        }
        return sb.toString()
    }
}
