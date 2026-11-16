package com.poshak.stock.data.remote.dto

import com.poshak.stock.data.local.entity.ProductEntity
import com.poshak.stock.data.local.entity.StockEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Nested stock variant row inside a product or incremental sync payload.
 */
@Serializable
data class StockItemDto(
    @SerialName("id")
    val id: String,
    @SerialName("product_id")
    val productId: String? = null,
    @SerialName("size")
    val size: String,
    @SerialName("size_sort_order")
    val sizeSortOrder: Int = 999999,
    @SerialName("quantity")
    val quantity: Int = 0,
    @SerialName("total_in")
    val totalIn: Int = 0,
    @SerialName("total_out")
    val totalOut: Int = 0,
    @SerialName("age_days")
    val ageDays: Int? = null,
    @SerialName("last_updated")
    val lastUpdated: String = ""
) {
    fun toEntity(parentProductId: String? = null): StockEntity {
        val resolvedProductId = (parentProductId ?: productId).orEmpty()
        return StockEntity(
            id = id,
            productId = resolvedProductId,
            size = size,
            sizeSortOrder = sizeSortOrder,
            quantity = quantity,
            totalIn = totalIn,
            totalOut = totalOut,
            ageDays = ageDays,
            lastUpdated = lastUpdated
        )
    }
}

/**
 * Product item returned by `/v1/stock/search`, `/v1/sync/offline-downloads`, and `/v1/sync/stock`.
 * Note: For `role = "employee"`, `party` is omitted by the backend and defaults to `""`.
 */
@Serializable
data class ProductDto(
    @SerialName("id")
    val id: String,
    @SerialName("source_product_key")
    val sourceProductKey: String,
    @SerialName("name")
    val name: String,
    @SerialName("brand")
    val brand: String,
    @SerialName("article")
    val article: String? = null,
    @SerialName("color")
    val color: String? = null,
    @SerialName("mrp")
    val mrp: Int,
    @SerialName("party")
    val party: String = "",
    @SerialName("barcode")
    val barcode: String,
    @SerialName("brandcode")
    val brandcode: String,
    @SerialName("updated_at")
    val updatedAt: String = "",
    @SerialName("stocks")
    val stocks: List<StockItemDto> = emptyList()
) {
    fun toProductEntity(isAdmin: Boolean): ProductEntity {
        return ProductEntity(
            id = id,
            sourceProductKey = sourceProductKey,
            name = name,
            brand = brand,
            article = article?.takeIf { it.isNotBlank() },
            color = color?.takeIf { it.isNotBlank() },
            mrp = mrp,
            party = if (isAdmin) party else "",
            barcode = barcode,
            brandcode = brandcode,
            updatedAt = updatedAt
        )
    }

    fun toStockEntities(): List<StockEntity> {
        return stocks.map { it.toEntity(parentProductId = id) }
    }
}

/**
 * Exact-match filter payload serialized for offline scope downloads and search history.
 */
@Serializable
data class StockSearchFilterDto(
    @SerialName("article")
    val article: String? = null,
    @SerialName("brand")
    val brand: String? = null,
    @SerialName("barcode")
    val barcode: String? = null,
    @SerialName("brandcode")
    val brandcode: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("party")
    val party: String? = null,
    @SerialName("color")
    val color: String? = null,
    @SerialName("size")
    val size: String? = null
)

/**
 * Response envelope returned by `GET /v1/stock/search` and `POST /v1/sync/offline-downloads`.
 */
@Serializable
data class StockSearchResponseDto(
    @SerialName("products")
    val products: List<ProductDto> = emptyList(),
    @SerialName("synced_at")
    val syncedAt: String = ""
)
