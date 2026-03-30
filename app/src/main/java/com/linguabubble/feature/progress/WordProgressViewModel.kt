package com.linguabubble.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguabubble.core.data.local.WordProgressDao
import com.linguabubble.core.data.local.WordProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WordProgressViewModel @Inject constructor(
    private val dao: WordProgressDao
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** Liste filtrée par la recherche, triée clics décroissants. */
    val filtered: StateFlow<List<WordProgressEntity>> =
        MutableStateFlow<List<WordProgressEntity>>(emptyList()).also { out ->
            viewModelScope.launch {
                combine(dao.observeAll(), _query) { all, q ->
                    if (q.isBlank()) all
                    else all.filter { it.word.contains(q, ignoreCase = true) }
                }.collect { out.value = it }
            }
        }

    fun setQuery(q: String) { _query.value = q }

    fun resetOne(word: String) {
        viewModelScope.launch { dao.resetClickCount(word) }
    }

    fun resetAll() {
        viewModelScope.launch { dao.resetAllClickCounts() }
    }

    fun resetNonExported() {
        viewModelScope.launch { dao.resetNonExportedClickCounts() }
    }
}
