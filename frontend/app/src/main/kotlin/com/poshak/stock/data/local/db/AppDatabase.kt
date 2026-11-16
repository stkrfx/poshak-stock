package com.poshak.stock.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.poshak.stock.data.local.dao.AutocompleteDao
import com.poshak.stock.data.local.dao.OfflineScopeDao
import com.poshak.stock.data.local.dao.ProductStockDao
import com.poshak.stock.data.local.dao.SearchHistoryDao
import com.poshak.stock.data.local.entity.AutocompleteEntity
import com.poshak.stock.data.local.entity.OfflineScopeEntity
import com.poshak.stock.data.local.entity.OfflineScopeProductEntity
import com.poshak.stock.data.local.entity.ProductEntity
import com.poshak.stock.data.local.entity.SearchHistoryEntity
import com.poshak.stock.data.local.entity.StockEntity

/**
 * Primary Room SQLite database for the Stock (`com.poshak.stock`) Android application.
 *
 * Includes all 6 required local tables:
 * 1. `products` ([ProductEntity])
 * 2. `stock` ([StockEntity])
 * 3. `autocomplete_suggestions` ([AutocompleteEntity])
 * 4. `offline_download_scopes` ([OfflineScopeEntity])
 * 5. `offline_scope_products` ([OfflineScopeProductEntity])
 * 6. `search_history` ([SearchHistoryEntity])
 */
@Database(
    entities = [
        ProductEntity::class,
        StockEntity::class,
        AutocompleteEntity::class,
        OfflineScopeEntity::class,
        OfflineScopeProductEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun productStockDao(): ProductStockDao
    abstract fun autocompleteDao(): AutocompleteDao
    abstract fun offlineScopeDao(): OfflineScopeDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    /**
     * Immediately wipes all 6 Room tables inside a single database transaction.
     * Invoked when the server returns HTTP 401 (`session_revoked`) or HTTP 403 (`account_blocked`).
     */
    @Transaction
    open suspend fun wipeAllTablesTransactionally() {
        offlineScopeDao().clearAllScopeProducts()
        offlineScopeDao().clearAllScopes()
        productStockDao().clearAllStock()
        productStockDao().clearAllProducts()
        autocompleteDao().clearAll()
        searchHistoryDao().clearAll()
    }

    companion object {
        const val DATABASE_NAME = "poshak_stock.db"
    }
}
