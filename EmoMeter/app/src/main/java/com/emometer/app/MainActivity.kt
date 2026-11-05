package com.emometer.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private enum class Mode { HOME, GAME, LAUGH, METER }

    private lateinit var previewView: PreviewView
    private lateinit var homeGroup: View
    private lateinit var meterGroup: View
    private lateinit var meter: EmoMeterView
    private lateinit var status: TextView
    private lateinit var prompt: TextView
    private lateinit var teachBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var homeBest: TextView
    private lateinit var homeInfo: TextView
    private lateinit var game: GameController
    private lateinit var laugh: LaughController

    /** Which camera is in use. Make me laugh defaults to the back camera; everything else uses the front. */
    var isBackCamera = false
        private set

    private val engine = EmotionEngine()
    private var tracker: FaceTracker? = null
    private lateinit var cameraExecutor: ExecutorService
    private val prefs by lazy { getSharedPreferences("emometer", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.HOME

    // Smoothing per frame (~24 fps): 0.3 settles in roughly a quarter of a second.
    private val alpha = 0.3f

    private var frames = 0
    private var fpsStart = 0L
    private var fps = 0
    private var faceVisible = false

    // ---- Teach-me state ----
    private data class Step(val petal: Int, val title: String, val hint: String)

    private val steps = listOf(
        Step(-1, "Neutral", "Relax your face and look at the camera."),
        Step(Plutchik.JOY, "Joy", "A big, real smile."),
        Step(Plutchik.TRUST, "Trust", "A warm, soft smile, like greeting a friend."),
        Step(Plutchik.FEAR, "Fear", "Eyebrows up and together, eyes wide, lips stretched back."),
        Step(Plutchik.SURPRISE, "Surprise", "Eyebrows high, eyes wide, mouth open."),
        Step(Plutchik.SADNESS, "Sadness", "Inner eyebrows up, corners of the mouth down."),
        Step(Plutchik.DISGUST, "Disgust", "Wrinkle your nose and lift your upper lip."),
        Step(Plutchik.ANGER, "Anger", "Eyebrows down and together, lips pressed."),
        Step(Plutchik.ANTICIPATION, "Anticipation", "Focused, waiting for something: eyes narrowed, lips pressed."),
    )
    private var teaching = false
    private var stepIndex = 0
    private var recording = false
    private val samples = ArrayList<FloatArray>()
    private var pending: Calibration? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        homeGroup = findViewById(R.id.home_group)
        meterGroup = findViewById(R.id.meter_group)
        meter = findViewById(R.id.meter)
        status = findViewById(R.id.status)
        prompt = findViewById(R.id.prompt)
        teachBtn = findViewById(R.id.teach)
        resetBtn = findViewById(R.id.reset)
        homeBest = findViewById(R.id.home_best)
        homeInfo = findViewById(R.id.home_info)

        engine.setCalibration(Calibration.load(prefs))
        cameraExecutor = Executors.newSingleThreadExecutor()
        game = GameController(this, engine, prefs) { showHome() }
        laugh = LaughController(this, engine, prefs) { showHome() }

        findViewById<Button>(R.id.home_start).setOnClickListener { startGame() }
        findViewById<Button>(R.id.home_laugh).setOnClickListener { startLaugh() }
        findViewById<Button>(R.id.home_meter).setOnClickListener { showMeter() }
        findViewById<Button>(R.id.meter_home).setOnClickListener { showHome() }
        teachBtn.setOnClickListener { if (teaching) skipStep() else startTeach() }
        resetBtn.setOnClickListener { if (teaching) cancelTeach() else resetCalibration() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == Mode.HOME) finish() else showHome()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
        showHome()
    }

    // ---- Modes ----

    private fun showHome() {
        if (teaching) cancelTeach()
        game.stop()
        laugh.stop()
        setBackCamera(false)
        mode = Mode.HOME
        meterGroup.visibility = View.GONE
        homeGroup.visibility = View.VISIBLE
        val best = prefs.getInt("best", -1)
        homeBest.text = if (best < 0) "" else getString(R.string.home_best, best)
        val laughBest = prefs.getLong("laugh_best", -1L)
        findViewById<TextView>(R.id.home_laugh_best).text =
            if (laughBest < 0) "" else getString(R.string.home_laugh_best, "%d:%02d".format(laughBest / 60000, (laughBest / 1000) % 60))
        val n = Stimuli.load(this).size
        homeInfo.text = if (n == 0) getString(R.string.home_no_pictures)
        else getString(R.string.home_info, minOf(GameController.PICTURES, n), n)
    }

    private fun startGame() {
        if (!game.start()) {
            Toast.makeText(this, R.string.home_no_pictures, Toast.LENGTH_LONG).show()
            return
        }
        mode = Mode.GAME
        homeGroup.visibility = View.GONE
        meterGroup.visibility = View.GONE
    }

    private fun startLaugh() {
        mode = Mode.LAUGH
        homeGroup.visibility = View.GONE
        meterGroup.visibility = View.GONE
        laugh.start()
    }

    private fun showMeter() {
        mode = Mode.METER
        homeGroup.visibility = View.GONE
        meterGroup.visibility = View.VISIBLE
        updateStatus()
    }

    // ---- Camera ----

    private fun startCamera() {
        cameraExecutor.execute {
            val t = try {
                FaceTracker(this) { v, frame -> runOnUiThread { onFace(v, frame) } }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.model_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            tracker = t
            runOnUiThread { bindCamera(t) }
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null

    private fun bindCamera(t: FaceTracker) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            rebind(t)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebind(t: FaceTracker) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(cameraExecutor) { img -> t.process(img) }
        val selector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, selector, preview, analysis)
        } catch (e: Exception) {
            // No such camera on this phone: fall back to the front one.
            isBackCamera = false
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }
    }

    /** Switch between back and front camera (no-op if already there). */
    fun setBackCamera(back: Boolean) {
        if (isBackCamera == back) return
        isBackCamera = back
        tracker?.let { rebind(it) }
    }

    private fun onFace(v: FloatArray?, frame: Bitmap?) {
        countFps()
        faceVisible = v != null
        if (v == null) {
            engine.decay(alpha)
        } else {
            if (recording) samples.add(v)
            engine.update(v, alpha)
        }
        when (mode) {
            Mode.METER -> {
                meter.setStrengths(engine.strength)
                if (!teaching) updateStatus()
            }
            Mode.GAME -> game.onFrame(v, frame)
            Mode.LAUGH -> laugh.onFrame(v, frame)
            Mode.HOME -> Unit
        }
    }

    private fun countFps() {
        val now = SystemClock.uptimeMillis()
        if (fpsStart == 0L) fpsStart = now
        frames++
        if (now - fpsStart >= 1000) {
            fps = frames
            frames = 0
            fpsStart = now
        }
    }

    private fun updateStatus() {
        val gpu = if (tracker?.usingGpu == false) "CPU" else "GPU"
        val face = if (faceVisible) "$fps fps · $gpu" else getString(R.string.no_face)
        val n = engine.calibration.taughtCount
        val taught = if (n == 0) getString(R.string.not_taught) else getString(R.string.taught_n, n)
        status.text = "$face\n$taught"
    }

    // ---- Teach-me ----

    private fun startTeach() {
        teaching = true
        pending = engine.calibration.copy()
        stepIndex = 0
        teachBtn.text = getString(R.string.skip)
        resetBtn.text = getString(R.string.cancel)
        status.text = getString(R.string.teaching)
        prompt.visibility = View.VISIBLE
        runStep()
    }

    private fun runStep() {
        handler.removeCallbacksAndMessages(null)
        recording = false
        if (stepIndex >= steps.size) {
            finishTeach()
            return
        }
        val s = steps[stepIndex]
        val head = "${stepIndex + 1}/${steps.size}  ${s.title}\n${s.hint}"
        for (i in 0..2) {
            handler.postDelayed({ prompt.text = "$head\n\nget ready… ${3 - i}" }, i * 1000L)
        }
        handler.postDelayed({
            samples.clear()
            recording = true
            prompt.text = "$head\n\nhold it…"
        }, 3000L)
        handler.postDelayed({
            recording = false
            capture(s)
        }, 8000L)
    }

    private fun capture(s: Step) {
        val cal = pending ?: return
        if (samples.size < 5) {
            prompt.text = getString(R.string.no_face_retry)
            handler.postDelayed({ runStep() }, 1500L)
            return
        }
        val avg = FloatArray(Shapes.N)
        for (v in samples) for (i in avg.indices) avg[i] += v[i]
        for (i in avg.indices) avg[i] = avg[i] / samples.size

        if (s.petal < 0) {
            // A new neutral face: keep every direction as-is (they are changes from neutral).
            System.arraycopy(avg, 0, cal.neutral, 0, Shapes.N)
        } else {
            for (i in avg.indices) cal.dirs[s.petal][i] = avg[i] - cal.neutral[i]
            cal.taught[s.petal] = true
        }
        stepIndex++
        runStep()
    }

    private fun skipStep() {
        stepIndex++
        runStep()
    }

    private fun finishTeach() {
        val cal = pending
        if (cal != null) {
            cal.save(prefs)
            engine.setCalibration(cal)
        }
        endTeach()
        prompt.visibility = View.VISIBLE
        prompt.text = getString(R.string.saved)
        handler.postDelayed({ prompt.visibility = View.GONE }, 1500L)
    }

    private fun cancelTeach() {
        endTeach()
        prompt.visibility = View.GONE
    }

    private fun endTeach() {
        handler.removeCallbacksAndMessages(null)
        teaching = false
        recording = false
        pending = null
        teachBtn.text = getString(R.string.teach)
        resetBtn.text = getString(R.string.reset)
        updateStatus()
    }

    private fun resetCalibration() {
        val d = Calibration.default()
        d.save(prefs)
        engine.setCalibration(d)
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        game.stop()
        laugh.stop()
        cameraExecutor.execute { tracker?.close() }
        cameraExecutor.shutdown()
    }
}
