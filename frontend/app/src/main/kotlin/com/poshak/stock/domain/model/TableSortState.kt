package com.poshak.stock.domain.model

/**
 * Sort direction for frontend table column sorting.
 */
enum class SortDirection(val symbol: String) {
    ASCENDING("▲"),
    DESCENDING("▼")
}

/**
 * Identifiers for all sortable columns across Overview and Detailed stock tables.
 * Dynamic size columns in Overview mode use `sizeColId(sizeName)` (`"size_col:<sizeName>"`).
 */
object SortableColumns {
    const val ARTICLE = "article"
    const val BRAND = "brand"
    const val NAME = "name"
    const val COLOR = "color"
    const val MRP = "mrp"
    const val PARTY = "party"
    const val BARCODE = "barcode"
    const val BRANDCODE = "brandcode"
    const val SIZE = "size"
    const val QUANTITY = "quantity"
    const val TOTAL_IN = "total_in"
    const val TOTAL_OUT = "total_out"
    const val AGE_DAYS = "age_days"

    private const val SIZE_COL_PREFIX = "size_col:"

    fun sizeColumnId(sizeName: String): String = "$SIZE_COL_PREFIX${sizeName.trim()}"

    fun isSizeColumnId(columnId: String): Boolean = columnId.startsWith(SIZE_COL_PREFIX)

    fun extractSizeName(columnId: String): String = columnId.removePrefix(SIZE_COL_PREFIX)
}

/**
 * Immutable state representing the currently sorted column and direction in the result table.
 *
 * Implements the 3-step tap rule from Section 9.5.3:
 * - 1st tap on a column header: Ascending (`▲`)
 * - 2nd tap on the same column header: Descending (`▼`)
 * - Tap on a different column header: Ascending (`▲`) on the new column.
 */
data class TableSortState(
    val columnId: String? = null,
    val direction: SortDirection = SortDirection.ASCENDING
) {
    fun onColumnHeaderTapped(tappedColumnId: String): TableSortState {
        return if (columnId == tappedColumnId) {
            val nextDirection = if (direction == SortDirection.ASCENDING) {
                SortDirection.DESCENDING
            } else {
                SortDirection.ASCENDING
            }
            copy(direction = nextDirection)
        } else {
            TableSortState(
                columnId = tappedColumnId,
                direction = SortDirection.ASCENDING
            )
        }
    }

    fun indicatorFor(targetColumnId: String): String {
        return if (columnId == targetColumnId) {
            " ${direction.symbol}"
        } else {
            ""
        }
    }
}
