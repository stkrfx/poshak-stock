package com.poshak.stock.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Material 3 In-App Self-Update Dialog supporting:
 * 1. Force Update (`isForceUpdate == true`): non-dismissible (`BackHandler` disabled,
 *    `dismissOnBackPress = false`, `dismissOnClickOutside = false`), blocking app usage until updated.
 * 2. Optional Update (`isForceUpdate == false`): **"Update Now"** and **"Later"** buttons.
 * 3. Live In-App Download Progress (`LinearProgressIndicator` showing `0%` to `100%` and MB downloaded).
 * 4. Native Package Installer Launch via `FileProvider` (`content://com.poshak.stock.fileprovider/updates/stock-update.apk`)
 *    with automatic `ACTION_MANAGE_UNKNOWN_APP_SOURCES` permission handling on Android 8.0+.
 */
@Composable
fun InAppUpdateDialog(
    uiState: UpdateDialogUiState,
    onStartDownload: () -> Unit,
    onDismissOptional: () -> Unit
) {
    if (uiState is UpdateDialogUiState.Hidden) return

    val context = LocalContext.current
    var pendingApkToInstall by remember { mutableStateOf<File?>(null) }

    val unknownSourcesSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val apkFile = pendingApkToInstall
        if (apkFile != null && canInstallUnknownPackages(context)) {
            launchNativePackageInstaller(context, apkFile)
        }
    }

    val isForceUpdate = when (uiState) {
        is UpdateDialogUiState.PromptAvailable -> uiState.isForceUpdate
        is UpdateDialogUiState.Downloading -> uiState.isForceUpdate
        is UpdateDialogUiState.ReadyToInstall -> uiState.isForceUpdate
        is UpdateDialogUiState.Error -> uiState.isForceUpdate
        UpdateDialogUiState.Hidden -> false
    }

    // Disable Back button when a mandatory force update or active download is in progress
    BackHandler(enabled = isForceUpdate || uiState is UpdateDialogUiState.Downloading) {
        // Consumed intentionally
    }

    // Automatically trigger the Android package installer as soon as download + SHA-256 verification completes
    LaunchedEffect(uiState) {
        if (uiState is UpdateDialogUiState.ReadyToInstall) {
            pendingApkToInstall = uiState.apkFile
            if (canInstallUnknownPackages(context)) {
                launchNativePackageInstaller(context, uiState.apkFile)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                unknownSourcesSettingsLauncher.launch(settingsIntent)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isForceUpdate && uiState !is UpdateDialogUiState.Downloading) {
                onDismissOptional()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isForceUpdate && uiState !is UpdateDialogUiState.Downloading,
            dismissOnClickOutside = !isForceUpdate && uiState !is UpdateDialogUiState.Downloading
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = "App Update",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            val titleText = when (uiState) {
                is UpdateDialogUiState.PromptAvailable ->
                    if (uiState.isForceUpdate) "Mandatory Update Required" else "Update Available"
                is UpdateDialogUiState.Downloading -> "Downloading Update..."
                is UpdateDialogUiState.ReadyToInstall -> "Ready to Install"
                is UpdateDialogUiState.Error -> "Update Failed"
                UpdateDialogUiState.Hidden -> ""
            }
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            when (uiState) {
                is UpdateDialogUiState.PromptAvailable -> {
                    UpdateDetailsContent(
                        versionName = uiState.versionInfo.latestVersionName,
                        releaseNotes = uiState.versionInfo.releaseNotes,
                        isForceUpdate = uiState.isForceUpdate
                    )
                }

                is UpdateDialogUiState.Downloading -> {
                    val percent = (uiState.progressFraction * 100).toInt().coerceIn(0, 100)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Downloading Stock v${uiState.versionInfo.latestVersionName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = { uiState.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            val sizeText = if (uiState.totalMb > 0f) {
                                String.format(
                                    Locale.US,
                                    "%.1f MB / %.1f MB",
                                    uiState.downloadedMb,
                                    uiState.totalMb
                                )
                            } else {
                                String.format(Locale.US, "%.1f MB", uiState.downloadedMb)
                            }
                            Text(
                                text = sizeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is UpdateDialogUiState.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Stock v${uiState.versionInfo.latestVersionName} has been verified (SHA-256) and is ready to install.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Tap \"Install Now\" if the system installer did not open automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UpdateDialogUiState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                UpdateDialogUiState.Hidden -> {}
            }
        },
        confirmButton = {
            when (uiState) {
                is UpdateDialogUiState.PromptAvailable -> {
                    Button(onClick = onStartDownload) {
                        Text("Update Now")
                    }
                }

                is UpdateDialogUiState.Downloading -> {
                    // Disabled button while actively downloading
                    Button(onClick = {}, enabled = false) {
                        Text("Downloading...")
                    }
                }

                is UpdateDialogUiState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            pendingApkToInstall = uiState.apkFile
                            if (canInstallUnknownPackages(context)) {
                                launchNativePackageInstaller(context, uiState.apkFile)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val settingsIntent = Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                )
                                unknownSourcesSettingsLauncher.launch(settingsIntent)
                            }
                        }
                    ) {
                        Text("Install Now")
                    }
                }

                is UpdateDialogUiState.Error -> {
                    Button(onClick = onStartDownload) {
                        Text("Retry")
                    }
                }

                UpdateDialogUiState.Hidden -> {}
            }
        },
        dismissButton = {
            if (!isForceUpdate &&
                (uiState is UpdateDialogUiState.PromptAvailable || uiState is UpdateDialogUiState.Error)
            ) {
                TextButton(onClick = onDismissOptional) {
                    Text("Later")
                }
            }
        }
    )
}

@Composable
private fun UpdateDetailsContent(
    versionName: String,
    releaseNotes: String,
    isForceUpdate: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (isForceUpdate) {
            Text(
                text = "This update is required to continue using Stock.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        } else {
            Text(
                text = "A new version of Stock is ready to install.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (releaseNotes.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "What's new:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = releaseNotes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun canInstallUnknownPackages(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }
}

private fun launchNativePackageInstaller(context: Context, apkFile: File) {
    val authority = "${context.packageName}.fileprovider"
    val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
}
