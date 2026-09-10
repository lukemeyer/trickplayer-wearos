package com.lukemeyer.bif.core.source

import com.lukemeyer.bif.core.subs.Srt

/**
 * The media-source seam. See `trickplayer-knowledge/SEAM.md`.
 *
 * **The one rule: shared code never asks a source-shaped question.** Not "does
 * this stream have a key", not "what is this frame's byte offset", not "how big
 * is this frame". Each of those has a different answer per provider and a wrong
 * answer for at least one of them.
 *
 * The contract is written against two real sources that disagree about nearly
 * everything: Plex ships a BIF index of byte offsets with per-frame lengths at
 * 2 s spacing; Jellyfin ships tile sheets at 10 s spacing with no per-frame
 * lengths at all, where one thumbnail costs an ~865 KB sheet and the next 99
 * are free.
 */

/**
 * Opaque. Only the provider that created it may read it.
 *
 * Plex puts a byte range in here; Jellyfin puts a sheet index and a grid cell.
 * Shared code that unwraps it has hard-coded a provider.
 */
interface FrameLocator

/** Opaque handle to an item's frame timeline. */
interface TimelineRef

/** Opaque handle to an item's subtitles. */
interface SubtitleRef

/**
 * One frame's position in time, and how much it costs.
 *
 * @param sizeHint compressed bytes, or **null if the source cannot say**. Null
 *   is an answer, not missing data: it is what makes blank filtering (F-007)
 *   and duplicate detection (F-036) *unavailable* on that source rather than
 *   merely expensive.
 */
data class FrameRef(
    val tsMs: Long,
    val sizeHint: Int?,
    val locator: FrameLocator,
)

/** An item that can actually be shown. */
data class Playable(
    val title: String,
    val durationMs: Long?,
    val timelineRef: TimelineRef,
    val subtitleRef: SubtitleRef?,
)

enum class FetchGranularity {
    /** One frame is one fetch — Plex's ranged GET. */
    FRAME,

    /**
     * One fetch yields many frames — Jellyfin's tile sheet.
     *
     * A provider declaring this **owns its own cache**: callers ask for one
     * frame and must not know a sheet was fetched. Prefetching one frame ahead
     * is meaningless here, and preview cost is not `n × frameSize`.
     */
    BATCH,
}

/**
 * What this provider can and cannot do, so callers can omit steps rather than
 * discovering emptiness — and so the scene policy can turn off filters it has
 * no input for, in one place.
 */
data class Capabilities(
    /** Jellyfin: the address *is* the identity, so it comes before auth. */
    val needsAddressFirst: Boolean,
    /** Plex has an account service listing servers; Jellyfin has none. */
    val hasServerDiscovery: Boolean,
    val hasPlaylists: Boolean,
    val hasContinueWatching: Boolean,
    /** False disables blank filtering and duplicate detection. */
    val hasFrameSizeHints: Boolean,
    val fetchGranularity: FetchGranularity,
)

interface MediaSource {

    fun capabilities(): Capabilities

    /**
     * The frame timeline for one item.
     *
     * Derived however the provider likes — parsed from an index, or computed
     * from tile geometry. Callers see only [FrameRef].
     */
    fun timeline(playable: Playable): List<FrameRef>

    /**
     * The bytes of one frame, ready to decode.
     *
     * A [FetchGranularity.BATCH] provider fetches and caches whatever larger
     * unit it must; that is its business, not the caller's.
     */
    fun frameBytes(playable: Playable, frame: FrameRef): ByteArray

    /** Parsed cues, already decoded and cleaned. Empty if the item has none. */
    fun cues(playable: Playable): List<Srt.Cue>

    /**
     * What previewing [sceneCount] scenes would cost, or null if unknowable.
     *
     * Not `sceneCount × frameSize`: on a batch source three scenes and a
     * hundred scenes cost the same.
     */
    fun previewCostBytes(playable: Playable, sceneCount: Int): Long?
}
