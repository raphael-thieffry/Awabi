package com.linguabubble.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    /** Incrémente le compteur de vue d'un mot. */
    @Query("UPDATE word_progress SET seenCount = seenCount + 1, lastSeen = :timestamp WHERE word = :word")
    suspend fun incrementSeenCount(word: String, timestamp: Long)
}
