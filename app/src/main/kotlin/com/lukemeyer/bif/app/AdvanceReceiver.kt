package com.lukemeyer.bif.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        val mode = SceneState.settings(context).onTap
        SceneState.advance(context, force = true, mode = mode)
        SceneState.requestUpdate(context)
    }

    companion object {
        const val ACTION = "com.lukemeyer.bif.app.ADVANCE"
    }
}
