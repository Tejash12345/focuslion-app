package com.focuslion.focuslion_app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Voice Lock: Leo reacts only to his owner's voice.
 *
 * The owner records their voice once; an on-device speaker model (Vosk
 * x-vectors, nothing leaves the phone) turns it into a "voice print". Every
 * "Hey Leo" and every follow-up command is compared with it; other voices
 * (people nearby, TV, YouTube) are ignored.
 */
object VoicePrint {
    const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    const val SPK_DIR = "vosk-spk"
    private const val PREFS = "leo_voice"

    /** similarity needed for each strictness level (0 relaxed, 1 normal, 2 strict) */
    private val LEVELS = doubleArrayOf(0.25, 0.33, 0.45)

    /** last comparison (for settings: "voice match 63%") */
    @Volatile var lastScore: Double = -1.0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun locked(ctx: Context) = prefs(ctx).getBoolean("lock", false) && print(ctx) != null
    fun wanted(ctx: Context) = prefs(ctx).getBoolean("lock", false)
    fun strict(ctx: Context) = prefs(ctx).getInt("strict", 1).coerceIn(0, 2)

    fun setLock(ctx: Context, on: Boolean, strict: Int?) {
        val e = prefs(ctx).edit().putBoolean("lock", on)
        if (strict != null) e.putInt("strict", strict.coerceIn(0, 2))
        e.apply()
    }

    fun print(ctx: Context): FloatArray? = try {
        val s = prefs(ctx).getString("print", null)
        if (s == null) null else JSONArray(s).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
    } catch (_: Exception) { null }

    fun save(ctx: Context, v: FloatArray) {
        val a = JSONArray()
        for (x in v) a.put(x.toDouble())
        prefs(ctx).edit().putString("print", a.toString()).putBoolean("lock", true).apply()
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove("print").putBoolean("lock", false).apply() }

    /** x-vector of a Vosk result (null if the result has none) + how many frames it covers */
    fun vector(json: String?): Pair<FloatArray, Int>? = try {
        val o = JSONObject(json ?: "{}")
        val a = o.optJSONArray("spk")
        if (a == null || a.length() == 0) null
        else Pair(FloatArray(a.length()) { a.getDouble(it).toFloat() }, o.optInt("spk_frames", 0))
    } catch (_: Exception) { null }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return -1.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) -1.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /** average several vectors (each normalised first) into one voice print */
    fun average(list: List<FloatArray>): FloatArray {
        val n = list.first().size
        val out = FloatArray(n)
        for (v in list) {
            var len = 0.0
            for (x in v) len += x * x
            val l = sqrt(len).toFloat().takeIf { it > 0f } ?: 1f
            for (i in 0 until n) out[i] += v[i] / l
        }
        for (i in 0 until n) out[i] /= list.size
        return out
    }

    /**
     * Is this result the owner's voice? true when Voice Lock is off.
     * Short phrases ("scroll down") carry less voice detail: a bit more lenient.
     */
    fun isOwner(ctx: Context, json: String?): Boolean {
        if (!locked(ctx)) return true
        val mine = print(ctx) ?: return true
        val (v, frames) = vector(json) ?: run { lastScore = -1.0; return false }
        val score = cosine(mine, v)
        lastScore = score
        val need = LEVELS[strict(ctx)] - (if (frames < 120) 0.05 else 0.0)
        val ok = score >= need
        // learn: a clear match nudges the saved voice print toward how you sound
        // now (this phone's mic, your room) — Leo gets better at knowing you
        if (ok && score >= need + 0.1 && frames >= 100) save(ctx, average(listOf(mine, mine, mine, mine, mine, mine, mine, mine, mine, v)))
        return ok
    }
}
