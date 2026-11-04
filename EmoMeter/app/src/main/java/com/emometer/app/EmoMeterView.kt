package com.emometer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs

/**
 * Four two-sided bars (neutral in the middle, intensity growing outward).
 * The bars are colour only; one large word above each bar names the current state.
 * Active blends (love, awe…) appear as large chips underneath.
 */
class EmoMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Colours per petal, per level (mild, middle, intense). Same colours as the wheel. */
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

    private val strength = FloatArray(Plutchik.COUNT)

    private val pad = 14 * dp
    private val captionH = 30 * dp
    private val barH = 24 * dp
    private val rowGap = 12 * dp
    private val blendH = 40 * dp
    private val radius = 8 * dp

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val side = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(21f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(17f)
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val clip = Path()

    fun setStrengths(s: FloatArray) {
        System.arraycopy(s, 0, strength, 0, Plutchik.COUNT)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = pad + 4 * (captionH + barH + rowGap) + blendH + pad
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = 0xD0000000.toInt()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 16 * dp, 16 * dp, fill)

        val left = pad
        val right = width - pad
        var y = pad
        for ((l, r) in Plutchik.AXES) {
            val v = strength[r] - strength[l]          // negative = left petal, positive = right petal
            val lvl = Plutchik.level(abs(v))
            val petal = if (v < 0) l else r
            drawCaption(canvas, left, right, y, l, r, if (lvl < 0) -1 else petal, lvl)
            y += captionH
            drawBar(canvas, left, right, y, l, r, v, lvl)
            y += barH + rowGap
        }
        drawBlends(canvas, left, right, y)
    }

    /** Petal names small at the ends; the current state large in the middle. */
    private fun drawCaption(c: Canvas, x0: Float, x1: Float, y: Float, l: Int, r: Int, petal: Int, lvl: Int) {
        val base = y + captionH - 8 * dp
        side.color = 0x99FFFFFF.toInt()
        side.textAlign = Paint.Align.LEFT
        c.drawText(Plutchik.NAMES[l], x0, base, side)
        side.textAlign = Paint.Align.RIGHT
        c.drawText(Plutchik.NAMES[r], x1, base, side)

        if (petal < 0) {
            big.color = 0x66FFFFFF
            big.isFakeBoldText = false
            c.drawText("neutral", (x0 + x1) / 2, base, big)
            big.isFakeBoldText = true
        } else {
            // Mild levels are pale colours, so the word stays white; the intense ones use the petal colour.
            big.color = if (lvl == 0) Color.WHITE else brighten(bg[petal][lvl])
            c.drawText(Plutchik.LEVELS[petal][lvl], (x0 + x1) / 2, base, big)
        }
    }

    /** Layout: [L intense][L middle][L mild][neutral (half)][R mild][R middle][R intense]. Colour only. */
    private fun drawBar(c: Canvas, x0: Float, x1: Float, y: Float, l: Int, r: Int, v: Float, lvl: Int) {
        val unit = (x1 - x0) / 6.5f
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
                fill.alpha = if (active) 255 else 80
                c.drawRect(rect, fill)
                if (active) {
                    stroke.color = Color.WHITE
                    stroke.strokeWidth = 3 * dp
                    rect.inset(1.5f * dp, 1.5f * dp)
                    c.drawRect(rect, stroke)
                }
            }
            x += w
        }
        c.restore()

        // Needle, mapped so it sits inside the highlighted segment.
        val center = (x0 + x1) / 2
        val nx = center + Math.signum(v) * needleOffset(abs(v)) * unit
        fill.color = Color.WHITE
        rect.set(nx - 2 * dp, y - 4 * dp, nx + 2 * dp, y + barH + 4 * dp)
        c.drawRoundRect(rect, 2 * dp, 2 * dp, fill)
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

    /** Only active blends are drawn, as large white chips with a two-colour mark. */
    private fun drawBlends(c: Canvas, x0: Float, x1: Float, y: Float) {
        val active = Plutchik.BLENDS.filter { strength[it.a] > 0.35f && strength[it.b] > 0.35f }
        val chipH = 34 * dp
        val top = y + (blendH - chipH) / 2
        if (active.isEmpty()) {
            side.color = 0x55FFFFFF
            side.textAlign = Paint.Align.CENTER
            c.drawText("no blend", (x0 + x1) / 2, top + chipH / 2 + side.textSize / 3, side)
            return
        }
        val mark = 14 * dp
        val gap = 8 * dp
        // Measure, then centre the row of chips.
        val widths = active.map { 10 * dp + mark + 8 * dp + chipText.measureText(it.name) + 12 * dp }
        val total = widths.sum() + gap * (active.size - 1)
        var x = maxOf(x0, (x0 + x1 - total) / 2)
        for ((i, b) in active.withIndex()) {
            val w = widths[i]
            if (x + w > x1) break
            rect.set(x, top, x + w, top + chipH)
            fill.color = Color.WHITE
            c.drawRoundRect(rect, radius, radius, fill)
            val mx = x + 10 * dp
            val my = top + (chipH - mark) / 2
            fill.color = bg[b.a][1]
            c.drawRect(mx, my, mx + mark / 2, my + mark, fill)
            fill.color = bg[b.b][1]
            c.drawRect(mx + mark / 2, my, mx + mark, my + mark, fill)
            chipText.color = Color.BLACK
            val fm = chipText.fontMetrics
            c.drawText(b.name, mx + mark + 8 * dp, top + chipH / 2 - (fm.ascent + fm.descent) / 2, chipText)
            x += w + gap
        }
    }

    /** Dark colours (deep blue, dark green) are hard to read on black; lift them toward white. */
    private fun brighten(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        if (lum > 110) return color
        val t = 0.45f
        return Color.rgb(
            (r + (255 - r) * t).toInt(),
            (g + (255 - g) * t).toInt(),
            (b + (255 - b) * t).toInt(),
        )
    }
}
