package com.poshak.stock.ui.login

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Step 2 of the One-Time Secure Login Flow:
 * Listens for the incoming SMS via the Google Play Services SMS Retriever API to autofill the
 * 6-digit OTP and exchanges the verified `firebase_id_token` with `POST /v1/auth/login`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpVerifyScreen(
    uiState: LoginUiState,
    onOtpInputChanged: (String) -> Unit,
    onVerifyOtpClicked: () -> Unit,
    onResendOtpClicked: (Activity) -> Unit,
    onBackToPhoneInput: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Register SMS Retriever API receiver to autofill the 6-digit OTP automatically
    DisposableEffect(context) {
        runCatching {
            SmsRetriever.getClient(context).startSmsRetriever()
        }

        val smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == SmsRetriever.SMS_RETRIEVED_ACTION) {
                    val extras = intent.extras ?: return
                    @Suppress("DEPRECATION")
                    val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                    if (status.statusCode == CommonStatusCodes.SUCCESS) {
                        val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                        val otpMatch = Regex("\\b(\\d{6})\\b").find(message)?.groupValues?.getOrNull(1)
                        if (!otpMatch.isNullOrBlank()) {
                            onOtpInputChanged(otpMatch)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                smsReceiver,
                filter,
                SmsRetriever.SEND_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        onDispose {
            runCatching {
                context.unregisterReceiver(smsReceiver)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify OTP") },
                navigationIcon = {
                    IconButton(
                        onClick = onBackToPhoneInput,
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Change phone number"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter 6-Digit Verification Code",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sent to ${uiState.normalizedMobile}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = uiState.otpInput,
                onValueChange = onOtpInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6-Digit OTP") },
                placeholder = { Text("123456") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "OTP"
                    )
                },
                trailingIcon = {
                    if (uiState.otpInput.isNotEmpty()) {
                        IconButton(onClick = { onOtpInputChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear OTP"
                            )
                        }
                    }
                },
                singleLine = true,
                enabled = !uiState.isLoading,
                isError = !uiState.errorMessage.isNullOrBlank(),
                supportingText = {
                    if (!uiState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (uiState.isDevBypassActive) {
                        Text("Dev Bypass active: enter any 6 digits (e.g. 123456) to verify")
                    } else {
                        Text("OTP will autofill automatically via SMS Retriever")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (uiState.otpInput.length == 6 && !uiState.isLoading) {
                            onVerifyOtpClicked()
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onVerifyOtpClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !uiState.isLoading && uiState.otpInput.length == 6
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = "Verify & Sign In",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBackToPhoneInput,
                    enabled = !uiState.isLoading
                ) {
                    Text("Change Number")
                }

                TextButton(
                    onClick = {
                        if (activity != null) {
                            onResendOtpClicked(activity)
                        }
                    },
                    enabled = !uiState.isLoading && uiState.resendCooldownSeconds == 0
                ) {
                    val label = if (uiState.resendCooldownSeconds > 0) {
                        "Resend OTP (${uiState.resendCooldownSeconds}s)"
                    } else {
                        "Resend OTP"
                    }
                    Text(label)
                }
            }
        }
    }
}
