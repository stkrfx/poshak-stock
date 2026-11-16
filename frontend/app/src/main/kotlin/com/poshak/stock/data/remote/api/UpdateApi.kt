package com.poshak.stock.data.remote.api

import com.poshak.stock.data.remote.dto.VersionCheckResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Retrofit service interface for the In-App Self-Update system (`/v1/app/version` & `/v1/app/download/{version_code}`).
 */
interface UpdateApi {

    @GET("v1/app/version")
    suspend fun checkVersion(
        @Query("current_version_code") currentVersionCode: Int
    ): Response<VersionCheckResponseDto>

    @Streaming
    @GET("v1/app/download/{version_code}")
    suspend fun downloadApk(
        @Path("version_code") versionCode: Int
    ): Response<ResponseBody>
}
