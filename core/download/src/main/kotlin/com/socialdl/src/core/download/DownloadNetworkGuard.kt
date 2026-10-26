package com.socialdl.src.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.socialdl.src.core.common.util.Logger
import okhttp3.Call
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a caller abort in-flight OkHttp calls instantly. [ChunkedDownloader]
 * registers every call it starts; the download worker cancels all of them the
 * moment the last usable network drops, unblocking socket reads immediately
 * instead of hanging until the read timeout.
 */
class DownloadCallCanceller {
    private val calls: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var cancelled = false

    /** True once [cancel] was called — retries against a dead network are skipped. */
    val isCancelled: Boolean get() = cancelled

    fun register(call: Call) {
        calls.add(call)
        if (cancelled) call.cancel()
    }

    /** Aborts every registered (and any future) call. */
    fun cancel() {
        cancelled = true
        calls.forEach { call -> runCatching { call.cancel() } }
    }
}

/**
 * Watchdog that fails a download the moment the device loses its last usable
 * INTERNET-capable network — instead of hanging on a socket read timeout and
 * burning retry backoff against a dead network.
 *
 * Tracks every available INTERNET-capable network: a wifi→cellular handover
 * keeps the download alive (another network replaced the lost one); losing ALL
 * of them aborts instantly via [DownloadCallCanceller].
 */
class NetworkLossWatchdog(
    context: Context,
    private val onAllNetworksLost: () -> Unit
) {
    private val tag = "NetworkLossWatchdog"
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val availableNetworks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            availableNetworks.add(network)
        }

        override fun onLost(network: Network) {
            availableNetworks.remove(network)
            if (availableNetworks.isEmpty()) {
                Logger.w(tag, "All usable networks lost — aborting download instantly")
                onAllNetworksLost()
            }
        }
    }

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    /** Registers the callback; pre-existing matching networks fire [onAvailable] at registration. */
    fun register() {
        runCatching { connectivityManager?.registerNetworkCallback(request, callback) }
            .onFailure { Logger.w(tag, "Network callback registration failed (watchdog inactive)", it) }
    }

    fun unregister() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }
}
