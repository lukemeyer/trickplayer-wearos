package com.lukemeyer.trickplayer.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Builds scenes ahead of the cursor, into the cache.
 *
 * The point is that a later glance becomes a cache read — no fetch, no parse —
 * so the expensive work happens while nobody is waiting on it. Same argument as
 * the Pebble prefetcher, and it matters more here, not less: the data source has
 * a 100 ms deadline it cannot fetch inside, so a cache miss is not slow, it is
 * *nothing on screen*.
 *
 * Deliberately conservative, as its predecessor was: one scene at a time, and it
 * stops the moment anything fails rather than hammering an unreachable server.
 */
class ScenePrefetchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val count = inputData.getInt(KEY_COUNT, PREFETCH_AHEAD)
        val settings = Settings(applicationContext)

        val repo = EpisodeRepository.open(applicationContext) ?: run {
            Log.i(TAG, "no episode configured; nothing to prefetch")
            return Result.success()
        }

        val total = repo.sceneCount()
        if (total == 0) {
            // The index itself could not be loaded — almost always the server
            // being unreachable. Retry rather than fail: the cache carries the
            // face until then, which is the whole reason it exists.
            Log.w(TAG, "episode not loadable; will retry")
            return Result.retry()
        }

        // Re-read the cursor on every iteration rather than trusting the value
        // this job was queued with.
        //
        // Tapping quickly used to starve the cache: each advance queued a fresh
        // job with REPLACE, cancelling the one already fetching, so with taps
        // arriving faster than a fetch completes nothing was ever finished and
        // the face ran out of scenes. Now the work is never cancelled and it
        // simply follows the cursor wherever it has got to.
        var built = 0
        for (n in 0 until count) {
            val idx = Math.floorMod(settings.sceneIndex + n, total)
            if (repo.cache.has(idx)) continue
            if (repo.build(idx) == null) {
                Log.w(TAG, "stopping prefetch at scene $idx")
                break
            }
            built++
        }
        Log.i(TAG, "prefetched $built scene(s) from ${settings.sceneIndex}; " +
            "cache holds ${repo.cache.count()} " +
            "(${repo.cache.sizeBytes() / 1024} KB)")

        // Tell whoever is drawing that there is now something to draw.
        //
        // Without this a cold start renders an empty face and stays that way:
        // the complications are asked for data while the cache is still empty,
        // correctly return nothing, and are never asked again — the prefetch
        // finishing is not by itself an event the system knows about. Observed
        // on a clean install (shots/11-cold-start.png).
        //
        // A broadcast rather than a direct call, so :data stays ignorant of
        // complications and could feed a Tile just as well.
        if (built > 0) {
            applicationContext.sendBroadcast(
                Intent(ACTION_SCENES_READY).setPackage(applicationContext.packageName),
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "TpPrefetch"

        /** Sent when new scenes have landed in the cache. */
        const val ACTION_SCENES_READY = "com.lukemeyer.trickplayer.data.SCENES_READY"
        const val KEY_FROM = "from"
        const val KEY_COUNT = "count"

        /**
         * How far ahead of the cursor to keep the cache warm.
         *
         * Must exceed the reach of one timeline burst, or the burst stops early
         * at the first uncached scene. Thirty minutes at a cue a minute is about
         * nine scenes at the measured 3.22 cues each; twelve leaves margin.
         */
        const val PREFETCH_AHEAD = 12

        private const val WORK_NAME = "bif.prefetch"

        /**
         * Queue a prefetch from [from].
         *
         * REPLACE rather than APPEND: if the cursor has moved on, an older
         * request is asking for scenes that are already behind us.
         */
        fun enqueue(context: Context, from: Int, count: Int = PREFETCH_AHEAD) {
            val req = OneTimeWorkRequestBuilder<ScenePrefetchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(KEY_FROM to from, KEY_COUNT to count))
                .build()
            // KEEP, not REPLACE: the worker follows the live cursor, so a job
            // already running is never stale, and replacing it just throws away
            // a fetch that was nearly done.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
