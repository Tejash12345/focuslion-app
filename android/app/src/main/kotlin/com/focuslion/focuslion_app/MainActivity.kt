package com.focuslion.focuslion_app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "focuslion/guard"
    private var roarPlayer: MediaPlayer? = null
    private var prevAlarmVol = -1

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "status" -> result.success(
                        mapOf(
                            "usage" to hasUsageAccess(),
                            "overlay" to Settings.canDrawOverlays(this),
                            "battery" to isIgnoringBattery(),
                            "running" to GuardService.running,
                        ),
                    )
                    "openUsage" -> {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        result.success(null)
                    }
                    "openOverlay" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                        result.success(null)
                    }
                    "openBattery" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                        result.success(null)
                    }
                    "requestNotif" -> {
                        if (Build.VERSION.SDK_INT >= 33) {
                            ActivityCompat.requestPermissions(
                                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100,
                            )
                        }
                        result.success(null)
                    }
                    "usage" -> {
                        val prefs = getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
                        val counts =
                            if (prefs.getInt(GuardService.KEY_USAGE_DAY, 0) == GuardService.dayKey()) {
                                prefs.getString(GuardService.KEY_USAGE_COUNTS, "{}") ?: "{}"
                            } else {
                                "{}"
                            }
                        result.success(counts)
                    }
                    "setConfig" -> {
                        val json = call.argument<String>("json") ?: "[]"
                        getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
                            .edit().putString(GuardService.KEY_CONFIG, json).apply()
                        result.success(null)
                    }
                    "start" -> {
                        val json = call.argument<String>("json") ?: "[]"
                        getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(GuardService.KEY_CONFIG, json)
                            .putBoolean("guard_on", true)
                            .apply()
                        startForegroundService(Intent(this, GuardService::class.java))
                        result.success(true)
                    }
                    "stop" -> {
                        getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean("guard_on", false).apply()
                        stopService(Intent(this, GuardService::class.java))
                        result.success(true)
                    }
                    "roar" -> {
                        playRoar()
                        result.success(null)
                    }
                    "setReminders" -> {
                        val json = call.argument<String>("json") ?: "[]"
                        ReminderScheduler.setReminders(this, json)
                        result.success(true)
                    }
                    "getReminders" -> {
                        result.success(
                            getSharedPreferences(ReminderScheduler.PREFS, Context.MODE_PRIVATE)
                                .getString(ReminderScheduler.KEY_REMINDERS, "[]"),
                        )
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            "android:get_usage_stats", Process.myUid(), packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Plays the loud lion roar (alarm stream cranked to max, then restored) so
     *  the 3D lion screen can roar on demand. Same audio the guard uses. */
    private fun playRoar() {
        try {
            roarPlayer?.release()
        } catch (_: Exception) {
        }
        roarPlayer = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (prevAlarmVol < 0) prevAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        } catch (_: Exception) {
        }
        try {
            val mp = MediaPlayer()
            val afd = resources.openRawResourceFd(R.raw.lion_roar) ?: return
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
            mp.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Exception) {
                }
                if (roarPlayer === it) roarPlayer = null
                restoreAlarmVolume()
            }
            mp.prepareAsync()
            roarPlayer = mp
        } catch (_: Exception) {
            restoreAlarmVolume()
        }
    }

    private fun restoreAlarmVolume() {
        val v = prevAlarmVol
        prevAlarmVol = -1
        if (v < 0) return
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, v, 0)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            roarPlayer?.release()
        } catch (_: Exception) {
        }
        roarPlayer = null
        restoreAlarmVolume()
        super.onDestroy()
    }
}
