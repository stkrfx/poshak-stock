package com.poshak.stock.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response returned by `GET /v1/app/version?current_version_code=<versionCode>`.
 */
@Serializable
data class VersionCheckResponseDto(
    @SerialName("latest_version_code")
    val latestVersionCode: Int = 1,
    @SerialName("latest_version_name")
    val latestVersionName: String = "1.0.0",
    @SerialName("apk_download_url")
    val apkDownloadUrl: String = "",
    @SerialName("apk_sha256")
    val apkSha256: String = "",
    @SerialName("release_notes")
    val releaseNotes: String = "",
    @SerialName("update_available")
    val updateAvailable: Boolean = false,
    @SerialName("force_update")
    val forceUpdate: Boolean = false
)
