package com.linguabubble.core.util

import com.linguabubble.core.data.local.LocalDictionary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Approche Yomiwa : longest-match scan sur le texte brut contre JMdict.
 *
 * Pas de tokenisation morphologique préalable — on cherche directement les
 * plus longues sous-chaînes connues du dictionnaire à chaque position.
 * Ça permet de capturer les expressions multi-mots (白い目, お世話になる, etc.)
 * dès lors qu'elles existent dans JMdict.
 *
 * Perf : 2 requêtes SQL (kanji IN / reading IN) quelle que soit la longueur
 * du texte, puis greedy scan en mémoire.
 *
 * Appelé depuis Dispatchers.IO — [LocalDictionary.batchLookup] appelle
 * ensureReady() en interne, donc pas besoin de le gérer ici.
 */
@Singleton
class DictionaryScanner @Inject constructor(
    private val localDictionary: LocalDictionary
) {

    companion object {
        private const val MAX_LEN = 8
        private val JP_REGEX   = Regex("[\\u3040-\\u9FFF]")
        private val KANJI_ONLY = Regex("^[\\u4E00-\\u9FFF]+$")
    }

    fun scan(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // 1. Générer toutes les sous-chaînes candidates contenant du japonais.
        //    Longueur min = 1 pour les kanjis purs (膝, 上, 感…), 2 pour les autres.
        val candidates = mutableSetOf<String>()
        for (i in text.indices) {
            for (len in 1..MAX_LEN) {
                val end = i + len
                if (end > text.length) break
                val sub = text.substring(i, end)
                if (len == 1 && !KANJI_ONLY.matches(sub)) continue
                if (JP_REGEX.containsMatchIn(sub)) candidates.add(sub)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // 2. Batch-lookup — ensureReady() est appelé à l'intérieur
        val known = localDictionary.batchLookup(candidates)
        if (known.isEmpty()) return emptyList()

        // 3. Greedy longest-match gauche → droite
        val results = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in MAX_LEN downTo 1) {
                val end = i + len
                if (end > text.length) continue
                val sub = text.substring(i, end)
                if (sub in known) {
                    results.add(sub)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) i++
        }

        return results.distinct()
    }
}
