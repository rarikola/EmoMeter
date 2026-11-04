package com.emometer.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.random.Random

/** One picture: file in assets/stimuli/, the emotion it is expected to cause (-1 = neutral), a short label. */
data class Stimulus(val file: String, val petal: Int, val label: String)

/**
 * Pictures live in app/src/main/assets/stimuli/ and are listed in stimuli/list.csv:
 *   file,emotion,label
 *   puppy01.jpg,joy,Puppy
 * emotion is one of: joy trust fear surprise sadness disgust anger anticipation neutral
 */
object Stimuli {

    fun load(ctx: Context): List<Stimulus> = try {
        ctx.assets.open("stimuli/list.csv").bufferedReader().readLines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.lowercase().startsWith("file,")) return@mapNotNull null
            val parts = t.split(",", limit = 3).map { it.trim() }
            if (parts.size < 3) return@mapNotNull null
            val emotion = parts[1].lowercase()
            val petal = if (emotion == "neutral") -1 else Plutchik.NAMES.indexOf(emotion)
            if (petal < -1 || (petal == -1 && emotion != "neutral")) return@mapNotNull null
            Stimulus(parts[0], petal, parts[2])
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun bitmap(ctx: Context, s: Stimulus): Bitmap? = try {
        ctx.assets.open("stimuli/${s.file}").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Pick [n] pictures: unseen ones first (so the whole pool rotates before anything repeats),
     * spread across emotions, and never the same emotion twice in a row when avoidable.
     */
    fun pick(all: List<Stimulus>, n: Int, prefs: SharedPreferences, rnd: Random = Random.Default): List<Stimulus> {
        if (all.isEmpty()) return emptyList()
        val count = minOf(n, all.size)
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        val fresh = all.filter { it.file !in seen }

        val chosen = ArrayList<Stimulus>()
        chosen += spread(fresh, count, rnd, cap = 4)
        if (chosen.size < count) {
            // Pool used up: start a new rotation with whatever else is needed.
            seen.clear()
            val rest = all.filter { it !in chosen }
            chosen += spread(rest, count - chosen.size, rnd)
        }
        seen += chosen.map { it.file }
        prefs.edit().putStringSet("seen", seen).apply()
        return order(chosen, rnd)
    }

    /** Round-robin across emotions so a session gets a mix; at most [cap] per emotion. */
    private fun spread(pool: List<Stimulus>, n: Int, rnd: Random, cap: Int = Int.MAX_VALUE): List<Stimulus> {
        val groups = pool.groupBy { it.petal }.values
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

    /** Shuffle, avoiding the same emotion back to back. */
    private fun order(items: List<Stimulus>, rnd: Random): List<Stimulus> {
        val left = items.shuffled(rnd).toMutableList()
        val out = ArrayList<Stimulus>()
        while (left.isNotEmpty()) {
            val last = out.lastOrNull()?.petal
            val i = left.indexOfFirst { it.petal != last }.let { if (it < 0) 0 else it }
            out += left.removeAt(i)
        }
        return out
    }
}
