package com.poshak.stock.data.remote.api

import com.poshak.stock.data.remote.dto.StockSearchResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit service interface for confidential stock search and prefix autocomplete endpoints.
 */
interface StockApi {

    /**
     * Queries `GET /v1/autocomplete` for a specific field and prefix.
     * Optional context filters (`article`, `brand`, `barcode`, `brandcode`, `name`, `party`)
     * scope `color` and `size` suggestions to the currently selected primary filters.
     */
    @GET("v1/autocomplete")
    suspend fun getAutocomplete(
        @Query("field") field: String,
        @Query("prefix") prefix: String,
        @Query("article") article: String? = null,
        @Query("brand") brand: String? = null,
        @Query("barcode") barcode: String? = null,
        @Query("brandcode") brandcode: String? = null,
        @Query("name") name: String? = null,
        @Query("party") party: String? = null
    ): Response<List<String>>

    /**
     * Queries `GET /v1/stock/search` with exact case-insensitive filters.
     * No pagination (`page`, `limit`) and no backend sorting.
     */
    @GET("v1/stock/search")
    suspend fun searchStock(
        @Query("article") article: String? = null,
        @Query("brand") brand: String? = null,
        @Query("barcode") barcode: String? = null,
        @Query("brandcode") brandcode: String? = null,
        @Query("name") name: String? = null,
        @Query("party") party: String? = null,
        @Query("color") color: String? = null,
        @Query("size") size: String? = null
    ): Response<StockSearchResponseDto>
}
