package com.linguabubble.core.util

import android.database.sqlite.SQLiteDatabase
import com.linguabubble.core.data.local.DeckEntryEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Génère un fichier .apkg (format Anki) à partir d'une liste de mots.
 *
 * Format .apkg = ZIP contenant :
 *   - collection.anki2 : base SQLite avec le schéma Anki
 *   - media            : fichier JSON vide "{}" (aucun média pour l'instant)
 *
 * Template de carte :
 *   Recto : kanji
 *   Verso : lecture (furigana) + traduction EN + (FR si dispo) + niveau JLPT
 */
object ApkgBuilder {

    private const val FIELD_SEP = "\u001f"

    /**
     * Construit le fichier .apkg dans [outputFile].
     * À appeler sur un thread IO.
     *
     * @param deckName  Nom du deck tel qu'il apparaîtra dans Anki.
     * @param entries   Mots à inclure dans le deck.
     * @param outputFile Chemin de sortie (ex. /storage/emulated/0/Download/Awabi_xxx.apkg).
     */
    fun build(deckName: String, entries: List<DeckEntryEntity>, outputFile: File) {
        val tmpDir = outputFile.parentFile!!
        val collectionFile = File(tmpDir, "collection_${System.currentTimeMillis()}.anki2")

        try {
            buildCollection(deckName, entries, collectionFile)

            ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("collection.anki2"))
                collectionFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("media"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        } finally {
            collectionFile.delete()
        }
    }

    // ─── Construction de la base SQLite Anki ─────────────────────────────────

    private fun buildCollection(
        deckName: String,
        entries: List<DeckEntryEntity>,
        file: File
    ) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.use {
            createSchema(it)

            val now        = System.currentTimeMillis() / 1000L
            val deckId     = now * 1000L
            val modelId    = deckId + 1

            insertCol(it, deckId, modelId, deckName, now)

            entries.forEachIndexed { index, entry ->
                val noteId = deckId + index + 100L
                val cardId = deckId + index + 200L
                insertNote(it, noteId, modelId, entry, now)
                insertCard(it, cardId, noteId, deckId, index, now)
            }
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS col (
                id      INTEGER PRIMARY KEY,
                crt     INTEGER NOT NULL,
                mod     INTEGER NOT NULL,
                scm     INTEGER NOT NULL,
                ver     INTEGER NOT NULL,
                dty     INTEGER NOT NULL,
                usn     INTEGER NOT NULL,
                ls      INTEGER NOT NULL,
                conf    TEXT NOT NULL,
                models  TEXT NOT NULL,
                decks   TEXT NOT NULL,
                dconf   TEXT NOT NULL,
                tags    TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notes (
                id      INTEGER PRIMARY KEY,
                guid    TEXT NOT NULL,
                mid     INTEGER NOT NULL,
                mod     INTEGER NOT NULL,
                usn     INTEGER NOT NULL,
                tags    TEXT NOT NULL,
                flds    TEXT NOT NULL,
                sfld    TEXT NOT NULL,
                csum    INTEGER NOT NULL,
                flags   INTEGER NOT NULL,
                data    TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS cards (
                id      INTEGER PRIMARY KEY,
                nid     INTEGER NOT NULL,
                did     INTEGER NOT NULL,
                ord     INTEGER NOT NULL,
                mod     INTEGER NOT NULL,
                usn     INTEGER NOT NULL,
                type    INTEGER NOT NULL,
                queue   INTEGER NOT NULL,
                due     INTEGER NOT NULL,
                ivl     INTEGER NOT NULL,
                factor  INTEGER NOT NULL,
                reps    INTEGER NOT NULL,
                lapses  INTEGER NOT NULL,
                left    INTEGER NOT NULL,
                odue    INTEGER NOT NULL,
                odid    INTEGER NOT NULL,
                flags   INTEGER NOT NULL,
                data    TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revlog (
                id      INTEGER PRIMARY KEY,
                cid     INTEGER NOT NULL,
                usn     INTEGER NOT NULL,
                ease    INTEGER NOT NULL,
                ivl     INTEGER NOT NULL,
                lastIvl INTEGER NOT NULL,
                factor  INTEGER NOT NULL,
                time    INTEGER NOT NULL,
                type    INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS graves (usn INTEGER NOT NULL, oid INTEGER NOT NULL, type INTEGER NOT NULL)")
    }

    private fun insertCol(
        db: SQLiteDatabase,
        deckId: Long,
        modelId: Long,
        deckName: String,
        now: Long
    ) {
        val conf = """{"activeDecks":[$deckId],"curDeck":$deckId,"newSpread":0,"collapseTime":1200,"timeLim":0,"estTimes":true,"dueCounts":true,"curModel":"$modelId","nextPos":1,"sortType":"noteFld","sortBackwards":false,"addToCur":true}"""

        val modelJson = buildModelJson(modelId, deckId, now)
        val deckJson  = buildDeckJson(deckId, deckName, now)
        val dconf     = """{"1":{"id":1,"mod":0,"name":"Default","usn":0,"maxTaken":60,"autoplay":true,"timer":0,"replayq":true,"new":{"bury":true,"delays":[1,10],"initialFactor":2500,"ints":[1,4,7],"order":1,"perDay":20,"separate":true},"lapse":{"delays":[10],"leechAction":0,"leechFails":8,"minInt":1,"mult":0},"rev":{"bury":true,"ease4":1.3,"fuzz":0.05,"ivlFct":1,"maxIvl":36500,"minSpace":1,"perDay":200}}}"""

        db.execSQL(
            "INSERT INTO col VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(1L, now, now, now * 1000, 11, 0, -1, 0, conf, modelJson, deckJson, dconf, "{}")
        )
    }

    private fun buildModelJson(modelId: Long, deckId: Long, now: Long): String {
        val css = """.card{font-family:Arial,sans-serif;font-size:20px;text-align:center;color:#000;background:#fff;}.kanji{font-size:48px;font-weight:bold;margin:16px 0;}.reading{font-size:20px;color:#555;margin:8px 0;}.definition{font-size:16px;margin:4px 0;}.definition-fr{font-size:15px;color:#444;margin:4px 0;}.jlpt{display:inline-block;margin-top:10px;padding:2px 8px;border-radius:4px;background:#e8f4fd;color:#1a73e8;font-size:13px;font-weight:bold;}"""
        val latexPre = """\\documentclass[12pt]{article}\\special{papersize=3in,5in}\\usepackage[utf8]{inputenc}\\usepackage{amssymb,amsmath}\\pagestyle{empty}\\setlength{\\parindent}{0in}\\begin{document}"""
        val latexPost = """\\end{document}"""
        return """{
            "$modelId":{
                "id":"$modelId",
                "name":"Awabi",
                "type":0,
                "mod":$now,
                "usn":-1,
                "sortf":0,
                "did":$deckId,
                "css":"$css",
                "latexPre":"$latexPre",
                "latexPost":"$latexPost",
                "tmpls":[{
                    "name":"Carte",
                    "ord":0,
                    "qfmt":"<div class='kanji'>{{Kanji}}</div>",
                    "afmt":"{{FrontSide}}<hr><div class='reading'>{{Lecture}}</div><div class='definition'>{{Traduction EN}}</div>{{#Traduction FR}}<div class='definition-fr'>{{Traduction FR}}</div>{{/Traduction FR}}{{#JLPT}}<div class='jlpt'>{{JLPT}}</div>{{/JLPT}}",
                    "bqfmt":"",
                    "bafmt":"",
                    "did":null,
                    "bfont":"",
                    "bsize":0
                }],
                "flds":[
                    {"name":"Kanji","ord":0,"sticky":false,"rtl":false,"font":"Arial","size":28,"media":[]},
                    {"name":"Lecture","ord":1,"sticky":false,"rtl":false,"font":"Arial","size":16,"media":[]},
                    {"name":"Traduction EN","ord":2,"sticky":false,"rtl":false,"font":"Arial","size":14,"media":[]},
                    {"name":"Traduction FR","ord":3,"sticky":false,"rtl":false,"font":"Arial","size":14,"media":[]},
                    {"name":"JLPT","ord":4,"sticky":false,"rtl":false,"font":"Arial","size":11,"media":[]}
                ],
                "tags":[],
                "vers":[]
            }
        }""".trimIndent()
    }

    private fun buildDeckJson(deckId: Long, deckName: String, now: Long): String {
        return """{
            "1":{"id":1,"mod":0,"name":"Default","usn":0,"lrnToday":[0,0],"revToday":[0,0],"newToday":[0,0],"timeToday":[0,0],"collapsed":false,"browserCollapsed":false,"desc":"","dyn":0,"conf":1,"extendNew":10,"extendRev":50},
            "$deckId":{"id":$deckId,"mod":$now,"name":"${deckName.replace("\"","'")}","usn":-1,"lrnToday":[0,0],"revToday":[0,0],"newToday":[0,0],"timeToday":[0,0],"collapsed":false,"browserCollapsed":false,"desc":"Exporté depuis Awabi","dyn":0,"conf":1,"extendNew":10,"extendRev":50}
        }""".trimIndent()
    }

    private fun insertNote(
        db: SQLiteDatabase,
        noteId: Long,
        modelId: Long,
        entry: DeckEntryEntity,
        now: Long
    ) {
        val fields = listOf(
            entry.kanji,
            entry.reading,
            entry.definitionEn,
            entry.definitionFr,
            entry.jlptLevel.removePrefix("jlpt-").uppercase()
        ).joinToString(FIELD_SEP)

        val csum = entry.kanji.take(8).fold(0L) { acc, c -> acc * 31 + c.code }

        db.execSQL(
            "INSERT INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(noteId, noteId.toString(36), modelId, now, -1, "", fields, entry.kanji, csum, 0, "")
        )
    }

    private fun insertCard(
        db: SQLiteDatabase,
        cardId: Long,
        noteId: Long,
        deckId: Long,
        ord: Int,
        now: Long
    ) {
        db.execSQL(
            "INSERT INTO cards VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(cardId, noteId, deckId, 0, now, -1, 0, 0, ord + 1, 0, 2500, 0, 0, 0, 0, 0, 0, "")
        )
    }
}
