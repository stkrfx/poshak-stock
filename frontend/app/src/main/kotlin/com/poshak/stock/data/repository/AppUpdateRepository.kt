package com.poshak.stock.data.repository

import android.content.Context
import com.poshak.stock.BuildConfig
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.remote.api.UpdateApi
import com.poshak.stock.data.remote.dto.VersionCheckResponseDto
import com.poshak.stock.data.sync.NetworkQualityChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress states emitted while streaming and verifying an in-app update APK.
 */
sealed interface ApkDownloadState {
    data class Downloading(
        val progressFraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : ApkDownloadState

    data class VerifiedReady(
        val apkFile: File
    ) : ApkDownloadState

    data class Failed(
        val reason: String
    ) : ApkDownloadState
}

/**
 * Handles the In-App Self-Update System (Section 7):
 * 1. Checks `GET /v1/app/version?current_version_code=<BuildConfig.VERSION_CODE>`.
 * 2. Streams `GET /v1/app/download/{version_code}` into `context.cacheDir/updates/stock-update.apk`
 *    (exposed via `content://com.poshak.stock.fileprovider/updates/stock-update.apk`).
 * 3. Computes and verifies the SHA-256 digest while streaming; deletes the file if tampered or mismatched.
 */
@Singleton
class AppUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateApi: UpdateApi,
    private val sessionManager: SessionManager,
    private val networkQualityChecker: NetworkQualityChecker
) {

    /**
     * Checks the backend for optional or mandatory (`force_update`) APK updates.
     */
    suspend fun checkForUpdate(
        currentVersionCode: Int = BuildConfig.VERSION_CODE
    ): Result<VersionCheckResponseDto?> = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn.value || !networkQualityChecker.isCurrentlyOnline()) {
            return@withContext Result.success(null)
        }

        runCatching {
            val response = updateApi.checkVersion(currentVersionCode = currentVersionCode)
            if (response.isSuccessful) {
                val body = response.body()
                sessionManager.setLastUpdateCheckEpochMs(System.currentTimeMillis())
                body
            } else {
                null
            }
        }
    }

    /**
     * Streams the APK binary for [versionCode] directly into `context.cacheDir/updates/stock-update.apk`,
     * emits live download progress, and verifies the SHA-256 hash against [expectedSha256].
     */
    fun streamAndVerifyApk(
        versionCode: Int,
        expectedSha256: String
    ): Flow<ApkDownloadState> = flow {
        emit(ApkDownloadState.Downloading(0f, 0L, 0L))

        val updatesDir = File(context.cacheDir, UPDATES_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        val targetApkFile = File(updatesDir, APK_FILE_NAME)
        val tempApkFile = File(updatesDir, "$APK_FILE_NAME.part")

        if (tempApkFile.exists()) {
            tempApkFile.delete()
        }

        val response = try {
            updateApi.downloadApk(versionCode = versionCode)
        } catch (e: Exception) {
            emit(ApkDownloadState.Failed("Network error while downloading update APK. Please retry."))
            return@flow
        }

        if (!response.isSuccessful) {
            emit(ApkDownloadState.Failed("Failed to download APK (HTTP ${response.code()})."))
            return@flow
        }

        val body = response.body()
        if (body == null) {
            emit(ApkDownloadState.Failed("Empty APK payload received from server."))
            return@flow
        }

        val totalBytes = body.contentLength()
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            body.byteStream().use { input ->
                FileOutputStream(tempApkFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastEmittedPercent = -1
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read

                        val fraction = if (totalBytes > 0L) {
                            (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val percent = (fraction * 100).toInt()
                        if (percent != lastEmittedPercent || totalBytes <= 0L) {
                            lastEmittedPercent = percent
                            emit(
                                ApkDownloadState.Downloading(
                                    progressFraction = fraction,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            tempApkFile.delete()
            emit(ApkDownloadState.Failed("Download interrupted: ${e.localizedMessage ?: "Network error"}"))
            return@flow
        }

        val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val normalizedExpected = expectedSha256.trim().lowercase()

        if (normalizedExpected.isNotEmpty() && !computedSha256.equals(normalizedExpected, ignoreCase = true)) {
            tempApkFile.delete()
            targetApkFile.delete()
            emit(
                ApkDownloadState.Failed(
                    "APK security checksum (SHA-256) mismatch. File deleted; tap Retry to download again."
                )
            )
            return@flow
        }

        if (targetApkFile.exists()) {
            targetApkFile.delete()
        }
        if (!tempApkFile.renameTo(targetApkFile)) {
            tempApkFile.copyTo(targetApkFile, overwrite = true)
            tempApkFile.delete()
        }

        emit(ApkDownloadState.VerifiedReady(apkFile = targetApkFile))
    }.flowOn(Dispatchers.IO)

    companion object {
        const val UPDATES_SUBDIR = "updates"
        const val APK_FILE_NAME = "stock-update.apk"
    }
}
