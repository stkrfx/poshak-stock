package com.poshak.stock.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.poshak.stock.data.local.SessionManager
import com.poshak.stock.data.repository.OfflineSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager background worker (`@HiltWorker`) that executes `GET /v1/sync/stock?since=<last_sync_timestamp>`
 * whenever network connectivity is available, keeping all locally stored products and stock rows
 * (both manually downloaded scopes and organically cached searches) up to date with Habron changes.
 */
@HiltWorker
class IncrementalSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionManager: SessionManager,
    private val offlineSyncRepository: OfflineSyncRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!sessionManager.isLoggedIn.value) {
            return Result.success()
        }

        val syncResult = offlineSyncRepository.runIncrementalSync()
        return if (syncResult.isSuccess) {
            Result.success()
        } else {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "poshak_stock_periodic_incremental_sync"
        const val UNIQUE_IMMEDIATE_WORK_NAME = "poshak_stock_immediate_incremental_sync"

        fun enqueuePeriodicAndImmediateSync(context: Context) {
            val networkConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<IncrementalSyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )

            triggerImmediateSync(context)
        }

        fun triggerImmediateSync(context: Context) {
            val networkConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateRequest = OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediateRequest
            )
        }
    }
}
