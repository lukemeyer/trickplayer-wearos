package com.lukemeyer.bif.app

import android.app.PendingIntent
import com.lukemeyer.bif.core.scene.Advance
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant

/**
 * Supplies the current subtitle cue as LONG_TEXT.
 *
 * The cheap half of the design, and the reason for choosing a 10 s scene over
 * the BIF's native 2 s spacing. At the measured 3.22 cues per scene, roughly
 * three advances in four change only this — no fetch, no image, no cache write.
 */
class SubtitleComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val steps = SceneTimeline.build(this)
        if (steps.isEmpty()) {
            listener.onComplicationData(
                build(request.complicationType, SceneState.cueText(this, null)),
            )
            return
        }

        // One entry per step — the cue is the thing that moves.
        val entries = steps.mapNotNull { st ->
            val data = build(request.complicationType, st.cue) ?: return@mapNotNull null
            TimelineEntry(
                validity = TimeInterval(
                    Instant.ofEpochMilli(st.fromMs),
                    Instant.ofEpochMilli(st.toMs),
                ),
                complicationData = data,
            )
        }
        Log.i(TAG, "request type=${request.complicationType}: ${entries.size} cue entrie(s), " +
            "first \"${steps.first().cue.take(40)}\"")

        val tail = build(request.complicationType, steps.last().cue)
        if (tail == null || entries.isEmpty()) {
            listener.onComplicationData(null)
            return
        }
        listener.onComplicationDataTimeline(
            ComplicationDataTimeline(
                defaultComplicationData = tail,
                timelineEntries = entries,
            ),
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, "Subtitles from the moment on screen")

    private fun build(type: ComplicationType, text: String): ComplicationData? {
        val desc = PlainComplicationText.Builder("subtitle").build()
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.ifEmpty { " " }).build(),
                contentDescription = desc,
            ).setTapAction(advanceIntent()).build()

            // Declared so the face can be re-pointed at a SHORT_TEXT slot
            // without touching the app.
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(20).ifEmpty { " " }).build(),
                contentDescription = desc,
            ).setTapAction(advanceIntent()).build()

            else -> null
        }
    }

    /**
     * Null when "On tap" is *do nothing*, so the complication has **no tap
     * target at all** rather than one that swallows the tap. A control that
     * says nothing happens should leave nothing to press.
     */
    private fun advanceIntent(): PendingIntent? {
        if (SceneState.settings(this).onTap == Advance.NOTHING) {
            return null
        }
        return PendingIntent.getBroadcast(
            this,
            0,
            Intent(AdvanceReceiver.ACTION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object { private const val TAG = "BifSubtitle" }
}
