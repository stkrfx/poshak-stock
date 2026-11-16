package com.poshak.stock.data.repository

import android.content.Context
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.remote.api.AuthApi
import com.poshak.stock.data.remote.dto.ApiErrorDto
import com.poshak.stock.data.remote.dto.LoginRequestDto
import com.poshak.stock.data.remote.dto.LogoutRequestDto
import com.poshak.stock.data.remote.dto.PreLoginRequestDto
import com.poshak.stock.data.remote.dto.UserSummaryDto
import com.poshak.stock.data.sync.IncrementalSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the One-Time Secure Login Flow:
 * 1. `POST /v1/auth/pre-login` (verifies phone number exists and is `'active'` before Firebase SMS cost).
 * 2. `POST /v1/auth/login` (exchanges verified `firebase_id_token` for JWT `access_token` + opaque `refresh_token`).
 * 3. `POST /v1/auth/logout` and security session revocation handling.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val json: Json
) {
    val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
    val userRole: StateFlow<String> = sessionManager.userRole
    val userName: StateFlow<String> = sessionManager.userName
    val securityLogoutMessage: StateFlow<String?> = sessionManager.securityLogoutMessage
    val securityLogoutEvents: SharedFlow<String> = sessionManager.securityLogoutEvents

    fun isAdmin(): Boolean = sessionManager.isAdmin()

    fun clearSecurityLogoutMessage() {
        sessionManager.clearSecurityLogoutMessage()
    }

    /**
     * Normalizes an Indian or E.164 phone number (`+91XXXXXXXXXX`).
     */
    fun normalizeMobileNumber(rawMobile: String): String {
        val cleaned = rawMobile.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
        return when {
            cleaned.length == 10 && cleaned.all { it.isDigit() } -> "+91$cleaned"
            cleaned.length == 12 && cleaned.startsWith("91") && cleaned.all { it.isDigit() } -> "+$cleaned"
            else -> cleaned
        }
    }

    /**
     * Step 1: Calls `POST /v1/auth/pre-login` with `{ "mobile": "+91XXXXXXXXXX" }`.
     * Only on `200 OK { "allowed": true }` may the caller invoke Firebase Phone Auth.
     */
    suspend fun preLoginCheck(rawMobile: String): Result<String> = withContext(Dispatchers.IO) {
        val normalized = normalizeMobileNumber(rawMobile)
        if (!normalized.matches(Regex("^\\+[1-9]\\d{9,14}$"))) {
            return@withContext Result.failure(
                IllegalArgumentException("Enter a valid 10-digit mobile number (e.g., +919876543210)")
            )
        }

        runCatching {
            val response = authApi.preLogin(PreLoginRequestDto(mobile = normalized))
            if (response.isSuccessful && response.body()?.allowed == true) {
                return@withContext Result.success(normalized)
            }

            val errDto = parseErrorBody(response.errorBody()?.string())
            val message = when (response.code()) {
                403 -> errDto?.message?.ifBlank { "This number is not authorized" }
                    ?: "This number is not authorized"
                429 -> errDto?.message?.ifBlank { "Too many attempts. Please try again later." }
                    ?: "Too many attempts. Please try again later."
                else -> errDto?.message?.ifBlank { "Unable to verify phone number (${response.code()})" }
                    ?: "Unable to verify phone number (${response.code()})"
            }
            Result.failure(IllegalStateException(message))
        }.getOrElse { e ->
            Result.failure(
                IllegalStateException(
                    "Network error while checking phone authorization. Check your internet connection.",
                    e
                )
            )
        }
    }

    /**
     * Step 2: Calls `POST /v1/auth/login` with the verified `firebase_id_token` and device metadata.
     */
    suspend fun loginWithFirebaseToken(firebaseIdToken: String): Result<UserSummaryDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = LoginRequestDto(
                    firebaseIdToken = firebaseIdToken.trim(),
                    deviceId = sessionManager.getOrCreateDeviceId(),
                    deviceName = sessionManager.getDeviceName(),
                    deviceType = "android"
                )
                val response = authApi.login(request)
                if (response.isSuccessful) {
                    val body = response.body()
                        ?: return@withContext Result.failure(IllegalStateException("Empty login response from server"))

                    sessionManager.saveLoginSession(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        userId = body.user.id,
                        userName = body.user.name,
                        userMobile = body.user.mobile,
                        userRole = body.user.role
                    )

                    // Schedule background incremental sync now that the user is authenticated
                    IncrementalSyncWorker.enqueuePeriodicAndImmediateSync(context)

                    return@withContext Result.success(body.user)
                }

                val errDto = parseErrorBody(response.errorBody()?.string())
                if (errDto?.error.equals(SessionManager.ERR_ACCOUNT_ocked, ignoreCase = true)) {
                    sessionManager.handleSecurityRevocationOrBlock(
                        errorCode = SessionManager.ERR_ACCOUNT_ocked,
                        serverMessage = errDto?.message
                    )
                }
                val message = errDto?.message?.ifBlank {
                    "Login failed (HTTP ${response.code()})"
                } ?: "Login failed (HTTP ${response.code()})"
                Result.failure(IllegalStateException(message))
            }.getOrElse { e ->
                Result.failure(
                    IllegalStateException("Network error during login. Please try again.", e)
                )
            }
        }

    /**
     * Logs the user out, revokes the refresh token on the server if reachable, and wipes local session state.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val refreshToken = sessionManager.getRefreshToken()
        if (!refreshToken.isNullOrBlank()) {
            runCatching {
                authApi.logout(LogoutRequestDto(refreshToken = refreshToken))
            }
        }
        sessionManager.performFullLocalWipe(reasonMessage = null)
    }

    private fun parseErrorBody(rawBody: String?): ApiErrorDto? {
        if (rawBody.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString<ApiErrorDto>(rawBody)
        }.getOrNull()
    }
}
