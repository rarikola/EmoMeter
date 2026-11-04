package com.emometer.app

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

/** The 52 MediaPipe face blendshapes, matched by name so their order never matters. */
object Shapes {
    val NAMES = listOf(
        "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
        "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft",
        "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
        "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose",
        "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft", "mouthFrownRight", "mouthFunnel",
        "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft", "mouthPressRight",
        "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower",
        "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight"
    )
    val N = NAMES.size
    val INDEX: Map<String, Int> = NAMES.withIndex().associate { it.value to it.index }

    /** Blinks and gaze direction say nothing about emotion and add noise, so they are ignored. */
    val MASK = FloatArray(N) { i ->
        val n = NAMES[i]
        if (n == "_neutral" || n.startsWith("eyeBlink") || n.startsWith("eyeLook")) 0f else 1f
    }

    /** Build a vector by name; "mouthSmile*" sets both mouthSmileLeft and mouthSmileRight. */
    fun vec(vararg pairs: Pair<String, Float>): FloatArray {
        val v = FloatArray(N)
        for ((key, value) in pairs) {
            if (key.endsWith("*")) {
                val base = key.dropLast(1)
                INDEX[base + "Left"]?.let { v[it] = value }
                INDEX[base + "Right"]?.let { v[it] = value }
            } else {
                INDEX[key]?.let { v[it] = value }
            }
        }
        return v
    }
}

/** Plutchik's 8 petals, in wheel order (clockwise from the top). */
object Plutchik {
    const val JOY = 0
    const val TRUST = 1
    const val FEAR = 2
    const val SURPRISE = 3
    const val SADNESS = 4
    const val DISGUST = 5
    const val ANGER = 6
    const val ANTICIPATION = 7
    const val COUNT = 8

    val NAMES = listOf("joy", "trust", "fear", "surprise", "sadness", "disgust", "anger", "anticipation")

    /** Level names from mild to intense. */
    val LEVELS = listOf(
        listOf("serenity", "joy", "ecstasy"),
        listOf("acceptance", "trust", "admiration"),
        listOf("apprehension", "fear", "terror"),
        listOf("distraction", "surprise", "amazement"),
        listOf("pensiveness", "sadness", "grief"),
        listOf("boredom", "disgust", "loathing"),
        listOf("annoyance", "anger", "rage"),
        listOf("interest", "anticipation", "vigilance"),
    )

    /** Each bar: left petal, right petal (opposites). */
    val AXES = listOf(ANGER to FEAR, ANTICIPATION to SURPRISE, JOY to SADNESS, TRUST to DISGUST)

    /** Blends of two neighbouring petals. */
    data class Blend(val name: String, val a: Int, val b: Int)
    val BLENDS = listOf(
        Blend("optimism", ANTICIPATION, JOY),
        Blend("love", JOY, TRUST),
        Blend("submission", TRUST, FEAR),
        Blend("awe", FEAR, SURPRISE),
        Blend("disapproval", SURPRISE, SADNESS),
        Blend("remorse", SADNESS, DISGUST),
        Blend("contempt", DISGUST, ANGER),
        Blend("aggressiveness", ANGER, ANTICIPATION),
    )

    /** Starting guesses (change from a neutral face) used until the person teaches their own. */
    val DEFAULT_DIRS: Array<FloatArray> = arrayOf(
        Shapes.vec("mouthSmile*" to 0.8f, "cheekSquint*" to 0.4f, "eyeSquint*" to 0.25f, "mouthUpperUp*" to 0.15f),
        Shapes.vec("mouthSmile*" to 0.35f, "mouthDimple*" to 0.3f, "mouthPress*" to 0.2f, "mouthClose" to 0.1f),
        Shapes.vec("browInnerUp" to 0.7f, "eyeWide*" to 0.6f, "mouthStretch*" to 0.5f, "jawOpen" to 0.15f, "browOuterUp*" to 0.2f),
        Shapes.vec("eyeWide*" to 0.6f, "browInnerUp" to 0.55f, "browOuterUp*" to 0.7f, "jawOpen" to 0.6f, "mouthFunnel" to 0.2f),
        Shapes.vec("mouthFrown*" to 0.6f, "browInnerUp" to 0.55f, "mouthShrugLower" to 0.35f, "mouthPress*" to 0.15f),
        Shapes.vec("noseSneer*" to 0.7f, "mouthUpperUp*" to 0.55f, "browDown*" to 0.3f, "eyeSquint*" to 0.3f, "mouthShrugUpper" to 0.25f),
        Shapes.vec("browDown*" to 0.8f, "mouthPress*" to 0.4f, "eyeSquint*" to 0.3f, "noseSneer*" to 0.25f, "jawForward" to 0.1f),
        Shapes.vec("eyeSquint*" to 0.35f, "browDown*" to 0.2f, "mouthPress*" to 0.3f, "mouthRollLower" to 0.2f, "browOuterUp*" to 0.15f),
    )

    /** 0 = mild, 1 = middle, 2 = intense, -1 = not showing. */
    fun level(v: Float): Int = when {
        v < 0.2f -> -1
        v < 0.5f -> 0
        v < 0.85f -> 1
        else -> 2
    }
}

/** What the person taught: their neutral face plus, for each petal, the change from neutral. */
class Calibration(
    val neutral: FloatArray,
    val dirs: Array<FloatArray>,
    val taught: BooleanArray,
) {
    fun copy() = Calibration(neutral.copyOf(), Array(dirs.size) { dirs[it].copyOf() }, taught.copyOf())
    val taughtCount get() = taught.count { it }

    fun save(p: SharedPreferences) {
        val e = p.edit()
        e.putString("neutral", neutral.joinToString(","))
        for (k in dirs.indices) e.putString("dir$k", dirs[k].joinToString(","))
        e.putString("taught", taught.joinToString(",") { if (it) "1" else "0" })
        e.apply()
    }

    companion object {
        fun default() = Calibration(
            FloatArray(Shapes.N),
            Array(Plutchik.COUNT) { Plutchik.DEFAULT_DIRS[it].copyOf() },
            BooleanArray(Plutchik.COUNT),
        )

        fun load(p: SharedPreferences): Calibration {
            val d = default()
            return try {
                val n = p.getString("neutral", null)?.let(::parse) ?: return d
                val dirs = Array(Plutchik.COUNT) { k -> p.getString("dir$k", null)?.let(::parse) ?: d.dirs[k] }
                val t = p.getString("taught", null)?.split(",")?.map { it == "1" }?.toBooleanArray()
                    ?: BooleanArray(Plutchik.COUNT)
                if (n.size != Shapes.N || dirs.any { it.size != Shapes.N } || t.size != Plutchik.COUNT) d
                else Calibration(n, dirs, t)
            } catch (e: Exception) {
                d
            }
        }

        private fun parse(s: String) = s.split(",").map { it.toFloat() }.toFloatArray()
    }
}

/**
 * Turns a live 52-value face vector into 8 petal strengths.
 *
 * The change from the neutral face is explained as a non-negative mix of the 8 taught
 * directions (non-negative least squares). Overlapping petals (a smile is part of both
 * joy and trust) then share the work instead of both firing at once.
 * A strength of 1.0 means "as strong as when you taught it".
 */
class EmotionEngine {
    private var cal: Calibration = Calibration.default()
    private var dirs = Array(Plutchik.COUNT) { FloatArray(Shapes.N) }
    private var gram = Array(Plutchik.COUNT) { FloatArray(Plutchik.COUNT) }
    private val raw = FloatArray(Plutchik.COUNT)

    /** Smoothed strengths, read by the view. */
    val strength = FloatArray(Plutchik.COUNT)

    val calibration get() = cal

    fun setCalibration(c: Calibration) {
        cal = c
        dirs = Array(Plutchik.COUNT) { k -> FloatArray(Shapes.N) { i -> c.dirs[k][i] * Shapes.MASK[i] } }
        gram = Array(Plutchik.COUNT) { a -> FloatArray(Plutchik.COUNT) { b -> dot(dirs[a], dirs[b]) } }
        raw.fill(0f)
    }

    fun update(face: FloatArray, alpha: Float) {
        val delta = FloatArray(Shapes.N) { i -> (face[i] - cal.neutral[i]) * Shapes.MASK[i] }
        val b = FloatArray(Plutchik.COUNT) { k -> dot(dirs[k], delta) }
        // Coordinate descent, warm-started from the previous frame.
        repeat(30) {
            for (k in 0 until Plutchik.COUNT) {
                val gkk = gram[k][k]
                if (gkk < 1e-6f) { raw[k] = 0f; continue }
                var s = b[k]
                for (j in 0 until Plutchik.COUNT) if (j != k) s -= gram[k][j] * raw[j]
                // Small penalty keeps tiny, noisy contributions at zero.
                raw[k] = min(1.5f, max(0f, (s - 0.05f * gkk) / gkk))
            }
        }
        for (k in 0 until Plutchik.COUNT) strength[k] += alpha * (raw[k] - strength[k])
    }

    /** No face in view: let everything fall back toward neutral. */
    fun decay(alpha: Float) {
        for (k in 0 until Plutchik.COUNT) strength[k] -= alpha * strength[k]
        raw.fill(0f)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
