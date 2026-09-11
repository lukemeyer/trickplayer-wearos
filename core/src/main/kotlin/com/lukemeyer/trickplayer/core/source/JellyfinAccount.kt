package com.lukemeyer.trickplayer.core.source

import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient.Companion.arr
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient.Companion.enc
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient.Companion.int
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient.Companion.long
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient.Companion.obj
import com.lukemeyer.trickplayer.core.jellyfin.JellyfinClient.Companion.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Jellyfin as a [MediaAccount].
 *
 * The mirror of [PlexAccount], and deliberately not a variation on it. There is
 * no account service here, so the address **is** the identity: it must be known
 * before anything can be authenticated, and there is nothing to discover
 * afterwards. That is why the add-source flow is provider-ordered rather than a
 * shared sequence (UI.md §1).
 */
class JellyfinAccount(
    serverUrl: String,
    token: String? = null,
    private var userId: String = "",
    private val cropper: SheetCropper,
    /**
     * Carried back in from a saved source so [persist] round-trips.
     *
     * Without them a reopened account would re-identify itself by its URL, and
     * the next save would land beside the original rather than replacing it.
     */
    savedId: String? = null,
    savedName: String? = null,
) : MediaAccount {

    override val provider = "jellyfin"

    private val client = JellyfinClient(serverUrl, token)
    private var name: String = savedName ?: client.base
    private var id: String = savedId ?: client.base

    override fun capabilities() = Capabilities(
        // No account service: the address is typed, not discovered.
        needsAddressFirst = true,
        hasServerDiscovery = false,
        hasPlaylists = true,
        hasContinueWatching = true,
        // A thumbnail is a crop, not a file (F-038).
        hasFrameSizeHints = false,
        fetchGranularity = FetchGranularity.BATCH,
    )

    // ----------------------------------------------------------------- auth

    override fun beginAuth(resuming: Map<String, String>?): AuthAttempt {
        val secret = resuming?.get("secret")
        if (secret != null) {
            // Resume rather than re-mint, for the same reason as Plex (F-018).
            return AuthAttempt(
                code = resuming["code"].orEmpty(),
                enterAt = enterAt(),
                state = resuming,
                expiresAtMs = resuming["expiresAtMs"]?.toLongOrNull() ?: farFuture(),
            )
        }
        val qc = client.initiateQuickConnect()
        return AuthAttempt(
            code = qc.code,
            enterAt = enterAt(),
            state = mapOf("secret" to qc.secret, "code" to qc.code),
            // The server does not publish a lifetime, so expiry is detected
            // from the 404 below rather than predicted from a clock.
            expiresAtMs = farFuture(),
        )
    }

    override fun poll(attempt: AuthAttempt): AuthPoll {
        val secret = attempt.state["secret"] ?: return AuthPoll.EXPIRED
        // Null means the server has forgotten the request: it timed out. Same
        // meaning as a dead Plex PIN, and worth saying out loud rather than
        // spinning — an expired code looks exactly like an untyped one.
        val approved = client.quickConnectApproved(secret) ?: return AuthPoll.EXPIRED
        if (!approved) return AuthPoll.PENDING

        val (token, user) = client.redeemQuickConnect(secret)
        client.token = token
        userId = user
        name = client.serverName()
        id = client.serverId()
        return AuthPoll.OK
    }

    /** There is nothing to discover — the server is the address that was typed. */
    override fun listServers(): List<ServerRef> = listOf(
        ServerRef(
            id = id,
            name = name,
            routes = listOf(client.base),
            accessToken = client.token.orEmpty(),
        ),
    )

    override fun use(server: ServerRef) { /* nothing to choose */ }

    // --------------------------------------------------------------- browse

    private data object ResumeRef : BrowseRef
    private data object PlaylistsRef : BrowseRef
    private data class ViewRef(val id: String, val type: String) : BrowseRef
    private data class SeriesRef(val id: String) : BrowseRef
    private data class PlaylistRef(val id: String) : BrowseRef

    /**
     * The item itself rides along in the ref.
     *
     * Jellyfin returns the trickplay manifest and the stream list with the
     * listing, so eligibility here costs no second request — unlike Plex, where
     * it is one metadata fetch per item. The streaming scan is still built the
     * same way, because Plex needs it.
     */
    private data class ItemRef(val id: String, val raw: JsonObject?) : BrowseRef

    override fun listRoots(): List<Container> {
        val roots = mutableListOf(
            Container(ResumeRef, "Continue watching"),
            Container(PlaylistsRef, "Playlists"),
        )
        for (v in client.views(userId)) {
            val type = v.str("CollectionType")
            if (type.isNotEmpty() && type != "movies" && type != "tvshows") continue
            roots += Container(ViewRef(v.str("Id"), type), v.str("Name"))
        }
        return roots
    }

    override fun listChildren(ref: BrowseRef): Pair<List<Container>, List<BrowseItem>> =
        when (ref) {
            is ResumeRef -> emptyList<Container>() to client.resume(userId).map(::toItem)

            is PlaylistsRef -> client.items(
                "userId=${enc(userId)}&Recursive=true" +
                    "&IncludeItemTypes=Playlist&SortBy=SortName",
            ).map {
                Container(
                    PlaylistRef(it.str("Id")),
                    it.str("Name"),
                    it.int("ChildCount").let { n -> if (n > 0) "$n items" else "" },
                )
            } to emptyList()

            is PlaylistRef ->
                emptyList<Container>() to client.playlistItems(userId, ref.id).map(::toItem)

            is ViewRef -> {
                // A TV view lists series; a film view lists films. The same
                // discovered depth as Plex, arrived at from the collection type
                // rather than assumed.
                val isShows = ref.type == "tvshows"
                val rows = client.items(
                    "userId=${enc(userId)}&ParentId=${enc(ref.id)}&Recursive=true" +
                        "&SortBy=SortName&IncludeItemTypes=" +
                        (if (isShows) "Series" else "Movie") +
                        (if (isShows) "" else "&Fields=${enc(client.itemFields)}"),
                )
                if (isShows) {
                    rows.map { Container(SeriesRef(it.str("Id")), it.str("Name")) } to emptyList()
                } else {
                    emptyList<Container>() to rows.map(::toItem)
                }
            }

            is SeriesRef -> emptyList<Container>() to client.items(
                "userId=${enc(userId)}&ParentId=${enc(ref.id)}&Recursive=true" +
                    "&IncludeItemTypes=Episode&SortBy=SortName&Fields=${enc(client.itemFields)}",
            ).map(::toItem)

            else -> emptyList<Container>() to emptyList()
        }

    private fun toItem(it: JsonObject): BrowseItem {
        val episode = it.str("Type") == "Episode"
        val title = if (episode) it.str("SeriesName") else it.str("Name")
        val sub = if (episode) {
            val s = it.int("ParentIndexNumber").toString().padStart(2, '0')
            val e = it.int("IndexNumber").toString().padStart(2, '0')
            "S${s}E$e ${it.str("Name")}"
        } else {
            it.str("ProductionYear")
        }
        return BrowseItem(ItemRef(it.str("Id"), it), title, sub)
    }

    /**
     * **Deliberately NOT Plex's rule.** Requiring an external subtitle file
     * would hide most of a Jellyfin library, because embedded tracks are
     * converted on demand (F-037). What this needs is trickplay generated at
     * some width, and a subtitle stream of any kind.
     */
    override fun resolvePlayable(item: BrowseItem): Playable? {
        val ref = item.ref as? ItemRef ?: return null
        val it = ref.raw ?: client.items(
            "userId=${enc(userId)}&Ids=${enc(ref.id)}&Fields=${enc(client.itemFields)}",
        ).firstOrNull() ?: return null

        val trickplay = it.obj("Trickplay")?.takeIf { t -> t.isNotEmpty() } ?: return null
        val media = it.arr("MediaSources").firstOrNull() ?: return null
        val sub = media.arr("MediaStreams").firstOrNull { s -> s.str("Type") == "Subtitle" }
            ?: return null

        // Widest available: more pixels per thumbnail, and the sheet count is
        // the same either way, so there is nothing to trade.
        val (_, byWidth) = trickplay.entries.first()
        val widths = byWidth.jsonObject
        val width = widths.keys.mapNotNull { w -> w.toIntOrNull() }.maxOrNull() ?: return null
        val g = widths[width.toString()]?.jsonObject ?: return null

        return Playable(
            title = listOf(item.title, item.subtitle).filter { s -> s.isNotEmpty() }
                .joinToString(" — "),
            durationMs = it.long("RunTimeTicks").takeIf { t -> t > 0 }?.div(10_000),
            timelineRef = JellyfinSource.JellyfinTimelineRef(
                itemId = it.str("Id"),
                width = width,
                geometry = JellyfinSource.Geometry(
                    tileWidth = g.int("TileWidth"),
                    tileHeight = g.int("TileHeight"),
                    thumbWidth = g.int("Width"),
                    thumbHeight = g.int("Height"),
                    intervalMs = g.long("Interval"),
                    thumbnailCount = g.int("ThumbnailCount"),
                ),
            ),
            subtitleRef = JellyfinSource.JellyfinSubtitleRef(
                itemId = it.str("Id"),
                mediaSourceId = media.str("Id"),
                index = sub.int("Index"),
            ),
        )
    }

    override fun openSource(playable: Playable): MediaSource = JellyfinSource(client, cropper)

    /**
     * The address is saved with the credential because here the address IS the
     * identity: there is no account service to rebuild it from, and asking for
     * an IP again on a watch is not a recovery path (UI.md §1).
     */
    override fun persist(): Map<String, String> = buildMap {
        put("provider", "jellyfin")
        put("id", id)
        put("name", name)
        put("server", client.base)
        put("token", client.token.orEmpty())
        put("userId", userId)
    }

    private fun enterAt() = "${client.base} — Quick Connect"

    /** Jellyfin publishes no lifetime; the 404 on poll is the real signal. */
    private fun farFuture() = System.currentTimeMillis() + 30L * 60 * 1000
}
