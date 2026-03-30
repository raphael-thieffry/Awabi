package com.linguabubble.feature.anki

import com.linguabubble.core.data.local.DeckEntryDao
import com.linguabubble.core.data.local.DeckEntryEntity
import com.linguabubble.core.domain.model.WordEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository pour le deck courant (mots en attente d'export Anki).
 */
@Singleton
class CurrentDeckRepository @Inject constructor(
    private val deckEntryDao: DeckEntryDao
) {

    /** Flux en temps réel du deck courant. */
    val entries: Flow<List<DeckEntryEntity>> = deckEntryDao.observeAll()

    /** Flux en temps réel du nombre de mots dans le deck. */
    val count: Flow<Int> = deckEntryDao.observeCount()

    /** Ajoute un mot au deck (ignoré s'il est déjà présent). */
    suspend fun add(word: WordEntry) {
        deckEntryDao.insert(
            DeckEntryEntity(
                kanji        = word.kanji,
                reading      = word.reading,
                definitionEn = word.definitionEn,
                definitionFr = word.definitionFr,
                jlptLevel    = word.jlptLevel
            )
        )
    }

    /** Retourne true si le mot est déjà dans le deck. */
    suspend fun contains(kanji: String): Boolean = deckEntryDao.contains(kanji) > 0

    /** Supprime un mot du deck. */
    suspend fun remove(kanji: String) = deckEntryDao.delete(kanji)

    /** Snapshot du deck pour l'export. */
    suspend fun getAll(): List<DeckEntryEntity> = deckEntryDao.getAll()

    /** Vide le deck après export. */
    suspend fun clear() = deckEntryDao.clear()
}
