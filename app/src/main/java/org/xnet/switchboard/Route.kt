package org.xnet.switchboard

import java.util.UUID

enum class Proto { TCP, UDP }

enum class EgressMode {
    /** Default route / loopback. */
    NONE,

    /** Bind upstream socket to a local source IP ([egressArg]). */
    SOURCE,

    /** SO_BINDTODEVICE to interface name ([egressArg]); needs root/CAP_NET_RAW. */
    DEVICE,

    /** Pin to an Android Network via android_setsocknetwork (no root). */
    NETWORK,
}

/** Transport to request for a NETWORK-egress route. ANY uses the specific
 *  [Route.networkHandle] as-is without requesting a particular transport. */
enum class NetTransport { ANY, WIFI, CELLULAR, ETHERNET }

/**
 * A single forwarding rule: expose [listenAddr]:[listenPort] and forward to
 * [targetAddr]:[targetPort], optionally pinning the egress path.
 */
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val proto: Proto = Proto.TCP,
    val listenAddr: String = "127.0.0.1",
    val listenPort: Int = 0,
    val targetAddr: String = "127.0.0.1",
    val targetPort: Int = 0,
    val egress: EgressMode = EgressMode.NONE,
    val egressArg: String = "",
    /** Opaque Android Network handle for EgressMode.NETWORK (0 = none).
     *  Used as a fallback/hint; the service prefers a freshly requested
     *  network of [transport] when that resolves. */
    val networkHandle: Long = 0L,
    /** For EgressMode.NETWORK: which transport to requestNetwork() for. */
    val transport: NetTransport = NetTransport.ANY,
    /** True if this route is a SOCKS5 proxy listener (no fixed target; the
     *  destination is supplied per-connection by the SOCKS client). The
     *  [proto] and [targetAddr]/[targetPort] fields are ignored for SOCKS. */
    val socks: Boolean = false,
    /** True if this route is an encrypted tunnel CLIENT (NoisePSK_NN): dials
     *  [serverAddr]:[serverPort] and splices to [targetAddr]:[targetPort].
     *  [psk] is 64 hex chars (stored in prefs — see note below); [cipher]
     *  0=AES-256-GCM, 1=ChaCha20-Poly1305. NOTE: the PSK is persisted in
     *  SharedPreferences in the clear, acceptable for this local human-UI app
     *  but not a secret store. */
    val tunnel: Boolean = false,
    val serverAddr: String = "",
    val serverPort: Int = 0,
    val psk: String = "",
    val cipher: Int = 0,
) {
    fun summary(): String {
        val eg = when (egress) {
            EgressMode.NONE -> ""
            EgressMode.SOURCE -> "  src=$egressArg"
            EgressMode.DEVICE -> "  dev=$egressArg"
            EgressMode.NETWORK -> "  via ${transport.name.lowercase()}"
        }
        if (tunnel) {
            return "tunnel  → $serverAddr:$serverPort ⇒ $targetAddr:$targetPort  (encrypted)$eg"
        }
        if (socks) {
            return "socks5  $listenAddr:$listenPort$eg"
        }
        val p = if (proto == Proto.TCP) "tcp" else "udp"
        return "$p  $listenAddr:$listenPort → $targetAddr:$targetPort$eg"
    }
}
