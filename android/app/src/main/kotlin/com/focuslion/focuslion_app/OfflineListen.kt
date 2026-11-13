package com.focuslion.focuslion_app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Leo Live offline ears: Android's own speech recognizer, preferring the
 * on-device model so voice commands still work with no internet (needs the
 * language's offline pack — English is usually already on the phone).
 * Calls [done] exactly once with {ok, msg, text}.
 */
object OfflineListen {
    private var rec: SpeechRecognizer? = null
    private var pending: ((Map<String, Any?>) -> Unit)? = null

    fun listen(activity: Activity, lang: String, done: (Map<String, Any?>) -> Unit) {
        activity.runOnUiThread {
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                done(mapOf("ok" to false, "msg" to "Speech recognition isn't available on this phone"))
                return@runOnUiThread
            }
            // answer a still-open previous request so its caller never hangs
            pending?.invoke(mapOf("ok" to false, "msg" to "cancelled"))
            pending = null
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(activity)
            rec = r
            var finished = false
            var self: ((Map<String, Any?>) -> Unit)? = null
            fun finish(m: Map<String, Any?>) {
                if (finished) return
                finished = true
                if (pending === self) pending = null
                done(m)
                r.destroy()
                if (rec === r) rec = null
            }
            self = { m -> finish(m) }
            pending = self
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                    finish(mapOf("ok" to t.isNotEmpty(), "msg" to t.ifEmpty { "I didn't catch that" }, "text" to t))
                }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for FocusLion"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Offline speech needs the language pack — download it in Settings › Google › Voice"
                        else -> "Speech error ($error)"
                    }
                    finish(mapOf("ok" to false, "msg" to msg))
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (lang.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            r.startListening(i)
        }
    }
}
