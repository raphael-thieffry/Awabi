package com.linguabubble.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entité Room qui suit la progression de l'utilisateur pour chaque mot.
 * Stocke le nombre de fois que le mot a été affiché (pour la mécanique SRS).
 */
@Entity(tableName = "word_progress")
data class WordProgressEntity(
    @PrimaryKey val word: String,
    val seenCount: Int = 0,
    val lastSeen: Long = 0L
)
