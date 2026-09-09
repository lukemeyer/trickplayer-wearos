package com.lukemeyer.bif.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant

/**
 * Supplies the BIF frame as a PHOTO_IMAGE complication.
 *
 * **Answers from cache and never fetches.** With
 * [ComplicationRequest.immediateResponseRequired] set the deadline is 100 ms
 * (20 s otherwise), so all this may do is hand over bytes the prefetch worker
 * already put within reach.
 *
 * That constraint is the Pebble architecture arriving from a different
 * direction: there the phone did the work because the watch had no decoder and a
 * BLE link in the way; here a worker does it because the callback has 100 ms.
 * Different reason, identical shape — everything expensive has already happened
 * by the time anyone asks.
 *
 * The image goes across as `Icon.createWithData` over the **raw BIF JPEG**,
 * about 13 KB, decoded in hardware on the other side. `createWithBitmap` would
 * put ~810 KB of ARGB_8888 through a Binder transaction against a ~1 MB limit.
 *
 * Note how completely this inverts the Pebble design, which went to great
 * lengths to *avoid* sending JPEG — decoding, median-cutting and
 * Floyd-Steinberg dithering every frame on the phone, because 11,200 B of 4-bit
 * indices beat 8-15 KB of JPEG over BLE to a device with no decoder. Here the
 * bytes are simply forwarded, and roughly 550 lines of image pipeline do not
 * exist.
 */
class FrameComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        if (request.complicationType != ComplicationType.PHOTO_IMAGE) {
            listener.onComplicationData(null)
            return
        }
        val steps = SceneTimeline.build(this)
        if (steps.isEmpty()) {
            // Nothing cached yet — the face has just been configured, or the
            // server was away when the worker last ran. Say nothing and let the
            // prefetch queued below fix it.
            Log.i(TAG, "request immediate=${request.immediateResponseRequired}: nothing cached")
            listener.onComplicationData(null)
            SceneState.ensurePrefetch(this)
            return
        }

        // One entry per *scene*, not per step: the picture only changes when the
        // scene does, so several consecutive steps share it. That is what keeps
        // the payload sane — each entry carries ~13 KB of JPEG against a Binder
        // limit near 1 MB, while the cue entries next door are about 1 KB.
        val runs = steps.groupBy { it.sceneIndex }
        val entries = runs.mapNotNull { (_, group) ->
            val e = group.first().entry ?: return@mapNotNull null
            TimelineEntry(
                validity = TimeInterval(
                    Instant.ofEpochMilli(group.first().fromMs),
                    Instant.ofEpochMilli(group.last().toMs),
                ),
                complicationData = build(e),
            )
        }
        Log.i(TAG, "request immediate=${request.immediateResponseRequired}: " +
            "${entries.size} frame entrie(s) over ${steps.size} steps")

        listener.onComplicationDataTimeline(
            ComplicationDataTimeline(
                // Holds the last frame once the burst runs out, rather than
                // blanking the face until something asks again.
                defaultComplicationData = build(steps.last().entry!!),
                timelineEntries = entries,
            ),
        )
        SceneState.ensurePrefetch(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.PHOTO_IMAGE) return null
        val jpeg = PlaceholderScene.jpeg()
        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithData(jpeg, 0, jpeg.size),
            contentDescription = PlainComplicationText.Builder("BIF frame").build(),
        ).build()
    }

    private fun build(entry: com.lukemeyer.bif.data.SceneCache.Entry): ComplicationData =
        PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithData(entry.jpeg, 0, entry.jpeg.size),
            contentDescription = PlainComplicationText.Builder(
                "Scene ${entry.index} at ${entry.tsMs / 1000}s",
            ).build(),
        )
            .setTapAction(advanceIntent())
            .build()

    private fun advanceIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(AdvanceReceiver.ACTION).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object { private const val TAG = "BifFrame" }
}
