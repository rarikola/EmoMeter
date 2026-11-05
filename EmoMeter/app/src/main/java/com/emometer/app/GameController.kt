package com.emometer.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
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
import kotlin.math.roundToInt
import kotlin.random.Random

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
        const val RELAX_MIN_MS = 1000L
        const val RELAX_MAX_MS = 3000L
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
    private val jokeText: TextView = act.findViewById(R.id.game_text)
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
        jokeText.visibility = View.GONE
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
        (image.drawable as? Animatable)?.stop()
        image.setImageDrawable(null)
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
        val s = items[i]
        counter.text = act.getString(R.string.game_counter, i + 1, items.size)
        progress.progress = i
        relax.visibility = View.GONE
        if (s.isText) {
            image.setImageDrawable(null)
            jokeText.text = s.label
            jokeText.visibility = View.VISIBLE
        } else {
            jokeText.visibility = View.GONE
            val d = Stimuli.drawable(act, s)
            image.setImageDrawable(d)
            if (d is Animatable) d.start()
        }

        val showMs = s.showMs
        timer.animate().cancel()
        timer.pivotX = 0f
        timer.scaleX = 1f
        timer.animate().scaleX(0f).setDuration(showMs).setInterpolator(LinearInterpolator()).start()

        handler.postDelayed({ measuring = i }, LAG_MS)
        handler.postDelayed({ startRelax(i) }, showMs)
    }

    private fun startRelax(i: Int) {
        phase = Phase.RELAX
        (image.drawable as? Animatable)?.stop()
        image.setImageDrawable(null)
        jokeText.visibility = View.GONE
        timer.animate().cancel()
        timer.scaleX = 0f
        relax.text = act.getString(R.string.game_relax)
        relax.visibility = View.VISIBLE
        handler.postDelayed({ if (measuring == i) measuring = -1 }, TAIL_MS)
        // Random gap so the next one can't be braced for.
        val gap = RELAX_MIN_MS + Random.nextLong(RELAX_MAX_MS - RELAX_MIN_MS + 1)
        handler.postDelayed({ showItem(i + 1) }, gap)
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
            leakTitle.text = leak.stimulus.shortLabel
            styleChip(leakChip, "${Plutchik.NAMES[leak.petal]} · ${strengthWord(leak.level)}", leak.petal, leak.level)
            leakChip.visibility = View.VISIBLE
            shareLine = act.getString(R.string.share_leak, leak.stimulus.shortLabel, Plutchik.NAMES[leak.petal])
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
            text = o.stimulus.shortLabel
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

    private fun mirror(b: Bitmap): Bitmap = ShareCard.mirror(b)

    private fun share() {
        ShareCard.share(
            act,
            title = act.getString(R.string.game_title),
            big = shareScore.toString(),
            suffix = "/100",
            line = shareLine,
            frame = shareFrame?.let { mirror(it) },
            text = act.getString(R.string.share_text, shareScore),
        )
    }
}
