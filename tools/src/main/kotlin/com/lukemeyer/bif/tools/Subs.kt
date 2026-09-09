package com.lukemeyer.bif.tools

import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.plex.PlexLibrary
import kotlinx.serialization.json.*
import java.io.File
import java.util.Properties

/**
 * How much of a show can this watch face actually use, and why not the rest?
 *
 * Counts a show's episodes three ways — sidecar subtitles (usable), embedded
 * only (rejected), none at all — and dumps the raw stream fields for a couple of
 * the rejected ones.
 *
 * Written during the investigation in spikes/EMBEDDED-SUBTITLES.md, and kept
 * because the answer is worth being able to ask again: the filter is strict
 * enough that "why is my show not listed?" is a fair question, and this answers
 * it in one command.
 *
 *   ./gradlew :tools:runSubs
 */
fun main() {
    val props = Properties().apply { File("local.properties").inputStream().use { load(it) } }
    fun p(k: String) = props.getProperty(k)?.trim().orEmpty()
    val server = p("plex.server")
    val plex = PlexClient(p("plex.token"), allowInsecureDirect = true)
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val lib = PlexLibrary(server, plex)

    fun container(path: String): JsonObject =
        json.parseToJsonElement(plex.getJson(server.trimEnd('/') + path))
            .jsonObject["MediaContainer"]?.jsonObject ?: JsonObject(emptyMap())
    fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
    fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    val tv = lib.sections().firstOrNull { it.type == "show" && it.title.equals("TV", true) }
        ?: lib.sections().first { it.type == "show" }
    val show = lib.shows(tv.key).firstOrNull { it.title.contains("Futurama", true) }
        ?: lib.shows(tv.key).first()
    println("show: ${show.title}")

    val eps = lib.episodes(show.ratingKey)
    println("episodes: ${eps.size}\n")

    var shown = 0
    var withSidecar = 0
    var embeddedOnly = 0
    var noSubsAtAll = 0

    for (ep in eps) {
        val meta = container("/library/metadata/${ep.ratingKey}").arr("Metadata").firstOrNull() ?: continue
        val part = meta.arr("Media").flatMap { it.arr("Part") }.firstOrNull() ?: continue
        val subs = part.arr("Stream").filter { it["streamType"]?.jsonPrimitive?.intOrNull == 3 }

        when {
            subs.isEmpty() -> { noSubsAtAll++; continue }
            subs.any { it.s("key").isNotEmpty() } -> { withSidecar++; continue }
            else -> embeddedOnly++
        }

        if (shown < 2) {
            shown++
            println("=== ${ep.subtitle} ${ep.title} (ratingKey ${ep.ratingKey}) ===")
            println("  partId   = ${part.s("id")}")
            println("  indexes  = ${part.s("indexes")}  (sd == has BIF)")
            println("  container= ${part.s("container")}")
            subs.forEachIndexed { i, st ->
                println("  subtitle stream[$i]:")
                st.entries.sortedBy { it.key }.forEach { (k, v) -> println("      $k = $v") }
            }
            println()
        }
    }
    println("--- across ${eps.size} episodes ---")
    println("  sidecar subtitles : $withSidecar   (usable today)")
    println("  embedded only     : $embeddedOnly   (what we are trying to reach)")
    println("  no subtitles      : $noSubsAtAll")
}
