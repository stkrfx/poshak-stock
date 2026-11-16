package com.poshak.stock.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.poshak.stock.ui.login.LoginStep
import com.poshak.stock.ui.login.LoginViewModel
import com.poshak.stock.ui.login.OtpVerifyScreen
import com.poshak.stock.ui.login.PhoneInputScreen
import com.poshak.stock.ui.main.StockMainScreen
import com.poshak.stock.ui.main.StockMainViewModel
import com.poshak.stock.ui.offline.OfflineDownloadsViewModel
import com.poshak.stock.ui.scanner.BarcodeScannerScreen
import com.poshak.stock.ui.update.AppUpdateViewModel
import com.poshak.stock.ui.update.InAppUpdateDialog

/**
 * Top-level Compose Navigation Graph (`AppNavGraph`).
 *
 * Enforces:
 * 1. Automatic back-stack clear and routing to `ScreenRoutes.LOGIN` when unauthenticated or
 *    when `401 session_revoked` / `403 account_blocked` triggers a full local data wipe.
 * 2. Resumption of Home-Screen Widget intent actions (`ACTION_OPEN_ARTICLE_SEARCH` or
 *    `ACTION_OPEN_BARCODE_SCANNER`) immediately on `StockMainScreen` (or right after authentication
 *    if the user was logged out when tapping the widget).
 * 3. Top-level rendering of `InAppUpdateDialog` for optional and mandatory (`force_update`) APK updates.
 */
@Composable
fun AppNavGraph(
    isLoggedIn: Boolean,
    pendingWidgetAction: String?,
    onConsumeWidgetAction: () -> Unit,
    appUpdateViewModel: AppUpdateViewModel,
    navController: NavHostController = rememberNavController()
) {
    val updateUiState by appUpdateViewModel.uiState.collectAsState()

    val startDestination = if (isLoggedIn) {
        ScreenRoutes.MAIN_STOCK
    } else {
        ScreenRoutes.LOGIN
    }

    // Reactively route between Login and Main Stock screen whenever authentication state changes
    LaunchedEffect(isLoggedIn) {
        val currentRoute = navController.currentDestination?.route
        if (!isLoggedIn && currentRoute != ScreenRoutes.LOGIN) {
            navController.navigate(ScreenRoutes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } else if (isLoggedIn && currentRoute != ScreenRoutes.MAIN_STOCK) {
            navController.navigate(ScreenRoutes.MAIN_STOCK) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            appUpdateViewModel.checkForUpdateOnLaunchOrResume(isColdStart = true)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = ScreenRoutes.LOGIN) {
            val loginViewModel: LoginViewModel = hiltViewModel()
            val loginUiState by loginViewModel.uiState.collectAsState()

            when (loginUiState.step) {
                LoginStep.PHONE_INPUT -> {
                    PhoneInputScreen(
                        uiState = loginUiState,
                        onMobileInputChanged = loginViewModel::onMobileInputChanged,
                        onPhoneHintSelected = { phone, activity ->
                            loginViewModel.onPhoneHintSelected(phone, activity)
                        },
                        onSubmitPhone = { activity ->
                            loginViewModel.submitPhoneForPreLogin(activity, isResend = false)
                        }
                    )
                }

                LoginStep.OTP_VERIFY -> {
                    OtpVerifyScreen(
                        uiState = loginUiState,
                        onOtpInputChanged = loginViewModel::onOtpInputChanged,
                        onVerifyOtpClicked = { loginViewModel.verifyOtpCode() },
                        onResendOtpClicked = { activity ->
                            loginViewModel.submitPhoneForPreLogin(activity, isResend = true)
                        },
                        onBackToPhoneInput = loginViewModel::returnToPhoneInput
                    )
                }
            }
        }

        composable(route = ScreenRoutes.MAIN_STOCK) {
            val stockMainViewModel: StockMainViewModel = hiltViewModel()
            val offlineDownloadsViewModel: OfflineDownloadsViewModel = hiltViewModel()

            // Dispatch any pending Home-Screen Widget intent action as soon as MAIN_STOCK is active
            LaunchedEffect(pendingWidgetAction) {
                if (!pendingWidgetAction.isNullOrBlank()) {
                    stockMainViewModel.handleWidgetIntentAction(pendingWidgetAction)
                    onConsumeWidgetAction()
                }
            }

            StockMainScreen(
                viewModel = stockMainViewModel,
                offlineDownloadsViewModel = offlineDownloadsViewModel,
                barcodeScannerOverlay = { onScanned, onClose ->
                    BarcodeScannerScreen(
                        onBarcodeScanned = onScanned,
                        onClose = onClose
                    )
                }
            )
        }
    }

    // Render In-App Self-Update Dialog above the active screen when logged in
    if (isLoggedIn) {
        InAppUpdateDialog(
            uiState = updateUiState,
            onStartDownload = { appUpdateViewModel.startUpdateDownload() },
            onDismissOptional = { appUpdateViewModel.dismissOptionalUpdate() }
        )
    }
}
