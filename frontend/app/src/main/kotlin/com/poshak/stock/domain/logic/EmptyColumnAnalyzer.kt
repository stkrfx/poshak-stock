package com.poshak.stock.domain.logic

import com.poshak.stock.domain.model.CommonValueChip
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.FilterState
import com.poshak.stock.domain.model.ProductWithStocks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Descriptor for a visible dynamic size column in the Overview Stock Table.
 */
data class VisibleSizeColumn(
    val sizeName: String,
    val sizeSortOrder: Int,
    val totalQuantityAcrossRows: Int
)

/**
 * Visibility and chip-overlap analysis for Overview and Detailed stock tables.
 */
data class TableColumnVisibility(
    val showArticle: Boolean,
    val showBrand: Boolean,
    val showName: Boolean,
    val showColor: Boolean,
    val showMrp: Boolean,
    val showParty: Boolean,
    val showBarcode: Boolean,
    val showBrandcode: Boolean,
    val showSizeInDetailed: Boolean,
    val showQuantity: Boolean,
    val showTotalIn: Boolean,
    val showTotalOut: Boolean,
    val showAgeDays: Boolean,
    val visibleOverviewSizeColumns: List<VisibleSizeColumn>,
    val chipHighlightedFields: Set<FilterField>
)

/**
 * Scans the current result set before rendering either the Overview table or the Detailed table
 * to enforce Mandatory Dynamic Empty Column Hiding (Section 9.5.1):
 * 1. If any data column (`Article`, `Color`, `Brand`, `Name`, `Party`, `Age`, etc.) is null or
 *    blank across 100% of the rows in the current result set, do not render that column at all.
 * 2. In Overview Mode (where each distinct size is a column), any size column whose total quantity
 *    across all rows in the current result set is `0` (or absent) is hidden, and visible size
 *    columns are ordered horizontally by `size_sort_order`.
 * 3. If `user.role == "employee"`, the `Party` column is strictly hidden.
 * 4. If a column's value is identical across 100% of rows and is already displayed in the
 *    Applied Filters or Common Values chip area, keep table focus clean and highlight/suppress
 *    redundant columns when other identifying columns exist.
 */
@Singleton
class EmptyColumnAnalyzer @Inject constructor() {

    fun analyze(
        products: List<ProductWithStocks>,
        activeFilter: FilterState,
        commonChips: List<CommonValueChip>,
        isAdmin: Boolean
    ): TableColumnVisibility {
        if (products.isEmpty()) {
            return TableColumnVisibility(
                showArticle = false,
                showBrand = false,
                showName = false,
                showColor = false,
                showMrp = false,
                showParty = false,
                showBarcode = false,
                showBrandcode = false,
                showSizeInDetailed = false,
                showQuantity = false,
                showTotalIn = false,
                showTotalOut = false,
                showAgeDays = false,
                visibleOverviewSizeColumns = emptyList(),
                chipHighlightedFields = emptySet()
            )
        }

        val allStocks = products.flatMap { it.stocks }

        val hasAnyArticle = products.any { !it.article.isNullOrBlank() }
        val hasAnyBrand = products.any { it.brand.isNotBlank() }
        val hasAnyName = products.any { it.name.isNotBlank() }
        val hasAnyColor = products.any { !it.color.isNullOrBlank() }
        val hasAnyMrp = products.any { it.mrp > 0 }
        val hasAnyParty = isAdmin && products.any { it.party.isNotBlank() }
        val hasAnyBarcode = products.any { it.barcode.isNotBlank() }
        val hasAnyBrandcode = products.any {
            it.brandcode.isNotBlank() && !it.brandcode.equals(it.barcode, ignoreCase = true)
        }

        val hasAnySize = allStocks.any { it.size.isNotBlank() }
        val hasAnyQuantity = allStocks.isNotEmpty()
        val hasAnyTotalIn = allStocks.any { it.totalIn != 0 }
        val hasAnyTotalOut = allStocks.any { it.totalOut != 0 }
        val hasAnyAgeDays = allStocks.any { it.ageDays != null && it.ageDays > 0 }

        // Compute Overview Mode dynamic size columns:
        // Group by canonical size, sort by MIN(sizeSortOrder) then size name, and hide any size with totalQty <= 0.
        data class SizeAccumulator(
            val displaySize: String,
            var minSortOrder: Int,
            var totalQty: Int
        )

        val sizeMap = LinkedHashMap<String, SizeAccumulator>()
        for (stock in allStocks) {
            val cleanSize = stock.size.trim()
            if (cleanSize.isEmpty()) continue
            val key = cleanSize.lowercase()
            val existing = sizeMap[key]
            if (existing == null) {
                sizeMap[key] = SizeAccumulator(
                    displaySize = cleanSize,
                    minSortOrder = stock.sizeSortOrder,
                    totalQty = stock.quantity
                )
            } else {
                if (stock.sizeSortOrder < existing.minSortOrder) {
                    existing.minSortOrder = stock.sizeSortOrder
                }
                existing.totalQty += stock.quantity
            }
        }

        val visibleSizeCols = sizeMap.values
            .filter { it.totalQty > 0 }
            .sortedWith(compareBy<SizeAccumulator> { it.minSortOrder }.thenBy { it.displaySize.lowercase() })
            .map { acc ->
                VisibleSizeColumn(
                    sizeName = acc.displaySize,
                    sizeSortOrder = acc.minSortOrder,
                    totalQuantityAcrossRows = acc.totalQty
                )
            }

        // Track fields that are already represented in Applied Filters or Common Value Chips
        val highlightedFields = mutableSetOf<FilterField>()
        activeFilter.activeFilterEntries(isAdmin).forEach { (field, _) ->
            highlightedFields.add(field)
        }
        commonChips.mapNotNull { it.filterField }.forEach { field ->
            highlightedFields.add(field)
        }

        return TableColumnVisibility(
            showArticle = hasAnyArticle,
            showBrand = hasAnyBrand,
            showName = hasAnyName,
            showColor = hasAnyColor,
            showMrp = hasAnyMrp,
            showParty = hasAnyParty,
            showBarcode = hasAnyBarcode,
            showBrandcode = hasAnyBrandcode,
            showSizeInDetailed = hasAnySize,
            showQuantity = hasAnyQuantity,
            showTotalIn = hasAnyTotalIn,
            showTotalOut = hasAnyTotalOut,
            showAgeDays = hasAnyAgeDays,
            visibleOverviewSizeColumns = visibleSizeCols,
            chipHighlightedFields = highlightedFields
        )
    }
}
