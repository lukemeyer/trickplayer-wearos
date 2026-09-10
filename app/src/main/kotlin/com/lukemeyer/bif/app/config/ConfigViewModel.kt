package com.lukemeyer.bif.app.config

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lukemeyer.bif.app.SceneState
import com.lukemeyer.bif.core.source.AuthAttempt
import com.lukemeyer.bif.core.source.AuthPoll
import com.lukemeyer.bif.core.source.BrowseItem
import com.lukemeyer.bif.core.source.BrowseRef
import com.lukemeyer.bif.core.source.Container
import com.lukemeyer.bif.core.source.JellyfinAccount
import com.lukemeyer.bif.core.source.JellyfinSource
import com.lukemeyer.bif.core.source.MediaAccount
import com.lukemeyer.bif.core.source.Playable
import com.lukemeyer.bif.core.source.PlexAccount
import com.lukemeyer.bif.core.source.PlexSource
import com.lukemeyer.bif.core.source.ServerRef
import com.lukemeyer.bif.data.AndroidSheetCropper
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
 * Drives sign-in, discovery and browsing — for whichever provider.
 *
 * **It names a provider in exactly one place**, [accountFor], and everything
 * else talks to [MediaAccount]. Before this it talked to `PlexTv`,
 * `PlexDiscovery` and `PlexLibrary` directly, which is how a second provider
 * becomes a rewrite of the flow rather than a new file. See
 * `trickplayer-knowledge/UI.md` §5.
 *
 * Every step reports partial results as they arrive rather than blocking on a
 * whole library — which matters most for eligibility, where a full show is 157
 * requests on Plex and roughly four seconds.
 */
class ConfigViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Step {
        /** Only ever shown with two or more saved. One source is not a screen. */
        data object Sources : Step
        data object AddProvider : Step
        /** Jellyfin only: the address IS the identity, so it comes before auth. */
        data object AddAddress : Step
        data class Linking(val code: String, val enterAt: String) : Step
        /** Plex only, and only with more than one. */
        data object Servers : Step
        data object Browse : Step
        data class Items(val title: String) : Step
        data class Done(val title: String) : Step
    }

    data class State(
        val step: Step = Step.AddProvider,
        val busy: Boolean = false,
        val error: String? = null,
        val sources: List<Map<String, String>> = emptyList(),
        val servers: List<ServerRef> = emptyList(),
        val roots: List<Container> = emptyList(),
        /** The source being browsed — not [sources].first(), once there are two. */
        val sourceName: String = "",
        /** Sub-containers at this level: shows, playlists. */
        val containers: List<Container> = emptyList(),
        /** Items confirmed usable so far; grows while [scanned] climbs. */
        val playable: List<Pair<BrowseItem, Playable>> = emptyList(),
        val scanned: Int = 0,
        val toScan: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val ctx: Context get() = getApplication()
    private val settings = Settings(ctx)

    /**
     * Stable per install, and it must be: plex.tv ties both the PIN and the
     * issued token to this identifier, so regenerating it invalidates sign-in.
     */
    private val clientId: String = ctx
        .getSharedPreferences("bif.client", Context.MODE_PRIVATE)
        .let { p ->
            p.getString("id", null) ?: UUID.randomUUID().toString()
                .also { p.edit().putString("id", it).apply() }
        }

    private var account: MediaAccount? = null
    private var attempt: AuthAttempt? = null
    private var server: ServerRef? = null
    /** Containers walked into, deepest last. Depth is discovered, not assumed. */
    private var stack: MutableList<Container> = mutableListOf()
    private var pollJob: Job? = null
    private var scanJob: Job? = null

    /** The one place a provider is named. */
    private fun accountFor(record: Map<String, String>): MediaAccount =
        if (record["provider"] == "jellyfin") {
            JellyfinAccount(
                serverUrl = record["server"].orEmpty(),
                token = record["token"],
                userId = record["userId"].orEmpty(),
                cropper = AndroidSheetCropper(),
                savedId = record["id"],
                savedName = record["name"],
            )
        } else {
            PlexAccount(
                clientId = clientId,
                accountToken = record["accountToken"],
                server = record["id"]?.let {
                    ServerRef(
                        id = it,
                        name = record["name"].orEmpty(),
                        routes = record["routes"]?.split('\n')?.filter(String::isNotBlank)
                            ?: listOfNotNull(record["server"]),
                        accessToken = record["token"].orEmpty(),
                    )
                },
            )
        }

    init {
        val saved = settings.sources
        _state.update { it.copy(sources = saved) }
        when {
            // A sign-in interrupted by the user walking off to type the code
            // resumes rather than restarting (F-018).
            settings.pendingAuth != null -> resumePendingAuth()
            saved.isEmpty() -> Unit                       // AddProvider, the default
            else -> {
                val last = settings.lastSourceId
                val rec = saved.firstOrNull { "${it["provider"]}:${it["id"]}" == last }
                    ?: saved.first()
                openSource(rec)
            }
        }
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

    // ------------------------------------------------------------- sources

    fun showSources() {
        _state.update { it.copy(step = Step.Sources, sources = settings.sources, error = null) }
    }

    fun addSource() {
        settings.pendingAuth = null
        _state.update { it.copy(step = Step.AddProvider, error = null) }
    }

    fun openSource(record: Map<String, String>) = bg {
        account = accountFor(record)
        settings.lastSourceId = "${record["provider"]}:${record["id"]}"
        // A saved Plex source has a server but no live route yet; racing it here
        // is what makes the rest of the session work (F-016).
        account!!.listServers().firstOrNull { it.id == record["id"] }
            ?.let { s -> server = s; account!!.use(s) }
            ?: run {
                val only = account!!.listServers().firstOrNull()
                    ?: throw java.io.IOException("that server is no longer on the account")
                server = only
                account!!.use(only)
            }
        stack.clear()
        loadRoots()
    }

    // ------------------------------------------------------------- sign in

    /** The only place a provider *type* is named to the user. */
    fun chooseProvider(provider: String) {
        if (provider == "jellyfin") {
            _state.update { it.copy(step = Step.AddAddress, error = null) }
        } else {
            account = PlexAccount(clientId)
            beginAuth(null)
        }
    }

    /** The one field on this whole watch, and only Jellyfin needs it. */
    fun setAddress(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val url = if (trimmed.startsWith("http")) trimmed else "http://$trimmed"
        account = JellyfinAccount(serverUrl = url, cropper = AndroidSheetCropper())
        beginAuth(null)
    }

    private fun resumePendingAuth() {
        val pending = settings.pendingAuth ?: return
        account = accountFor(pending)
        beginAuth(pending)
    }

    /**
     * Code-and-poll, one path for both providers.
     *
     * The flows are the same shape — mint, show, poll, exchange — which is what
     * lets F-018's rules transfer at all. Only the place the code is entered
     * differs, and that string comes from the provider because the user cannot
     * guess it.
     */
    private fun beginAuth(resuming: Map<String, String>?) = bg {
        val acc = account ?: return@bg
        val a = acc.beginAuth(resuming)
        attempt = a
        // Persisted because the point of a short code is that it is typed on a
        // DIFFERENT device: the user walks away and this activity may not
        // survive it. A fresh code on the way back strands them on one nobody
        // is polling, with nothing on screen to say why.
        settings.pendingAuth = a.state + mapOf(
            "provider" to acc.provider,
            "server" to (acc.persist()["server"] ?: ""),
        )
        _state.update { it.copy(step = Step.Linking(a.code, a.enterAt), busy = false) }

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(3000)
                if (redeem()) return@launch
                if (_state.value.step !is Step.Linking) return@launch
            }
        }
    }

    /**
     * "I have entered it" — check now instead of waiting for the next poll.
     *
     * Nothing may depend on a timer alone. A watch dozes, and this activity can
     * be stopped while the user is at another device typing; on the way back the
     * poll loop may be seconds away or gone entirely.
     */
    fun checkNow() = bg {
        if (!redeem()) {
            _state.update { it.copy(error = "Not linked yet.", busy = false) }
        }
    }

    /** @return true once a credential is held and the flow has moved on. */
    private suspend fun redeem(): Boolean {
        val acc = account ?: return false
        val a = attempt ?: return false
        val result = try {
            withContext(Dispatchers.IO) { acc.poll(a) }
        } catch (e: Exception) {
            return false
        }
        when (result) {
            AuthPoll.PENDING -> return false

            // Said out loud rather than left spinning: an expired code looks
            // exactly like one the user has not got round to typing.
            AuthPoll.EXPIRED -> {
                settings.pendingAuth = null
                attempt = null
                _state.update {
                    it.copy(
                        error = "That code expired. Try again.",
                        step = Step.AddProvider,
                        busy = false,
                    )
                }
                return true
            }

            AuthPoll.OK -> {
                settings.pendingAuth = null
                attempt = null
                afterAuth()
                return true
            }
        }
    }

    private suspend fun afterAuth() {
        val acc = account ?: return
        val servers = withContext(Dispatchers.IO) { acc.listServers() }
        // Provider-supplied, not a fixed step: Jellyfin returns the one server
        // it was given, so there is nothing to choose and nothing to show.
        if (servers.size == 1 || !acc.capabilities().hasServerDiscovery) {
            servers.firstOrNull()?.let { chooseServerNow(it) }
                ?: fail(java.io.IOException("no servers on this account"))
            return
        }
        _state.update { it.copy(step = Step.Servers, servers = servers, busy = false) }
    }

    fun chooseServer(s: ServerRef) = bg { chooseServerNow(s) }

    private suspend fun chooseServerNow(s: ServerRef) {
        val acc = account ?: return
        server = s
        withContext(Dispatchers.IO) { acc.use(s) }
        settings.saveSource(acc.persist())
        stack.clear()
        loadRoots()
    }

    // -------------------------------------------------------------- browse

    private suspend fun loadRoots() {
        val acc = account ?: return
        val roots = withContext(Dispatchers.IO) { acc.listRoots() }
        _state.update {
            it.copy(
                step = Step.Browse,
                roots = roots,
                sources = settings.sources,
                sourceName = server?.name ?: acc.persist()["name"].orEmpty(),
                busy = false,
            )
        }
    }

    fun openContainer(c: Container) = bg {
        stack.add(c)
        showLevel()
    }

    /**
     * One level, whatever that level is.
     *
     * Containers and items are shown by the same screen because depth is
     * discovered: a show is a container, a film is an item, a playlist is a
     * container of items. The old `library -> show -> episode` walk was a
     * *television* structure that films and playlists had to be bent to fit.
     */
    private suspend fun showLevel() {
        val acc = account ?: return
        val here = stack.lastOrNull() ?: return
        val (containers, items) = withContext(Dispatchers.IO) { acc.listChildren(here.ref) }
        _state.update {
            it.copy(
                step = Step.Items(here.title),
                containers = containers,
                playable = emptyList(),
                scanned = 0,
                toScan = items.size,
                busy = false,
            )
        }
        scan(items)
    }

    /**
     * Check eligibility one item at a time, publishing hits as they are found.
     *
     * Lazy and incremental on purpose. On Plex it is a metadata request each —
     * measured at 23 ms, so a 157-episode show is close to four seconds — and
     * an item is only usable if it has both halves. Jellyfin answers for free
     * because the listing already carried the fields, but the shape is the same
     * and the provider is the one that knows.
     */
    private fun scan(items: List<BrowseItem>) {
        scanJob?.cancel()
        if (items.isEmpty()) return
        scanJob = viewModelScope.launch {
            val acc = account ?: return@launch
            for (item in items) {
                val p = try {
                    withContext(Dispatchers.IO) { acc.resolvePlayable(item) }
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

    fun choose(item: BrowseItem, play: Playable) {
        val acc = account ?: return
        val s = server ?: return
        val saved = acc.persist()
        val route = saved["server"] ?: s.routes.firstOrNull() ?: return

        // Unwrapping the refs is the one thing this layer is allowed to do with
        // them, and only to write them down: a config has to survive the
        // process, and the provider is the one that says what it needs back.
        val episode = when (val ref = play.timelineRef) {
            is PlexSource.PlexTimelineRef -> Settings.Episode(
                server = route,
                routes = s.routes.ifEmpty { listOf(route) },
                token = s.accessToken,
                timelineRef = ref.partId,
                subtitleRef = (play.subtitleRef as? PlexSource.PlexSubtitleRef)?.key.orEmpty(),
                title = play.title.ifEmpty { item.title },
                skipSilent = true,
                provider = Settings.Provider.PLEX,
            )

            is JellyfinSource.JellyfinTimelineRef -> {
                val sub = play.subtitleRef as? JellyfinSource.JellyfinSubtitleRef
                Settings.Episode(
                    server = route,
                    routes = listOf(route),
                    token = saved["token"].orEmpty(),
                    timelineRef = -1L,
                    subtitleRef = "",
                    title = play.title.ifEmpty { item.title },
                    skipSilent = true,
                    provider = Settings.Provider.JELLYFIN,
                    trickplay = Settings.Trickplay(
                        itemId = ref.itemId,
                        mediaSourceId = sub?.mediaSourceId.orEmpty(),
                        width = ref.width,
                        tileWidth = ref.geometry.tileWidth,
                        tileHeight = ref.geometry.tileHeight,
                        thumbWidth = ref.geometry.thumbWidth,
                        thumbHeight = ref.geometry.thumbHeight,
                        intervalMs = ref.geometry.intervalMs,
                        thumbnailCount = ref.geometry.thumbnailCount,
                        subtitleIndex = sub?.index ?: -1,
                    ),
                )
            }

            else -> return
        }

        settings.episode = episode
        scanJob?.cancel()
        SceneState.ensurePrefetch(ctx)
        SceneState.requestUpdate(ctx)
        _state.update { it.copy(step = Step.Done(episode.title)) }
    }

    /**
     * Leave the confirmation screen and choose something else.
     *
     * Without this the app is a one-way trip: pick an episode, and the only way
     * to pick a different one is to force-stop it.
     */
    fun pickAnother() = bg {
        stack.clear()
        if (account != null) loadRoots()
        else _state.update { it.copy(step = Step.AddProvider, busy = false) }
    }

    fun back() = bg {
        when (_state.value.step) {
            is Step.Items -> {
                scanJob?.cancel()
                stack.removeLastOrNull()
                if (stack.isEmpty()) loadRoots() else showLevel()
            }
            // With one source the sources step does not exist to go back to.
            Step.Browse ->
                if (settings.sources.size > 1) showSources()
                else _state.update { it.copy(busy = false) }
            Step.Servers, Step.AddAddress, is Step.Linking -> {
                // Abandoning a sign-in abandons the code with it; leaving it
                // persisted would resume a flow the user just backed out of.
                pollJob?.cancel()
                settings.pendingAuth = null
                attempt = null
                _state.update { it.copy(step = Step.AddProvider, busy = false, error = null) }
            }
            else -> _state.update { it.copy(busy = false) }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        scanJob?.cancel()
    }
}
