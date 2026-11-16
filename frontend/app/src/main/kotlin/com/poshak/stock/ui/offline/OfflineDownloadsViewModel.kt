package com.poshak.stock.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poshak.stock.data.local.dao.CachedInventorySummary
import com.poshak.stock.data.local.entity.OfflineScopeEntity
import com.poshak.stock.data.repository.OfflineSyncRepository
import com.poshak.stock.domain.model.FilterState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OfflineDownloadsUiState(
    val scopes: List<OfflineScopeEntity> = emptyList(),
    val cachedSummary: CachedInventorySummary = CachedInventorySummary(0, 0, 0),
    val isDownloadingFullStock: Boolean = false,
    val isDownloadingFilteredScope: Boolean = false,
    val isSyncingDelta: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel for `OfflineDownloadsSheet` (Section 6.1):
 * Allows the user to:
 * 1. Download Full Stock (`"scope_type": "all"`) for 100% offline shop operation.
 * 2. Download Filtered Stock (`"scope_type": "filtered"`, e.g. `Brand: KILLER`, `Name: FORMAL SHIRT`).
 * 3. Trigger an immediate incremental delta sync (`GET /v1/sync/stock?since=...`).
 * 4. View and remove saved offline scopes.
 */
@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(
    private val offlineSyncRepository: OfflineSyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineDownloadsUiState())
    val uiState: StateFlow<OfflineDownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            offlineSyncRepository.observeOfflineScopes().collect { list ->
                _uiState.update { it.copy(scopes = list) }
            }
        }
        viewModelScope.launch {
            offlineSyncRepository.observeCachedInventorySummary().collect { summary ->
                _uiState.update { it.copy(cachedSummary = summary) }
            }
        }
    }

    fun downloadFullStock() {
        if (_uiState.value.isDownloadingFullStock) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingFullStock = true,
                    statusMessage = null,
                    errorMessage = null
                )
            }
            val result = offlineSyncRepository.downloadFullStock()
            result.onSuccess { scope ->
                _uiState.update {
                    it.copy(
                        isDownloadingFullStock = false,
                        statusMessage = "Downloaded full stock (${scope.productCount} products, ${scope.stockRowCount} size rows) + offline autocomplete."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDownloadingFullStock = false,
                        errorMessage = err.localizedMessage ?: "Failed to download full stock"
                    )
                }
            }
        }
    }

    fun downloadFilteredScope(filter: FilterState) {
        if (_uiState.value.isDownloadingFilteredScope) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingFilteredScope = true,
                    statusMessage = null,
                    errorMessage = null
                )
            }
            val result = offlineSyncRepository.downloadFilteredStock(filter)
            result.onSuccess { scope ->
                _uiState.update {
                    it.copy(
                        isDownloadingFilteredScope = false,
                        statusMessage = "Downloaded scope \"${scope.title}\" (${scope.productCount} products) for offline use."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDownloadingFilteredScope = false,
                        errorMessage = err.localizedMessage ?: "Failed to download filtered scope"
                    )
                }
            }
        }
    }

    fun runIncrementalDeltaSyncNow() {
        if (_uiState.value.isSyncingDelta) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncingDelta = true,
                    statusMessage = null,
                    errorMessage = null
                )
            }
            val result = offlineSyncRepository.runIncrementalSync()
            result.onSuccess { changesApplied ->
                _uiState.update {
                    it.copy(
                        isSyncingDelta = false,
                        statusMessage = if (changesApplied > 0) {
                            "Synced $changesApplied updated/removed inventory records."
                        } else {
                            "Local offline cache is already up to date."
                        }
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingDelta = false,
                        errorMessage = err.localizedMessage ?: "Incremental sync failed"
                    )
                }
            }
        }
    }

    fun deleteScope(scopeId: String) {
        viewModelScope.launch {
            offlineSyncRepository.deleteOfflineScope(scopeId)
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
