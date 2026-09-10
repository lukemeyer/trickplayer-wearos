package com.lukemeyer.bif.data

import android.content.Context
import android.util.Log
import com.lukemeyer.bif.core.timeline.Timeline
import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.scene.Episode
import com.lukemeyer.bif.core.scene.SceneResolver
import com.lukemeyer.bif.core.subs.Srt
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

    private val plex = PlexClient(config.token, allowInsecureDirect = true)
    val cache = SceneCache(context, config.profile)

    /** Routes to try, the one that last worked first. */
    private val routes: List<String> =
        (listOf(config.server) + config.routes).distinct()

    @Volatile private var active: String = config.server
    private fun timelineUrl() = plex.timelineUrl(active, config.timelineRef)

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

    private val indexFile = File(context.cacheDir, "episode/${config.profile}.idx")
    private val subsFile = File(context.cacheDir, "episode/${config.profile}.srt")

    /**
     * Fetch and parse the index and subtitles, once.
     *
     * Both are cached to disk: the index is ~6 KB and the sidecar ~32 KB, so
     * holding them costs nothing next to re-fetching them on every cold start.
     */
    @Synchronized
    fun episode(): Episode {
        episode?.let { return it }
        indexFile.parentFile?.mkdirs()

        val idxBytes = indexFile.takeIf { it.exists() }?.readBytes() ?: run {
            viaAnyRoute("index") { uri ->
                val url = plex.timelineUrl(uri, config.timelineRef)
                val head = plex.getRange(url, 0, 63)
                val header = Timeline.parseHeader(head)
                Log.i(TAG, "BIF ${header.count} frames, multiplier ${header.multiplier} ms")
                plex.getRange(url, 0, (header.indexBytes - 1).toLong())
            }?.also { indexFile.writeBytes(it) }
                ?: throw java.io.IOException("no route to the Plex server")
        }
        val header = Timeline.parseHeader(idxBytes)
        val index = Timeline.parseIndex(idxBytes, header)

        val srt = subsFile.takeIf { it.exists() }?.readText() ?: run {
            if (config.subtitleRef.isEmpty()) "" else {
                viaAnyRoute("subtitles") { uri ->
                    Srt.decodeBytes(plex.getTextBytes(plex.subtitleUrl(uri, config.subtitleRef)))
                }?.also { subsFile.writeText(it) } ?: ""
            }
        }
        val cues = if (srt.isEmpty()) emptyList() else Srt.parse(srt)

        val ep = Episode(
            index = index,
            picked = Timeline.pickFrames(index, config.intervalMs),
            cues = cues,
            intervalMs = config.intervalMs,
            skipSilent = config.skipSilent,
        )
        Log.i(TAG, "episode ready: ${ep.sceneCount} scenes, ${cues.size} cues")
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

        val jpeg = viaAnyRoute("scene $sceneIndex") { uri ->
            plex.getRange(
                plex.timelineUrl(uri, config.timelineRef),
                ent.offset.toLong(),
                (ent.offset + ent.length - 1).toLong(),
            )
        } ?: return null

        val cues = ep.cuesFor(r.frameIndex).ifEmpty { listOf(fmtTime(ent.tsMs)) }
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
        private const val TAG = "BifRepo"

        /** Null when no episode has been configured yet. */
        fun open(context: Context): EpisodeRepository? {
            val cfg = Settings(context).episode ?: return null
            return EpisodeRepository(context.applicationContext, cfg)
        }
    }
}
