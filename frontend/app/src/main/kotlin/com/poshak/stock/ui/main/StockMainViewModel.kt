package com.poshak.stock.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poshak.stock.data.local.dao.CachedInventorySummary
import com.poshak.stock.data.local.entity.SearchHistoryEntity
import com.poshak.stock.data.repository.AuthRepository
import com.poshak.stock.data.repository.AutocompleteRepository
import com.poshak.stock.data.repository.OfflineSyncRepository
import com.poshak.stock.data.repository.StockRepository
import com.poshak.stock.data.sync.NetworkQualityChecker
import com.poshak.stock.domain.logic.CommonValuesCalculator
import com.poshak.stock.domain.logic.EmptyColumnAnalyzer
import com.poshak.stock.domain.logic.FilterPreconditionValidator
import com.poshak.stock.domain.logic.FrontendTableSorter
import com.poshak.stock.domain.logic.TableColumnVisibility
import com.poshak.stock.domain.model.CommonValueChip
import com.poshak.stock.domain.model.DetailedStockRow
import com.poshak.stock.domain.model.FilterField
import com.poshak.stock.domain.model.FilterState
import com.poshak.stock.domain.model.ProductWithStocks
import com.poshak.stock.domain.model.TableSortState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TableViewMode {
    OVERVIEW,
    DETAILED
}

enum class ScannerLaunchTarget {
    MAIN_DIRECT_SEARCH,
    FILTER_SHEET_BARCODE_FIELD
}

data class StockMainUiState(
    val isAdmin: Boolean = false,
    val userName: String = "",
    val isOnline: Boolean = true,
    val preferOfflineMode: Boolean = false,
    val viewMode: TableViewMode = TableViewMode.OVERVIEW,

    // Active applied filter & results state
    val activeFilter: FilterState = FilterState(),
    val hasActiveSearch: Boolean = false,
    val isLoading: Boolean = false,
    val isPullRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val rawProducts: List<ProductWithStocks> = emptyList(),
    val sortedOverviewProducts: List<ProductWithStocks> = emptyList(),
    val sortedDetailedRows: List<DetailedStockRow> = emptyList(),
    val sortState: TableSortState = TableSortState(),
    val commonValueChips: List<CommonValueChip> = emptyList(),
    val columnVisibility: TableColumnVisibility = EmptyColumnAnalyzer().analyze(
        emptyList(),
        FilterState(),
        emptyList(),
        false
    ),
    val lastSyncedAt: String = "",
    val isResultFromOfflineCache: Boolean = false,
    val filterHistoryDepth: Int = 0,

    // Modal Bottom Sheet Filter Form state
    val isFilterSheetOpen: Boolean = false,
    val focusedSheetField: FilterField? = null,
    val draftFilter: FilterState = FilterState(),
    val draftTexts: Map<FilterField, String> = emptyMap(),
    val suggestionsByField: Map<FilterField, List<String>> = emptyMap(),
    val loadingSuggestionsField: FilterField? = null,

    // Other overlays & Home / Recent Searches state
    val isOfflineDownloadsSheetOpen: Boolean = false,
    val isScannerOpen: Boolean = false,
    val scannerTarget: ScannerLaunchTarget = ScannerLaunchTarget.MAIN_DIRECT_SEARCH,
    val recentSearches: List<SearchHistoryEntity> = emptyList(),
    val cachedSummary: CachedInventorySummary = CachedInventorySummary(0, 0, 0)
) {
    val totalProductCount: Int
        get() = rawProducts.size

    val totalVariantCount: Int
        get() = rawProducts.sumOf { it.stocks.size }

    val totalStockQuantity: Int
        get() = rawProducts.sumOf { it.totalQuantity }
}

/**
 * Primary ViewModel for `StockMainScreen`.
 *
 * Implements:
 * 1. In-screen `FilterState` history stack for Back navigation across filter refinements (Section 9.7).
 * 2. Strict Non-Solo Rule for `Size` & `Color`:
 *    - `Size` and `Color` cannot be searched alone and do not query autocomplete until at least one
 *      primary filter (`Article`, `Brand`, `Barcode/Brandcode`, `Name`, or `Party` [admin only]) is active.
 *    - Removing the last active primary filter when `Size` and/or `Color` remain immediately clears
 *      the entire query, all remaining filters, and the result state (Section 1.5 & 9.3).
 * 3. Uniform Autocomplete & Exact-Match-On-Unfocus (Section 1.4 & 9.2).
 * 4. 100% Frontend Sorting (`FrontendTableSorter`), Common Value Chips (`CommonValuesCalculator`),
 *    and Mandatory Dynamic Empty Column Hiding (`EmptyColumnAnalyzer`).
 * 5. Unified Home-Screen Widget deep-link handling (`ACTION_OPEN_ARTICLE_SEARCH` and
 *    `ACTION_OPEN_BARCODE_SCANNER`) with zero validation exceptions (Section 9.6).
 */
@HiltViewModel
class StockMainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val stockRepository: StockRepository,
    private val autocompleteRepository: AutocompleteRepository,
    private val offlineSyncRepository: OfflineSyncRepository,
    private val networkQualityChecker: NetworkQualityChecker,
    private val validator: FilterPreconditionValidator,
    private val tableSorter: FrontendTableSorter,
    private val commonValuesCalculator: CommonValuesCalculator,
    private val emptyColumnAnalyzer: EmptyColumnAnalyzer
) : ViewModel() {

    private data class FilterHistorySnapshot(
        val filter: FilterState,
        val rawProducts: List<ProductWithStocks>,
        val sortState: TableSortState,
        val lastSyncedAt: String,
        val isFromOfflineCache: Boolean
    )

    private val filterHistoryStack = ArrayDeque<FilterHistorySnapshot>()
    private val autocompleteJobs = mutableMapOf<FilterField, Job>()
    private var searchJob: Job? = null

    private val _uiState = MutableStateFlow(
        StockMainUiState(
            isAdmin = authRepository.isAdmin(),
            userName = authRepository.userName.value,
            isOnline = networkQualityChecker.isCurrentlyOnline()
        )
    )
    val uiState: StateFlow<StockMainUiState> = _uiState.asStateFlow()

    init {
        // Observe user role & name changes
        viewModelScope.launch {
            authRepository.userRole.collect { role ->
                val isAdmin = role.equals("admin", ignoreCase = true)
                _uiState.update { current ->
                    val sanitizedActive = current.activeFilter.enforceRoleAndNonSoloRule(isAdmin)
                    val sanitizedDraft = current.draftFilter.enforceRoleAndNonSoloRule(isAdmin)
                    recomputeDerivedTableState(
                        current.copy(
                            isAdmin = isAdmin,
                            userName = authRepository.userName.value,
                            activeFilter = sanitizedActive,
                            draftFilter = sanitizedDraft
                        )
                    )
                }
            }
        }

        // Observe real-time network connectivity
        viewModelScope.launch {
            networkQualityChecker.isOnlineFlow.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
                if (online) {
                    stockRepository.refreshSearchHistoryFromServer()
                }
            }
        }

        // Observe latest 40 search history items from Room
        viewModelScope.launch {
            stockRepository.observeRecentSearches(40).collect { history ->
                _uiState.update { it.copy(recentSearches = history) }
            }
        }

        // Observe cached inventory counts in Room
        viewModelScope.launch {
            offlineSyncRepository.observeCachedInventorySummary().collect { summary ->
                _uiState.update { it.copy(cachedSummary = summary) }
            }
        }

        // Initial background refresh of search history if online
        viewModelScope.launch {
            stockRepository.refreshSearchHistoryFromServer()
        }
    }

    // =========================================================================
    // VIEW MODE, OFFLINE MODE & SORTING
    // =========================================================================

    fun setTableViewMode(mode: TableViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun togglePreferOfflineMode() {
        _uiState.update { it.copy(preferOfflineMode = !it.preferOfflineMode) }
    }

    /**
     * Tapping any column header sorts the in-memory result list via `FrontendTableSorter`:
     * - 1st tap: Ascending (`▲`)
     * - 2nd tap on same header: Descending (`▼`)
     * - Tap on a different header: Ascending (`▲`) on the new column.
     */
    fun onColumnHeaderTapped(columnId: String) {
        _uiState.update { current ->
            val nextSort = current.sortState.onColumnHeaderTapped(columnId)
            recomputeDerivedTableState(current.copy(sortState = nextSort))
        }
    }

    // =========================================================================
    // MODAL BOTTOM SHEET FILTER FORM & AUTOCOMPLETE
    // =========================================================================

    /**
     * Opens the Search Filter Modal Bottom Sheet and optionally focuses [initialFocusField].
     */
    fun openFilterBottomSheet(initialFocusField: FilterField? = null) {
        val current = _uiState.value
        val isAdmin = current.isAdmin
        val baseDraft = current.activeFilter.enforceRoleAndNonSoloRule(isAdmin)
        val initialTexts = buildDraftTextMap(baseDraft, isAdmin)

        _uiState.update {
            it.copy(
                isFilterSheetOpen = true,
                focusedSheetField = initialFocusField,
                draftFilter = baseDraft,
                draftTexts = initialTexts,
                errorMessage = null
            )
        }

        // If opening with a pre-populated field, trigger initial autocomplete suggestions for it
        if (initialFocusField != null && initialFocusField != FilterField.BARCODE) {
            val currentText = initialTexts[initialFocusField].orEmpty()
            if (currentText.isNotBlank()) {
                queryFieldSuggestions(initialFocusField, currentText)
            }
        }
    }

    fun closeFilterBottomSheet() {
        _uiState.update {
            it.copy(
                isFilterSheetOpen = false,
                focusedSheetField = null
            )
        }
    }

    fun consumeFocusedSheetField() {
        _uiState.update { it.copy(focusedSheetField = null) }
    }

    /**
     * Called as the user types in a filter field inside `FilterBottomSheet`.
     */
    fun onDraftFieldTextChanged(field: FilterField, newText: String) {
        val isAdmin = _uiState.value.isAdmin
        if (field == FilterField.PARTY && !isAdmin) return

        // For Barcode/Brandcode, typing directly updates the draft filter value
        if (field == FilterField.BARCODE) {
            val cleanCode = newText.trim()
            _uiState.update { state ->
                val nextDraft = state.draftFilter.withField(FilterField.BARCODE, cleanCode)
                val enforcedDraft = enforceSecondaryClearIfNoPrimaryInDraft(nextDraft, isAdmin)
                state.copy(
                    draftFilter = enforcedDraft,
                    draftTexts = syncSecondaryTextsIfCleared(
                        state.draftTexts + (FilterField.BARCODE to newText),
                        enforcedDraft
                    )
                )
            }
            return
        }

        // For autocomplete fields:
        // If user clears the text, immediately clear the committed draft value for that field
        // and enforce the non-solo rule if no primary filters remain.
        _uiState.update { state ->
            val updatedDraftTexts = state.draftTexts.toMutableMap().apply {
                put(field, newText)
            }
            var updatedDraftFilter = state.draftFilter
            if (newText.isBlank()) {
                updatedDraftFilter = updatedDraftFilter.withField(field, "")
                updatedDraftFilter = enforceSecondaryClearIfNoPrimaryInDraft(updatedDraftFilter, isAdmin)
            }
            val syncedTexts = syncSecondaryTextsIfCleared(updatedDraftTexts, updatedDraftFilter)
            val updatedSuggestions = state.suggestionsByField.toMutableMap().apply {
                if (newText.isBlank()) {
                    put(field, emptyList())
                }
                if (!updatedDraftFilter.hasPrimaryFilter(isAdmin)) {
                    put(FilterField.COLOR, emptyList())
                    put(FilterField.SIZE, emptyList())
                }
            }

            state.copy(
                draftFilter = updatedDraftFilter,
                draftTexts = syncedTexts,
                suggestionsByField = updatedSuggestions
            )
        }

        if (newText.trim().isNotEmpty()) {
            queryFieldSuggestions(field, newText)
        } else {
            autocompleteJobs[field]?.cancel()
        }
    }

    /**
     * Dedicated trailing Clear (`X`) button handler for a single field in `FilterBottomSheet`.
     */
    fun clearDraftField(field: FilterField) {
        autocompleteJobs[field]?.cancel()
        val isAdmin = _uiState.value.isAdmin
        _uiState.update { state ->
            val nextDraft = enforceSecondaryClearIfNoPrimaryInDraft(
                state.draftFilter.withField(field, ""),
                isAdmin
            )
            val nextTexts = state.draftTexts.toMutableMap().apply {
                put(field, "")
            }
            val syncedTexts = syncSecondaryTextsIfCleared(nextTexts, nextDraft)
            val nextSuggestions = state.suggestionsByField.toMutableMap().apply {
                put(field, emptyList())
                if (!nextDraft.hasPrimaryFilter(isAdmin)) {
                    put(FilterField.COLOR, emptyList())
                    put(FilterField.SIZE, emptyList())
                }
            }
            state.copy(
                draftFilter = nextDraft,
                draftTexts = syncedTexts,
                suggestionsByField = nextSuggestions
            )
        }
    }

    /**
     * Clears all fields inside `FilterBottomSheet`.
     */
    fun clearAllDraftFilters() {
        autocompleteJobs.values.forEach { it.cancel() }
        _uiState.update { state ->
            state.copy(
                draftFilter = FilterState(),
                draftTexts = emptyMap(),
                suggestionsByField = emptyMap(),
                loadingSuggestionsField = null
            )
        }
    }

    /**
     * Explicit tap on a suggestion in the dropdown: selects the canonical suggestion value,
     * keeps the dropdown suggestions available, and unlocks Size/Color if a primary filter was selected.
     */
    fun onSuggestionSelected(field: FilterField, suggestionValue: String) {
        val isAdmin = _uiState.value.isAdmin
        if (field == FilterField.PARTY && !isAdmin) return

        val clean = suggestionValue.trim()
        _uiState.update { state ->
            val nextDraft = state.draftFilter.withField(field, clean)
            val nextTexts = state.draftTexts.toMutableMap().apply {
                put(field, clean)
            }
            state.copy(
                draftFilter = nextDraft,
                draftTexts = nextTexts
            )
        }
        // Keep suggestions fresh for the selected prefix so persistent dropdown rule is honored
        queryFieldSuggestions(field, clean)
    }

    /**
     * Implements the Mandatory Exact-Match-On-Unfocus / Blur Rule (Section 1.4 & 9.2):
     * When the user unfocuses/leaves an autocomplete input field without tapping a dropdown item:
     * - If an exact case-insensitive match exists in that field's current suggestion list,
     *   it is automatically selected as the filter value.
     * - If no exact match exists, the input field automatically resets to blank (`""`).
     */
    fun onDraftFieldUnfocused(field: FilterField) {
        val state = _uiState.value
        val isAdmin = state.isAdmin
        if (field == FilterField.BARCODE) {
            val cleanCode = state.draftTexts[FilterField.BARCODE]?.trim().orEmpty()
            _uiState.update { cur ->
                val nextDraft = enforceSecondaryClearIfNoPrimaryInDraft(
                    cur.draftFilter.withField(FilterField.BARCODE, cleanCode),
                    isAdmin
                )
                cur.copy(
                    draftFilter = nextDraft,
                    draftTexts = syncSecondaryTextsIfCleared(
                        cur.draftTexts + (FilterField.BARCODE to cleanCode),
                        nextDraft
                    )
                )
            }
            return
        }

        val typedText = state.draftTexts[field].orEmpty()
        val committedValue = state.draftFilter.getValue(field)
        val suggestions = state.suggestionsByField[field].orEmpty()

        val resolvedValue = validator.resolveValueOnUnfocus(
            typedText = typedText,
            currentCommittedValue = committedValue,
            currentSuggestions = suggestions
        )

        _uiState.update { cur ->
            val updatedDraft = enforceSecondaryClearIfNoPrimaryInDraft(
                cur.draftFilter.withField(field, resolvedValue),
                isAdmin
            )
            val updatedTexts = cur.draftTexts.toMutableMap().apply {
                put(field, resolvedValue)
            }
            val syncedTexts = syncSecondaryTextsIfCleared(updatedTexts, updatedDraft)
            cur.copy(
                draftFilter = updatedDraft,
                draftTexts = syncedTexts
            )
        }
    }

    /**
     * Resolves all draft fields using the Exact-Match-On-Unfocus rule, validates that at least
     * one primary filter is present (no solo Size/Color), and executes the stock search.
     */
    fun applyDraftFiltersAndSearch() {
        val current = _uiState.value
        val isAdmin = current.isAdmin

        // First resolve all autocomplete inputs in case one was still focused when tapping Search
        var resolvedDraft = current.draftFilter
        val resolvedTexts = current.draftTexts.toMutableMap()

        for (field in FilterField.entries) {
            if (field == FilterField.PARTY && !isAdmin) continue
            if (field == FilterField.BARCODE) {
                val code = resolvedTexts[FilterField.BARCODE]?.trim().orEmpty()
                resolvedDraft = resolvedDraft.withField(FilterField.BARCODE, code)
                resolvedTexts[FilterField.BARCODE] = code
            } else {
                val typed = resolvedTexts[field].orEmpty()
                val committed = resolvedDraft.getValue(field)
                val suggestions = current.suggestionsByField[field].orEmpty()
                val resolved = validator.resolveValueOnUnfocus(typed, committed, suggestions)
                resolvedDraft = resolvedDraft.withField(field, resolved)
                resolvedTexts[field] = resolved
            }
        }

        resolvedDraft = resolvedDraft.enforceRoleAndNonSoloRule(isAdmin)

        if (!validator.canExecuteSearch(resolvedDraft, isAdmin)) {
            // If no valid primary filter is selected, reset secondary filters and clear if empty
            _uiState.update {
                it.copy(
                    draftFilter = FilterState(),
                    draftTexts = emptyMap(),
                    errorMessage = if ( isAdmin ) {
                        "Select at least one primary filter (Article, Brand, Barcode, Name, or Party)."
                    } else {
                        "Select at least one primary filter (Article, Brand, Barcode, or Name)."
                    }
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isFilterSheetOpen = false,
                focusedSheetField = null,
                draftFilter = resolvedDraft,
                draftTexts = buildDraftTextMap(resolvedDraft, isAdmin),
                errorMessage = null
            )
        }

        executeSearchWithFilter(
            targetFilter = resolvedDraft,
            pushCurrentToHistory = current.hasActiveSearch && current.activeFilter != resolvedDraft,
            recordHistory = true
        )
    }

    private fun queryFieldSuggestions(field: FilterField, rawPrefix: String) {
        autocompleteJobs[field]?.cancel()
        val prefix = rawPrefix.trim()
        if (prefix.isEmpty()) return

        autocompleteJobs[field] = viewModelScope.launch {
            delay(140L) // Fast debounce for responsive typing
            val snapshot = _uiState.value
            val isAdmin = snapshot.isAdmin

            // Strict Non-Solo Gating for Color & Size
            val isSecondary = field == FilterField.COLOR || field == FilterField.SIZE
            val effectiveContextFilter = buildEffectivePrimaryContextFilter(snapshot)
            if (isSecondary && !effectiveContextFilter.hasPrimaryFilter(isAdmin)) {
                _uiState.update { state ->
                    state.copy(
                        suggestionsByField = state.suggestionsByField + (field to emptyList())
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(loadingSuggestionsField = field) }
            val suggestions = autocompleteRepository.getSuggestions(
                field = field.queryKey,
                rawPrefix = prefix,
                contextFilter = effectiveContextFilter,
                preferOfflineOnly = snapshot.preferOfflineMode
            )
            _uiState.update { state ->
                state.copy(
                    suggestionsByField = state.suggestionsByField + (field to suggestions),
                    loadingSuggestionsField = if (state.loadingSuggestionsField == field) null else state.loadingSuggestionsField
                )
            }
        }
    }

    /**
     * Builds the effective primary filter context inside `FilterBottomSheet` so that even if the user
     * typed an exact match into a primary field and immediately tapped `Color` or `Size`, the
     * primary filter context is already resolved.
     */
    fun buildEffectivePrimaryContextFilter(state: StockMainUiState = _uiState.value): FilterState {
        val isAdmin = state.isAdmin
        var filter = state.draftFilter
        val primaryFields = listOf(
            FilterField.ARTICLE,
            FilterField.BRAND,
            FilterField.BARCODE,
            FilterField.NAME,
            FilterField.PARTY
        )
        for (pf in primaryFields) {
            if (pf == FilterField.PARTY && !isAdmin) continue
            val currentVal = filter.getValue(pf)
            if (currentVal.isBlank()) {
                val typed = state.draftTexts[pf]?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    if (pf == FilterField.BARCODE) {
                        filter = filter.withField(FilterField.BARCODE, typed)
                    } else {
                        val resolved = validator.resolveValueOnUnfocus(
                            typedText = typed,
                            currentCommittedValue = "",
                            currentSuggestions = state.suggestionsByField[pf].orEmpty()
                        )
                        if (resolved.isNotBlank()) {
                            filter = filter.withField(pf, resolved)
                        }
                    }
                }
            }
        }
        return filter
    }

    // =========================================================================
    // APPLIED FILTER CHIPS, COMMON VALUE CHIPS & DOUBLE-TAP TABLE CELLS
    // =========================================================================

    /**
     * Single-tap on an Applied Filter chip or a filterable Common Value chip:
     * Opens the Filter Bottom Sheet and immediately focuses that specific filter field's input (Section 9.4).
     */
    fun onChipSingleTappedToFocusField(field: FilterField) {
        val isAdmin = _uiState.value.isAdmin
        if (field == FilterField.PARTY && !isAdmin) return
        openFilterBottomSheet(initialFocusField = field)
    }

    /**
     * Double-tap on an unapplied filterable Common Value chip (Section 9.4.2)
     * OR double-tap on a filterable cell in the Overview/Detailed table (Section 9.5.4):
     * Immediately promotes/applies that value as an active filter and pushes the previous state
     * onto the in-screen filter history stack.
     */
    fun applySingleFilterAddition(field: FilterField, value: String) {
        val current = _uiState.value
        val isAdmin = current.isAdmin
        if (field == FilterField.PARTY && !isAdmin) return

        val cleanValue = value.trim()
        if (cleanValue.isEmpty()) return

        // If that exact filter is already applied, no-op
        if (current.activeFilter.getValue(field).equals(cleanValue, ignoreCase = true)) {
            return
        }

        val nextFilter = validator.updateFilterField(
            currentFilter = current.activeFilter,
            field = field,
            newValue = cleanValue,
            isAdmin = isAdmin
        )
        if (!validator.canExecuteSearch(nextFilter, isAdmin)) {
            return
        }

        executeSearchWithFilter(
            targetFilter = nextFilter,
            pushCurrentToHistory = current.hasActiveSearch,
            recordHistory = true
        )
    }

    /**
     * Removes a single Applied Filter chip (`X` icon) and enforces the Mandatory Full-Clear Rule
     * when only `Size` and/or `Color` remain (Section 1.5 & 9.3):
     * - If removing the last active primary filter leaves only `Size` and/or `Color`,
     *   immediately clears the entire filter state (removing the remaining `Size`/`Color` filters too)
     *   and clears the current search results back to the empty/recent-searches state!
     */
    fun removeAppliedFilter(field: FilterField) {
        val current = _uiState.value
        val isAdmin = current.isAdmin
        val updatedFilter = validator.removeFilterField(
            currentFilter = current.activeFilter,
            fieldToRemove = field,
            isAdmin = isAdmin
        )

        if (!updatedFilter.hasPrimaryFilter(isAdmin)) {
            // Mandatory Full Query Clear when zero primary filters remain
            clearAllFiltersAndResults()
            return
        }

        executeSearchWithFilter(
            targetFilter = updatedFilter,
            pushCurrentToHistory = true,
            recordHistory = true
        )
    }

    /**
     * Clears all active filters, clears the in-screen filter history stack, and returns to the
     * root Home / Recent Searches state.
     */
    fun clearAllFiltersAndResults() {
        searchJob?.cancel()
        filterHistoryStack.clear()
        _uiState.update { current ->
            recomputeDerivedTableState(
                current.copy(
                    activeFilter = FilterState(),
                    draftFilter = FilterState(),
                    draftTexts = emptyMap(),
                    hasActiveSearch = false,
                    isLoading = false,
                    isPullRefreshing = false,
                    errorMessage = null,
                    rawProducts = emptyList(),
                    sortState = TableSortState(),
                    filterHistoryDepth = 0
                )
            )
        }
    }

    // =========================================================================
    // STOCK SEARCH EXECUTION, RECENT SEARCHES & PULL-TO-REFRESH
    // =========================================================================

    fun onRecentSearchClicked(item: SearchHistoryEntity) {
        val isAdmin = _uiState.value.isAdmin
        val filter = stockRepository.parseSearchHistoryFilter(item)
        if (!filter.hasPrimaryFilter(isAdmin)) return

        executeSearchWithFilter(
            targetFilter = filter,
            pushCurrentToHistory = _uiState.value.hasActiveSearch,
            recordHistory = true
        )
    }

    /**
     * Pull-to-Refresh handler with `/v1/health` connection quality gate (Section 5.4).
     */
    fun onPullToRefresh() {
        val current = _uiState.value
        if (!current.hasActiveSearch || !current.activeFilter.hasPrimaryFilter(current.isAdmin)) {
            viewModelScope.launch {
                _uiState.update { it.copy(isPullRefreshing = true) }
                if (networkQualityChecker.isServerHealthyAndReachable()) {
                    offlineSyncRepository.runIncrementalSync()
                    stockRepository.refreshSearchHistoryFromServer()
                }
                _uiState.update { it.copy(isPullRefreshing = false) }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isPullRefreshing = true, errorMessage = null) }
            val serverReachable = networkQualityChecker.isServerHealthyAndReachable()
            val result = stockRepository.searchStock(
                rawFilter = current.activeFilter,
                preferOfflineMode = !serverReachable || current.preferOfflineMode
            )
            result.onSuccess { searchRes ->
                _uiState.update { state ->
                    recomputeDerivedTableState(
                        state.copy(
                            isPullRefreshing = false,
                            rawProducts = searchRes.products,
                            lastSyncedAt = searchRes.syncedAt,
                            isResultFromOfflineCache = searchRes.isFromOfflineCache
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isPullRefreshing = false,
                        errorMessage = err.localizedMessage ?: "Failed to refresh stock results"
                    )
                }
            }
        }
    }

    private fun executeSearchWithFilter(
        targetFilter: FilterState,
        pushCurrentToHistory: Boolean,
        recordHistory: Boolean
    ) {
        val current = _uiState.value
        val isAdmin = current.isAdmin
        val sanitized = targetFilter.enforceRoleAndNonSoloRule(isAdmin)

        if (!sanitized.hasPrimaryFilter(isAdmin)) {
            clearAllFiltersAndResults()
            return
        }

        if (pushCurrentToHistory && current.hasActiveSearch && current.activeFilter.hasPrimaryFilter(isAdmin)) {
            filterHistoryStack.addLast(
                FilterHistorySnapshot(
                    filter = current.activeFilter,
                    rawProducts = current.rawProducts,
                    sortState = current.sortState,
                    lastSyncedAt = current.lastSyncedAt,
                    isFromOfflineCache = current.isResultFromOfflineCache
                )
            )
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeFilter = sanitized,
                    draftFilter = sanitized,
                    draftTexts = buildDraftTextMap(sanitized, isAdmin),
                    hasActiveSearch = true,
                    isLoading = true,
                    errorMessage = null,
                    sortState = TableSortState(),
                    filterHistoryDepth = filterHistoryStack.size
                )
            }

            if (recordHistory) {
                launch { stockRepository.recordSearchHistory(sanitized) }
            }

            val result = stockRepository.searchStock(
                rawFilter = sanitized,
                preferOfflineMode = _uiState.value.preferOfflineMode
            )

            result.onSuccess { searchRes ->
                _uiState.update { state ->
                    recomputeDerivedTableState(
                        state.copy(
                            isLoading = false,
                            rawProducts = searchRes.products,
                            lastSyncedAt = searchRes.syncedAt,
                            isResultFromOfflineCache = searchRes.isFromOfflineCache,
                            errorMessage = null
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    recomputeDerivedTableState(
                        state.copy(
                            isLoading = false,
                            rawProducts = emptyList(),
                            errorMessage = err.localizedMessage ?: "Unable to fetch stock data"
                        )
                    )
                }
            }
        }
    }

    // =========================================================================
    // BARCODE SCANNER, OFFLINE DOWNLOADS SHEET & WIDGET INTENTS
    // =========================================================================

    fun openBarcodeScanner(target: ScannerLaunchTarget) {
        _uiState.update {
            it.copy(
                isScannerOpen = true,
                scannerTarget = target
            )
        }
    }

    fun closeBarcodeScanner() {
        _uiState.update { it.copy(isScannerOpen = false) }
    }

    fun onBarcodeScanned(scannedCode: String) {
        val cleanCode = scannedCode.trim()
        if (cleanCode.isEmpty()) {
            closeBarcodeScanner()
            return
        }

        val current = _uiState.value
        when (current.scannerTarget) {
            ScannerLaunchTarget.FILTER_SHEET_BARCODE_FIELD -> {
                val nextDraft = current.draftFilter.withField(FilterField.BARCODE, cleanCode)
                _uiState.update { state ->
                    state.copy(
                        isScannerOpen = false,
                        isFilterSheetOpen = true,
                        draftFilter = nextDraft,
                        draftTexts = state.draftTexts + (FilterField.BARCODE to cleanCode)
                    )
                }
            }

            ScannerLaunchTarget.MAIN_DIRECT_SEARCH -> {
                _uiState.update {
                    it.copy(
                        isScannerOpen = false,
                        isFilterSheetOpen = false
                    )
                }
                val newFilter = FilterState(barcode = cleanCode)
                executeSearchWithFilter(
                    targetFilter = newFilter,
                    pushCurrentToHistory = current.hasActiveSearch,
                    recordHistory = true
                )
            }
        }
    }

    fun openOfflineDownloadsSheet() {
        _uiState.update { it.copy(isOfflineDownloadsSheetOpen = true) }
    }

    fun closeOfflineDownloadsSheet() {
        _uiState.update { it.copy(isOfflineDownloadsSheetOpen = false) }
    }

    /**
     * Handles unified Home-Screen Widget click intents (Section 9.6):
     * - `ACTION_OPEN_ARTICLE_SEARCH`: opens `FilterBottomSheet` and focuses `Article` input with keyboard.
     * - `ACTION_OPEN_BARCODE_SCANNER`: opens the ML Kit camera scanner directly and renders results
     *   on the exact same `StockMainScreen`.
     */
    fun handleWidgetIntentAction(action: String?) {
        when (action) {
            ACTION_OPEN_ARTICLE_SEARCH -> {
                openFilterBottomSheet(initialFocusField = FilterField.ARTICLE)
            }

            ACTION_OPEN_BARCODE_SCANNER -> {
                openBarcodeScanner(target = ScannerLaunchTarget.MAIN_DIRECT_SEARCH)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            clearAllFiltersAndResults()
            authRepository.logout()
        }
    }

    // =========================================================================
    // BACK NAVIGATION & IN-SCREEN FILTER HISTORY (SECTION 9.7)
    // =========================================================================

    /**
     * Handles hierarchical Back navigation per Section 9.7:
     * 1. If Scanner, Filter Bottom Sheet, or Offline Downloads Sheet is open -> closes overlay first.
     * 2. If user modified filters on the Results view -> pops previous `FilterState` from history stack
     *    and restores previous results immediately from memory.
     * 3. If at the first search state -> clears active search and returns to Home / Recent Searches view.
     * 4. Only when at root Home view does `handleBackPress()` return `false` to exit the app.
     */
    fun handleBackPress(): Boolean {
        val current = _uiState.value
        if (current.isScannerOpen) {
            closeBarcodeScanner()
            return true
        }
        if (current.isFilterSheetOpen) {
            closeFilterBottomSheet()
            return true
        }
        if (current.isOfflineDownloadsSheetOpen) {
            closeOfflineDownloadsSheet()
            return true
        }
        if (filterHistoryStack.isNotEmpty()) {
            val previous = filterHistoryStack.removeLast()
            _uiState.update { state ->
                recomputeDerivedTableState(
                    state.copy(
                        activeFilter = previous.filter,
                        draftFilter = previous.filter,
                        draftTexts = buildDraftTextMap(previous.filter, state.isAdmin),
                        rawProducts = previous.rawProducts,
                        sortState = previous.sortState,
                        lastSyncedAt = previous.lastSyncedAt,
                        isResultFromOfflineCache = previous.isFromOfflineCache,
                        hasActiveSearch = true,
                        isLoading = false,
                        errorMessage = null,
                        filterHistoryDepth = filterHistoryStack.size
                    )
                )
            }
            return true
        }
        if (current.hasActiveSearch) {
            clearAllFiltersAndResults()
            return true
        }
        return false
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private fun recomputeDerivedTableState(state: StockMainUiState): StockMainUiState {
        val sortedOverview = tableSorter.sortOverviewProducts(
            products = state.rawProducts,
            sortState = state.sortState
        )
        val sortedDetailed = tableSorter.buildAndSortDetailedRows(
            products = state.rawProducts,
            sortState = state.sortState
        )
        val commonChips = commonValuesCalculator.calculateCommonValues(
            products = state.rawProducts,
            activeFilter = state.activeFilter,
            isAdmin = state.isAdmin
        )
        val visibility = emptyColumnAnalyzer.analyze(
            products = state.rawProducts,
            activeFilter = state.activeFilter,
            commonChips = commonChips,
            isAdmin = state.isAdmin
        )
        return state.copy(
            sortedOverviewProducts = sortedOverview,
            sortedDetailedRows = sortedDetailed,
            commonValueChips = commonChips,
            columnVisibility = visibility
        )
    }

    private fun enforceSecondaryClearIfNoPrimaryInDraft(
        draft: FilterState,
        isAdmin: Boolean
    ): FilterState {
        val roleChecked = if (isAdmin) draft else draft.copy(party = "")
        return if (!roleChecked.hasPrimaryFilter(isAdmin)) {
            roleChecked.copy(color = "", size = "")
        } else {
            roleChecked
        }
    }

    private fun syncSecondaryTextsIfCleared(
        texts: Map<FilterField, String>,
        draft: FilterState
    ): Map<FilterField, String> {
        val mutable = texts.toMutableMap()
        if (draft.color.isBlank() && !draft.hasPrimaryFilter(_uiState.value.isAdmin)) {
            mutable[FilterField.COLOR] = ""
        }
        if (draft.size.isBlank() && !draft.hasPrimaryFilter(_uiState.value.isAdmin)) {
            mutable[FilterField.SIZE] = ""
        }
        return mutable
    }

    private fun buildDraftTextMap(filter: FilterState, isAdmin: Boolean): Map<FilterField, String> {
        return mapOf(
            FilterField.ARTICLE to filter.article,
            FilterField.BRAND to filter.brand,
            FilterField.BARCODE to filter.barcode.ifBlank { filter.brandcode },
            FilterField.NAME to filter.name,
            FilterField.PARTY to if (isAdmin) filter.party else "",
            FilterField.COLOR to filter.color,
            FilterField.SIZE to filter.size
        )
    }

    companion object {
        const val ACTION_OPEN_ARTICLE_SEARCH = "com.poshak.stock.ACTION_OPEN_ARTICLE_SEARCH"
        const val ACTION_OPEN_BARCODE_SCANNER = "com.poshak.stock.ACTION_OPEN_BARCODE_SCANNER"
    }
}
