package com.lukemeyer.trickplayer.tools

import com.lukemeyer.trickplayer.core.scene.Episode
import com.lukemeyer.trickplayer.core.scene.SceneResolver
import com.lukemeyer.trickplayer.core.source.BrowseItem
import com.lukemeyer.trickplayer.core.source.Container
import com.lukemeyer.trickplayer.core.source.JellyfinAccount
import com.lukemeyer.trickplayer.core.source.SheetCropper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

/**
 * Runs the shipping Jellyfin path against a real server, headlessly.
 *
 * The same trick [Pipeline] uses for Plex, and the reason `:core` is kept free
 * of Android imports: this exercises the *actual shipping classes* —
 * [JellyfinAccount], `JellyfinSource`, the scene policy — with no watch, no
 * emulator and no phone attached. What it cannot check is the Android
 * [com.lukemeyer.trickplayer.data.AndroidSheetCropper]; everything above that is the
 * code that ships.
 *
 * Credentials come from local.properties, which is gitignored. Nothing is
 * committed and the token is never printed.
 *
 *   ./gradlew :tools:runJellyfin
 */
fun main() {
    val props = Properties().apply {
        val f = File("local.properties")
        if (!f.exists()) {
            System.err.println("local.properties not found; need jellyfin.server / jellyfin.token / jellyfin.userId")
            return
        }
        f.inputStream().use { load(it) }
    }
    fun p(k: String) = props.getProperty(k)?.trim().orEmpty()

    val server = p("jellyfin.server")
    val token = p("jellyfin.token")
    val userId = p("jellyfin.userId")
    if (server.isEmpty() || token.isEmpty() || userId.isEmpty()) {
        System.err.println("local.properties is missing jellyfin.server / jellyfin.token / jellyfin.userId")
        return
    }

    val account = JellyfinAccount(server, token, userId, ImageIoCropper)
    say("server", server)
    say("capabilities", account.capabilities().toString())

    // --- roots: the same three as every other platform (UI.md §2) -----------
    val roots = account.listRoots()
    say("roots", roots.joinToString(", ") { it.title })

    // --- find something playable, streaming as F-015 requires ---------------
    var found: Pair<BrowseItem, com.lukemeyer.trickplayer.core.source.Playable>? = null
    for (root in roots) {
        val (containers, items) = account.listChildren(root.ref)
        say("  ${root.title}", "${containers.size} container(s), ${items.size} item(s)")
        found = found ?: scanFor(account, items)
        if (found == null) {
            for (c: Container in containers.take(3)) {
                val (_, deeper) = account.listChildren(c.ref)
                found = scanFor(account, deeper)
                if (found != null) break
            }
        }
        if (found != null) break
    }
    val (item, playable) = found ?: run {
        System.err.println("nothing playable on this server")
        return
    }
    say("chose", playable.title.ifEmpty { item.title })

    // --- the seam, from here down nothing knows this is Jellyfin -----------
    val source = account.openSource(playable)
    val caps = source.capabilities()

    val t0 = System.currentTimeMillis()
    val frames = source.timeline(playable)
    say("timeline", "${frames.size} frames in ${System.currentTimeMillis() - t0} ms " +
        "(no network: it is geometry)")
    say("sizeHints", frames.firstOrNull()?.sizeHint?.toString()
        ?: "null — the honest answer, so F-007 and F-036 are unavailable")

    val cues = source.cues(playable)
    say("cues", "${cues.size}")

    val episode = Episode(
        index = frames,
        cues = cues,
        durationMs = frames.lastOrNull()?.tsMs ?: 0L,
        skipSilent = true,
        hasFrameSizeHints = caps.hasFrameSizeHints,
    )
    say("scenes", "${episode.sceneCount} of ${frames.size} frames " +
        "(${episode.duplicateFlags.count { it }} duplicate — expect 0 with no size hints)")

    // Sheets, not scenes. Three scenes touch three sheets of a six-sheet film
    // and every scene touches all six — so these are equal only on an item
    // small enough to fit one sheet.
    val sheets = (frames.size + 99) / 100
    say("preview 3", fmtBytes(source.previewCostBytes(playable, 3)))
    say("preview all", fmtBytes(source.previewCostBytes(playable, episode.sceneCount)) +
        "   ($sheets sheet(s) — the ceiling, whatever is asked for)")

    // --- fetch three scenes, which is one sheet and then two crops ----------
    for (n in 0 until minOf(3, episode.sceneCount)) {
        val r = SceneResolver.resolve(episode, n) ?: continue
        val frame = episode.index[r.frameIndex]
        val start = System.currentTimeMillis()
        val jpeg = source.frameBytes(playable, frame)
        val ms = System.currentTimeMillis() - start
        val text = episode.cuesFor(r.scene).firstOrNull() ?: "(no cue)"
        say("scene $n", "frame ${r.frameIndex} @${frame.tsMs / 1000}s, " +
            "${jpeg.size} B in ${ms} ms — ${text.take(48)}")
    }
}

private fun scanFor(
    account: JellyfinAccount,
    items: List<BrowseItem>,
): Pair<BrowseItem, com.lukemeyer.trickplayer.core.source.Playable>? {
    for (i in items) {
        val p = runCatching { account.resolvePlayable(i) }.getOrNull() ?: continue
        return i to p
    }
    return null
}

private fun fmtBytes(n: Long?): String =
    if (n == null) "unknown" else "%.1f MB".format(n / 1e6)

/**
 * The JVM's own decoder, standing in for Android's `BitmapRegionDecoder`.
 *
 * Deliberately the naive whole-sheet decode: this harness is checking the
 * geometry and the plumbing, and the interesting property of the Android
 * implementation — that it never materialises the other 99 thumbnails — is the
 * one thing that cannot be checked here.
 */
private object ImageIoCropper : SheetCropper {
    override fun crop(sheet: ByteArray, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val img: BufferedImage = ImageIO.read(sheet.inputStream())
            ?: error("sheet is not a decodable image")
        val sub = img.getSubimage(
            x.coerceAtMost(img.width - 1),
            y.coerceAtMost(img.height - 1),
            w.coerceAtMost(img.width - x),
            h.coerceAtMost(img.height - y),
        )
        val out = ByteArrayOutputStream()
        ImageIO.write(sub, "jpg", out)
        return out.toByteArray()
    }
}

private fun say(k: String, v: String) = println("  %-22s %s".format(k, v))
