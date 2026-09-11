package com.lukemeyer.trickplayer.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Resolved scenes on disk: the frame's JPEG plus the cues for its window.
 *
 * A scene costs a ranged fetch (~13 KB) and the resolve work behind it. Doing
 * that once per scene *ever*, rather than once per glance, is what lets the face
 * keep working when the watch is nowhere near the Plex server — which is most of
 * the day. That argument is inherited wholesale from the Pebble build; only the
 * storage changed.
 *
 * And it changed a lot. Pebble had to base64 scenes into a localStorage of
 * unknown quota and evict on quota errors, because that was the only persistence
 * PebbleKit JS offered. Here it is a directory. No encoding, no probing, no
 * quota-error retry loop — 208 lines of cache.js reduce to a size cap and
 * `listFiles()`.
 *
 * Two files per scene, `<idx>.jpg` and `<idx>.json`, because that stays
 * inspectable with `adb shell` when something looks wrong.
 */
class SceneCache(context: Context, private val profile: String) {

    private val root = File(context.applicationContext.cacheDir, "scenes/$profile")

    data class Entry(
        val index: Int,
        val frameIndex: Int,
        val tsMs: Long,
        val cues: List<String>,
        val jpeg: ByteArray,
    )

    init {
        root.mkdirs()
        // Scenes from a previous episode or interval can never be served: their
        // directory is a sibling, and it is dropped the first time this one is
        // opened rather than lingering until the disk fills.
        root.parentFile?.listFiles()?.forEach {
            if (it.isDirectory && it.name != profile) it.deleteRecursively()
        }
    }

    private fun jpegFile(i: Int) = File(root, "$i.jpg")
    private fun metaFile(i: Int) = File(root, "$i.json")

    fun has(index: Int): Boolean = jpegFile(index).exists() && metaFile(index).exists()

    /** Null on a miss, and on anything unreadable — a corrupt entry is a miss. */
    fun get(index: Int): Entry? {
        val jf = jpegFile(index)
        val mf = metaFile(index)
        if (!jf.exists() || !mf.exists()) return null
        return try {
            val o = JSONObject(mf.readText())
            val arr = o.getJSONArray("cues")
            val cues = List(arr.length()) { arr.getString(it) }
            // Touch for LRU. Read time is what "recently used" means here.
            val now = System.currentTimeMillis()
            jf.setLastModified(now)
            mf.setLastModified(now)
            Entry(index, o.getInt("frame"), o.getLong("ts"), cues, jf.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "dropping unreadable scene $index: ${e.message}")
            jf.delete(); mf.delete()
            null
        }
    }

    fun put(entry: Entry) {
        try {
            jpegFile(entry.index).writeBytes(entry.jpeg)
            metaFile(entry.index).writeText(
                JSONObject()
                    .put("frame", entry.frameIndex)
                    .put("ts", entry.tsMs)
                    .put("cues", JSONArray(entry.cues))
                    .toString(),
            )
            trim()
        } catch (e: Exception) {
            Log.w(TAG, "could not store scene ${entry.index}: ${e.message}")
        }
    }

    fun count(): Int = root.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Evict oldest-touched scenes until under the cap.
     *
     * The cap is generous on purpose: a whole 24.5 minute episode is 148 scenes
     * at ~13 KB, about 2 MB. `MAX_BYTES` therefore holds several episodes and
     * exists to bound the pathological case, not to ration normal use — the
     * opposite of Pebble, where the cache was the binding constraint.
     */
    private fun trim() {
        var total = sizeBytes()
        if (total <= MAX_BYTES) return
        val byAge = root.listFiles()
            ?.filter { it.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (f in byAge) {
            if (total <= MAX_BYTES) break
            val idx = f.nameWithoutExtension.toIntOrNull() ?: continue
            total -= f.length() + metaFile(idx).length()
            f.delete(); metaFile(idx).delete()
        }
        Log.i(TAG, "trimmed to ${sizeBytes() / 1024} KB, ${count()} scenes")
    }

    companion object {
        private const val TAG = "TpCache"
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
