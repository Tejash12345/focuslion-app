package com.focuslion.focuslion_app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * While Leo listens, YouTube / music must not play into the microphone (it
 * gets "heard" as commands). Like any voice assistant we take temporary audio
 * focus — players pause (or duck) — and give it back when Leo is done, so
 * they resume by themselves.
 */
object AudioDuck {
    private var request: AudioFocusRequest? = null
    private var held = false

    fun hold(ctx: Context) {
        if (held) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                am.requestAudioFocus(r)
                request = r
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            held = true
        } catch (_: Exception) { }
    }

    fun release(ctx: Context) {
        if (!held) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= 26) request?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
        } catch (_: Exception) { }
        request = null
        held = false
    }
}
