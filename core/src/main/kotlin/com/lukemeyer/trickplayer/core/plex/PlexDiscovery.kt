package com.lukemeyer.trickplayer.core.plex

import okhttp3.OkHttpClient
import okhttp3.Request

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Picks a working route to a server by **racing** the candidates.
 *
 * plex.tv hands back several connections per server — a LAN address, a public
 * one, and usually a relay — and which of them works depends entirely on where
 * the watch happens to be. The G2 app made this the user's problem with a
 * dropdown labelled "Local / Remote / Relay". That is a reasonable thing to ask
 * of someone sitting at a desktop and a bad thing to ask of someone looking at a
 * 450 px circle, who moves between wifi and LTE several times a day and should
 * never have to think about it.
 *
 * So: fire `/identity` at every candidate at once, keep the first that answers,
 * and re-race when the network changes.
 *
 * Ordering breaks ties rather than deciding: local first (fastest, no egress),
 * then direct remote, then relay last — Plex relays are bandwidth-limited and
 * are a fallback, not a choice.
 *
 * The timeout is deliberately generous. When a Wear OS watch is in Bluetooth
 * range of its phone the system **turns the WiFi interface off** and proxies all
 * traffic through the phone, which is markedly slower and higher-latency than a
 * direct connection. A probe budget tuned to WiFi would time out on the proxy
 * and report every route dead, which looks identical to having no network at
 * all.
 *
 * That proxying also decides whether a LAN route works, and the answer is not
 * about the watch: traffic exits from the *phone*, so a `10.x` address is
 * reachable whenever the phone is on that network, however far the watch is from
 * the wifi itself.
 */
object PlexDiscovery {

    data class Route(val uri: String, val local: Boolean, val relay: Boolean, val millis: Long)

    private fun rank(c: PlexTv.Connection) = when {
        c.local -> 0
        !c.relay -> 1
        else -> 2
    }

    /**
     * @return the best route that answered, or null if none did.
     */
    fun pickRoute(server: PlexTv.Server, timeoutMs: Long = 8000): Route? {
        if (server.connections.isEmpty()) return null
        val http = PlexClient.insecureHttpClient()
        val pool = Executors.newFixedThreadPool(minOf(6, server.connections.size))
        try {
            val started = System.currentTimeMillis()
            val futures = server.connections.map { c ->
                c to pool.submit<Route?> {
                    if (probe(http, c.uri, server.accessToken, timeoutMs)) {
                        Route(c.uri, c.local, c.relay, System.currentTimeMillis() - started)
                    } else null
                }
            }
            // Collect everything that answered inside the window, then prefer by
            // rank and then by speed. Taking the literal first responder would
            // sometimes pick a relay over a LAN address that was 20 ms behind it.
            val ok = futures.mapNotNull { (c, f) ->
                try {
                    f.get(timeoutMs, TimeUnit.MILLISECONDS)?.let { c to it }
                } catch (e: Exception) {
                    null
                }
            }
            return ok.sortedWith(compareBy({ rank(it.first) }, { it.second.millis }))
                .firstOrNull()?.second
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probe(http: OkHttpClient, uri: String, token: String, timeoutMs: Long): Boolean =
        try {
            val client = http.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder()
                .url("${uri.trimEnd('/')}/identity")
                .header("X-Plex-Token", token)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

}
