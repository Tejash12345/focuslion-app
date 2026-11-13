package com.focuslion.focuslion_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * "Hey Leo" wake word — OPT-IN, and only while FocusLion is open on screen
 * (MainActivity stops it in onPause). Uses the phone's on-device recognizer in
 * a quiet loop; nothing is sent anywhere until the wake phrase is heard. Then
 * it stops and hands the rest of the sentence ("hey leo, open whatsapp" ->
 * "open whatsapp") to the web app, which opens Leo.
 */
object WakeListen {
    private var rec: SpeechRecognizer? = null
    private var running = false
    private var heard: ((String) -> Unit)? = null
    private var act: Activity? = null
    private val main = Handler(Looper.getMainLooper())
    private var muted = false

    private val WAKE = Regex(
        "(?:^|\\s)(?:hey|hi|hello|ok|okay|hay|he)\\s*(?:leo|lio|liyo|leyo|neo)\\b|^\\s*(?:leo|lio|liyo|leyo)\\b|హే\\s*లియో|హాయ్\\s*లియో|हे\\s*लियो|हाय\\s*लियो",
        RegexOption.IGNORE_CASE,
    )

    fun start(activity: Activity, onHeard: (String) -> Unit) {
        main.post {
            heard = onHeard
            act = activity
            if (running) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) return@post
            running = true
            muteBeeps(activity, true)
            loop()
        }
    }

    fun stop() {
        main.post {
            running = false
            main.removeCallbacksAndMessages(null)
            rec?.destroy()
            rec = null
            act?.let { muteBeeps(it, false) }
        }
    }

    /** The recognizer chimes on every restart — keep it quiet while looping. */
    private fun muteBeeps(ctx: Context, on: Boolean) {
        if (on == muted) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            try { am.adjustStreamVolume(s, dir, 0) } catch (_: Exception) { /* DND may block — fine */ }
        }
        muted = on
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        main.postDelayed({ if (running) loop() }, delayMs)
    }

    private fun loop() {
        val a = act ?: return
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(a)
        rec = r
        var fired = false
        fun check(text: String, final: Boolean): Boolean {
            val m = WAKE.find(text) ?: return false
            if (!final) return true // wait for the full sentence
            fired = true
            running = false
            muteBeeps(a, false)
            val rest = text.substring(m.range.last + 1).trim().trimStart(',', '.', ' ')
            r.destroy()
            if (rec === r) rec = null
            heard?.invoke(rest)
            return true
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { check(it, false) }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (!check(t, true)) restart(150)
            }
            override fun onError(error: Int) {
                if (fired) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(1500)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> restart(4000)
                    else -> restart(250) // no match / timeout: just keep listening
                }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        try { r.startListening(i) } catch (_: Exception) { restart(1000) }
    }
}
