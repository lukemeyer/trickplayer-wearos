package com.lukemeyer.bif.tools

import com.lukemeyer.bif.core.plex.PlexClient
import com.lukemeyer.bif.core.plex.PlexDiscovery
import com.lukemeyer.bif.core.plex.PlexLibrary
import com.lukemeyer.bif.core.plex.PlexTv
import java.io.File
import java.util.Properties

/**
 * Exercises the Phase 5 sign-in and browsing code headlessly, before any UI
 * exists to hide behind — same reason `Pipeline.kt` exists for the content path.
 *
 *   ./gradlew :tools:runDiscover
 */
fun main() {
    val props = Properties().apply {
        val f = File("local.properties")
        if (!f.exists()) { System.err.println("no local.properties"); return }
        f.inputStream().use { load(it) }
    }
    fun p(k: String) = props.getProperty(k)?.trim().orEmpty()

    val clientId = p("plex.clientId").ifEmpty { "bif-watchface-dev-harness" }
    val tv = PlexTv(clientId)

    // ---------------------------------------------------------------- PIN
    // Minting a PIN touches no account — it is an unauthenticated endpoint that
    // returns a code. Nothing is linked unless somebody enters it.
    println("== PIN ==")
    try {
        val pin = tv.createPin()
        say("code", "${pin.code}   <- this is what the watch would show")
        say("length", "${pin.code.length} chars")
        say("enter at", "https://plex.tv/link")
        say("poll", "returns ${tv.checkPin(pin.id) ?: "null (nobody has linked it)"}")
    } catch (e: Exception) {
        say("FAILED", e.message ?: e.toString())
    }

    val token = p("plex.token")
    if (token.isEmpty()) { println("\nno plex.token; stopping before discovery"); return }

    // ---------------------------------------------------------- discovery
    println("\n== servers ==")
    val servers = tv.servers(token)
    servers.forEach { s ->
        say(s.name, "${s.connections.size} route(s), owned=${s.owned}")
        s.connections.forEach { c ->
            val kind = if (c.local) "local" else if (c.relay) "relay" else "remote"
            println("      %-7s %s".format(kind, c.uri.take(58)))
        }
    }
    val server = servers.firstOrNull() ?: run { println("no servers"); return }

    println("\n== racing routes ==")
    val t0 = System.currentTimeMillis()
    val route = PlexDiscovery.pickRoute(server)
    if (route == null) { println("  no route answered"); return }
    say("winner", (if (route.local) "local" else if (route.relay) "relay" else "remote") +
        "  ${route.millis} ms   (race took ${System.currentTimeMillis() - t0} ms)")

    // ------------------------------------------------------------ browse
    val plex = PlexClient(server.accessToken, allowInsecureDirect = true)
    val lib = PlexLibrary(route.uri, plex)

    println("\n== libraries ==")
    val sections = lib.sections()
    sections.forEach { say(it.title, it.type) }

    // Prefer a real TV library over DVR recordings, which never have sidecars.
    val shows = sections.firstOrNull { it.type == "show" && it.title.equals("TV", true) }
        ?: sections.firstOrNull { it.type == "show" }
        ?: run { println("no TV library"); return }
    println("\n== shows in ${shows.title} ==")
    val all = lib.shows(shows.key)
    say("count", all.size.toString())
    all.take(5).forEach { println("      ${it.title}") }

    val show = all.firstOrNull { it.title.contains("Futurama", true) } ?: all.firstOrNull() ?: return
    println("\n== eligibility in \"${show.title}\" ==")
    val eps = lib.episodes(show.itemId)
    say("episodes", eps.size.toString())

    // Lazily, one at a time — which is the point: this is what a watch can
    // afford, and the UI shows results as they arrive rather than blocking on
    // the whole library the way the G2 app's batches of 20 did.
    var usable = 0
    val t1 = System.currentTimeMillis()
    val hits = ArrayList<String>()
    eps.forEach { ep ->
        val play = lib.playable(ep.itemId)
        if (play != null) {
            usable++
            if (hits.size < 6) hits.add("      ${ep.subtitle} ${ep.title.take(34)}  part ${play.timelineRef}  subs ${play.subLanguage}")
        }
    }
    hits.forEach(::println)
    if (usable > hits.size) println("      … and ${usable - hits.size} more")
    println()
    say("usable", "$usable of ${eps.size}")
    say("cost", "${System.currentTimeMillis() - t1} ms for ${eps.size} checks " +
        "(${(System.currentTimeMillis() - t1) / eps.size} ms each)")
}

private fun say(k: String, v: String) = println("  %-22s %s".format(k, v))
