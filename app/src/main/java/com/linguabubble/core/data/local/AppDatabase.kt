package com.linguabubble.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base de données Room locale de LinguaBubble.
 * Version 1 — contient uniquement la progression des mots pour le sprint 2.
 */
@Database(
    entities = [WordProgressEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /** DAO pour la progression des mots (SRS). */
    abstract fun wordProgressDao(): WordProgressDao
}
