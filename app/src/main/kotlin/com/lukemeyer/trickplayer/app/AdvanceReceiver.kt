package com.lukemeyer.trickplayer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukemeyer.trickplayer.core.scene.Advance

/**
 * The single place an advance happens, whatever triggered it.
 *
 * The Pebble build funnelled touch and accel-tap through one advance() so that
 * rate limiting and buffer accounting could not be got wrong by a source added
 * later. Same idea, different sources: a tap on either complication, and the
 * system's own request for data when the face wakes.
 *
 * That second source is worth noting. triggers.js called the backlight trigger
 * the ideal one and unreachable — "fires exactly when someone looks", but Alloy
 * exposed no such event. Wear OS asks a data source for data when the face
 * becomes active, which is approximately that, for free.
 */
class AdvanceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // A tap is explicit intent: always move, dwell gate or not. What it
        // moves is the user's choice — the complications do not even offer a tap
        // action when that choice is "do nothing", so this is belt and braces.
        val settings = SceneState.settings(context)
        if (settings.onTap == Advance.AUTOPLAY) {
            // A tap cannot push a timeline; all it can do is ask the system to
            // come back and ask us. So the intent is written down, and the
            // request that follows finds it and builds the burst.
            //
            // Cut any live burst short first. A burst in flight is re-served
            // unchanged by SceneTimeline, which is what keeps the two
            // complications in step — so without this the tap would be ignored
            // until the current one ran out. Consuming it credits exactly what
            // played and clears the way for a new one from here.
            SceneTimeline.consume(context)
            settings.autoplayUntilMs = System.currentTimeMillis() + settings.autoplayLengthMs
        } else {
            SceneState.advance(context, force = true, mode = settings.onTap)
        }
        SceneState.requestUpdate(context)
    }

    companion object {
        const val ACTION = "com.lukemeyer.trickplayer.app.ADVANCE"
    }
}
