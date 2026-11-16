package com.poshak.stock.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poshak.stock.BuildConfig
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.remote.dto.VersionCheckResponseDto
import com.poshak.stock.data.repository.ApkDownloadState
import com.poshak.stock.data.repository.AppUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * UI state for the In-App Self-Update Dialog (`InAppUpdateDialog`).
 */
sealed interface UpdateDialogUiState {
    data object Hidden : UpdateDialogUiState

    data class PromptAvailable(
        val versionInfo: VersionCheckResponseDto,
        val isForceUpdate: Boolean
    ) : UpdateDialogUiState

    data class Downloading(
        val versionInfo: VersionCheckResponseDto,
        val isForceUpdate: Boolean,
        val progressFraction: Float,
        val downloadedMb: Float,
        val totalMb: Float
    ) : UpdateDialogUiState

    data class ReadyToInstall(
        val versionInfo: VersionCheckResponseDto,
        val isForceUpdate: Boolean,
        val apkFile: File
    ) : UpdateDialogUiState

    data class Error(
        val versionInfo: VersionCheckResponseDto,
        val isForceUpdate: Boolean,
        val errorMessage: String
    ) : UpdateDialogUiState
}

/**
 * ViewModel managing startup and foreground resume (> 30 min) self-update checks,
 * in-app APK download progress, SHA-256 verification, and installer handoff.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val appUpdateRepository: AppUpdateRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateDialogUiState>(UpdateDialogUiState.Hidden)
    val uiState: StateFlow<UpdateDialogUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var hasCheckedOnColdStart = false

    /**
     * Checks for an app update on cold start or when returning to foreground after > 30 minutes.
     */
    fun checkForUpdateOnLaunchOrResume(isColdStart: Boolean = false) {
        if (!sessionManager.isLoggedIn.value) return

        val currentState = _uiState.value
        if (currentState is UpdateDialogUiState.Downloading ||
            currentState is UpdateDialogUiState.ReadyToInstall ||
            (currentState is UpdateDialogUiState.PromptAvailable && currentState.isForceUpdate)
        ) {
            return
        }

        val now = System.currentTimeMillis()
        val lastChecked = sessionManager.getLastUpdateCheckEpochMs()
        val thirtyMinutesMs = 30 * 60 * 1000L

        if (!isColdStart && hasCheckedOnColdStart && (now - lastChecked) < thirtyMinutesMs) {
            return
        }

        hasCheckedOnColdStart = true
        viewModelScope.launch {
            val result = appUpdateRepository.checkForUpdate(BuildConfig.VERSION_CODE)
            val info = result.getOrNull() ?: return@launch
            if (info.updateAvailable) {
                _uiState.value = UpdateDialogUiState.PromptAvailable(
                    versionInfo = info,
                    isForceUpdate = info.forceUpdate
                )
            }
        }
    }

    /**
     * Dismisses the update prompt ONLY if `isForceUpdate == false`.
     */
    fun dismissOptionalUpdate() {
        val current = _uiState.value
        if (current is UpdateDialogUiState.PromptAvailable && !current.isForceUpdate) {
            _uiState.value = UpdateDialogUiState.Hidden
        } else if (current is UpdateDialogUiState.Error && !current.isForceUpdate) {
            _uiState.value = UpdateDialogUiState.Hidden
        }
    }

    /**
     * Starts or retries streaming the APK binary into `cacheDir/updates/stock-update.apk`.
     */
    fun startUpdateDownload() {
        val versionInfo = when (val current = _uiState.value) {
            is UpdateDialogUiState.PromptAvailable -> current.versionInfo
            is UpdateDialogUiState.Error -> current.versionInfo
            is UpdateDialogUiState.ReadyToInstall -> current.versionInfo
            else -> return
        }

        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            appUpdateRepository.streamAndVerifyApk(
                versionCode = versionInfo.latestVersionCode,
                expectedSha256 = versionInfo.apkSha256
            ).collect { state ->
                when (state) {
                    is ApkDownloadState.Downloading -> {
                        val dlMb = state.downloadedBytes.toFloat() / (1024f * 1024f)
                        val totMb = if (state.totalBytes > 0L) {
                            state.totalBytes.toFloat() / (1024f * 1024f)
                        } else {
                            0f
                        }
                        _uiState.value = UpdateDialogUiState.Downloading(
                            versionInfo = versionInfo,
                            isForceUpdate = versionInfo.forceUpdate,
                            progressFraction = state.progressFraction,
                            downloadedMb = dlMb,
                            totalMb = totMb
                        )
                    }

                    is ApkDownloadState.VerifiedReady -> {
                        _uiState.value = UpdateDialogUiState.ReadyToInstall(
                            versionInfo = versionInfo,
                            isForceUpdate = versionInfo.forceUpdate,
                            apkFile = state.apkFile
                        )
                    }

                    is ApkDownloadState.Failed -> {
                        _uiState.value = UpdateDialogUiState.Error(
                            versionInfo = versionInfo,
                            isForceUpdate = versionInfo.forceUpdate,
                            errorMessage = state.reason
                        )
                    }
                }
            }
        }
    }
}
