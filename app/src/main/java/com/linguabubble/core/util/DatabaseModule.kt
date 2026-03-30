package com.linguabubble.core.util

import android.content.Context
import androidx.room.Room
import com.linguabubble.core.data.local.AppDatabase
import com.linguabubble.core.data.local.DeckEntryDao
import com.linguabubble.core.data.local.LocalDictionary
import com.linguabubble.core.data.local.WordProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Module Hilt qui fournit la base de données Room et ses DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "awabi.db"
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
    }

    @Provides
    @Singleton
    fun provideWordProgressDao(db: AppDatabase): WordProgressDao {
        return db.wordProgressDao()
    }

    @Provides
    @Singleton
    fun provideDeckEntryDao(db: AppDatabase): DeckEntryDao {
        return db.deckEntryDao()
    }

    @Provides
    @Singleton
    fun provideLocalDictionary(@ApplicationContext context: Context): LocalDictionary {
        return LocalDictionary(context)
    }
}
