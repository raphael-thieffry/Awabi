package com.linguabubble.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO Room pour le deck courant (mots à exporter vers Anki).
 */
@Dao
interface DeckEntryDao {

    /** Flux en temps réel de toutes les entrées, triées par date d'ajout décroissante. */
    @Query("SELECT * FROM deck_entry ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DeckEntryEntity>>

    /** Snapshot synchrone pour l'export. */
    @Query("SELECT * FROM deck_entry ORDER BY addedAt DESC")
    suspend fun getAll(): List<DeckEntryEntity>

    /** Nombre de mots dans le deck courant. */
    @Query("SELECT COUNT(*) FROM deck_entry")
    fun observeCount(): Flow<Int>

    /** Vérifie si un mot est déjà dans le deck. */
    @Query("SELECT COUNT(*) FROM deck_entry WHERE kanji = :kanji")
    suspend fun contains(kanji: String): Int

    /** Ajoute ou ignore si déjà présent. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DeckEntryEntity)

    /** Supprime un mot du deck. */
    @Query("DELETE FROM deck_entry WHERE kanji = :kanji")
    suspend fun delete(kanji: String)

    /** Vide entièrement le deck (après export). */
    @Query("DELETE FROM deck_entry")
    suspend fun clear()
}
