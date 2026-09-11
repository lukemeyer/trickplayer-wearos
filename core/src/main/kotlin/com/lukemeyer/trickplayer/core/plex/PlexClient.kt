package com.lukemeyer.trickplayer.core.plex

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Plex HTTP access.
 *
 * Ranged GETs are essential rather than an optimisation: the test episode's BIF
 * is 9,497,976 bytes, so it can be neither held in memory nor cached whole. The
 * index (~6 KB) is fetched once; frames are pulled individually by byte range.
 *
 * Plex serves `Accept-Ranges: bytes` and answers 206 correctly — verified
 * against a real server during the Pebble project — but a reverse proxy in front
 * of it might not, so a server that ignores `Range` and returns the whole body
 * is handled by slicing locally rather than by trusting the status code.
 *
 * Unlike the Pebble build there is no JPEG decoding, palette fitting or
 * dithering here. Wear OS has a hardware JPEG decoder and a full-colour display,
 * so frame bytes are passed through untouched.
 */
class PlexClient(
    private val token: String,
    /**
     * Plex's `*.plex.direct` certificates are valid, but they are issued for a
     * hashed hostname that resolves to a LAN address. Direct-IP access to a
     * server without a matching cert needs this escape hatch; it is off by
     * default and only ever enabled for a user-entered direct address.
     */
    allowInsecureDirect: Boolean = false,
) {
    // Kept private so OkHttp stays an implementation detail of :core rather than
    // leaking into every consumer's classpath.
    private val http: OkHttpClient = defaultClient(allowInsecureDirect)


    /** True once a 206 has been seen, false if the server ignored `Range`. */
    @Volatile
    var rangeSupported: Boolean? = null
        private set

    class HttpException(val code: Int, val url: String) :
        java.io.IOException("HTTP $code for $url")

    /**
     * @param from inclusive first byte, or null for the whole resource.
     * @param to inclusive last byte.
     */
    fun getRange(url: String, from: Long? = null, to: Long? = null): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("X-Plex-Token", token)
            .header("Accept", "*/*")
            .apply { if (from != null && to != null) header("Range", "bytes=$from-$to") }
            .build()

        http.newCall(req).execute().use { res ->
            if (res.code != 200 && res.code != 206) throw HttpException(res.code, url)
            if (from != null && rangeSupported == null) rangeSupported = res.code == 206

            val body = res.body?.bytes() ?: ByteArray(0)

            // If the server ignored Range and sent everything, slice locally so
            // callers get what they asked for regardless.
            if (from != null && to != null && res.code == 200 && body.size > to - from) {
                val end = minOf(to + 1, body.size.toLong()).toInt()
                val start = minOf(from, end.toLong()).toInt()
                return body.copyOfRange(start, end)
            }
            return body
        }
    }

    /**
     * Raw bytes of a text resource. Callers decode — a subtitle sidecar's
     * encoding is sniffed from its BOM rather than assumed (F-035), so this
     * deliberately does not guess a charset.
     */
    fun getTextBytes(url: String): ByteArray = getRange(url)

    fun getText(url: String): String = String(getRange(url), Charsets.UTF_8)

    /**
     * JSON GET against the media server.
     *
     * Plex answers XML unless asked otherwise, and the wildcard Accept header
     * used for frame fetches is not enough — it has to say JSON explicitly.
     */
    fun getJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("X-Plex-Token", token)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw HttpException(res.code, url)
            return res.body?.string().orEmpty()
        }
    }

    /**
     * Total size of a resource, from the `Content-Range` of a one-byte request.
     *
     * Worth having as its own call: it is the only way to check the BIF
     * structural invariant (`sum(frame lengths) + index == file size`) against
     * something the parser did not itself produce. Deriving it from the sentinel
     * offset would make the check tautological.
     */
    fun totalSize(url: String): Long? {
        val req = Request.Builder().url(url)
            .header("X-Plex-Token", token)
            .header("Range", "bytes=0-0")
            .build()
        http.newCall(req).execute().use { res ->
            val cr = res.header("Content-Range") ?: return null
            return cr.substringAfter('/', "").toLongOrNull()
        }
    }

    /** The `sd` trick-play index for a media part. */
    fun timelineUrl(server: String, timelineRef: Long): String =
        "${server.trimEnd('/')}/library/parts/$timelineRef/indexes/sd"

    /** A subtitle sidecar. Only streams with a non-null `key` can be fetched. */
    fun subtitleUrl(server: String, subtitleRef: String): String =
        "${server.trimEnd('/')}$subtitleRef"

    companion object {
        /** Shared with PlexDiscovery, which probes the same hosts. */
        fun insecureHttpClient(): OkHttpClient = defaultClient(allowInsecureDirect = true)

        private fun defaultClient(allowInsecureDirect: Boolean = false): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
            if (allowInsecureDirect) {
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
                }
                val ctx = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
                }
                b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
            }
            return b.build()
        }
    }
}
