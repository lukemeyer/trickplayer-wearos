package com.lukemeyer.bif.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * A stand-in frame for the complication picker's preview, where there is no
 * cache and no episode to draw from.
 *
 * Encoded as JPEG at 320x180 so the preview travels the same path and the same
 * shape as a real BIF frame.
 */
object PlaceholderScene {

    private var cached: ByteArray? = null

    fun jpeg(): ByteArray = cached ?: build().also { cached = it }

    private fun build(): ByteArray {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until 180 step 4) {
            p.color = Color.rgb(24 + y / 3, 26 + y / 4, 34 + y / 3)
            c.drawRect(0f, y.toFloat(), 320f, (y + 4).toFloat(), p)
        }
        p.color = Color.rgb(150, 160, 180)
        p.textSize = 22f
        p.textAlign = Paint.Align.CENTER
        c.drawText("BIF", 160f, 98f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
