# Fonts

**Lekton**, `lekton.ttf` — SIL Open Font License 1.1, from Google Fonts
(github.com/google/fonts, `ofl/lekton`).

It is here for one reason: **it is a fixed-width face whose advance is exactly
0.5 em**, so at 32px a character is 16px and the 320px subtitle band is exactly
20 characters. Character count being width is what lets the app decide its own
line breaks — see F-045, and `CueLayout.paddedPagesOf`.

Measured against the alternatives at the same size and band:

| | advance | chars |
|---|--:|--:|
| Lekton | 0.500 em | 20 |
| Inconsolata | 0.500 em | 20 |
| Anonymous Pro | 0.546 em | 18 |
| Space Mono / Roboto Mono | 0.612 em | 16 |

A wider face is not a cosmetic choice here: it costs characters, and characters
cost pagination.

## One thing Lekton does not have

**Only U+0020.** `U+00A0` (no-break space), `U+2007`, `U+2002`, `U+2003` and
`U+3000` all map to glyph 0, so anything using them is drawn from a fallback
face at roughly a quarter em rather than Lekton's half.

That matters because the subtitle indent has to be a no-break space — an
ordinary one is swallowed when the renderer breaks the line. Measured on
hardware, **two no-break spaces make one cell**, which is why
`CueLayout.NBSP_PER_CELL` is 2. Change the font and that number needs
re-measuring, not re-deriving.
