package com.lukemeyer.bif.app

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.lukemeyer.bif.data.EpisodeRepository
import com.lukemeyer.bif.data.ScenePrefetchWorker
import com.lukemeyer.bif.core.scene.Advance
import com.lukemeyer.bif.core.scene.Cursor
import com.lukemeyer.bif.data.SceneCache
import com.lukemeyer.bif.data.Settings

/**
 * Where the face is in the episode, and the single path by which that moves.
 *
 * Both complication services read through here, so the frame and the cue can
 * never disagree about which scene is current.
 */
object SceneState {

    private const val TAG = "BifState"

    fun settings(ctx: Context) = Settings(ctx)

    /**
     * The current scene, from cache only.
     *
     * Never fetches. Called from `onComplicationRequest`, which may be given
     * 100 ms — see [EpisodeRepository]. A miss returns null and the caller shows
     * a placeholder; the prefetch queued alongside is what fixes it for next
     * time.
     */
    fun current(ctx: Context): SceneCache.Entry? {
        val cfg = Settings(ctx).episode ?: return null
        val idx = Settings(ctx).sceneIndex
        return SceneCache(ctx, cfg.profile).get(idx)
    }

    fun cueText(ctx: Context, entry: SceneCache.Entry?): String {
        val s = Settings(ctx)
        val e = entry ?: return when {
            s.episode == null -> "Not configured"
            // "Loading…" that never resolves is the worst thing this can say:
            // it looks like patience will fix it when nothing will. If the last
            // fetch failed, say so.
            s.lastError != null -> "Can't reach Plex"
            else -> "Loading…"
        }
        if (e.cues.isEmpty()) return ""
        return e.cues[s.cueIndex.coerceIn(0, e.cues.lastIndex)]
    }

    /**
     * Advance one step: cue-within-scene first, then scene.
     *
     * The cheap case is deliberately first and deliberately common — at the
     * measured 3.22 cues per scene, roughly three advances in four change only
     * the text and touch neither the network nor the cache.
     *
     * @param mode what this trigger is configured to do. [Advance.NOTHING]
     *   returns without moving, which is the honest implementation of "do
     *   nothing" — not an advance that is then discarded.
     * @param force a tap is explicit intent and always moves. A glance is not,
     *   and goes through the dwell gate — both so a wrist-raise does not skip
     *   scenes, and because advancing triggers a complication refresh, which is
     *   itself glance-shaped and would otherwise loop forever.
     * @return true if anything moved.
     */
    fun advance(ctx: Context, force: Boolean, mode: Advance = Advance.NEXT_SUBTITLE): Boolean {
        if (mode == Advance.NOTHING) return false
        // Catch up with whatever the burst on the face already played, or this
        // moves forward from a position that is several steps stale — which on
        // screen looks like the face jumping backwards.
        SceneTimeline.consume(ctx)

        val s = Settings(ctx)
        val cfg = s.episode ?: return false
        val cache = SceneCache(ctx, cfg.profile)

        val cursor = Cursor(
            sceneIndex = s.sceneIndex,
            cueIndex = s.cueIndex,
            minDwellMs = s.dwellMs,
            lastAdvanceMs = s.lastAdvanceMs,
        )
        val cues = cache.get(cursor.sceneIndex)?.cues.orEmpty()
        val now = System.currentTimeMillis()

        // Never step into a scene that is not there yet.
        //
        // Tapping faster than the prefetch can fetch used to walk the cursor
        // off the end of the cache, and then the face showed "Loading…" over a
        // stale frame and stayed there — the cursor was somewhere the fetcher
        // was not, and nothing ever brought them back together. Holding
        // position instead is what the Pebble build did when its ring ran dry,
        // and for the same reason: a face that pauses is fine, a face that
        // breaks is not.
        // Under NEXT_SCENE every advance crosses, because unread cues in this
        // scene are exactly what it skips.
        val crossing = mode == Advance.NEXT_SCENE ||
            cursor.cueIndex + 1 >= maxOf(cues.size, 1)
        if (crossing && !cache.has(cursor.sceneIndex + 1)) {
            Log.i(TAG, "holding at scene ${cursor.sceneIndex}: next not cached yet")
            ScenePrefetchWorker.enqueue(ctx, cursor.sceneIndex)
            return false
        }

        return when (val r = cursor.advance(now, maxOf(cues.size, 1), mode, force)) {
            is Cursor.Result.Throttled -> {
                Log.i(TAG, "advance throttled (glance within dwell)")
                false
            }
            is Cursor.Result.SameScene -> {
                s.setPosition(r.sceneIndex, r.cueIndex)
                s.lastAdvanceMs = cursor.lastAdvanceMs
                // The burst on the face is now wrong, and its elapsed time must
                // not be counted again on top of this move.
                s.timelineStartMs = 0L
                Log.i(TAG, "advance -> scene ${r.sceneIndex} cue ${r.cueIndex} (text only)")
                true
            }
            is Cursor.Result.NewScene -> {
                s.setPosition(r.sceneIndex, 0)
                s.lastAdvanceMs = cursor.lastAdvanceMs
                s.timelineStartMs = 0L
                Log.i(TAG, "advance -> scene ${r.sceneIndex} (new frame)")
                // Crossing a scene is the only advance that consumes the buffer,
                // so it is the only one that has to refill it.
                ScenePrefetchWorker.enqueue(ctx, r.sceneIndex)
                true
            }
        }
    }


    /** Keep the cache warm ahead of wherever we are. */
    fun ensurePrefetch(ctx: Context) {
        if (Settings(ctx).episode == null) return
        ScenePrefetchWorker.enqueue(ctx, Settings(ctx).sceneIndex)
    }

    fun requestUpdate(ctx: Context) {
        val app = ctx.applicationContext
        listOf(
            FrameComplicationService::class.java,
            SubtitleComplicationService::class.java,
        ).forEach {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, it))
                .requestUpdateAll()
        }
    }
}
