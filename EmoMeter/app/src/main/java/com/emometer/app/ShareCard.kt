package com.emometer.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Builds a 1080×1350 result card (title, big result, face, one line) and opens the share sheet. */
object ShareCard {

    fun mirror(b: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /**
     * @param big the large result, e.g. "82" or "0:47"
     * @param suffix smaller grey text after it, e.g. "/100" (may be empty)
     * @param frame the player's face, already oriented as it should look (mirror front-camera frames first)
     */
    fun share(act: Activity, title: String, big: String, suffix: String, line: String, frame: Bitmap?, text: String) {
        val card = build(act, title, big, suffix, line, frame)
        val dir = File(act.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "result.png")
        FileOutputStream(file).use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(Intent.createChooser(intent, null))
    }

    private fun build(act: Activity, title: String, big: String, suffix: String, line: String, frame: Bitmap?): Bitmap {
        val w = 1080
        val h = 1350
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF161616.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        p.color = 0xFFAAAAAA.toInt()
        p.textSize = 52f
        c.drawText(title, w / 2f, 110f, p)

        // Big result, with an optional smaller suffix; the pair is centred together.
        p.textAlign = Paint.Align.LEFT
        p.color = Color.WHITE
        p.isFakeBoldText = true
        p.textSize = 170f
        val bw = p.measureText(big)
        p.isFakeBoldText = false
        p.textSize = 64f
        val sw = if (suffix.isEmpty()) 0f else p.measureText(suffix) + 12f
        val x0 = (w - bw - sw) / 2f
        p.isFakeBoldText = true
        p.textSize = 170f
        c.drawText(big, x0, 290f, p)
        if (suffix.isNotEmpty()) {
            p.isFakeBoldText = false
            p.textSize = 64f
            p.color = 0xFF888888.toInt()
            c.drawText(suffix, x0 + bw + 12f, 290f, p)
        }
        p.isFakeBoldText = false
        p.textAlign = Paint.Align.CENTER

        val box = RectF(240f, 350f, 840f, 1060f)
        if (frame != null) {
            // Centre-crop the frame into the box.
            val scale = maxOf(box.width() / frame.width, box.height() / frame.height)
            val dw = frame.width * scale
            val dh = frame.height * scale
            val dst = RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
            c.save()
            c.clipPath(Path().apply { addRoundRect(box, 40f, 40f, Path.Direction.CW) })
            c.drawBitmap(frame, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            p.color = 0xFF2A2A2A.toInt()
            c.drawRoundRect(box, 40f, 40f, p)
        }

        p.color = Color.WHITE
        p.textSize = 50f
        c.drawText(line, w / 2f, 1160f, p)

        p.color = 0xFF777777.toInt()
        p.textSize = 40f
        c.drawText(act.getString(R.string.app_name), w / 2f, 1280f, p)
        return b
    }
}
