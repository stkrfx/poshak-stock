package com.poshak.stock.data.repository

import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.local.dao.AutocompleteDao
import com.poshak.stock.data.local.dao.CachedInventorySummary
import com.poshak.stock.data.local.dao.OfflineScopeDao
import com.poshak.stock.data.local.dao.ProductStockDao
import com.poshak.stock.data.local.entity.AutocompleteEntity
import com.poshak.stock.data.local.entity.OfflineScopeEntity
import com.poshak.stock.data.remote.api.SyncApi
import com.poshak.stock.data.remote.dto.OfflineDownloadRequestDto
import com.poshak.stock.data.remote.dto.StockSearchFilterDto
import com.poshak.stock.domain.model.FilterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages:
 * 1. Manual Offline Downloads (Full Stock Download `"all"` or Filtered Scope Download `"filtered"`).
 * 2. Automatic population of Room's `autocomplete_suggestions` from downloaded offline scopes.
 * 3. Incremental background sync (`GET /v1/sync/stock?since=<last_sync_timestamp>`) keeping
 *    both manually downloaded scopes and organically cached searches up to date with Habron changes.
 */
@Singleton
class OfflineSyncRepository @Inject constructor(
    private val syncApi: SyncApi,
    private val productStockDao: ProductStockDao,
    private val offlineScopeDao: OfflineScopeDao,
    private val autocompleteDao: AutocompleteDao,
    private val autocompleteRepository: AutocompleteRepository,
    private val sessionManager: SessionManager,
    private val json: Json
) {

    fun observeOfflineScopes(): Flow<List<OfflineScopeEntity>> {
        return offlineScopeDao.observeAllScopes()
    }

    fun observeCachedInventorySummary(): Flow<CachedInventorySummary> {
        return productStockDao.observeCachedInventorySummary()
    }

    /**
     * Downloads the complete stock inventory (`scope_type = "all"`) for 100% offline shop operation.
     */
    suspend fun downloadFullStock(): Result<OfflineScopeEntity> = withContext(Dispatchers.IO) {
        val isAdmin = sessionManager.isAdmin()
        runCatching {
            val response = syncApi.downloadOfflineScope(
                OfflineDownloadRequestDto(
                    scopeType = OfflineScopeEntity.SCOPE_TYPE_ALL,
                    filters = StockSearchFilterDto()
                )
            )
            if (!response.isSuccessful) {
                throw IllegalStateException("Full stock download failed (HTTP ${response.code()})")
            }

            val body = response.body()
                ?: throw IllegalStateException("Empty offline download payload")

            val products = body.products
            val syncedAt = body.syncedAt.ifBlank { Instant.now().toString() }

            val productEntities = products.map { it.toProductEntity(isAdmin) }
            val stockEntities = products.flatMap { it.toStockEntities() }

            // 1. Upsert all products and stock rows into Room
            productStockDao.upsertProductsAndStocks(
                products = productEntities,
                stocks = stockEntities,
                replaceProductStocks = true
            )

            // 2. Populate Room's autocomplete_suggestions with all distinct values
            autocompleteRepository.extractAndCacheSuggestionsFromProducts(
                products = products,
                isAdmin = isAdmin
            )

            // 3. Save the Full Stock OfflineScopeEntity and product cross-references
            val scopeEntity = OfflineScopeEntity(
                id = OfflineScopeEntity.FULL_STOCK_SCOPE_ID,
                scopeType = OfflineScopeEntity.SCOPE_TYPE_ALL,
                title = "Full Stock Inventory",
                filtersJson = "{}",
                productCount = productEntities.size,
                stockRowCount = stockEntities.size,
                totalQuantity = stockEntities.sumOf { it.quantity },
                downloadedAt = System.currentTimeMillis(),
                lastSyncedAt = syncedAt
            )
            offlineScopeDao.saveScopeWithProductIds(
                scope = scopeEntity,
                productIds = productEntities.map { it.id }
            )

            // 4. Advance incremental sync cursor
            sessionManager.setLastIncrementalSyncTimestamp(syncedAt)
            scopeEntity
        }
    }

    /**
     * Downloads stock matching specific filters (`scope_type = "filtered"`, e.g. `Brand: KILLER`, `Name: FORMAL SHIRT`).
     */
    suspend fun downloadFilteredStock(rawFilter: FilterState): Result<OfflineScopeEntity> =
        withContext(Dispatchers.IO) {
            val isAdmin = sessionManager.isAdmin()
            val filter = rawFilter.enforceRoleAndNonSoloRule(isAdmin)
            if (!filter.hasPrimaryFilter(isAdmin)) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Select at least one primary filter (Article, Brand, Barcode, Name, or Party) for a filtered offline download."
                    )
                )
            }

            runCatching {
                val filterDto = filter.toDto(isAdmin)
                val response = syncApi.downloadOfflineScope(
                    OfflineDownloadRequestDto(
                        scopeType = OfflineScopeEntity.SCOPE_TYPE_FILTERED,
                        filters = filterDto
                    )
                )
                if (!response.isSuccessful) {
                    throw IllegalStateException("Filtered offline download failed (HTTP ${response.code()})")
                }

                val body = response.body()
                    ?: throw IllegalStateException("Empty filtered download payload")

                val products = body.products
                val syncedAt = body.syncedAt.ifBlank { Instant.now().toString() }

                val productEntities = products.map { it.toProductEntity(isAdmin) }
                val stockEntities = products.flatMap { it.toStockEntities() }

                // 1. Upsert all returned products and stock rows into Room
                productStockDao.upsertProductsAndStocks(
                    products = productEntities,
                    stocks = stockEntities,
                    replaceProductStocks = filter.size.isBlank()
                )

                // 2. Populate Room's autocomplete_suggestions with all distinct values
                autocompleteRepository.extractAndCacheSuggestionsFromProducts(
                    products = products,
                    isAdmin = isAdmin
                )

                // 3. Save deterministic OfflineScopeEntity for this filter combination
                val filtersJson = json.encodeToString(filterDto)
                val scopeId = buildFilteredScopeId(filtersJson)
                val scopeTitle = filter.toDisplaySummary(isAdmin).ifBlank { "Filtered Stock Scope" }

                val scopeEntity = OfflineScopeEntity(
                    id = scopeId,
                    scopeType = OfflineScopeEntity.SCOPE_TYPE_FILTERED,
                    title = scopeTitle,
                    filtersJson = filtersJson,
                    productCount = productEntities.size,
                    stockRowCount = stockEntities.size,
                    totalQuantity = stockEntities.sumOf { it.quantity },
                    downloadedAt = System.currentTimeMillis(),
                    lastSyncedAt = syncedAt
                )
                offlineScopeDao.saveScopeWithProductIds(
                    scope = scopeEntity,
                    productIds = productEntities.map { it.id }
                )

                if (sessionManager.getLastIncrementalSyncTimestamp().isNullOrBlank()) {
                    sessionManager.setLastIncrementalSyncTimestamp(syncedAt)
                }

                scopeEntity
            }
        }

    /**
     * Deletes a saved offline download scope entry.
     */
    suspend fun deleteOfflineScope(scopeId: String) = withContext(Dispatchers.IO) {
        offlineScopeDao.deleteScopeProductRefs(scopeId)
        offlineScopeDao.deleteScopeById(scopeId)
    }

    /**
     * Executes incremental background delta sync (`GET /v1/sync/stock?since=<last_sync_timestamp>`).
     * Returns the total number of upserted and deleted records applied to Room.
     */
    suspend fun runIncrementalSync(): Result<Int> = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn.value) {
            return@withContext Result.success(0)
        }

        val isAdmin = sessionManager.isAdmin()
        val sinceTimestamp = sessionManager.getLastIncrementalSyncTimestamp()
            ?: "1970-01-01T00:00:00Z"

        runCatching {
            val response = syncApi.getIncrementalSync(since = sinceTimestamp)
            if (!response.isSuccessful) {
                throw IllegalStateException("Incremental sync failed with HTTP ${response.code()}")
            }

            val body = response.body() ?: return@runCatching 0
            val upsertedProducts = body.upserts.products.map { it.toProductEntity(isAdmin) }
            val upsertedStocks = body.upserts.stocks.map { it.toEntity() }
            val deletedProductIds = body.deletes.productIds
            val deletedStockIds = body.deletes.stockIds

            productStockDao.applyIncrementalSync(
                upsertedProducts = upsertedProducts,
                upsertedStocks = upsertedStocks,
                deletedProductIds = deletedProductIds,
                deletedStockIds = deletedStockIds
            )

            // Organically upsert distinct autocomplete values from updated products & stock sizes
            if (body.upserts.products.isNotEmpty()) {
                autocompleteRepository.extractAndCacheSuggestionsFromProducts(
                    products = body.upserts.products,
                    isAdmin = isAdmin
                )
            }
            if (body.upserts.stocks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val sizeSuggestions = body.upserts.stocks
                    .filter { it.size.isNotBlank() }
                    .distinctBy { it.size.trim().lowercase() }
                    .map { st ->
                        AutocompleteEntity.create(
                            field = AutocompleteEntity.FIELD_SIZE,
                            displayValue = st.size,
                            sizeSortOrder = st.sizeSortOrder,
                            updatedAt = now
                        )
                    }
                if (sizeSuggestions.isNotEmpty()) {
                    autocompleteDao.upsertSuggestions(sizeSuggestions)
                }
            }

            val syncedAt = body.syncedAt.ifBlank { Instant.now().toString() }
            sessionManager.setLastIncrementalSyncTimestamp(syncedAt)
            offlineScopeDao.updateAllScopesLastSyncedAt(syncedAt)

            upsertedProducts.size + upsertedStocks.size + deletedProductIds.size + deletedStockIds.size
        }
    }

    private fun buildFilteredScopeId(filtersJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(filtersJson.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return "scope_filtered_$hex"
    }
}
