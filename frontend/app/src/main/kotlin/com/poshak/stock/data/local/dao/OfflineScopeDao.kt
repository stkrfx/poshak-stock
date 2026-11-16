package com.poshak.stock.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.poshak.stock.data.local.entity.OfflineScopeEntity
import com.poshak.stock.data.local.entity.OfflineScopeProductEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for `offline_download_scopes` and `offline_scope_products`.
 * Manages user-initiated Full Stock Downloads and Filtered Scope Downloads.
 */
@Dao
interface OfflineScopeDao {

    @Query("SELECT * FROM offline_download_scopes ORDER BY downloaded_at DESC")
    fun observeAllScopes(): Flow<List<OfflineScopeEntity>>

    @Query("SELECT * FROM offline_download_scopes ORDER BY downloaded_at DESC")
    suspend fun getAllScopes(): List<OfflineScopeEntity>

    @Query("SELECT * FROM offline_download_scopes WHERE id = :scopeId LIMIT 1")
    suspend fun getScopeById(scopeId: String): OfflineScopeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScope(scope: OfflineScopeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScopeProductRefs(refs: List<OfflineScopeProductEntity>)

    @Query("DELETE FROM offline_scope_products WHERE scope_id = :scopeId")
    suspend fun deleteScopeProductRefs(scopeId: String)

    @Transaction
    suspend fun saveScopeWithProductIds(scope: OfflineScopeEntity, productIds: List<String>) {
        insertScope(scope)
        deleteScopeProductRefs(scope.id)
        if (productIds.isNotEmpty()) {
            val refs = productIds.map { pid ->
                OfflineScopeProductEntity(scopeId = scope.id, productId = pid)
            }
            refs.chunked(500).forEach { chunk ->
                insertScopeProductRefs(chunk)
            }
        }
    }

    @Query("DELETE FROM offline_download_scopes WHERE id = :scopeId")
    suspend fun deleteScopeById(scopeId: String)

    @Query("UPDATE offline_download_scopes SET last_synced_at = :syncedAt")
    suspend fun updateAllScopesLastSyncedAt(syncedAt: String)

    @Query("DELETE FROM offline_scope_products")
    suspend fun clearAllScopeProducts()

    @Query("DELETE FROM offline_download_scopes")
    suspend fun clearAllScopes()
}
