# Phase 0 findings — Wear OS

Measured on the Wear OS 6.1 emulator (`system-images;android-36.1;android-wear-signed;arm64-v8a`,
large round, 454x454 @ 320dpi), AGP 8.13.0 / Gradle 8.14 / JDK 21.

Screenshots taken while working through these findings are **not kept in the
repo** — they are frames of a TV episode, and development artefacts rather than
anything needed to build. Following the same policy as `trickplayer-pebble`.
Where one settled a question, the finding says what it showed.

## The headline: the pipeline that dominated the Pebble build does not exist here

Pebble's central decision was **never send JPEG**. The phone decoded, median-cut
and Floyd–Steinberg dithered every frame to 4-bit indices, because 11,200 B beat
8–15 KB of JPEG over BLE to a device with no decoder and a 122 KB heap.

Every term flips on Wear OS. `Icon.createWithData(jpegBytes)` puts the BIF JPEG
across a Binder call untouched, and the WFF runtime decodes it in hardware.
Deleted outright: `jpeg.js` (340 lines), `render.js` (199), `proto.js` (136),
the base64 localStorage cache (208), the XS heap tuning in `mdbl.c`, strip
expansion, and `RING_SIZE = 2`. Roughly 900 lines of the Pebble build were
answers to constraints this platform does not have.

What survived is in `:core`, and it is the part that was never about Pebble.

---

## W1 — `Icon.createWithData(jpeg)` in a WFF `PHOTO_IMAGE` slot: **RESOLVED, YES**

`PhotoImageComplicationData.Builder(Icon.createWithData(jpeg, 0, size), …)`
renders correctly in a Watch Face Format `PHOTO_IMAGE` slot. A 5,934 B
synthetic JPEG made the round trip with no conversion anywhere.

The source string is `[COMPLICATION.PHOTO_IMAGE]`, confirmed against Google's
own `WatchFaceFormat/Complications` sample. It is **not** in the published XSD —
`PHOTO_IMAGE` is enumerated as a slot *type*, but the `Image resource` source
strings are free-form `xs:string` and resolved at runtime, so the schema cannot
tell you this and the reference does not list it.

`createWithBitmap` was rejected without testing: a 450x450 ARGB_8888 is ~810 KB
against a ~1 MB Binder limit.

## W2 — tap on a `PHOTO_IMAGE` slot: **RESOLVED, YES**

`setTapAction(pendingIntent)` on the complication data fires on a tap anywhere
in the slot, reaching a plain `BroadcastReceiver`. The WFF `ComplicationSlot`
reference documents no tap attribute, which made this look doubtful; the tap is
handled by the complication system, not the face, so the face needs nothing.

The measured cycle is exactly the design:

```
BifAdvance: advanced -> scene=0 cue=1
BifFrame:   scene 0 -> 5934 B jpeg     <-- same scene, same bytes
BifAdvance: advanced -> scene=0 cue=2
BifFrame:   scene 0 -> 5934 B jpeg     <-- still the same
BifAdvance: advanced -> scene=1 cue=0
BifFrame:   scene 1 -> 5589 B jpeg     <-- new frame, finally
```

Three text-only advances, then one that costs an image. That is the 3.17
cues-per-scene ratio the whole design rests on, running for real.

### The trigger Pebble wanted, and could not have

`triggers.js` named the ideal Pebble trigger and gave up on it: *"backlight —
fires exactly when someone looks, but Alloy exposes no backlight event."* It
settled for touch plus accel-tap.

Wear OS asks a data source for data when the face becomes active. That is
approximately the backlight trigger, for free, alongside tap. Both funnel into
one `advance()`, as before.

## W3 — update cadence: **RESOLVED, and looser than documented**

The docs ask that `ComplicationDataSourceUpdateRequester.requestUpdate` be called
no more than **once per five minutes on average**. Measured, that is advisory
rather than enforced:

| | |
|---|---|
| Taps issued | 8, one second apart |
| Updates delivered | **8 of 8** |
| Tap -> data-source callback | **~510 ms**, consistently |
| Throttling observed | none |

So `MIN_DWELL_MS` is our restraint, not the system's — the same status it had on
Pebble. Do not assume this holds on real hardware under battery saver.

## W4 — `LONG_TEXT` wrapping: **RESOLVED, and it caught a layout bug**

`<Text maxLines="3" ellipsis="TRUE">` does wrap a real subtitle to three lines.
But the first layout put a **360-wide band running to y=426**, and on a 450
circle that does not fit:

| y | usable chord width |
|---|---|
| 315 | 412 |
| 360 | 360 |
| 391 | 304 |
| 426 | **202** |

The text wrapped correctly and then ran straight off both sides of the bezel,
with `ellipsis="TRUE"` doing nothing to stop it — it ellipsises on line count,
not on the clip boundary.

Fixed by measuring rather than guessing: the band now ends at y=391 and is 290
wide. Ellipsis then works as intended.

**Final layout, 450x450 design canvas:**

```
clock     y  26 ..  90    digital, centred
frame     y  82 .. 307    400x225, 16:9, corners cropped by the bezel
subtitle  y 315 .. 391    290 wide, 3 lines at 17px
```

The runtime scales the 450 canvas to the panel and says so:
`WatchFaceViewer: project 450.0 x 450.0, canvas 454 x 454 … scale 1.008889`.

## W5 — sideloading and selecting a WFF face: **RESOLVED, but nothing about it is guessable**

This cost the most time of anything in Phase 0, and every wrong turn failed
silently or misleadingly.

**1. A WFF package declares NO service.** The obvious move — declaring
`androidx.wear.watchface.control.WatchFaceControlService`, as an AndroidX watch
face would — builds and installs perfectly happily and then produces:

```
set-watchface failed. FavoriteOperationException: Watch face package is not installed.
```

The package *is* installed. It simply was never recognised as a watch face.
Discovery is by the `com.google.wear.watchface.format.version` property plus
`res/raw/watchface.xml`, and nothing else.

**2. `res/xml/watch_face_info.xml` must be minimal.** `<Preview>` is the only
required element. Invented ones (a `Category` value copied out of a doc excerpt)
break registration with the same non-message.

**3. The selection command takes `--es watchFaceId`, not `--ecn component`.**
Every component form fails with the same "not installed" error, including the
runtime's own `RuntimeControlService` and `DeclarativeWatchFaceRuntime0/1`:

```bash
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
  --es operation set-watchface --es watchFaceId com.lukemeyer.bif.watchface
```

Success looks like `result=1, data="Favorite Id=[4] Runtime=[2]"`.

**How this was settled:** by swapping Google's own `SimpleDigital` sample content
into this module. It failed identically, which proved the package was fine and
the *command* was wrong. Worth remembering as a technique — the error message
pointed squarely at the package and was pointing the wrong way.

**4. Binding a complication needs four extras, and the names are not obvious.**
Each wrong guess only reveals the next missing parameter, so it is a four-round
game of twenty questions:

```bash
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
  --es operation set-complication \
  --ecn component com.lukemeyer.bif.app/com.lukemeyer.bif.app.FrameComplicationService \
  --es watchFaceId com.lukemeyer.bif.watchface \
  --ei slot 11 --ei type 8
```

* `--ecn component` is the **data source**, and `--es watchFaceId` the face.
* `--ei type` is an **int**, and the values are the legacy ones:
  **`LONG_TEXT = 4`, `PHOTO_IMAGE (LARGE_IMAGE) = 8`.** A string type is rejected.
* `--ei slot` takes the **runtime's** slot ids, not the `slotId` from the XML.
  Slots `1` and `2` in `watchface.xml` are **11 and 12** at runtime. Read them
  from logcat — the runtime prints `WearComplicationProvider: [11:NO_DATA]` for
  each slot as it loads. Anything else gives
  `IndexOutOfBoundsException: Complication slot is out of range`.

## W6 — is `immediateResponseRequired` set? **Observed false**

Every request logged so far reports `immediate=false`, i.e. the 20 s deadline
rather than the 100 ms one:

```
BifFrame: request slot=5 type=PHOTO_IMAGE immediate=false
```

Do not design for it. The 100 ms path exists and a cache-only
`onComplicationRequest` is required regardless — the point of the split is that
fetching happens in a worker either way. But 20 s is what was seen, so a
first-run cache miss has more room than feared.

---

## The trap that has no logs

WFF is declarative and the runtime does not report XML errors: a malformed face
renders wrong, or not at all, in silence. This is the exact counterpart of the
Pebble build's *"invalid font = white screen, nothing in the logs"*, and it will
cost the same hours if not defended against.

Two defences, in order:

1. **`xmllint --noout`** for well-formedness. This alone caught a real bug: XML
   forbids `--` inside comments, and `<!-- ----- clock ----- -->` is invalid.
2. **The official XSD**, vendored at `spikes/wff-spec/v2/` from
   `github.com/google/watchface`. Note that `xmllint` **cannot compile it** —
   it uses `xs:all` with `maxOccurs > 1`, which is XSD 1.1 and beyond libxml2:

   ```
   abstractPartType.xsd:40: Invalid value for maxOccurs (must be 0 or 1)
   ```

   Reading it by hand is still the fastest way to settle a question. Running it
   properly needs Xerces (jars are in the same upstream repo).

The runtime's own log is the third defence and is more useful than expected:

```bash
adb logcat --pid=$(adb shell pidof -s com.google.wear.watchface.runtime)
```

`DWF:WatchFaceViewer` prints the canvas projection, and
`DWF:WearComplicationProvider` prints every slot with its runtime id and current
data — which is where the slot ids in W5 came from.

## Toolchain notes earned the hard way

* **Nothing was installed on this machine** — no JDK, no SDK, no Studio, no
  Gradle, only a homebrew `adb`.
* **`brew install --cask temurin` needs sudo** and cannot run unattended (it is
  a `.pkg` into `/Library`). The keg-only **`brew install openjdk@21`** formula
  is the same JDK with no password; point `JAVA_HOME` at
  `/opt/homebrew/opt/openjdk@21`. See `../env.sh`.
* **`avdmanager create avd` prints a `devices.xml` error and works anyway.**
  `Could not load devices from …/system-images/…/devices.xml` is noise; the AVD
  is created with the right profile.
* **Gradle plugin versions must be pinned in the root `build.gradle.kts`** with
  `apply false`, or modules using `alias(...)` fail with *"already on the
  classpath with an unknown version"*.
* **`android.useAndroidX=true`** is not defaulted; without it the watchface
  complication dependency fails `checkDebugAarMetadata`.
* Set `compileOptions` to 17 and `kotlinOptions.jvmTarget` to 17 together — a
  `jvmToolchain(21)` on an Android module mismatches `compileDebugJavaWithJavac`.

## The Kotlin port, against the real server

`./gradlew :tools:run` runs the shipping `:core` code against the live Plex
server with no device involved. Against the same test episode the Pebble build
used (Futurama S8E1), the port reproduces every measured number:

| | Pebble (JS) | Wear (Kotlin) | |
|---|---|---|---|
| Frames | 736 | **736** | ✓ |
| Native spacing | 2,000 ms | **2,000 ms** | ✓ |
| Index size | 5,960 B | **5,960 B** | ✓ |
| File size | 9,497,976 B | **9,497,976 B** | ✓ |
| JPEG min / mean / max | 580 / 12,896 / 21,670 | **580 / 12,896 / 21,670** | ✓ |
| Subtitle cues | 476 | **476** | ✓ |
| `timestampMultiplier` field | 0 -> 1000 ms | **0 -> 1000 ms** | ✓ |
| `sum(lengths) + index == file size` | holds | **holds exactly** | ✓ |

Two numbers differ, and the new ones are the better-founded:

| | Pebble | Wear | |
|---|---|---|---|
| Scenes at 10 s | 150 | **148** | 736 frames / 5 = 147.2 -> 148. Derived from picked frames rather than from duration. |
| Cues per scene | 3.17 | **3.22** | Follows from the scene count. |
| Silent scenes | 14 | **12** | Same. |

Fetching five scenes end to end costs **54,617 B** against the 9,497,976 B whole
file — which is the ranged-fetch argument in one line.

### A design bug the real data exposed, and the fix

Frame 0 is a 580 B all-black JPEG, so scene 0 skipped forward — and landed on the
frame scene 1 wanted. Avoiding just the previous scene's frame looked like the
fix, and was not: with several skips in a row the collision is with a scene two
or more back.

```
scene 4 -> frame 30 (skipped 2)
scene 5 -> frame 35 (skipped 2)
scene 6 -> frame 30      <-- scene 4 already showed this
scene 7 -> frame 35      <-- and scene 5 showed this
```

Both earlier versions of this project skip forward at read time and so have the
same bug; Pebble hid it behind a 2-slot ring and per-scene caching.

**Fixed by filtering once, up front.** `Episode.scenes` is the picked list with
blanks and silent windows already removed, so scene N is a list lookup and every
scene has a distinct frame by construction. `SceneResolver` shrank to four lines.
Measured on the test episode:

| | |
|---|---|
| Picked at 10 s | 148 |
| Near-blank dropped | 4 |
| Silent dropped | 9 |
| **Scenes kept** | **135** |
| **Distinct frames** | **135 of 135** |
| Cues per scene | **3.50** (up from 3.22 — silent windows no longer drag the average down) |

The filter costs no network: both tests are answerable from the parsed index and
the cue list.

### Two bugs that only a device could find

**Android's regex engine is stricter than the JVM's.** `Regex("""\{[^}]*}""")`
compiles fine under `./gradlew :core:test` and throws
`PatternSyntaxException: Syntax error in regexp pattern near index 8` the moment
it runs on the watch — Android's ICU implementation rejects the unescaped closing
brace that the JVM accepts. The whole prefetch worker died in
`Srt.<clinit>` with nothing on screen to say why. **JVM unit tests cannot catch
this class of difference.** Escape every metacharacter, and run it on a device.

**Kotlin initialises properties in declaration order.** `Episode.scenes` filters
on `blankThresholdBytes`; declared above it, the threshold is still 0 and nothing
is ever judged blank. Completely silent. A unit test caught this one.

### Filling the cache is not an event the system knows about

A clean install rendered an empty face and stayed empty, with every part working
correctly: the complications were asked for data while the cache was still empty,
correctly returned nothing, and **were never asked again**. The prefetch worker
then filled the cache perfectly well, and nobody was looking.

Nothing logs an error in this state, which is what makes it worth writing down.
The worker now broadcasts when it lands scenes, and a receiver in `:app` calls
`requestUpdateAll`. A broadcast rather than a direct call, so `:data` stays
ignorant of complications and could feed a Tile just as well.

Cold start, from `adb uninstall` to a populated face with nothing touched in
between:

```
BifPrefetch: prefetched 8 scene(s); cache holds 8 (101 KB)
BifReady:    scenes ready; refreshing complications
BifFrame:    request immediate=false scene=0 hit 12835 B frame 5
```

`:core` also asserts the structural invariants as unit tests, so a regression in
the parser fails `./gradlew :core:test` rather than the watch face.

## W7 — ambient mode: **RESOLVED**

`PHOTO_IMAGE` is explicitly not recommended in ambient, and a photograph is
close to the worst case for OLED burn-in besides being most of the panel lit
continuously. In ambient this face is a clock and nothing else.

WFF has no if/else for display mode. Every element carries an `alpha`, and a
`<Variant mode="AMBIENT" target="alpha" value="…"/>` child gives the value that
alpha takes in ambient; the runtime interpolates. `AMBIENT` is the **only** mode
the schema allows, and `Variant` is accepted on `ComplicationSlot`, `Group`,
`Scene`, the clocks, and any part.

Anything not expressible as an interpolated property needs **two elements
cross-faded on alpha** — font weight, for instance. Hence two `DigitalClock`s
here: a bold 52 px one at the top for interactive, and a light 88 px one centred
for ambient, each fading the other out. That is the pattern Google's
`SimpleDigital` sample uses, and it is not obvious from the reference.

Order matters inside `TimeText`: the schema declares an `xs:sequence`, so
`<Variant>` must come **before** `<Font>`. `ComplicationSlot` is `xs:all`, so
there it does not.

### Driving ambient on the emulator

Not obvious, and there is no debug-surface operation for it
(`set-ambient`, `enter-ambient` and friends are all "Unrecognized operation").

```bash
adb shell input keyevent KEYCODE_HOME    # must be ON the watch face first
adb shell input keyevent 223             # KEYCODE_SLEEP
adb shell dumpsys power | grep mWakefulness   # -> Dozing
adb exec-out screencap -p > /tmp/ambient.png
adb shell input keyevent KEYCODE_WAKEUP
```

`KEYCODE_POWER` is **not** the one — it opens the app launcher, and dozing from
there screenshots the launcher rather than the face, which looks exactly like a
broken ambient variant. `KEYCODE_SLEEP` is 223.

`KEYCODE_POWER` is not the one, and neither is `KEYCODE_HOME`: **HOME pressed
while the watch face is showing opens the app launcher.** Doze from there and
the screenshot is a dimmed launcher, which is easy to mistake for a broken
ambient variant — it cost two rounds of confusion here. `KEYCODE_BACK` is the
reliable way back to the face.

Worse, a brightness check does not catch the mistake: the launcher's white
circles are bright, so "the screen has content" is true either way. Check that
the *frame band specifically* goes dark.

### Measured

`spikes/tools/luminance.py` reports mean luminance for the whole panel and per
band (no PIL on this machine, so it decodes the PNG itself):

| | frame band | subtitle band | whole panel |
|---|---|---|---|
| Interactive, dark frame (Earth in space) | 36.1 | 10.7 | 19.9 |
| Interactive, bright frame (city daylight) | 110.0 | 71.2 | 61.0 |
| **Ambient** | **3.3** | **0.0** | **1.6** |

So the panel drops to between **3% and 8% of its interactive mean**, depending
entirely on how bright the frame happened to be. The subtitle band goes to
**exactly zero**. The residual 3.3 in the frame band is the ambient clock, which
sits at y=165–285 and therefore overlaps it — not leakage from the picture.

Frame and subtitle vanish completely and the full face returns on wake —
verified by screenshotting interactive, ambient, and back-from-ambient in turn.

### The glance trigger — this section was wrong, and here is the correction

It originally read "the glance trigger, confirmed", on the strength of this,
which appeared unprompted after waking from ambient:

```
BifFrame: request immediate=false scene=1 hit 17401 B frame 10
```

Two things were wrong with the conclusion.

**First, nothing acted on it.** `onComplicationRequest` served the current scene
and returned; it never called `advance`. So even when the request arrived, the
face did not move. On hardware the symptom was exactly that: cues advanced on tap
and never on wake. Observing a signal is not the same as wiring it up, and this
document claimed the second on the evidence of the first.

**Second, the signal is not reliable.** Repeating the measurement later, waking
the face produced **no request at all**. With
`UPDATE_PERIOD_SECONDS = 0` — "never poll" — the system has no obligation to ask,
and mostly does not. The Phase 0 observation was a system-initiated refresh that
happened to land, and it was generalised into a rule on a single sighting.

**What actually works** is `UPDATE_PERIOD_SECONDS`, which asks the system to call
the data source at roughly that interval *while the watch face is active* — which
is to say, while someone is looking at it. That is the glance trigger, and it has
to be requested. 300 s is the practical floor; lower values are clamped.

Measured with the screen held awake and nothing touched:

```
BifFrame: request immediate=false scene=7 hit 14747 B frame 60
BifState: advance -> scene 7 cue 1 (text only)
BifFrame: request immediate=false scene=7 hit 14747 B frame 60
BifState: advance throttled (glance within dwell)
```

The second request there is the one our own refresh caused. **The dwell gate is
load-bearing**: advancing asks the system to redraw, redrawing asks the data
source for data, and that is itself a glance-shaped event. Without the gate it
cycles forever.

## Phase 5 — sign-in and browsing, measured

Exercised headlessly first via `./gradlew :tools:runDiscover`, before any UI
existed — the same discipline `Pipeline.kt` applies to the content path.

| | |
|---|---|
| PIN code | **4 characters** with `strong=false`; a strong PIN returns a long opaque string, useless on a 450 px screen |
| Servers found | 2 |
| Route race | local won in **89 ms**; whole race **191 ms** |
| Libraries | 7 |
| Shows in the TV library | **357** |
| Eligibility check | **23 ms** each |
| Futurama | **50 usable of 157** — exactly the figure the Pebble build measured |

That last row is the useful one: the eligibility rule ported faithfully. An item
needs an `sd` index **and** a subtitle stream with a non-null `key`, because most
SRT streams Plex reports are embedded and cannot be fetched separately.

### 357 shows is not a browsable list

The library browse worked and was useless: reaching the middle of the alphabet is
roughly forty-four swipes. Plex's `/library/onDeck` — what the account is
part-way through — is a handful of items and is nearly always the answer to
"which episode do you want on your wrist". It is now offered first, with full
browsing kept for everything else.

This is the sort of thing that only shows up on the device. On a desktop, 357
rows in a scrolling list is unremarkable.

## W8 — driving the face without a trigger: timelines

`UPDATE_PERIOD_SECONDS` and the hoped-for wake event both turned out to be dead
ends (W3, and the correction above). Measured on the emulator:

| Requested poll | Polls seen |
|---|---|
| 5 s | **0** in 330 s |
| 60 s | **0** in 260 s |
| 300 s | 1, once — on a single sighting, so not to be trusted |

And **0 complication requests across 5 doze/wake cycles**. Waking the watch does
not ask the data source for anything.

So stop waiting to be asked. A data source can return a
**`ComplicationDataTimeline`** — a list of `TimelineEntry`, each with a
`TimeInterval` of validity — and the face steps through it by itself, calling
nobody. Verified: one push, then the face advanced on its own with zero further
requests.

### Granularity is the face's redraw rate

A timeline is only re-evaluated when the face redraws, and a face showing `hh:mm`
redraws **once a minute**. Entries eight seconds apart stepped exactly once, on
the minute boundary, and then sat unchanged for the next 27 s. Showing seconds
fixes that: the face redraws every second and short entries play properly.

### The ambient scare, and why it was wrong

The emulator appears to punish seconds by abandoning the AMBIENT variant. Mean
luminance of the frame band while "dozing":

| Face | Frame band, emulator |
|---|---|
| `hh:mm` | 3.8 |
| `hh:mm:ss` | 44.6 |
| `hh:mm` + a text part reading `[SECOND]` | 30.5 |

On that evidence this project switched to one cue per minute and gave up burst
playback. **That was wrong**, and on real hardware it is simply not true: a
Pixel Watch 3 left alone settles into the proper ambient variant — minute-only
clock, no picture — with `hh:mm:ss` in the interactive one.

Two lessons, both earned the hard way:

1. **The emulator is not the device.** Ambient handling in particular differs.
2. **The measurement interfered with the thing measured.** Screenshotting over
   adb means interacting with the watch, and interacting with it is exactly what
   keeps it out of ambient. Every "ambient" capture on hardware was really the
   interactive face, because taking the picture prevented the state it was
   supposed to record. An ambient measurement is only valid if nothing has
   touched the device.

So there is no trade: seconds in the interactive variant, minutes in ambient,
burst playback, and a dark ambient face are all available at once.

### What it looks like working

The burst is sized to the **screen timeout**, not to how far ahead we could plan.
The watch sleeps after 15 s, so a 30-minute timeline would be 99% waste — built,
pushed across a Binder, and never rendered. Matching the two also keeps the
catch-up arithmetic honest, since "the burst finished" and "the screen was on
throughout" then mean the same thing.

On hardware, one tap and 8 entries of 2 s:

```
BifTimeline: burst of 8 steps x 2000ms from scene 56 cue 3 (through scene 59 cue 1)
03:42:01  "The Pope is about to make his decis..."
03:42:14  "Yeah, come on."          <- new frame, new dialogue
```

A glance gets a run of the show; the next glance picks up from wherever it
actually got to.

### Two things that had to be got right

**Both complications must derive from one function.** They are separate data
sources answering separate requests; if each worked out its own position they
would drift, and you would get one scene's picture under the next scene's
dialogue. `SceneTimeline.build()` is the single source, and both services log
identical bursts.

**The cursor must catch up with what actually played.** The face plays a burst
alone and nothing reports back how much was seen — the wrist may have dropped
after two seconds. Recording when the burst was pushed lets the next request work
out the elapsed steps from the wall clock. Without it, a tap advances from a
position several steps stale, which on screen looks like the face jumping
*backwards* over what it just showed.

## Two ways to make a permanent "Loading…"

Both found on hardware, both invisible on the emulator, and they produce the
same symptom from opposite directions.

**A route can die because you came home.** The stored route was the server's
public address, the watch was on the same LAN as the server, and the router
would not hairpin — so the connection failed *from inside the network*:

```
failed to connect to 203.0.113.45:32400 from /10.12.18.225 after 10000ms
```

Racing routes once and keeping the winner is wrong whichever way you move.
Every candidate is now stored and a failed fetch falls through them.

**The cursor can outrun the cache and never return.** Cache held scenes 0-15,
cursor was at 19. The catch-up walk read "this scene has no cues" and "this scene
was never fetched" as the same condition, so an uncached scene simply advanced
the cursor — off the end, permanently. `Cursor.walk` in `:core` now distinguishes
them and has tests, this being the second bug of that exact shape.

Neither could have been found on the emulator: one needs a real router, the other
needs someone glancing at the watch faster than it can fetch.

## Still open

* **Real hardware.** Everything above is the emulator. Update cadence under
  battery saver, actual ambient power draw and burn-in mitigation (real devices
  may shift the face periodically) are unmeasured.
* **Low-bit ambient.** White text on black should be safe on a low-bit panel,
  but it has not been tested on one.
* **The PIN -> token exchange.** Minting the PIN, displaying the code and polling
  are all verified against the live plex.tv API; the code has never actually been
  entered at plex.tv/link, because that needs a human. Everything downstream was
  verified by seeding a token directly.
* **Whether the runtime slot ids are stable across *devices*.** They survive a
  clean uninstall/reinstall here — the favourite id moved 4 -> 5 while the slots
  stayed 11 and 12 — but that is one emulator, one face. `run.sh` reads them
  from logcat rather than hardcoding them, which costs nothing and cannot be
  wrong.
