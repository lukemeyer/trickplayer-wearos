package com.lukemeyer.bif.core.subs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SrtTest {

    @Test
    fun `parses a plain cue`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello there.
        """.trimIndent()
        val cues = Srt.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Hello there.", cues[0].text)
    }

    @Test
    fun `strips html tags that real Plex sidecars are full of`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i>Italic</i> and <b>bold</b>"
        assertEquals("Italic and bold", Srt.parse(srt)[0].text)
    }

    @Test
    fun `strips ASS override blocks`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}Top-positioned line"
        assertEquals("Top-positioned line", Srt.parse(srt)[0].text)
    }

    @Test
    fun `tolerates a missing hours field and a dot separator`() {
        // Both occur in the wild; the browser original handled them and so must
        // this one.
        assertEquals(61_500L, Srt.toMs("01:01.500"))
        assertEquals(3_661_500L, Srt.toMs("01:01:01.500"))
        assertEquals(3_661_500L, Srt.toMs("1:01:01,500"))
    }

    @Test
    fun `handles CRLF line endings`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nLine one\r\nLine two\r\n"
        val cues = Srt.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Line one\nLine two", cues[0].text)
    }

    @Test
    fun `skips blocks with no text and blocks with no timing`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Real line

            2
            00:00:03,000 --> 00:00:04,000

            NOTE: this block has no arrow at all
        """.trimIndent()
        val cues = Srt.parse(srt)
        assertEquals(1, cues.size)
        assertEquals("Real line", cues[0].text)
    }

    @Test
    fun `a cue is owned by the scene it starts in, never both`() {
        // A cue straddling a scene boundary must appear exactly once, otherwise
        // the same line shows twice as you advance.
        val cues = listOf(
            Srt.Cue(9_500, 11_500, "straddles the boundary"),
            Srt.Cue(10_000, 12_000, "starts inside the second window"),
            Srt.Cue(19_999, 21_000, "last of the second window"),
        )
        val first = Srt.cuesInWindow(cues, 0, 10_000)
        val second = Srt.cuesInWindow(cues, 10_000, 20_000)

        assertEquals(listOf("straddles the boundary"), first)
        assertEquals(
            listOf("starts inside the second window", "last of the second window"),
            second,
        )
        // The decisive property: no cue appears in two windows.
        assertTrue(first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun `a silent window yields no cues`() {
        // 14 of 150 windows in the test episode are silent. The scene policy
        // needs this to be an empty list, not a crash.
        val cues = listOf(Srt.Cue(0, 1000, "only line"))
        assertEquals(emptyList<String>(), Srt.cuesInWindow(cues, 50_000, 60_000))
    }

    @Test
    @DisplayName("format is the inverse of parse, so a cue cache needs no new format")
    fun formatRoundTrips() {
        val original = Srt.parse(
            """
            1
            00:00:01,000 --> 00:00:03,500
            <i>First line</i>
            and a second

            2
            01:02:03,004 --> 01:02:05,000
            {\an8}Positioned, then stripped
            """.trimIndent()
        )
        val again = Srt.parse(Srt.format(original))
        assertEquals(original, again)
        // The timestamps survive an hour boundary and a millisecond that is not
        // a round number, which is where a naive formatter goes wrong.
        assertEquals(3_723_004L, again[1].startMs)
    }
}