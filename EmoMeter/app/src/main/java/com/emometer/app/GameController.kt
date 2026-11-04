package com.emometer.app

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Poker face: pictures flash on screen, the player keeps a straight face, every leak costs points.
 *
 * Flow: 3 s baseline (relaxed face becomes this session's neutral) → for each picture:
 * 3 s on screen + 1.5 s relax gap → results. The meter is hidden during the round.
 * A leak is measured from 0.25 s after the picture appears until 0.5 s after it disappears.
 */
class GameController(
    private val act: MainActivity,
    private val engine: EmotionEngine,
    private val prefs: SharedPreferences,
    private val onExit: () -> Unit,
) {
    companion object {
        const val PICTURES = 20
        const val SHOW_MS = 3000L
        const val RELAX_MS = 1500L
        const val BASELINE_MS = 3000L
        const val LAG_MS = 250L
        const val TAIL_MS = 500L
        const val POINTS = 5
    }

    private val handler = Handler(Looper.getMainLooper())

    private val group: View = act.findViewById(R.id.game_group)
    private val counter: TextView = act.findViewById(R.id.game_counter)
    private val faceDot: TextView = act.findViewById(R.id.game_face)
    private val progress: ProgressBar = act.findViewById(R.id.game_progress)
    private val image: ImageView = act.findViewById(R.id.game_image)
    private val relax: TextView = act.findViewById(R.id.game_relax)
    private val timer: View = act.findViewById(R.id.game_timer)
    private val message: TextView = act.findViewById(R.id.game_message)

    private val results: View = act.findViewById(R.id.results_group)
    private val scoreText: TextView = act.findViewById(R.id.res_score)
    private val bestText: TextView = act.findViewById(R.id.res_best)
    private val leakImage: ImageView = act.findViewById(R.id.leak_image)
    private val leakCaption: TextView = act.findViewById(R.id.leak_caption)
    private val leakTitle: TextView = act.findViewById(R.id.leak_title)
    private val leakChip: TextView = act.findViewById(R.id.leak_chip)
    private val list: LinearLayout = act.findViewById(R.id.res_list)

    private enum class Phase { IDLE, BASELINE, SHOW, RELAX, DONE }

    private class Peak {
        var value = 0f
        var petal = -1
        var frame: Bitmap? = null
        var frames = 0
        var missing = 0
    }

    private class Outcome(val stimulus: Stimulus, val petal: Int, val level: Int, val value: Float, val lost: Boolean, val frame: Bitmap?) {
        val penalty: Int
            get() = if (lost) POINTS else when (level) { -1 -> 0; 0 -> 1; 1 -> 3; else -> POINTS }
    }

    private var phase = Phase.IDLE
    private var items: List<Stimulus> = emptyList()
    private var peaks: Array<Peak> = emptyArray()
    private var measuring = -1
    private val baseline = ArrayList<FloatArray>()
    private var original: Calibration? = null
    private var lastFrame: Bitmap? = null

    private var shareScore = 0
    private var shareFrame: Bitmap? = null
    private var shareLine = ""

    init {
        act.findViewById<Button>(R.id.res_again).setOnClickListener { start() }
        act.findViewById<Button>(R.id.res_share).setOnClickListener { share() }
        act.findViewById<Button>(R.id.res_home).setOnClickListener { stop(); onExit() }
    }

    /** Returns false when there are no pictures to show. */
    fun start(): Boolean {
        val all = Stimuli.load(act)
        if (all.isEmpty()) return false
        stop()
        items = Stimuli.pick(all, PICTURES, prefs)
        peaks = Array(items.size) { Peak() }
        original = engine.calibration
        lastFrame = null

        results.visibility = View.GONE
        group.visibility = View.VISIBLE
        counter.text = ""
        progress.max = items.size
        progress.progress = 0
        image.setImageDrawable(null)
        timer.scaleX = 0f
        relax.visibility = View.VISIBLE
        message.text = act.getString(R.string.game_baseline)

        phase = Phase.BASELINE
        baseline.clear()
        for (i in 0..2) {
            handler.postDelayed({ relax.text = act.getString(R.string.game_get_ready, 3 - i) }, i * 1000L)
        }
        handler.postDelayed({ finishBaseline() }, BASELINE_MS)
        return true
    }

    /** Leave the game (back button or Home). Restores the normal calibration if mid-round. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        timer.animate().cancel()
        if (phase == Phase.BASELINE || phase == Phase.SHOW || phase == Phase.RELAX) {
            original?.let { engine.setCalibration(it) }
        }
        phase = Phase.IDLE
        measuring = -1
        group.visibility = View.GONE
        results.visibility = View.GONE
    }

    /** Called for every camera frame, after the engine has been updated. */
    fun onFrame(face: FloatArray?, frame: Bitmap?) {
        if (phase == Phase.IDLE || phase == Phase.DONE) return
        if (face != null) {
            faceDot.text = act.getString(R.string.game_face_ok)
            faceDot.setTextColor(0xFF3BD16F.toInt())
            if (frame != null) lastFrame = frame
        } else {
            faceDot.text = act.getString(R.string.game_face_lost)
            faceDot.setTextColor(0xFFFF6B6B.toInt())
        }

        if (phase == Phase.BASELINE) {
            if (face != null) baseline.add(face)
            return
        }

        val m = measuring
        if (m < 0) return
        val p = peaks[m]
        p.frames++
        if (face == null) {
            p.missing++
            return
        }
        for (k in 0 until Plutchik.COUNT) {
            val s = engine.strength[k]
            if (s > p.value) {
                p.value = s
                p.petal = k
                p.frame = frame
            }
        }
    }

    private fun finishBaseline() {
        val base = original
        if (base != null && baseline.size >= 5) {
            // This session's relaxed face (current light, angle, distance) becomes neutral.
            val avg = FloatArray(Shapes.N)
            for (v in baseline) for (i in avg.indices) avg[i] += v[i]
            for (i in avg.indices) avg[i] = avg[i] / baseline.size
            val session = base.copy()
            System.arraycopy(avg, 0, session.neutral, 0, Shapes.N)
            engine.setCalibration(session)
        }
        message.text = act.getString(R.string.game_hidden)
        showItem(0)
    }

    private fun showItem(i: Int) {
        if (i >= items.size) {
            finish()
            return
        }
        phase = Phase.SHOW
        counter.text = act.getString(R.string.game_counter, i + 1, items.size)
        progress.progress = i
        image.setImageBitmap(Stimuli.bitmap(act, items[i]))
        relax.visibility = View.GONE

        timer.animate().cancel()
        timer.pivotX = 0f
        timer.scaleX = 1f
        timer.animate().scaleX(0f).setDuration(SHOW_MS).setInterpolator(LinearInterpolator()).start()

        handler.postDelayed({ measuring = i }, LAG_MS)
        handler.postDelayed({ startRelax(i) }, SHOW_MS)
    }

    private fun startRelax(i: Int) {
        phase = Phase.RELAX
        image.setImageDrawable(null)
        timer.animate().cancel()
        timer.scaleX = 0f
        relax.text = act.getString(R.string.game_relax)
        relax.visibility = View.VISIBLE
        handler.postDelayed({ if (measuring == i) measuring = -1 }, TAIL_MS)
        handler.postDelayed({ showItem(i + 1) }, RELAX_MS)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        measuring = -1
        phase = Phase.DONE
        original?.let { engine.setCalibration(it) }
        progress.progress = items.size

        val outcomes = items.indices.map { i ->
            val p = peaks[i]
            val lost = p.frames == 0 || p.missing * 2 > p.frames
            val level = if (lost) -1 else Plutchik.level(p.value)
            Outcome(items[i], p.petal, level, p.value, lost, p.frame)
        }
        val totalPenalty = outcomes.sumOf { it.penalty }
        val maxPenalty = POINTS * outcomes.size
        val score = (100f * (1f - totalPenalty.toFloat() / maxPenalty)).roundToInt().coerceIn(0, 100)

        val best = prefs.getInt("best", -1)
        val newBest = score > best
        if (newBest) prefs.edit().putInt("best", score).apply()

        group.visibility = View.GONE
        results.visibility = View.VISIBLE
        scoreText.text = score.toString()
        bestText.text = if (newBest) act.getString(R.string.res_new_best) else act.getString(R.string.res_best, best)
        bestText.setTextColor(if (newBest) 0xFF3BD16F.toInt() else 0xFFAAAAAA.toInt())

        val leak = outcomes.filter { !it.lost && it.level >= 0 }.maxByOrNull { it.value }
        val frame = leak?.frame ?: lastFrame
        leakImage.setImageBitmap(frame?.let { mirror(it) })
        if (leak != null) {
            leakCaption.text = act.getString(R.string.res_biggest_leak)
            leakTitle.text = leak.stimulus.label
            styleChip(leakChip, "${Plutchik.NAMES[leak.petal]} · ${strengthWord(leak.level)}", leak.petal, leak.level)
            leakChip.visibility = View.VISIBLE
            shareLine = act.getString(R.string.share_leak, leak.stimulus.label, Plutchik.NAMES[leak.petal])
        } else {
            leakCaption.text = act.getString(R.string.res_no_leaks_caption)
            leakTitle.text = act.getString(R.string.res_no_leaks)
            leakChip.visibility = View.GONE
            shareLine = act.getString(R.string.res_no_leaks)
        }
        shareScore = score
        shareFrame = frame

        list.removeAllViews()
        for (o in outcomes) list.addView(row(o))
    }

    private fun strengthWord(level: Int) = when (level) {
        0 -> act.getString(R.string.level_mild)
        1 -> act.getString(R.string.level_middle)
        else -> act.getString(R.string.level_strong)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics)

    private fun row(o: Outcome): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7f).toInt(), 0, dp(7f).toInt())
        }
        val name = TextView(act).apply {
            text = o.stimulus.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chip = TextView(act).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        when {
            o.lost -> {
                chip.text = act.getString(R.string.res_face_lost)
                chip.setTextColor(0xFFFF8A8A.toInt())
            }
            o.level < 0 -> {
                chip.text = act.getString(R.string.res_no_leak)
                chip.setTextColor(0xFF888888.toInt())
            }
            else -> styleChip(chip, Plutchik.LEVELS[o.petal][o.level], o.petal, o.level)
        }
        row.addView(chip)
        return row
    }

    private fun styleChip(chip: TextView, text: String, petal: Int, level: Int) {
        chip.text = text
        chip.setTextColor(PetalColors.FG[petal][level])
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(PetalColors.BG[petal][level])
        }
        val h = dp(10f).toInt()
        val v = dp(4f).toInt()
        chip.setPadding(h, v, h, v)
    }

    private fun mirror(b: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    // ---- Share card ----

    private fun share() {
        val card = buildCard()
        val dir = File(act.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "pokerface.png")
        FileOutputStream(file).use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, act.getString(R.string.share_text, shareScore))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(Intent.createChooser(intent, null))
    }

    private fun buildCard(): Bitmap {
        val w = 1080
        val h = 1350
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF161616.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        p.color = 0xFFAAAAAA.toInt()
        p.textSize = 52f
        c.drawText(act.getString(R.string.game_title), w / 2f, 110f, p)

        p.color = Color.WHITE
        p.isFakeBoldText = true
        p.textSize = 190f
        val scoreStr = shareScore.toString()
        val sw = p.measureText(scoreStr)
        p.textAlign = Paint.Align.LEFT
        c.drawText(scoreStr, w / 2f - sw / 2f - 50f, 300f, p)
        p.isFakeBoldText = false
        p.textSize = 64f
        p.color = 0xFF888888.toInt()
        c.drawText("/100", w / 2f + sw / 2f - 40f, 300f, p)
        p.textAlign = Paint.Align.CENTER

        val f = shareFrame
        val box = RectF(240f, 360f, 840f, 1060f)
        if (f != null) {
            val face = mirror(f)
            // Centre-crop the frame into the box.
            val scale = maxOf(box.width() / face.width, box.height() / face.height)
            val dw = face.width * scale
            val dh = face.height * scale
            val dst = RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
            c.save()
            c.clipPath(Path().apply { addRoundRect(box, 40f, 40f, Path.Direction.CW) })
            c.drawBitmap(face, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            p.color = 0xFF2A2A2A.toInt()
            c.drawRoundRect(box, 40f, 40f, p)
        }

        p.color = Color.WHITE
        p.textSize = 50f
        c.drawText(shareLine, w / 2f, 1160f, p)

        p.color = 0xFF777777.toInt()
        p.textSize = 40f
        c.drawText(act.getString(R.string.app_name), w / 2f, 1280f, p)
        return b
    }
}
