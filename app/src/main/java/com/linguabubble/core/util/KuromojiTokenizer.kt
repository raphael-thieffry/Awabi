package com.linguabubble.core.util

import com.atilika.kuromoji.ipadic.Tokenizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper autour du tokeniseur Kuromoji (ipadic).
 * Extrait les tokens japonais pertinents : noms (名詞), verbes (動詞), adjectifs (形容詞).
 * Filtre les tokens trop courts ou sans caractères japonais.
 */
@Singleton
class KuromojiTokenizer @Inject constructor() {

    /** Regex qui vérifie la présence d'au moins un caractère japonais (hiragana, katakana, kanji). */
    private val japaneseCharRegex = Regex("[\\u3040-\\u9FFF]")

    /** Catégories grammaticales japonaises à conserver. */
    private val relevantPos = setOf("名詞", "動詞", "形容詞")

    // Initialisation lazy pour éviter un chargement au démarrage (Kuromoji est lourd)
    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    /**
     * Tokenise un texte japonais et retourne la liste dédupliquée des tokens pertinents.
     *
     * @param text texte brut (mélange possible de japonais et autre)
     * @return liste de tokens japonais filtrés (noms, verbes, adjectifs), sans doublons
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        return try {
            tokenizer.tokenize(text)
                .filter { token ->
                    // Garder uniquement les catégories grammaticales pertinentes
                    token.partOfSpeechLevel1 in relevantPos
                }
                .map { it.surface }
                .filter { surface ->
                    // Au moins 2 caractères
                    surface.length >= 2 &&
                    // Doit contenir au moins un caractère japonais
                    japaneseCharRegex.containsMatchIn(surface)
                }
                .distinct()
        } catch (e: Exception) {
            // Si Kuromoji échoue (texte non-japonais, etc.), retourner une liste vide
            emptyList()
        }
    }
}
