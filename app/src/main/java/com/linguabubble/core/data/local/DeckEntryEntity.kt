package com.linguabubble.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entité Room représentant un mot ajouté au deck courant (appui long dans la bulle).
 * Le deck courant est exporté vers Anki puis vidé.
 */
@Entity(tableName = "deck_entry")
data class DeckEntryEntity(
    @PrimaryKey val kanji: String,
    val reading: String,
    val definitionEn: String,
    val definitionFr: String,
    val jlptLevel: String,
    val addedAt: Long = System.currentTimeMillis()
)
