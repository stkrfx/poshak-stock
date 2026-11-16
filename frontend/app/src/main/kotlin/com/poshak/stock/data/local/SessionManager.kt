package com.poshak.stock.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.WorkManager
import com.poshak.stock.data.local.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages encrypted session tokens, user role visibility (`admin` vs `employee`),
 * sync timestamps, and the mandatory security full-data-wipe handler.
 *
 * Per Section 3.3 of the specification:
 * As soon as the Android app receives HTTP 401 (`session_revoked`) or HTTP 403 (`account_blocked`):
 * 1. Immediately wipes all 6 Room tables (`products`, `stock`, `autocomplete_suggestions`,
 *    `offline_download_scopes`, `offline_scope_products`, `search_history`).
 * 2. Wipes tokens from EncryptedSharedPreferences / Keystore.
 * 3. Cancels all WorkManager tasks.
 * 4. Emits a navigation event to clear the back stack and route to the Login screen with:
 *    `"Your account has been deactivated. Contact the shop owner."`
 *
 * Ordinary network errors (timeouts, DNS failures, 5xx) NEVER wipe local data.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wipeMutex = Mutex()

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs(context)
    }

    private val _isLoggedIn = MutableStateFlow(hasValidTokensInternal())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userRole = MutableStateFlow(prefs.getString(KEY_USER_ROLE, ROLE_EMPLOYEE) ?: ROLE_EMPLOYEE)
    val userRole: StateFlow<String> = _userRole.asStateFlow()

    private val _userName = MutableStateFlow(prefs.getString(KEY_USER_NAME, "") ?: "")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _securityLogoutMessage = MutableStateFlow<String?>(null)
    val securityLogoutMessage: StateFlow<String?> = _securityLogoutMessage.asStateFlow()

    private val _securityLogoutEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val securityLogoutEvents: SharedFlow<String> = _securityLogoutEvents.asSharedFlow()

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If keystore gets corrupted after OS restore, clear and recreate cleanly
            ctx.deleteSharedPreferences(PREFS_FILE_NAME)
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private fun hasValidTokensInternal(): Boolean {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        return !access.isNullOrBlank() && !refresh.isNullOrBlank()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getUserId(): String = prefs.getString(KEY_USER_ID, "") ?: ""

    fun getUserName(): String = prefs.getString(KEY_USER_NAME, "") ?: ""

    fun getUserMobile(): String = prefs.getString(KEY_USER_MOBILE, "") ?: ""

    fun getUserRole(): String = prefs.getString(KEY_USER_ROLE, ROLE_EMPLOYEE) ?: ROLE_EMPLOYEE

    fun isAdmin(): Boolean = getUserRole().equals(ROLE_ADMIN, ignoreCase = true)

    /**
     * Saves session tokens and authenticated user details upon `POST /v1/auth/login`.
     */
    fun saveLoginSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        userName: String,
        userMobile: String,
        userRole: String
    ) {
        val normalizedRole = userRole.trim().lowercase()
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken.trim())
            .putString(KEY_REFRESH_TOKEN, refreshToken.trim())
            .putString(KEY_USER_ID, userId.trim())
            .putString(KEY_USER_NAME, userName.trim())
            .putString(KEY_USER_MOBILE, userMobile.trim())
            .putString(KEY_USER_ROLE, normalizedRole)
            .apply()

        _securityLogoutMessage.value = null
        _userRole.value = normalizedRole
        _userName.value = userName.trim()
        _isLoggedIn.value = true

        // If user is an employee, proactively purge any party autocomplete suggestions from Room
        if (normalizedRole != ROLE_ADMIN) {
            ioScope.launch {
                runCatching { appDatabase.autocompleteDao().deletePartySuggestions() }
            }
        }
    }

    /**
     * Atomically updates rotated `access_token` and `refresh_token` upon `POST /v1/auth/refresh`.
     */
    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken.trim())
            .putString(KEY_REFRESH_TOKEN, refreshToken.trim())
            .apply()
        _isLoggedIn.value = true
    }

    /**
     * Returns a stable hardware/installation device identifier bound to the refresh token.
     */
    @SuppressLint("HardwareIds")
    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        val generated = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            UUID.randomUUID().toString().replace("-", "")
        }
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }.ifBlank { "Android Device" }
    }

    fun getLastIncrementalSyncTimestamp(): String? {
        return prefs.getString(KEY_LAST_SYNC_AT, null)
    }

    fun setLastIncrementalSyncTimestamp(rfc3339Timestamp: String) {
        prefs.edit().putString(KEY_LAST_SYNC_AT, rfc3339Timestamp.trim()).apply()
    }

    fun getLastUpdateCheckEpochMs(): Long {
        return prefs.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L)
    }

    fun setLastUpdateCheckEpochMs(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, epochMs).apply()
    }

    fun clearSecurityLogoutMessage() {
        _securityLogoutMessage.value = null
    }

    /**
     * Triggered when the server returns 401 `session_revoked` or 403 `account_blocked`.
     * Immediately wipes all Room tables, EncryptedSharedPreferences tokens, and WorkManager jobs.
     */
    fun handleSecurityRevocationOrBlock(errorCode: String, serverMessage: String? = null) {
        val displayMessage = when {
            errorCode.equals(ERR_ACCOUNT_ocked, ignoreCase = true) -> DEACTIVATED_ACCOUNT_MESSAGE
            !serverMessage.isNullOrBlank() -> serverMessage
            else -> DEACTIVATED_ACCOUNT_MESSAGE
        }
        ioScope.launch {
            performFullLocalWipe(displayMessage)
        }
    }

    /**
     * Executes a complete, transactional wipe of all local Room tables, tokens, and background sync tasks.
     */
    suspend fun performFullLocalWipe(reasonMessage: String? = DEACTIVATED_ACCOUNT_MESSAGE) {
        wipeMutex.withLock {
            // 1. Cancel all scheduled and running WorkManager tasks
            runCatching {
                WorkManager.getInstance(context).cancelAllWork()
            }

            // 2. Wipe tokens and user state from EncryptedSharedPreferences (preserving stable device_id)
            val deviceId = prefs.getString(KEY_DEVICE_ID, null)
            prefs.edit().clear().apply {
                if (!deviceId.isNullOrBlank()) {
                    putString(KEY_DEVICE_ID, deviceId)
                }
            }.apply()

            // 3. Wipe all 6 Room database tables transactionally
            runCatching {
                appDatabase.wipeAllTablesTransactionally()
            }

            // 4. Update reactive auth flows and emit security logout event
            _userRole.value = ROLE_EMPLOYEE
            _userName.value = ""
            _isLoggedIn.value = false

            if (!reasonMessage.isNullOrBlank()) {
                _securityLogoutMessage.value = reasonMessage
                _securityLogoutEvents.tryEmit(reasonMessage)
            }
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "poshak_stock_secure_session"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_MOBILE = "user_mobile"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC_AT = "last_incremental_sync_at"
        private const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"

        const val ROLE_ADMIN = "admin"
        const val ROLE_EMPLOYEE = "employee"

        const val ERR_SESSION_REVOKED = "session_revoked"
        const val ERR_ACCOUNT_ocked = "account_blocked"

        const val DEACTIVATED_ACCOUNT_MESSAGE =
            "Your account has been deactivated. Contact the shop owner."
    }
}
