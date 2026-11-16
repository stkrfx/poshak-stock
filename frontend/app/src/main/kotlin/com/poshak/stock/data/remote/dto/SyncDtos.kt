package com.poshak.stock.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request body for `POST /v1/sync/offline-downloads`.
 * `scopeType` is `"all"` (Full Stock Download) or `"filtered"` (Filtered Scope Download).
 */
@Serializable
data class OfflineDownloadRequestDto(
    @SerialName("scope_type")
    val scopeType: String,
    @SerialName("filters")
    val filters: StockSearchFilterDto = StockSearchFilterDto()
)

@Serializable
data class IncrementalUpsertsDto(
    @SerialName("products")
    val products: List<ProductDto> = emptyList(),
    @SerialName("stocks")
    val stocks: List<StockItemDto> = emptyList()
)

@Serializable
data class IncrementalDeletesDto(
    @SerialName("product_ids")
    val productIds: List<String> = emptyList(),
    @SerialName("stock_ids")
    val stockIds: List<String> = emptyList()
)

/**
 * Response envelope returned by `GET /v1/sync/stock?since=<RFC3339_timestamp>`.
 */
@Serializable
data class IncrementalSyncResponseDto(
    @SerialName("upserts")
    val upserts: IncrementalUpsertsDto = IncrementalUpsertsDto(),
    @SerialName("deletes")
    val deletes: IncrementalDeletesDto = IncrementalDeletesDto(),
    @SerialName("synced_at")
    val syncedAt: String = ""
)

/**
 * Search history record returned by `GET /v1/search-history` and `POST /v1/search-history`.
 */
@Serializable
data class SearchHistoryItemDto(
    @SerialName("id")
    val id: String,
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("is_mine")
    val isMine: Boolean = false,
    @SerialName("query_json")
    val queryJson: JsonElement,
    @SerialName("query_text")
    val queryText: String,
    @SerialName("created_at")
    val createdAt: String
)

@Serializable
data class CreateSearchHistoryRequestDto(
    @SerialName("query_json")
    val queryJson: StockSearchFilterDto,
    @SerialName("query_text")
    val queryText: String
)

@Serializable
data class HealthResponseDto(
    @SerialName("status")
    val status: String = "ok"
)
