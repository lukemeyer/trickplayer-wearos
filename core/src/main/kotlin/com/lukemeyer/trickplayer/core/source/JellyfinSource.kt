package com.lukemeyer.trickplayer.core.source

import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient
import com.lukemeyer.trickplayer.core.subs.Srt

/**
 * Jellyfin as a [MediaSource].
 *
 * The seam's whole justification, in one file. Where Plex hands out a byte index
 * and one frame is one ranged GET, Jellyfin hands out **tile sheets**:
 *
 *  * A thumbnail has no byte length of its own, so [FrameRef.sizeHint] is null
 *    and blank filtering (F-007) and duplicate detection (F-036) are
 *    *unavailable* here — skipped, not faked.
 *  * The fetch atom is a sheet of up to 100 thumbnails (~865 KB measured), so
 *    the first thumbnail costs the whole sheet and the next 99 are free. This
 *    provider therefore **owns a cache**, which is what
 *    [FetchGranularity.BATCH] declares.
 *  * Native spacing is 10 s rather than Plex's 2 s.
 *
 * Callers see none of that. They ask for a frame and get JPEG bytes.
 */
class JellyfinSource(
    private val client: JellyfinClient,
    /**
     * Cropping needs a decoder, and :core has none — it is plain Kotlin/JVM so
     * that :tools can run the shipping pipeline with no Android SDK. The
     * platform supplies one, exactly as the JS builds supply canvas or jpeg.js.
     */
    private val cropper: SheetCropper,
) : MediaSource {

    /**
     * The geometry Jellyfin publishes for one width, and everything the frame
     * timeline is derived from. **No network at all** — the manifest arrived
     * with the item metadata, so `timeline()` here costs nothing where Plex
     * costs two ranged reads.
     */
    data class Geometry(
        val tileWidth: Int,
        val tileHeight: Int,
        val thumbWidth: Int,
        val thumbHeight: Int,
        val intervalMs: Long,
        val thumbnailCount: Int,
    ) {
        val perSheet: Int get() = tileWidth * tileHeight
    }

    data class JellyfinTimelineRef(
        val itemId: String,
        val width: Int,
        val geometry: Geometry,
    ) : TimelineRef

    data class JellyfinSubtitleRef(
        val itemId: String,
        val mediaSourceId: String,
        val index: Int,
    ) : SubtitleRef

    /** Where thumbnail `i` sits inside its sheet. A [FrameLocator], so opaque. */
    data class Cell(
        val sheet: Int,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    ) : FrameLocator

    override fun capabilities() = Capabilities(
        // No account service: the address IS the identity.
        needsAddressFirst = true,
        hasServerDiscovery = false,
        hasPlaylists = true,
        hasContinueWatching = true,
        // A thumbnail is a crop, not a file. There is no byte length to judge
        // blankness or duplication by (F-038).
        hasFrameSizeHints = false,
        // One fetch yields up to 100 thumbnails.
        fetchGranularity = FetchGranularity.BATCH,
    )

    override fun timeline(playable: Playable): List<FrameRef> {
        val ref = playable.timelineRef as JellyfinTimelineRef
        val g = ref.geometry
        return (0 until g.thumbnailCount).map { i ->
            FrameRef(
                tsMs = i * g.intervalMs,
                // Not missing data: the honest answer for a source with none.
                sizeHint = null,
                locator = cellOf(g, i),
            )
        }
    }

    /**
     * One thumbnail's bytes, cropped out of its sheet.
     *
     * The caller cannot tell this apart from Plex's ranged GET, which is the
     * point. Whether it cost a request or a crop of something already held is
     * this class's business.
     */
    override fun frameBytes(playable: Playable, frame: FrameRef): ByteArray {
        val ref = playable.timelineRef as JellyfinTimelineRef
        val cell = frame.locator as Cell
        return cropper.crop(sheetBytes(ref, cell.sheet), cell.x, cell.y, cell.w, cell.h)
    }

    // The provider's own cache, which BATCH granularity obliges it to keep. Two
    // sheets covers any plausible run of scenes at 100 thumbnails each, and a
    // watch has no memory to spare for more.
    private val sheets = LinkedHashMap<Int, ByteArray>()

    @Synchronized
    private fun sheetBytes(ref: JellyfinTimelineRef, sheet: Int): ByteArray {
        sheets[sheet]?.let { return it }
        val bytes = client.trickplaySheet(ref.itemId, ref.width, sheet)
        sheets[sheet] = bytes
        while (sheets.size > SHEET_CACHE_MAX) {
            sheets.remove(sheets.keys.first())
        }
        return bytes
    }

    override fun cues(playable: Playable): List<Srt.Cue> {
        val ref = playable.subtitleRef as? JellyfinSubtitleRef ?: return emptyList()
        val bytes = client.subtitleSrt(ref.itemId, ref.mediaSourceId, ref.index)
        // Same BOM sniff as Plex. Jellyfin's conversion output is UTF-8, but
        // deciding that from the bytes costs nothing and F-035 is about what
        // happens when a build assumes (F-035).
        return Srt.parse(Srt.decodeBytes(bytes))
    }

    /**
     * How many SHEETS a preview would touch, not how many frames.
     *
     * Three scenes and a hundred routinely cost the same here. The sheet size is
     * not known until one is fetched, so this estimates from the measured
     * ~865 KB rather than pretending to a precision it does not have.
     */
    override fun previewCostBytes(playable: Playable, sceneCount: Int): Long? {
        val ref = playable.timelineRef as? JellyfinTimelineRef ?: return null
        val g = ref.geometry
        if (g.thumbnailCount == 0) return null
        val step = maxOf(1, g.thumbnailCount / maxOf(sceneCount, 1))
        val touched = HashSet<Int>()
        var i = 0
        while (i < g.thumbnailCount && touched.size <= 64) {
            touched += cellOf(g, i).sheet
            i += step
        }
        return touched.size.toLong() * APPROX_SHEET_BYTES
    }

    companion object {
        private const val SHEET_CACHE_MAX = 2

        /** Measured on a real 10x10 sheet of 320x132 thumbnails (F-038). */
        const val APPROX_SHEET_BYTES = 865L * 1024

        /**
         * Pure geometry, so it can be checked against the captured fixture
         * without a server or a decoder.
         *
         * The final sheet is normally **partial** — the measured capture has 73
         * thumbnails in a 100-cell grid — so anything that assumes full sheets
         * runs off the end of the last image.
         */
        fun cellOf(g: Geometry, i: Int): Cell {
            val cell = i % g.perSheet
            return Cell(
                sheet = i / g.perSheet,
                x = (cell % g.tileWidth) * g.thumbWidth,
                y = (cell / g.tileWidth) * g.thumbHeight,
                w = g.thumbWidth,
                h = g.thumbHeight,
            )
        }
    }
}

/**
 * Crops a region out of an encoded image and re-encodes it.
 *
 * An interface rather than a function because the only implementations that
 * matter are platform ones: Android's `Bitmap`/`YuvImage` in :data, and
 * `javax.imageio` under test. :core stays free of both.
 */
interface SheetCropper {
    fun crop(sheet: ByteArray, x: Int, y: Int, w: Int, h: Int): ByteArray
}
