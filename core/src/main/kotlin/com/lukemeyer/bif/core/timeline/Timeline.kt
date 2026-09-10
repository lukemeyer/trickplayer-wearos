package com.lukemeyer.bif.core.timeline

import com.lukemeyer.bif.core.source.FrameLocator

/**
 * The frame timeline for one item: which frame exists at which moment, and
 * where to get it.
 *
 * **Named `Timeline`, not `BifIndex` and not `Trickplay`, on purpose.** BIF is a
 * Plex/Roku container; the *concept* of a frame timeline survives a second media
 * source but the format does not, so a type that hands out BIF byte offsets
 * would make adding one a rewrite. `Trickplay` is out because that is the name
 * of Jellyfin's own feature, and the collision would land in exactly the file
 * where it is most confusing. See trickplayer-knowledge/PLAN.md §6-7.
 *
 * Today the only source is Plex, so what follows parses BIF specifically. The
 * seam that makes that an implementation detail is Phase 3.
 *
 * ## BIF (Roku/Plex trick-play index) parsing
 *
 * Layout: a 64-byte header, then (count + 1) 8-byte entries of
 * [timestamp uint32 LE, offset uint32 LE]. The final entry is a sentinel whose
 * timestamp is 0xFFFFFFFF and whose offset is EOF — that is what gives the last
 * real frame its length, so it must be read, not skipped.
 *
 * Only the index is parsed. The frames are NOT read here: the test episode's BIF
 * is 9,497,976 bytes, so frames are fetched individually by byte range. The
 * index itself is 5,960 B and is fetched once.
 *
 * Two corrections carried forward from bif-watchface-pebble, both found against
 * a real Plex file rather than the spec (see that project's PHASE0-FINDINGS.md):
 *
 *  1. The timestamp multiplier at offset 16 is **0** in real Plex output. Per the
 *     BIF spec, 0 means "use the 1000 ms default". plex-bif-viewer hardcoded
 *     `* 1000` and was right by luck; reading the field literally yields
 *     all-zero timestamps and a silently broken cue -> frame mapping.
 *  2. The last entry's length comes from the sentinel, not from the file size
 *     guessed at by the caller.
 */
object Timeline {

    private val MAGIC = byteArrayOf(
        0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    const val HEADER_BYTES = 64

    /** One frame's position in the BIF file. */
    /**
     * A frame's position **in the BIF file** — a byte range.
     *
     * This is a Plex [FrameLocator]: shared code never reads `offset` or
     * `length`, because a tile-sheet source has neither. It reaches the scene
     * policy wrapped in a `source.FrameRef`, which carries only the timestamp
     * and a size hint. See `trickplayer-knowledge/SEAM.md`.
     */
    data class FrameRef(val tsMs: Long, val offset: Int, val length: Int) : FrameLocator

    data class Header(
        val version: Int,
        val count: Int,
        val multiplier: Int,
        /** Total bytes of header + entries — exactly what to range-fetch. */
        val indexBytes: Int,
    )

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) or
            ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or
            ((b[o + 3].toLong() and 0xff) shl 24)) and 0xffffffffL

    /** @param bytes at least the 64-byte header. */
    fun parseHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_BYTES) { "need $HEADER_BYTES header bytes, got ${bytes.size}" }
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) throw IllegalArgumentException("not a BIF file")
        }
        val count = u32(bytes, 12).toInt()
        // 0 means "default 1000 ms" — see note 1 above.
        val mult = u32(bytes, 16).toInt().let { if (it == 0) 1000 else it }
        return Header(
            version = u32(bytes, 8).toInt(),
            count = count,
            multiplier = mult,
            indexBytes = HEADER_BYTES + 8 * (count + 1),
        )
    }

    /**
     * @param bytes the whole index region (header + entries), i.e.
     *   [Header.indexBytes] long.
     * @return one entry per frame, in file order.
     */
    fun parseIndex(bytes: ByteArray, header: Header): List<FrameRef> {
        require(bytes.size >= header.indexBytes) {
            "need ${header.indexBytes} index bytes, got ${bytes.size}"
        }
        return List(header.count) { i ->
            val o = HEADER_BYTES + 8 * i
            val ts = u32(bytes, o)
            val off = u32(bytes, o + 4).toInt()
            // The next entry's offset is this frame's end. For the last frame
            // that next entry is the sentinel, whose offset is EOF.
            val next = u32(bytes, HEADER_BYTES + 8 * (i + 1) + 4).toInt()
            FrameRef(tsMs = ts * header.multiplier, offset = off, length = next - off)
        }
    }

    /**
     * Lift a parsed BIF index into the source-neutral form the scene policy
     * consumes. The byte length becomes the size hint — Plex is a source that
     * *can* answer that question, which is why blank filtering and duplicate
     * detection are available here and not on a tile-sheet source.
     */
    fun toFrameRefs(index: List<FrameRef>): List<com.lukemeyer.bif.core.source.FrameRef> =
        index.map {
            com.lukemeyer.bif.core.source.FrameRef(
                tsMs = it.tsMs, sizeHint = it.length, locator = it,
            )
        }

    /**
     * Pick one frame every [intervalMs] of video, rather than using the BIF's
     * native spacing.
     *
     * This is load-bearing, not a tuning detail. Real Plex files are **2 s**
     * apart, which is far finer than a scene wants: a scene should span several
     * subtitle cues so that most advances move text only. At native spacing,
     * with ~3 s cues, nearly every advance would cross into a new frame and the
     * whole scene model collapses. At 10 s the measured average is 3.17 cues per
     * scene.
     */
    fun pickFrames(index: List<FrameRef>, intervalMs: Long): List<Int> {
        val picked = ArrayList<Int>()
        var nextTs = 0L
        for (i in index.indices) {
            if (index[i].tsMs >= nextTs) {
                picked.add(i)
                nextTs = index[i].tsMs + intervalMs
            }
        }
        return picked
    }
}
