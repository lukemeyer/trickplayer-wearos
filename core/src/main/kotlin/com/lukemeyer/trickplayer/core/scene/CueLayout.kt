package com.lukemeyer.trickplayer.core.scene

/**
 * How a long cue fits a screen without losing any of it. See
 * `trickplayer-knowledge` F-002.
 *
 * **The rule is a no-omission constraint, not a formatting nicety.** A device
 * has a character-per-line budget and a line-per-page budget, and neither is a
 * reason to drop a word: text that does not fit paginates. Truncating with an
 * ellipsis spends three characters saying "there was more", which the reader
 * can already see, and loses the rest.
 *
 * **The two numbers are not here on purpose.** They are screen measurements —
 * pixel width over glyph width, band height over line height — and differ per
 * platform. This file is the algorithm they feed; `watchface.xml` records what
 * they are for this one.
 *
 * What a platform then *does* with several pages — cycle them, extend the
 * scene, wait for the next glance — is also not here. On Wear OS the answer is
 * that a page is simply another cue, so the cursor pages through them exactly
 * as it steps through cues, which costs no fetch and no new concept.
 *
 * Ported line-for-line from the reference implementation,
 * `tools/timeline-tuner/lib/cuewrap.js`, and pinned by `corpus/cues/`.
 */
object CueLayout {

    /**
     * Greedy word-wrap to a character budget.
     *
     * Never splits a word **unless the word itself is longer than the line**,
     * in which case it hard-breaks: every character still shown, spread over
     * more lines. That is not omission, which is what truncating would be.
     *
     * Line breaks inside the cue are treated as spaces. The author's breaks
     * were chosen for a cinema screen and this is a watch; keeping them would
     * spend the four-line budget on ragged half-lines.
     *
     * @param maxCharsPerLine 0 or less means unlimited — one line.
     */
    fun wrap(text: String, maxCharsPerLine: Int): List<String> {
        if (maxCharsPerLine <= 0) return listOf(text)

        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")

        val lines = ArrayList<String>()
        var current = ""

        for (w in words) {
            var word = w
            // A single word too long for the line still has to go somewhere.
            while (word.length > maxCharsPerLine) {
                if (current.isNotEmpty()) {
                    lines.add(current)
                    current = ""
                }
                lines.add(word.substring(0, maxCharsPerLine))
                word = word.substring(maxCharsPerLine)
            }

            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= maxCharsPerLine) {
                current = candidate
            } else {
                lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)

        return lines
    }

    /** @param maxLinesPerPage 0 or less means unlimited — a single page. */
    fun paginate(lines: List<String>, maxLinesPerPage: Int): List<List<String>> {
        if (maxLinesPerPage <= 0) return listOf(lines)
        val pages = lines.chunked(maxLinesPerPage)
        return pages.ifEmpty { listOf(emptyList()) }
    }

    /**
     * One cue as the pages a screen can actually show it in.
     *
     * Each page comes back as a single string with its lines joined by spaces,
     * because the surface this feeds — a WFF `LONG_TEXT` complication —
     * **deletes newline characters outright**, joining the words either side
     * into one. The page was wrapped to fit, so letting the renderer re-wrap
     * the same words lands in the same place.
     */
    fun pagesOf(text: String, maxCharsPerLine: Int, maxLinesPerPage: Int): List<String> =
        paginate(wrap(text, maxCharsPerLine), maxLinesPerPage)
            .map { page -> page.joinToString(" ") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text) }
}
