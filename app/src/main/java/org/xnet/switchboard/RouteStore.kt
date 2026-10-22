package org.xnet.switchboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists routes in SharedPreferences as JSON (no external deps). */
object RouteStore {
    private const val PREFS = "switchboard.routes"
    private const val KEY = "routes"

    fun load(ctx: Context): List<Route> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Route(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    proto = if (o.optInt("proto") == 1) Proto.UDP else Proto.TCP,
                    listenAddr = o.optString("listenAddr", "127.0.0.1"),
                    listenPort = o.optInt("listenPort"),
                    targetAddr = o.optString("targetAddr", "127.0.0.1"),
                    targetPort = o.optInt("targetPort"),
                    egress = EgressMode.entries[o.optInt("egress")],
                    egressArg = o.optString("egressArg"),
                    networkHandle = o.optLong("networkHandle"),
                    transport = NetTransport.entries[o.optInt("transport")],
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, routes: List<Route>) {
        val arr = JSONArray()
        routes.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("proto", if (r.proto == Proto.UDP) 1 else 0)
                    .put("listenAddr", r.listenAddr)
                    .put("listenPort", r.listenPort)
                    .put("targetAddr", r.targetAddr)
                    .put("targetPort", r.targetPort)
                    .put("egress", r.egress.ordinal)
                    .put("egressArg", r.egressArg)
                    .put("networkHandle", r.networkHandle)
                    .put("transport", r.transport.ordinal),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
