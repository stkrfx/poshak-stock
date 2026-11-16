package com.poshak.stock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.ui.main.StockMainViewModel
import com.poshak.stock.ui.navigation.AppNavGraph
import com.poshak.stock.ui.theme.StockTheme
import com.poshak.stock.ui.update.AppUpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-Activity entry point (`MainActivity`) for Stock (`com.poshak.stock`).
 *
 * Handles:
 * 1. Normal launcher startups.
 * 2. Home-Screen Widget deep-link intents (`ACTION_OPEN_ARTICLE_SEARCH` and `ACTION_OPEN_BARCODE_SCANNER`),
 *    both on cold start and via `onNewIntent` when already running (`launchMode="singleTop"`).
 * 3. Startup and foreground-resume (> 30 min) self-update version checks via [AppUpdateViewModel].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val appUpdateViewModel: AppUpdateViewModel by viewModels()

    private var pendingWidgetAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        extractWidgetActionFromIntent(intent)
        appUpdateViewModel.checkForUpdateOnLaunchOrResume(isColdStart = true)

        setContent {
            val isLoggedIn by sessionManager.isLoggedIn.collectAsState()
            val lifecycleOwner = LocalLifecycleOwner.current

            // Check for app updates when returning to foreground after > 30 minutes
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        appUpdateViewModel.checkForUpdateOnLaunchOrResume(isColdStart = false)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            StockTheme {
                AppNavGraph(
                    isLoggedIn = isLoggedIn,
                    pendingWidgetAction = pendingWidgetAction,
                    onConsumeWidgetAction = { pendingWidgetAction = null },
                    appUpdateViewModel = appUpdateViewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractWidgetActionFromIntent(intent)
    }

    private fun extractWidgetActionFromIntent(incomingIntent: Intent?) {
        val action = incomingIntent?.action ?: return
        if (
            action == StockMainViewModel.ACTION_OPEN_ARTICLE_SEARCH ||
            action == StockMainViewModel.ACTION_OPEN_BARCODE_SCANNER
        ) {
            pendingWidgetAction = action
        }
    }
}
