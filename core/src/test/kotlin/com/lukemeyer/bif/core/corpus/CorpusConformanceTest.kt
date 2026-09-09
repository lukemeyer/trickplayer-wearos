package com.lukemeyer.bif.core.corpus

import com.lukemeyer.bif.core.subs.Srt
import com.lukemeyer.bif.core.timeline.Timeline
import kotlinx.serialization.json.*
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

    // --------------------------------------------------------------- scene

    @Test
    @DisplayName("scene: NOT YET APPLICABLE — this build has neither corpus scene policy")
    fun sceneSelection() {
        assumeTrue(
            false,
            "The corpus scene cases are F-001's native-timing policy and the " +
                "tuner's nearest-to-midpoint interval policy. This build does " +
                "neither: Timeline.pickFrames takes the first frame at or after " +
                "each interval boundary. Adopting F-001 is PLAN.md Phase 2.",
        )
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
