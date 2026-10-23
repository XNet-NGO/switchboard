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
    ): Int

    /**
     * Register a SOCKS5 proxy listener. No fixed target: each SOCKS client
     * supplies the destination per connection. The same egress policy is
     * applied to every upstream socket the proxy opens, so network pinning
     * (CALLBACK egress) works exactly like a normal route — this is the
     * no-root "SOCKS over cellular/wifi" path.
     */
    external fun nativeAddSocks(
        handle: Long,
        listenAddr: String,
        listenPort: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
    ): Int

    external fun nativeRun(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
}
