package org.xnet.switchboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** A selectable egress network: a human label plus the opaque handle portmux
 *  passes back to [android.net.Network.fromNetworkHandle]. */
data class EgressNetwork(
    val handle: Long,
    val label: String,
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
            val kind = transportLabel(caps)
            val extra = buildString {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) append(" · internet")
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) append(" · metered")
                if (net == active) append(" · active")
            }
            out.add(EgressNetwork(net.networkHandle, kind + extra))
        }
        return out
    }

    private fun ConnectivityManager.getNetworkCaps(net: android.net.Network) =
        try { getNetworkCapabilities(net) } catch (_: Throwable) { null }

    private fun transportLabel(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
        else -> "Other"
    }
}
