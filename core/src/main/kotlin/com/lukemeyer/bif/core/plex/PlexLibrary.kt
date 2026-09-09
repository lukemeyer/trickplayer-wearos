package com.lukemeyer.bif.core.plex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Browsing a media server: libraries, shows, episodes, and — the part that
 * actually matters — which of them this watch face can use at all.
 */
class PlexLibrary(private val server: String, private val plex: PlexClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Section(val key: String, val title: String, val type: String)
    data class Item(val ratingKey: String, val title: String, val subtitle: String)

    /** An item that can actually be shown: it has both halves. */
    data class Playable(
        val partId: Long,
        val subKey: String,
        val subLanguage: String,
        val title: String,
        val durationMs: Long,
    )

    private fun JsonObject.str(k: String, d: String = "") =
        this[k]?.jsonPrimitive?.contentOrNull ?: d
    private fun JsonObject.int(k: String, d: Int = 0) =
        this[k]?.jsonPrimitive?.intOrNull ?: d
    private fun JsonObject.long(k: String, d: Long = 0) =
        this[k]?.jsonPrimitive?.longOrNull ?: d

    private fun get(path: String): JsonObject {
        val url = server.trimEnd('/') + path
        val text = plex.getJson(url)
        return json.parseToJsonElement(text).jsonObject["MediaContainer"]?.jsonObject
            ?: JsonObject(emptyMap())
    }

    private fun JsonObject.arr(k: String): List<JsonObject> =
        (this[k] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    /** Movie and TV libraries only; music and photos have no trick-play index. */
    fun sections(): List<Section> =
        get("/library/sections").arr("Directory")
            .map { Section(it.str("key"), it.str("title"), it.str("type")) }
            .filter { it.type == "show" || it.type == "movie" }

    fun shows(sectionKey: String): List<Item> =
        get("/library/sections/$sectionKey/all").arr("Metadata")
            .map { Item(it.str("ratingKey"), it.str("title"), it.str("year")) }

    /** Every episode of a show, flat. */
    fun episodes(showRatingKey: String): List<Item> =
        get("/library/metadata/$showRatingKey/allLeaves").arr("Metadata")
            .map {
                val s = it.int("parentIndex").toString().padStart(2, '0')
                val e = it.int("index").toString().padStart(2, '0')
                Item(it.str("ratingKey"), it.str("title"), "S${s}E$e")
            }

    /**
     * Episodes and films the account is part-way through.
     *
     * Added because browsing by library does not actually work on a watch. The
     * test account's TV library is **357 shows**, which is roughly forty-four
     * swipes to reach the middle of the alphabet — technically a browse, and
     * useless. On Deck is a handful of items and is almost always the answer to
     * "which episode do you want on your wrist", since it is the one you are
     * already watching.
     *
     * Full browsing stays available for everything else.
     */
    fun onDeck(): List<Item> =
        get("/library/onDeck").arr("Metadata").map { m ->
            if (m.str("type") == "episode") {
                val s = m.int("parentIndex").toString().padStart(2, '0')
                val e = m.int("index").toString().padStart(2, '0')
                Item(m.str("ratingKey"), m.str("grandparentTitle"), "S${s}E$e ${m.str("title")}")
            } else {
                Item(m.str("ratingKey"), m.str("title"), m.str("year"))
            }
        }

    fun movies(sectionKey: String): List<Item> =
        get("/library/sections/$sectionKey/all").arr("Metadata")
            .map { Item(it.str("ratingKey"), it.str("title"), it.str("year")) }

    /**
     * Whether one item is usable, and if so how to fetch it.
     *
     * **Eligibility is much stricter than it looks, and this is the single most
     * surprising thing about the whole project.** An item needs *both*:
     *
     *  * an `sd` trick-play index on the part — no BIF, no pictures; and
     *  * a subtitle stream with a **non-null `key`**.
     *
     * That second condition is the killer. Most SRT streams Plex reports are
     * *embedded* in the media file and have `key: null`; only sidecar files can
     * be fetched separately. Filtering on codec alone offers episodes that then
     * show no dialogue at all. On the Pebble test library this took Futurama
     * from 157 episodes to 50.
     *
     * Checked lazily, one item at a time, by the caller. The G2 app fetched full
     * metadata for every item in the library up front in batches of 20, which is
     * slow on a desktop and hopeless on a watch — and most of it is thrown away.
     */
    fun playable(ratingKey: String): Playable? {
        val meta = get("/library/metadata/$ratingKey").arr("Metadata").firstOrNull() ?: return null

        for (media in meta.arr("Media")) {
            for (part in media.arr("Part")) {
                if (!part.str("indexes").contains("sd")) continue
                val sub = part.arr("Stream").firstOrNull { st ->
                    st.int("streamType") == 3 &&
                        st.str("codec").equals("srt", ignoreCase = true) &&
                        st.str("key").isNotEmpty()
                } ?: continue
                return Playable(
                    partId = part.long("id"),
                    subKey = sub.str("key"),
                    subLanguage = sub.str("language"),
                    title = titleOf(meta),
                    durationMs = meta.long("duration"),
                )
            }
        }
        return null
    }

    private fun titleOf(meta: JsonObject): String =
        if (meta.str("type") == "episode") {
            val s = meta.int("parentIndex").toString().padStart(2, '0')
            val e = meta.int("index").toString().padStart(2, '0')
            "${meta.str("grandparentTitle")} S${s}E$e — ${meta.str("title")}"
        } else {
            meta.str("title")
        }
}
