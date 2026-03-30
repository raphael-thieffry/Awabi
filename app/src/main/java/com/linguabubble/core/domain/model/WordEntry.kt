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
    /** Nombre d'apparitions dans les scans (friction SRS) */
    val seenCount: Int = 0,
    /** Nombre de taps utilisateur sur le chip (ordre dans le panneau + badge) */
    val clickCount: Int = 0
)
