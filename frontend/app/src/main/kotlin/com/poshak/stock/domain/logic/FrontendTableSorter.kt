package com.poshak.stock.domain.logic

import com.poshak.stock.domain.model.DetailedStockRow
import com.poshak.stock.domain.model.ProductWithStocks
import com.poshak.stock.domain.model.SortDirection
import com.poshak.stock.domain.model.SortableColumns
import com.poshak.stock.domain.model.TableSortState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs 100% in-memory frontend sorting for both Overview Mode (`List<ProductWithStocks>`)
 * and Detailed Mode (`List<DetailedStockRow>`) per Section 9.5.3:
 * - Text columns sort case-insensitively.
 * - Numeric columns (`MRP`, `Qty`, `Total In`, `Total Out`, `Age`, and individual size-quantity
 *   columns in Overview mode) sort numerically.
 * - `Size` column in Detailed mode sorts by `size_sort_order`.
 */
@Singleton
class FrontendTableSorter @Inject constructor() {

    /**
     * Sorts products for the **Overview Table** according to [sortState].
     */
    fun sortOverviewProducts(
        products: List<ProductWithStocks>,
        sortState: TableSortState
    ): List<ProductWithStocks> {
        val colId = sortState.columnId ?: return products
        if (products.size <= 1) return products

        val ascComparator: Comparator<ProductWithStocks> = when {
            SortableColumns.isSizeColumnId(colId) -> {
                val sizeName = SortableColumns.extractSizeName(colId)
                compareBy<ProductWithStocks> { it.quantityForSize(sizeName) }
                    .thenBy { (it.article ?: "").lowercase() }
            }

            colId == SortableColumns.ARTICLE ->
                compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.article?.takeIf { s -> s.isNotBlank() } }

            colId == SortableColumns.BRAND ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.brand }

            colId == SortableColumns.NAME ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }

            colId == SortableColumns.COLOR ->
                compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.color?.takeIf { s -> s.isNotBlank() } }

            colId == SortableColumns.MRP ->
                compareBy<ProductWithStocks> { it.mrp }

            colId == SortableColumns.PARTY ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.party }

            colId == SortableColumns.BARCODE ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.barcode }

            colId == SortableColumns.BRANDCODE ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.brandcode }

            colId == SortableColumns.QUANTITY ->
                compareBy<ProductWithStocks> { it.totalQuantity }

            colId == SortableColumns.TOTAL_IN ->
                compareBy<ProductWithStocks> { it.totalInSum }

            colId == SortableColumns.TOTAL_OUT ->
                compareBy<ProductWithStocks> { it.totalOutSum }

            colId == SortableColumns.AGE_DAYS ->
                compareBy(nullsLast()) { it.maxAgeDays }

            else -> return products
        }

        val finalComparator = if (sortState.direction == SortDirection.ASCENDING) {
            ascComparator
        } else {
            ascComparator.reversed()
        }

        return products.sortedWith(finalComparator)
    }

    /**
     * Flattens [products] into [DetailedStockRow] items and sorts them for the **Detailed Table**
     * according to [sortState].
     */
    fun buildAndSortDetailedRows(
        products: List<ProductWithStocks>,
        sortState: TableSortState
    ): List<DetailedStockRow> {
        val rows = products.flatMap { product ->
            product.stocks.map { stock ->
                DetailedStockRow(product = product, stock = stock)
            }
        }
        val colId = sortState.columnId ?: return rows
        if (rows.size <= 1) return rows

        val ascComparator: Comparator<DetailedStockRow> = when (colId) {
            SortableColumns.ARTICLE ->
                compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) {
                    it.product.article?.takeIf { s -> s.isNotBlank() }
                }

            SortableColumns.BRAND ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.product.brand }

            SortableColumns.NAME ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.product.name }

            SortableColumns.COLOR ->
                compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) {
                    it.product.color?.takeIf { s -> s.isNotBlank() }
                }

            SortableColumns.MRP ->
                compareBy<DetailedStockRow> { it.product.mrp }

            SortableColumns.PARTY ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.product.party }

            SortableColumns.BARCODE ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.product.barcode }

            SortableColumns.BRANDCODE ->
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.product.brandcode }

            SortableColumns.SIZE ->
                compareBy<DetailedStockRow> { it.stock.sizeSortOrder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.stock.size }

            SortableColumns.QUANTITY ->
                compareBy<DetailedStockRow> { it.stock.quantity }

            SortableColumns.TOTAL_IN ->
                compareBy<DetailedStockRow> { it.stock.totalIn }

            SortableColumns.TOTAL_OUT ->
                compareBy<DetailedStockRow> { it.stock.totalOut }

            SortableColumns.AGE_DAYS ->
                compareBy(nullsLast()) { it.stock.ageDays }

            else -> return rows
        }

        val finalComparator = if (sortState.direction == SortDirection.ASCENDING) {
            ascComparator
        } else {
            ascComparator.reversed()
        }

        return rows.sortedWith(finalComparator)
    }
}
