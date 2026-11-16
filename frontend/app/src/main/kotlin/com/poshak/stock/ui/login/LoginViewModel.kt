package com.poshak.stock.ui.login

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.poshak.stock.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class LoginStep {
    PHONE_INPUT,
    OTP_VERIFY
}

data class LoginUiState(
    val step: LoginStep = LoginStep.PHONE_INPUT,
    val mobileInput: String = "",
    val normalizedMobile: String = "",
    val otpInput: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val securityBannerMessage: String? = null,
    val resendCooldownSeconds: Int = 0,
    val isDevBypassActive: Boolean = false
)

/**
 * ViewModel orchestrating the One-Time Secure Login Flow (Section 3.1):
 * 1. Android Phone Number Hint bottom sheet (or manual input `+91XXXXXXXXXX`).
 * 2. Calls `POST /v1/auth/pre-login` first.
 * 3. Only on `200 OK { "allowed": true }` invokes Firebase Phone Auth (`verifyPhoneNumber`).
 * 4. Verifies the 6-digit OTP (autofilled via SMS Retriever API or typed), obtains `firebase_id_token`,
 *    and exchanges it via `POST /v1/auth/login` for the JWT `access_token` + `refresh_token`.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(
            securityBannerMessage = authRepository.securityLogoutMessage.value
        )
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var storedVerificationId: String? = null
    private var storedResendingToken: PhoneAuthProvider.ForceResendingToken? = null
    private var cooldownJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.securityLogoutMessage.collect { msg ->
                _uiState.update { it.copy(securityBannerMessage = msg) }
            }
        }
    }

    fun onMobileInputChanged(value: String) {
        _uiState.update {
            it.copy(
                mobileInput = value,
                errorMessage = null
            )
        }
    }

    fun onOtpInputChanged(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(6)
        _uiState.update {
            it.copy(
                otpInput = digitsOnly,
                errorMessage = null
            )
        }
        if (digitsOnly.length == 6 && !_uiState.value.isLoading) {
            verifyOtpCode(digitsOnly)
        }
    }

    /**
     * Called when the user selects a phone number from the Google Play Services Phone Number Hint sheet.
     */
    fun onPhoneHintSelected(phoneNumber: String, activity: Activity) {
        val normalized = authRepository.normalizeMobileNumber(phoneNumber)
        _uiState.update {
            it.copy(
                mobileInput = normalized,
                errorMessage = null
            )
        }
        submitPhoneForPreLogin(activity)
    }

    /**
     * Step 1 & 2: Calls `POST /v1/auth/pre-login` and, ONLY if `allowed == true`, triggers Firebase Phone Auth.
     */
    fun submitPhoneForPreLogin(activity: Activity, isResend: Boolean = false) {
        val rawMobile = _uiState.value.mobileInput
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null
                )
            }
            authRepository.clearSecurityLogoutMessage()

            val preLoginResult = authRepository.preLoginCheck(rawMobile)
            if (preLoginResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = preLoginResult.exceptionOrNull()?.message
                            ?: "This number is not authorized"
                    )
                }
                return@launch
            }

            val normalizedMobile = preLoginResult.getOrThrow()
            _uiState.update { it.copy(normalizedMobile = normalizedMobile) }

            // Check if Firebase is initialized in this build; if not (e.g. local dev without google-services.json),
            // allow OTP step to complete using the dev_phone:<mobile> bypass token when DEV_BYPASS_FIREBASE=true on backend.
            val firebaseInitialized = runCatching {
                FirebaseApp.getApps(activity).isNotEmpty()
            }.getOrDefault(false)

            if (!firebaseInitialized) {
                storedVerificationId = "DEV_BYPASS_VERIFICATION_ID"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        step = LoginStep.OTP_VERIFY,
                        otpInput = "",
                        isDevBypassActive = true
                    )
                }
                startResendCooldown()
                return@launch
            }

            startFirebasePhoneVerification(
                activity = activity,
                phoneNumber = normalizedMobile,
                isResend = isResend
            )
        }
    }

    private fun startFirebasePhoneVerification(
        activity: Activity,
        phoneNumber: String,
        isResend: Boolean
    ) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Instant verification or auto-retrieval by Play Services
                val smsCode = credential.smsCode
                if (!smsCode.isNullOrBlank()) {
                    _uiState.update { it.copy(otpInput = smsCode) }
                }
                signInWithFirebaseCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "SMS verification failed. Please try again."
                    )
                }
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                storedVerificationId = verificationId
                storedResendingToken = token
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        step = LoginStep.OTP_VERIFY,
                        otpInput = "",
                        errorMessage = null,
                        isDevBypassActive = false
                    )
                }
                startResendCooldown()
            }
        }

        val firebaseAuth = FirebaseAuth.getInstance()
        val optionsBuilder = PhoneAuthOptions.newBuilder(firebaseAuth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (isResend && storedResendingToken != null) {
            optionsBuilder.setForceResendingToken(storedResendingToken!!)
        }

        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
    }

    /**
     * Step 3: Verifies the 6-digit OTP code entered by the user or extracted by SMS Retriever API.
     */
    fun verifyOtpCode(otpCode: String = _uiState.value.otpInput) {
        val cleanOtp = otpCode.trim()
        if (cleanOtp.length != 6) {
            _uiState.update { it.copy(errorMessage = "Enter the 6-digit OTP sent to your phone") }
            return
        }

        if (_uiState.value.isDevBypassActive) {
            // Dev mode without google-services.json: send "dev_phone:<mobile>" to backend
            exchangeTokenWithBackend("dev_phone:${_uiState.value.normalizedMobile}")
            return
        }

        val verificationId = storedVerificationId
        if (verificationId.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Verification session expired. Please request a new OTP.")
            }
            return
        }

        val credential = PhoneAuthProvider.getCredential(verificationId, cleanOtp)
        signInWithFirebaseCredential(credential)
    }

    private fun signInWithFirebaseCredential(credential: PhoneAuthCredential) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val authResult = FirebaseAuth.getInstance()
                    .signInWithCredential(credential)
                    .await()
                val firebaseUser = authResult.user
                    ?: throw IllegalStateException("Firebase authentication returned no user")
                val tokenResult = firebaseUser.getIdToken(false).await()
                val idToken = tokenResult.token
                    ?: throw IllegalStateException("Failed to retrieve Firebase ID token")

                exchangeTokenWithBackend(idToken)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Invalid OTP code. Please try again."
                    )
                }
            }
        }
    }

    private fun exchangeTokenWithBackend(firebaseIdToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val loginResult = authRepository.loginWithFirebaseToken(firebaseIdToken)
            if (loginResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        otpInput = ""
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = loginResult.exceptionOrNull()?.message
                            ?: "Authentication failed on server"
                    )
                }
            }
        }
    }

    fun returnToPhoneInput() {
        cooldownJob?.cancel()
        _uiState.update {
            it.copy(
                step = LoginStep.PHONE_INPUT,
                otpInput = "",
                errorMessage = null,
                resendCooldownSeconds = 0
            )
        }
    }

    private fun startResendCooldown(seconds: Int = 45) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _uiState.update { it.copy(resendCooldownSeconds = remaining) }
                if (remaining > 0) delay(1000L)
            }
        }
    }
}
