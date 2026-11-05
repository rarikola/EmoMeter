package com.emometer.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Make me laugh: a friend gets 60 seconds to crack the player.
 *
 * The friend normally holds the phone and points the back camera at the player, so the player
 * can't see the meters. Flow: 3 s baseline (player relaxes; that face becomes neutral) → 60 s round.
 * A slight smile flashes "almost!"; a clear smile held for 0.3 s is a crack and ends the round.
 * The clock only runs while a face is in view.
 */
class LaughController(
    private val act: MainActivity,
    private val engine: EmotionEngine,
    private val prefs: SharedPreferences,
    private val onExit: () -> Unit,
) {
    companion object {
        const val ROUND_MS = 60_000L
        const val BASELINE_MS = 3000L
        const val WARN_LEVEL = 0.2f      // slight smile
        const val REARM_LEVEL = 0.15f    // back to neutral before the next near-miss counts
        const val CRACK_LEVEL = 0.5f     // clear smile
        const val CRACK_HOLD_MS = 300L
        const val EVENT_LEVEL = 0.5f     // other reactions worth listing
    }

    private enum class Phase { IDLE, BASELINE, PLAY, DONE }

    private class Event(val petal: Int, var value: Float, val atMs: Long)

    private val handler = Handler(Looper.getMainLooper())

    private val group: View = act.findViewById(R.id.laugh_group)
    private val clock: TextView = act.findViewById(R.id.laugh_clock)
    private val flip: Button = act.findViewById(R.id.laugh_flip)
    private val message: TextView = act.findViewById(R.id.laugh_msg)
    private val warning: TextView = act.findViewById(R.id.laugh_warning)
    private val meter: LaughMeterView = act.findViewById(R.id.laugh_meter)

    private val results: View = act.findViewById(R.id.laugh_results)
    private val resTitle: TextView = act.findViewById(R.id.lres_title)
    private val resTime: TextView = act.findViewById(R.id.lres_time)
    private val resBest: TextView = act.findViewById(R.id.lres_best)
    private val resImage: ImageView = act.findViewById(R.id.lres_image)
    private val resList: LinearLayout = act.findViewById(R.id.lres_list)

    private var phase = Phase.IDLE
    private val baseline = ArrayList<FloatArray>()
    private var original: Calibration? = null

    private var elapsed = 0L
    private var lastTick = 0L
    private var crackSince = -1L
    private var nearMisses = 0
    private var armed = true
    private val events = ArrayList<Event>()
    private var openEvent: Event? = null
    private var lastFrame: Bitmap? = null
    private var resultFrame: Bitmap? = null
    private var cracked = false
    private var findingFace = false

    init {
        flip.setOnClickListener {
            val back = !act.isBackCamera
            prefs.edit().putBoolean("laugh_back", back).apply()
            act.setBackCamera(back)
            start()   // a new camera angle needs a new baseline
        }
        act.findViewById<Button>(R.id.lres_again).setOnClickListener { start() }
        act.findViewById<Button>(R.id.lres_share).setOnClickListener { share() }
        act.findViewById<Button>(R.id.lres_home).setOnClickListener { stop(); onExit() }
    }

    fun start() {
        handler.removeCallbacksAndMessages(null)
        if (phase == Phase.PLAY || phase == Phase.BASELINE) original?.let { engine.setCalibration(it) }
        act.setBackCamera(prefs.getBoolean("laugh_back", true))
        original = engine.calibration
        elapsed = 0L
        lastTick = 0L
        crackSince = -1L
        nearMisses = 0
        armed = true
        events.clear()
        openEvent = null
        lastFrame = null
        resultFrame = null
        cracked = false
        findingFace = false

        results.visibility = View.GONE
        group.visibility = View.VISIBLE
        warning.visibility = View.GONE
        meter.setValues(0f, -1, 0f)
        clock.text = format(ROUND_MS)
        updateFlipLabel()
        runBaseline()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (phase == Phase.PLAY || phase == Phase.BASELINE) original?.let { engine.setCalibration(it) }
        phase = Phase.IDLE
        group.visibility = View.GONE
        results.visibility = View.GONE
    }

    private fun updateFlipLabel() {
        flip.text = act.getString(if (act.isBackCamera) R.string.laugh_cam_back else R.string.laugh_cam_front)
    }

    private fun runBaseline() {
        phase = Phase.BASELINE
        baseline.clear()
        message.visibility = View.VISIBLE
        val intro = act.getString(R.string.laugh_baseline)
        for (i in 0..2) {
            handler.postDelayed({ message.text = "$intro\n\n${3 - i}" }, i * 1000L)
        }
        handler.postDelayed({ finishBaseline() }, BASELINE_MS)
    }

    private fun finishBaseline() {
        val base = original
        if (baseline.size < 5 || base == null) {
            message.text = act.getString(R.string.laugh_no_face_baseline)
            handler.postDelayed({ runBaseline() }, 1500L)
            return
        }
        val avg = FloatArray(Shapes.N)
        for (v in baseline) for (i in avg.indices) avg[i] += v[i]
        for (i in avg.indices) avg[i] = avg[i] / baseline.size
        val session = base.copy()
        System.arraycopy(avg, 0, session.neutral, 0, Shapes.N)
        engine.setCalibration(session)

        phase = Phase.PLAY
        lastTick = 0L
        message.text = act.getString(R.string.laugh_go)
        handler.postDelayed({ if (phase == Phase.PLAY) message.visibility = View.GONE }, 1500L)
    }

    /** Called for every camera frame, after the engine has been updated. */
    fun onFrame(face: FloatArray?, frame: Bitmap?) {
        if (phase == Phase.IDLE || phase == Phase.DONE) return
        if (face != null && frame != null) lastFrame = frame

        if (phase == Phase.BASELINE) {
            if (face != null) baseline.add(face)
            return
        }

        val now = SystemClock.uptimeMillis()
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceAtMost(200L)
        lastTick = now

        if (face == null) {
            // Clock pauses until the face is back.
            message.text = act.getString(R.string.laugh_find_face)
            message.visibility = View.VISIBLE
            findingFace = true
            warning.visibility = View.GONE
            crackSince = -1L
            meter.setValues(0f, -1, 0f)
            return
        }
        if (findingFace) {
            message.visibility = View.GONE
            findingFace = false
        }
        elapsed += dt

        val s = engine.strength
        // A soft smile can land on "trust" (it is taught as a warm smile), so both count as smiling.
        val laugh = max(s[Plutchik.JOY], s[Plutchik.TRUST])
        var op = -1
        var ov = 0f
        for (k in 0 until Plutchik.COUNT) {
            if (k == Plutchik.JOY || k == Plutchik.TRUST) continue
            if (s[k] > ov) { ov = s[k]; op = k }
        }
        meter.setValues(laugh, op, ov)

        // Near-misses: each time a slight smile appears after the face was back at neutral.
        if (laugh >= WARN_LEVEL && armed) { nearMisses++; armed = false }
        if (laugh < REARM_LEVEL) armed = true
        warning.visibility = if (laugh >= WARN_LEVEL) View.VISIBLE else View.GONE

        // Other strong reactions, one entry per episode, keeping its peak.
        if (op >= 0 && ov >= EVENT_LEVEL) {
            val e = openEvent
            if (e != null && e.petal == op) {
                if (ov > e.value) e.value = ov
            } else {
                openEvent = Event(op, ov, elapsed).also { events.add(it) }
            }
        } else if (ov < 0.35f) {
            openEvent = null
        }

        // Crack: a clear smile held for a moment.
        if (laugh >= CRACK_LEVEL) {
            if (crackSince < 0) crackSince = now
            if (now - crackSince >= CRACK_HOLD_MS) {
                resultFrame = frame
                finish(true)
                return
            }
        } else {
            crackSince = -1L
        }

        clock.text = format(ROUND_MS - elapsed)
        if (elapsed >= ROUND_MS) finish(false)
    }

    private fun finish(didCrack: Boolean) {
        handler.removeCallbacksAndMessages(null)
        phase = Phase.DONE
        cracked = didCrack
        original?.let { engine.setCalibration(it) }
        if (!didCrack) resultFrame = lastFrame
        if (didCrack && nearMisses > 0) nearMisses--   // the crack itself started as a "slight smile"

        val lasted = if (didCrack) elapsed.coerceAtMost(ROUND_MS) else ROUND_MS
        val best = prefs.getLong("laugh_best", -1L)
        val newBest = lasted > best
        if (newBest) prefs.edit().putLong("laugh_best", lasted).apply()

        group.visibility = View.GONE
        results.visibility = View.VISIBLE
        resTitle.text = act.getString(if (didCrack) R.string.lres_cracked else R.string.lres_survived)
        resTime.text = format(lasted, countUp = true)
        resBest.text = if (newBest) act.getString(R.string.res_new_best) else act.getString(R.string.lres_best, format(best, countUp = true))
        resBest.setTextColor(if (newBest) 0xFF3BD16F.toInt() else 0xFFAAAAAA.toInt())
        resImage.setImageBitmap(resultFrame?.let { oriented(it) })

        resList.removeAllViews()
        if (nearMisses > 0) resList.addView(chip(act.getString(R.string.lres_near_misses, nearMisses), PetalColors.BG[Plutchik.JOY][0], PetalColors.FG[Plutchik.JOY][0]))
        for (e in events.sortedByDescending { it.value }.take(3)) {
            val lvl = maxOf(0, Plutchik.level(e.value))
            val text = act.getString(R.string.lres_event, Plutchik.LEVELS[e.petal][lvl], format(e.atMs, countUp = true))
            resList.addView(chip(text, PetalColors.BG[e.petal][lvl], PetalColors.FG[e.petal][lvl]))
        }
        if (resList.childCount == 0) {
            resList.addView(TextView(act).apply {
                text = act.getString(R.string.lres_nothing)
                setTextColor(0xFF888888.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
        }
    }

    /** Front-camera frames are mirrored so they look like a selfie; back-camera frames are not. */
    private fun oriented(b: Bitmap): Bitmap = if (act.isBackCamera) b else ShareCard.mirror(b)

    private fun share() {
        val lasted = if (cracked) elapsed.coerceAtMost(ROUND_MS) else ROUND_MS
        val seconds = (lasted / 1000).toInt()
        ShareCard.share(
            act,
            title = act.getString(R.string.laugh_title),
            big = format(lasted, countUp = true),
            suffix = "",
            line = act.getString(if (cracked) R.string.share_laugh_cracked else R.string.share_laugh_survived),
            frame = resultFrame?.let { oriented(it) },
            text = act.getString(if (cracked) R.string.share_laugh_text else R.string.share_laugh_text_survived, seconds),
        )
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics)

    private fun chip(text: String, bg: Int, fg: Int): View {
        val t = TextView(act)
        t.text = text
        t.setTextColor(fg)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        t.background = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(bg)
        }
        val h = dp(12f).toInt()
        val v = dp(5f).toInt()
        t.setPadding(h, v, h, v)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8f).toInt()
        t.layoutParams = lp
        return t
    }

    /** m:ss. Countdowns round up (so it shows 1:00 at the start); elapsed times round down. */
    private fun format(ms: Long, countUp: Boolean = false): String {
        val total = if (countUp) (ms / 1000) else ((ms + 999) / 1000)
        val sec = total.coerceAtLeast(0)
        return "%d:%02d".format(sec / 60, sec % 60)
    }
}
