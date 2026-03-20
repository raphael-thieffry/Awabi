package com.linguabubble.core.domain.model

/**
 * Modèle domaine représentant un mot japonais avec ses métadonnées.
 * Utilisé pour afficher les mots dans la bulle flottante.
 */
data class WordEntry(
    /** Forme kanji/écrite du mot */
    val kanji: String,
    /** Lecture en hiragana (furigana) */
    val reading: String,
    /** Définition en anglais (source JMdict / Jisho) */
    val definitionEn: String,
    /** Définition en français — peut être vide si MyMemory indisponible */
    val definitionFr: String,
    /** Niveau JLPT ex : "jlpt-n3", ou vide si inconnu */
    val jlptLevel: String,
    /** Nombre de fois que l'utilisateur a vu ce mot (issu de Room) */
    val seenCount: Int = 0
)
