package com.lukemeyer.bif.data

import android.content.Context
import com.lukemeyer.bif.core.scene.Cursor

/**
 * The chosen episode, the tuning knobs, and where we are in it.
 *
 * SharedPreferences, not DataStore, and that is deliberate: a complication data
 * source may be given **100 ms** to answer when `immediateResponseRequired` is
 * set, and DataStore is suspend-only. A synchronous read from an
 * already-loaded SharedPreferences is the right shape for that callback;
 * anything that needs a coroutine to answer a 100 ms deadline is a liability.
 */
class Settings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("bif.settings", Context.MODE_PRIVATE)

    data class Episode(
        /** The route currently believed to work. */
        val server: String,
        /**
         * Every route plex.tv knows about, best first.
         *
         * Stored because a watch moves. A route is raced once when the episode
         * is chosen, and the winner is almost always the LAN address — which is
         * unreachable the moment you leave the house. Without the alternatives
         * on hand there is nothing to fall back to, and the face simply stops
         * with everything apparently fine: fetches fail quietly, the cache runs
         * dry, and it sits on "Loading…" forever.
         */
        val routes: List<String> = listOf(server),
        val token: String,
        val timelineRef: Long,
        val subtitleRef: String,
        val title: String,
        val skipSilent: Boolean,
    ) {
        /**
         * Identifies the cached encoding. Changing episode or scene granularity
         * changes this, so stale scenes can never be served for the new one —
         * the same job `cache.setProfile(timelineRef, w, h, depth)` did on Pebble.
         */
        val profile: String get() = "$timelineRef.$SCENE_POLICY"
    }

    var episode: Episode?
        get() {
            val server = prefs.getString(K_SERVER, null) ?: return null
            val token = prefs.getString(K_TOKEN, null) ?: return null
            val timelineRef = prefs.getLong(K_PART, -1L)
            if (timelineRef < 0) return null
            return Episode(
                server = server,
                token = token,
                timelineRef = timelineRef,
                routes = prefs.getString(K_ROUTES, null)
                    ?.split('\n')?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() } ?: listOf(server),
                subtitleRef = prefs.getString(K_SUBKEY, "").orEmpty(),
                title = prefs.getString(K_TITLE, "").orEmpty(),
                skipSilent = prefs.getBoolean(K_SKIP_SILENT, true),
            )
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) {
                    clear()
                } else {
                    putString(K_SERVER, v.server)
                    putString(K_ROUTES, v.routes.joinToString("\n"))
                    putString(K_TOKEN, v.token)
                    putLong(K_PART, v.timelineRef)
                    putString(K_SUBKEY, v.subtitleRef)
                    putString(K_TITLE, v.title)
                    putBoolean(K_SKIP_SILENT, v.skipSilent)
                    // A new episode invalidates the position, not just the cache.
                    putInt(K_SCENE, 0)
                    putInt(K_CUE, 0)
                }
            }.apply()
        }

    /**
     * Resume position. Persisted on every advance so the face picks up where it
     * left off across reboots and updates — which matters more here than it
     * looks, since crossing into a new scene is the step that costs a fetch.
     */
    var sceneIndex: Int
        get() = prefs.getInt(K_SCENE, 0)
        set(v) = prefs.edit().putInt(K_SCENE, v).apply()

    var cueIndex: Int
        get() = prefs.getInt(K_CUE, 0)
        set(v) = prefs.edit().putInt(K_CUE, v).apply()

    /** Promote a route that just worked, so the next fetch starts there. */
    fun setActiveRoute(uri: String) {
        prefs.edit().putString(K_SERVER, uri).apply()
    }

    /**
     * Why the last fetch failed, or null. Lets the face say something more
     * useful than "Loading…" when it is really "off the network".
     */
    var lastError: String?
        get() = prefs.getString(K_LAST_ERROR, null)
        set(v) = prefs.edit().putString(K_LAST_ERROR, v).apply()

    fun setPosition(scene: Int, cue: Int) {
        prefs.edit().putInt(K_SCENE, scene).putInt(K_CUE, cue).apply()
    }

    /**
     * When the cursor last moved.
     *
     * Persisted because the process that moves it does not survive between
     * moves — a complication data source is bound, asked, and killed — so an
     * in-memory dwell timestamp resets constantly and gates nothing.
     */
    var lastAdvanceMs: Long
        get() = prefs.getLong(K_LAST_ADVANCE, 0L)
        set(v) = prefs.edit().putLong(K_LAST_ADVANCE, v).apply()

    /**
     * How long a glance-driven advance waits before it will move again.
     *
     * A knob rather than a constant because the interesting question — how fast
     * the system actually polls a complication — cannot be answered while our
     * own gate is the thing doing the limiting. Set it low to watch the raw
     * cadence, then back up to something sane.
     */
    var dwellMs: Long
        get() = prefs.getLong(K_DWELL, Cursor.DEFAULT_MIN_DWELL_MS)
        set(v) = prefs.edit().putLong(K_DWELL, v).apply()

    /**
     * How long each entry of the burst is on screen.
     *
     * Two seconds: fast enough that a glance shows a run of the show rather
     * than a single still, slow enough to read a line of dialogue. Requires the
     * face to redraw every second, which is why it shows seconds.
     */
    var timelineStepMs: Long
        get() = prefs.getLong(K_TL_STEP, 2_000L)
        set(v) = prefs.edit().putLong(K_TL_STEP, v).apply()

    /**
     * How far ahead a single push covers.
     *
     * **Match this to the watch's screen timeout.** The horizon is not "how far
     * ahead we could plan", it is "how long the screen will be on", and those
     * are different numbers by two orders of magnitude. A thirty-minute burst on
     * a watch that sleeps after fifteen seconds is 99% waste: the entries are
     * built, pushed across a Binder, and never rendered.
     *
     * Matching it also makes the catch-up arithmetic honest. [timelineStartMs]
     * infers what played from elapsed wall clock, capped at the burst length —
     * so when the burst and the awake period are the same length, "the burst
     * finished" and "the screen was on throughout" mean the same thing. Make the
     * burst longer and the cap starts crediting steps that were shown to a dark
     * screen.
     */
    var timelineHorizonMs: Long
        get() = prefs.getLong(K_TL_HORIZON, 16_000L)
        set(v) = prefs.edit().putLong(K_TL_HORIZON, v).apply()

    /**
     * When the timeline currently on the face was pushed, or 0 for none.
     *
     * The face plays a burst on its own and nothing tells us how much of it was
     * actually seen — the wrist may have dropped after two seconds. Recording
     * the start lets the next request work out how many steps really elapsed
     * from the wall clock, rather than assuming the whole burst was watched and
     * skipping content nobody saw.
     */
    var timelineStartMs: Long
        get() = prefs.getLong(K_TL_START, 0L)
        set(v) = prefs.edit().putLong(K_TL_START, v).apply()

    /**
     * The sign-in PIN in flight, if any.
     *
     * Persisted because the config activity does not necessarily survive the
     * user walking away to type the code at plex.tv/link on another device —
     * which is the whole point of a short PIN. See
     * trickplayer-knowledge findings/F-018. Minting a fresh one
     * on the way back strands them on a code that is no longer being polled,
     * and they have no way to tell.
     */
    data class PendingPin(val id: Long, val code: String, val expiresAtMs: Long) {
        fun isLive(nowMs: Long) = nowMs < expiresAtMs
    }

    var pendingPin: PendingPin?
        get() {
            val id = prefs.getLong(K_PIN_ID, -1L)
            if (id < 0) return null
            return PendingPin(
                id = id,
                code = prefs.getString(K_PIN_CODE, "").orEmpty(),
                expiresAtMs = prefs.getLong(K_PIN_EXPIRES, 0L),
            )
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) {
                    remove(K_PIN_ID); remove(K_PIN_CODE); remove(K_PIN_EXPIRES)
                } else {
                    putLong(K_PIN_ID, v.id)
                    putString(K_PIN_CODE, v.code)
                    putLong(K_PIN_EXPIRES, v.expiresAtMs)
                }
            }.apply()
        }

    private companion object {
        /**
         * Part of the cache profile, so a change to the SCENE SELECTION POLICY
         * invalidates cached scenes rather than serving them under a mapping
         * that no longer holds.
         *
         * The profile used to key on the scene interval, which worked only
         * while an interval was what decided scene -> frame. Adopting F-001
         * removed the interval entirely: scene N now resolves to a different
         * frame than it did, so every pre-existing entry is wrong. Bumping
         * this is what makes that safe.
         */
        const val SCENE_POLICY = "f001"

        const val K_SERVER = "server"
        const val K_ROUTES = "routes"
        const val K_TOKEN = "token"
        const val K_PART = "partId"
        const val K_SUBKEY = "subKey"
        const val K_TITLE = "title"
        const val K_SKIP_SILENT = "skipSilent"
        const val K_SCENE = "scene"
        const val K_CUE = "cue"
        const val K_LAST_ADVANCE = "lastAdvanceMs"
        const val K_DWELL = "dwellMs"
        const val K_TL_STEP = "timelineStepMs"
        const val K_TL_HORIZON = "timelineHorizonMs"
        const val K_TL_START = "timelineStartMs"
        const val K_LAST_ERROR = "lastError"
        const val K_PIN_ID = "pinId"
        const val K_PIN_CODE = "pinCode"
        const val K_PIN_EXPIRES = "pinExpiresAtMs"
    }
}
