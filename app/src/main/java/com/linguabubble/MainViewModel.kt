package com.linguabubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguabubble.core.data.local.DeckEntryEntity
import com.linguabubble.core.data.local.WordProgressDao
import com.linguabubble.core.util.ApkgBuilder
import com.linguabubble.feature.anki.CurrentDeckRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val currentDeckRepository: CurrentDeckRepository,
    private val wordProgressDao: WordProgressDao
) : ViewModel() {

    /** Flux du deck courant, partagé avec l'UI. */
    val deckEntries: StateFlow<List<DeckEntryEntity>> = currentDeckRepository.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Supprime un mot du deck. */
    fun removeEntry(kanji: String) {
        viewModelScope.launch { currentDeckRepository.remove(kanji) }
    }

    /** Reset le clickCount d'un seul mot. */
    fun resetWordClicks(kanji: String) {
        viewModelScope.launch { wordProgressDao.resetClickCount(kanji) }
    }

    /** Reset tous les clickCounts. */
    fun resetAllClicks() {
        viewModelScope.launch { wordProgressDao.resetAllClickCounts() }
    }

    /**
     * Exporte le deck courant en .apkg dans [outputFile].
     * Vide le deck après export.
     * Appelle [onDone] sur le thread principal quand c'est terminé.
     */
    fun exportAndClear(deckName: String, outputFile: File, onDone: (success: Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val entries = currentDeckRepository.getAll()
                    ApkgBuilder.build(deckName, entries, outputFile)
                    // Marquer les mots exportés pour la fonctionnalité "reset non-exportés"
                    wordProgressDao.markAsExported(entries.map { it.kanji })
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (success) currentDeckRepository.clear()
            onDone(success)
        }
    }
}
