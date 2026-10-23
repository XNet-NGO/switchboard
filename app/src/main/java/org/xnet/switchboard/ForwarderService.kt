package org.xnet.switchboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service that owns a portmux instance and runs its event loop on a
 * dedicated worker thread. No VpnService / system TUN is used.
 *
 * Routes are supplied once at start via the intent extra. Changing routes means
 * restarting the service (portmux routes are fixed at run()).
 */
class ForwarderService : Service() {

    private var handle: Long = 0L
    private var worker: Thread? = null
    private var requester: NetworkRequester? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_STICKY

        val routes = RouteStore.load(this)
        if (routes.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafely(routes.size)) {
            // Could not enter foreground (e.g. missing/denied requirements).
            // Running a background service for networking would be killed by
            // the OS, so stop cleanly rather than limp along.
            Log.e(TAG, "startForeground failed; aborting")
            stopSelf()
            return START_NOT_STICKY
        }

        val req = NetworkRequester(this)
        requester = req

        // All portmux setup + run happens on the worker thread. resolveHandle()
        // may block (waiting for requestNetwork to resolve), so it must not run
        // on the main thread; and the hev-task system is thread-local, so
        // new/add/run belong together on one thread.
        worker = thread(name = "portmux-run") {
            val h = SwitchboardNative.nativeNew()
            if (h == 0L) {
                Log.e(TAG, "portmux_new failed")
                stopSelf()
                return@thread
            }
            handle = h

            routes.forEach { r ->
                val kind = when (r.egress) {
                    EgressMode.NONE -> SwitchboardNative.EGRESS_NONE
                    EgressMode.SOURCE -> SwitchboardNative.EGRESS_SOURCE
                    EgressMode.DEVICE -> SwitchboardNative.EGRESS_DEVICE
                    EgressMode.NETWORK -> SwitchboardNative.EGRESS_CALLBACK
                }
                // For NETWORK egress, request the chosen transport and use the
                // resolved handle (falls back to the stored handle on timeout).
                val netHandle = if (r.egress == EgressMode.NETWORK) {
                    req.resolveHandle(r.transport, r.networkHandle)
                } else {
                    0L
                }
                val rc = if (r.socks) {
                    // SOCKS5 proxy: no fixed target; the same egress policy is
                    // applied to every upstream the proxy opens.
                    SwitchboardNative.nativeAddSocks(
                        h, r.listenAddr, r.listenPort, kind, r.egressArg, netHandle,
                    )
                } else {
                    val proto = if (r.proto == Proto.TCP) SwitchboardNative.PROTO_TCP
                    else SwitchboardNative.PROTO_UDP
                    SwitchboardNative.nativeAddRoute(
                        h, proto, r.listenAddr, r.listenPort,
                        r.targetAddr, r.targetPort, kind, r.egressArg, netHandle,
                    )
                }
                if (rc != 0) Log.w(TAG, "add route failed: ${r.summary()}")
            }

            Log.i(TAG, "forwarder run loop start")
            SwitchboardNative.nativeRun(h) // blocks until stop
            Log.i(TAG, "forwarder run loop exit")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (handle != 0L) {
            SwitchboardNative.nativeStop(handle)
            worker?.join(3000)
            SwitchboardNative.nativeDestroy(handle)
            handle = 0L
        }
        worker = null
        requester?.releaseAll()
        requester = null
        super.onDestroy()
    }

    /**
     * Enter the foreground with the correct service type. On API 34+ the type
     * must be passed to startForeground(); declaring it in the manifest alone
     * is not sufficient. Returns false if the platform refuses (caught so the
     * service can stop cleanly instead of crashing).
     */
    private fun startForegroundSafely(routeCount: Int): Boolean {
        val notif = buildNotification(routeCount)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground error: ${t.message}")
            false
        }
    }

    private fun buildNotification(routeCount: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Forwarding",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Switchboard is forwarding ports"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Switchboard")
            .setContentText(
                if (routeCount == 1) "Forwarding 1 route"
                else "Forwarding $routeCount routes",
            )
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ForwarderService"
        private const val CHANNEL_ID = "switchboard.forwarding"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, ForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ForwarderService::class.java))
        }
    }
}
