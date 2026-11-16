package com.poshak.stock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks a user-initiated offline download scope (`offline_download_scopes` table).
 * Can represent either a Full Stock Download (`scopeType = "all"`)
 * or a Filtered Scope Download (`scopeType = "filtered"`).
 */
@Entity(
    tableName = "offline_download_scopes"
)
data class OfflineScopeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "scope_type")
    val scopeType: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "filters_json")
    val filtersJson: String,

    @ColumnInfo(name = "product_count")
    val productCount: Int,

    @ColumnInfo(name = "stock_row_count")
    val stockRowCount: Int,

    @ColumnInfo(name = "total_quantity")
    val totalQuantity: Int,

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String
) {
    companion object {
        const val SCOPE_TYPE_ALL = "all"
        const val SCOPE_TYPE_FILTERED = "filtered"
        const val FULL_STOCK_SCOPE_ID = "scope_full_stock_all"
    }
}

/**
 * Cross-reference mapping between an [OfflineScopeEntity] and the [ProductEntity] IDs it downloaded
 * (`offline_scope_products` table).
 */
@Entity(
    tableName = "offline_scope_products",
    primaryKeys = ["scope_id", "product_id"],
    foreignKeys = [
        ForeignKey(
            entity = OfflineScopeEntity::class,
            parentColumns = ["id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["scope_id"]),
        Index(value = ["product_id"])
    ]
)
data class OfflineScopeProductEntity(
    @ColumnInfo(name = "scope_id")
    val scopeId: String,

    @ColumnInfo(name = "product_id")
    val productId: String
)
