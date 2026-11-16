package com.poshak.stock.data.remote.interceptor

import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.remote.dto.ApiErrorDto
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the `Authorization: Bearer <access_token>` header to all protected API calls
 * and monitors responses for `401 Unauthorized` (`session_revoked`) or `403 Forbidden` (`account_blocked`)
 * to trigger an immediate local data wipe.
 */
@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
    private val json: Json
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val encodedPath = originalRequest.url.encodedPath

        val isPublicEndpoint = PUBLIC_PATHS.any { encodedPath.endsWith(it) }
        val requestBuilder = originalRequest.newBuilder()

        if (!isPublicEndpoint && originalRequest.header(HEADER_AUTHORIZATION) == null) {
            val accessToken = sessionManager.getAccessToken()
            if (!accessToken.isNullOrBlank()) {
                requestBuilder.header(HEADER_AUTHORIZATION, "Bearer $accessToken")
            }
        }

        val response = chain.proceed(requestBuilder.build())

        // Inspect 401/403 responses for explicit "session_revoked" or "account_blocked" error codes
        if (!isPublicEndpoint && (response.code == 401 || response.code == 403)) {
            runCatching {
                val peeked = response.peekBody(4096).string()
                if (peeked.isNotBlank()) {
                    val apiError = json.decodeFromString<ApiErrorDto>(peeked)
                    if (
                        apiError.error.equals(SessionManager.ERR_SESSION_REVOKED, ignoreCase = true) ||
                        apiError.error.equals(SessionManager.ERR_ACCOUNT_ocked, ignoreCase = true)
                    ) {
                        sessionManager.handleSecurityRevocationOrBlock(
                            errorCode = apiError.error,
                            serverMessage = apiError.message
                        )
                    }
                }
            }
        }

        return response
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"

        private val PUBLIC_PATHS = listOf(
            "/v1/health",
            "/v1/auth/pre-login",
            "/v1/auth/login",
            "/v1/auth/refresh"
        )
    }
}
