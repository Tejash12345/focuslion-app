package com.focuslion.focuslion_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Foreground service that polls the current foreground app every second.
 * If a guarded app is opened outside its allowed window, or its daily limit
 * is used up, it draws a full-screen lion block screen on top of it.
 *
 * The block screen is a TYPE_APPLICATION_OVERLAY window added via WindowManager
 * (the "display over other apps" permission), NOT a launched Activity. Starting
 * an Activity from a background service is a "background activity launch", which
 * Android silently blocks from target SDK 34 onward (Android 14/15/16) — that is
 * why the old BlockActivity never appeared on recent devices. An overlay window
 * can be shown from a background service on every version.
 *
 * Usage counting: the service counts foreground seconds itself while it runs
 * (1-second ticks), persisted across restarts, and seeds the day's starting
 * value once from the exact usage-event log. Android's bucketed usage stats
 * are never used — they overlap and overcount, which made apps get blocked
 * long before their real limit.
 */
class GuardService : Service() {

    companion object {
        @Volatile var running = false
        const val CHANNEL_ID = "focuslion_guard"
        const val ALERT_CHANNEL_ID = "focuslion_alerts"
        const val PREFS = "guard_prefs"
        const val KEY_CONFIG = "config_json"
        const val KEY_USAGE_DAY = "usage_day"
        const val KEY_USAGE_COUNTS = "usage_counts"
        // the rigged, animated lion model shown on the block screen when online
        const val LION_UID = "c87e400e549f40f39a22dff7bf256d34"

        fun dayKey(): Int {
            val c = Calendar.getInstance()
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForeground: String = ""

    // the lion block screen, shown as a system overlay window over the guarded app
    private var overlayView: View? = null
    private var overlayPkg: String? = null
    private var roar: MediaPlayer? = null
    // optional 3D lion WebView shown on the block screen when online
    private var lionWebView: WebView? = null
    // alarm volume we temporarily override while the roar plays, to restore after
    private var prevAlarmVol = -1

    // today's counted foreground time, per package, in milliseconds
    private val usedMs = HashMap<String, Long>()
    private val warnedToday = HashSet<String>()
    private var usageDay = 0
    private var lastTickAt = 0L
    private var lastPersistAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                checkForeground()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification())
        running = true
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        hideBlockOverlay()
        persistUsage()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- core ----------

    private fun checkForeground() {
        // screen off -> nothing is being used, don't count or block
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            lastTickAt = 0L
            return
        }

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 4000, now)
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = event.packageName
            }
        }
        if (pkg != null) lastForeground = pkg
        val current = lastForeground

        ensureDay(now)

        // count this tick toward the foreground app if it's guarded
        val rules = allRules()
        val currentRule = rules.firstOrNull { it.pkg == current }
        if (currentRule != null && lastTickAt > 0L) {
            val delta = (now - lastTickAt).coerceIn(0L, 5000L)
            usedMs[current] = (usedMs[current] ?: 0L) + delta
        }
        lastTickAt = now
        if (now - lastPersistAt > 15_000) persistUsage()

        if (current.isEmpty() || current == packageName) { hideBlockOverlay(); return }
        val rule = currentRule ?: run { hideBlockOverlay(); return }

        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // Reason 1: outside the allowed hours window (if a schedule is set).
        // Windows may wrap past midnight (e.g. 9 PM - 1 AM).
        val inWindow =
            if (rule.fromMin <= rule.untilMin) nowMin >= rule.fromMin && nowMin < rule.untilMin
            else nowMin >= rule.fromMin || nowMin < rule.untilMin
        val blockedBySchedule = rule.scheduled && !inWindow

        // Reason 2: today's counted usage has reached the daily limit
        val usedTodayMs = usedMs[current] ?: 0L
        val blockedByLimit = rule.dailyLimit > 0 && usedTodayMs >= rule.dailyLimit * 60_000L

        // friendly reminder 5 minutes before the limit (once per app per day)
        if (!blockedBySchedule && !blockedByLimit && rule.dailyLimit > 0) {
            val remainingMs = rule.dailyLimit * 60_000L - usedTodayMs
            if (remainingMs <= 5 * 60_000L && !warnedToday.contains(current)) {
                warnedToday.add(current)
                postWarning(rule.label, ((remainingMs + 59_999) / 60_000L).toInt())
            }
        }

        if (!blockedBySchedule && !blockedByLimit) {
            hideBlockOverlay()
            return
        }

        // blocked! draw the lion screen over the offending app
        showBlockOverlay(
            pkg = current,
            appName = rule.label,
            reason = if (blockedBySchedule) "schedule" else "limit",
            window = formatWindow(rule.fromMin, rule.untilMin),
            limit = rule.dailyLimit,
            usedMin = (usedTodayMs / 60_000L).toInt(),
        )
    }

    // ---------- block overlay ----------

    /**
     * Adds the full-screen lion block window over whatever is on screen.
     * Idempotent: if it's already showing for [pkg], does nothing (so the
     * 1-second tick doesn't stack windows or restart the roar).
     */
    private fun showBlockOverlay(
        pkg: String,
        appName: String,
        reason: String,
        window: String,
        limit: Int,
        usedMin: Int,
    ) {
        if (overlayView != null && overlayPkg == pkg) return
        // an existing overlay for a different app — replace it cleanly
        if (overlayView != null) hideBlockOverlay()

        if (!Settings.canDrawOverlays(this)) return // permission revoked — nothing we can do

        val content = buildBlockView(this, appName, reason, window, limit, usedMin) { goHome() }

        // a focusable container so we can intercept BACK and keep the user locked
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) goHome()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.addView(content)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // WebGL for the 3D lion
            PixelFormat.OPAQUE,
        )

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(root, params)
            overlayView = root
            overlayPkg = pkg
            playRoar()
            // when online, drop the real 3D animated lion in over the emoji
            if (isOnline()) injectLion3D(content)
        } catch (_: Exception) {
        }
    }

    /** Loads the animated 3D lion into the block screen's lion box (online only).
     *  The native animated emoji underneath stays as an instant, offline fallback. */
    private fun injectLion3D(content: View) {
        val box = content.findViewWithTag<FrameLayout>("lionBox") ?: return
        try {
            val wv = WebView(this)
            wv.setBackgroundColor(Color.TRANSPARENT)
            wv.alpha = 0f
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun onReady() {
                    handler.post {
                        // fade the 3D lion in and hide the emoji behind it
                        wv.animate().alpha(1f).setDuration(400).start()
                        (box.getChildAt(0) as? TextView)?.animate()?.alpha(0f)
                            ?.setDuration(300)?.start()
                    }
                }
            }, "Lion3D")
            wv.loadDataWithBaseURL(
                "https://sketchfab.com/", lion3dHtml(), "text/html", "utf-8", null,
            )
            box.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            lionWebView = wv
        } catch (_: Exception) {
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /** Sketchfab viewer page that auto-plays the lion's roar then loops idle. */
    private fun lion3dHtml(): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}
#f{width:100%;height:100%;border:0;display:block}</style>
<script src="https://static.sketchfab.com/api/sketchfab-viewer-1.12.1.js"></script>
</head><body>
<iframe id="f" allow="autoplay;fullscreen;xr-spatial-tracking" allowfullscreen></iframe>
<script>
  var client = new Sketchfab('1.12.1', document.getElementById('f'));
  var api=null, animDur=0, segTimer=null, segStart=0, segEnd=0, onEnd=null, TF=500;
  // start on the roar, then cycle through every move on a loop
  var SEQ=[[230,279],[5,64],[70,99],[105,124],[130,159],[165,194],[200,224],[285,324],[330,349],[355,500]];
  var idx=0;
  function clearSeg(){ if(segTimer){clearInterval(segTimer); segTimer=null;} onEnd=null; }
  function runSeg(sf, ef, done){
    if(!api) return; clearSeg();
    segStart=(sf/TF)*animDur; segEnd=(ef/TF)*animDur; onEnd=done;
    api.setSpeed(1); api.seekTo(segStart); api.play();
    segTimer=setInterval(function(){
      api.getCurrentTime(function(e,t){
        if(e||t==null) return;
        if(t>=segEnd-0.02 || t<segStart-0.4){ var cb=onEnd; clearSeg(); if(cb) cb(); }
      });
    }, 60);
  }
  function step(){ var s=SEQ[idx]; idx=(idx+1)%SEQ.length; runSeg(s[0], s[1], step); }
  client.init('$LION_UID', {
    success:function(a){ api=a; api.start();
      api.addEventListener('viewerready', function(){
        try{ api.setCycleMode('loopOne'); }catch(e){}
        api.getCameraLookAt(function(err, c){
          if(err||!c) return;
          var p=c.position, t=c.target, f=0.95;
          api.setCameraLookAt([t[0]+(p[0]-t[0])*f, t[1]+(p[1]-t[1])*f, t[2]+(p[2]-t[2])*f], t, 0);
        });
        api.getAnimations(function(err, anims){
          if(!err && anims && anims.length){
            animDur=anims[0][2]||0;
            api.setCurrentAnimationByUID(anims[0][0], function(){
              if(animDur>0){ step(); }
              try{ Lion3D.onReady(); }catch(e){}
            });
          } else { try{ Lion3D.onReady(); }catch(e){} }
        });
      });
    },
    error:function(){},
    autostart:1, autospin:0, ui_infos:0, ui_controls:0, ui_stop:0,
    ui_watermark:1, ui_ar:0, ui_help:0, ui_settings:0, ui_vr:0,
    ui_fullscreen:0, ui_annotations:0, ui_hint:0, transparent:1
  });
</script></body></html>
"""

    /**
     * Plays the real lion roar over the block screen. Uses the ALARM usage so it
     * is heard even when media is muted or another app is playing audio, and
     * holds the player in [roar] so it isn't garbage-collected mid-playback.
     */
    private fun playRoar() {
        try {
            roar?.release()
        } catch (_: Exception) {
        }
        roar = null
        maxAlarmVolume()
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
            mp.isLooping = false
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Exception) {
                }
                if (roar === it) roar = null
                restoreAlarmVolume()
            }
            mp.prepareAsync()
            roar = mp
        } catch (_: Exception) {
            restoreAlarmVolume()
        }
    }

    /** Crank the alarm stream to its maximum so the roar is loud regardless of
     *  the user's volume slider. The previous level is restored afterwards. */
    private fun maxAlarmVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (prevAlarmVol < 0) prevAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (_: Exception) {
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

    private fun hideBlockOverlay() {
        val v = overlayView ?: return
        overlayView = null
        overlayPkg = null
        try {
            lionWebView?.apply {
                loadUrl("about:blank")
                destroy()
            }
        } catch (_: Exception) {
        }
        lionWebView = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        try {
            roar?.release()
        } catch (_: Exception) {
        }
        roar = null
        restoreAlarmVolume()
    }

    /** Send the user to the home screen, then take the lion screen down. */
    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
        }
        hideBlockOverlay()
    }

    // ---------- daily usage bookkeeping ----------

    /** Roll the day over: restore persisted counts, seed from the event log once. */
    private fun ensureDay(now: Long) {
        val day = dayKey()
        if (day == usageDay) return
        usageDay = day
        usedMs.clear()
        warnedToday.clear()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_USAGE_DAY, 0) == day) {
            // service restarted on the same day — pick up where we left off
            try {
                val o = JSONObject(prefs.getString(KEY_USAGE_COUNTS, "{}") ?: "{}")
                for (k in o.keys()) usedMs[k] = o.optLong(k, 0L)
            } catch (_: Exception) {
            }
        }

        // seed each guarded app from the exact event log (covers usage from
        // before the guard was running today); keep whichever is larger
        for (rule in allRules()) {
            val fromEvents = usageMsFromEvents(rule.pkg, now)
            if (fromEvents > (usedMs[rule.pkg] ?: 0L)) usedMs[rule.pkg] = fromEvents
        }
        persistUsage()
    }

    private fun persistUsage() {
        lastPersistAt = System.currentTimeMillis()
        try {
            val o = JSONObject()
            for ((k, v) in usedMs) o.put(k, v)
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_USAGE_DAY, usageDay)
                .putString(KEY_USAGE_COUNTS, o.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Exact foreground milliseconds for [pkg] since local midnight, from raw events. */
    private fun usageMsFromEvents(pkg: String, now: Long): Long {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val events = usm.queryEvents(cal.timeInMillis, now)
            val e = UsageEvents.Event()
            val resumedActivities = HashSet<String>()
            var foregroundSince = 0L
            var total = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName != pkg) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (resumedActivities.isEmpty()) foregroundSince = e.timeStamp
                        resumedActivities.add(e.className ?: "")
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        resumedActivities.remove(e.className ?: "")
                        if (resumedActivities.isEmpty() && foregroundSince != 0L) {
                            total += e.timeStamp - foregroundSince
                            foregroundSince = 0L
                        }
                    }
                }
            }
            if (resumedActivities.isNotEmpty() && foregroundSince != 0L) total += now - foregroundSince
            total
        } catch (_: Exception) {
            0L
        }
    }

    // ---------- config ----------

    private data class Rule(
        val pkg: String, val label: String,
        val scheduled: Boolean, val fromMin: Int, val untilMin: Int,
        val dailyLimit: Int,
    )

    private fun allRules(): List<Rule> {
        val json = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, "[]") ?: "[]"
        val out = ArrayList<Rule>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("enabled")) continue
                out.add(
                    Rule(
                        o.optString("pkg"),
                        o.optString("label", o.optString("pkg")),
                        o.optBoolean("scheduled", false),
                        o.optInt("fromMin", 0),
                        o.optInt("untilMin", 1439),
                        o.optInt("dailyLimit", 0),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun formatWindow(from: Int, until: Int): String {
        fun f(m: Int): String {
            val h = m / 60
            val mm = m % 60
            val ampm = if (h >= 12) "PM" else "AM"
            val hh = if (h % 12 == 0) 12 else h % 12
            return String.format("%d:%02d %s", hh, mm, ampm)
        }
        return "${f(from)} – ${f(until)}"
    }

    // ---------- notification ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "FocusLion Guard",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps the lion watching your guarded apps" }
        nm.createNotificationChannel(ch)
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID, "Time reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Warns you a few minutes before an app gets blocked" }
        nm.createNotificationChannel(alerts)
    }

    private fun postWarning(appLabel: String, minutesLeft: Int) {
        try {
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("🦁 $minutesLeft min left for $appLabel")
                .setContentText("Your daily time is almost up — wrap it up before the lion roars!")
                .setSmallIcon(applicationInfo.icon)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(appLabel.hashCode(), n)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🦁 FocusLion Guard is active")
            .setContentText("Guarding your apps — tap to manage")
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
