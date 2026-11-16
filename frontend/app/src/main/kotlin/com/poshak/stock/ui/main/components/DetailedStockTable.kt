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
import com.poshak.stock.domain.model.DetailedStockRow
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.SortableColumns
import com.poshak.stock.domain.model.TableSortState

private data class DetailedColSpec(
    val id: String,
    val title: String,
    val baseWidth: Dp,
    val isNumeric: Boolean,
    val filterField: FilterField? = null
)

/**
 * Detailed Stock Table (Section 9.5):
 * - 1 row per flattened product + size variant (`DetailedStockRow`).
 * - Shares the exact same leading `S.No.` width (`SERIAL_COL_WIDTH = 48.dp`) as `OverviewStockTable`
 *   so toggling between Overview and Detailed never shifts the serial column boundary.
 * - Enforces `Modifier.padding(horizontal = 10.dp, vertical = 8.dp)` on every header cell and data cell.
 * - Hides any column (`Article`, `Color`, `Brand`, `Name`, `Party`, `Age`, `Total In`, `Total Out`, etc.)
 *   that is null or blank across 100% of rows (`EmptyColumnAnalyzer`).
 * - Strictly hides `Party` when `isAdmin == false`.
 * - Uses `BoxWithConstraints` for 100% minimum width expansion and synchronized horizontal scroll.
 * - Tapping any column header sorts in-memory (`Size` sorts by `size_sort_order`).
 * - Double-tapping any filterable cell (`Article`, `Brand`, `Name`, `Party` [admin only], `Color`,
 *   `Size`, `Barcode`, `Brandcode`) immediately applies that value as an active filter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailedStockTable(
    rows: List<DetailedStockRow>,
    columnVisibility: TableColumnVisibility,
    sortState: TableSortState,
    isAdmin: Boolean,
    onColumnHeaderTap: (String) -> Unit,
    onCellDoubleTapFilter: (FilterField, String) -> Unit
) {
    val visibleSpecs = remember(columnVisibility, isAdmin) {
        buildList {
            if (columnVisibility.showArticle) {
                add(DetailedColSpec(SortableColumns.ARTICLE, "Article", 96.dp, false, FilterField.ARTICLE))
            }
            if (columnVisibility.showBrand) {
                add(DetailedColSpec(SortableColumns.BRAND, "Brand", 110.dp, false, FilterField.BRAND))
            }
            if (columnVisibility.showName) {
                add(DetailedColSpec(SortableColumns.NAME, "Name", 136.dp, false, FilterField.NAME))
            }
            if (columnVisibility.showColor) {
                add(DetailedColSpec(SortableColumns.COLOR, "Color", 92.dp, false, FilterField.COLOR))
            }
            if (columnVisibility.showSizeInDetailed) {
                add(DetailedColSpec(SortableColumns.SIZE, "Size", 72.dp, false, FilterField.SIZE))
            }
            if (columnVisibility.showQuantity) {
                add(DetailedColSpec(SortableColumns.QUANTITY, "Qty", 74.dp, true))
            }
            if (columnVisibility.showTotalIn) {
                add(DetailedColSpec(SortableColumns.TOTAL_IN, "Total In", 82.dp, true))
            }
            if (columnVisibility.showTotalOut) {
                add(DetailedColSpec(SortableColumns.TOTAL_OUT, "Total Out", 86.dp, true))
            }
            if (columnVisibility.showAgeDays) {
                add(DetailedColSpec(SortableColumns.AGE_DAYS, "Age (d)", 78.dp, true))
            }
            if (columnVisibility.showMrp) {
                add(DetailedColSpec(SortableColumns.MRP, "MRP", 82.dp, true))
            }
            if (isAdmin && columnVisibility.showParty) {
                add(DetailedColSpec(SortableColumns.PARTY, "Party", 130.dp, false, FilterField.PARTY))
            }
            if (columnVisibility.showBarcode) {
                add(DetailedColSpec(SortableColumns.BARCODE, "Barcode", 118.dp, false, FilterField.BARCODE))
            }
            if (columnVisibility.showBrandcode) {
                add(DetailedColSpec(SortableColumns.BRANDCODE, "Brandcode", 122.dp, false, FilterField.BARCODE))
            }
        }
    }

    val horizontalScrollState = rememberScrollState()
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenMaxWidth = maxWidth
        val totalBaseDataWidth = visibleSpecs.fold(0.dp) { acc, spec -> acc + spec.baseWidth }
        val totalIntrinsicWidth = SERIAL_COL_WIDTH + totalBaseDataWidth

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
                    items = rows,
                    key = { _, item -> "${item.product.id}_${item.stock.id}" }
                ) { rowIndex, row ->
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
                            val rawValue = when (spec.id) {
                                SortableColumns.ARTICLE -> row.product.article.orEmpty()
                                SortableColumns.BRAND -> row.product.brand
                                SortableColumns.NAME -> row.product.name
                                SortableColumns.COLOR -> row.product.color.orEmpty()
                                SortableColumns.SIZE -> row.stock.size
                                SortableColumns.QUANTITY -> row.stock.quantity.toString()
                                SortableColumns.TOTAL_IN -> row.stock.totalIn.toString()
                                SortableColumns.TOTAL_OUT -> row.stock.totalOut.toString()
                                SortableColumns.AGE_DAYS -> row.stock.ageDays?.toString().orEmpty()
                                SortableColumns.MRP -> "₹${row.product.mrp}"
                                SortableColumns.PARTY -> if (isAdmin) row.product.party else ""
                                SortableColumns.BARCODE -> row.product.barcode
                                SortableColumns.BRANDCODE -> row.product.brandcode
                                else -> ""
                            }

                            val isChipHighlighted = spec.filterField != null &&
                                columnVisibility.chipHighlightedFields.contains(spec.filterField)

                            val cellModifier = Modifier
                                .width(colWidth)
                                .border(width = 0.5.dp, color = borderColor)
                                .then(
                                    if (spec.filterField != null && rawValue.isNotBlank()) {
                                        Modifier.combinedClickable(
                                            onClick = {},
                                            onDoubleClick = {
                                                onCellDoubleTapFilter(spec.filterField, rawValue)
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
                                    text = rawValue.ifBlank { "-" },
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
