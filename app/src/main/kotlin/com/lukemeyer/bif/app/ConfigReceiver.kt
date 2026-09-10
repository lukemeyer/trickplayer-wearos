package com.lukemeyer.bif.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lukemeyer.bif.data.Settings

/**
 * Sets the episode over adb.
 *
 * Kept as a development shortcut now that [config.ConfigActivity] does this
 * properly on the watch — it seeds a known episode without walking the UI, which
 * is what makes the content pipeline testable in one command. It is the
 * counterpart of the Pebble project's gitignored `local-config.js`, and existed
 * for the same reason: the pipeline had to be buildable before the config page
 * was.
 *
 *     adb shell am broadcast -a com.lukemeyer.bif.app.CONFIGURE \
 *         --es server https://… --es token … --el timelineRef 27295 \
 *         --es subtitleRef /library/streams/103375
 *
 * A token passed this way is visible to `adb shell dumpsys` and to logcat's
 * broadcast records. That is acceptable on a development emulator and is why it
 * is not how the real path works: signing in through the UI never puts a token
 * on a command line.
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val server = intent.getStringExtra("server")
        val token = intent.getStringExtra("token")
        val timelineRef = intent.getLongExtra("timelineRef", -1L)
        if (server.isNullOrEmpty() || token.isNullOrEmpty() || timelineRef < 0) {
            Log.w(TAG, "need --es server, --es token and --el timelineRef")
            return
        }

        // Optional: --es routes "uri1,uri2" to seed the fallback list the way
        // the config UI does, so the off-network path can be exercised.
        // Comma, not pipe: `adb shell` hands the command to a shell on the
        // device, which eats a pipe and silently truncates every extra after it.
        val routes = intent.getStringExtra("routes")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf(server.trimEnd('/'))

        Settings(context).episode = Settings.Episode(
            server = server.trimEnd('/'),
            routes = routes,
            token = token,
            timelineRef = timelineRef,
            subtitleRef = intent.getStringExtra("subtitleRef").orEmpty(),
            title = intent.getStringExtra("title").orEmpty(),
            skipSilent = intent.getBooleanExtra("skipSilent", true),
        )
        // Never log the token.
        Log.i(TAG, "configured part $timelineRef; starting prefetch")

        // Optional tuning knob: --el dwellMs 5000 to watch the raw poll cadence.
        if (intent.hasExtra("dwellMs")) {
            val d = intent.getLongExtra("dwellMs", 60_000L)
            Settings(context).dwellMs = d
            Log.i(TAG, "dwell set to $d ms")
        }

        SceneState.ensurePrefetch(context)
        SceneState.requestUpdate(context)
    }

    companion object {
        const val ACTION = "com.lukemeyer.bif.app.CONFIGURE"
        private const val TAG = "BifConfig"
    }
}
