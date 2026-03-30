package com.linguabubble.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO Room pour accéder aux données de progression des mots.
 */
@Dao
interface WordProgressDao {

    /** Récupère la progression d'un mot par sa clé (forme écrite). */
    @Query("SELECT * FROM word_progress WHERE word = :word LIMIT 1")
    suspend fun getProgress(word: String): WordProgressEntity?

    /** Insère ou met à jour la progression d'un mot. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WordProgressEntity)

    /** Incrémente le compteur de vue d'un mot (scan). */
    @Query("UPDATE word_progress SET seenCount = seenCount + 1, lastSeen = :timestamp WHERE word = :word")
    suspend fun incrementSeenCount(word: String, timestamp: Long)

    /** Incrémente le compteur de clics utilisateur sur un mot. */
    @Query("UPDATE word_progress SET clickCount = clickCount + 1 WHERE word = :word")
    suspend fun incrementClickCount(word: String)

    /** Remet le compteur de clics d'un mot à zéro. */
    @Query("UPDATE word_progress SET clickCount = 0 WHERE word = :word")
    suspend fun resetClickCount(word: String)

    /** Remet tous les compteurs de clics à zéro (reset global). */
    @Query("UPDATE word_progress SET clickCount = 0")
    suspend fun resetAllClickCounts()

    /** Flux temps réel de tous les mots triés par clics décroissants (pour WordProgressActivity). */
    @Query("SELECT * FROM word_progress ORDER BY clickCount DESC")
    fun observeAll(): Flow<List<WordProgressEntity>>

    /** Snapshot pour les opérations ponctuelles (reset dialog, export). */
    @Query("SELECT * FROM word_progress ORDER BY clickCount DESC")
    suspend fun getAll(): List<WordProgressEntity>

    /** Reset les clics uniquement des mots jamais exportés. */
    @Query("UPDATE word_progress SET clickCount = 0 WHERE wasExported = 0")
    suspend fun resetNonExportedClickCounts()

    /** Marque une liste de mots comme exportés. */
    @Query("UPDATE word_progress SET wasExported = 1 WHERE word IN (:words)")
    suspend fun markAsExported(words: List<String>)
}
