package com.lukemeyer.trickplayer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lukemeyer.trickplayer.data.ScenePrefetchWorker
import com.lukemeyer.trickplayer.data.Settings

/**
 * Redraws the face once the prefetch worker has put scenes in the cache.
 *
 * This closes the cold-start gap. The complications are asked for data as soon
 * as the face is selected, which on a fresh install is before anything has been
 * fetched; they correctly return nothing, and nothing asks them again. The face
 * then sits empty indefinitely with no error anywhere — the cache is filling
 * perfectly well, and no one is looking.
 */
class ScenesReadyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScenePrefetchWorker.ACTION_SCENES_READY) return

        // Only wake the face if it has nothing to show. Prefetching happens
        // constantly — including as a side effect of serving a burst — and
        // refreshing on every completion tears down a burst that is part way
        // through playing. Measured on hardware: one tap produced four pushes in
        // fourteen seconds, each replacing the last.
        if (Settings(context).timelineStartMs != 0L) {
            Log.i(TAG, "scenes ready, but a burst is already playing; leaving it alone")
            return
        }
        Log.i(TAG, "scenes ready; refreshing complications")
        SceneState.requestUpdate(context)
    }

    private companion object { const val TAG = "TpReady" }
}
