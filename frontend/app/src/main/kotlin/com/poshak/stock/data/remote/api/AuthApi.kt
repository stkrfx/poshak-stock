package com.poshak.stock.data.remote.api

import com.poshak.stock.data.remote.dto.LoginRequestDto
import com.poshak.stock.data.remote.dto.LoginResponseDto
import com.poshak.stock.data.remote.dto.LogoutRequestDto
import com.poshak.stock.data.remote.dto.LogoutResponseDto
import com.poshak.stock.data.remote.dto.PreLoginRequestDto
import com.poshak.stock.data.remote.dto.PreLoginResponseDto
import com.poshak.stock.data.remote.dto.RefreshRequestDto
import com.poshak.stock.data.remote.dto.RefreshResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit service interface for `/v1/auth/*` endpoints.
 */
interface AuthApi {

    @POST("v1/auth/pre-login")
    suspend fun preLogin(
        @Body request: PreLoginRequestDto
    ): Response<PreLoginResponseDto>

    @POST("v1/auth/login")
    suspend fun login(
        @Body request: LoginRequestDto
    ): Response<LoginResponseDto>

    @POST("v1/auth/refresh")
    suspend fun refresh(
        @Body request: RefreshRequestDto
    ): Response<RefreshResponseDto>

    @POST("v1/auth/logout")
    suspend fun logout(
        @Body request: LogoutRequestDto
    ): Response<LogoutResponseDto>
}
