package com.lukemeyer.bif.core.scene

import com.lukemeyer.bif.core.timeline.Timeline
import com.lukemeyer.bif.core.subs.Srt

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
    /** Which BIF frame this resolved to — not [index], once skipping is applied. */
    val frameIndex: Int,
    val tsMs: Long,
    val cues: List<String>,
    /** Raw BIF JPEG bytes, passed through untouched. */
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
    val index: List<Timeline.FrameRef>,
    val picked: List<Int>,
    val cues: List<Srt.Cue>,
    val intervalMs: Long,
    val skipSilent: Boolean = true,
) {

    /**
     * Frames below this many bytes are treated as near-blank.
     *
     * Episodes open and close on black and fade through it at act breaks. Frame 0
     * of the test episode is a **580 byte** JPEG against a 12,896 byte mean — it
     * quantises to a single colour and is dead air on a watch face.
     *
     * The Pebble build could only discover this *after* fetching and decoding a
     * frame, then had to fetch another. Judging by compressed size instead means
     * blank frames are dropped straight from the index, **before any network
     * happens at all** — a strictly better trick the earlier design had no way to
     * use, since it needed the decoded palette to decide.
     *
     * The threshold is relative to this episode's own median rather than a fixed
     * number, so it travels to content with different encoding settings.
     */
    val blankThresholdBytes: Int = if (index.isEmpty()) 0 else {
        // Mean of the two middle values for an even-length list, NOT
        // sorted[size / 2]. This used to take the upper middle, which differs
        // from the Pebble build and the Timeline Tuner by half the gap between
        // the two central frames — 250 bytes of median, 37 bytes of threshold,
        // on the conformance fixture. No frame happened to sit in that band, so
        // nothing visibly broke; a frame around 12% of median eventually will,
        // and one platform would drop it while another kept it. See
        // trickplayer-knowledge findings/F-034.
        val sorted = index.map { it.length }.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
        (median * 0.15).toInt()
    }

    /**
     * The frames actually worth showing, blanks and silent windows already
     * removed. Scene N is `scenes[N]`, and that is the whole mapping.
     *
     * The obvious design — keep every picked frame and step forward past a bad
     * one at read time — is what both earlier versions of this did, and it is
     * quietly broken. Skipping forward does not pass over a frame, it *steals* a
     * later scene's frame, and that scene then serves it again:
     *
     *     scene 4 -> frame 30 (skipped 2)
     *     scene 5 -> frame 35 (skipped 2)
     *     scene 6 -> frame 30      <-- scene 4 already showed this
     *     scene 7 -> frame 35      <-- and scene 5 showed this
     *
     * Observed on the real test episode. Avoiding just the previous scene's frame
     * does not help, because the collision is with a scene two or more back.
     * Filtering once, up front, removes the whole class of bug: every scene maps
     * to a distinct frame by construction, nothing is skipped at read time, and
     * the scene count is honest.
     *
     * It is also free — both tests are answerable from the parsed index and the
     * cue list, so this costs no network at all.
     *
     * NB: this must be declared **after** [blankThresholdBytes]. Kotlin
     * initialises properties in declaration order, and with this block above it
     * the threshold is still 0 when the filter runs, so nothing is ever judged
     * blank. That failure is completely silent; a unit test caught it.
     */
    val scenes: List<Int> = run {
        val usable = picked.filter {
            !isNearBlank(it) && (!skipSilent || cuesFor(it).isNotEmpty())
        }
        // If the filters would gut the episode — an item with no subtitles, or
        // one whose frames are all tiny — a repetitive face beats an empty one.
        if (usable.size >= MIN_USABLE_SCENES) usable else picked
    }

    val sceneCount: Int get() = scenes.size

    fun isNearBlank(frameIndex: Int): Boolean =
        index[frameIndex].length < blankThresholdBytes

    /** The cues belonging to the window that starts at [frameIndex]. */
    fun cuesFor(frameIndex: Int): List<String> {
        val from = index[frameIndex].tsMs
        return Srt.cuesInWindow(cues, from, from + intervalMs)
            // The face wraps text itself; a newline inside a single cue would
            // fight that, so flatten to spaces and keep cues as the unit.
            .map { it.replace('\n', ' ') }
    }

    private companion object {
        /** Below this, the filters are doing more harm than good. */
        const val MIN_USABLE_SCENES = 8
    }
}
