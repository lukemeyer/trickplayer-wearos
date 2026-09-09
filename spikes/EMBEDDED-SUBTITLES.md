# Can embedded subtitles be reached?

The eligibility filter requires a subtitle stream with a non-null `key`, which
means a **sidecar file**. Streams embedded in the container report no key. On the
test library that is the difference between 50 and 157 usable Futurama episodes,
so it is worth knowing exactly why, and what the alternatives cost.

## What the library actually looks like

Measured across all 157 episodes (`./gradlew :tools:runSubs`):

| | |
|---|---|
| Sidecar subtitles | **50** — usable today |
| Embedded only | **107** — the prize |
| No subtitles at all | **0** |

Every one of the 107 has `indexes: sd`, so the BIF is already there. Subtitles
are the only thing standing between them and working.

A representative embedded stream, verbatim from the server:

```
codec = "srt"            id = 2674
format = ass  (per the transcode decision)
index = 2                container = mkv
language = "English"     streamType = 3
                         ← note: no "key" field at all
```

## What was tried

### `/library/streams/{id}` — **501 Not Implemented**

The endpoint that serves sidecars, aimed at an embedded stream id. Not a 404 or
an empty body: the server explicitly says it does not implement this. Embedded
streams are simply not exposed as fetchable text. That is the whole reason for
the filter.

### `/library/parts/{id}` — works, and is useless

Returns the entire MKV, 74,393,793 bytes, HTTP 200, and it honours Range. So the
file *is* reachable and could in principle be parsed.

It is not worth it. Matroska interleaves subtitle blocks through the clusters
alongside video, so there is no contiguous subtitle region to range-fetch —
recovering the text means reading essentially the whole file. **74 MB of transfer
for about 32 KB of text**, per episode. That is a non-starter on a watch, and it
would need an MKV demuxer that this project has no other use for.

### The transcoder — promising, then not

`/video/:/transcode/universal/decision` says the server is willing:

```
decision = transcode
location = segments-subs
format   = ass
```

`segments-subs` reads like exactly what is wanted — subtitles delivered as their
own HLS segments. But the master playlist that comes back never names a subtitle
rendition:

```
#EXTM3U
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2498000,RESOLUTION=960x720
session/<id>/base/index.m3u8
```

No `#EXT-X-MEDIA:TYPE=SUBTITLES`, and the guessed paths (`base-subs/`, `subs/`)
are 404. The suspicion was that Plex only offers a separate subtitle track to a
client that has declared it can consume one, so it was retried with

```
X-Plex-Client-Profile-Extra: add-transcode-target-subtitle-codec(
    type=videoProfile&context=streaming&protocol=hls&container=mpegts&subtitleCodec=webvtt)
```

as a header, and with `X-Plex-Product`/`X-Plex-Platform` set to look like the web
client. **The playlist is byte-identical in all three cases.** The server burns
the subtitles into the video and does not offer them separately.

**A trap worth recording**: with `hasMDE=1`, calling `/start.m3u8` without first
calling `/decision` for the same session returns **400 Bad Request**. The first
attempt at bisecting the profile header was worthless because every arm was
failing for that reason instead of the one being tested.

And even if this route had worked, the economics are poor: subtitle segments are
produced as the transcode progresses, so harvesting a whole episode means driving
a full video transcode to completion — minutes of somebody's server CPU for 32 KB
of text.

### On-demand subtitle search — the one live option

```
GET /library/metadata/{ratingKey}/subtitles?language=en
```

Returns ~10 candidates from OpenSubtitles, each a real stream with a real key,
and the best one flagged:

```
{"id":110442,"key":"/library/streams/110442","codec":"srt","format":"srt",
 "perfectMatch":"1","providerTitle":"OpenSubtitles", ...}
```

But `GET /library/streams/110442` is **404**. The candidates are transient
listings, not files — the ids even change between two searches a minute apart
(110432 → 110442). Making one real requires a **write**:

```
PUT /library/metadata/{ratingKey}/subtitles?key=…
```

which downloads the subtitle and attaches it to the item. After that it is an
ordinary sidecar with a fetchable key, and **every existing line of this project
works on it unchanged** — no new parser, no transcode session, no new failure
mode.

Note `hearingImpaired=0&forced=0` as query parameters returns **500**; the
`language` parameter alone works.

## Where that leaves it

| Option | Cost | Verdict |
|---|---|---|
| Read embedded streams directly | — | **Impossible.** 501 by design |
| Parse the MKV ourselves | 74 MB per episode, plus a demuxer | Absurd for a watch |
| Harvest transcoder subtitle segments | A full video transcode per episode | Not offered anyway |
| **Plex on-demand subtitle search** | One `PUT` per episode, writes to the library | **The only viable route** |
| Do nothing | — | 50 of 157 stay usable |

The last two are a genuine trade rather than a technical question. The download
route works and is cheap, but it writes to the user's library and the subtitles
come from a third party, so their timing may not match the embedded track that
was already in the file. That is a decision for whoever owns the library, not
something to do quietly on their behalf.

## Decision: leave it alone

**Not pursued.** The strict filter stays, and the library is never modified.

The deciding factor is not difficulty — the `PUT` is a few lines — but that the
only route on offer swaps subtitles that are already correct, sitting inside the
file and timed to that exact encode, for third-party ones whose sync is a guess.
The whole design rests on a cue matching the frame beside it; subtitles that
drift break the thing the watch face is for, and they break it quietly.

Fifty episodes of one show is plenty to use, and `:tools:runSubs` answers "why
isn't this one listed?" whenever it comes up.

Worth revisiting if Plex ever exposes embedded streams directly. The 501 on
`/library/streams/{id}` is a deliberate gap, not a missing feature, so it may
close one day.
