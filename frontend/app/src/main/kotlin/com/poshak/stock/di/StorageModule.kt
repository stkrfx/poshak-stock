package com.poshak.stock.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Jetpack [DataStore] and encrypted [SessionManager].
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    private const val USER_PREFERENCES_NAME = "poshak_stock_ui_preferences"

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(USER_PREFERENCES_NAME) }
        )
    }

    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context,
        appDatabase: AppDatabase
    ): SessionManager {
        return SessionManager(context, appDatabase)
    }
}
