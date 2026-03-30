package com.linguabubble.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de données Room locale de Awabi.
 * v1 → v2 : ajout clickCount sur word_progress + table deck_entry.
 */
@Database(
    entities = [WordProgressEntity::class, DeckEntryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordProgressDao(): WordProgressDao
    abstract fun deckEntryDao(): DeckEntryDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE word_progress ADD COLUMN wasExported INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE word_progress ADD COLUMN clickCount INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS deck_entry (
                        kanji TEXT NOT NULL PRIMARY KEY,
                        reading TEXT NOT NULL,
                        definitionEn TEXT NOT NULL,
                        definitionFr TEXT NOT NULL,
                        jlptLevel TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
