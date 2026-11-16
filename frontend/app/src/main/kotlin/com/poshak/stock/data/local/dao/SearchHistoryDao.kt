package com.poshak.stock.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.poshak.stock.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the `search_history` table.
 * Maintains the latest 40 searches locally and synchronizes with `/v1/search-history`.
 */
@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY timestamp_ms DESC LIMIT :limit")
    fun observeRecentSearches(limit: Int = 40): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun getRecentSearches(limit: Int = 40): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchHistory(items: List<SearchHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistoryItem(item: SearchHistoryEntity)

    @Query(
        """
        DELETE FROM search_history
        WHERE id NOT IN (
            SELECT id FROM search_history
            ORDER BY timestamp_ms DESC
            LIMIT :keepCount
        )
        """
    )
    suspend fun trimOldHistory(keepCount: Int = 40)

    @Transaction
    suspend fun replaceHistoryWithLatest(items: List<SearchHistoryEntity>, keepCount: Int = 40) {
        if (items.isNotEmpty()) {
            upsertSearchHistory(items)
            trimOldHistory(keepCount)
        }
    }

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}
