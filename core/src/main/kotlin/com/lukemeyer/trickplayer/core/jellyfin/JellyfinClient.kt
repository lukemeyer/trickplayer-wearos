package com.lukemeyer.trickplayer.core.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Jellyfin HTTP access: sign-in, browsing, tile sheets, subtitles.
 *
 * One class where Plex needs two ([PlexTv][com.lukemeyer.trickplayer.core.plex.PlexTv]
 * and [PlexClient][com.lukemeyer.trickplayer.core.plex.PlexClient]) because there is no
 * account service to be separate from. **The address is the identity**, and
 * that single fact reshapes the whole flow: nothing can be authenticated before
 * the user has said which server, and there is no list of servers to discover
 * afterwards.
 *
 * Credentials ride in `Authorization`, never a query string (F-021). The header
 * identifies this client even unauthenticated, because Quick Connect ties the
 * pending request to this device and the approval screen shows the product name
 * to whoever is approving it.
 */
class JellyfinClient(
    baseUrl: String,
    @Volatile var token: String? = null,
    private val product: String = "Trickplayer",
    private val deviceId: String = "trickplayer-wearos",
) {
    val base: String = baseUrl.trimEnd('/')

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

    private fun authHeader(): String {
        val parts = mutableListOf(
            "Client=\"$product\"",
            "Device=\"Wear OS\"",
            "DeviceId=\"$deviceId\"",
            "Version=\"1.0\"",
        )
        token?.let { parts += "Token=\"$it\"" }
        return "MediaBrowser " + parts.joinToString(", ")
    }

    private fun request(path: String, post: Boolean = false, body: String? = null): Request =
        Request.Builder()
            .url("$base$path")
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .apply {
                if (post || body != null) {
                    header("Content-Type", "application/json")
                    post((body ?: "").toRequestBody(null))
                }
            }
            .build()

    /** @return parsed JSON, or null on 404 — which Quick Connect uses as a signal. */
    fun getJson(path: String, post: Boolean = false, body: String? = null): JsonObject? {
        http.newCall(request(path, post, body)).execute().use { res ->
            if (res.code == 404) return null
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw HttpException(res.code, path)
            if (text.isBlank()) return JsonObject(emptyMap())
            return json.parseToJsonElement(text).jsonObject
        }
    }

    fun getBytes(path: String): ByteArray {
        http.newCall(request(path)).execute().use { res ->
            if (!res.isSuccessful) throw HttpException(res.code, path)
            return res.body?.bytes() ?: ByteArray(0)
        }
    }

    // -------------------------------------------------------- quick connect

    data class QuickConnect(val code: String, val secret: String)

    fun quickConnectEnabled(): Boolean =
        runCatching { getJson("/QuickConnect/Enabled") }.isSuccess

    fun initiateQuickConnect(): QuickConnect {
        val o = getJson("/QuickConnect/Initiate", post = true)
            ?: throw IOException("Quick Connect is not enabled on this server")
        return QuickConnect(o.str("Code"), o.str("Secret"))
    }

    /** Null when the server has forgotten the secret — i.e. it timed out. */
    fun quickConnectApproved(secret: String): Boolean? {
        val o = getJson("/QuickConnect/Connect?secret=${enc(secret)}") ?: return null
        return o["Authenticated"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /** @return access token to user id. Approval is not a token; it is redeemed. */
    fun redeemQuickConnect(secret: String): Pair<String, String> {
        val o = getJson(
            "/Users/AuthenticateWithQuickConnect",
            body = """{"Secret":"$secret"}""",
        ) ?: throw IOException("Quick Connect secret is no longer valid")
        val user = o["User"]?.jsonObject ?: JsonObject(emptyMap())
        return o.str("AccessToken") to user.str("Id")
    }

    fun serverId(): String = runCatching {
        getJson("/System/Info/Public")?.str("Id").orEmpty()
    }.getOrDefault("").ifEmpty { base }

    fun serverName(): String = runCatching {
        getJson("/System/Info/Public")?.str("ServerName").orEmpty()
    }.getOrDefault("").ifEmpty { base }

    // --------------------------------------------------------------- browse

    /**
     * Everything eligibility needs, asked for with the listing.
     *
     * Unlike Plex, where every eligibility check is its own metadata request
     * (23 ms each, four seconds for a long show), Jellyfin will return the
     * trickplay manifest and the stream list inline — so the scan is free here
     * and the streaming UI is just as necessary for Plex.
     */
    val itemFields = "MediaSources,MediaStreams,Trickplay,RunTimeTicks"

    fun views(userId: String): List<JsonObject> =
        getJson("/Users/${enc(userId)}/Views")?.arr("Items").orEmpty()

    fun resume(userId: String): List<JsonObject> =
        getJson(
            "/Users/${enc(userId)}/Items/Resume?" +
                "Limit=40&MediaTypes=Video&Fields=${enc(itemFields)}",
        )?.arr("Items").orEmpty()

    fun items(query: String): List<JsonObject> =
        getJson("/Items?$query")?.arr("Items").orEmpty()

    fun playlistItems(userId: String, playlistId: String): List<JsonObject> =
        getJson(
            "/Playlists/${enc(playlistId)}/Items?userId=${enc(userId)}" +
                "&Fields=${enc(itemFields)}",
        )?.arr("Items").orEmpty()

    // ------------------------------------------------------------ media

    /** One tile sheet. ~865 KB, and it carries up to 100 thumbnails (F-038). */
    fun trickplaySheet(itemId: String, width: Int, sheet: Int): ByteArray =
        getBytes("/Videos/${enc(itemId)}/Trickplay/$width/$sheet.jpg")

    /**
     * One subtitle stream, converted to SRT on demand.
     *
     * The conversion is the point: Jellyfin will serve an EMBEDDED track this
     * way, which is why its eligibility rule must not be Plex's (F-037).
     */
    fun subtitleSrt(itemId: String, mediaSourceId: String, index: Int): ByteArray =
        getBytes("/Videos/${enc(itemId)}/${enc(mediaSourceId)}/Subtitles/$index/Stream.srt")

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun JsonObject.str(k: String, d: String = ""): String =
            this[k]?.jsonPrimitive?.contentOrNull ?: d
        fun JsonObject.int(k: String, d: Int = 0): Int =
            this[k]?.jsonPrimitive?.intOrNull ?: d
        fun JsonObject.long(k: String, d: Long = 0L): Long =
            this[k]?.jsonPrimitive?.longOrNull ?: d
        fun JsonObject.arr(k: String): List<JsonObject> =
            (this[k] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    }
}

private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
