package com.lukemeyer.bif.app.config

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lukemeyer.bif.app.SceneState
import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.plex.PlexDiscovery
import com.lukemeyer.bif.core.plex.PlexLibrary
import com.lukemeyer.bif.core.plex.PlexTv
import com.lukemeyer.bif.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Drives sign-in, discovery and browsing.
 *
 * Every step reports partial results as they arrive rather than blocking on a
 * whole library — which matters most for eligibility checking, where a full show
 * is 157 requests and roughly four seconds.
 */
class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Step {
        data object SignIn : Step
        data class Linking(val code: String) : Step
        data object Servers : Step
        data object Libraries : Step
        data object Shows : Step
        data class Items(val showTitle: String) : Step
        data class Done(val title: String) : Step
    }

    data class State(
        val step: Step = Step.SignIn,
        val busy: Boolean = false,
        val error: String? = null,
        val servers: List<PlexTv.Server> = emptyList(),
        val sections: List<PlexLibrary.Section> = emptyList(),
        val shows: List<PlexLibrary.Item> = emptyList(),
        /** Episodes confirmed usable so far; grows while [scanned] climbs. */
        val playable: List<Pair<PlexLibrary.Item, PlexLibrary.Playable>> = emptyList(),
        val scanned: Int = 0,
        val toScan: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val ctx: Context get() = getApplication()
    private val settings = Settings(ctx)
    private var pendingPinId: Long? = null
    private var pendingPinExpiresAtMs: Long? = null

    /**
     * Stable per install, and it must be: plex.tv ties both the PIN and the
     * issued token to this identifier, so regenerating it invalidates the
     * sign-in.
     */
    private val clientId: String = ctx
        .getSharedPreferences("bif.client", Context.MODE_PRIVATE)
        .let { p ->
            p.getString("id", null) ?: UUID.randomUUID().toString()
                .also { p.edit().putString("id", it).apply() }
        }

    private val tv = PlexTv(clientId)

    private var token: String? = null
    private var server: PlexTv.Server? = null
    private var route: String? = null
    private var routes: List<String> = emptyList()
    private var library: PlexLibrary? = null
    private var sectionKey: String? = null
    private var pollJob: Job? = null
    private var scanJob: Job? = null

    init {
        // Already signed in from a previous run? Skip straight to the servers.
        settings.episode?.let { token = it.token }
        if (token != null) loadServers()
    }

    private fun fail(e: Throwable) =
        _state.update { it.copy(busy = false, error = e.message ?: e.toString()) }

    private fun <T> bg(block: suspend () -> T) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            fail(e)
        }
    }

    // ------------------------------------------------------------- sign in

    fun signIn() = bg {
        // Resume a PIN that is still alive rather than minting a new one.
        //
        // The whole point of a short code is that it is typed at plex.tv/link
        // on a *different* device, so the user walks away from the watch
        // mid-flow and the activity may not survive that. Minting a fresh code
        // on the way back silently invalidates the one they are looking at,
        // and nothing on screen tells them why it never completes.
        // See trickplayer-knowledge findings/F-018.
        val now = System.currentTimeMillis()
        val stored = settings.pendingPin?.takeIf { it.isLive(now) }

        val pinId: Long
        val code: String
        val expiresAtMs: Long
        if (stored != null) {
            pinId = stored.id
            code = stored.code
            expiresAtMs = stored.expiresAtMs
        } else {
            val minted = tv.createPin()
            pinId = minted.id
            code = minted.code
            expiresAtMs = now + minted.expiresInSeconds * 1000
            settings.pendingPin = Settings.PendingPin(pinId, code, expiresAtMs)
        }

        pendingPinId = pinId
        pendingPinExpiresAtMs = expiresAtMs
        _state.update { it.copy(step = Step.Linking(code), busy = false) }

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Poll until linked or the PIN actually expires — the expiry comes
            // from plex.tv's own `expiresIn`, not from counting attempts, so a
            // slow network cannot make a live code look dead or a dead one look
            // live. Every three seconds: brisk enough to feel immediate, slow
            // enough not to hammer plex.tv.
            while (System.currentTimeMillis() < expiresAtMs) {
                delay(3000)
                if (tryRedeem(pinId)) return@launch
            }
            settings.pendingPin = null
            _state.update {
                it.copy(error = "That code expired. Try again.", step = Step.SignIn)
            }
        }
    }

    /**
     * "I have authorised it" — redeem now instead of waiting for the next poll.
     *
     * Nothing may depend on a timer alone. A watch can doze, and the activity
     * can be stopped while the user is at another device typing the code; on
     * the way back the poll loop may be seconds away or gone entirely. An
     * explicit action is the escape hatch that makes the flow finish.
     */
    fun checkNow() = bg {
        val id = pendingPinId ?: return@bg
        if (!tryRedeem(id)) {
            val expired = System.currentTimeMillis() >= (pendingPinExpiresAtMs ?: 0L)
            _state.update {
                if (expired) {
                    settings.pendingPin = null
                    it.copy(error = "That code expired. Try again.", step = Step.SignIn, busy = false)
                } else {
                    it.copy(error = "Not linked yet — finish at plex.tv/link.", busy = false)
                }
            }
        }
    }

    /** @return true if the PIN has been linked and a token is now held. */
    private suspend fun tryRedeem(pinId: Long): Boolean {
        val t = try {
            withContext(Dispatchers.IO) { tv.checkPin(pinId) }
        } catch (e: Exception) { null } ?: return false
        token = t
        settings.pendingPin = null
        pendingPinId = null
        loadServers()
        return true
    }

    // ----------------------------------------------------------- discovery

    fun loadServers() = bg {
        val list = tv.servers(token!!)
        _state.update { it.copy(step = Step.Servers, servers = list, busy = false) }
    }

    /** Race this server's routes, then list its libraries. */
    fun chooseServer(s: PlexTv.Server) = bg {
        server = s
        val r = PlexDiscovery.pickRoute(s)
            ?: throw java.io.IOException("No route to ${s.name} from here")
        route = r.uri
        // Keep the losers. The winner here is nearly always the LAN address,
        // which stops existing the moment the watch leaves the house — and with
        // no alternatives stored there is nothing to fall back to.
        routes = (listOf(r.uri) + s.connections.map { it.uri }).distinct()
        val lib = PlexLibrary(r.uri, PlexClient(s.accessToken, allowInsecureDirect = true))
        library = lib
        val sections = lib.sections()
        _state.update { it.copy(step = Step.Libraries, sections = sections, busy = false) }
    }

    /** Jump straight to what the account is part-way through. */
    fun chooseOnDeck() = bg {
        val items = library!!.onDeck()
        _state.update { it.copy(step = Step.Items("On Deck"), shows = emptyList(), busy = false) }
        scan(items)
    }

    fun chooseSection(sec: PlexLibrary.Section) = bg {
        sectionKey = sec.key
        val lib = library!!
        if (sec.type == "show") {
            val shows = lib.shows(sec.key)
            _state.update { it.copy(step = Step.Shows, shows = shows, busy = false) }
        } else {
            // A movie library has no middle level; scan it directly.
            val movies = lib.movies(sec.key)
            _state.update { it.copy(step = Step.Items(sec.title), busy = false) }
            scan(movies)
        }
    }

    fun chooseShow(show: PlexLibrary.Item) = bg {
        val eps = library!!.episodes(show.itemId)
        _state.update { it.copy(step = Step.Items(show.title), busy = false) }
        scan(eps)
    }

    /**
     * Check eligibility one item at a time, publishing hits as they are found.
     *
     * Lazy and incremental on purpose. An item is only usable if it has both an
     * `sd` trick-play index **and** a subtitle stream with a non-null key —
     * most SRT streams are embedded and cannot be fetched separately — and that
     * takes a metadata request each. Measured: 23 ms per check, so a 157-episode
     * show is close to four seconds. The G2 app fetched the whole library up
     * front in batches of 20 and made you wait for all of it.
     */
    private fun scan(items: List<PlexLibrary.Item>) {
        scanJob?.cancel()
        _state.update { it.copy(playable = emptyList(), scanned = 0, toScan = items.size) }
        scanJob = viewModelScope.launch {
            val lib = library ?: return@launch
            for (item in items) {
                val p = try {
                    withContext(Dispatchers.IO) { lib.playable(item.itemId) }
                } catch (e: Exception) { null }
                _state.update { s ->
                    s.copy(
                        scanned = s.scanned + 1,
                        playable = if (p != null) s.playable + (item to p) else s.playable,
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------- choose

    fun choose(item: PlexLibrary.Item, play: PlexLibrary.Playable) {
        val s = server ?: return
        val uri = route ?: return
        settings.episode = Settings.Episode(
            server = uri,
            routes = routes.ifEmpty { listOf(uri) },
            token = s.accessToken,
            timelineRef = play.timelineRef,
            subtitleRef = play.subtitleRef,
            title = play.title.ifEmpty { item.title },
            skipSilent = true,
        )
        scanJob?.cancel()
        SceneState.ensurePrefetch(ctx)
        SceneState.requestUpdate(ctx)
        _state.update { it.copy(step = Step.Done(play.title.ifEmpty { item.title })) }
    }

    /**
     * Leave the confirmation screen and choose something else.
     *
     * Without this the app is a one-way trip: pick an episode, and the only way
     * to pick a different one is to force-stop it. Goes back to the library list
     * if a server is already connected, since re-racing routes to change episode
     * is pointless work.
     */
    fun pickAnother() {
        _state.update {
            it.copy(
                step = if (library != null) Step.Libraries else Step.Servers,
                playable = emptyList(),
                scanned = 0,
                toScan = 0,
                error = null,
            )
        }
    }

    fun back() {
        _state.update { s ->
            when (s.step) {
                is Step.Items -> s.copy(step = if (s.shows.isEmpty()) Step.Libraries else Step.Shows)
                Step.Shows -> s.copy(step = Step.Libraries)
                Step.Libraries -> s.copy(step = Step.Servers)
                else -> s
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        scanJob?.cancel()
    }

    private companion object {
        /** 3 s apart; plex.tv PINs last a few minutes. */
    }
}
