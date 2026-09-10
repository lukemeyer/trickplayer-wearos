package com.lukemeyer.bif.core.source

import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.subs.Srt
import com.lukemeyer.bif.core.timeline.Timeline

/**
 * Plex as a [MediaSource].
 *
 * Constructed **per route**: the caller owns failover and re-racing ([[F-016]])
 * and simply builds another of these against a different base URL. That keeps
 * this class free of retry policy, which is a device concern rather than a
 * source one.
 */
class PlexSource(
    private val client: PlexClient,
    private val baseUrl: String,
) : MediaSource {

    /**
     * Plex's opaque refs. Nothing outside this file may read them.
     *
     * Named `Plex…` rather than `Timeline`/`Subtitle` because the short names
     * shadow the imported [Timeline] object, which is exactly the sort of
     * collision that produces a baffling "unresolved reference" later.
     */
    data class PlexTimelineRef(val partId: Long) : TimelineRef
    data class PlexSubtitleRef(val key: String) : SubtitleRef

    override fun capabilities() = Capabilities(
        // plex.tv authenticates first and discovers servers after.
        needsAddressFirst = false,
        hasServerDiscovery = true,
        hasPlaylists = true,
        hasContinueWatching = true,
        // A BIF index states every frame's byte length, which is what makes
        // blank filtering (F-007) and duplicate detection (F-036) possible here.
        hasFrameSizeHints = true,
        // One frame is one ranged GET, ~13 KB.
        fetchGranularity = FetchGranularity.FRAME,
    )

    private fun timelineUrl(ref: TimelineRef) =
        client.timelineUrl(baseUrl, (ref as PlexTimelineRef).partId)

    override fun timeline(playable: Playable): List<FrameRef> {
        val url = timelineUrl(playable.timelineRef)
        // Header first to learn how long the index is, then the index itself.
        // The frames are NOT read: the track is megabytes and is fetched one
        // frame at a time (F-005).
        val head = client.getRange(url, 0, (Timeline.HEADER_BYTES - 1).toLong())
        val header = Timeline.parseHeader(head)
        val indexBytes = client.getRange(url, 0, (header.indexBytes - 1).toLong())
        val index = Timeline.parseIndex(indexBytes, header)
        return Timeline.toFrameRefs(index)
    }

    override fun frameBytes(playable: Playable, frame: FrameRef): ByteArray {
        // Unwrapping the locator is this provider's own business — on Plex it
        // is a byte range; a tile-sheet source finds a sheet and a cell here.
        val loc = frame.locator as Timeline.FrameRef
        return client.getRange(
            timelineUrl(playable.timelineRef),
            loc.offset.toLong(),
            (loc.offset + loc.length - 1).toLong(),
        )
    }

    override fun cues(playable: Playable): List<Srt.Cue> {
        val ref = playable.subtitleRef as? PlexSubtitleRef ?: return emptyList()
        // Bytes, then a BOM sniff — never Content-Type. A real Plex server
        // serves UTF-16 sidecars labelled text/html (F-035).
        val bytes = client.getTextBytes(client.subtitleUrl(baseUrl, ref.key))
        return Srt.parse(Srt.decodeBytes(bytes))
    }

    override fun previewCostBytes(playable: Playable, sceneCount: Int): Long? {
        // One fetch per frame here, so cost really is proportional — unlike a
        // batch source, where three scenes and a hundred cost the same.
        val frames = runCatching { timeline(playable) }.getOrNull() ?: return null
        if (frames.isEmpty()) return null
        val median = frames.mapNotNull { it.sizeHint }.sorted()
            .let { if (it.isEmpty()) return null else it[it.size / 2] }
        return median.toLong() * sceneCount
    }
}
