package com.poshak.stock.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poshak.stock.ui.main.TableViewMode

/**
 * Top header bar for `StockMainScreen`:
 * - Displays app title, role indicator (`ADMIN` vs `EMPLOYEE`), live `Online`/`Offline` pill,
 *   and quick actions for Offline Downloads, Barcode Scanner, and Logout.
 * - Includes the unified search trigger bar (opening `FilterBottomSheet`) and the
 *   `Overview` vs `Detailed` table mode segmented switch when results are active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopStatusAndModeBar(
    isAdmin: Boolean,
    isOnline: Boolean,
    preferOfflineMode: Boolean,
    hasActiveSearch: Boolean,
    activeFilterSummary: String,
    viewMode: TableViewMode,
    totalProducts: Int,
    totalQuantity: Int,
    cachedProductCount: Int,
    onOpenFilterSheet: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenOfflineDownloads: () -> Unit,
    onTogglePreferOfflineMode: () -> Unit,
    onViewModeChange: (TableViewMode) -> Unit,
    onLogoutClick: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Title + Role Badge + Online/Offline Status + Action Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Stock",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    // Role Badge
                    Surface(
                        color = if (isAdmin) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isAdmin) "ADMIN" else "STAFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isAdmin) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Connectivity Pill
                    val effectiveOnline = isOnline && !preferOfflineMode
                    Surface(
                        color = if (effectiveOnline) {
                            Color(0xFFE6F4EA)
                        } else {
                            Color(0xFFFEF7E0)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { onTogglePreferOfflineMode() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (effectiveOnline) Color(0xFF1E8E3E) else Color(0xFFE37400)
                                    )
                            )
                            Text(
                                text = when {
                                    !isOnline -> "Offline ($cachedProductCount)"
                                    preferOfflineMode -> "Local Mode ($cachedProductCount)"
                                    else -> "Online"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (effectiveOnline) Color(0xFF137333) else Color(0xFFB06000)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenOfflineDownloads) {
                        Icon(
                            imageVector = if (preferOfflineMode || !isOnline) {
                                Icons.Default.CloudOff
                            } else {
                                Icons.Default.CloudDownload
                            },
                            contentDescription = "Offline Stock Downloads",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onOpenScanner) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Barcode / Brandcode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onLogoutClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign Out",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Second Row: Search Trigger Bar (opens FilterBottomSheet)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenFilterSheet),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Filters",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (activeFilterSummary.isNotBlank()) {
                                activeFilterSummary
                            } else if (isAdmin) {
                                "Search Article, Brand, Name, Barcode, Party..."
                            } else {
                                "Search Article, Brand, Name, Barcode..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (activeFilterSummary.isNotBlank()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Open Filter Sheet",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Third Row (visible when search is active): Overview vs Detailed Segmented Toggle + Summary Counts
            if (hasActiveSearch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = viewMode == TableViewMode.OVERVIEW,
                            onClick = { onViewModeChange(TableViewMode.OVERVIEW) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.TableChart,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        ) {
                            Text(
                                text = "Overview",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        SegmentedButton(
                            selected = viewMode == TableViewMode.DETAILED,
                            onClick = { onViewModeChange(TableViewMode.DETAILED) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.ViewList,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        ) {
                            Text(
                                text = "Detailed",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = true,
                            onClick = onOpenFilterSheet,
                            label = {
                                Text(
                                    text = "$totalProducts Items • Qty: $totalQuantity",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        }
    }
}
