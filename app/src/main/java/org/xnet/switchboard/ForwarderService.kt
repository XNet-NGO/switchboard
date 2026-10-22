package org.xnet.switchboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_STICKY

        val routes = RouteStore.load(this)
        if (routes.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(routes.size))

        handle = SwitchboardNative.nativeNew()
        if (handle == 0L) {
            Log.e(TAG, "portmux_new failed")
            stopSelf()
            return START_NOT_STICKY
        }

        routes.forEach { r ->
            val proto = if (r.proto == Proto.TCP) SwitchboardNative.PROTO_TCP
            else SwitchboardNative.PROTO_UDP
            val kind = when (r.egress) {
                EgressMode.NONE -> SwitchboardNative.EGRESS_NONE
                EgressMode.SOURCE -> SwitchboardNative.EGRESS_SOURCE
                EgressMode.DEVICE -> SwitchboardNative.EGRESS_DEVICE
                EgressMode.NETWORK -> SwitchboardNative.EGRESS_CALLBACK
            }
            val rc = SwitchboardNative.nativeAddRoute(
                handle, proto, r.listenAddr, r.listenPort,
                r.targetAddr, r.targetPort, kind, r.egressArg, r.networkHandle,
            )
            if (rc != 0) Log.w(TAG, "add route failed: ${r.summary()}")
        }

        worker = thread(name = "portmux-run") {
            Log.i(TAG, "forwarder run loop start")
            SwitchboardNative.nativeRun(handle) // blocks until stop
            Log.i(TAG, "forwarder run loop exit")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (handle != 0L) {
            SwitchboardNative.nativeStop(handle)
            worker?.join(2000)
            SwitchboardNative.nativeDestroy(handle)
            handle = 0L
        }
        worker = null
        super.onDestroy()
    }

    private fun buildNotification(routeCount: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Forwarding",
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Switchboard")
            .setContentText("Forwarding $routeCount route(s)")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
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
