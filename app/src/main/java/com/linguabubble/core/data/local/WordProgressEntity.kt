package com.linguabubble.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entité Room qui suit la progression de l'utilisateur pour chaque mot.
 * - seenCount : nb d'apparitions dans les scans → friction SRS
 * - clickCount : nb de taps utilisateur sur le chip → ordre dans le panneau + badge
 */
@Entity(tableName = "word_progress")
data class WordProgressEntity(
    @PrimaryKey val word: String,
    val seenCount: Int = 0,
    val lastSeen: Long = 0L,
    val clickCount: Int = 0,
    /** true si ce mot a été inclus dans au moins un deck exporté vers Anki */
    val wasExported: Boolean = false
)
