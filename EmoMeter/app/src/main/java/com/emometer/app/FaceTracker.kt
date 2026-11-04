package com.emometer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Runs MediaPipe Face Landmarker on camera frames and reports the 52 blendshape scores
 * (or null when no face is found). Results arrive on a MediaPipe thread.
 */
class FaceTracker(context: Context, private val onResult: (FloatArray?, Bitmap?) -> Unit) {

    val usingGpu: Boolean
    private val landmarker: FaceLandmarker
    private var lastTs = 0L

    /** The most recent upright camera frame; handed out with each result (used for the "frozen face"). */
    @Volatile private var lastFrame: Bitmap? = null

    init {
        var gpu = true
        val lm = try {
            build(context, Delegate.GPU)
        } catch (e: Throwable) {
            Log.w("EmoMeter", "GPU delegate failed, using CPU", e)
            gpu = false
            build(context, Delegate.CPU)
        }
        landmarker = lm
        usingGpu = gpu
    }

    private fun build(context: Context, delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handle(result) }
            .setErrorListener { e -> Log.e("EmoMeter", "Face landmarker error", e) }
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    /** Called on the camera executor. Always closes the frame. */
    fun process(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = try {
            image.toBitmap()
        } finally {
            image.close()
        }
        val upright = if (rotation == 0) bitmap else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        lastFrame = upright
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        try {
            landmarker.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            Log.e("EmoMeter", "detectAsync failed", e)
        }
    }

    private fun handle(result: FaceLandmarkerResult) {
        val shapes = result.faceBlendshapes()
        val frame = lastFrame
        if (!shapes.isPresent || shapes.get().isEmpty()) {
            onResult(null, frame)
            return
        }
        val v = FloatArray(Shapes.N)
        for (c in shapes.get()[0]) {
            Shapes.INDEX[c.categoryName()]?.let { v[it] = c.score() }
        }
        onResult(v, frame)
    }

    fun close() = landmarker.close()
}
