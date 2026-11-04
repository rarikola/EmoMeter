package com.emometer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Four two-sided bars (neutral in the middle, intensity growing outward) plus the 8 blend chips.
 * The segment closest to the face lights up; a white needle shows the exact position.
 */
class EmoMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    /** Background / text colour per petal, per level (mild, middle, intense). Same colours as the wheel. */
    private val bg = arrayOf(
        intArrayOf(0xFFFFFFB1.toInt(), 0xFFFFFF54.toInt(), 0xFFFFE854.toInt()),
        intArrayOf(0xFF8CFF8C.toInt(), 0xFF54FF54.toInt(), 0xFF00B400.toInt()),
        intArrayOf(0xFF8CC68C.toInt(), 0xFF009600.toInt(), 0xFF007000.toInt()),
        intArrayOf(0xFFA5DBFF.toInt(), 0xFF59BDFF.toInt(), 0xFF0089E0.toInt()),
        intArrayOf(0xFF8C8CFF.toInt(), 0xFF5151FF.toInt(), 0xFF0000C8.toInt()),
        intArrayOf(0xFFFFC6FF.toInt(), 0xFFFF54FF.toInt(), 0xFFDE00DE.toInt()),
        intArrayOf(0xFFFF8C8C.toInt(), 0xFFFF0000.toInt(), 0xFFD40000.toInt()),
        intArrayOf(0xFFFFC48C.toInt(), 0xFFFFA854.toInt(), 0xFFFF7F00.toInt()),
    )
    private val fg = arrayOf(
        intArrayOf(0xFF5A5000.toInt(), 0xFF5A5000.toInt(), 0xFF5A4500.toInt()),
        intArrayOf(0xFF0B4D0B.toInt(), 0xFF0B4D0B.toInt(), Color.WHITE),
        intArrayOf(0xFF0B3D0B.toInt(), Color.WHITE, Color.WHITE),
        intArrayOf(0xFF00304F.toInt(), 0xFF00304F.toInt(), Color.WHITE),
        intArrayOf(0xFF0A0A4A.toInt(), Color.WHITE, Color.WHITE),
        intArrayOf(0xFF4A004A.toInt(), 0xFF4A004A.toInt(), Color.WHITE),
        intArrayOf(0xFF5A0000.toInt(), Color.WHITE, Color.WHITE),
        intArrayOf(0xFF5A2E00.toInt(), 0xFF5A2E00.toInt(), Color.WHITE),
    )

    private val strength = FloatArray(Plutchik.COUNT)

    private val pad = 12 * dp
    private val captionH = 16 * dp
    private val barH = 34 * dp
    private val rowGap = 8 * dp
    private val chipH = 28 * dp
    private val chipGap = 6 * dp
    private val radius = 8 * dp

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val clip = Path()

    fun setStrengths(s: FloatArray) {
        System.arraycopy(s, 0, strength, 0, Plutchik.COUNT)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = pad + 4 * (captionH + barH + rowGap) + 4 * dp + 2 * chipH + chipGap + pad
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        // Panel background.
        fill.color = 0xC0000000.toInt()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 16 * dp, 16 * dp, fill)

        val left = pad
        val right = width - pad
        var y = pad
        for ((l, r) in Plutchik.AXES) {
            drawCaption(canvas, left, right, y, Plutchik.NAMES[l], Plutchik.NAMES[r])
            y += captionH
            drawBar(canvas, left, right, y, l, r)
            y += barH + rowGap
        }
        y += 4 * dp
        drawBlends(canvas, left, right, y)
    }

    private fun drawCaption(c: Canvas, x0: Float, x1: Float, y: Float, a: String, b: String) {
        text.textSize = 11 * sp
        text.color = 0xCCFFFFFF.toInt()
        text.isFakeBoldText = false
        val base = y + captionH - 4 * dp
        text.textAlign = Paint.Align.LEFT
        c.drawText(a, x0, base, text)
        text.textAlign = Paint.Align.CENTER
        text.color = 0x88FFFFFF.toInt()
        c.drawText("neutral", (x0 + x1) / 2, base, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = 0xCCFFFFFF.toInt()
        c.drawText(b, x1, base, text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Layout: [L intense][L middle][L mild][neutral (half)][R mild][R middle][R intense] */
    private fun drawBar(c: Canvas, x0: Float, x1: Float, y: Float, l: Int, r: Int) {
        val unit = (x1 - x0) / 6.5f
        val v = strength[r] - strength[l]          // negative = left petal, positive = right petal
        val lvl = Plutchik.level(abs(v))
        val activeSeg = when {
            lvl < 0 -> 3
            v < 0 -> 2 - lvl
            else -> 4 + lvl
        }

        c.save()
        rect.set(x0, y, x1, y + barH)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(clip)

        var x = x0
        for (seg in 0..6) {
            val w = if (seg == 3) unit * 0.5f else unit
            rect.set(x, y, x + w, y + barH)
            val active = seg == activeSeg
            if (seg == 3) {
                fill.color = if (active) 0x66FFFFFF else 0x26FFFFFF
                c.drawRect(rect, fill)
            } else {
                val petal = if (seg < 3) l else r
                val level = if (seg < 3) 2 - seg else seg - 4
                fill.color = bg[petal][level]
                fill.alpha = if (active) 255 else 95
                c.drawRect(rect, fill)
                drawFitted(c, Plutchik.LEVELS[petal][level], rect, fg[petal][level], if (active) 255 else 150, active)
            }
            if (active && seg != 3) {
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3 * dp
                rect.inset(1.5f * dp, 1.5f * dp)
                c.drawRect(rect, stroke)
            }
            x += w
        }
        c.restore()

        // Needle, mapped so it sits inside the highlighted segment.
        val center = (x0 + x1) / 2
        val nx = center + Math.signum(v) * needleOffset(abs(v)) * unit
        fill.color = Color.WHITE
        fill.alpha = 255
        rect.set(nx - 1.5f * dp, y - 3 * dp, nx + 1.5f * dp, y + barH + 3 * dp)
        c.drawRoundRect(rect, 1.5f * dp, 1.5f * dp, fill)
    }

    /** Piecewise map from strength to distance from center, in segment units. */
    private fun needleOffset(a: Float): Float {
        val xs = floatArrayOf(0f, 0.2f, 0.5f, 0.85f, 1.3f)
        val ys = floatArrayOf(0f, 0.25f, 1.25f, 2.25f, 3.2f)
        if (a >= xs.last()) return ys.last()
        for (i in 1 until xs.size) {
            if (a <= xs[i]) {
                val t = (a - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }

    private fun drawBlends(c: Canvas, x0: Float, x1: Float, y0: Float) {
        val cols = 4
        val gap = 6 * dp
        val w = (x1 - x0 - gap * (cols - 1)) / cols
        for ((i, b) in Plutchik.BLENDS.withIndex()) {
            val col = i % cols
            val row = i / cols
            val x = x0 + col * (w + gap)
            val y = y0 + row * (chipH + chipGap)
            val on = strength[b.a] > 0.35f && strength[b.b] > 0.35f
            rect.set(x, y, x + w, y + chipH)
            if (on) {
                fill.color = Color.WHITE
                c.drawRoundRect(rect, radius, radius, fill)
            } else {
                stroke.color = 0x55FFFFFF
                stroke.strokeWidth = 1 * dp
                rect.inset(0.5f * dp, 0.5f * dp)
                c.drawRoundRect(rect, radius, radius, stroke)
                rect.set(x, y, x + w, y + chipH)
            }
            // Small two-colour square: which petals the blend is made of.
            val s = 10 * dp
            val sx = x + 7 * dp
            val sy = y + (chipH - s) / 2
            fill.color = bg[b.a][1]
            c.drawRect(sx, sy, sx + s / 2, sy + s, fill)
            fill.color = bg[b.b][1]
            c.drawRect(sx + s / 2, sy, sx + s, sy + s, fill)

            val textRect = RectF(sx + s + 2 * dp, y, x + w - 2 * dp, y + chipH)
            drawFitted(c, b.name, textRect, if (on) Color.BLACK else 0xAAFFFFFF.toInt(), 255, on)
        }
    }

    /** Draw text centred in r, shrinking it if needed so long words like "apprehension" still fit. */
    private fun drawFitted(c: Canvas, s: String, r: RectF, color: Int, alpha: Int, bold: Boolean) {
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = bold
        var size = 12 * sp
        text.textSize = size
        val maxW = r.width() - 4 * dp
        val tw = text.measureText(s)
        if (tw > maxW) {
            size = (size * maxW / tw).coerceAtLeast(7 * sp)
            text.textSize = size
        }
        text.color = color
        text.alpha = min(alpha, Color.alpha(color))
        val fm = text.fontMetrics
        val base = r.centerY() - (fm.ascent + fm.descent) / 2
        c.drawText(s, r.centerX(), base, text)
        text.isFakeBoldText = false
    }
}
