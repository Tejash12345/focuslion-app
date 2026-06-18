package com.focuslion.focuslion_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Fires at a scheduled reminder time: pops the animated lion overlay over
 * whatever is on screen, roars (loud, on the alarm stream), and auto-dismisses
 * after a few seconds. Works offline and with no watermark.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        if (!Settings.canDrawOverlays(context)) return // can't show without the permission

        val app = context.applicationContext
        val message = intent.getStringExtra("label").let {
            if (it.isNullOrBlank()) "Time for a focus check." else it
        }

        // keep the CPU alive for the few seconds the reminder is on screen
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "focuslion:reminder")
        try {
            wake.acquire(12_000L)
        } catch (_: Exception) {
        }

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val main = Handler(Looper.getMainLooper())

        // alarm-stream roar, cranked to max then restored
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevAlarmVol = try {
            am.getStreamVolume(AudioManager.STREAM_ALARM)
        } catch (_: Exception) {
            -1
        }

        var view: View? = null
        var player: MediaPlayer? = null

        fun cleanup() {
            view?.let {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
            view = null
            try {
                player?.release()
            } catch (_: Exception) {
            }
            player = null
            if (prevAlarmVol >= 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, prevAlarmVol, 0)
                } catch (_: Exception) {
                }
            }
            try {
                if (wake.isHeld) wake.release()
            } catch (_: Exception) {
            }
        }

        main.post {
            val content = buildReminderView(app, message) { cleanup() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE,
            )
            try {
                wm.addView(content, params)
                view = content
            } catch (_: Exception) {
                cleanup()
                return@post
            }

            // roar at full alarm volume
            try {
                if (prevAlarmVol >= 0) {
                    am.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0,
                    )
                }
                val mp = MediaPlayer()
                val afd = app.resources.openRawResourceFd(R.raw.lion_roar)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setVolume(1f, 1f)
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                player = mp
            } catch (_: Exception) {
            }

            // auto-dismiss after the reminder has had its moment
            main.postDelayed({ cleanup() }, 7_000L)
        }
    }
}
