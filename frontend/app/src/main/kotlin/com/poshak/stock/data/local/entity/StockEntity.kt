package com.poshak.stock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a size-level stock variant row cached locally in Room (`stock` table).
 * Enforces a unique index on `(product_id, size)` and cascades deletion when its parent product is deleted.
 * Quantities (`quantity`, `total_in`, `total_out`, `age_days`) are always overwritten with absolute server values.
 */
@Entity(
    tableName = "stock",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["product_id", "size"], unique = true),
        Index(value = ["product_id"]),
        Index(value = ["size"])
    ]
)
data class StockEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "size")
    val size: String,

    @ColumnInfo(name = "size_sort_order")
    val sizeSortOrder: Int = 999999,

    @ColumnInfo(name = "quantity")
    val quantity: Int = 0,

    @ColumnInfo(name = "total_in")
    val totalIn: Int = 0,

    @ColumnInfo(name = "total_out")
    val totalOut: Int = 0,

    @ColumnInfo(name = "age_days")
    val ageDays: Int? = null,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: String
)
