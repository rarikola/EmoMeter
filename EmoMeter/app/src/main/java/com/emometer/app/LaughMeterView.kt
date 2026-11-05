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

/**
 * Two one-sided bars for "Make me laugh":
 *  1. laugh meter: neutral → slight smile → smile → laughing (joy colours)
 *  2. other reactions: whichever other emotion is strongest, in its own colours, with its name
 * Each bar: [neutral (half)][mild][middle][intense], the active segment lit, a white needle.
 */
class LaughMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var laugh = 0f
    private var otherPetal = -1
    private var other = 0f

    private val pad = 14 * dp
    private val captionH = 32 * dp
    private val barH = 26 * dp
    private val rowGap = 14 * dp
    private val radius = 8 * dp

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f); color = 0xAAFFFFFF.toInt() }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(22f)
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()
    private val clip = Path()

    private val laughWords = arrayOf("slight smile", "smile", "laughing")

    fun setValues(laugh: Float, otherPetal: Int, other: Float) {
        this.laugh = laugh
        this.otherPetal = otherPetal
        this.other = other
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = pad + 2 * (captionH + barH) + rowGap + pad
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = 0xC8000000.toInt()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 16 * dp, 16 * dp, fill)

        val x0 = pad
        val x1 = width - pad
        var y = pad

        // 1. Laugh meter
        val ll = Plutchik.level(laugh)
        val laughWord = if (ll < 0) context.getString(R.string.laugh_neutral) else laughWords[ll]
        val laughColor = if (ll < 0) 0x77FFFFFF else PetalColors.BG[Plutchik.JOY][1]
        caption(canvas, x0, x1, y, context.getString(R.string.laugh_meter), laughWord, laughColor)
        y += captionH
        bar(canvas, x0, x1, y, PetalColors.BG[Plutchik.JOY], laugh, ll)
        y += barH + rowGap

        // 2. Other reactions
        val p = otherPetal
        val ol = if (p < 0) -1 else Plutchik.level(other)
        val otherWord = if (ol < 0) context.getString(R.string.laugh_nothing) else Plutchik.LEVELS[p][ol]
        val otherColor = if (ol < 0) 0x77FFFFFF else brighten(PetalColors.BG[p][maxOf(ol, 1)])
        caption(canvas, x0, x1, y, context.getString(R.string.laugh_other), otherWord, otherColor)
        y += captionH
        val colors = if (p < 0) intArrayOf(0xFF555555.toInt(), 0xFF666666.toInt(), 0xFF777777.toInt()) else PetalColors.BG[p]
        bar(canvas, x0, x1, y, colors, if (p < 0) 0f else other, ol)
    }

    private fun caption(c: Canvas, x0: Float, x1: Float, y: Float, label: String, word: String, wordColor: Int) {
        val base = y + captionH - 9 * dp
        c.drawText(label, x0, base, small)
        big.color = wordColor
        c.drawText(word, x1, base, big)
    }

    private fun bar(c: Canvas, x0: Float, x1: Float, y: Float, colors: IntArray, v: Float, level: Int) {
        val unit = (x1 - x0) / 3.5f
        c.save()
        rect.set(x0, y, x1, y + barH)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(clip)

        // Neutral segment.
        rect.set(x0, y, x0 + unit * 0.5f, y + barH)
        fill.color = if (level < 0) 0x66FFFFFF else 0x26FFFFFF
        c.drawRect(rect, fill)

        for (k in 0..2) {
            val sx = x0 + unit * (0.5f + k)
            rect.set(sx, y, sx + unit, y + barH)
            fill.color = colors[k]
            fill.alpha = if (k == level) 255 else 80
            c.drawRect(rect, fill)
            if (k == level) {
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3 * dp
                rect.inset(1.5f * dp, 1.5f * dp)
                c.drawRect(rect, stroke)
            }
        }
        c.restore()

        val nx = x0 + needle(v) * unit
        fill.color = Color.WHITE
        rect.set(nx - 2 * dp, y - 4 * dp, nx + 2 * dp, y + barH + 4 * dp)
        c.drawRoundRect(rect, 2 * dp, 2 * dp, fill)
    }

    /** Strength → distance from the left edge in segment units, matching the level thresholds. */
    private fun needle(a: Float): Float {
        val xs = floatArrayOf(0f, 0.2f, 0.5f, 0.85f, 1.3f)
        val ys = floatArrayOf(0.05f, 0.5f, 1.5f, 2.5f, 3.45f)
        if (a <= 0f) return ys[0]
        if (a >= xs.last()) return ys.last()
        for (i in 1 until xs.size) {
            if (a <= xs[i]) {
                val t = (a - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }

    /** Dark colours are hard to read on black; lift them toward white. */
    private fun brighten(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        if (0.299 * r + 0.587 * g + 0.114 * b > 110) return color
        val t = 0.45f
        return Color.rgb((r + (255 - r) * t).toInt(), (g + (255 - g) * t).toInt(), (b + (255 - b) * t).toInt())
    }
}
