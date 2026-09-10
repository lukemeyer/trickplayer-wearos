package com.lukemeyer.bif.data

import android.content.Context
import com.lukemeyer.bif.core.scene.Advance
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

    /**
     * Which provider this episode came from.
     *
     * Absent means Plex, because every config written before there was a second
     * provider is one — reading it as anything else would orphan an existing
     * watch's setup, which is the same reason the key strings below are frozen.
     */
    enum class Provider { PLEX, JELLYFIN }

    /**
     * What Jellyfin needs to rebuild a source, and Plex does not have.
     *
     * A tile-sheet source is not addressed by a part id and a subtitle path: it
     * needs the item, the media source, the chosen width and the whole geometry
     * (F-038). Stored as its own block rather than by widening the Plex fields,
     * because "timelineRef, but sometimes it means something else" is how a
     * seam gets quietly undone.
     */
    data class Trickplay(
        val itemId: String,
        val mediaSourceId: String,
        val width: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val thumbWidth: Int,
        val thumbHeight: Int,
        val intervalMs: Long,
        val thumbnailCount: Int,
        val subtitleIndex: Int,
    )

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
        val provider: Provider = Provider.PLEX,
        /** Jellyfin only. Null on Plex, where the two refs above are enough. */
        val trickplay: Trickplay? = null,
    ) {
        /**
         * Identifies the cached encoding. Changing episode or scene granularity
         * changes this, so stale scenes can never be served for the new one —
         * the same job `cache.setProfile(timelineRef, w, h, depth)` did on Pebble.
         */
        val profile: String get() {
            // skipSilent is part of the profile because it changes the SCENE
            // LIST, not just what is shown: with it off, scene 93 resolves to a
            // different frame. Cached scenes from the other setting are not
            // stale, they are wrong.
            val policy = "$SCENE_POLICY.${if (skipSilent) "s1" else "s0"}"
            return if (provider == Provider.JELLYFIN && trickplay != null) {
                "jf-${trickplay.itemId}.${trickplay.width}.$policy"
            } else {
                "$timelineRef.$policy"
            }
        }
    }

    var episode: Episode?
        get() {
            val server = prefs.getString(K_SERVER, null) ?: return null
            val token = prefs.getString(K_TOKEN, null) ?: return null
            val provider =
                if (prefs.getString(K_PROVIDER, null) == "jellyfin") Provider.JELLYFIN
                else Provider.PLEX
            val trickplay = if (provider == Provider.JELLYFIN) {
                Trickplay(
                    itemId = prefs.getString(K_JF_ITEM, null) ?: return null,
                    mediaSourceId = prefs.getString(K_JF_MEDIA, "").orEmpty(),
                    width = prefs.getInt(K_JF_WIDTH, 0),
                    tileWidth = prefs.getInt(K_JF_TILE_W, 0),
                    tileHeight = prefs.getInt(K_JF_TILE_H, 0),
                    thumbWidth = prefs.getInt(K_JF_THUMB_W, 0),
                    thumbHeight = prefs.getInt(K_JF_THUMB_H, 0),
                    intervalMs = prefs.getLong(K_JF_INTERVAL, 0L),
                    thumbnailCount = prefs.getInt(K_JF_COUNT, 0),
                    subtitleIndex = prefs.getInt(K_JF_SUB_INDEX, -1),
                )
            } else null
            // A Plex config is identified by its part id; a Jellyfin one has no
            // such number and must not be rejected for lacking it.
            val timelineRef = prefs.getLong(K_PART, -1L)
            if (provider == Provider.PLEX && timelineRef < 0) return null
            return Episode(
                provider = provider,
                trickplay = trickplay,
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
                    // Remove the episode, not the file. A blanket clear() used
                    // to be harmless because the episode was all there was;
                    // it now shares these prefs with the saved sources, and
                    // signing out of an episode must not sign the watch out of
                    // its servers.
                    listOf(
                        K_SERVER, K_ROUTES, K_TOKEN, K_PART, K_SUBKEY, K_TITLE,
                        K_SKIP_SILENT, K_SCENE, K_CUE, K_PROVIDER,
                        K_JF_ITEM, K_JF_MEDIA, K_JF_WIDTH, K_JF_TILE_W, K_JF_TILE_H,
                        K_JF_THUMB_W, K_JF_THUMB_H, K_JF_INTERVAL, K_JF_COUNT,
                        K_JF_SUB_INDEX,
                    ).forEach { remove(it) }
                } else {
                    putString(K_SERVER, v.server)
                    putString(K_ROUTES, v.routes.joinToString("\n"))
                    putString(K_TOKEN, v.token)
                    putLong(K_PART, v.timelineRef)
                    putString(K_SUBKEY, v.subtitleRef)
                    putString(K_TITLE, v.title)
                    putBoolean(K_SKIP_SILENT, v.skipSilent)
                    putString(K_PROVIDER, if (v.provider == Provider.JELLYFIN) "jellyfin" else "plex")
                    v.trickplay?.let { t ->
                        putString(K_JF_ITEM, t.itemId)
                        putString(K_JF_MEDIA, t.mediaSourceId)
                        putInt(K_JF_WIDTH, t.width)
                        putInt(K_JF_TILE_W, t.tileWidth)
                        putInt(K_JF_TILE_H, t.tileHeight)
                        putInt(K_JF_THUMB_W, t.thumbWidth)
                        putInt(K_JF_THUMB_H, t.thumbHeight)
                        putLong(K_JF_INTERVAL, t.intervalMs)
                        putInt(K_JF_COUNT, t.thumbnailCount)
                        putInt(K_JF_SUB_INDEX, t.subtitleIndex)
                    }
                    // A new episode invalidates the position, not just the cache.
                    putInt(K_SCENE, 0)
                    putInt(K_CUE, 0)
                }
            }.apply()
        }

    /**
     * What a wrist raise does, and what a tap does.
     *
     * Two controls rather than one because the triggers are genuinely
     * independent here — a wrist raise is ambient intent, a complication tap is
     * deliberate — and different answers to each are reasonable: *nothing* on
     * wake so the episode does not drift while you check the time, *next scene*
     * on tap when you actually want it. Pebble gets one row because a watch face
     * there cannot receive touch at all (UI.md §4.2).
     *
     * Global rather than per-episode: it is how you like the face to behave, not
     * a property of what is playing.
     */
    var onWake: Advance
        get() = readAdvance(K_ON_WAKE, Advance.NEXT_SUBTITLE)
        set(v) = prefs.edit().putString(K_ON_WAKE, v.name).apply()

    var onTap: Advance
        get() = readAdvance(K_ON_TAP, Advance.NEXT_SCENE)
        set(v) = prefs.edit().putString(K_ON_TAP, v.name).apply()

    private fun readAdvance(key: String, fallback: Advance): Advance =
        prefs.getString(key, null)
            ?.let { runCatching { Advance.valueOf(it) }.getOrNull() }
            ?: fallback

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

    /**
     * The servers this watch is signed in to — provider, address, credential.
     *
     * Saved whole, and on Jellyfin that is not a convenience: the address IS the
     * identity, there is no account service to rebuild it from, and asking
     * someone to dictate `http://192.168.1.10:8096` to a watch a second time is
     * not a recovery path (UI.md §1).
     *
     * Stored as the flat string maps the accounts hand back, one per line of
     * `key\tvalue`, records separated by a form feed. A map is what
     * `MediaAccount.persist()` returns precisely so this layer does not have to
     * know which provider's fields it is holding.
     */
    var sources: List<Map<String, String>>
        get() = prefs.getString(K_SOURCES, null)
            ?.split('\u000c')
            ?.filter { it.isNotBlank() }
            ?.map { rec ->
                rec.split('\n').mapNotNull { line ->
                    val i = line.indexOf('\t')
                    if (i < 0) null else line.substring(0, i) to line.substring(i + 1)
                }.toMap()
            }
            ?: emptyList()
        set(v) {
            prefs.edit().putString(
                K_SOURCES,
                v.joinToString("\u000c") { rec ->
                    rec.entries.joinToString("\n") { "${it.key}\t${it.value}" }
                },
            ).apply()
        }

    /** Add or replace one, keyed by provider and server id. */
    fun saveSource(record: Map<String, String>) {
        val id = record["provider"] to record["id"]
        sources = sources.filterNot { (it["provider"] to it["id"]) == id } + listOf(record)
        lastSourceId = "${record["provider"]}:${record["id"]}"
    }

    /** The last-used source is the default, and browsing starts there. */
    var lastSourceId: String?
        get() = prefs.getString(K_LAST_SOURCE, null)
        set(v) = prefs.edit().putString(K_LAST_SOURCE, v).apply()

    /**
     * A sign-in in flight, whichever provider it belongs to.
     *
     * Generalised from [PendingPin] for the same reason it existed: the code is
     * typed on another device, so this activity may not survive the trip
     * (F-018). Quick Connect has a secret rather than a pin id, which is why
     * this is a map and not two more columns.
     */
    var pendingAuth: Map<String, String>?
        get() = prefs.getString(K_PENDING_AUTH, null)
            ?.split('\n')
            ?.mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i < 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }
        set(v) {
            prefs.edit().apply {
                if (v == null) remove(K_PENDING_AUTH)
                else putString(K_PENDING_AUTH, v.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            }.apply()
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
        // New keys only. The ones above are frozen: renaming a stored key
        // silently orphans an existing watch's configuration.
        const val K_PROVIDER = "provider"
        const val K_ON_WAKE = "onWake"
        const val K_ON_TAP = "onTap"
        const val K_SOURCES = "sources"
        const val K_LAST_SOURCE = "lastSourceId"
        const val K_PENDING_AUTH = "pendingAuth"
        const val K_JF_ITEM = "jfItemId"
        const val K_JF_MEDIA = "jfMediaSourceId"
        const val K_JF_WIDTH = "jfWidth"
        const val K_JF_TILE_W = "jfTileWidth"
        const val K_JF_TILE_H = "jfTileHeight"
        const val K_JF_THUMB_W = "jfThumbWidth"
        const val K_JF_THUMB_H = "jfThumbHeight"
        const val K_JF_INTERVAL = "jfIntervalMs"
        const val K_JF_COUNT = "jfThumbnailCount"
        const val K_JF_SUB_INDEX = "jfSubtitleIndex"

        const val K_PIN_ID = "pinId"
        const val K_PIN_CODE = "pinCode"
        const val K_PIN_EXPIRES = "pinExpiresAtMs"
    }
}
