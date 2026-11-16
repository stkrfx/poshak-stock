package com.poshak.stock.domain.model

import com.poshak.stock.data.local.entity.ProductWithStockEntities
import com.poshak.stock.data.remote.dto.ProductDto
import com.poshak.stock.data.remote.dto.StockItemDto

/**
 * Domain representation of a single size-level stock variant row.
 */
data class StockVariant(
    val id: String,
    val productId: String,
    val size: String,
    val sizeSortOrder: Int,
    val quantity: Int,
    val totalIn: Int,
    val totalOut: Int,
    val ageDays: Int?,
    val lastUpdated: String
)

/**
 * Domain representation of a product with all of its nested [StockVariant] rows.
 * Used across both Overview Mode (1 row per product with size columns)
 * and Detailed Mode (1 row per flattened product + size variant).
 */
data class ProductWithStocks(
    val id: String,
    val sourceProductKey: String,
    val name: String,
    val brand: String,
    val article: String?,
    val color: String?,
    val mrp: Int,
    val party: String,
    val barcode: String,
    val brandcode: String,
    val updatedAt: String,
    val stocks: List<StockVariant>
) {
    val totalQuantity: Int
        get() = stocks.sumOf { it.quantity }

    val totalInSum: Int
        get() = stocks.sumOf { it.totalIn }

    val totalOutSum: Int
        get() = stocks.sumOf { it.totalOut }

    val maxAgeDays: Int?
        get() = stocks.mapNotNull { it.ageDays }.maxOrNull()

    fun quantityForSize(sizeName: String): Int {
        return stocks
            .filter { it.size.equals(sizeName, ignoreCase = true) }
            .sumOf { it.quantity }
    }

    companion object {
        fun fromDto(dto: ProductDto, isAdmin: Boolean): ProductWithStocks {
            val sortedStocks = dto.stocks
                .map { it.toDomain(dto.id) }
                .sortedWith(compareBy<StockVariant> { it.sizeSortOrder }.thenBy { it.size.lowercase() })

            return ProductWithStocks(
                id = dto.id,
                sourceProductKey = dto.sourceProductKey,
                name = dto.name,
                brand = dto.brand,
                article = dto.article?.takeIf { it.isNotBlank() },
                color = dto.color?.takeIf { it.isNotBlank() },
                mrp = dto.mrp,
                party = if (isAdmin) dto.party else "",
                barcode = dto.barcode,
                brandcode = dto.brandcode,
                updatedAt = dto.updatedAt,
                stocks = sortedStocks
            )
        }

        fun fromEntity(
            relation: ProductWithStockEntities,
            isAdmin: Boolean,
            sizeFilter: String? = null
        ): ProductWithStocks {
            val p = relation.product
            val filteredStocks = relation.stocks
                .filter { st ->
                    sizeFilter.isNullOrBlank() || st.size.equals(sizeFilter.trim(), ignoreCase = true)
                }
                .map { st ->
                    StockVariant(
                        id = st.id,
                        productId = st.productId,
                        size = st.size,
                        sizeSortOrder = st.sizeSortOrder,
                        quantity = st.quantity,
                        totalIn = st.totalIn,
                        totalOut = st.totalOut,
                        ageDays = st.ageDays,
                        lastUpdated = st.lastUpdated
                    )
                }
                .sortedWith(compareBy<StockVariant> { it.sizeSortOrder }.thenBy { it.size.lowercase() })

            return ProductWithStocks(
                id = p.id,
                sourceProductKey = p.sourceProductKey,
                name = p.name,
                brand = p.brand,
                article = p.article?.takeIf { it.isNotBlank() },
                color = p.color?.takeIf { it.isNotBlank() },
                mrp = p.mrp,
                party = if (isAdmin) p.party else "",
                barcode = p.barcode,
                brandcode = p.brandcode,
                updatedAt = p.updatedAt,
                stocks = filteredStocks
            )
        }

        private fun StockItemDto.toDomain(parentProductId: String): StockVariant {
            return StockVariant(
                id = id,
                productId = productId ?: parentProductId,
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
}

/**
 * Flattened row item used in Detailed Table Mode (1 row per product + size variant).
 */
data class DetailedStockRow(
    val product: ProductWithStocks,
    val stock: StockVariant
)

/**
 * Result wrapper returned by `StockRepository.searchStock(...)`.
 */
data class StockSearchResult(
    val products: List<ProductWithStocks>,
    val syncedAt: String,
    val isFromOfflineCache: Boolean
)
