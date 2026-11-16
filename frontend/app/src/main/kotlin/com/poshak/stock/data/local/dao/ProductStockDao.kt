package com.poshak.stock.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.poshak.stock.data.local.entity.ProductEntity
import com.poshak.stock.data.local.entity.ProductWithStockEntities
import com.poshak.stock.data.local.entity.StockEntity
import kotlinx.coroutines.flow.Flow

/**
 * Summary counts of locally cached products and stock variants in Room.
 */
data class CachedInventorySummary(
    @ColumnInfo(name = "product_count")
    val productCount: Int,
    @ColumnInfo(name = "stock_row_count")
    val stockRowCount: Int,
    @ColumnInfo(name = "total_quantity")
    val totalQuantity: Int
)

/**
 * Data Access Object for `products` and `stock` tables.
 * Handles exact case-insensitive offline searches, organic cache upserts,
 * context-scoped Size/Color autocomplete queries, and incremental background sync updates.
 */
@Dao
interface ProductStockDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStocks(stocks: List<StockEntity>)

    @Query("DELETE FROM stock WHERE product_id IN (:productIds)")
    suspend fun deleteStocksForProducts(productIds: List<String>)

    @Query("DELETE FROM products WHERE id IN (:productIds)")
    suspend fun deleteProductsByIds(productIds: List<String>)

    @Query("DELETE FROM stock WHERE id IN (:stockIds)")
    suspend fun deleteStocksByIds(stockIds: List<String>)

    /**
     * Transactionally upserts products and their stock rows.
     * When [replaceProductStocks] is true (e.g., for full product payloads from `/v1/stock/search`
     * without a single-size filter or `/v1/sync/offline-downloads`), existing stock rows for those
     * products are replaced cleanly so removed sizes do not linger.
     */
    @Transaction
    suspend fun upsertProductsAndStocks(
        products: List<ProductEntity>,
        stocks: List<StockEntity>,
        replaceProductStocks: Boolean = false
    ) {
        if (products.isNotEmpty()) {
            insertProducts(products)
            if (replaceProductStocks) {
                val productIds = products.map { it.id }
                productIds.chunked(500).forEach { chunk ->
                    deleteStocksForProducts(chunk)
                }
            }
        }
        if (stocks.isNotEmpty()) {
            insertStocks(stocks)
        }
    }

    /**
     * Transactionally applies incremental delta updates from `GET /v1/sync/stock?since=...`.
     */
    @Transaction
    suspend fun applyIncrementalSync(
        upsertedProducts: List<ProductEntity>,
        upsertedStocks: List<StockEntity>,
        deletedProductIds: List<String>,
        deletedStockIds: List<String>
    ) {
        if (deletedStockIds.isNotEmpty()) {
            deletedStockIds.chunked(500).forEach { chunk ->
                deleteStocksByIds(chunk)
            }
        }
        if (deletedProductIds.isNotEmpty()) {
            deletedProductIds.chunked(500).forEach { chunk ->
                deleteProductsByIds(chunk)
            }
        }
        if (upsertedProducts.isNotEmpty()) {
            insertProducts(upsertedProducts)
        }
        if (upsertedStocks.isNotEmpty()) {
            insertStocks(upsertedStocks)
        }
    }

    /**
     * Executes an exact case-insensitive search against local Room `products` and `stock` tables.
     * Matches `barcode` or `brandcode` against either `p.barcode` or `p.brandcode`.
     * Enforces role security: if [isAdmin] is false, [party] filter is ignored.
     */
    @Transaction
    @Query(
        """
        SELECT p.* FROM products p
        WHERE (:article IS NULL OR :article = '' OR LOWER(p.article) = LOWER(:article))
          AND (:brand IS NULL OR :brand = '' OR LOWER(p.brand) = LOWER(:brand))
          AND (:name IS NULL OR :name = '' OR LOWER(p.name) = LOWER(:name))
          AND (:isAdmin = 0 OR :party IS NULL OR :party = '' OR LOWER(p.party) = LOWER(:party))
          AND (:color IS NULL OR :color = '' OR LOWER(p.color) = LOWER(:color))
          AND (:barcode IS NULL OR :barcode = '' OR p.barcode = :barcode OR p.brandcode = :barcode)
          AND (:brandcode IS NULL OR :brandcode = '' OR p.barcode = :brandcode OR p.brandcode = :brandcode)
          AND (
              :size IS NULL OR :size = '' OR EXISTS (
                  SELECT 1 FROM stock sx
                  WHERE sx.product_id = p.id
                    AND LOWER(sx.size) = LOWER(:size)
              )
          )
        """
    )
    suspend fun searchLocalProductsWithStocks(
        article: String?,
        brand: String?,
        barcode: String?,
        brandcode: String?,
        name: String?,
        party: String?,
        color: String?,
        size: String?,
        isAdmin: Boolean
    ): List<ProductWithStockEntities>

    /**
     * Context-scoped offline Color autocomplete when at least one primary filter is selected.
     */
    @Query(
        """
        SELECT DISTINCT p.color FROM products p
        WHERE p.color IS NOT NULL
          AND p.color != ''
          AND LOWER(p.color) LIKE :normalizedPrefix || '%'
          AND (:article IS NULL OR :article = '' OR LOWER(p.article) = LOWER(:article))
          AND (:brand IS NULL OR :brand = '' OR LOWER(p.brand) = LOWER(:brand))
          AND (:name IS NULL OR :name = '' OR LOWER(p.name) = LOWER(:name))
          AND (:isAdmin = 0 OR :party IS NULL OR :party = '' OR LOWER(p.party) = LOWER(:party))
          AND (:code IS NULL OR :code = '' OR p.barcode = :code OR p.brandcode = :code)
        ORDER BY p.color ASC
        LIMIT 50
        """
    )
    suspend fun getScopedColorSuggestions(
        normalizedPrefix: String,
        article: String?,
        brand: String?,
        name: String?,
        party: String?,
        code: String?,
        isAdmin: Boolean
    ): List<String>

    /**
     * Context-scoped offline Size autocomplete when at least one primary filter is selected.
     */
    @Query(
        """
        SELECT s.size FROM stock s
        INNER JOIN products p ON p.id = s.product_id
        WHERE s.size != ''
          AND LOWER(s.size) LIKE :normalizedPrefix || '%'
          AND (:article IS NULL OR :article = '' OR LOWER(p.article) = LOWER(:article))
          AND (:brand IS NULL OR :brand = '' OR LOWER(p.brand) = LOWER(:brand))
          AND (:name IS NULL OR :name = '' OR LOWER(p.name) = LOWER(:name))
          AND (:isAdmin = 0 OR :party IS NULL OR :party = '' OR LOWER(p.party) = LOWER(:party))
          AND (:code IS NULL OR :code = '' OR p.barcode = :code OR p.brandcode = :code)
        GROUP BY s.size
        ORDER BY MIN(s.size_sort_order) ASC, s.size ASC
        LIMIT 50
        """
    )
    suspend fun getScopedSizeSuggestions(
        normalizedPrefix: String,
        article: String?,
        brand: String?,
        name: String?,
        party: String?,
        code: String?,
        isAdmin: Boolean
    ): List<String>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM products) AS product_count,
            (SELECT COUNT(*) FROM stock) AS stock_row_count,
            COALESCE((SELECT SUM(quantity) FROM stock), 0) AS total_quantity
        """
    )
    fun observeCachedInventorySummary(): Flow<CachedInventorySummary>

    @Query("DELETE FROM stock")
    suspend fun clearAllStock()

    @Query("DELETE FROM products")
    suspend fun clearAllProducts()
}
