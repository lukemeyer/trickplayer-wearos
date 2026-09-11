package com.lukemeyer.trickplayer.core.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The fixture is synthesised rather than committed, so no real media is needed
 * and the structural invariants are stated explicitly. It mirrors what a real
 * Plex BIF looks like — crucially, **multiplier 0** and a real sentinel entry,
 * which are the two things the Pebble port got wrong first time round.
 */
class TimelineTest {

    private fun le32(v: Long) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )

    /**
     * @param multiplier written to offset 16 verbatim; pass 0 to reproduce what
     *   Plex actually emits.
     */
    private fun makeBif(
        frameSizes: List<Int>,
        spacingSec: Int = 2,
        multiplier: Long = 0,
    ): ByteArray {
        val count = frameSizes.size
        val indexBytes = 64 + 8 * (count + 1)
        val out = java.io.ByteArrayOutputStream()

        // header
        out.write(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))
        out.write(le32(0))              // version
        out.write(le32(count.toLong())) // frame count
        out.write(le32(multiplier))     // timestamp multiplier
        out.write(ByteArray(64 - 20))   // reserved

        // entries, then the sentinel
        var off = indexBytes
        for (i in 0 until count) {
            out.write(le32((i * spacingSec).toLong()))
            out.write(le32(off.toLong()))
            off += frameSizes[i]
        }
        out.write(le32(0xFFFFFFFFL))    // sentinel timestamp
        out.write(le32(off.toLong()))   // sentinel offset == EOF

        // frame payloads, so the file-size invariant is checkable
        for (size in frameSizes) out.write(ByteArray(size))
        return out.toByteArray()
    }

    @Test
    fun `multiplier of zero means 1000ms, not zero`() {
        // The whole cue-to-frame mapping rests on this. Reading offset 16
        // literally yields all-zero timestamps and a silently broken face.
        val bif = makeBif(listOf(100, 200, 300), multiplier = 0)
        val header = Timeline.parseHeader(bif)
        assertEquals(1000, header.multiplier)

        val index = Timeline.parseIndex(bif, header)
        assertEquals(listOf(0L, 2000L, 4000L), index.map { it.tsMs })
    }

    @Test
    fun `an explicit multiplier is honoured`() {
        val bif = makeBif(listOf(10, 20), spacingSec = 3, multiplier = 500)
        val header = Timeline.parseHeader(bif)
        assertEquals(500, header.multiplier)
        assertEquals(listOf(0L, 1500L), Timeline.parseIndex(bif, header).map { it.tsMs })
    }

    @Test
    fun `frame lengths come from the next offset, last one from the sentinel`() {
        val sizes = listOf(580, 12896, 21670)
        val bif = makeBif(sizes)
        val header = Timeline.parseHeader(bif)
        val index = Timeline.parseIndex(bif, header)
        assertEquals(sizes, index.map { it.length })
    }

    @Test
    fun `sum of frame sizes plus index equals file size`() {
        // This equality is the parser's own regression test — it is what proved
        // the Pebble parser correct against the real 9,497,976 byte file.
        val sizes = List(736) { 580 + (it * 37) % 21_000 }
        val bif = makeBif(sizes)
        val header = Timeline.parseHeader(bif)
        val index = Timeline.parseIndex(bif, header)

        assertEquals(736, header.count)
        assertEquals(64 + 8 * 737, header.indexBytes)
        assertEquals(bif.size, index.sumOf { it.length } + header.indexBytes)
    }

    @Test
    fun `header bytes are all that is needed to learn the index size`() {
        // The shipping path range-fetches 64 bytes, then indexBytes. Parsing the
        // header must not need any more than those 64.
        val bif = makeBif(List(736) { 1000 })
        val header = Timeline.parseHeader(bif.copyOfRange(0, 64))
        assertEquals(5960, header.indexBytes)
    }

    @Test
    fun `rejects a non-BIF file`() {
        val notBif = ByteArray(64) { 0x7f }
        assertThrows(IllegalArgumentException::class.java) { Timeline.parseHeader(notBif) }
    }

    @Test
    fun `pickFrames decouples scene interval from native 2s spacing`() {
        // The measured reality: Plex emits frames 2s apart. A 10s scene interval
        // must therefore select every 5th frame.
        val bif = makeBif(List(100) { 1000 }, spacingSec = 2)
        val header = Timeline.parseHeader(bif)
        val index = Timeline.parseIndex(bif, header)

        val picked = Timeline.pickFrames(index, 10_000)
        assertEquals(20, picked.size)
        assertEquals(listOf(0, 5, 10, 15), picked.take(4))
        assertEquals(listOf(0L, 10_000L, 20_000L), picked.take(3).map { index[it].tsMs })
    }

    @Test
    fun `pickFrames at or below native spacing keeps every frame`() {
        val bif = makeBif(List(50) { 1000 }, spacingSec = 2)
        val header = Timeline.parseHeader(bif)
        val index = Timeline.parseIndex(bif, header)
        assertEquals(50, Timeline.pickFrames(index, 2000).size)
        assertEquals(50, Timeline.pickFrames(index, 1).size)
    }
}
