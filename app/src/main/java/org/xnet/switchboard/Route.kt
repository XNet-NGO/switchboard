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

    /** Pin to an Android Network via bindSocket (no root). */
    NETWORK,
}

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
    /** Opaque Android Network handle for EgressMode.NETWORK (0 = none). */
    val networkHandle: Long = 0L,
) {
    fun summary(): String {
        val p = if (proto == Proto.TCP) "tcp" else "udp"
        val eg = when (egress) {
            EgressMode.NONE -> ""
            EgressMode.SOURCE -> "  src=$egressArg"
            EgressMode.DEVICE -> "  dev=$egressArg"
            EgressMode.NETWORK -> "  via network"
        }
        return "$p  $listenAddr:$listenPort → $targetAddr:$targetPort$eg"
    }
}
