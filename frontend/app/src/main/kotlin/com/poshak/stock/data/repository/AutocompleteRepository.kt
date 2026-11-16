package com.poshak.stock.data.repository

import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.local.dao.AutocompleteDao
import com.poshak.stock.data.local.dao.ProductStockDao
import com.poshak.stock.data.local.entity.AutocompleteEntity
import com.poshak.stock.data.remote.api.StockApi
import com.poshak.stock.data.remote.dto.ProductDto
import com.poshak.stock.data.sync.NetworkQualityChecker
import com.poshak.stock.domain.model.FilterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages uniform field-specific autocomplete suggestions across online (`/v1/autocomplete`)
 * and offline (Room `autocomplete_suggestions` and scoped `products`/`stock`) sources.
 *
 * Implements:
 * 1. Role-based visibility: `party` suggestions are strictly blocked for `employee` users.
 * 2. Strict Non-Solo Gating for `Size` and `Color`: returns `emptyList()` without querying
 *    Room or the backend unless at least one primary filter is currently selected.
 * 3. Organic Growth: every online `/v1/autocomplete` response and every downloaded/searched
 *    product batch automatically populates Room's `autocomplete_suggestions` table.
 */
@Singleton
class AutocompleteRepository @Inject constructor(
    private val stockApi: StockApi,
    private val autocompleteDao: AutocompleteDao,
    private val productStockDao: ProductStockDao,
    private val sessionManager: SessionManager,
    private val networkQualityChecker: NetworkQualityChecker
) {
    // Tracks recently synced (field, prefix, contextKey) tuples in memory to avoid redundant network hits
    private val recentPrefixSyncCache = ConcurrentHashMap<String, Long>()

    /**
     * Queries autocomplete suggestions strictly for [field] matching [rawPrefix].
     */
    suspend fun getSuggestions(
        field: String,
        rawPrefix: String,
        contextFilter: FilterState,
        preferOfflineOnly: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        val normalizedField = field.trim().lowercase()
        val normalizedPrefix = rawPrefix.trim().lowercase()
        if (normalizedPrefix.isEmpty()) {
            return@withContext emptyList()
        }

        val isAdmin = sessionManager.isAdmin()

        // Rule: Employee role can never query or view Party suggestions
        if (normalizedField == AutocompleteEntity.FIELD_PARTY && !isAdmin) {
            return@withContext emptyList()
        }

        // Rule: Strict Non-Solo Gating for Size and Color
        val isSecondaryField =
            normalizedField == AutocompleteEntity.FIELD_COLOR ||
                normalizedField == AutocompleteEntity.FIELD_SIZE

        if (isSecondaryField && !contextFilter.hasPrimaryFilter(isAdmin)) {
            return@withContext emptyList()
        }

        // 1. Query local Room first for instant response
        val localSuggestions = queryLocalSuggestions(
            field = normalizedField,
            normalizedPrefix = normalizedPrefix,
            contextFilter = contextFilter,
            isAdmin = isAdmin
        )

        if (preferOfflineOnly || !networkQualityChecker.isCurrentlyOnline()) {
            return@withContext localSuggestions
        }

        // 2. Check if this (field, prefix, context) was synced within the last 60 seconds
        val cacheKey = buildPrefixCacheKey(normalizedField, normalizedPrefix, contextFilter, isAdmin)
        val now = System.currentTimeMillis()
        val lastSynced = recentPrefixSyncCache[cacheKey] ?: 0L
        if (localSuggestions.isNotEmpty() && (now - lastSynced) < PREFIX_SYNC_TTL_MS) {
            return@withContext localSuggestions
        }

        // 3. Query GET /v1/autocomplete and organically upsert into Room
        val remoteSuggestions = runCatching {
            val response = stockApi.getAutocomplete(
                field = normalizedField,
                prefix = normalizedPrefix,
                article = contextFilter.article.takeIf { isSecondaryField && it.isNotBlank() },
                brand = contextFilter.brand.takeIf { isSecondaryField && it.isNotBlank() },
                barcode = contextFilter.barcode.takeIf { isSecondaryField && it.isNotBlank() },
                brandcode = contextFilter.brandcode.takeIf { isSecondaryField && it.isNotBlank() },
                name = contextFilter.name.takeIf { isSecondaryField && it.isNotBlank() },
                party = if (isAdmin && isSecondaryField) {
                    contextFilter.party.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            )
            if (response.isSuccessful) {
                response.body().orEmpty()
            } else {
                null
            }
        }.getOrNull()

        if (remoteSuggestions != null) {
            recentPrefixSyncCache[cacheKey] = now
            if (remoteSuggestions.isNotEmpty()) {
                val entities = remoteSuggestions.mapIndexed { index, displayValue ->
                    AutocompleteEntity.create(
                        field = normalizedField,
                        displayValue = displayValue,
                        sizeSortOrder = if (normalizedField == AutocompleteEntity.FIELD_SIZE) index + 1 else 999999,
                        updatedAt = now
                    )
                }
                autocompleteDao.upsertSuggestions(entities)
            }
            // Merge remote + local preserving order and distinct case-insensitive display values
            val combined = LinkedHashMap<String, String>()
            remoteSuggestions.forEach { item ->
                val clean = item.trim()
                if (clean.isNotEmpty()) {
                    combined[clean.lowercase()] = clean
                }
            }
            localSuggestions.forEach { item ->
                val clean = item.trim()
                if (clean.isNotEmpty()) {
                    combined.putIfAbsent(clean.lowercase(), clean)
                }
            }
            return@withContext combined.values.toList()
        }

        return@withContext localSuggestions
    }

    private suspend fun queryLocalSuggestions(
        field: String,
        normalizedPrefix: String,
        contextFilter: FilterState,
        isAdmin: Boolean
    ): List<String> {
        val code = contextFilter.barcode.ifBlank { contextFilter.brandcode }.takeIf { it.isNotBlank() }
        if (field == AutocompleteEntity.FIELD_COLOR && contextFilter.hasPrimaryFilter(isAdmin)) {
            val scopedColors = productStockDao.getScopedColorSuggestions(
                normalizedPrefix = normalizedPrefix,
                article = contextFilter.article.takeIf { it.isNotBlank() },
                brand = contextFilter.brand.takeIf { it.isNotBlank() },
                name = contextFilter.name.takeIf { it.isNotBlank() },
                party = if (isAdmin) contextFilter.party.takeIf { it.isNotBlank() } else null,
                code = code,
                isAdmin = isAdmin
            )
            if (scopedColors.isNotEmpty()) {
                return scopedColors
            }
        }

        if (field == AutocompleteEntity.FIELD_SIZE && contextFilter.hasPrimaryFilter(isAdmin)) {
            val scopedSizes = productStockDao.getScopedSizeSuggestions(
                normalizedPrefix = normalizedPrefix,
                article = contextFilter.article.takeIf { it.isNotBlank() },
                brand = contextFilter.brand.takeIf { it.isNotBlank() },
                name = contextFilter.name.takeIf { it.isNotBlank() },
                party = if (isAdmin) contextFilter.party.takeIf { it.isNotBlank() } else null,
                code = code,
                isAdmin = isAdmin
            )
            if (scopedSizes.isNotEmpty()) {
                return scopedSizes
            }
        }

        return autocompleteDao.getSuggestionsByPrefix(
            field = field,
            normalizedPrefix = normalizedPrefix
        )
    }

    /**
     * Extracts all distinct `article`, `brand`, `name`, `party` (admin only), `color`, and `size`
     * values from a list of [ProductDto] items and transactionally upserts them into Room's
     * `autocomplete_suggestions` table.
     */
    suspend fun extractAndCacheSuggestionsFromProducts(
        products: List<ProductDto>,
        isAdmin: Boolean
    ) = withContext(Dispatchers.IO) {
        if (products.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        val suggestionMap = LinkedHashMap<Pair<String, String>, AutocompleteEntity>()

        fun addValue(field: String, rawValue: String?, sortOrder: Int = 999999) {
            val trimmed = rawValue?.trim().orEmpty()
            if (trimmed.isEmpty()) return
            val key = field to trimmed.lowercase()
            val existing = suggestionMap[key]
            if (existing == null || sortOrder < existing.sizeSortOrder) {
                suggestionMap[key] = AutocompleteEntity(
                    field = field,
                    normalizedValue = trimmed.lowercase(),
                    displayValue = trimmed,
                    sizeSortOrder = sortOrder,
                    updatedAt = now
                )
            }
        }

        for (product in products) {
            addValue(AutocompleteEntity.FIELD_ARTICLE, product.article)
            addValue(AutocompleteEntity.FIELD_BRAND, product.brand)
            addValue(AutocompleteEntity.FIELD_NAME, product.name)
            if (isAdmin) {
                addValue(AutocompleteEntity.FIELD_PARTY, product.party)
            }
            addValue(AutocompleteEntity.FIELD_COLOR, product.color)

            for (stock in product.stocks) {
                addValue(
                    field = AutocompleteEntity.FIELD_SIZE,
                    rawValue = stock.size,
                    sortOrder = stock.sizeSortOrder
                )
            }
        }

        if (suggestionMap.isNotEmpty()) {
            suggestionMap.values.chunked(500).forEach { chunk ->
                autocompleteDao.upsertSuggestions(chunk)
            }
        }
    }

    private fun buildPrefixCacheKey(
        field: String,
        normalizedPrefix: String,
        contextFilter: FilterState,
        isAdmin: Boolean
    ): String {
        val isSecondary =
            field == AutocompleteEntity.FIELD_COLOR || field == AutocompleteEntity.FIELD_SIZE
        if (!isSecondary) {
            return "$field:$normalizedPrefix"
        }
        return buildString {
            append(field).append(':').append(normalizedPrefix)
            append("|a=").append(contextFilter.article.lowercase())
            append("|b=").append(contextFilter.brand.lowercase())
            append("|c=").append(contextFilter.barcode.ifBlank { contextFilter.brandcode }.lowercase())
            append("|n=").append(contextFilter.name.lowercase())
            if (isAdmin) {
                append("|p=").append(contextFilter.party.lowercase())
            }
        }
    }

    companion object {
        private const val PREFIX_SYNC_TTL_MS = 60_000L
    }
}
