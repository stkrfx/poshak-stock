package com.poshak.stock.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.poshak.stock.data.remote.api.SyncApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors real-time network connectivity and provides the Pull-to-Refresh `/v1/health`
 * connection-quality gate.
 */
@Singleton
class NetworkQualityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncApi: SyncApi
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Returns true if the device currently has an active, validated internet connection.
     */
    fun isCurrentlyOnline(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Reactive stream of connectivity changes for the Top Status Bar (`Online` vs `Offline`).
     */
    val isOnlineFlow: Flow<Boolean> = callbackFlow {
        trySend(isCurrentlyOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isCurrentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val validated =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(validated)
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }

        awaitClose {
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
    }.distinctUntilChanged()

    /**
     * Pull-to-Refresh connection quality gate: verifies both OS connectivity and
     * a fast (< 3.5s) `200 OK` response from `GET /v1/health`.
     */
    suspend fun isServerHealthyAndReachable(timeoutMs: Long = 3500L): Boolean =
        withContext(Dispatchers.IO) {
            if (!isCurrentlyOnline()) return@withContext false
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    val response = syncApi.checkHealth()
                    response.isSuccessful && response.body()?.status.equals("ok", ignoreCase = true)
                }.getOrDefault(false)
            } ?: false
        }
}
