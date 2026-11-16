package com.poshak.stock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores recent stock searches locally in Room (`search_history` table).
 * Synced with `/v1/search-history` when online and recorded locally when offline.
 */
@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["timestamp_ms"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "is_mine")
    val isMine: Boolean,

    @ColumnInfo(name = "query_json")
    val queryJson: String,

    @ColumnInfo(name = "query_text")
    val queryText: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long
)
