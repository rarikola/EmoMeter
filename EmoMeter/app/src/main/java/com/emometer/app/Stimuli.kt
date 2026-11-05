package com.emometer.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.random.Random

/**
 * One item to react to: a picture (jpg/png/webp), an animated loop (gif or animated webp),
 * or a one-line joke (file "-", the joke is the label).
 * [petal] is the emotion it is expected to cause (-1 = neutral).
 */
data class Stimulus(val file: String, val petal: Int, val label: String) {
    val isText get() = file == "-"
    val isAnimated get() = file.endsWith(".gif", ignoreCase = true) || file.contains("_anim", ignoreCase = true)

    /** Used to remember what was already shown. */
    val key get() = if (isText) "text:${label.hashCode()}" else file

    /** How long it stays on screen: jokes need reading time, loops need a full cycle. */
    val showMs: Long get() = when {
        isText -> 5000L
        isAnimated -> 4000L
        else -> 3000L
    }

    /** Short version for the results list and the share card. */
    val shortLabel get() = if (label.length <= 32) label else label.take(30).trimEnd() + "…"
}

/**
 * Items live in app/src/main/assets/stimuli/ and are listed in stimuli/list.csv:
 *   file,emotion,label
 *   puppy01.jpg,joy,Puppy
 *   goat_roof_anim.webp,surprise,Goat on a car
 *   -,joy,I bought a book on procrastination. I'll read it tomorrow.
 * emotion is one of: joy trust fear surprise sadness disgust anger anticipation neutral
 * The label is everything after the second comma, so jokes may contain commas.
 */
object Stimuli {

    private val POSITIVE = setOf(Plutchik.JOY, Plutchik.TRUST, Plutchik.ANTICIPATION)
    private val NEGATIVE = setOf(Plutchik.FEAR, Plutchik.SADNESS, Plutchik.DISGUST, Plutchik.ANGER)

    /** +1 pleasant, -1 unpleasant, 0 neither (surprise, neutral). */
    private fun mood(petal: Int) = when (petal) {
        in POSITIVE -> 1
        in NEGATIVE -> -1
        else -> 0
    }

    fun load(ctx: Context): List<Stimulus> = try {
        ctx.assets.open("stimuli/list.csv").bufferedReader().readLines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.lowercase().startsWith("file,")) return@mapNotNull null
            val parts = t.split(",", limit = 3).map { it.trim() }
            if (parts.size < 3 || parts[0].isEmpty() || parts[2].isEmpty()) return@mapNotNull null
            val emotion = parts[1].lowercase()
            val petal = if (emotion == "neutral") -1 else Plutchik.NAMES.indexOf(emotion)
            if (petal == -1 && emotion != "neutral") return@mapNotNull null
            Stimulus(parts[0], petal, parts[2])
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Picture or animated loop. Animated files animate on Android 9+, and show their first frame on older phones. */
    fun drawable(ctx: Context, s: Stimulus): Drawable? {
        if (s.isText) return null
        val path = "stimuli/${s.file}"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.assets, path))
            } else {
                ctx.assets.open(path).use { BitmapDrawable(ctx.resources, BitmapFactory.decodeStream(it)) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pick [n] items: unseen ones first (so the whole pool rotates before anything repeats),
     * spread across emotions (at most 4 of one kind), ordered so moods alternate.
     */
    fun pick(all: List<Stimulus>, n: Int, prefs: SharedPreferences, rnd: Random = Random.Default): List<Stimulus> {
        if (all.isEmpty()) return emptyList()
        val count = minOf(n, all.size)
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        val fresh = all.filter { it.key !in seen }

        val chosen = ArrayList<Stimulus>()
        chosen += spread(fresh, count, rnd, cap = 4)
        if (chosen.size < count) {
            // Pool used up: start a new rotation with whatever else is needed.
            seen.clear()
            val rest = all.filter { it !in chosen }
            chosen += spread(rest, count - chosen.size, rnd)
        }
        seen += chosen.map { it.key }
        prefs.edit().putStringSet("seen", seen).apply()
        return order(chosen, rnd)
    }

    /** Round-robin across kinds (each emotion, with jokes as their own kind); at most [cap] per kind. */
    private fun spread(pool: List<Stimulus>, n: Int, rnd: Random, cap: Int = Int.MAX_VALUE): List<Stimulus> {
        val groups = pool.groupBy { Pair(it.petal, it.isText) }.values
            .map { it.shuffled(rnd).take(cap).toMutableList() }
            .shuffled(rnd)
        val out = ArrayList<Stimulus>()
        while (out.size < n && groups.any { it.isNotEmpty() }) {
            for (g in groups) {
                if (out.size >= n) break
                if (g.isNotEmpty()) out += g.removeAt(0)
            }
        }
        return out
    }

    /**
     * Mix moods: after something pleasant prefer something unpleasant and the other way round,
     * never the same emotion twice in a row, and never two jokes in a row.
     */
    private fun order(items: List<Stimulus>, rnd: Random): List<Stimulus> {
        val left = items.shuffled(rnd).toMutableList()
        val out = ArrayList<Stimulus>()
        while (left.isNotEmpty()) {
            val prev = out.lastOrNull()
            val i = if (prev == null) 0 else {
                val last: Stimulus = prev
                val m = mood(last.petal)
                fun ok(s: Stimulus) = s.petal != last.petal && !(s.isText && last.isText)
                left.indexOfFirst { ok(it) && m != 0 && mood(it.petal) == -m }
                    .takeIf { it >= 0 }
                    ?: left.indexOfFirst { ok(it) }.takeIf { it >= 0 }
                    ?: 0
            }
            out += left.removeAt(i)
        }
        return out
    }
}
