package com.lukemeyer.bif.core.source

import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.plex.PlexDiscovery
import com.lukemeyer.bif.core.plex.PlexLibrary
import com.lukemeyer.bif.core.plex.PlexTv

/**
 * Plex as a [MediaAccount].
 *
 * A thin arrangement of the classes that were already here — [PlexTv],
 * [PlexDiscovery], [PlexLibrary] — behind the shared interface, rather than a
 * rewrite of any of them. What it adds is the shape: before this, the config
 * ViewModel talked to all three directly, which is exactly how a second
 * provider becomes a rewrite of the UI instead of a new file.
 */
class PlexAccount(
    private val clientId: String,
    private var accountToken: String? = null,
    private var server: ServerRef? = null,
) : MediaAccount {

    override val provider = "plex"

    private val tv = PlexTv(clientId, product = "Trickplayer")
    private var library: PlexLibrary? = null
    private var activeRoute: String? = null

    override fun capabilities() = Capabilities(
        needsAddressFirst = false,          // plex.tv authenticates first
        hasServerDiscovery = true,
        hasPlaylists = true,
        hasContinueWatching = true,
        hasFrameSizeHints = true,
        fetchGranularity = FetchGranularity.FRAME,
    )

    // ----------------------------------------------------------------- auth

    override fun beginAuth(resuming: Map<String, String>?): AuthAttempt {
        // Resume rather than re-mint. The point of a short code is that it is
        // typed on a *different* device, so the user walks away and this
        // activity may not survive it; a fresh code silently invalidates the
        // one they are looking at, and nothing on screen says why (F-018).
        val resumedId = resuming?.get("id")?.toLongOrNull()
        val resumedExpiry = resuming?.get("expiresAtMs")?.toLongOrNull() ?: 0L
        if (resumedId != null && System.currentTimeMillis() < resumedExpiry) {
            return AuthAttempt(
                code = resuming["code"].orEmpty(),
                enterAt = ENTER_AT,
                state = resuming,
                expiresAtMs = resumedExpiry,
            )
        }

        val pin = tv.createPin()
        val expiresAt = System.currentTimeMillis() + pin.expiresInSeconds * 1000
        return AuthAttempt(
            code = pin.code,
            enterAt = ENTER_AT,
            state = mapOf(
                "id" to pin.id.toString(),
                "code" to pin.code,
                "expiresAtMs" to expiresAt.toString(),
            ),
            expiresAtMs = expiresAt,
        )
    }

    override fun poll(attempt: AuthAttempt): AuthPoll {
        val id = attempt.state["id"]?.toLongOrNull() ?: return AuthPoll.EXPIRED
        val token = runCatching { tv.checkPin(id) }.getOrNull()
        if (token != null) {
            accountToken = token
            return AuthPoll.OK
        }
        // Expiry from the server's own lifetime, never from a poll count: a
        // slow network must not make a live code look dead.
        return if (System.currentTimeMillis() >= attempt.expiresAtMs) {
            AuthPoll.EXPIRED
        } else {
            AuthPoll.PENDING
        }
    }

    // -------------------------------------------------------------- servers

    override fun listServers(): List<ServerRef> =
        tv.servers(accountToken ?: error("not signed in")).map { s ->
            ServerRef(
                id = s.clientIdentifier,
                name = s.name,
                owner = if (s.owned) null else "shared",
                // EVERY route, not just the winner: F-016 re-races when the
                // network changes, which it cannot do from one saved URL.
                routes = s.connections.map { it.uri },
                // Server-specific, not the account token (F-020).
                accessToken = s.accessToken,
            )
        }

    override fun use(server: ServerRef) {
        this.server = server
        // Race, don't probe in order (F-016). PlexDiscovery wants the plex.tv
        // shape, so rebuild just enough of it for the race.
        val tvServer = PlexTv.Server(
            name = server.name,
            clientIdentifier = server.id,
            accessToken = server.accessToken,
            owned = server.owner == null,
            connections = server.routes.map {
                PlexTv.Connection(uri = it, local = false, relay = false, address = "")
            },
        )
        val winner = PlexDiscovery.pickRoute(tvServer)?.uri ?: server.routes.firstOrNull()
        ?: throw java.io.IOException("No route to ${server.name} from here")
        activeRoute = winner
        library = PlexLibrary(winner, PlexClient(server.accessToken, allowInsecureDirect = true))
    }

    // --------------------------------------------------------------- browse

    private data class SectionRef(val key: String, val type: String) : BrowseRef
    private data class ShowRef(val ratingKey: String) : BrowseRef
    private data class PlaylistRef(val ratingKey: String) : BrowseRef
    private data object OnDeckRef : BrowseRef
    private data object PlaylistsRef : BrowseRef
    private data class ItemRef(val ratingKey: String) : BrowseRef

    override fun listRoots(): List<Container> {
        val lib = library ?: error("no server chosen")
        // Continue watching first, and it is not a nicety: the test account has
        // 357 shows, roughly forty-four swipes to the middle of the alphabet
        // (F-019).
        return listOf(
            Container(OnDeckRef, "Continue watching"),
            Container(PlaylistsRef, "Playlists"),
        ) + lib.sections().map { Container(SectionRef(it.key, it.type), it.title) }
    }

    override fun listChildren(ref: BrowseRef): Pair<List<Container>, List<BrowseItem>> {
        val lib = library ?: error("no server chosen")
        return when (ref) {
            is OnDeckRef -> emptyList<Container>() to lib.onDeck().map(::toItem)
            is PlaylistsRef -> lib.playlists()
                .map { Container(PlaylistRef(it.itemId), it.title, it.subtitle) } to emptyList()
            is PlaylistRef -> emptyList<Container>() to
                lib.playlistItems(ref.ratingKey).map(::toItem)
            is SectionRef ->
                if (ref.type == "show") {
                    lib.shows(ref.key).map { Container(ShowRef(it.itemId), it.title) } to emptyList()
                } else {
                    emptyList<Container>() to lib.movies(ref.key).map(::toItem)
                }
            is ShowRef -> emptyList<Container>() to lib.episodes(ref.ratingKey).map(::toItem)
            else -> emptyList<Container>() to emptyList()
        }
    }

    private fun toItem(i: PlexLibrary.Item) = BrowseItem(ItemRef(i.itemId), i.title, i.subtitle)

    /**
     * **Plex's rule, and only Plex's.** An item needs an `sd` trick-play index
     * AND a subtitle stream with a non-null key, because most SRT streams Plex
     * reports are embedded and cannot be fetched separately (F-014). Jellyfin
     * converts embedded tracks on demand and must not be asked this (F-037).
     */
    override fun resolvePlayable(item: BrowseItem): Playable? {
        val lib = library ?: return null
        val ref = item.ref as? ItemRef ?: return null
        val p = lib.playable(ref.ratingKey) ?: return null
        return Playable(
            title = p.title.ifEmpty { item.title },
            durationMs = p.durationMs,
            timelineRef = PlexSource.PlexTimelineRef(p.timelineRef),
            subtitleRef = PlexSource.PlexSubtitleRef(p.subtitleRef),
        )
    }

    override fun openSource(playable: Playable): MediaSource {
        val s = server ?: error("no server chosen")
        val route = activeRoute ?: error("no route")
        return PlexSource(PlexClient(s.accessToken, allowInsecureDirect = true), route)
    }

    /** Every route, so F-016 can re-race after the watch changes network. */
    override fun persist(): Map<String, String> = buildMap {
        put("provider", "plex")
        accountToken?.let { put("accountToken", it) }
        server?.let {
            put("id", it.id)
            put("name", it.name)
            put("token", it.accessToken)
            put("routes", it.routes.joinToString("\n"))
        }
        activeRoute?.let { put("server", it) }
    }

    private companion object {
        const val ENTER_AT = "plex.tv/link"
    }
}
