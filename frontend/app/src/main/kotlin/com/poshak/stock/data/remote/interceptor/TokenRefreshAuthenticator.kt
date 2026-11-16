package com.poshak.stock.data.remote.interceptor

import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.remote.api.AuthApi
import com.poshak.stock.data.remote.dto.ApiErrorDto
import com.poshak.stock.data.remote.dto.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OkHttp [Authenticator] that intercepts 401 responses, atomically rotates the refresh token
 * via `POST /v1/auth/refresh`, and retries the original request with the new access token.
 *
 * If `/v1/auth/refresh` returns 401 (`session_revoked`) or 403 (`account_blocked`), it immediately
 * invokes [SessionManager.handleSecurityRevocationOrBlock] to wipe all local Room tables, tokens,
 * and WorkManager jobs. Ordinary network failures never wipe local data.
 */
@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    private val sessionManager: SessionManager,
    private val authApiProvider: Provider<AuthApi>,
    private val json: Json
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        if (
            path.endsWith("/v1/auth/login") ||
            path.endsWith("/v1/auth/pre-login") ||
            path.endsWith("/v1/auth/refresh")
        ) {
            return null
        }

        // If the 401 response itself already explicitly states "session_revoked", wipe immediately and do not retry.
        val initialErrorCode = parseErrorCode(response)
        if (
            initialErrorCode.equals(SessionManager.ERR_SESSION_REVOKED, ignoreCase = true) ||
            initialErrorCode.equals(SessionManager.ERR_ACCOUNT_ocked, ignoreCase = true)
        ) {
            sessionManager.handleSecurityRevocationOrBlock(initialErrorCode)
            return null
        }

        // Prevent infinite retry loops
        if (responseCount(response) >= 2) {
            return null
        }

        synchronized(this) {
            val currentAccessToken = sessionManager.getAccessToken()
            val requestAuthHeader = response.request.header(AuthHeaderInterceptor.HEADER_AUTHORIZATION)
            val requestToken = requestAuthHeader?.removePrefix("Bearer ")?.trim()

            // If another thread already refreshed the token while we waited for the lock, reuse it
            if (!currentAccessToken.isNullOrBlank() && currentAccessToken != requestToken) {
                return response.request.newBuilder()
                    .header(AuthHeaderInterceptor.HEADER_AUTHORIZATION, "Bearer $currentAccessToken")
                    .build()
            }

            val refreshToken = sessionManager.getRefreshToken()
            if (refreshToken.isNullOrBlank()) {
                return null
            }

            val deviceId = sessionManager.getOrCreateDeviceId()

            val refreshResult = runCatching {
                runBlocking {
                    authApiProvider.get().refresh(
                        RefreshRequestDto(
                            refreshToken = refreshToken,
                            deviceId = deviceId
                        )
                    )
                }
            }.getOrNull() ?: return null // Transient network failure: do NOT wipe local data

            if (refreshResult.isSuccessful) {
                val body = refreshResult.body() ?: return null
                sessionManager.updateTokens(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken
                )
                return response.request.newBuilder()
                    .header(AuthHeaderInterceptor.HEADER_AUTHORIZATION, "Bearer ${body.accessToken}")
                    .build()
            }

            // If server rejected refresh token with 401 or 403, inspect error code and trigger full local wipe
            if (refreshResult.code() == 401 || refreshResult.code() == 403) {
                val rawErrBody = runCatching { refreshResult.errorBody()?.string().orEmpty() }.getOrDefault("")
                val parsedErr = runCatching {
                    json.decodeFromString<ApiErrorDto>(rawErrBody)
                }.getOrNull()

                val errCode = parsedErr?.error?.ifBlank {
                    if (refreshResult.code() == 403) SessionManager.ERR_ACCOUNT_ocked else SessionManager.ERR_SESSION_REVOKED
                } ?: SessionManager.ERR_SESSION_REVOKED

                sessionManager.handleSecurityRevocationOrBlock(
                    errorCode = errCode,
                    serverMessage = parsedErr?.message
                )
            }

            return null
        }
    }

    private fun parseErrorCode(response: Response): String {
        return runCatching {
            val raw = response.peekBody(4096).string()
            if (raw.isNotBlank()) {
                json.decodeFromString<ApiErrorDto>(raw).error
            } else {
                ""
            }
        }.getOrDefault("")
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
