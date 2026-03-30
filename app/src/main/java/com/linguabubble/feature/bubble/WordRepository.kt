package com.linguabubble.feature.bubble

import com.linguabubble.core.data.local.LocalDictionary
import com.linguabubble.core.data.local.WordProgressDao
import com.linguabubble.core.data.local.WordProgressEntity
import com.linguabubble.core.data.remote.JishoApiService
import com.linguabubble.core.domain.model.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository qui orchestre les lookups de mots japonais.
 *
 * Stratégie :
 *   1. Dictionnaire local JMdict (asset SQLite, ~215k entrées, offline, instantané)
 *   2. Fallback Jisho API si le mot est absent du dict local (avec délai anti rate-limit)
 *
 * TODO: passer Jisho en fallback pour les mots hors JMdict (argot, néologismes, etc.)
 */
@Singleton
class WordRepository @Inject constructor(
    private val jishoApi: JishoApiService,
    private val wordProgressDao: WordProgressDao,
    private val localDictionary: LocalDictionary
) {

    /**
     * Prépare le dictionnaire local au premier appel (copie l'asset si besoin).
     * Appelé sur le thread IO pour ne pas bloquer le Main.
     */
    private suspend fun ensureDict() = withContext(Dispatchers.IO) {
        localDictionary.ensureReady()
    }

    /**
     * Recherche une liste de tokens et retourne les WordEntry correspondants.
     * Les tokens trouvés localement sont résolus sans délai.
     * Les tokens non trouvés localement passent par Jisho (avec délai 120ms).
     */
    suspend fun lookupWords(tokens: List<String>): List<WordEntry> {
        ensureDict()
        val results  = mutableListOf<WordEntry>()
        var jishoReqs = 0

        for (token in tokens.take(12)) {
            val entry = lookupLocal(token)
                ?: run {
                    // Jisho : délai uniquement quand on l'utilise vraiment
                    if (jishoReqs > 0) delay(120)
                    jishoReqs++
                    lookupJisho(token)
                }
            entry?.let { results.add(it) }
        }
        return results
    }

    // ─── Lookup local ─────────────────────────────────────────────────────────

    private suspend fun lookupLocal(token: String): WordEntry? =
        withContext(Dispatchers.IO) {
            val entry = localDictionary.lookup(token) ?: return@withContext null
            buildWordEntry(
                kanji      = entry.kanji,
                reading    = entry.reading,
                definitionEn = entry.defEn,
                jlptLevel  = ""
            )
        }

    // ─── Fallback Jisho ───────────────────────────────────────────────────────

    private suspend fun lookupJisho(token: String): WordEntry? {
        return try {
            val response     = jishoApi.searchWord(token)
            val firstResult  = response.data.firstOrNull() ?: return null
            val firstJapanese = firstResult.japanese.firstOrNull()
            val kanji        = firstJapanese?.word ?: firstJapanese?.reading ?: token
            val reading      = firstJapanese?.reading ?: ""
            val definitionEn = firstResult.senses
                .firstOrNull()
                ?.english_definitions
                ?.take(3)
                ?.joinToString(", ")
                ?: ""
            val jlptLevel = firstResult.jlpt.firstOrNull() ?: ""
            buildWordEntry(kanji, reading, definitionEn, jlptLevel)
        } catch (e: Exception) {
            null
        }
    }

    // ─── Progression Room (commun aux deux sources) ───────────────────────────

    private suspend fun buildWordEntry(
        kanji: String,
        reading: String,
        definitionEn: String,
        jlptLevel: String
    ): WordEntry? = withContext(Dispatchers.IO) {
        if (definitionEn.isBlank()) return@withContext null

        val progress  = wordProgressDao.getProgress(kanji)
        val seenCount = progress?.seenCount ?: 0
        val now       = System.currentTimeMillis()

        if (progress == null) {
            wordProgressDao.upsert(WordProgressEntity(word = kanji, seenCount = 1, lastSeen = now))
        } else {
            wordProgressDao.incrementSeenCount(kanji, now)
        }

        WordEntry(
            kanji        = kanji,
            reading      = reading,
            definitionEn = definitionEn,
            definitionFr = "",
            jlptLevel    = jlptLevel,
            seenCount    = seenCount,
            clickCount   = progress?.clickCount ?: 0
        )
    }
}
