package com.lukemeyer.bif.core.scene

import com.lukemeyer.bif.core.timeline.Timeline
import com.lukemeyer.bif.core.subs.Srt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenePolicyTest {

    /** Frames 2 s apart, all a typical size unless overridden. */
    private fun episode(
        frameCount: Int = 100,
        intervalMs: Long = 10_000,
        skipSilent: Boolean = true,
        sizes: (Int) -> Int = { 12_896 },
        cues: List<Srt.Cue> = defaultCues(frameCount),
    ): Episode {
        val index = List(frameCount) { i ->
            Timeline.FrameRef(tsMs = i * 2000L, offset = i * 1000, length = sizes(i))
        }
        return Episode(index, Timeline.pickFrames(index, intervalMs), cues, intervalMs, skipSilent)
    }

    /** One cue every 3 s, so a 10 s window holds about three. */
    private fun defaultCues(frameCount: Int) =
        (0 until frameCount * 2000 / 3000).map {
            Srt.Cue(it * 3000L, it * 3000L + 2500, "line $it")
        }

    // ------------------------------------------------------------ resolver

    @Test
    fun `resolves to a picked frame`() {
        val ep = episode()
        val r = SceneResolver.resolve(ep, 3)!!
        assertEquals(3, r.sceneIndex)
        assertTrue(r.frameIndex in ep.scenes)
    }

    @Test
    fun `every scene maps to a distinct frame`() {
        // The invariant the whole selection design exists to guarantee. The
        // earlier skip-at-read-time approach violated it against real content:
        // a scene that skipped forward took a later scene's frame, and that
        // scene then showed the same picture again.
        val ep = episode(sizes = { if (it % 7 == 0) 580 else 12_896 })
        val frames = (0 until ep.sceneCount).mapNotNull { SceneResolver.resolve(ep, it)?.frameIndex }
        assertEquals(frames.size, frames.toSet().size, "a frame is used by more than one scene")
    }

    @Test
    fun `near-blank frames are excluded up front, not skipped at read time`() {
        // Frame 0 of the test episode is a 580 B black frame against a 12,896 B
        // mean. It must simply not be in the list.
        val ep = episode(sizes = { if (it == 0) 580 else 12_896 })
        assertTrue(ep.isNearBlank(0))
        assertTrue(0 !in ep.scenes)
        assertEquals(0, SceneResolver.resolve(ep, 0)!!.skipped)
    }

    @Test
    fun `blank threshold is relative to the episode's own median`() {
        // A differently-encoded episode with much smaller frames must not have
        // every frame declared blank.
        val small = episode(sizes = { if (it == 0) 90 else 2_000 })
        assertTrue(small.isNearBlank(0))
        assertTrue(!small.isNearBlank(5))
    }

    @Test
    fun `silent windows are excluded`() {
        // Cues only in the first 10 s; every later window is silent, so the
        // filter would gut the episode and the fallback keeps it watchable.
        val ep = episode(cues = listOf(Srt.Cue(0, 2000, "only line")))
        assertEquals(ep.picked.size, ep.sceneCount, "should fall back rather than empty out")

        // With enough dialogue to survive the filter, silent windows go.
        val sparse = episode(cues = (0 until 40).map { Srt.Cue(it * 3000L, it * 3000L + 2000, "l$it") })
        assertTrue(sparse.sceneCount in 8..sparse.picked.size)
        sparse.scenes.forEach { assertTrue(sparse.cuesFor(it).isNotEmpty()) }
    }

    @Test
    fun `skipSilent off keeps silent windows`() {
        val ep = episode(cues = listOf(Srt.Cue(0, 2000, "only line")), skipSilent = false)
        assertEquals(ep.picked.size, ep.sceneCount)
    }

    @Test
    fun `scene index wraps at the end of the episode`() {
        val ep = episode(frameCount = 50)
        val r = SceneResolver.resolve(ep, ep.sceneCount + 2)!!
        assertEquals(ep.scenes[2], r.frameIndex)
    }

    // ------------------------------------------------------- cue windowing

    @Test
    fun `a ten second window holds about three cues`() {
        // The number the whole design rests on: measured 3.17 on the real
        // episode. With a cue every 3s a 10s window should hold 3 or 4.
        val ep = episode()
        val counts = ep.picked.map { ep.cuesFor(it).size }
        val avg = counts.average()
        assertTrue(avg in 3.0..4.0) { "expected ~3 cues per scene, got $avg" }
    }

    @Test
    fun `no cue appears in two consecutive scenes`() {
        val ep = episode()
        val a = ep.cuesFor(ep.picked[1]).toSet()
        val b = ep.cuesFor(ep.picked[2]).toSet()
        assertTrue(a.intersect(b).isEmpty())
    }

    // -------------------------------------------------------------- cursor

    @Test
    fun `most advances move text only and need no new frame`() {
        val c = Cursor(minDwellMs = 0)
        val cueCount = 4
        val results = (1..4).map { c.advance(it.toLong(), cueCount) }

        assertEquals(
            listOf(
                Cursor.Result.SameScene(0, 1),
                Cursor.Result.SameScene(0, 2),
                Cursor.Result.SameScene(0, 3),
                Cursor.Result.NewScene(1),
            ),
            results,
        )
        // Three of four advances cost no image — the efficiency claim, asserted.
        assertEquals(3, results.count { it is Cursor.Result.SameScene })
    }

    @Test
    fun `a single-cue scene advances straight to the next scene`() {
        val c = Cursor(minDwellMs = 0)
        assertEquals(Cursor.Result.NewScene(1), c.advance(1, cueCount = 1))
    }

    @Test
    fun `advances inside the dwell window are throttled`() {
        val c = Cursor(minDwellMs = 60_000)
        assertEquals(Cursor.Result.SameScene(0, 1), c.advance(100_000, 3))
        assertEquals(Cursor.Result.Throttled, c.advance(120_000, 3))
        assertEquals(1, c.cueIndex, "throttled advance must not move the cursor")
        assertEquals(Cursor.Result.SameScene(0, 2), c.advance(160_001, 3))
    }

    @Test
    fun `default dwell is a minute`() {
        assertEquals(60 * 1000L, Cursor.DEFAULT_MIN_DWELL_MS)
        val c = Cursor()
        assertEquals(Cursor.Result.SameScene(0, 1), c.advance(300_000, 3))
        assertEquals(Cursor.Result.Throttled, c.advance(320_000, 3))
    }

    @Test
    fun `a forced advance ignores the dwell gate`() {
        // A tap is explicit intent and must always move, or the face feels
        // broken; a glance must not, or a wrist-raise runs away.
        val c = Cursor(minDwellMs = 60_000)
        assertEquals(Cursor.Result.SameScene(0, 1), c.advance(100_000, 4))
        assertEquals(Cursor.Result.Throttled, c.advance(101_000, 4))
        assertEquals(Cursor.Result.SameScene(0, 2), c.advance(101_000, 4, force = true))
    }

    @Test
    fun `lastAdvanceMs survives a round trip through storage`() {
        // The process holding a Cursor is killed between uses, so the dwell gate
        // only works if the timestamp is persisted and restored.
        val first = Cursor(minDwellMs = 60_000)
        first.advance(500_000, 3)
        assertEquals(500_000L, first.lastAdvanceMs)

        val restored = Cursor(
            sceneIndex = first.sceneIndex,
            cueIndex = first.cueIndex,
            minDwellMs = 60_000,
            lastAdvanceMs = first.lastAdvanceMs,
        )
        assertEquals(Cursor.Result.Throttled, restored.advance(510_000, 3))
    }
}


/**
 * The cursor walk, which has been wrong twice on hardware in the same way.
 * Both times the face ended up permanently on "Loading…" with a full cache.
 */
class CursorWalkTest {

    /** Scenes 0..15 cached with 3 cues each; 16 and beyond never fetched. */
    private val cached: (Int) -> Int? = { if (it in 0..15) 3 else null }

    @org.junit.jupiter.api.Test
    fun `walks through cues then into the next scene`() {
        org.junit.jupiter.api.Assertions.assertEquals(
            0 to 2, Cursor.walk(0, 0, 2, cached),
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            1 to 0, Cursor.walk(0, 0, 3, cached),
        )
    }

    @org.junit.jupiter.api.Test
    fun `never walks past the last cached scene`() {
        // The exact hardware failure: cache holds 0-15, and a long burst tries
        // to march the cursor to 19.
        val (scene, cue) = Cursor.walk(15, 0, 40, cached)
        org.junit.jupiter.api.Assertions.assertEquals(15, scene)
        org.junit.jupiter.api.Assertions.assertEquals(2, cue, "should rest on the last cue it has")
    }

    @org.junit.jupiter.api.Test
    fun `a cursor already past the cache does not run further`() {
        org.junit.jupiter.api.Assertions.assertEquals(
            19 to 0, Cursor.walk(19, 0, 30, cached),
        )
    }

    @org.junit.jupiter.api.Test
    fun `an uncached scene is not treated as a scene with no cues`() {
        // The bug in one line: `cueCountOf` returning null must stop the walk,
        // not read as "zero cues, therefore advance".
        val onlyOne: (Int) -> Int? = { if (it == 0) 2 else null }
        org.junit.jupiter.api.Assertions.assertEquals(
            0 to 1, Cursor.walk(0, 0, 99, onlyOne),
        )
    }
}
