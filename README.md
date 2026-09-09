# Trickplayer watchface (Wear OS)

A Wear OS watch face that shows a frame from a Plex BIF trick-play index plus the
subtitles from that moment, advancing through an episode as you glance at it.

Third target for this idea, after
[`trickplayer-g2`](../trickplayer-g2) (Even Realities G2 glasses) and
[`trickplayer-pebble`](../trickplayer-pebble) (Pebble Time 2). The *ideas*
are ported; most of the Pebble *code* is not, because it was shaped almost
entirely by constraints Wear OS does not have.

## Status

| Phase | State |
|---|---|
| 0 — toolchain + spikes | **done**. See [spikes/PHASE0-FINDINGS.md](spikes/PHASE0-FINDINGS.md) |
| 1 — `:core` against a real server | **done** — parsers and scene policy, 29 unit tests, numbers reproduced live |
| 2 — cache & prefetch | **done** — disk cache, WorkManager prefetch, resume position |
| 3 — complication + WFF face | **done** |
| 4 — real content on the face | **done** — real frames and synced subtitles, cached and advancing |
| 5 — config app | **done** — PIN sign-in, route racing, On Deck, eligibility-filtered browse |
| 6 — ambient mode | **done** — frame hidden, thin centred clock; power/hardware still unmeasured |

Real frames and synced subtitles from a real Plex server render on the watch
face, cached and prefetched, advancing on tap, with a proper ambient variant.
Sign-in, server discovery and episode picking all happen on the watch.
Outstanding: verification on real hardware, and the PIN→token exchange itself
(everything up to and including displaying the code is verified; completing the
link needs a human at plex.tv/link).

## The inversion this port turns on

Pebble's central decision was **never send JPEG**. The phone decoded, median-cut
and Floyd–Steinberg dithered every frame to 4-bit indices, because 11,200 B beat
8–15 KB of JPEG over BLE to a device with no decoder and a 122 KB heap.

Every term flips here. `Icon.createWithData(jpegBytes)` hands the BIF JPEG
across a Binder call untouched and the runtime decodes it in hardware. So the
whole image pipeline is gone: `jpeg.js` (340 lines), `render.js` (199),
`proto.js` (136), the base64 localStorage cache (208), the XS heap tuning in
`mdbl.c`, strip expansion, `RING_SIZE = 2`. About 900 lines of the Pebble build
were answers to questions this platform does not ask.

What survives is in `:core`, and it is the part that was never about Pebble: the
BIF index parsing, `pickFrames` decoupling the scene interval from the BIF's
native 2 s spacing, the cue-window model, the skip policy, and the single
rate-limited `advance()` path.

## Two APKs, and they must stay two

Watch Face Format requires `android:hasCode="false"`, and Google requires the
watch face bundle be *completely separate* from the bundle holding app logic. So
the Pebble watch/phone split is reborn as an APK boundary — except the channel is
the complication API rather than chunked AppMessage over BLE:

```
:app        Kotlin. Fetches, decides, caches. Publishes two complication
            data sources — the frame (PHOTO_IMAGE) and the cue (LONG_TEXT).
:watchface  XML only, 13 KB. Renders whatever it is handed.
```

`androidx.wear.watchface` — the watch-face surface itself — is deprecated, and
since 14 Jan 2026 those faces cannot be installed from Play at all. The
complication **data source** libraries are not part of that deprecation, which is
why the app lives on that side of the line.

The same split falls out of a second constraint arrived at independently: a data
source must answer `onComplicationRequest` within 100 ms when
`immediateResponseRequired` is set. It therefore **cannot fetch** — only serve
what a worker already cached. Which is Pebble's design exactly, for a completely
different reason.

## Layout

```
src/
  core/        pure Kotlin/JVM — no Android imports, so :tools can run it headlessly
    bif/       BifIndex.parse, pickFrames
    subs/      Srt.parse, cuesInWindow
    scene/     Episode, SceneResolver, Cursor — the advance/skip policy
    plex/      PlexClient (OkHttp, ranged GETs)
  data/        Android — SceneCache (disk), Settings, EpisodeRepository,
               ScenePrefetchWorker. The only thing allowed to touch the network.
  app/         complication data sources, tap receiver, and config/ — the
               Compose for Wear sign-in and browsing UI
  watchface/   res/raw/watchface.xml and nothing else
  tools/       Pipeline.kt — runs :core against a real Plex server, no device
  spikes/      Phase 0 findings and the vendored WFF XSD
```

`:core` staying free of Android imports is the direct descendant of the trick
that made Pebble's Phase 0 work — `bif.js` ran under both PebbleKit JS and Node,
so the pipeline could be exercised against a real server with nothing attached.

## Build and run

Nothing is installed by default on macOS. `env.sh` records where things are;
`brew install openjdk@21` (the `temurin` cask needs sudo and cannot run
unattended).

```bash
. ./env.sh
./gradlew :core:test          # 29 tests, no device needed
./gradlew :tools:run          # the pipeline against a real Plex server
```

```bash
./run.sh                      # build, install, activate, bind complications, screenshot
```

`run.sh` exists because **none of the install/activate steps are guessable** and
each wrong guess fails with a message pointing somewhere else. The three that
cost the most time:

- A WFF package declares **no service**. Adding the obvious
  `WatchFaceControlService` builds and installs fine, then reports
  *"Watch face package is not installed"* — which it is.
- Selection takes **`--es watchFaceId`**, never `--ecn component`.
- Binding a complication needs the **runtime's** slot ids (the `slotId="1"` in
  the XML is `11` at runtime) and **int** type values from the legacy set
  (`LONG_TEXT = 4`, `PHOTO_IMAGE = 8`).

Full detail, and how each was tracked down, in
[spikes/PHASE0-FINDINGS.md](spikes/PHASE0-FINDINGS.md).

## Installing to a real watch

The watch and this machine must be on the same network. On the watch:

1. Settings → System → About → tap **Build number** seven times.
2. Settings → **Developer options** → enable **ADB debugging**.
3. Same screen → enable **Wireless debugging**, then tap into it. It shows an
   **IP address & port**, and a separate **Pair new device** entry with a
   six-digit code and a *different* port. The two ports are not the same and
   mixing them up is the usual failure.

Then here:

```bash
. ./env.sh
adb pair 192.168.1.50:41234      # the PAIRING port; paste the 6-digit code
adb connect 192.168.1.50:5555    # the port from the Wireless debugging screen
adb devices
```

Older watches sometimes offer "Debug over Wi-Fi" with just an IP and no pairing
code; in that case skip `adb pair` and go straight to `adb connect`.

**Check the API level before installing** — `minSdk` is 34 (Wear OS 5):

```bash
adb -s <serial> shell getprop ro.build.version.sdk
```

34 or higher is fine. If it reports 33 (Wear OS 4), lower `minSdk` to 33 in both
`app/build.gradle.kts` and `watchface/build.gradle.kts` and set the manifest
property `com.google.wear.watchface.format.version` to `1`. Nothing is lost:
WFF v1 already has `PHOTO_IMAGE` and the `Variant` element the ambient mode uses.

Then, naming the device explicitly — with an emulator also attached, a bare
`adb` refuses to act and every step fails in a way that looks like a build
problem:

```bash
./run.sh <serial>
```

If `set-watchface` does not work on retail hardware, select it by hand instead:
long-press the watch face, swipe to **BIF**, tap. Complications are set by
tapping the empty slots — which is worth doing at least once anyway, since it
exercises the real picker and confirms both data sources appear with sensible
names and preview images.

## Which network the watch is actually on

Worth understanding before debugging anything that looks like "no server".

**In Bluetooth range of its phone, a Wear OS watch turns its WiFi interface off**
and proxies all traffic through the phone. So the question "is the watch on
wifi?" is usually the wrong one. Two consequences:

- **A LAN route works whenever the *phone* is on that network**, however far the
  watch is from the wifi. Traffic exits from the phone. Walking to the end of the
  garden does not break a `10.x` address; leaving the house with the phone does.
- **The proxy is slower and higher-latency than direct.** Route probing allows
  8 s for this reason — a budget tuned to wifi times out on the proxy and reports
  every route dead, which is indistinguishable from having no network at all.

Away from the phone, the watch uses its own wifi or LTE, and only the public and
relay routes work — which is what the fallback in `EpisodeRepository` is for.

Frame fetches are ~13 KB, small enough that even the proxy carries them
comfortably.

## Measured against real content

Test episode: Futurama S8E1, 24.5 min, 736 BIF frames. `:tools` reproduces every
number the Pebble build measured — frame count, spacing, index size, file size,
JPEG size distribution, cue count, and the `sum(lengths) + index == file size`
invariant, exactly.

| | |
|---|---|
| BIF file | 9,497,976 B — hence ranged fetches, not an optimisation |
| Native frame spacing | **2 s** — much finer than the design wants |
| Scene interval | 10 s (every 5th frame), independent of BIF spacing |
| Subtitles | 476 cues, 32 KB, parses in 40 ms |
| Picked at 10 s | 148 frames |
| Dropped: near-blank / silent | 4 / 9 |
| **Scenes** | **135**, every one a distinct frame |
| **Cues per scene** | **3.50** — so ~3 advances in 4 are text-only |
| Eight scenes prefetched | 97 KB, in ~350 ms, against 9.5 MB for the whole file |

The cue density is the number the whole design rests on: a scene spanning several
cues is what keeps the fetching down. Confirmed on the face with real content —
two taps re-served the same 12,835 B frame and moved only the text, and the third
crossed to a new one:

```
advance -> scene 0 cue 1 (text only)    hit 12835 B frame 5
advance -> scene 0 cue 2 (text only)    hit 12835 B frame 5
advance -> scene 1 (new frame)          hit 17401 B frame 10
```

Every one a cache hit. Nothing fetches on the read path, by construction.

## Sign-in

The PIN flow, and a watch is the best place for it that this project has found.

The Pebble build deferred sign-in entirely and took a pasted `X-Plex-Token`,
because a config webview has to navigate away to app.plex.tv and come back, and
a backgrounded webview may stop polling. The G2 app implemented it but needed a
copy-this-URL-to-your-clipboard dance.

On a watch the awkwardness evaporates, because nobody expects to type here in the
first place: ask plex.tv for a PIN, show the **four-character code**, and let the
user enter it at plex.tv/link on any device. `strong=false` on the pin request is
what gets the short code rather than a long opaque one — the difference between
legible at a glance and useless.

**Routes are raced, not chosen.** plex.tv returns several connections per server
— LAN, public, relay — and which works depends on where the watch is. The G2 app
made that the user's problem with a Local/Remote/Relay dropdown. Here `/identity`
goes to all of them at once and the first good answer wins, preferring local, then
direct, then relay. Measured: local won in 89 ms, whole race 191 ms.

**And re-raced, because a watch moves.** Racing once was not enough, and the
failure was nasty: the LAN address wins at home because it genuinely is fastest,
then stops existing the moment you leave — and with nothing to fall back to,
every fetch fails quietly, the cache runs dry, and the face sits on "Loading…"
forever with no indication anything is wrong. All the candidate routes are now
stored alongside the chosen one, a failed fetch falls through them, and whichever
answers is promoted and remembered:

```
index failed via 10-99-99-99: Unable to resolve host … No address associated with hostname
route changed to 10-12-18-8 for index
episode ready: 135 scenes, 476 cues
```

The face also stops claiming to be loading when it is really unreachable — a
"Loading…" that never resolves is the worst thing it can say, because it looks
like patience will fix it.

**On Deck is the first thing offered**, because browsing by library does not
actually work on a watch. The test account's TV library is 357 shows — about
forty-four swipes to the middle of the alphabet, which is technically a browse
and practically useless. On Deck is a handful of items and is nearly always what
you want on your wrist. Full browsing stays for everything else.

**Eligibility is checked lazily and results stream in.** An item needs both an
`sd` index and a subtitle stream with a non-null `key`, which costs a metadata
request each — 23 ms measured, so a 157-episode show is close to four seconds.
Hits appear as they are found rather than after the whole show. The G2 app
fetched the entire library up front in batches of 20.

That `key` requirement means **sidecar** subtitles: streams embedded in an MKV
are not fetchable, and on the test library that is the difference between 50 and
157 usable Futurama episodes. Whether they can be reached was investigated
properly and the answer is no — see
[spikes/EMBEDDED-SUBTITLES.md](spikes/EMBEDDED-SUBTITLES.md). `/library/streams/`
returns **501** for an embedded stream by design, the transcoder burns them in
rather than offering a separate track, and parsing the MKV means 74 MB of
transfer per episode for 32 KB of text. The one working route — having Plex
download a third-party sidecar — was considered and declined: it writes to the
library, and swaps subtitles timed to that exact encode for ones whose sync is a
guess. `./gradlew :tools:runSubs` reports how much of a given show is usable.

## Things worth knowing before changing anything

**WFF fails silently.** The runtime reports no XML errors: a malformed face
renders wrong, or not at all, and says nothing. This is the exact counterpart of
the Pebble build's *"invalid font = white screen, nothing in the logs"*.
Validate with `xmllint --noout` first — it already caught one real bug, since XML
forbids `--` inside comments. The official XSD is vendored at
`spikes/wff-spec/v2/`, but note `xmllint` **cannot compile it** (it uses XSD 1.1
constructs); read it by hand or run it under Xerces.

**The round bezel is not a suggestion.** On a 450 circle the usable chord is 360
wide at y=360 but only 202 at y=426. The first subtitle band was 360 wide running
to y=426: it wrapped to three lines correctly and then ran straight off both
sides, with `ellipsis="TRUE"` doing nothing — it ellipsises on line count, not on
the clip boundary. Geometry here is measured, not chosen.

**`SUPPORTED_TYPES` in the manifest takes the legacy type names.** It is
`LARGE_IMAGE`, not `PHOTO_IMAGE` — the latter is only the AndroidX Kotlin name
for the same thing. Get it wrong and nothing complains anywhere; the service is
simply never bound and the slot stays empty.

**Ambient has no if/else.** WFF expresses display mode as interpolated
properties: every element carries an `alpha`, and a
`<Variant mode="AMBIENT" target="alpha" value="0"/>` child gives the value it
takes in ambient. `AMBIENT` is the only mode the schema allows. Anything that
is not an interpolatable property — font weight, say — needs **two elements
cross-faded on alpha**, which is why the clock is declared twice: bold at the
top for interactive, thin and centred for ambient. Inside `TimeText`, `Variant`
must come *before* `Font`; the schema is a sequence there.

Measured with `spikes/tools/luminance.py`, the panel's mean luminance drops from
19.9 (a dark frame) or 61.0 (a bright one) to **1.6**, and the subtitle band to
exactly zero.

To drive ambient on the emulator — there is no debug-surface operation for it:

```bash
adb shell input keyevent KEYCODE_BACK   # leave the launcher; get onto the face
adb shell input keyevent 223            # KEYCODE_SLEEP
```

Neither `KEYCODE_POWER` nor `KEYCODE_HOME` is the one — **HOME on the watch face
opens the app launcher**, and dozing from there screenshots a dimmed launcher
that looks exactly like a broken ambient variant. A brightness check will not
catch it either, since the launcher is bright; check the frame band specifically.

**The five-minute update limit is advisory.** The docs ask that
`requestUpdate` be called no more than once per five minutes on average. Measured
on the emulator, eight taps a second apart all delivered, each ~510 ms after the
tap, with nothing throttled. `MIN_DWELL_MS` is our restraint, not the system's —
the same status it had on Pebble. Untested on real hardware under battery saver.

**Android's regex engine is stricter than the JVM's.** `Regex("""\{[^}]*}""")`
passes `:core:test` and throws `PatternSyntaxException` on the watch — ICU
rejects the bare closing brace the JVM accepts, and the prefetch worker died in
a static initialiser with nothing on screen to explain it. JVM tests cannot catch
this; escape defensively and run it on a device.

**Filling a cache is not an event.** A clean install rendered an empty face
indefinitely with every part working: the complications were asked for data
before the prefetch had run, correctly returned nothing, and were never asked
again. Nothing logs an error in that state. The worker now broadcasts when it
lands scenes.

## The permanent "Loading…", and what it actually was

Worth writing down because the obvious explanation was wrong, and the real one
had two independent causes that produced identical symptoms.

**The guess was a dead LAN route.** It was not: the stored route was the
*public* address. The watch was sitting on the same LAN as the server, trying to
reach the server's own public IP, and the router would not hairpin:

```
scene 19 failed via 203-0-113-45: failed to connect to 203.0.113.45:32400
                                     from /10.12.18.225 after 10000ms
scene 19 failed on all 1 route(s)
```

So a route can fail *because you came home*, not because you left. Racing once
and keeping the winner is wrong in both directions; hence storing every route
and falling through them.

**And the cursor had run off the end of the cache.** The cache held scenes 0-15
and the cursor was at 19. Burst viewing consumes about eight steps a glance, so a
few glances in a row outrun the prefetch — and the catch-up walk treated "this
scene has no cues" and "this scene was never fetched" as the same thing, so it
marched the cursor forward into scenes that did not exist and could never come
back. The face then showed "Loading…" for ever with a cache full of perfectly
good earlier scenes.

That walk now lives in `Cursor.walk` in `:core` with tests, because it is the
second time it has been got wrong in exactly that way.

## Known issues

- **Only the stored routes are retried.** If every one of them fails — the
  server's public address changed, or the only working path is a relay that was
  not in the list — the app does not go back to plex.tv to re-discover. Re-pick
  the episode to refresh them.
- **An episode chosen before route storage existed has only one route.** Pick it
  again in the config app to capture the alternates.
- **Browsing a large library is tedious.** 357 shows is about forty-four swipes;
  On Deck sidesteps it for the common case but full browse has no index, search
  or jump-to-letter.

- **The episode is set over adb**, via `ConfigReceiver`. Phase 5 replaces that
  with Plex PIN sign-in on the watch, which is the one thing a watch is genuinely
  the best place for — a four-character code and no keyboard.
- **Everything is emulator-only.** No real-hardware power, cadence or burn-in
  measurements exist. Ambient is verified to *render* correctly; what it costs
  is unknown. Real devices may also shift the face periodically for burn-in
  protection, which is untested here.
- **The app APK is 27 MB** against the watch face's 13 KB — the complications
  tree plus Compose. `isMinifyEnabled` is off; nothing has been trimmed. The
  asymmetry is the architecture, not an accident: everything that thinks lives
  in one bundle and the thing on screen is pure XML.
- **The PIN exchange is unverified end to end.** Minting, displaying and polling
  are all confirmed against the live plex.tv API; nobody has actually entered a
  code at plex.tv/link, which needs a human.
