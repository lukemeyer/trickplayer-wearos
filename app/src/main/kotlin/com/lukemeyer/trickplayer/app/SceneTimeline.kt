package com.lukemeyer.trickplayer.app

import android.content.Context
import android.util.Log
import com.lukemeyer.trickplayer.core.scene.Advance
import com.lukemeyer.trickplayer.core.scene.Cursor
import com.lukemeyer.trickplayer.data.SceneCache
import com.lukemeyer.trickplayer.data.Settings

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
 *
 * **The burst is [Advance.AUTOPLAY], and nothing else.** It used to happen
 * whatever the user had asked for, which is why "on wake: next subtitle" would
 * still march several scenes forward while they watched — the setting described
 * one advance and the mechanism delivered eight. Now a burst is built only when
 * autoplay is the chosen behaviour; every other mode produces **one entry**, at
 * a position moved by exactly the amount that mode names.
 */
object SceneTimeline {

    private const val TAG = "TpTimeline"

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
        val horizon = s.autoplayLengthMs.coerceAtLeast(step)

        // Catch up with whatever the last burst actually played BEFORE deciding
        // anything, or this moves forward from a stale position.
        consumePrevious(s, cache, nowMs, step)

        // A tap-started autoplay outlives the tap: the tap could only ask the
        // system to come back and ask us, so the intent has to be stored.
        val autoplaying = nowMs < s.autoplayUntilMs
        val mode = if (autoplaying) Advance.AUTOPLAY else s.onWake

        if (mode != Advance.AUTOPLAY) return single(s, cache, nowMs, horizon, mode)

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

            // An autoplay step is a next-subtitle step: cues first, then the
            // picture. That is what makes three advances in four free.
            if (cue + 1 < cues.size) {
                cue++
            } else {
                scene++
                cue = 0
            }
        }

        if (steps.isNotEmpty()) {
            s.timelineStartMs = nowMs
            Log.i(TAG, "autoplay burst of ${steps.size} steps x ${step}ms from scene " +
                "${s.sceneIndex} cue ${s.cueIndex} (through scene $scene cue $cue)")
        }
        return steps
    }

    /**
     * One entry, at a position moved by exactly what this mode names.
     *
     * The dwell gate does the work that made a burst self-consistent: a single
     * request round asks **both** complications, and without it the second would
     * advance a second time. It is also what stops the feedback loop — moving
     * asks the system to refresh, which asks us again, which is itself
     * glance-shaped.
     */
    private fun single(
        s: Settings,
        cache: SceneCache,
        nowMs: Long,
        horizon: Long,
        mode: Advance,
    ): List<Step> {
        if (mode != Advance.NOTHING) {
            val cues = cache.get(s.sceneIndex)?.cues.orEmpty()
            val crossing = mode == Advance.NEXT_SCENE || s.cueIndex + 1 >= maxOf(cues.size, 1)
            // Never step into a scene that is not there yet: holding position is
            // recoverable, walking off the end of the cache is not.
            if (!crossing || cache.has(s.sceneIndex + 1)) {
                val cursor = Cursor(s.sceneIndex, s.cueIndex, s.dwellMs, s.lastAdvanceMs)
                when (val r = cursor.advance(nowMs, maxOf(cues.size, 1), mode)) {
                    is Cursor.Result.Throttled -> Unit
                    is Cursor.Result.SameScene -> {
                        s.setPosition(r.sceneIndex, r.cueIndex)
                        s.lastAdvanceMs = cursor.lastAdvanceMs
                    }
                    is Cursor.Result.NewScene -> {
                        s.setPosition(r.sceneIndex, 0)
                        s.lastAdvanceMs = cursor.lastAdvanceMs
                    }
                }
            }
        }

        // Nothing is playing, so nothing may be credited as played later.
        s.timelineStartMs = 0L

        val entry = cache.get(s.sceneIndex) ?: return emptyList()
        val cue = entry.cues.getOrElse(s.cueIndex) { entry.cues.lastOrNull() ?: "" }
        Log.i(TAG, "$mode -> scene ${s.sceneIndex} cue ${s.cueIndex} (one entry)")
        return listOf(Step(nowMs, nowMs + horizon, s.sceneIndex, s.cueIndex, entry, cue))
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
        // Only an autoplay ever leaves a burst in flight, and every other path
        // zeroes this — so a non-zero start IS "an autoplay was playing".
        val start = s.timelineStartMs
        if (start <= 0L) return

        val elapsed = (nowMs - start).coerceAtLeast(0)
        val played = (elapsed / stepMs).toInt()
            .coerceAtMost((s.autoplayLengthMs / stepMs).toInt())
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
