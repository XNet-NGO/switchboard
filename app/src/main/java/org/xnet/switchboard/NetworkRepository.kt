package org.xnet.switchboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** A selectable egress network: a human label, the opaque handle, and the
 *  transport so the forwarder can requestNetwork() for the right kind. */
data class EgressNetwork(
    val handle: Long,
    val label: String,
    val transport: NetTransport,
)

/** Enumerates the device's currently available networks so a route can pin its
 *  upstream to one of them (Wi-Fi, cellular, ethernet, VPN, ...). */
object NetworkRepository {

    fun available(ctx: Context): List<EgressNetwork> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val out = ArrayList<EgressNetwork>()
        val active = cm.activeNetwork
        cm.allNetworks.forEach { net ->
            val caps = cm.getNetworkCaps(net) ?: return@forEach
            val transport = transportOf(caps)
            val kind = transportLabel(transport)
            val extra = buildString {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) append(" · internet")
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) append(" · metered")
                if (net == active) append(" · active")
            }
            out.add(EgressNetwork(net.networkHandle, kind + extra, transport))
        }
        return out
    }

    private fun ConnectivityManager.getNetworkCaps(net: android.net.Network) =
        try { getNetworkCapabilities(net) } catch (_: Throwable) { null }

    private fun transportOf(caps: NetworkCapabilities): NetTransport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetTransport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetTransport.ETHERNET
        else -> NetTransport.ANY
    }

    private fun transportLabel(t: NetTransport): String = when (t) {
        NetTransport.WIFI -> "Wi-Fi"
        NetTransport.CELLULAR -> "Cellular"
        NetTransport.ETHERNET -> "Ethernet"
        NetTransport.ANY -> "Other"
    }
}
