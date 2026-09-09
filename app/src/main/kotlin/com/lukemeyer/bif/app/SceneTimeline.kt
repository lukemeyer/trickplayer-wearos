package com.lukemeyer.bif.app

import android.content.Context
import android.util.Log
import com.lukemeyer.bif.core.scene.Cursor
import com.lukemeyer.bif.data.SceneCache
import com.lukemeyer.bif.data.Settings

/**
 * Works out what the face should show over the next few seconds, so it can be
 * handed the whole sequence at once and play it without us.
 *
 * This exists because the trigger we wanted does not. Waking the watch produces
 * no complication request — measured at zero across five wake cycles — and
 * `UPDATE_PERIOD_SECONDS` is ignored below about five minutes. So rather than
 * being told when to move, the face is given a short burst of pre-computed steps
 * and steps through them on its own.
 *
 * Both complications derive from **this one function**. They are separate data
 * sources answering separate requests, and if each worked out its own position
 * they would drift — a frame from one scene under a cue from the next.
 *
 * The burst is sized to the screen timeout, not to how far ahead we could plan:
 * the watch sleeps after about fifteen seconds, so anything longer is built,
 * pushed across a Binder, and never seen.
 */
object SceneTimeline {

    private const val TAG = "BifTimeline"

    /** One step of the burst: what is on screen for one slice of time. */
    data class Step(
        val fromMs: Long,
        val toMs: Long,
        val sceneIndex: Int,
        val cueIndex: Int,
        val entry: SceneCache.Entry?,
        val cue: String,
    )

    /**
     * Advance the stored cursor past however much of the previous burst actually
     * played, then return a fresh burst starting now.
     *
     * Consumption is idempotent: it stamps a new start time, so the second
     * complication to call this in the same round sees zero elapsed steps and
     * does not advance the cursor a second time.
     */
    fun build(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<Step> {
        val s = Settings(ctx)
        val cfg = s.episode ?: return emptyList()
        val cache = SceneCache(ctx, cfg.profile)
        val step = s.timelineStepMs.coerceAtLeast(1000)
        val horizon = s.timelineHorizonMs.coerceAtLeast(step)

        consumePrevious(s, cache, nowMs, step)

        var scene = s.sceneIndex
        var cue = s.cueIndex
        val steps = ArrayList<Step>()
        var t = nowMs
        val end = nowMs + horizon

        while (t < end) {
            val e = cache.get(scene)
            // Stop rather than pushing entries for scenes that have not been
            // fetched — a burst of blanks is worse than a shorter burst.
            if (e == null) {
                if (steps.isEmpty()) Log.i(TAG, "scene $scene not cached; nothing to push")
                break
            }
            val cues = e.cues
            steps.add(
                Step(t, t + step, scene, cue, e, cues.getOrElse(cue) { cues.lastOrNull() ?: "" }),
            )
            t += step

            if (cue + 1 < cues.size) {
                cue++
            } else {
                scene++
                cue = 0
            }
        }

        if (steps.isNotEmpty()) {
            s.timelineStartMs = nowMs
            Log.i(TAG, "burst of ${steps.size} steps x ${step}ms from scene " +
                "${s.sceneIndex} cue ${s.cueIndex} (through scene $scene cue $cue)")
        }
        return steps
    }

    /**
     * Catch the stored cursor up with whatever the face has already played.
     *
     * Must happen before anything reads the cursor, including a tap. A tap that
     * advanced from the pre-burst position would move the face *backwards* over
     * everything the burst had just shown.
     */
    fun consume(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        val s = Settings(ctx)
        val cfg = s.episode ?: return
        consumePrevious(s, SceneCache(ctx, cfg.profile), nowMs, s.timelineStepMs.coerceAtLeast(1000))
    }

    /**
     * Move the cursor forward by the number of steps that genuinely elapsed
     * since the last burst was pushed, capped at the burst's length.
     */
    private fun consumePrevious(s: Settings, cache: SceneCache, nowMs: Long, stepMs: Long) {
        val start = s.timelineStartMs
        if (start <= 0L) return

        val elapsed = (nowMs - start).coerceAtLeast(0)
        val played = (elapsed / stepMs).toInt()
            .coerceAtMost((s.timelineHorizonMs / stepMs).toInt())
        if (played <= 0) return

        // The walk itself lives in :core and is unit-tested. It has been got
        // wrong twice on hardware, both times by reading "not cached" as "a
        // scene with no cues" and marching the cursor off the end of the cache,
        // where nothing could bring it back.
        val (scene, cue) = Cursor.walk(s.sceneIndex, s.cueIndex, played) { idx ->
            cache.get(idx)?.cues?.size
        }

        s.setPosition(scene, cue)
        s.lastAdvanceMs = nowMs
        s.timelineStartMs = 0L      // consumed; do not count these steps twice
        Log.i(TAG, "previous burst played $played step(s) -> scene $scene cue $cue")
    }
}
