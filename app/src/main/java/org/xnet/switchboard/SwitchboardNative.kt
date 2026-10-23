package org.xnet.switchboard

/**
 * Thin Kotlin wrapper over the portmux JNI bridge.
 *
 * The native `portmux_run` blocks, so [run] must be called on a dedicated
 * worker thread. All other calls are cheap and thread-safe to issue from
 * another thread (stop writes to an eventfd; create/destroy are plain).
 *
 * Network egress pinning is handled entirely in native code via
 * android_setsocknetwork(); the [Route.networkHandle] (from
 * Network.getNetworkHandle()) is passed straight through to nativeAddRoute.
 */
object SwitchboardNative {

    init {
        System.loadLibrary("switchboard")
    }

    // Egress kinds mirrored from portmux.h.
    const val EGRESS_NONE = 0
    const val EGRESS_SOURCE = 1
    const val EGRESS_DEVICE = 2
    const val EGRESS_CALLBACK = 3

    // Proto values mirrored from portmux.h (0 TCP, 1 UDP).
    const val PROTO_TCP = 0
    const val PROTO_UDP = 1

    /**
     * Token grouping CALLBACK (NETWORK-egress) routes that share a transport,
     * so a live network-handle update ([nativeUpdateNetworkHandle]) reaches all
     * of them. Must be > 0 for a pinned route (0 means "no pin" on the native
     * side, which allows the default network). Derived from the transport
     * ordinal; NetworkRequester keys its requests by transport too, so one
     * token == one requested Network.
     */
    fun tokenFor(transport: NetTransport): Int = transport.ordinal + 1

    external fun nativeNew(): Long
    external fun nativeAddRoute(
        handle: Long,
        proto: Int,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
        token: Int,
    ): Int

    /**
     * Register a SOCKS5 proxy listener. No fixed target: each SOCKS client
     * supplies the destination per connection. The same egress policy is
     * applied to every upstream socket the proxy opens, so network pinning
     * (CALLBACK egress) works exactly like a normal route — this is the
     * no-root "SOCKS over cellular/wifi" path.
     *
     * For NETWORK egress pass a [token] (> 0) identifying the transport group;
     * the handle can then be refreshed live via [nativeUpdateNetworkHandle]
     * when the pinned network is lost/regained mid-session (a long-lived SOCKS
     * proxy spans many connections).
     */
    external fun nativeAddSocks(
        handle: Long,
        listenAddr: String,
        listenPort: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
        token: Int,
    ): Int

    /**
     * Push a live network-handle update for every CALLBACK route sharing
     * [token]. Pass [netHandle] = 0 to mark the transport unavailable, which
     * makes new upstream connections for that token fail closed (no leak to the
     * default network) until a fresh handle arrives. Safe to call from any
     * thread (the native side updates an atomic under a mutex); may be called
     * on a running engine.
     */
    external fun nativeUpdateNetworkHandle(token: Int, netHandle: Long)

    external fun nativeRun(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
}
