package com.poshak.stock

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.sync.IncrementalSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for `Stock` (`com.poshak.stock`).
 * Configures Hilt dependency injection and custom [HiltWorkerFactory] for WorkManager background sync.
 */
@HiltAndroidApp
class StockApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionManager: SessionManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (sessionManager.isLoggedIn.value) {
            IncrementalSyncWorker.enqueuePeriodicAndImmediateSync(this)
        }
    }
}
