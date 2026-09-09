# Shared knowledge

This is **Trickplayer watchface** — the Wear OS build of Trickplayer, one of three
implementations of the same product:

| Repo | Platform |
|---|---|
| `trickplayer-g2` | Even Realities G2 |
| `trickplayer-pebble` | Pebble Time 2 |
| `trickplayer-wearos` | Wear OS |

Findings that are not specific to Wear OS live in **`trickplayer-knowledge`**,
not here.

## The rule

A non-obvious discovery gets a `findings/` entry in `trickplayer-knowledge`,
with the per-platform *applies / status* table filled in, **before the PR that
acts on it merges**.

"Does not apply to Pebble, because X" is a complete answer and takes ten
seconds. Silence is what costs six months — see `PLAN.md` §2 for the three
corrections that sat in one repo's comments while the other two shipped the bug.

## What belongs where

**`trickplayer-knowledge`** — the rules, and the conformance corpus that
enforces them: trick-play index parsing, SRT parsing and cue-window ownership,
scene selection policy, cursor semantics, the media-source contract.

**Here** — everything shaped by this platform's constraints: the image pipeline,
the transport, the trigger model, layout, and config UI. Those are genuinely
different across the three and are not worth unifying.

## Vocabulary

Domain terms are standardized across all three repos. `Timeline` (not
`BifIndex`), `FrameRef`, `timelineRef`, `itemId`, `subtitleRef`, `scene`
(not `chunk`). See `PLAN.md` §7, Phase 0.

## Conformance corpus

`corpus/` is **vendored** from `trickplayer-knowledge` — never edit it here.
Refresh it by running that repo's `tools/corpus/sync-corpus.sh`, which also has
a `--check` mode that reports drift.

```bash
. ./env.sh && ./gradlew :core:test
```

`CorpusConformanceTest` runs alongside the existing `:core` suites and is doing
a different job from them: the others check that this build does what its
author intended, while this one checks that it does what the *other two
platforms* were told to do.

A skipped test there is not a pass. It records a rule this build does not
implement, with a pointer to where that is tracked.
