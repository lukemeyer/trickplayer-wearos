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
     * How many U+00A0 make one monospace cell — see [paddedPagesOf]. A property
     * of the font's coverage, not of the algorithm, but it belongs next to the
     * code that emits them.
     */
    const val NBSP_PER_CELL = 2

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
     * Wrap, but treat the cue's own line breaks as breaks.
     *
     * [wrap] folds them into spaces, which is right when the renderer decides
     * the lines anyway. It is wrong when the caller is deciding them — a
     * two-speaker cue is the case that matters, where "- Hi!" and "- Ahoy!"
     * belong on separate lines and joining them orphans a word.
     */
    fun wrapKeepingBreaks(text: String, maxCharsPerLine: Int): List<String> =
        text.split("\n").flatMap { line ->
            if (line.isBlank()) emptyList() else wrap(line, maxCharsPerLine)
        }.ifEmpty { listOf("") }

    /**
     * Wrap where the budget is not the same on every line.
     *
     * Exists because a round screen is not a rectangle: the lowest line of a
     * page sits where the circle has narrowed, so it needs to start a character
     * further in and therefore has one character less to work with. Deciding
     * that during the wrap is the only way to avoid losing a character to the
     * bezel without losing one to a shorter budget everywhere else.
     *
     * @param widthOf the budget for a given 0-based line index.
     */
    fun wrapKeepingBreaks(text: String, widthOf: (Int) -> Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isBlank()) continue
            var rest = para.trim()
            while (rest.isNotEmpty()) {
                val w = widthOf(out.size)
                val taken = wrap(rest, w).firstOrNull() ?: break
                out.add(taken)
                // `wrap` may hard-break an oversized word, so step by what it
                // actually consumed rather than assuming a space follows.
                rest = rest.removePrefix(taken).trimStart()
            }
        }
        return out.ifEmpty { listOf("") }
    }

    /**
     * One cue as pages, with every line **padded out to the full width**.
     *
     * This is how a break is forced through a renderer that deletes newline
     * characters (F-045): a line padded to exactly the band's width leaves no
     * room for the next word, so the renderer's own wrap breaks where this
     * function decided. It only works in a fixed-width font, where character
     * count is width.
     *
     * @param wrapWidth how many characters may carry text — deliberately ONE
     *   LESS than [padWidth], so every line ends in at least one space. Without
     *   that guarantee a full-width line would be concatenated directly onto
     *   the next one and the renderer, seeing no separator, would fuse the two
     *   words either side into a word that does not exist.
     */
    fun paddedPagesOf(
        text: String,
        wrapWidth: Int,
        padWidth: Int,
        maxLinesPerPage: Int,
        /**
         * 0-based line indexes, within a page, that start one character in.
         *
         * For the bezel: the bottom line of a page on a round face is clipped
         * at its left end, and one cell of indent clears it. Those lines are
         * wrapped one character narrower so the indent costs nothing.
         */
        indentedLines: Set<Int> = emptySet(),
        /** How many cells of indent those lines get. */
        indentCells: Int = 1,
    ): List<String> {
        val indented = { i: Int -> (i % maxLinesPerPage) in indentedLines }
        val lines = wrapKeepingBreaks(text) { i ->
            if (indented(i)) wrapWidth - indentCells else wrapWidth
        }
        // U+00A0, not a plain space: the renderer breaks the line AT the
        // padding whitespace and trims what follows, so an ordinary leading
        // space is swallowed and the indent never appears. A no-break space is
        // not a break opportunity and survives to the start of the line.
        //
        // **Two per cell, because it is not the same width as a cell.** Lekton
        // contains U+0020 and nothing else space-like — U+00A0, U+2007, U+2002,
        // U+2003 and U+3000 all map to glyph 0 — so the no-break space is drawn
        // from a fallback face at roughly a quarter em against the monospace
        // half em. Measured on hardware: two of them indent by exactly one
        // cell. The wrap still reserves whole cells, so the arithmetic and the
        // pixels agree.
        val indent = "\u00A0".repeat(indentCells * NBSP_PER_CELL)
        return lines.mapIndexed { i, l -> if (indented(i)) "$indent$l" else l }
            .let { paginate(it, maxLinesPerPage) }
            .map { page -> page.joinToString("") { it.padEnd(padWidth) }.trimEnd() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text) }
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
