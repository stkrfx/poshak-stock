package com.poshak.stock.data.repository

import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.local.dao.ProductStockDao
import com.poshak.stock.data.local.dao.SearchHistoryDao
import com.poshak.stock.data.local.entity.SearchHistoryEntity
import com.poshak.stock.data.remote.api.StockApi
import com.poshak.stock.data.remote.api.SyncApi
import com.poshak.stock.data.remote.dto.CreateSearchHistoryRequestDto
import com.poshak.stock.data.remote.dto.ProductDto
import com.poshak.stock.data.remote.dto.StockSearchFilterDto
import com.poshak.stock.data.sync.NetworkQualityChecker
import com.poshak.stock.domain.model.FilterState
import com.poshak.stock.domain.model.ProductWithStocks
import com.poshak.stock.domain.model.StockSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates stock search across the confidential Go backend (`GET /v1/stock/search`)
 * and local Room SQLite database (`products` & `stock`), plus automatic organic caching
 * and search history synchronization.
 */
@Singleton
class StockRepository @Inject constructor(
    private val stockApi: StockApi,
    private val syncApi: SyncApi,
    private val productStockDao: ProductStockDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val autocompleteRepository: AutocompleteRepository,
    private val sessionManager: SessionManager,
    private val networkQualityChecker: NetworkQualityChecker,
    private val json: Json
) {

    /**
     * Executes a stock search for [rawFilter].
     *
     * Rules:
     * 1. Enforces Role Visibility: strips `party` if caller is `employee`.
     * 2. Enforces Strict Non-Solo Rule for `Size` and `Color`: if no primary filter is active,
     *    returns an empty result without hitting Room or the network.
     * 3. Online Mode: calls `GET /v1/stock/search`, organically caches all returned products,
     *    stock variants, and distinct autocomplete values into Room, and returns the result.
     *    Falls back to local Room if a network failure occurs.
     * 4. Offline Mode: queries local Room directly using exact case-insensitive matching.
     */
    suspend fun searchStock(
        rawFilter: FilterState,
        preferOfflineMode: Boolean = false
    ): Result<StockSearchResult> = withContext(Dispatchers.IO) {
        val isAdmin = sessionManager.isAdmin()
        val filter = rawFilter.enforceRoleAndNonSoloRule(isAdmin)

        // Never allow solo Size/Color or empty queries to reach Room or Backend
        if (!filter.hasPrimaryFilter(isAdmin)) {
            return@withContext Result.success(
                StockSearchResult(
                    products = emptyList(),
                    syncedAt = Instant.now().toString(),
                    isFromOfflineCache = preferOfflineMode || !networkQualityChecker.isCurrentlyOnline()
                )
            )
        }

        val shouldTryOnline = !preferOfflineMode && networkQualityChecker.isCurrentlyOnline()
        if (shouldTryOnline) {
            val onlineResult = runCatching {
                val response = stockApi.searchStock(
                    article = filter.article.takeIf { it.isNotBlank() },
                    brand = filter.brand.takeIf { it.isNotBlank() },
                    barcode = filter.barcode.takeIf { it.isNotBlank() },
                    brandcode = filter.brandcode.takeIf { it.isNotBlank() },
                    name = filter.name.takeIf { it.isNotBlank() },
                    party = if (isAdmin) filter.party.takeIf { it.isNotBlank() } else null,
                    color = filter.color.takeIf { it.isNotBlank() },
                    size = filter.size.takeIf { it.isNotBlank() }
                )
                if (response.isSuccessful) {
                    response.body()
                } else {
                    null
                }
            }.getOrNull()

            if (onlineResult != null) {
                // Organically cache all returned products, stock variants, and autocomplete values in Room
                organicallyCacheProducts(
                    products = onlineResult.products,
                    isAdmin = isAdmin,
                    replaceProductStocks = filter.size.isBlank(),
                    syncedAt = onlineResult.syncedAt
                )

                val domainProducts = onlineResult.products.map { dto ->
                    ProductWithStocks.fromDto(dto, isAdmin)
                }

                return@withContext Result.success(
                    StockSearchResult(
                        products = domainProducts,
                        syncedAt = onlineResult.syncedAt.ifBlank { Instant.now().toString() },
                        isFromOfflineCache = false
                    )
                )
            }
        }

        // Offline Mode or Seamless Offline Fallback
        return@withContext runCatching {
            val localProducts = queryLocalRoom(filter, isAdmin)
            val lastSync = sessionManager.getLastIncrementalSyncTimestamp()
                ?: Instant.now().toString()
            StockSearchResult(
                products = localProducts,
                syncedAt = lastSync,
                isFromOfflineCache = true
            )
        }
    }

    private suspend fun queryLocalRoom(
        filter: FilterState,
        isAdmin: Boolean
    ): List<ProductWithStocks> {
        val relations = productStockDao.searchLocalProductsWithStocks(
            article = filter.article.takeIf { it.isNotBlank() },
            brand = filter.brand.takeIf { it.isNotBlank() },
            barcode = filter.barcode.takeIf { it.isNotBlank() },
            brandcode = filter.brandcode.takeIf { it.isNotBlank() },
            name = filter.name.takeIf { it.isNotBlank() },
            party = if (isAdmin) filter.party.takeIf { it.isNotBlank() } else null,
            color = filter.color.takeIf { it.isNotBlank() },
            size = filter.size.takeIf { it.isNotBlank() },
            isAdmin = isAdmin
        )
        return relations.mapNotNull { rel ->
            val domain = ProductWithStocks.fromEntity(
                relation = rel,
                isAdmin = isAdmin,
                sizeFilter = filter.size.takeIf { it.isNotBlank() }
            )
            if (filter.size.isNotBlank() && domain.stocks.isEmpty()) {
                null
            } else {
                domain
            }
        }
    }

    /**
     * Organically saves products, stock variants, and distinct autocomplete suggestions into Room.
     */
    suspend fun organicallyCacheProducts(
        products: List<ProductDto>,
        isAdmin: Boolean,
        replaceProductStocks: Boolean,
        syncedAt: String
    ) = withContext(Dispatchers.IO) {
        if (products.isEmpty()) return@withContext

        val productEntities = products.map { it.toProductEntity(isAdmin) }
        val stockEntities = products.flatMap { it.toStockEntities() }

        productStockDao.upsertProductsAndStocks(
            products = productEntities,
            stocks = stockEntities,
            replaceProductStocks = replaceProductStocks
        )

        autocompleteRepository.extractAndCacheSuggestionsFromProducts(
            products = products,
            isAdmin = isAdmin
        )

        if (syncedAt.isNotBlank() && sessionManager.getLastIncrementalSyncTimestamp().isNullOrBlank()) {
            sessionManager.setLastIncrementalSyncTimestamp(syncedAt)
        }
    }

    /**
     * Observes the latest 40 searches from Room (`search_history`).
     */
    fun observeRecentSearches(limit: Int = 40): Flow<List<SearchHistoryEntity>> {
        return searchHistoryDao.observeRecentSearches(limit)
    }

    /**
     * Records an executed search in Room immediately and syncs with `POST /v1/search-history` if online.
     */
    suspend fun recordSearchHistory(rawFilter: FilterState) = withContext(Dispatchers.IO) {
        val isAdmin = sessionManager.isAdmin()
        val filter = rawFilter.enforceRoleAndNonSoloRule(isAdmin)
        if (!filter.hasPrimaryFilter(isAdmin)) return@withContext

        val filterDto = filter.toDto(isAdmin)
        val queryJsonString = json.encodeToString(filterDto)
        val queryText = filter.toDisplaySummary(isAdmin)
        if (queryText.isBlank()) return@withContext

        if (networkQualityChecker.isCurrentlyOnline()) {
            val remoteItem = runCatching {
                val resp = syncApi.createSearchHistory(
                    CreateSearchHistoryRequestDto(
                        queryJson = filterDto,
                        queryText = queryText
                    )
                )
                if (resp.isSuccessful) resp.body() else null
            }.getOrNull()

            if (remoteItem != null) {
                val entity = SearchHistoryEntity(
                    id = remoteItem.id,
                    userId = remoteItem.userId.ifBlank { sessionManager.getUserId() },
                    isMine = true,
                    queryJson = remoteItem.queryJson.toString(),
                    queryText = remoteItem.queryText,
                    createdAt = remoteItem.createdAt,
                    timestampMs = parseTimestampMs(remoteItem.createdAt)
                )
                searchHistoryDao.insertSearchHistoryItem(entity)
                searchHistoryDao.trimOldHistory(40)
                return@withContext
            }
        }

        // Offline local fallback history item
        val nowIso = Instant.now().toString()
        val localEntity = SearchHistoryEntity(
            id = "local_${UUID.randomUUID()}",
            userId = sessionManager.getUserId(),
            isMine = true,
            queryJson = queryJsonString,
            queryText = queryText,
            createdAt = nowIso,
            timestampMs = System.currentTimeMillis()
        )
        searchHistoryDao.insertSearchHistoryItem(localEntity)
        searchHistoryDao.trimOldHistory(40)
    }

    /**
     * Refreshes the latest 40 search history entries from `GET /v1/search-history?limit=40`.
     */
    suspend fun refreshSearchHistoryFromServer() = withContext(Dispatchers.IO) {
        if (!networkQualityChecker.isCurrentlyOnline()) return@withContext

        val isAdmin = sessionManager.isAdmin()
        runCatching {
            val response = syncApi.getSearchHistory(limit = 40)
            if (response.isSuccessful) {
                val items = response.body().orEmpty()
                val entities = items.mapNotNull { dto ->
                    val rawJson = dto.queryJson.toString()
                    if (!isAdmin) {
                        val parsed = runCatching {
                            json.decodeFromString<StockSearchFilterDto>(rawJson)
                        }.getOrNull()
                        if (parsed != null) {
                            val sanitized = FilterState.fromDto(parsed, isAdmin = false)
                            if (!sanitized.hasPrimaryFilter(isAdmin = false)) {
                                return@mapNotNull null
                            }
                        }
                    }
                    SearchHistoryEntity(
                        id = dto.id,
                        userId = dto.userId,
                        isMine = dto.isMine,
                        queryJson = rawJson,
                        queryText = dto.queryText,
                        createdAt = dto.createdAt,
                        timestampMs = parseTimestampMs(dto.createdAt)
                    )
                }
                searchHistoryDao.replaceHistoryWithLatest(entities, keepCount = 40)
            }
        }
    }

    /**
     * Parses a [SearchHistoryEntity]'s `queryJson` back into a role-validated [FilterState].
     */
    fun parseSearchHistoryFilter(entity: SearchHistoryEntity): FilterState {
        val isAdmin = sessionManager.isAdmin()
        return runCatching {
            val dto = json.decodeFromString<StockSearchFilterDto>(entity.queryJson)
            FilterState.fromDto(dto, isAdmin = isAdmin)
        }.getOrDefault(FilterState())
    }

    private fun parseTimestampMs(isoString: String): Long {
        return runCatching {
            Instant.parse(isoString).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }
}
