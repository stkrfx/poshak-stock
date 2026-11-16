package com.poshak.stock.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Standard error response returned by the Go Backend API.
 */
@Serializable
data class ApiErrorDto(
    @SerialName("error")
    val error: String = "",
    @SerialName("message")
    val message: String = ""
)

@Serializable
data class PreLoginRequestDto(
    @SerialName("mobile")
    val mobile: String
)

@Serializable
data class PreLoginResponseDto(
    @SerialName("allowed")
    val allowed: Boolean = false
)

@Serializable
data class LoginRequestDto(
    @SerialName("firebase_id_token")
    val firebaseIdToken: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_type")
    val deviceType: String = "android"
)

@Serializable
data class UserSummaryDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("mobile")
    val mobile: String,
    @SerialName("role")
    val role: String
)

@Serializable
data class LoginResponseDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Int = 900,
    @SerialName("user")
    val user: UserSummaryDto
)

@Serializable
data class RefreshRequestDto(
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("device_id")
    val deviceId: String
)

@Serializable
data class RefreshResponseDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Int = 900
)

@Serializable
data class LogoutRequestDto(
    @SerialName("refresh_token")
    val refreshToken: String
)

@Serializable
data class LogoutResponseDto(
    @SerialName("message")
    val message: String = ""
)
