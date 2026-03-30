package com.linguabubble

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/**
 * Point d'entrée de l'application Awabi.
 * @HiltAndroidApp initialise le graphe de dépendances Hilt.
 */
@HiltAndroidApp
class AwabiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        migrateDatabaseName()
    }

    /** Renomme linguabubble.db → awabi.db si l'ancien fichier existe encore. */
    private fun migrateDatabaseName() {
        val oldDb = getDatabasePath("linguabubble.db")
        val newDb = getDatabasePath("awabi.db")
        if (oldDb.exists() && !newDb.exists()) {
            oldDb.renameTo(newDb)
            File("${oldDb.path}-wal").takeIf { it.exists() }?.renameTo(File("${newDb.path}-wal"))
            File("${oldDb.path}-shm").takeIf { it.exists() }?.renameTo(File("${newDb.path}-shm"))
        }
    }
}
