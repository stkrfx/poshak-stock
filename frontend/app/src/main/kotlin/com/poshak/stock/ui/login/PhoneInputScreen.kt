package com.poshak.stock.ui.login

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity

/**
 * Step 1 of the One-Time Secure Login Flow:
 * Prompts the user via the Android Phone Number Hint bottom sheet (or manual `+91XXXXXXXXXX` input),
 * displays any account deactivation warning banner (`"Your account has been deactivated. Contact the shop owner."`),
 * and validates authorization via `POST /v1/auth/pre-login` before triggering Firebase SMS OTP.
 */
@Composable
fun PhoneInputScreen(
    uiState: LoginUiState,
    onMobileInputChanged: (String) -> Unit,
    onPhoneHintSelected: (String, Activity) -> Unit,
    onSubmitPhone: (Activity) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hasAutoLaunchedHint by rememberSaveable { mutableStateOf(false) }

    val phoneHintLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && activity != null) {
            runCatching {
                val phoneNumber = Identity.getSignInClient(activity)
                    .getPhoneNumberFromIntent(result.data)
                if (!phoneNumber.isNullOrBlank()) {
                    onPhoneHintSelected(phoneNumber, activity)
                }
            }
        }
    }

    fun requestPhoneNumberHint() {
        val hostActivity = activity ?: return
        val request = GetPhoneNumberHintIntentRequest.builder().build()
        Identity.getSignInClient(hostActivity)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener { pendingIntent ->
                runCatching {
                    phoneHintLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                }
            }
    }

    // Automatically trigger the system Phone Number Hint bottom sheet once on initial launch
    LaunchedEffect(Unit) {
        if (!hasAutoLaunchedHint && uiState.mobileInput.isBlank()) {
            hasAutoLaunchedHint = true
            requestPhoneNumberHint()
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = "Stock Logo",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Stock",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Authorized Staff & Admin Inventory Portal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Security Deactivation Banner (when redirected after 401 session_revoked or 403 account_blocked)
            if (!uiState.securityBannerMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Security Notice",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = uiState.securityBannerMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = uiState.mobileInput,
                onValueChange = onMobileInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Mobile Number") },
                placeholder = { Text("+919876543210") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Phone"
                    )
                },
                trailingIcon = {
                    if (uiState.mobileInput.isNotEmpty()) {
                        IconButton(onClick = { onMobileInputChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear mobile number"
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
                    } else {
                        Text("Enter your registered 10-digit mobile number or +91XXXXXXXXXX")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (activity != null && uiState.mobileInput.isNotBlank()) {
                            onSubmitPhone(activity)
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (activity != null) {
                        onSubmitPhone(activity)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !uiState.isLoading && uiState.mobileInput.isNotBlank()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = "Continue & Send OTP",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { requestPhoneNumberHint() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            ) {
                Text("Select from Device SIM Hint")
            }
        }
    }
}
