package com.lukemeyer.bif.tools

import com.lukemeyer.bif.core.timeline.Timeline
import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.scene.Episode
import com.lukemeyer.bif.core.scene.SceneResolver
import com.lukemeyer.bif.core.subs.Srt
import java.io.File
import java.util.Properties

/**
 * Runs the shipping :core pipeline against a real Plex server, headlessly.
 *
 * This is the direct descendant of the trick that made the Pebble project's
 * Phase 0 possible: bif.js and render.js ran under both PebbleKit JS and Node,
 * so spikes/tools/pipeline.js could exercise the *actual shipping code* against
 * a real server with no watch, no emulator and no phone attached. Here the same
 * property comes from :core being plain Kotlin/JVM with no Android imports.
 *
 * Credentials come from local.properties, which is gitignored. Nothing is
 * committed and the token is never printed.
 *
 *   ./gradlew :tools:run
 */
fun main() {
    val props = Properties().apply {
        val f = File("local.properties")
        if (!f.exists()) {
            System.err.println("local.properties not found; need plex.server / plex.token / plex.partId / plex.subKey")
            return
        }
        f.inputStream().use { load(it) }
    }
    fun p(k: String) = props.getProperty(k)?.trim().orEmpty()

    val server = p("plex.server")
    val token = p("plex.token")
    val timelineRef = p("plex.partId").toLongOrNull()
    val subtitleRef = p("plex.subKey")
    if (server.isEmpty() || token.isEmpty() || timelineRef == null) {
        System.err.println("local.properties is missing plex.server / plex.token / plex.partId")
        return
    }

    // .plex.direct certs are valid but issued for a hashed hostname; allow the
    // direct route since this is a dev harness pointed at the user's own LAN.
    val plex = PlexClient(token, allowInsecureDirect = true)
    val timelineUrl = plex.timelineUrl(server, timelineRef)

    say("server", server.substringAfter("//").substringBefore(':').take(14) + "…")
    say("part", timelineRef.toString())

    // ---------------------------------------------------------------- index
    val t0 = System.currentTimeMillis()
    val head = plex.getRange(timelineUrl, 0, 63)
    val header = Timeline.parseHeader(head)
    say("range supported", plex.rangeSupported.toString())
    say("frames", header.count.toString())
    say("multiplier", "${header.multiplier} ms" +
        if (header.multiplier == 1000) "  (field is 0; 1000 is the spec default)" else "")
    say("index bytes", header.indexBytes.toString())

    val idxBytes = plex.getRange(timelineUrl, 0, (header.indexBytes - 1).toLong())
    val index = Timeline.parseIndex(idxBytes, header)
    say("index fetched in", "${System.currentTimeMillis() - t0} ms")

    // --------------------------------------------------------- invariants
    val fileSize = plex.totalSize(timelineUrl)
    val sum = index.sumOf { it.length.toLong() }
    say("file size", fileSize?.toString() ?: "unknown")
    val ok = fileSize != null && sum + header.indexBytes == fileSize
    say("sum(lengths) + index", "$sum + ${header.indexBytes} = ${sum + header.indexBytes}" +
        if (ok) "   MATCHES" else "   *** MISMATCH ***")

    val spacing = if (index.size > 1) index[1].tsMs - index[0].tsMs else 0
    say("native spacing", "$spacing ms")
    val sizes = index.map { it.length }.sorted()
    say("jpeg sizes", "min ${sizes.first()}  median ${sizes[sizes.size / 2]}  " +
        "max ${sizes.last()}  mean ${sizes.average().toInt()}")

    // ------------------------------------------------------------ subtitles
    val cues = if (subtitleRef.isNotEmpty()) {
        val t1 = System.currentTimeMillis()
        val text = plex.getText(plex.subtitleUrl(server, subtitleRef))
        val parsed = Srt.parse(text)
        say("subtitles", "${parsed.size} cues, ${text.length} chars, " +
            "parsed in ${System.currentTimeMillis() - t1} ms")
        parsed
    } else {
        say("subtitles", "no subKey configured")
        emptyList()
    }

    // -------------------------------------------------------------- scenes
    val ep = Episode(Timeline.toFrameRefs(index), cues, durationMs = index.last().tsMs)
    say("binning", "the source's own frame timings (F-001), not a fixed interval")
    say("frames in index", index.size.toString())
    say("blank threshold", "${ep.blankThresholdBytes} B (15% of median)")
    say("near-blank", index.indices.count { ep.isNearBlank(it) }.toString())
    say("duplicates (by length)", ep.duplicateFlags.count { it }.toString() +
        "   ${"%.1f".format(ep.duplicateFlags.count { it } * 100.0 / index.size)}% — F-036, no bytes fetched")
    say("scenes kept", ep.sceneCount.toString())

    val perScene = ep.scenes.map { ep.cuesFor(it).size }
    say("cues per scene", "avg %.2f".format(perScene.average()) +
        "   (this is the number the whole design rests on)")
    val distinct = ep.scenes.map { it.frameIndex }.toSet().size
    say("distinct frames", "$distinct of ${ep.sceneCount}" +
        if (distinct == ep.sceneCount) "   no two scenes share a frame" else "   *** COLLISION ***")
    val sceneBytes = ep.scenes.sumOf { index[it.frameIndex].length.toLong() }
    val trackBytes = index.sumOf { it.length.toLong() }
    say("bytes to ship", "$sceneBytes of $trackBytes" +
        "   ${"%.0f".format(sceneBytes * 100.0 / trackBytes)}% of the track")

    // Resolve and actually fetch a handful, end to end.
    println()
    println("  resolving and fetching the first 5 scenes:")
    var fetched = 0L
    for (i in 0 until 5) {
        val r = SceneResolver.resolve(ep, i) ?: continue
        val ent = index[r.frameIndex]
        val t2 = System.currentTimeMillis()
        val jpeg = plex.getRange(timelineUrl, ent.offset.toLong(), (ent.offset + ent.length - 1).toLong())
        fetched += jpeg.size
        val cueList = ep.cuesFor(r.scene)
        println("    scene %-2d -> frame %-4d @%5ds  %6d B  %d cues  %dms".format(
            i, r.frameIndex, ent.tsMs / 1000, jpeg.size, cueList.size,
            System.currentTimeMillis() - t2))
        cueList.firstOrNull()?.let { println("             \"${it.take(64)}\"") }
    }
    println()
    say("fetched for 5 scenes", "$fetched B")
    say("vs whole BIF", "${fileSize ?: 0} B — the reason frames are ranged, not cached whole")
}

private fun say(k: String, v: String) = println("  %-22s %s".format(k, v))
