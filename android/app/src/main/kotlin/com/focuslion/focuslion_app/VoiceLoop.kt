package com.focuslion.focuslion_app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Leo's offline ears: ONE microphone loop that listens for "Hey Leo" and then
 * hears the command — without losing words.
 *
 * The old approach stopped the wake recognizer and started a second one after
 * "Hey Leo"; that took a few hundred ms, so in "hey leo scroll down" the word
 * "scroll" was lost and Leo only heard "down". Here recent audio is kept in
 * memory and replayed into a command recognizer that is created up front, and
 * the hardware buffer holds 3 s so nothing overflows while it catches up.
 *
 * All Vosk calls happen on this loop's own thread; callbacks go to the main thread.
 */
class VoiceLoop(
    private val model: Model,
    private val onWake: () -> Unit,
    private val onCommand: (String) -> Unit,
    private val onEnd: () -> Unit,
) {
    companion object {
        /** only one loop may own the microphone (and use the model) at a time */
        val mic = Any()

        private val WAKE = Regex("\\b(hey|hi|okay|ok|hello) leo\\b")
        private val WAKE_WORD = Regex("\\b(leo|lio|leon|leos)\\b")
        private val NOISE = setOf("", "leo", "hey", "hey leo", "the", "a", "huh", "uh", "um", "hi", "hello")

        /** "the hey leo scroll down" / "hey lio scroll down" -> "scroll down" */
        fun stripWake(text: String): String {
            val t = text.trim().lowercase()
            val words = t.split(Regex("\\s+"))
            val head = words.take(5).joinToString(" ")
            val last = WAKE_WORD.findAll(head).lastOrNull() ?: return t
            return t.substring(last.range.last + 1).trim().trimStart(',', '.', ' ')
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "leo-voice").apply { isDaemon = true; start() }
    }

    /** Asks the loop to stop; it releases the microphone within ~0.2 s. Never blocks the caller. */
    fun stop() {
        running = false
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() = synchronized(mic) {
        val rate = 16000
        val chunk = 3200 // 0.2 s per read
        var rec: AudioRecord? = null
        var wakeRec: Recognizer? = null
        var cmdRec: Recognizer? = null
        try {
            if (!running) return@synchronized
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // 3 s of hardware buffer: no overflow while the command recognizer catches up
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2 * 3))
            if (rec.state != AudioRecord.STATE_INITIALIZED) return@synchronized
            // grammar = only the wake phrases: tiny, accurate, low battery
            wakeRec = Recognizer(model, rate.toFloat(), "[\"hey leo\", \"hi leo\", \"okay leo\", \"hello leo\", \"ok leo\", \"[unk]\"]")
            // created up front so nothing is lost while it would be built later
            cmdRec = Recognizer(model, rate.toFloat())
            rec.startRecording()
            val ring = ArrayDeque<ShortArray>() // recent audio
            val buf = ShortArray(chunk)
            var capturing = false
            var capStart = 0L
            var spoke = false

            fun finish(text: String) {
                val cmd = stripWake(text)
                capturing = false
                cmdRec?.reset()
                wakeRec?.reset()
                ring.clear()
                main.post { onCommand(if (cmd in NOISE) "" else cmd) }
            }

            while (running) {
                val n = rec.read(buf, 0, chunk)
                if (n < 0) break // mic taken (call / other app) or dead: end, the service restarts us
                if (n == 0) { Thread.sleep(20); continue }
                val piece = buf.copyOf(n)
                ring.addLast(piece)
                while (ring.size > 10) ring.removeFirst()

                if (!capturing) {
                    val w = wakeRec ?: break
                    val heard = if (w.acceptWaveForm(piece, n)) text(w.result, "text") else text(w.partialResult, "partial")
                    if (WAKE.containsMatchIn(heard)) {
                        capturing = true
                        capStart = System.currentTimeMillis()
                        spoke = false
                        main.post { onWake() }
                        // replay the recent audio so the start of the command isn't lost
                        val r = cmdRec ?: break
                        r.reset()
                        for (p in ring.toList().takeLast(6)) r.acceptWaveForm(p, p.size)
                        w.reset()
                    }
                    continue
                }

                val r = cmdRec ?: break
                val now = System.currentTimeMillis()
                if (r.acceptWaveForm(piece, n)) {
                    val t = text(r.result, "text")
                    if (stripWake(t) !in NOISE) { finish(t); continue }
                } else {
                    if (stripWake(text(r.partialResult, "partial")) !in NOISE) spoke = true
                }
                // nothing said after "Hey Leo" -> hand over quickly (Leo listens himself)
                if (!spoke && now - capStart > 3000) { finish(""); continue }
                if (now - capStart > 7000) { finish(text(r.finalResult, "text")); continue }
            }
        } catch (_: Exception) {
            // mic busy / model problem: reported through onEnd, the service restarts us
        } finally {
            try { rec?.stop() } catch (_: Exception) { }
            try { rec?.release() } catch (_: Exception) { }
            try { wakeRec?.close() } catch (_: Exception) { }
            try { cmdRec?.close() } catch (_: Exception) { }
            running = false
            main.post { onEnd() }
        }
    }

    private fun text(json: String?, key: String): String =
        try { JSONObject(json ?: "{}").optString(key).trim().lowercase() } catch (_: Exception) { "" }
}
