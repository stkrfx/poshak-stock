package com.poshak.stock.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.poshak.stock.ui.main.components.CentralChipSection
import com.poshak.stock.ui.main.components.DetailedStockTable
import com.poshak.stock.ui.main.components.FilterBottomSheet
import com.poshak.stock.ui.main.components.OverviewStockTable
import com.poshak.stock.ui.main.components.RecentSearchesView
import com.poshak.stock.ui.main.components.TopStatusAndModeBar
import com.poshak.stock.ui.offline.OfflineDownloadsSheet
import com.poshak.stock.ui.offline.OfflineDownloadsViewModel

/**
 * Unified Main Screen (`StockMainScreen`) for both normal app usage and Home-Screen Widget launches.
 *
 * Combines:
 * 1. `TopStatusAndModeBar` (Online/Offline indicator, Role badge, Search bar trigger, Overview/Detailed switch).
 * 2. `CentralChipSection` (Applied Filter chips + 100% frontend-computed Common Value chips & MRP tonal badge).
 * 3. `OverviewStockTable` & `DetailedStockTable` (fixed `48.dp` `S.No.`, internal padding, dynamic empty
 *    column hiding, 100% minimum width expansion, frontend header sorting, and double-tap cell filtering).
 * 4. `RecentSearchesView` when no active search is present.
 * 5. `FilterBottomSheet` & `OfflineDownloadsSheet`.
 * 6. Hierarchical `BackHandler` popping the in-screen `FilterState` history stack before returning to Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockMainScreen(
    viewModel: StockMainViewModel,
    offlineDownloadsViewModel: OfflineDownloadsViewModel,
    barcodeScannerOverlay: @Composable (onCodeScanned: (String) -> Unit, onClose: () -> Unit) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val offlineUiState by offlineDownloadsViewModel.uiState.collectAsState()

    // Intercept Back press whenever an overlay is open, filter history exists, or an active search is shown
    val shouldInterceptBack = uiState.isScannerOpen ||
        uiState.isFilterSheetOpen ||
        uiState.isOfflineDownloadsSheetOpen ||
        uiState.filterHistoryDepth > 0 ||
        uiState.hasActiveSearch

    BackHandler(enabled = shouldInterceptBack) {
        viewModel.handleBackPress()
    }

    if (uiState.isScannerOpen) {
        barcodeScannerOverlay(
            { scannedCode -> viewModel.onBarcodeScanned(scannedCode) },
            { viewModel.closeBarcodeScanner() }
        )
        return
    }

    Scaffold(
        topBar = {
            TopStatusAndModeBar(
                isAdmin = uiState.isAdmin,
                isOnline = uiState.isOnline,
                preferOfflineMode = uiState.preferOfflineMode,
                hasActiveSearch = uiState.hasActiveSearch,
                activeFilterSummary = uiState.activeFilter.toDisplaySummary(uiState.isAdmin),
                viewMode = uiState.viewMode,
                totalProducts = uiState.totalProductCount,
                totalQuantity = uiState.totalStockQuantity,
                cachedProductCount = uiState.cachedSummary.productCount,
                onOpenFilterSheet = { viewModel.openFilterBottomSheet(null) },
                onOpenScanner = {
                    viewModel.openBarcodeScanner(ScannerLaunchTarget.MAIN_DIRECT_SEARCH)
                },
                onOpenOfflineDownloads = {
                    offlineDownloadsViewModel.clearMessages()
                    viewModel.openOfflineDownloadsSheet()
                },
                onTogglePreferOfflineMode = { viewModel.togglePreferOfflineMode() },
                onViewModeChange = { viewModel.setTableViewMode(it) },
                onLogoutClick = { viewModel.logout() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.hasActiveSearch) {
                CentralChipSection(
                    activeFilter = uiState.activeFilter,
                    commonValueChips = uiState.commonValueChips,
                    isAdmin = uiState.isAdmin,
                    onSingleTapFocusField = { field ->
                        viewModel.onChipSingleTappedToFocusField(field)
                    },
                    onRemoveAppliedFilter = { field ->
                        viewModel.removeAppliedFilter(field)
                    },
                    onClearAllFilters = {
                        viewModel.clearAllFiltersAndResults()
                    },
                    onDoubleTapPromoteCommonChip = { field, rawValue ->
                        viewModel.applySingleFilterAddition(field, rawValue)
                    }
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isPullRefreshing,
                onRefresh = { viewModel.onPullToRefresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    !uiState.hasActiveSearch -> {
                        RecentSearchesView(
                            recentSearches = uiState.recentSearches,
                            cachedSummary = uiState.cachedSummary,
                            onOpenFilterSheet = { viewModel.openFilterBottomSheet(null) },
                            onOpenScanner = {
                                viewModel.openBarcodeScanner(ScannerLaunchTarget.MAIN_DIRECT_SEARCH)
                            },
                            onOpenOfflineDownloads = {
                                offlineDownloadsViewModel.clearMessages()
                                viewModel.openOfflineDownloadsSheet()
                            },
                            onRecentSearchClick = { item ->
                                viewModel.onRecentSearchClicked(item)
                            }
                        )
                    }

                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.rawProducts.isEmpty() -> {
                        EmptyResultsPlaceholder(
                            errorMessage = uiState.errorMessage,
                            isOffline = !uiState.isOnline || uiState.preferOfflineMode,
                            onModifyFilters = { viewModel.openFilterBottomSheet(null) },
                            onClearFilters = { viewModel.clearAllFiltersAndResults() }
                        )
                    }

                    uiState.viewMode == TableViewMode.OVERVIEW -> {
                        OverviewStockTable(
                            products = uiState.sortedOverviewProducts,
                            columnVisibility = uiState.columnVisibility,
                            sortState = uiState.sortState,
                            isAdmin = uiState.isAdmin,
                            onColumnHeaderTap = { colId -> viewModel.onColumnHeaderTapped(colId) },
                            onCellDoubleTapFilter = { field, value ->
                                viewModel.applySingleFilterAddition(field, value)
                            }
                        )
                    }

                    else -> {
                        DetailedStockTable(
                            rows = uiState.sortedDetailedRows,
                            columnVisibility = uiState.columnVisibility,
                            sortState = uiState.sortState,
                            isAdmin = uiState.isAdmin,
                            onColumnHeaderTap = { colId -> viewModel.onColumnHeaderTapped(colId) },
                            onCellDoubleTapFilter = { field, value ->
                                viewModel.applySingleFilterAddition(field, value)
                            }
                        )
                    }
                }
            }
        }
    }

    if (uiState.isFilterSheetOpen) {
        FilterBottomSheet(
            isAdmin = uiState.isAdmin,
            draftFilter = uiState.draftFilter,
            effectivePrimaryContextFilter = viewModel.buildEffectivePrimaryContextFilter(uiState),
            draftTexts = uiState.draftTexts,
            suggestionsByField = uiState.suggestionsByField,
            loadingSuggestionsField = uiState.loadingSuggestionsField,
            focusedField = uiState.focusedSheetField,
            errorMessage = uiState.errorMessage,
            onConsumeFocusedField = { viewModel.consumeFocusedSheetField() },
            onDraftFieldTextChanged = { field, text ->
                viewModel.onDraftFieldTextChanged(field, text)
            },
            onSuggestionSelected = { field, suggestion ->
                viewModel.onSuggestionSelected(field, suggestion)
            },
            onClearDraftField = { field -> viewModel.clearDraftField(field) },
            onClearAllDraftFilters = { viewModel.clearAllDraftFilters() },
            onDraftFieldUnfocused = { field -> viewModel.onDraftFieldUnfocused(field) },
            onOpenBarcodeScannerForField = {
                viewModel.openBarcodeScanner(ScannerLaunchTarget.FILTER_SHEET_BARCODE_FIELD)
            },
            onApplyFiltersAndSearch = { viewModel.applyDraftFiltersAndSearch() },
            onDismiss = { viewModel.closeFilterBottomSheet() }
        )
    }

    if (uiState.isOfflineDownloadsSheetOpen) {
        OfflineDownloadsSheet(
            uiState = offlineUiState,
            activeFilter = uiState.activeFilter,
            isAdmin = uiState.isAdmin,
            isOnline = uiState.isOnline,
            onDownloadFullStock = { offlineDownloadsViewModel.downloadFullStock() },
            onDownloadCurrentFilterScope = {
                offlineDownloadsViewModel.downloadFilteredScope(uiState.activeFilter)
            },
            onRunIncrementalSync = { offlineDownloadsViewModel.runIncrementalDeltaSyncNow() },
            onDeleteScope = { scopeId -> offlineDownloadsViewModel.deleteScope(scopeId) },
            onOpenFilterSheetToSelectScope = {
                viewModel.closeOfflineDownloadsSheet()
                viewModel.openFilterBottomSheet(null)
            },
            onDismiss = { viewModel.closeOfflineDownloadsSheet() }
        )
    }
}

@Composable
private fun EmptyResultsPlaceholder(
    errorMessage: String?,
    isOffline: Boolean,
    onModifyFilters: () -> Unit,
    onClearFilters: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Inventory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = errorMessage ?: "No Matching Stock Found",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = if (isOffline) {
                "No matching items found in local Room cache for these filters."
            } else {
                "No products matched all applied exact-match filters."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        Button(
            onClick = onModifyFilters,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Text("Edit Search Filters")
        }
        Button(
            onClick = onClearFilters,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(top = 8.dp)
        ) {
            Text("Clear Filters")
        }
    }
}
