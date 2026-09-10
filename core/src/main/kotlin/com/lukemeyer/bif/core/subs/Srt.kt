package com.lukemeyer.bif.core.subs

/**
 * SRT parsing.
 *
 * Ported from bif-watchface-pebble's subs.js, which in turn came from
 * plex-bif-viewer's subtitles.ts. Differences kept from the Pebble version:
 *
 *  * HTML tags are stripped. Real Plex sidecars are full of `<i>…</i>`, and ASS
 *    override blocks like `{\an8}` show up too.
 *  * No chunked yielding. The browser original yielded to the event loop every
 *    200 blocks to keep a UI responsive; a 476-cue file parses in about a
 *    millisecond and this runs off the main thread anyway.
 *
 * The time pattern tolerates a missing hours field and both `,` and `.` as the
 * millisecond separator — real files need both.
 */
object Srt {

    /**
     * Decode subtitle bytes, sniffing the byte-order mark.
     *
     * A real Plex server serves UTF-16 sidecars, labelled `text/html` with no
     * charset at all. Decoding blindly as UTF-8 — which this build did — gives
     * a string full of NULs, out of which [parse] extracts **zero** cues.
     * Silently: the fetch is a 200, the parse succeeds, and the face shows a
     * frame with no dialogue for the whole episode. `skipSilent` then judges
     * every window silent on top of that, so scene filtering collapses to its
     * floor and the real cause is buried one level deeper.
     *
     * Content-Type is deliberately not consulted; the bytes are the only
     * honest signal. See trickplayer-knowledge findings/F-035.
     */
    fun decodeBytes(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xff
        return when {
            bytes.size >= 2 && at(0) == 0xff && at(1) == 0xfe ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && at(0) == 0xfe && at(1) == 0xff ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 3 && at(0) == 0xef && at(1) == 0xbb && at(2) == 0xbf ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    // Every regex metacharacter is escaped, including the closing brace, which
    // looks like belt-and-braces and is not. Android's ICU engine rejects a bare
    // `}` that the JVM accepts: `\{[^}]*}` compiles fine under `:core:test` and
    // throws PatternSyntaxException ("near index 8") the moment it runs on a
    // watch. JVM unit tests cannot catch this class of difference — the only
    // defence is escaping defensively and running the thing on a device.
    private val TIME = Regex("""(\d{1,2})?:?(\d{2}):(\d{2})[,.](\d{3})""")
    private val TAGS = Regex("""<[^>]*>""")
    private val BRACES = Regex("""\{[^}]*\}""")

    fun toMs(s: String?): Long {
        if (s == null) return 0
        val m = TIME.find(s.trim()) ?: return 0
        val (h, mm, ss, ms) = m.destructured
        val hours = if (h.isEmpty()) 0L else h.toLong()
        return (hours * 3600 + mm.toLong() * 60 + ss.toLong()) * 1000 + ms.toLong()
    }

    private fun clean(s: String) = s.replace(TAGS, "").replace(BRACES, "").trim()

    /** @return cues in file order. */
    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')

        for (block in norm.split("\n\n")) {
            val lines = block.trim().split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti == -1) continue

            val parts = lines[ti].split("-->")
            val startMs = toMs(parts.getOrNull(0))
            val endMs = toMs(parts.getOrNull(1))

            val body = lines.drop(ti + 1).map(::clean).filter { it.isNotEmpty() }
            if (body.isEmpty()) continue

            out.add(Cue(startMs, endMs, body.joinToString("\n")))
        }
        return out
    }

    /**
     * Every cue belonging to the window `[fromMs, toMs)`.
     *
     * A scene owns a window of video, and a cue is owned by the scene it
     * **starts** in. Selecting on overlap instead would show a line that
     * straddles the boundary twice as you advance.
     */
    fun cuesInWindow(cues: List<Cue>, fromMs: Long, toMs: Long): List<String> =
        cues.filter { it.startMs >= fromMs && it.startMs < toMs }.map { it.text }
}
