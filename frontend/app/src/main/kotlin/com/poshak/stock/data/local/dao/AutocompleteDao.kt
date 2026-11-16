package com.poshak.stock.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.poshak.stock.data.local.entity.AutocompleteEntity

/**
 * Data Access Object for the `autocomplete_suggestions` table.
 * Supports sub-millisecond prefix lookups (`normalized_value LIKE :prefix || '%'`)
 * and organic upsert of distinct values from both online autocomplete and downloaded stock datasets.
 */
@Dao
interface AutocompleteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuggestions(items: List<AutocompleteEntity>)

    @Query(
        """
        SELECT display_value
        FROM autocomplete_suggestions
        WHERE field = :field
          AND normalized_value LIKE :normalizedPrefix || '%'
        ORDER BY
            CASE WHEN field = 'size' THEN size_sort_order ELSE 999999 END ASC,
            display_value ASC
        LIMIT :limit
        """
    )
    suspend fun getSuggestionsByPrefix(
        field: String,
        normalizedPrefix: String,
        limit: Int = 50
    ): List<String>

    @Query("DELETE FROM autocomplete_suggestions WHERE field = 'party'")
    suspend fun deletePartySuggestions()

    @Query("DELETE FROM autocomplete_suggestions")
    suspend fun clearAll()
}
