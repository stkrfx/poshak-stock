package com.poshak.stock.ui.main.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poshak.stock.domain.logic.TableColumnVisibility
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.ProductWithStocks
import com.poshak.stock.domain.model.SortableColumns
import com.poshak.stock.domain.model.TableSortState

/**
 * Shared constant width (`48.dp`) for the leading Serial Number (`S.No.`) column
 * across both `OverviewStockTable` and `DetailedStockTable` (Section 9.5.2).
 */
val SERIAL_COL_WIDTH: Dp = 48.dp

private data class OverviewColSpec(
    val id: String,
    val title: String,
    val baseWidth: Dp,
    val isNumeric: Boolean,
    val filterField: FilterField? = null,
    val sizeColumnName: String? = null
)

/**
 * Overview Stock Table (Section 9.5):
 * - 1 row per product, with dynamic size columns ordered horizontally by `size_sort_order`.
 * - Hides any column (`Article`, `Brand`, `Name`, `Color`, `MRP`, `Party`, or size column with 0 total qty)
 *   that has no data across 100% of rows (`EmptyColumnAnalyzer`).
 * - Strictly hides `Party` when `isAdmin == false`.
 * - Fixed `S.No.` column width (`SERIAL_COL_WIDTH = 48.dp`) and `Modifier.padding(horizontal = 10.dp, vertical = 8.dp)`
 *   on every header and data cell.
 * - Uses `BoxWithConstraints`: if `SERIAL_COL_WIDTH + visible data columns` is less than `maxWidth`,
 *   distributes extra width proportionally across visible data columns so the table always fills 100% of screen width;
 *   otherwise enables synchronized `Modifier.horizontalScroll`.
 * - Tapping any column header triggers 100% frontend sorting (`▲` / `▼`).
 * - Double-tapping any filterable cell applies that value as an active filter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverviewStockTable(
    products: List<ProductWithStocks>,
    columnVisibility: TableColumnVisibility,
    sortState: TableSortState,
    isAdmin: Boolean,
    onColumnHeaderTap: (String) -> Unit,
    onCellDoubleTapFilter: (FilterField, String) -> Unit
) {
    val visibleSpecs = remember(columnVisibility, isAdmin) {
        buildList {
            if (columnVisibility.showArticle) {
                add(OverviewColSpec(SortableColumns.ARTICLE, "Article", 96.dp, false, FilterField.ARTICLE))
            }
            if (columnVisibility.showBrand) {
                add(OverviewColSpec(SortableColumns.BRAND, "Brand", 110.dp, false, FilterField.BRAND))
            }
            if (columnVisibility.showName) {
                add(OverviewColSpec(SortableColumns.NAME, "Name", 136.dp, false, FilterField.NAME))
            }
            if (columnVisibility.showColor) {
                add(OverviewColSpec(SortableColumns.COLOR, "Color", 92.dp, false, FilterField.COLOR))
            }
            // Dynamic Size Columns (sorted by size_sort_order, only non-zero total quantity)
            columnVisibility.visibleOverviewSizeColumns.forEach { sizeCol ->
                add(
                    OverviewColSpec(
                        id = SortableColumns.sizeColumnId(sizeCol.sizeName),
                        title = sizeCol.sizeName,
                        baseWidth = 64.dp,
                        isNumeric = true,
                        filterField = FilterField.SIZE,
                        sizeColumnName = sizeCol.sizeName
                    )
                )
            }
            if (columnVisibility.showQuantity) {
                add(OverviewColSpec(SortableColumns.QUANTITY, "Total Qty", 86.dp, true))
            }
            if (columnVisibility.showMrp) {
                add(OverviewColSpec(SortableColumns.MRP, "MRP", 82.dp, true))
            }
            if (isAdmin && columnVisibility.showParty) {
                add(OverviewColSpec(SortableColumns.PARTY, "Party", 130.dp, false, FilterField.PARTY))
            }
        }
    }

    val horizontalScrollState = rememberScrollState()
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenMaxWidth = maxWidth
        val totalBaseDataWidth = visibleSpecs.fold(0.dp) { acc, spec -> acc + spec.baseWidth }
        val totalIntrinsicWidth = SERIAL_COL_WIDTH + totalBaseDataWidth

        // Distribute extra width proportionally across visible data columns if narrower than maxWidth
        val expandedWidths: List<Dp> = remember(screenMaxWidth, visibleSpecs) {
            if (visibleSpecs.isEmpty()) {
                emptyList()
            } else if (totalIntrinsicWidth < screenMaxWidth && totalBaseDataWidth > 0.dp) {
                val extraWidth = screenMaxWidth - totalIntrinsicWidth
                visibleSpecs.map { spec ->
                    val ratio = spec.baseWidth.value / totalBaseDataWidth.value
                    spec.baseWidth + (extraWidth * ratio)
                }
            } else {
                visibleSpecs.map { it.baseWidth }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScrollState)
        ) {
            // Sticky Header Row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(width = 0.5.dp, color = borderColor),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Fixed S.No. Header Cell (48.dp)
                Box(
                    modifier = Modifier
                        .width(SERIAL_COL_WIDTH)
                        .border(width = 0.5.dp, color = borderColor)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S.No.",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                visibleSpecs.forEachIndexed { index, spec ->
                    val colWidth = expandedWidths[index]
                    val sortIndicator = sortState.indicatorFor(spec.id)
                    val isSorted = sortState.columnId == spec.id

                    Box(
                        modifier = Modifier
                            .width(colWidth)
                            .border(width = 0.5.dp, color = borderColor)
                            .clickable { onColumnHeaderTap(spec.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = if (spec.isNumeric) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Text(
                            text = "${spec.title}$sortIndicator",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSorted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = if (spec.isNumeric) TextAlign.End else TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Data Rows
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    items = products,
                    key = { _, item -> item.id }
                ) { rowIndex, product ->
                    val rowBg = if (rowIndex % 2 == 0) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                    }

                    Row(
                        modifier = Modifier.background(rowBg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Fixed 48.dp S.No. Cell
                        Box(
                            modifier = Modifier
                                .width(SERIAL_COL_WIDTH)
                                .border(width = 0.5.dp, color = borderColor)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${rowIndex + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        visibleSpecs.forEachIndexed { colIndex, spec ->
                            val colWidth = expandedWidths[colIndex]
                            val cellText = when {
                                spec.sizeColumnName != null -> {
                                    val qty = product.quantityForSize(spec.sizeColumnName)
                                    if (qty == 0) "-" else qty.toString()
                                }
                                spec.id == SortableColumns.ARTICLE -> product.article.orEmpty()
                                spec.id == SortableColumns.BRAND -> product.brand
                                spec.id == SortableColumns.NAME -> product.name
                                spec.id == SortableColumns.COLOR -> product.color.orEmpty()
                                spec.id == SortableColumns.QUANTITY -> product.totalQuantity.toString()
                                spec.id == SortableColumns.MRP -> "₹${product.mrp}"
                                spec.id == SortableColumns.PARTY -> if (isAdmin) product.party else ""
                                else -> ""
                            }

                            val doubleTapValue = when {
                                spec.sizeColumnName != null ->
                                    if (product.quantityForSize(spec.sizeColumnName) > 0) spec.sizeColumnName else ""
                                spec.filterField != null -> cellText
                                else -> ""
                            }

                            val isChipHighlighted = spec.filterField != null &&
                                columnVisibility.chipHighlightedFields.contains(spec.filterField)

                            val cellModifier = Modifier
                                .width(colWidth)
                                .border(width = 0.5.dp, color = borderColor)
                                .then(
                                    if (spec.filterField != null && doubleTapValue.isNotBlank()) {
                                        Modifier.combinedClickable(
                                            onClick = {},
                                            onDoubleClick = {
                                                onCellDoubleTapFilter(spec.filterField, doubleTapValue)
                                            }
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)

                            Box(
                                modifier = cellModifier,
                                contentAlignment = if (spec.isNumeric) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Text(
                                    text = cellText.ifBlank { "-" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = when {
                                        spec.id == SortableColumns.QUANTITY -> FontWeight.Bold
                                        isChipHighlighted -> FontWeight.SemiBold
                                        else -> FontWeight.Normal
                                    },
                                    color = if (isChipHighlighted) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    textAlign = if (spec.isNumeric) TextAlign.End else TextAlign.Start,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = borderColor)
}
