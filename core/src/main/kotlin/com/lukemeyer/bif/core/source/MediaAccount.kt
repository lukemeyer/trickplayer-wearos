package com.lukemeyer.bif.core.source

/**
 * The ACCOUNT seam: signing in, and finding something to play.
 *
 * [MediaSource] is the other half — one item, already chosen. This half is
 * everything before that, and it exists for the same reason: the two providers'
 * sign-in flows are genuinely different shapes, and a flow written against
 * either one of them hard-codes it. See `trickplayer-knowledge/UI.md` §5.
 *
 * The shape is the one G2 arrived at first and this mirrors deliberately —
 * three platforms drifting apart by re-deriving the same interface is what
 * `PLAN.md` exists to stop.
 *
 * ```
 *   capabilities()        the same object MediaSource declares
 *   beginAuth(resuming)   code-and-poll, both providers (F-018)
 *   listServers()         Plex only; Jellyfin returns the one it was given
 *   use(server)           fixes the route later calls use
 *   listRoots()           continue watching, playlists, libraries
 *   listChildren(ref)     recursive; no assumed depth
 *   resolvePlayable(item) the ONLY eligibility signal
 *   openSource(playable)  hands back the item-scoped half
 * ```
 */

/** Where a container or item lives. Opaque outside the provider that made it. */
interface BrowseRef

/** A thing you can open. Might hold items, containers, or both. */
data class Container(
    val ref: BrowseRef,
    val title: String,
    val subtitle: String = "",
)

/**
 * A thing you might be able to play — **might**.
 *
 * Eligibility is not decided here and cannot be read off this. It is one
 * question to the provider, per item, and the answer is [Playable] or null.
 */
data class BrowseItem(
    val ref: BrowseRef,
    val title: String,
    val subtitle: String = "",
)

/**
 * A code the user types somewhere else, and a way to find out when they have.
 *
 * One type for both providers because F-018 found the flows are the same shape:
 * mint a short code, show it, poll, exchange. What differs is only WHERE the
 * code is entered — which the user cannot guess, so [enterAt] is a string the
 * provider writes.
 *
 * @param state what re-attaches to this attempt after the activity dies. The
 *   whole point of a short code is that it is typed on another device, so the
 *   user walks away mid-flow; minting a fresh one on the way back strands them
 *   on a code nobody is polling.
 */
data class AuthAttempt(
    val code: String,
    val enterAt: String,
    val state: Map<String, String>,
    val expiresAtMs: Long,
)

/** The three answers a poll can give. "Expired" is not a kind of "pending". */
enum class AuthPoll { PENDING, OK, EXPIRED }

/** One server, and every route known to reach it. */
data class ServerRef(
    val id: String,
    val name: String,
    val owner: String? = null,
    /** Best first. Kept whole so F-016 can re-race when the network changes. */
    val routes: List<String>,
    /** Server-specific where the provider has one — prefer it (F-020). */
    val accessToken: String,
)

interface MediaAccount {

    val provider: String

    fun capabilities(): Capabilities

    /** @param resuming a previous [AuthAttempt.state], or null to mint a code. */
    fun beginAuth(resuming: Map<String, String>? = null): AuthAttempt

    /** @return [AuthPoll.OK] once a credential is held. */
    fun poll(attempt: AuthAttempt): AuthPoll

    /** Jellyfin returns exactly one: the address the user typed. */
    fun listServers(): List<ServerRef>

    /** Fix the route everything below will use. */
    fun use(server: ServerRef)

    /** Continue watching, playlists, libraries — the same three everywhere. */
    fun listRoots(): List<Container>

    /**
     * One level down, whatever that level is.
     *
     * Depth is discovered rather than assumed: a show is a container, a film is
     * an item, a playlist is a container of items. Hard-coding
     * `library -> show -> episode` bakes in a *television* structure that films,
     * playlists and collections all have to be bent to fit.
     */
    fun listChildren(ref: BrowseRef): Pair<List<Container>, List<BrowseItem>>

    /**
     * Whether this item is usable, and if so how to fetch it.
     *
     * **Returning null is the only eligibility signal there is.** Plex needs the
     * non-null-subtitle-key filter and Jellyfin must not have it (F-037), so
     * shared code never asks the question itself — it asks the account.
     */
    fun resolvePlayable(item: BrowseItem): Playable?

    /** The item-scoped half of the seam. Only a provider constructs a source. */
    fun openSource(playable: Playable): MediaSource

    /** What re-creates this account after the process dies. */
    fun persist(): Map<String, String>
}
