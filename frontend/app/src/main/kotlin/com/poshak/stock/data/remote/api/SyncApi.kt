package com.poshak.stock.data.remote.api

import com.poshak.stock.data.remote.dto.CreateSearchHistoryRequestDto
import com.poshak.stock.data.remote.dto.HealthResponseDto
import com.poshak.stock.data.remote.dto.IncrementalSyncResponseDto
import com.poshak.stock.data.remote.dto.OfflineDownloadRequestDto
import com.poshak.stock.data.remote.dto.SearchHistoryItemDto
import com.poshak.stock.data.remote.dto.StockSearchResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit service interface for offline scope downloads, incremental background sync,
 * search history, and connection health readiness checks.
 */
interface SyncApi {

    /**
     * Downloads either the full stock inventory (`scope_type = "all"`)
     * or a filtered subset (`scope_type = "filtered"`).
     */
    @POST("v1/sync/offline-downloads")
    suspend fun downloadOfflineScope(
        @Body request: OfflineDownloadRequestDto
    ): Response<StockSearchResponseDto>

    /**
     * Fetches incremental product/stock upserts and deleted entity IDs since `since` (RFC3339).
     */
    @GET("v1/sync/stock")
    suspend fun getIncrementalSync(
        @Query("since") since: String
    ): Response<IncrementalSyncResponseDto>

    /**
     * Fetches the latest `limit` (default 40) search history items.
     */
    @GET("v1/search-history")
    suspend fun getSearchHistory(
        @Query("limit") limit: Int = 40
    ): Response<List<SearchHistoryItemDto>>

    /**
     * Records a newly executed search query on the server.
     */
    @POST("v1/search-history")
    suspend fun createSearchHistory(
        @Body request: CreateSearchHistoryRequestDto
    ): Response<SearchHistoryItemDto>

    /**
     * Lightweight readiness check (`GET /v1/health`) used as the Pull-to-Refresh quality gate.
     */
    @GET("v1/health")
    suspend fun checkHealth(): Response<HealthResponseDto>
}
