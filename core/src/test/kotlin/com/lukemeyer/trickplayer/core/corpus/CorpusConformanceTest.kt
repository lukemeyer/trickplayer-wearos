package com.lukemeyer.trickplayer.core.corpus

import com.lukemeyer.trickplayer.core.subs.Srt
import com.lukemeyer.trickplayer.core.scene.Episode
import com.lukemeyer.trickplayer.core.source.FrameLocator
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient
import com.lukemeyer.trickplayer.core.source.FrameRef
import com.lukemeyer.trickplayer.core.source.JellyfinSource
import com.lukemeyer.trickplayer.core.source.Playable
import com.lukemeyer.trickplayer.core.source.SheetCropper
import com.lukemeyer.trickplayer.core.timeline.Timeline
import kotlinx.serialization.json.*
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Conformance against the shared corpus.
 *
 * `corpus/` is **vendored** from `trickplayer-knowledge` — never edit it here.
 * Refresh it with that repo's `tools/corpus/sync-corpus.sh`.
 *
 * These are not extra unit tests. The other suites in `:core` check that this
 * implementation does what its author intended; these check that it does what
 * the *other two platforms* were told to do. A failure here means the three
 * builds have diverged on a shared rule, which is the whole reason the corpus
 * exists (see `trickplayer-knowledge/PLAN.md` §5).
 */
class CorpusConformanceTest {

    private val corpus: File = File(
        System.getProperty("corpus.dir") ?: error("corpus.dir not set — see core/build.gradle.kts")
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun obj(rel: String): JsonObject =
        json.parseToJsonElement(File(corpus, rel).readText()).jsonObject

    // ------------------------------------------------------------ timeline

    @Test
    @DisplayName("timeline: header, frames, sentinel length, size invariant")
    fun timeline() {
        val bytes = File(corpus, "timeline/synthetic.bif").readBytes()
        val exp = obj("timeline/synthetic.expected.json")

        val header = Timeline.parseHeader(bytes)
        // The file stores 0, which per the BIF spec means "1000 ms". A parser
        // that reads it literally produces all-zero timestamps.
        assertEquals(exp["multiplierMs"]!!.jsonPrimitive.int, header.multiplier, "multiplier")
        assertEquals(exp["frameCount"]!!.jsonPrimitive.int, header.count, "frameCount")
        assertEquals(
            64 + exp["indexByteLength"]!!.jsonPrimitive.int, header.indexBytes, "indexBytes"
        )

        val index = Timeline.parseIndex(bytes, header)
        val expFrames = exp["frames"]!!.jsonArray
        assertEquals(expFrames.size, index.size, "frame count")
        expFrames.forEachIndexed { i, e ->
            val f = e.jsonObject
            assertEquals(f["tsMs"]!!.jsonPrimitive.long, index[i].tsMs, "frame $i tsMs")
            assertEquals(f["offset"]!!.jsonPrimitive.int, index[i].offset, "frame $i offset")
            // The LAST frame's length comes from the sentinel entry, not from
            // the file size. This assertion is why the sentinel must be read.
            assertEquals(f["length"]!!.jsonPrimitive.int, index[i].length, "frame $i length")
        }

        val headerPlusIndex = exp["invariant"]!!.jsonObject["headerPlusIndex"]!!.jsonPrimitive.int
        assertEquals(
            exp["fileByteLength"]!!.jsonPrimitive.int,
            index.sumOf { it.length } + headerPlusIndex,
            "sum(frame lengths) + header + index == file size",
        )
    }

    @Test
    @DisplayName("timeline: a real multiplier, and a file longer than the sentinel says")
    fun timelineEdgeCases() {
        // Neither shape occurs in real Plex output, which is precisely why a
        // parser that hardcodes 1000, or that derives the last frame's length
        // from the file size, survives on real files. Both bugs are live in
        // the G2 build today.
        for (name in listOf("multiplier", "trailing")) {
            val bytes = File(corpus, "timeline/$name.bif").readBytes()
            val exp = obj("timeline/$name.expected.json")
            val header = Timeline.parseHeader(bytes)
            assertEquals(
                exp["multiplierMs"]!!.jsonPrimitive.int, header.multiplier, "$name multiplier"
            )
            val index = Timeline.parseIndex(bytes, header)
            exp["frames"]!!.jsonArray.forEachIndexed { i, e ->
                val f = e.jsonObject
                assertEquals(f["tsMs"]!!.jsonPrimitive.long, index[i].tsMs, "$name frame $i tsMs")
                assertEquals(f["length"]!!.jsonPrimitive.int, index[i].length, "$name frame $i length")
            }
        }
    }

    @Test
    @DisplayName("timeline: malformed input is rejected, not silently accepted")
    fun timelineRejects() {
        val bytes = File(corpus, "timeline/synthetic.bif").readBytes()

        val notBif = bytes.copyOf().also { it[1] = 0 }
        assertThrows(IllegalArgumentException::class.java) { Timeline.parseHeader(notBif) }
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.parseHeader(bytes.copyOfRange(0, 32))
        }
        val header = Timeline.parseHeader(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.parseIndex(bytes.copyOfRange(0, 70), header)
        }
    }

    @Test
    @DisplayName("timeline: pickFrames decouples scene cadence from native spacing")
    fun pickFrames() {
        val bytes = File(corpus, "timeline/synthetic.bif").readBytes()
        val index = Timeline.parseIndex(bytes, Timeline.parseHeader(bytes))
        // Fixture frames are 2 s apart; a 4 s cadence keeps every second one.
        assertEquals(listOf(0, 2, 4, 6, 8, 10), Timeline.pickFrames(index, 4_000))
    }

    // ---------------------------------------------------------------- subs

    @Test
    @DisplayName("subs: parsing, cleaning, and dropping cues that clean to nothing")
    fun subtitles() {
        val text = File(corpus, "subs/torture.srt").readText()
        val exp = obj("subs/torture.expected.json")
        val cues = Srt.parse(text)

        assertEquals(exp["cueCount"]!!.jsonPrimitive.int, cues.size, "cueCount")
        exp["cues"]!!.jsonArray.forEachIndexed { i, e ->
            val c = e.jsonObject
            assertEquals(c["startMs"]!!.jsonPrimitive.long, cues[i].startMs, "cue $i startMs")
            assertEquals(c["endMs"]!!.jsonPrimitive.long, cues[i].endMs, "cue $i endMs")
            assertEquals(c["text"]!!.jsonPrimitive.content, cues[i].text, "cue $i text")
        }
    }

    @Test
    @DisplayName("subs: a cue belongs to the window it STARTS in")
    fun cueWindows() {
        val cues = Srt.parse(File(corpus, "subs/torture.srt").readText())
        val exp = obj("subs/torture.expected.json")

        exp["cuesInWindow"]!!.jsonArray.forEach { e ->
            val w = e.jsonObject
            val from = w["fromMs"]!!.jsonPrimitive.long
            val to = w["toMs"]!!.jsonPrimitive.long
            val wantStarts = w["expectStartMs"]!!.jsonArray.map { it.jsonPrimitive.long }
            val wantText = wantStarts.map { ms -> cues.first { it.startMs == ms }.text }
            assertEquals(wantText, Srt.cuesInWindow(cues, from, to), "window [$from,$to)")
        }
    }

    @Test
    @DisplayName("scene: a source with no per-frame sizes skips the filters, not fakes them")
    fun sceneNoSizeHints() {
        // A tile-sheet source has no per-frame byte lengths at all, so blank
        // filtering and duplicate detection have no input. SEAM.md §4: they are
        // SKIPPED, not substituted for. This is the case that makes the seam
        // real rather than declarative — before the refactor this build could
        // not express it.
        val fx = obj("scene/episode.frames.json")
        val cx = obj("scene/episode.cues.json")
        val want = obj("scene/episode.expected.json")["cases"]!!
            .jsonObject["no-size-hints"]!!.jsonObject["expect"]!!.jsonObject

        val noSize = fx["frames"]!!.jsonArray.map {
            FrameRef(
                tsMs = it.jsonObject["tsMs"]!!.jsonPrimitive.long,
                sizeHint = null,
                locator = object : FrameLocator {},
            )
        }
        val cues = cx["cues"]!!.jsonArray.map {
            val c = it.jsonObject
            Srt.Cue(c["startMs"]!!.jsonPrimitive.long, c["endMs"]!!.jsonPrimitive.long, "x")
        }
        val ep = Episode(
            index = noSize,
            cues = cues,
            durationMs = fx["durationMs"]!!.jsonPrimitive.long,
            skipSilent = true,
            hasFrameSizeHints = false,
        )

        assertEquals(want["sceneCount"]!!.jsonPrimitive.int, ep.sceneCount, "sceneCount")
        assertEquals(0, ep.blankThresholdBytes, "nothing can be judged blank")
        assertEquals(false, ep.duplicateFlags.any { it }, "nothing can be judged duplicate")
        assertEquals(false, noSize.indices.any { ep.isNearBlank(it) }, "isNearBlank must be false throughout")

        // And the same fixture WITH sizes must differ, or the gate does nothing.
        val withSize = fx["frames"]!!.jsonArray.map {
            val f = it.jsonObject
            FrameRef(
                tsMs = f["tsMs"]!!.jsonPrimitive.long,
                sizeHint = f["length"]!!.jsonPrimitive.int,
                locator = object : FrameLocator {},
            )
        }
        val full = Episode(
            index = withSize, cues = cues,
            durationMs = fx["durationMs"]!!.jsonPrimitive.long, skipSilent = true,
        )
        assertTrue(
            full.sceneCount < ep.sceneCount,
            "size hints should filter something: ${full.sceneCount} vs ${ep.sceneCount}",
        )
    }

    // ---------------------------------------------------------------- real

    @Test
    @DisplayName("real: a captured trick-play index from licence-free content")
    fun realCapture() {
        val dir = File(corpus, "real")
        // Plex captures only. The Jellyfin capture is tile sheets with no .bif
        // and nothing to parse a header from; it is checked by
        // [jellyfinGeometry] instead.
        val names = (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".expected.json") }
            .map { it.name.removeSuffix(".expected.json") }
            .filter { n ->
                val src = obj("real/$n.expected.json")["source"]?.jsonObject
                src?.get("provider")?.jsonPrimitive?.contentOrNull != "jellyfin"
            }
        // corpus/real/ is a slot and may be empty; degrade cleanly.
        assumeTrue(
            names.isNotEmpty(),
            "corpus/real/ is empty — no real captured fixture yet. See its README.",
        )

        for (name in names) {
            val bytes = File(dir, "$name.bif").readBytes()
            val exp = obj("real/$name.expected.json")
            val header = Timeline.parseHeader(bytes)
            // What the encoder actually wrote, rather than what we assume it
            // writes — the reason to capture a real file at all.
            assertEquals(
                exp["multiplierMs"]!!.jsonPrimitive.int, header.multiplier, "$name multiplier"
            )
            val index = Timeline.parseIndex(bytes, header)
            exp["frames"]!!.jsonArray.forEachIndexed { i, e ->
                val f = e.jsonObject
                assertEquals(f["tsMs"]!!.jsonPrimitive.long, index[i].tsMs, "$name frame $i tsMs")
                assertEquals(f["offset"]!!.jsonPrimitive.int, index[i].offset, "$name frame $i offset")
                assertEquals(f["length"]!!.jsonPrimitive.int, index[i].length, "$name frame $i length")
            }

            // The zero-I/O length heuristic against HASHED ground truth from a
            // real encoder (F-036) — the assertion the finding rests on, which
            // a synthetic fixture cannot make honestly.
            exp["duplicateOf"]?.jsonArray?.let { truthArr ->
                val truth = truthArr.map { it !is kotlinx.serialization.json.JsonNull }
                val ep = Episode(
                    Timeline.toFrameRefs(index), emptyList(),
                    durationMs = index.last().tsMs, skipSilent = false,
                )
                var falsePositives = 0
                ep.duplicateFlags.forEachIndexed { i, d -> if (d && !truth[i]) falsePositives++ }
                assertEquals(0, falsePositives, "$name: heuristic flagged a distinct frame")
            }
        }
    }

    @Test
    @DisplayName("real: tile-sheet geometry, from the same film on the other provider")
    fun jellyfinGeometry() {
        val dir = File(corpus, "real")
        val names = (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".expected.json") }
            .map { it.name.removeSuffix(".expected.json") }
            .filter { n ->
                obj("real/$n.expected.json")["source"]?.jsonObject
                    ?.get("provider")?.jsonPrimitive?.contentOrNull == "jellyfin"
            }
        assumeTrue(names.isNotEmpty(), "no Jellyfin capture in corpus/real/")

        for (name in names) {
            val exp = obj("real/$name.expected.json")
            val g = exp["geometry"]!!.jsonObject
            val geometry = JellyfinSource.Geometry(
                tileWidth = g["tileWidth"]!!.jsonPrimitive.int,
                tileHeight = g["tileHeight"]!!.jsonPrimitive.int,
                thumbWidth = g["thumbWidth"]!!.jsonPrimitive.int,
                thumbHeight = g["thumbHeight"]!!.jsonPrimitive.int,
                intervalMs = g["intervalMs"]!!.jsonPrimitive.long,
                thumbnailCount = g["thumbnailCount"]!!.jsonPrimitive.int,
            )

            // The timeline comes from geometry alone — no network, where Plex
            // needs two ranged reads.
            val playable = Playable(
                title = name,
                durationMs = null,
                timelineRef = JellyfinSource.JellyfinTimelineRef("x", 320, geometry),
                subtitleRef = null,
            )
            val frames = JellyfinSource(NoClient, NoCropper).timeline(playable)

            assertEquals(
                g["thumbnailCount"]!!.jsonPrimitive.int, frames.size, "$name frame count"
            )
            exp["frames"]!!.jsonArray.forEachIndexed { i, e ->
                val f = e.jsonObject
                assertEquals(f["tsMs"]!!.jsonPrimitive.long, frames[i].tsMs, "$name frame $i tsMs")
                // **Null, and that is the answer.** A thumbnail has no byte
                // length of its own, which is what makes blank filtering and
                // duplicate detection unavailable here rather than merely
                // expensive (F-038, SEAM.md §4).
                assertNull(frames[i].sizeHint, "$name frame $i sizeHint")
                assertEquals(
                    f["sheet"]!!.jsonPrimitive.int,
                    (frames[i].locator as JellyfinSource.Cell).sheet,
                    "$name frame $i sheet",
                )
            }

            // The crop boxes, including the last thumbnail of a PARTIAL final
            // sheet — 73 thumbnails in a 100-cell grid, which is what anything
            // assuming full sheets runs off the end of.
            for (e in exp["cropBoxes"]!!.jsonArray) {
                val b = e.jsonObject
                val i = b["index"]!!.jsonPrimitive.int
                val cell = JellyfinSource.cellOf(geometry, i)
                assertEquals(b["sheet"]!!.jsonPrimitive.int, cell.sheet, "$name box $i sheet")
                assertEquals(b["x"]!!.jsonPrimitive.int, cell.x, "$name box $i x")
                assertEquals(b["y"]!!.jsonPrimitive.int, cell.y, "$name box $i y")
                assertEquals(b["w"]!!.jsonPrimitive.int, cell.w, "$name box $i w")
                assertEquals(b["h"]!!.jsonPrimitive.int, cell.h, "$name box $i h")
            }

            // Preview cost counts SHEETS, not scenes. On this fixture — 73
            // thumbnails in one 100-cell sheet — three scenes and every scene
            // therefore cost exactly the same.
            //
            // **That equality is a property of a single-sheet item, not of the
            // provider.** A feature-length film is six sheets, where three
            // scenes touch three of them and all the scenes touch six. What
            // generalises is the bound below: cost can never exceed the whole
            // trickplay set however many scenes are asked for, which is what
            // makes gating on the estimate meaningful (F-038, UI.md §3).
            val src = JellyfinSource(NoClient, NoCropper)
            val sheets = (geometry.thumbnailCount + geometry.perSheet - 1) / geometry.perSheet
            val ceiling = sheets * JellyfinSource.APPROX_SHEET_BYTES
            for (n in listOf(1, 3, 10, geometry.thumbnailCount, geometry.thumbnailCount * 10)) {
                val cost = src.previewCostBytes(playable, n)!!
                assertTrue(
                    cost <= ceiling,
                    "$name: $n scenes estimated at $cost, above the $ceiling ceiling",
                )
            }
            if (sheets == 1) {
                assertEquals(
                    src.previewCostBytes(playable, 3),
                    src.previewCostBytes(playable, geometry.thumbnailCount),
                    "$name: one sheet serves every scene, so the cost cannot differ",
                )
            }
        }
    }

    /** Neither is reached: the geometry checks above touch no network or pixels. */
    private object NoCropper : SheetCropper {
        override fun crop(sheet: ByteArray, x: Int, y: Int, w: Int, h: Int) =
            error("no cropping in a geometry test")
    }

    private val NoClient = JellyfinClient("http://unused.invalid")

    @Test
    @DisplayName("subs: encoding is sniffed from the BOM, not assumed")
    fun subtitleEncodings() {
        val exp = obj("subs/torture.expected.json")
        val enc = obj("subs/encodings.expected.json")
        val expected = exp["cues"]!!.jsonArray.map {
            val c = it.jsonObject
            Triple(
                c["startMs"]!!.jsonPrimitive.long,
                c["endMs"]!!.jsonPrimitive.long,
                c["text"]!!.jsonPrimitive.content,
            )
        }

        for (v in enc["variants"]!!.jsonArray) {
            val file = v.jsonObject["file"]!!.jsonPrimitive.content
            val bytes = File(corpus, file.removePrefix("subs/").let { "subs/$it" }).readBytes()
            val cues = Srt.parse(Srt.decodeBytes(bytes))
            assertEquals(expected.size, cues.size, "$file: cueCount")
            cues.forEachIndexed { i, c ->
                assertEquals(expected[i].first, c.startMs, "$file cue $i startMs")
                assertEquals(expected[i].second, c.endMs, "$file cue $i endMs")
                assertEquals(expected[i].third, c.text, "$file cue $i text")
            }
        }

        // A plain UTF-8 file with no BOM must still decode.
        val plain = Srt.parse(Srt.decodeBytes(File(corpus, "subs/torture.srt").readBytes()))
        assertEquals(expected.size, plain.size, "no BOM still decodes as UTF-8")
    }

    // --------------------------------------------------------------- scene

    @Test
    @DisplayName("scene: the adopted policy — native timings, length dedup, empty drop")
    fun sceneSelection() {
        val fx = obj("scene/episode.frames.json")
        val cx = obj("scene/episode.cues.json")
        val want = obj("scene/episode.expected.json")["cases"]!!
            .jsonObject["adopted"]!!.jsonObject["expect"]!!.jsonObject

        val index = fx["frames"]!!.jsonArray.map {
            val f = it.jsonObject
            Timeline.FrameRef(
                tsMs = f["tsMs"]!!.jsonPrimitive.long,
                offset = f["offset"]!!.jsonPrimitive.int,
                length = f["length"]!!.jsonPrimitive.int,
            )
        }
        val cues = cx["cues"]!!.jsonArray.map {
            val c = it.jsonObject
            Srt.Cue(c["startMs"]!!.jsonPrimitive.long, c["endMs"]!!.jsonPrimitive.long, "x")
        }
        val ep = Episode(
            index = Timeline.toFrameRefs(index),
            cues = cues,
            durationMs = fx["durationMs"]!!.jsonPrimitive.long,
            skipSilent = true,
        )

        assertEquals(want["sceneCount"]!!.jsonPrimitive.int, ep.sceneCount, "sceneCount")

        val seen = LinkedHashSet<Int>()
        var bytes = 0L
        var cueTotal = 0
        for (sc in ep.scenes) {
            val f = index[sc.frameIndex]
            if (seen.add(f.offset)) bytes += f.length
            cueTotal += cues.count { it.startMs >= sc.windowStartMs && it.startMs < sc.windowEndMs }
        }
        assertEquals(want["sceneBytes"]!!.jsonPrimitive.long, bytes, "sceneBytes")
        assertEquals(
            want["uniqueFramesShipped"]!!.jsonPrimitive.int, seen.size, "uniqueFramesShipped")
        assertEquals(
            want["avgCuesPerScene"]!!.jsonPrimitive.double,
            (cueTotal.toDouble() / ep.sceneCount), 0.0001, "avgCuesPerScene")

        // Duplicate detection must agree with the fixture's hashed ground
        // truth, so the heuristic is checked against reality rather than
        // against itself (F-036).
        val truth = fx["frames"]!!.jsonArray.map {
            it.jsonObject["duplicateOfIndex"] !is kotlinx.serialization.json.JsonNull &&
                it.jsonObject["duplicateOfIndex"] != null
        }
        var falsePositives = 0
        var missed = 0
        ep.duplicateFlags.forEachIndexed { i, d ->
            if (d && !truth[i]) falsePositives++
            if (!d && truth[i]) missed++
        }
        assertEquals(0, falsePositives, "length heuristic flagged a distinct frame")
        assertEquals(0, missed, "length heuristic missed a real duplicate")
    }

    @Test
    @DisplayName("scene: blank filtering and the usable floor")
    fun sceneFilter() {
        val exp = obj("scene/filter.cases.json")
        for (e in exp["cases"]!!.jsonArray) {
            val c = e.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val lengths = c["lengths"]!!.jsonArray.map { it.jsonPrimitive.int }
            val pct = c["blankThresholdPct"]!!.jsonPrimitive.int
            val wantMedian = c["expectMedianLength"]!!.jsonPrimitive.double
            val wantUsable = c["expectUsableIndices"]!!.jsonArray.map { it.jsonPrimitive.int }

            val index = lengths.mapIndexed { i, len ->
                Timeline.FrameRef(tsMs = i * 2000L, offset = 0, length = len)
            }
            val ep = Episode(
                index = Timeline.toFrameRefs(index),
                cues = emptyList(),
                durationMs = index.size * 2000L,
                skipSilent = false,
            )
            // Assert the threshold directly rather than recovering the median
            // by division: blankThresholdBytes truncates to Int, so dividing
            // back out reports a median a few bytes light and turns an exact
            // agreement into a spurious failure.
            val wantThreshold = (wantMedian * pct / 100.0).toInt()
            assertEquals(
                wantThreshold, ep.blankThresholdBytes,
                "$name: blank threshold (median $wantMedian at $pct%)",
            )
            assertEquals(
                wantUsable,
                index.indices.filter { !ep.isNearBlank(it) },
                "$name: usable",
            )
        }
    }

    // ---------------------------------------------------------------- cues

    @Test
    @DisplayName("cues: NOT YET APPLICABLE — no wrap/paginate implementation")
    fun cueLayout() {
        assumeTrue(
            false,
            "F-002 is not implemented here — the watch face relies on WFF " +
                "LONG_TEXT wrapping and ellipsises rather than paginating.",
        )
    }
}
