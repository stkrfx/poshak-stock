package com.poshak.stock.di

import android.content.Context
import androidx.room.Room
import com.poshak.stock.data.local.dao.AutocompleteDao
import com.poshak.stock.data.local.dao.OfflineScopeDao
import com.poshak.stock.data.local.dao.ProductStockDao
import com.poshak.stock.data.local.dao.SearchHistoryDao
import com.poshak.stock.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room [AppDatabase] singleton and all 4 DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideProductStockDao(database: AppDatabase): ProductStockDao {
        return database.productStockDao()
    }

    @Provides
    fun provideAutocompleteDao(database: AppDatabase): AutocompleteDao {
        return database.autocompleteDao()
    }

    @Provides
    fun provideOfflineScopeDao(database: AppDatabase): OfflineScopeDao {
        return database.offlineScopeDao()
    }

    @Provides
    fun provideSearchHistoryDao(database: AppDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }
}
