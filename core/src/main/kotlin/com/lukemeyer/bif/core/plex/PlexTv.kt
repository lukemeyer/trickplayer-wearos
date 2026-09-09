package com.lukemeyer.bif.core.plex

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * plex.tv account API: sign-in and server discovery.
 *
 * Separate from [PlexClient], which talks to a media server: different host,
 * different headers, JSON rather than ranged binary.
 *
 * ## Why the PIN flow, and why a watch is the best place for it
 *
 * Both earlier versions of this project struggled here. The Pebble build
 * deferred sign-in entirely and took a pasted `X-Plex-Token`, because a config
 * webview has to navigate away to app.plex.tv and come back, and a backgrounded
 * webview may stop polling. The G2 app implemented it but needed a
 * copy-the-URL-to-your-clipboard dance to get the user to the authorisation page.
 *
 * On a watch the awkwardness disappears, because there is no expectation of
 * typing or navigating in the first place. Ask plex.tv for a PIN, show the
 * four-character code, and let the user enter it at **plex.tv/link** on whatever
 * device they like. The watch just polls. What was a workaround everywhere else
 * is the natural interaction here.
 *
 * `strong = false` is deliberate: a strong PIN returns a long opaque code, and
 * the short four-character one is the whole point on a 450 px screen.
 */
class PlexTv(
    /** Stable per install. plex.tv ties the PIN and the token to it. */
    private val clientId: String,
    private val product: String = "BIF Watchface",
) {
    // Private so OkHttp stays an implementation detail of :core rather than
    // leaking onto every consumer's classpath.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()


    data class Pin(val id: Long, val code: String)

    /** One reachable route to one server. */
    data class Connection(
        val uri: String,
        val local: Boolean,
        val relay: Boolean,
        val address: String,
    )

    data class Server(
        val name: String,
        val clientIdentifier: String,
        /** Server-specific token; prefer it over the account token. */
        val accessToken: String,
        val owned: Boolean,
        val connections: List<Connection>,
    )

    // Plex's JSON is inconsistent enough — fields absent, null, or a different
    // type depending on the endpoint — that reading it element by element is
    // less trouble than modelling it. org.json would be the obvious tool and is
    // not available: :core is plain Kotlin/JVM, and org.json only exists on
    // Android.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(k: String, d: String = "") =
        this[k]?.jsonPrimitive?.contentOrNull ?: d
    private fun JsonObject.bool(k: String, d: Boolean = false) =
        this[k]?.jsonPrimitive?.booleanOrNull ?: d
    private fun JsonObject.long(k: String) =
        this[k]?.jsonPrimitive?.longOrNull ?: error("missing $k")

    private fun Request.Builder.plexHeaders() = apply {
        header("Accept", "application/json")
        header("X-Plex-Product", product)
        header("X-Plex-Version", "1.0")
        header("X-Plex-Client-Identifier", clientId)
        header("X-Plex-Device", "Wear OS")
        header("X-Plex-Platform", "Android")
    }

    /** Mint a PIN. The user enters [Pin.code] at plex.tv/link. */
    fun createPin(): Pin {
        val req = Request.Builder()
            .url("https://plex.tv/api/v2/pins?strong=false")
            .plexHeaders()
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("createPin: HTTP ${res.code} $body")
            val o = json.parseToJsonElement(body).jsonObject
            return Pin(o.long("id"), o.str("code"))
        }
    }

    /**
     * Poll a PIN. Returns the account token once the user has linked it, or null
     * while still waiting.
     *
     * Poll politely — every few seconds. A PIN expires after a few minutes.
     */
    fun checkPin(pinId: Long): String? {
        val req = Request.Builder()
            .url("https://plex.tv/api/v2/pins/$pinId")
            .plexHeaders()
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.code == 404) return null          // expired or consumed
            if (!res.isSuccessful) throw IOException("checkPin: HTTP ${res.code} $body")
            val o = json.parseToJsonElement(body).jsonObject
            return o.str("authToken").ifEmpty { null }
        }
    }

    /** Servers on the account, each with every route plex.tv knows about. */
    fun servers(token: String): List<Server> {
        val req = Request.Builder()
            .url("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
            .plexHeaders()
            .header("X-Plex-Token", token)
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("resources: HTTP ${res.code} $body")
            return json.parseToJsonElement(body).jsonArray
                .map { it.jsonObject }
                .filter { d ->
                    d.str("provides").split(',').map(String::trim).contains("server")
                }
                .map { d ->
                    Server(
                        name = d.str("name", "Plex Server"),
                        clientIdentifier = d.str("clientIdentifier"),
                        accessToken = d.str("accessToken").ifEmpty { token },
                        owned = d.bool("owned"),
                        connections = (d["connections"] as? JsonArray).orEmpty()
                            .map { it.jsonObject }
                            .map { c ->
                                Connection(
                                    uri = c.str("uri"),
                                    local = c.bool("local"),
                                    relay = c.bool("relay"),
                                    address = c.str("address"),
                                )
                            }
                            .filter { it.uri.isNotEmpty() },
                    )
                }
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
