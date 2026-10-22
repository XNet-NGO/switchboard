package org.xnet.switchboard

import android.net.Network
import android.os.Build

/**
 * Thin Kotlin wrapper over the portmux JNI bridge.
 *
 * The native `portmux_run` blocks, so [run] must be called on a dedicated
 * worker thread. All other calls are cheap and thread-safe to issue from
 * another thread (stop writes to an eventfd; create/destroy are plain).
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

    external fun nativeRun(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)

    /**
     * Up-called from native `bind_hook` for CALLBACK egress. Binds the given
     * socket fd to the Android [Network] identified by [networkHandle] so the
     * upstream connection egresses on that network - no root required.
     *
     * [networkHandle] is produced by [Network.getNetworkHandle].
     */
    @JvmStatic
    fun bindSocketToNetwork(fd: Int, networkHandle: Long): Boolean {
        return try {
            if (networkHandle == 0L) return true
            val net = networkFromHandle(networkHandle) ?: return false
            // Network.bindSocket(FileDescriptor) needs a FileDescriptor; use the
            // raw-fd overload available via ParcelFileDescriptor adoption.
            val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
            try {
                net.bindSocket(pfd.fileDescriptor)
                true
            } finally {
                // detachFd would close our dup; fromFd dup'd, so just close pfd.
                pfd.close()
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun networkFromHandle(handle: Long): Network? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network.fromNetworkHandle(handle)
        } else {
            null
        }
    }
}
