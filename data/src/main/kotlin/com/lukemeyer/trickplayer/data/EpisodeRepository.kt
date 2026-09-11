package com.lukemeyer.trickplayer.data

import android.content.Context
import android.util.Log
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient
import com.lukemeyer.trickplayer.core.plex.PlexClient
import com.lukemeyer.trickplayer.core.scene.CueLayout
import com.lukemeyer.trickplayer.core.scene.Episode
import com.lukemeyer.trickplayer.core.scene.SceneResolver
import com.lukemeyer.trickplayer.core.source.JellyfinSource
import com.lukemeyer.trickplayer.core.source.MediaSource
import com.lukemeyer.trickplayer.core.source.Playable
import com.lukemeyer.trickplayer.core.source.PlexSource
import com.lukemeyer.trickplayer.core.subs.Srt
import java.io.File

/**
 * Builds scenes, and is the only thing here allowed to touch the network.
 *
 * The split it enforces is the whole architecture. A complication data source
 * may be given 100 ms to answer, so it may only read [SceneCache]; everything
 * expensive — ranged fetches, parsing, resolving — happens here, off the
 * callback, driven by [ScenePrefetchWorker].
 *
 * That is the Pebble phone/watch split reborn. There the watch could not fetch
 * because it had no radio budget and no decoder; here the callback cannot fetch
 * because it has 100 ms. Different reason, identical consequence: by the time
 * anyone asks for a scene, the work is already done.
 */
class EpisodeRepository private constructor(
    private val context: Context,
    private val config: Settings.Episode,
) {

    val cache = SceneCache(context, config.profile)

    /** Routes to try, the one that last worked first. */
    private val routes: List<String> =
        (listOf(config.server) + config.routes).distinct()

    @Volatile private var active: String = config.server

    /**
     * This episode as the seam sees it, and the provider that can serve it.
     *
     * Route-independent: a source is built **per route** inside [viaAnyRoute],
     * because failover is a device concern and not a source one. Which provider
     * gets built is the only place in this module that names one — everything
     * below asks the interface.
     */
    private val playable: Playable = when (config.provider) {
        Settings.Provider.PLEX -> Playable(
            title = config.title,
            durationMs = null,
            timelineRef = PlexSource.PlexTimelineRef(config.timelineRef),
            subtitleRef = if (config.subtitleRef.isEmpty()) null
                else PlexSource.PlexSubtitleRef(config.subtitleRef),
        )

        Settings.Provider.JELLYFIN -> {
            val t = config.trickplay ?: error("Jellyfin config without trickplay geometry")
            Playable(
                title = config.title,
                durationMs = null,
                timelineRef = JellyfinSource.JellyfinTimelineRef(
                    itemId = t.itemId,
                    width = t.width,
                    geometry = JellyfinSource.Geometry(
                        tileWidth = t.tileWidth,
                        tileHeight = t.tileHeight,
                        thumbWidth = t.thumbWidth,
                        thumbHeight = t.thumbHeight,
                        intervalMs = t.intervalMs,
                        thumbnailCount = t.thumbnailCount,
                    ),
                ),
                subtitleRef = t.subtitleIndex.takeIf { it >= 0 }?.let {
                    JellyfinSource.JellyfinSubtitleRef(t.itemId, t.mediaSourceId, it)
                },
            )
        }
    }

    private fun sourceFor(uri: String): MediaSource = when (config.provider) {
        Settings.Provider.PLEX ->
            PlexSource(PlexClient(config.token, allowInsecureDirect = true), uri)

        // A tile-sheet source keeps its own cache, so building one per route
        // per call would throw the sheet away between scenes. One instance,
        // reused — there is only ever one route on Jellyfin anyway.
        Settings.Provider.JELLYFIN -> jellyfin ?: JellyfinSource(
            JellyfinClient(uri, config.token),
            AndroidSheetCropper(),
        ).also { jellyfin = it }
    }

    @Volatile private var jellyfin: JellyfinSource? = null

    /**
     * Run a fetch, falling back through the other routes when the current one
     * is unreachable, and remembering whichever works.
     *
     * This is what makes the face survive walking out of the house. The route
     * is raced once when the episode is picked and the LAN address wins, since
     * it is genuinely the fastest — and then stops existing the moment the watch
     * is off that network. Retrying the same dead address forever is exactly
     * what a permanently stuck "Loading…" looks like from the outside.
     */
    private fun <T> viaAnyRoute(what: String, block: (String) -> T): T? {
        var lastMessage: String? = null
        for (uri in routes.sortedByDescending { it == active }) {
            try {
                val result = block(uri)
                if (uri != active) {
                    Log.i(TAG, "route changed to ${hostOf(uri)} for $what")
                    active = uri
                    Settings(context).setActiveRoute(uri)
                }
                Settings(context).lastError = null
                return result
            } catch (e: Exception) {
                lastMessage = e.message
                Log.w(TAG, "$what failed via ${hostOf(uri)}: ${e.message}")
            }
        }
        Settings(context).lastError = lastMessage ?: "unreachable"
        Log.w(TAG, "$what failed on all ${routes.size} route(s)")
        return null
    }

    /** Host only — the rest of a plex.direct URL is noise in a log. */
    private fun hostOf(uri: String) =
        uri.substringAfter("//").substringBefore(':').substringBefore('.')

    /** Parsed once per process; the on-disk copies below survive a cold start. */
    @Volatile private var episode: Episode? = null

    private val subsFile = File(context.cacheDir, "episode/${config.profile}.srt")

    /**
     * Fetch the timeline and the cues, once per process.
     *
     * **The timeline is no longer cached to disk, and the subtitles still are.**
     * That asymmetry is the seam showing through honestly rather than a
     * regression: a timeline is provider-shaped — Plex's is byte offsets,
     * Jellyfin's is geometry with no bytes at all — so nothing here can
     * serialise one without unwrapping a locator it is not allowed to read.
     * Cues are just text and stay cached.
     *
     * The cost of dropping it is two ranged reads of about 6 KB on Plex, and
     * literally nothing on Jellyfin, where the timeline is computed from the
     * manifest that came with the item. It is also only paid on a [SceneCache]
     * miss, which is a fetch either way.
     */
    @Synchronized
    fun episode(): Episode {
        episode?.let { return it }
        subsFile.parentFile?.mkdirs()

        val frames = viaAnyRoute("timeline") { uri -> sourceFor(uri).timeline(playable) }
            ?: throw java.io.IOException("no route to the ${config.provider} server")

        // Cached to disk as SRT — the format the parser already reads, rather
        // than a serialisation invented for the cache. Worth keeping on
        // Jellyfin especially, where fetching cues makes the server convert an
        // embedded track on demand rather than serve a file (F-037).
        val cues = subsFile.takeIf { it.exists() }
            ?.let { runCatching { Srt.parse(it.readText()) }.getOrNull() }
            ?: viaAnyRoute("subtitles") { uri -> sourceFor(uri).cues(playable) }
                ?.also { runCatching { subsFile.writeText(Srt.format(it)) } }
            ?: emptyList()

        // A source with no per-frame byte lengths gets neither blank filtering
        // nor duplicate detection — skipped, not faked. Decided once here from
        // the provider's own capabilities rather than by each filter noticing a
        // null and deciding for itself (SEAM.md §4).
        val caps = sourceFor(active).capabilities()

        val ep = Episode(
            index = frames,
            cues = cues,
            durationMs = frames.lastOrNull()?.tsMs ?: 0L,
            skipSilent = config.skipSilent,
            hasFrameSizeHints = caps.hasFrameSizeHints,
        )
        Log.i(TAG, "episode ready: ${ep.sceneCount} scenes of ${frames.size} frames " +
            "(${ep.duplicateFlags.count { it }} duplicate), ${cues.size} cues, " +
            "${config.provider}")
        episode = ep
        return ep
    }

    /**
     * Resolve and fetch one scene into the cache. No-op if already cached.
     *
     * @return the cached entry, or null if the scene could not be built.
     */
    fun build(sceneIndex: Int): SceneCache.Entry? {
        cache.get(sceneIndex)?.let { return it }

        val ep = try { episode() } catch (e: Exception) {
            Log.w(TAG, "index unavailable: ${e.message}"); return null
        }

        // Blank and silent frames were filtered out when the Episode was built,
        // so this is a list lookup and every scene has a distinct frame.
        val r = SceneResolver.resolve(ep, sceneIndex) ?: return null
        val ent = ep.index[r.frameIndex]

        // The provider reads its own locator; this layer never does. On Plex
        // that is a byte range, on a tile-sheet source a sheet and a grid cell,
        // and the difference stays behind the seam (SEAM.md §5).
        val jpeg = viaAnyRoute("scene $sceneIndex") { uri ->
            sourceFor(uri).frameBytes(playable, ent)
        } ?: return null

        // A cue too long for the face becomes several cues, one per page, and
        // the cursor then pages through them exactly as it steps through cues —
        // no fetch, no new concept, and nothing is truncated (F-002).
        //
        // Measured on this screen: the 320-wide band fits 18 characters and
        // four lines. Against a real film's 1,581 cues that leaves 5.2% of them
        // needing a second page; at the old 280 width it was 13.3%.
        val cues = ep.cuesFor(r.scene)
            .flatMap { CueLayout.paddedPagesOf(it, WRAP_CHARS, LINE_CHARS, LINES_PER_PAGE) }
            .ifEmpty { listOf(fmtTime(ent.tsMs)) }
        Log.i(TAG, "scene $sceneIndex -> frame ${r.frameIndex} @${ent.tsMs / 1000}s, " +
            "${jpeg.size} B, ${cues.size} cues")

        // Store the resolved scene so a replay repeats none of this work.
        return SceneCache.Entry(sceneIndex, r.frameIndex, ent.tsMs, cues, jpeg)
            .also { cache.put(it) }
    }

    fun sceneCount(): Int = try { episode().sceneCount } catch (e: Exception) { 0 }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val TAG = "TpRepo"

        /**
         * What the watch face can actually show, measured on a Pixel Watch 3
         * with a character ruler — not calculated. `watchface.xml` carries the
         * geometry these come from; change them together or the pages will not
         * match the band.
         *
         * These are deliberately NOT in `:core`: they are a measurement of one
         * screen, and F-002 is the algorithm, not the numbers.
         */
        /**
         * The band is 20 Lekton characters wide at 32px. Text is wrapped to 19
         * and padded to 20, so every line ends in at least one space — see
         * [CueLayout.paddedPagesOf] for why that is not optional.
         */
        private const val LINE_CHARS = 20
        private const val WRAP_CHARS = LINE_CHARS - 1
        private const val LINES_PER_PAGE = 4

        /** Null when no episode has been configured yet. */
        fun open(context: Context): EpisodeRepository? {
            val cfg = Settings(context).episode ?: return null
            return EpisodeRepository(context.applicationContext, cfg)
        }
    }
}
