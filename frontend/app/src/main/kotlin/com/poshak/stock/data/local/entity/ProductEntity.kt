package com.poshak.stock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Represents a product row cached locally in Room (`products` table).
 * Keyed by its stable UUID `id` (mapped 1:1 with `source_product_key` from Habron).
 * For users with `role = "employee"`, `party` is stored as `""`.
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["source_product_key"], unique = true),
        Index(value = ["name"]),
        Index(value = ["brand"]),
        Index(value = ["article"]),
        Index(value = ["party"]),
        Index(value = ["color"]),
        Index(value = ["barcode"]),
        Index(value = ["brandcode"])
    ]
)
data class ProductEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "source_product_key")
    val sourceProductKey: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "brand")
    val brand: String,

    @ColumnInfo(name = "article")
    val article: String?,

    @ColumnInfo(name = "color")
    val color: String?,

    @ColumnInfo(name = "mrp")
    val mrp: Int,

    @ColumnInfo(name = "party")
    val party: String,

    @ColumnInfo(name = "barcode")
    val barcode: String,

    @ColumnInfo(name = "brandcode")
    val brandcode: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String
)

/**
 * Room relation wrapper joining a [ProductEntity] with all of its nested [StockEntity] variants.
 */
data class ProductWithStockEntities(
    @Embedded
    val product: ProductEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "product_id"
    )
    val stocks: List<StockEntity>
)
