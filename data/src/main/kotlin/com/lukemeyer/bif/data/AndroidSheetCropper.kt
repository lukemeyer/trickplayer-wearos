package com.lukemeyer.bif.data

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.lukemeyer.bif.core.source.SheetCropper
import java.io.ByteArrayOutputStream

/**
 * Crops one thumbnail out of a Jellyfin tile sheet.
 *
 * [BitmapRegionDecoder] rather than decode-then-crop, and that is the whole
 * point of this class: a sheet is 3200x1320 — **4.22 megapixels, or ~17 MB of
 * ARGB_8888** — and only one 320x132 cell of it is wanted. Decoding the whole
 * thing to throw away 99% is the transient allocation that made this question
 * worth measuring on Pebble at all (F-041), and Wear OS has less headroom than
 * a phone, not more. Region decoding never materialises the rest.
 *
 * The re-encode is unavoidable: the caller's contract is JPEG bytes, because on
 * Plex that is literally what came off the wire.
 */
class AndroidSheetCropper(private val quality: Int = 90) : SheetCropper {

    override fun crop(sheet: ByteArray, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val decoder = BitmapRegionDecoder.newInstance(sheet, 0, sheet.size)
            ?: throw IllegalStateException("sheet is not a decodable image")
        try {
            // Clamp to the sheet: the final sheet of a set is normally PARTIAL
            // — the measured capture is 73 thumbnails in a 100-cell grid — so a
            // cell near the end can be asked for past the bottom edge if the
            // geometry and the image ever disagree.
            val rect = Rect(x, y, minOf(x + w, decoder.width), minOf(y + h, decoder.height))
            if (rect.isEmpty) throw IllegalArgumentException("crop $rect is outside the sheet")

            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565   // no alpha in a photo
            }
            val bmp = decoder.decodeRegion(rect, opts)
                ?: throw IllegalStateException("could not decode $rect")
            try {
                val out = ByteArrayOutputStream(w * h / 4)
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                return out.toByteArray()
            } finally {
                bmp.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }
}
