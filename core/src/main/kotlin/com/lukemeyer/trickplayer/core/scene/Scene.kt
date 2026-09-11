package com.lukemeyer.trickplayer.core.scene

import com.lukemeyer.trickplayer.core.source.FrameRef
import com.lukemeyer.trickplayer.core.subs.Srt

/**
 * A scene is one frame plus **every subtitle cue in its window**.
 *
 * That grouping is the whole design. Because the scene interval (~10 s of video)
 * is far coarser than the BIF's native 2 s frame spacing, several cues normally
 * share a frame — measured at 3.17 cues per scene on the test episode. So most
 * advances move the text only and need no new image at all; crossing into a new
 * scene is the expensive step, and everything else exists to hide it.
 */
data class Scene(
    val index: Int,
    /** Which source frame this resolved to — not [index], once skipping is applied. */
    val frameIndex: Int,
    val tsMs: Long,
    val cues: List<String>,
    /** Raw JPEG bytes from the source, passed through untouched. */
    val jpeg: ByteArray,
) {
    val cueCount: Int get() = maxOf(1, cues.size)

    // ByteArray in a data class needs these; the identity that matters is the
    // scene's position, not a byte-by-byte image comparison.
    override fun equals(other: Any?) = other is Scene && other.index == index &&
        other.frameIndex == frameIndex
    override fun hashCode() = index * 31 + frameIndex
}

/**
 * Everything about one episode that is fetched once and then reused: the parsed
 * BIF index, the frames chosen at the scene interval, and the full cue list.
 *
 * The subtitle sidecar is ~32 KB / 476 cues and parses in about a millisecond,
 * so it is worth holding whole — it is what makes most advances text-only.
 */
class Episode(
    val index: List<FrameRef>,
    val cues: List<Srt.Cue>,
    /** End of the last scene's window. Usually the last frame's timestamp. */
    val durationMs: Long = index.lastOrNull()?.tsMs ?: 0L,
    val skipSilent: Boolean = true,
    /**
     * From the provider's [com.lukemeyer.trickplayer.core.source.Capabilities].
     *
     * False means this source has no per-frame byte lengths — a tile-sheet
     * source has none, because a thumbnail is a crop and not a file. Blank
     * filtering and duplicate detection both read that length, so both are
     * **skipped, not faked**: no guessing duplicates from timing, no treating
     * every frame as non-blank and calling that filtering.
     *
     * Taking the decision once, here, is deliberate. The alternative — each
     * filter separately discovering a null size hint and deciding for itself —
     * is how two filters end up disagreeing about what "unavailable" means.
     *
     * Measured on the same film: 55 scenes from Plex, 34 from Jellyfin. That
     * gap is correct. See `trickplayer-knowledge/SEAM.md` §4.
     */
    val hasFrameSizeHints: Boolean = true,
    private val blankThresholdPct: Int = 15,
) {

    /** One scene: a frame, and the window of video it owns. */
    data class SceneRef(
        val frameIndex: Int,
        val windowStartMs: Long,
        val windowEndMs: Long,
    )

    /**
     * Frames below this many bytes are treated as near-blank.
     *
     * Episodes open and close on black and fade through it at act breaks. Frame 0
     * of the test episode is a **580 byte** JPEG against a 12,896 byte mean — it
     * quantises to a single colour and is dead air on a watch face.
     *
     * Judging by compressed size means blank frames are dropped straight from the
     * index, **before any network happens at all**.
     *
     * The threshold is relative to this episode's own median rather than a fixed
     * number, so it travels to content with different encoding settings.
     */
    val blankThresholdBytes: Int = run {
        // Zero means "nothing is ever judged blank", which is the right answer
        // both for an empty episode and for a source with no size hints.
        if (index.isEmpty() || !hasFrameSizeHints) return@run 0
        val sorted = index.mapNotNull { it.sizeHint }.sorted()
        if (sorted.isEmpty()) return@run 0

        // Mean of the two middle values for an even-length list, NOT
        // sorted[size / 2] — see trickplayer-knowledge findings/F-034.
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
        (median * blankThresholdPct / 100.0).toInt()
    }

    /**
     * Byte-identical-to-an-earlier-frame, inferred from **declared length alone**.
     *
     * Hashing the bytes is what F-001 specifies and it costs a full-track
     * read — measured at 1815 of 1815 frames, 10.48 MB, 10.2 s on the episode
     * that finding cites. The length heuristic found 99.7% of duplicates across
     * three real episodes with one false positive in 6,149 frames, for no
     * network at all. See findings/F-036.
     *
     * Each frame is compared to the current run's **representative**, not to its
     * immediate neighbour, so a run survives a frame that merely happens to match
     * the one before it.
     */
    val duplicateFlags: List<Boolean> = run {
        val dup = MutableList(index.size) { false }
        if (hasFrameSizeHints) {
            var rep = 0
            for (i in 1 until index.size) {
                val a = index[i].sizeHint
                val b = index[rep].sizeHint
                if (a != null && b != null && a == b) dup[i] = true else rep = i
            }
        }
        dup
    }

    /**
     * The scenes actually worth showing — blanks, duplicates and silent windows
     * already removed. Scene N is `scenes[N]`, and that is the whole mapping.
     *
     * Binning is by the source's **own frame timings**, not a synthetic interval:
     * every surviving frame is its own scene, so scenes follow the content's real
     * cuts. A scene's window runs to the **next kept frame**, so the time of a
     * skipped duplicate folds into the scene that replaces it and its cues come
     * with it rather than disappearing.
     *
     * Filtering happens once, up front. Stepping past a bad frame at read time
     * does not pass over it — it steals a *later* scene's frame, and that scene
     * then serves it again. Filtering once removes the whole class of bug: every
     * scene maps to a distinct frame by construction.
     *
     * NB: this must be declared **after** [blankThresholdBytes] and
     * [duplicateFlags]. Kotlin initialises properties in declaration order, and
     * with this block above them the threshold is still 0 when the filter runs,
     * so nothing is ever judged blank. That failure is completely silent; a unit
     * test caught it.
     */
    val scenes: List<SceneRef> = run {
        val all = index.indices.toList()
        val target = minOf(MIN_USABLE_SCENES, all.size)

        val notBlank = all.filter { !isNearBlank(it) }
        var kept = notBlank.filter { !duplicateFlags[it] }
        // Dropping duplicates must not gut a static episode, nor must blank
        // filtering: a repetitive face beats an empty one.
        if (kept.size < target) kept = notBlank
        if (kept.size < target) kept = all

        val built = kept.mapIndexed { i, frameIndex ->
            SceneRef(
                frameIndex = frameIndex,
                windowStartMs = index[frameIndex].tsMs,
                windowEndMs = if (i + 1 < kept.size) index[kept[i + 1]].tsMs else durationMs,
            )
        }

        if (!skipSilent) built else {
            val withCues = built.filter { cuesFor(it).isNotEmpty() }
            if (withCues.size >= target) withCues else built
        }
    }

    val sceneCount: Int get() = scenes.size

    fun isNearBlank(frameIndex: Int): Boolean {
        if (!hasFrameSizeHints) return false
        val size = index[frameIndex].sizeHint ?: return false
        return size < blankThresholdBytes
    }

    /** The cues belonging to this scene's window. */
    fun cuesFor(scene: SceneRef): List<String> =
        Srt.cuesInWindow(cues, scene.windowStartMs, scene.windowEndMs)
            // The face wraps text itself; a newline inside a single cue would
            // fight that, so flatten to spaces and keep cues as the unit.
            .map { it.replace('\n', ' ') }

    private companion object {
        /** Below this, the filters are doing more harm than good. */
        const val MIN_USABLE_SCENES = 8
    }
}
