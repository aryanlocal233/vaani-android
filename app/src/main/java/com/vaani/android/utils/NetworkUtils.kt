package com.vaani.android.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coarse network quality classification, used to decide which translation pipeline to route
 * through: HIGH/MEDIUM favor the full cloud pipeline, LOW may fall back to a lighter hybrid
 * pipeline, OFFLINE should surface a "no connection" state to the user.
 */
enum class NetworkQuality {
    HIGH,
    MEDIUM,
    LOW,
    OFFLINE
}

@Singleton
class NetworkUtils @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun getCurrentNetworkQuality(): NetworkQuality {
        val network = connectivityManager.activeNetwork ?: return NetworkQuality.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkQuality.OFFLINE

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return NetworkQuality.OFFLINE
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return NetworkQuality.HIGH
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val downstreamKbps = capabilities.linkDownstreamBandwidthKbps
            return when {
                downstreamKbps >= HIGH_BANDWIDTH_THRESHOLD_KBPS -> NetworkQuality.HIGH
                downstreamKbps >= MEDIUM_BANDWIDTH_THRESHOLD_KBPS -> NetworkQuality.MEDIUM
                downstreamKbps > 0 -> NetworkQuality.LOW
                else -> NetworkQuality.MEDIUM
            }
        }

        return NetworkQuality.MEDIUM
    }

    fun isOnline(): Boolean = getCurrentNetworkQuality() != NetworkQuality.OFFLINE

    companion object {
        // Roughly 5G / fast WiFi territory.
        private const val HIGH_BANDWIDTH_THRESHOLD_KBPS = 20_000
        // Roughly 4G territory.
        private const val MEDIUM_BANDWIDTH_THRESHOLD_KBPS = 4_000
    }
}
