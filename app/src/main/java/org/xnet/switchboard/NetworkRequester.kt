package org.xnet.switchboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Requests specific transports via [ConnectivityManager.requestNetwork] and
 * keeps the requests alive so the app is permitted to pin sockets to those
 * networks (pure enumeration + bindSocket is not enough when the network isn't
 * the active default — the framework requires the app to have requested it).
 *
 * One instance per forwarder run. Call [resolveHandle] for each NETWORK-egress
 * route before adding it to portmux, then [releaseAll] on stop.
 */
class NetworkRequester(context: Context) {

    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private class Holder {
        val latch = CountDownLatch(1)
        @Volatile var network: Network? = null
        lateinit var callback: ConnectivityManager.NetworkCallback
    }

    // Keyed by transport so multiple routes sharing a transport reuse one request.
    private val holders = ConcurrentHashMap<NetTransport, Holder>()

    /**
     * Ensure a network of [transport] is requested and resolved; returns its
     * network handle, or 0 if it could not be obtained within [timeoutMs].
     * For [NetTransport.ANY], returns [fallbackHandle] unchanged (no request).
     */
    fun resolveHandle(
        transport: NetTransport,
        fallbackHandle: Long,
        timeoutMs: Long = 8000,
    ): Long {
        if (transport == NetTransport.ANY) return fallbackHandle

        val holder = holders.getOrPut(transport) { request(transport) }
        val ok = try {
            holder.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        val net = holder.network
        return if (ok && net != null) {
            net.networkHandle
        } else {
            Log.w(TAG, "network for $transport not available; falling back")
            fallbackHandle
        }
    }

    private fun request(transport: NetTransport): Holder {
        val holder = Holder()
        val transportType = when (transport) {
            NetTransport.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetTransport.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetTransport.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            NetTransport.ANY -> -1
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { if (transportType >= 0) addTransportType(transportType) }
            .build()

        holder.callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                holder.network = network
                holder.latch.countDown()
                Log.i(TAG, "network available for $transport: ${network.networkHandle}")
            }

            override fun onLost(network: Network) {
                if (holder.network == network) holder.network = null
                Log.i(TAG, "network lost for $transport")
            }
        }
        try {
            cm.requestNetwork(req, holder.callback)
        } catch (t: Throwable) {
            // e.g. SecurityException without CHANGE_NETWORK_STATE
            Log.w(TAG, "requestNetwork($transport) failed: ${t.message}")
            holder.latch.countDown()
        }
        return holder
    }

    /** Release all held network requests. Safe to call once, on stop. */
    fun releaseAll() {
        holders.values.forEach { h ->
            try {
                cm.unregisterNetworkCallback(h.callback)
            } catch (_: Throwable) {
            }
        }
        holders.clear()
    }

    private companion object {
        const val TAG = "NetworkRequester"
    }
}
