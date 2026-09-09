package com.lukemeyer.bif.core.scene

/**
 * Where we are in the episode, and the single path by which that moves.
 *
 * Every trigger funnels through [advance] rather than touching the position
 * directly, so rate limiting lives in exactly one place and a trigger source
 * added later cannot get it wrong. That was the point of the Pebble trigger
 * registry and it carries over unchanged.
 *
 * The rate limit is a policy choice here, as it was on Pebble. The docs ask that
 * a data source not request updates more than once per five minutes on average,
 * but that is guidance rather than a gate: eight taps a second apart all
 * delivered, each about 510 ms after the tap, with nothing throttled
 * (spikes/PHASE0-FINDINGS.md, W3). So the five minutes below is politeness and
 * battery, not a wall — and it is a knob worth turning down while testing.
 */
class Cursor(
    var sceneIndex: Int = 0,
    var cueIndex: Int = 0,
    private val minDwellMs: Long = DEFAULT_MIN_DWELL_MS,
    /**
     * When the last advance happened, restored from storage.
     *
     * This exists because the process holding a Cursor does not survive between
     * uses: a complication data source is bound, asked, and killed. Keeping the
     * timestamp in memory means the dwell gate resets constantly and never
     * actually gates anything, so the caller persists it and hands it back.
     */
    lastAdvanceMs: Long = 0L,
) {
    var lastAdvanceMs: Long = lastAdvanceMs
        private set

    sealed interface Result {
        /** Too soon since the last move. Nothing changed. */
        data object Throttled : Result
        /** Moved within the same scene: new text, **same image, no fetch**. */
        data class SameScene(val sceneIndex: Int, val cueIndex: Int) : Result
        /** Crossed into a new scene: a new frame is needed. */
        data class NewScene(val sceneIndex: Int) : Result
    }

    /**
     * @param cueCount how many cues the *current* scene has. The caller knows
     *   this; passing it in keeps [Cursor] free of any dependency on how scenes
     *   are stored or fetched.
     */
    /**
     * @param force skip the dwell gate. A tap is explicit intent and should
     *   always move; a glance is not, and must not turn a wrist-raise into a
     *   runaway advance.
     */
    fun advance(nowMs: Long, cueCount: Int, force: Boolean = false): Result {
        if (!force && nowMs - lastAdvanceMs < minDwellMs) return Result.Throttled
        lastAdvanceMs = nowMs

        // Text-only advance first — this is the cheap, common case. At 3.17 cues
        // per scene roughly three advances in four land here.
        if (cueIndex + 1 < cueCount) {
            cueIndex++
            return Result.SameScene(sceneIndex, cueIndex)
        }
        sceneIndex++
        cueIndex = 0
        return Result.NewScene(sceneIndex)
    }

    /** Reset the throttle — used when the episode changes under us. */
    fun resetDwell() { lastAdvanceMs = 0 }

    fun moveTo(scene: Int, cue: Int = 0) {
        sceneIndex = scene
        cueIndex = cue
    }

    companion object {

        /**
         * Walk a position forward [steps] times, stopping at the edge of what
         * is available.
         *
         * Pure, and separate from any storage, because this walk has now been
         * got wrong twice in ways that only showed up on hardware. Both were the
         * same mistake: treating "this scene has no cues" as "move to the next
         * scene", when it actually means "this scene was never fetched". The
         * cursor then marched off the end of the cache into scenes that do not
         * exist and could not come back — the face showing "Loading…" for ever
         * with a cache full of perfectly good earlier scenes.
         *
         * @param cueCountOf how many cues a scene has, or **null if it is not
         *   available**. The distinction is the entire point.
         * @return where the walk ended, which may be short of [steps].
         */
        fun walk(
            sceneIndex: Int,
            cueIndex: Int,
            steps: Int,
            cueCountOf: (Int) -> Int?,
        ): Pair<Int, Int> {
            var scene = sceneIndex
            var cue = cueIndex
            repeat(steps) {
                val cues = cueCountOf(scene) ?: return scene to cue
                if (cue + 1 < cues) {
                    cue++
                } else {
                    if (cueCountOf(scene + 1) == null) return scene to cue
                    scene++
                    cue = 0
                }
            }
            return scene to cue
        }

        /**
         * How long a glance-driven advance waits before it will move again.
         *
         * A minute, following the Pebble build's `MIN_DWELL_MS`. It has two
         * jobs. The obvious one is that raising your wrist twice in ten seconds
         * should not skip two scenes. The subtle and more important one is that
         * it breaks a feedback loop: advancing asks the system to refresh the
         * complications, which asks the data source for data, which is itself a
         * glance-shaped event — without a gate that cycles forever.
         *
         * Not to be confused with the five-minute figure in the docs for
         * `requestUpdate`, which is about how often we may *ask* to redraw, and
         * which W3 measured as advisory rather than enforced.
         */
        const val DEFAULT_MIN_DWELL_MS = 60 * 1000L
    }
}
