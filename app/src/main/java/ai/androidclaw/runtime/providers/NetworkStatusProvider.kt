package ai.androidclaw.runtime.providers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

data class NetworkStatusSnapshot(
    val supported: Boolean,
    val isConnected: Boolean,
    val isValidated: Boolean,
    val isMetered: Boolean,
) {
    val summary: String
        get() = when {
            !supported -> "Unavailable"
            !isConnected -> "Offline"
            !isValidated -> "Connected, internet not validated"
            isMetered -> "Connected (metered)"
            else -> "Connected"
        }
}

interface NetworkStatusProvider {
    fun currentStatus(): NetworkStatusSnapshot

    fun observeStatus(): Flow<NetworkStatusSnapshot> = flowOf(currentStatus())
}

class AndroidNetworkStatusProvider(
    context: Context,
) : NetworkStatusProvider {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    override fun currentStatus(): NetworkStatusSnapshot {
        val manager = connectivityManager ?: return NetworkStatusSnapshot(
            supported = false,
            isConnected = false,
            isValidated = false,
            isMetered = false,
        )
        val activeNetwork = manager.activeNetwork ?: return NetworkStatusSnapshot(
            supported = true,
            isConnected = false,
            isValidated = false,
            isMetered = manager.isActiveNetworkMetered,
        )
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return NetworkStatusSnapshot(
            supported = true,
            isConnected = false,
            isValidated = false,
            isMetered = manager.isActiveNetworkMetered,
        )
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = hasInternet &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkStatusSnapshot(
            supported = true,
            isConnected = hasInternet,
            isValidated = validated,
            isMetered = manager.isActiveNetworkMetered,
        )
    }

    override fun observeStatus(): Flow<NetworkStatusSnapshot> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(currentStatus())
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                trySend(currentStatus())
            }

            override fun onLost(network: android.net.Network) {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(currentStatus())
            }
        }

        trySend(currentStatus())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
