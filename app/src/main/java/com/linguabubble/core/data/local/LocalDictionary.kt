package com.linguabubble.core.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Dictionnaire local JMdict bundlé en asset (jmdict.db).
 *
 * Lors du premier appel à [ensureReady], le fichier est copié depuis les assets
 * vers filesDir. Les appels suivants réutilisent le fichier déjà copié.
 *
 * Lookup par kanji exact, puis par lecture si pas de résultat.
 */
class LocalDictionary(private val context: Context) {

    data class Entry(
        val kanji:   String,
        val reading: String,
        val defEn:   String
    )

    private val dbFile: File
        get() = File(context.filesDir, "jmdict.db")

    @Volatile private var db: SQLiteDatabase? = null

    /** Copie l'asset si nécessaire et ouvre la base en lecture seule. */
    fun ensureReady() {
        if (db != null) return
        synchronized(this) {
            if (db != null) return
            if (!dbFile.exists()) copyAsset()
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
        }
    }

    /**
     * Cherche [token] d'abord sur le champ kanji, puis sur reading.
     * Retourne null si introuvable.
     */
    fun lookup(token: String): Entry? {
        val d = db ?: return null
        // Recherche sur la colonne kanji (= le mot ou expression exacte)
        d.rawQuery(
            "SELECT kanji, reading, def_en FROM entries WHERE kanji = ? LIMIT 1",
            arrayOf(token)
        ).use { c ->
            if (c.moveToFirst()) return Entry(c.getString(0), c.getString(1), c.getString(2))
        }
        // Fallback sur la lecture (mots sans kanji, tout-hiragana)
        d.rawQuery(
            "SELECT kanji, reading, def_en FROM entries WHERE reading = ? LIMIT 1",
            arrayOf(token)
        ).use { c ->
            if (c.moveToFirst()) return Entry(c.getString(0), c.getString(1), c.getString(2))
        }
        return null
    }

    /**
     * Recherche en lot : retourne le sous-ensemble de [candidates] présents dans le dict.
     * Appelle [ensureReady] automatiquement — sûr à appeler depuis un thread IO.
     * Batché par tranche de 900 (limite SQLite = 999 paramètres).
     */
    fun batchLookup(candidates: Set<String>): Set<String> {
        ensureReady()
        val d = db ?: return emptySet()
        val found = mutableSetOf<String>()
        for (chunk in candidates.toList().chunked(900)) {
            val ph   = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            d.rawQuery("SELECT kanji   FROM entries WHERE kanji   IN ($ph)", args)
                .use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
            d.rawQuery("SELECT reading FROM entries WHERE reading IN ($ph)", args)
                .use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
        }
        return found
    }

    fun close() {
        db?.close()
        db = null
    }

    private fun copyAsset() {
        context.assets.open("jmdict.db").use { input ->
            dbFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
