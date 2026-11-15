package com.focuslion.focuslion_app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Leo everywhere: a floating lion bubble over other apps, plus an OFFLINE
 * "Hey Leo" wake word (Vosk — on-device, grammar limited to the wake phrase,
 * no beeps, nothing leaves the phone) that works with FocusLion closed.
 *
 *  • Tap the bubble or say "Hey Leo" -> FocusLion opens with Leo listening.
 *  • Drag to move; long-press to hide until FocusLion is opened again.
 *  • Listens only while the screen is on and FocusLion is NOT on screen
 *    (inside the app the in-app wake word handles it).
 * Android requires the "Leo is ready" notification for background mic use.
 */
class LeoBubbleService : Service() {

    companion object {
        const val EXTRA_WAKE = "wake"
        private const val ACTION_STOP = "com.focuslion.leo.STOP"
        private const val CHANNEL = "leo_bubble"
        private const val NOTIF_ID = 4711
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
        private const val MODEL_DIR = "vosk-en-in"

        @Volatile var instance: LeoBubbleService? = null
        @Volatile var appVisible = false
        /** What was said after "Hey Leo" (or "" for a bubble tap) — the web app takes it. */
        @Volatile var pendingWake: String? = null
        /** What Leo last heard after "Hey Leo" and what he did — shown in settings. */
        @Volatile var lastHeard: String = ""
        @Volatile var lastResult: String = ""

        const val EXTRA_BUBBLE = "bubble"

        /** wake = background "Hey Leo"; bubble = show the floating lion (either or both). */
        fun start(ctx: Context, wake: Boolean, bubble: Boolean = true) {
            val i = Intent(ctx, LeoBubbleService::class.java).putExtra(EXTRA_WAKE, wake).putExtra(EXTRA_BUBBLE, bubble)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            // turned off by the user: don't bring it back
            ctx.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).edit().putBoolean("on", false).apply()
            ctx.stopService(Intent(ctx, LeoBubbleService::class.java))
        }

        // ---------- Leo log: what happened, kept across restarts (shown in settings) ----------
        private val events = ArrayDeque<String>()

        fun ev(msg: String) {
            android.util.Log.i("Leo", msg)
            val line = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + msg
            synchronized(events) {
                if (events.isEmpty()) instance?.let { s ->
                    s.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).getString("log", "")
                        ?.split('|')?.filter { it.isNotBlank() }?.forEach { events.addLast(it) }
                }
                events.addLast(line.replace('|', '/'))
                while (events.size > 40) events.removeFirst()
                instance?.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)?.edit()?.putString("log", events.joinToString("|"))?.apply()
            }
        }

        fun log(): String = synchronized(events) { events.toList().takeLast(25).reversed().joinToString("\n") }

        fun markAppVisible(v: Boolean) {
            appVisible = v
            // FocusLion is on screen: Android lets us (re)claim background mic access now
            if (v) instance?.regainMic()
            instance?.refresh()
        }

        fun takeWake(): String? { val p = pendingWake; pendingWake = null; return p }

        /** For Leo's settings: is "Hey Leo" really listening right now? */
        fun status(): Map<String, Any?> {
            val s = instance
            return mapOf(
                "running" to (s != null),
                "listening" to (s?.voice != null),
                "lastHeard" to lastHeard, "lastResult" to lastResult,
                "model" to (s?.model != null),
                "wake" to (s?.wakeWanted == true),
                "enroll" to (s?.enrollState ?: ""),
                "log" to log(),
                "appVisible" to appVisible,
                "screenOn" to (s?.screenOn ?: true),
                "lockWaiting" to (s != null && VoicePrint.locked(s) && s.spkModel == null),
                "lastEnd" to (s?.lastEnd ?: ""),
                "micBlocked" to (s?.micBlocked == true),
            )
        }
        /** A FocusLion page the Leo pop-up asked the full app to open. */
        @Volatile var pendingRoute: String? = null
        fun takeRoute(): String? { val p = pendingRoute; pendingRoute = null; return p }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var hiddenByUser = false
    private var wakeWanted = false
    private var bubbleWanted = true
    private var model: Model? = null
    /** Voice Lock speaker model (loaded only when Voice Lock is used) */
    @Volatile private var spkModel: SpeakerModel? = null
    @Volatile var enrollState = ""
    /** why the mic loop last stopped by itself (shown in settings) */
    @Volatile var lastEnd = ""
    /** Android blocked our background mic (we were restarted while FocusLion was closed) */
    @Volatile var micBlocked = false
    private var silentEnds = 0
    @Volatile private var destroyed = false
    @Volatile private var spkLoading = false
    /** the Voice Lock model couldn't load: listen without it rather than not at all */
    @Volatile private var spkFailed = false
    private var voice: VoiceLoop? = null
    private var screenOn = true
    private var lastFire = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            refresh()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).edit().putBoolean("on", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        // a null intent = Android restarted us after killing the process: use the saved choice
        val prefs = getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
        wakeWanted = intent?.getBooleanExtra(EXTRA_WAKE, wakeWanted) ?: prefs.getBoolean("wake", false)
        bubbleWanted = intent?.getBooleanExtra(EXTRA_BUBBLE, bubbleWanted) ?: prefs.getBoolean("bubble", true)
        prefs.edit().putBoolean("on", true).putBoolean("wake", wakeWanted).putBoolean("bubble", bubbleWanted).apply()
        hiddenByUser = false
        if (intent != null && intent.action == null) {
            // a fresh start (from FocusLion / the fix screen): new mic loop with fresh mic access
            silentEnds = 0
            micBlocked = false
            if (!capturing) stopWake()
        }
        try {
            goForeground(if (wakeWanted && model == null) "Getting “Hey Leo” ready…" else readyText())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (bubbleWanted) showBubble() else removeBubble()
        if (wakeWanted) prepareModel() else stopWake()
        refresh()
        // ask Android to bring us back if it kills the process (battery savers do)
        return START_STICKY
    }

    // swiping FocusLion away from Recents must not stop "Hey Leo"
    override fun onTaskRemoved(rootIntent: Intent?) {
        ev("FocusLion closed from recent apps")
        // some phones (Motorola, Xiaomi, Oppo…) kill the whole app right after this:
        // ask Android to start Leo again in a few seconds
        scheduleRestart(4000)
        refresh()
    }

    /** Bring Leo back after `ms` if he was killed (only while the user has him turned on). */
    private fun scheduleRestart(ms: Long) {
        val prefs = getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("on", false)) return
        try {
            val i = Intent(applicationContext, LeoBubbleService::class.java)
                .putExtra(EXTRA_WAKE, prefs.getBoolean("wake", false)).putExtra(EXTRA_BUBBLE, prefs.getBoolean("bubble", true))
                .putExtra("restart", true)
            val pi = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(applicationContext, 9, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else PendingIntent.getService(applicationContext, 9, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val at = System.currentTimeMillis() + ms
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ev("Leo service started")
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
                if (ttsReady) {
                    try { tts?.language = java.util.Locale.US } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenRx, f)
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        main.post {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leo-bubble-${System.currentTimeMillis()}")
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        val stillWanted = getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).getBoolean("on", false)
        ev(if (stillWanted) "Leo was stopped by Android — restarting" else "Leo turned off")
        if (stillWanted) scheduleRestart(5000)
        destroyed = true
        instance = null
        main.removeCallbacksAndMessages(null)
        stopWake()
        AudioDuck.release(this) // never leave the user's music paused
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        // free the model only after the mic loop has really finished with it
        model?.let { m -> Thread { synchronized(VoiceLoop.mic) { try { m.close() } catch (_: Exception) {} } }.start() }
        model = null
        spkModel?.let { s -> Thread { synchronized(VoiceLoop.mic) { try { s.close() } catch (_: Exception) {} } }.start() }
        spkModel = null
        removeBubble()
        super.onDestroy()
    }

    private fun readyText() = when {
        wakeWanted && bubbleWanted -> "Say “Hey Leo” or tap the lion"
        wakeWanted -> "Say “Hey Leo” anytime"
        else -> "Tap the lion to talk to Leo"
    }

    // ---------- foreground notification ----------
    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Leo assistant", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, LeoBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🦁 Leo is ready")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            (if (wakeWanted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= 30 && wakeWanted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            if (wakeWanted && Build.VERSION.SDK_INT >= 34) micBlocked = false
        } catch (e: Exception) {
            if (type == 0 || !wakeWanted) throw e
            // Android 14+ refuses background mic for a service restarted while FocusLion
            // was closed: keep running without it and ask the user for one tap
            val noMic = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, micOffNotif(), noMic)
            micBlocked = true
        }
    }

    /** the "tap to turn Hey Leo back on" notification (opens FocusLion for a moment) */
    private fun micOffNotif(): Notification {
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🦁 Tap to turn “Hey Leo” back on")
            .setContentText("Android paused Leo's microphone after the app was closed — one tap fixes it")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    /** FocusLion is on screen: claim the background microphone again (allowed now). */
    fun regainMic() {
        if (!wakeWanted) return
        if (!micBlocked && silentEnds == 0) return
        micBlocked = false
        silentEnds = 0
        lastEnd = ""
        // re-start the service while FocusLion is on screen: Android grants the mic again
        try { start(this, true, bubbleWanted) } catch (_: Exception) { }
    }

    private var lastMicFix = 0L

    /** Mic is muted by Android: open the invisible fix screen (needs "display over other apps"). */
    private fun fixMicSilently(): Boolean {
        val now = System.currentTimeMillis()
        if (!screenOn || appVisible || !Settings.canDrawOverlays(this) || now - lastMicFix < 90_000) return false
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (km.isKeyguardLocked) return false // wait until the phone is unlocked
        lastMicFix = now
        ev("mic muted by Android - restarting from the foreground")
        return try {
            startActivity(Intent(this, MicFixActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            true
        } catch (_: Exception) { false }
    }

    private fun updateNotif(text: String) {
        try { goForeground(text) } catch (_: Exception) {}
    }

    // ---------- the floating bubble ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        val wmgr = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = wmgr
        val size = (58 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this)
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(0xFF7DD3FC.toInt(), 0xFF2563EB.toInt(), 0xFF1E1B4B.toInt())
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = size * 0.7f
            setStroke((2 * resources.displayMetrics.density).toInt(), 0xCCBAE6FD.toInt())
        }
        frame.elevation = 12f
        val img = ImageView(this)
        img.setImageResource(R.drawable.lion)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (8 * resources.displayMetrics.density).toInt()
        img.setPadding(pad, pad, pad, pad)
        frame.addView(img, FrameLayout.LayoutParams(size, size))

        val prefs = getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
        val p = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 24)
            y = prefs.getInt("y", (resources.displayMetrics.heightPixels * 0.55).toInt())
        }
        params = p

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var downAt = 0L; var moved = false
        val longPress = Runnable {
            if (!moved) {
                hiddenByUser = true
                refresh()
                Toast.makeText(this, "Leo bubble hidden — opens again with FocusLion", Toast.LENGTH_SHORT).show()
            }
        }
        frame.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; downAt = System.currentTimeMillis(); moved = false
                    main.postDelayed(longPress, 650)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) {
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                        try { wmgr.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (moved) {
                        // snap to the nearest side edge
                        val w = resources.displayMetrics.widthPixels
                        p.x = if (p.x + size / 2 < w / 2) 8 else w - size - 8
                        try { wmgr.updateViewLayout(v, p) } catch (_: Exception) {}
                        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
                    } else if (e.action == MotionEvent.ACTION_UP && System.currentTimeMillis() - downAt < 600 && !hiddenByUser) {
                        openLeo("")
                    }
                }
            }
            true
        }
        try { wmgr.addView(frame, p); bubble = frame } catch (_: Exception) { bubble = null }
    }

    private fun removeBubble() {
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    /** Show/hide the bubble and pause/resume listening for the current state. */
    fun refresh() {
        main.post {
            val outside = !appVisible && screenOn
            bubble?.visibility = if (outside && !hiddenByUser) View.VISIBLE else View.GONE
            // fully RELEASE the microphone while Leo / FocusLion is on screen —
            // a merely paused recorder still holds it and Leo would hear silence
            if (outside && wakeWanted && !capturing) { if (voice == null) startWake() }
            else if (!capturing) stopWake()
        }
    }

    /** Bring up ONLY Leo (the pop-up window), listening or running `rest`. */
    private fun openLeo(rest: String) {
        pendingWake = rest
        launchLeo()
    }

    /** Show the Leo pop-up right now (it stays alive between uses, so this is instant). */
    private fun launchLeo() {
        val now = System.currentTimeMillis()
        if (now - lastFire < 1200) return
        lastFire = now
        val i = Intent(this, LeoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(i) } catch (_: Exception) {}
    }

    // ---------- offline "Hey Leo" (Vosk) ----------
    private fun prepareModel() {
        if (model != null) { refresh(); return }
        Thread {
            try {
                val dir = File(filesDir, MODEL_DIR)
                val ready = File(dir, ".ready")
                if (!ready.exists()) {
                    updateNotif("Downloading the “Hey Leo” voice model (one time, ~36 MB)…")
                    download(MODEL_URL, dir)
                    ready.createNewFile()
                }
                val root = dir.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").exists() } ?: dir
                val m = Model(root.absolutePath)
                // Voice Lock on: the speaker model too (before listening starts)
                if (VoicePrint.locked(this)) try { loadSpk() } catch (_: Exception) { }
                main.post {
                    model = m
                    updateNotif(readyText())
                    refresh() // starts listening only if FocusLion isn't on screen
                }
            } catch (e: Exception) {
                main.post { updateNotif("“Hey Leo” needs internet once to download — tap the lion meanwhile") }
            }
        }.start()
    }

    private fun download(url: String, dir: File) {
        dir.deleteRecursively(); dir.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        conn.inputStream.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(dir, entry.name)
                    if (!out.canonicalPath.startsWith(dir.canonicalPath)) throw SecurityException("bad zip entry")
                    if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                    entry = zip.nextEntry
                }
            }
        }
    }

    /** Loads (downloading once, ~13 MB) the on-device speaker model for Voice Lock. Background thread only. */
    @Synchronized private fun loadSpk(): SpeakerModel {
        spkModel?.let { return it }
        val dir = File(filesDir, VoicePrint.SPK_DIR)
        val ready = File(dir, ".ready")
        if (!ready.exists()) {
            download(VoicePrint.SPK_URL, dir)
            ready.createNewFile()
        }
        val root = dir.walkTopDown().maxDepth(2).firstOrNull { File(it, "mfcc.conf").exists() } ?: dir
        val s = SpeakerModel(root.absolutePath)
        spkModel = s
        return s
    }

    /**
     * Voice Lock setup: records the owner for ~12 s (on-device) and saves their
     * voice print. Progress is shown through status()["enroll"].
     */
    fun enrollVoice(): String {
        val m = model ?: return "The voice model is still getting ready — try again in a minute"
        if (enrollState == "preparing" || enrollState == "recording") return "Already recording"
        enrollState = "preparing"
        main.post { stopWake() }
        Thread {
            try {
                val s = loadSpk()
                val vecs = ArrayList<FloatArray>()
                var frames = 0
                synchronized(VoiceLoop.mic) {
                    if (destroyed) throw IllegalStateException("stopped")
                    enrollState = "recording"
                    val rate = 16000
                    val minBuf = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    @SuppressLint("MissingPermission")
                    val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                        android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2))
                    var r: org.vosk.Recognizer? = null
                    try {
                        r = org.vosk.Recognizer(m, rate.toFloat(), s)
                        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microphone busy")
                        rec.startRecording()
                        val buf = ShortArray(3200)
                        val end = System.currentTimeMillis() + 12_000
                        fun take(json: String?) {
                            val v = VoicePrint.vector(json) ?: return
                            if (v.second >= 40) { vecs += v.first; frames += v.second }
                        }
                        while (System.currentTimeMillis() < end) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) { Thread.sleep(20); continue }
                            if (r.acceptWaveForm(buf, n)) take(r.result)
                        }
                        take(r.finalResult)
                    } finally {
                        try { rec.stop() } catch (_: Exception) { }
                        rec.release()
                        try { r?.close() } catch (_: Exception) { }
                    }
                }
                enrollState = if (vecs.isEmpty() || frames < 250) "failed: I didn't hear enough of your voice — speak the whole time and try again"
                else { VoicePrint.save(this, VoicePrint.average(vecs)); "done" }
            } catch (e: Exception) {
                enrollState = "failed: ${e.message ?: "error"} — needs internet once to download the voice-ID model"
            }
            main.post { restartWake() }
        }.start()
        return "ok"
    }

    /** Voice Lock was switched / re-recorded: restart listening with the new setting. */
    fun restartWake() {
        stopWake()
        refresh() // startWake loads the speaker model first if needed
    }

    private fun startWake() {
        val m = model ?: return
        if (voice != null || capturing) return
        if (enrollState == "preparing" || enrollState == "recording") return
        val lock = VoicePrint.locked(this)
        if (lock && spkModel == null && !spkFailed) {
            // load the speaker model first (once at a time), then try again
            if (!spkLoading) {
                spkLoading = true
                Thread {
                    try { loadSpk() } catch (_: Exception) {
                        spkFailed = true
                        ev("Voice Lock model missing (needs internet once) — listening without Voice Lock")
                    }
                    spkLoading = false
                    main.post { refresh() }
                }.start()
            }
            return
        }
        lateinit var loop: VoiceLoop
        // callbacks from an old (stopped) loop are ignored: only the current one counts
        loop = VoiceLoop(m,
            onWake = { if (voice === loop) onWakeHeard() },
            onCommand = { cmd, audio, conf -> if (voice === loop) onCommandHeard(cmd, audio, conf) },
            onEnd = {
                if (voice === loop) {
                    // the loop ended by itself (mic taken by a call, error): clean up, retry soon
                    voice = null
                    lastEnd = loop.endReason
                    ev("mic loop ended: ${loop.endReason}")
                    if (loop.endReason == "silent") {
                        // Android muted us: fix it invisibly; if that's not possible, ask for one tap
                        if (++silentEnds >= 1 && fixMicSilently()) { }
                        else if (silentEnds >= 2 && !micBlocked) {
                            micBlocked = true
                            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, micOffNotif()) } catch (_: Exception) { }
                        }
                    } else if (loop.endReason.isEmpty()) silentEnds = 0
                    if (capturing) { capturing = false; listeningUi(false); AudioDuck.release(this) }
                    // mic blocked / silent: retry slowly (another app may just be recording)
                    main.postDelayed({ refresh() }, if (silentEnds > 0 || loop.endReason == "mic") 10_000 else 2000)
                }
            },
            // a video / song is playing: the wake word must be extra clear
            isNoisy = { (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive },
            onFollow = { said, free -> if (voice === loop) onFollowHeard(said, free) },
            spk = if (lock) spkModel else null,
            owner = { json -> VoicePrint.isOwner(this, json) },
            onStranger = {
                ev("ignored a voice that is not the owner (score ${VoicePrint.lastScore})")
                if (voice === loop) {
                    val pct = (VoicePrint.lastScore * 100).toInt()
                    lastResult = "ignored — not your voice" + (if (pct > -100) " (match $pct%)" else "")
                }
            },
        )
        voice = loop
        ev("mic loop started (voiceLock=$lock)")
        loop.start()
        main.postDelayed({ if (voice === loop && loop.endReason.isEmpty()) lastEnd = "" }, 6000) // running fine
    }

    // ---------- after "Hey Leo" ----------
    @Volatile private var capturing = false

    /** True while the command after "Hey Leo" is still being heard. */
    fun isCapturing() = capturing

    /** "Hey Leo" spotted: feedback now; the same mic loop keeps hearing the command. */
    /** Was a video / music playing when "Hey Leo" was heard? (then unclear input is ignored) */
    private var mediaWasPlaying = false

    private fun onWakeHeard() {
        ev("wake heard (appVisible=$appVisible screenOn=$screenOn)")
        if (appVisible || !screenOn) return
        mediaWasPlaying = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
        capturing = true
        pendingWake = null
        buzz()
        listeningUi(true) // the bubble glows while Leo listens for the command
        AudioDuck.hold(this) // pause YouTube/music so it isn't heard as the command
    }

    /** The command after "Hey Leo" ("" = nothing said: Leo's window listens himself). */
    private fun onCommandHeard(cmd: String, audio: ShortArray? = null, conf: Double = 1.0) {
        if (!capturing) return
        handleCommand(cmd, audio, conf)
        ev("command: " + lastResult + " (offline sure: " + (conf * 100).toInt() + "%)")
    }

    private fun handleCommand(cmd: String, audio: ShortArray?, conf: Double) {
        capturing = false
        listeningUi(false)
        val clean = cmd.trim()

        // 1. Clear memory voice command: instant local handling
        if (clean.matches(Regex("^(clear|reset|forget)( (leo|your|the))? (memory|skills|all|everything)$", RegexOption.IGNORE_CASE))) {
            AudioDuck.release(this)
            getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).edit().remove("log").apply()
            synchronized(events) { events.clear() }
            LeoActivity.instance?.runInBackground("(typeof window.clearLeoBrain=='function'&&(window.clearLeoBrain(),1))||0") {}
            buzz()
            lastResult = "memory cleared"
            ev("Leo: memory cleared")
            speak("Memory cleared.")
            LeoActivity.instance?.let { it.runOnUiThread { it.speak("Memory cleared.", "en", 0.95f) } }
            refresh()
            return
        }

        // 2. Instant screen command execution on the phone (10ms latency)
        if (clean.isNotEmpty() && (QuickCommands.isScreenCommand(clean) || conf >= 0.70)) {
            val handled = try { QuickCommands.tryRun(this, clean) } catch (_: Exception) { false }
            if (handled) {
                lastResult = "done on screen"
                ev("Leo: done on screen (\"$clean\")")
                AudioDuck.release(this)
                buzz()
                startFollowing()
                refresh()
                return
            }
        }

        if (clean.isNotEmpty() && LeoAccessibility.instance == null && QuickCommands.isScreenCommand(clean)) {
            AudioDuck.release(this)
            lastResult = "needs Accessibility"
            askForAccessibility()
            refresh()
            return
        }

        // 3. Media noise filtering
        if (mediaWasPlaying && (clean.isEmpty() || clean.split(" ").size < 2)) {
            AudioDuck.release(this)
            lastResult = "ignored (media playing)"
            refresh()
            return
        }

        // 4. LeoActivity foreground execution if active on screen
        val leo = LeoActivity.instance
        if (audio != null && isOnline() && leo != null && !leo.isFinishing && !leo.isDestroyed && appVisible) {
            lastHeard = clean
            if (sendAudioToLeo(audio, clean, conf)) {
                lastResult = "Leo is listening to your words (cloud)"
                startFollowing()
                refresh()
                return
            }
        }

        // 5. Try delivering to background LeoActivity if running and ready
        if (clean.isNotEmpty() && sendToLeo(clean)) {
            lastResult = "handled by Leo in the background"
            startFollowing()
            refresh()
            return
        }

        // 6. Otherwise (window closed, AI question, or empty wake word): launch visible Leo window
        AudioDuck.release(this)
        lastResult = if (clean.isEmpty()) "opened Leo (no command heard)" else "opened Leo: $clean"
        pendingWake = clean
        launchLeo()
        refresh()
    }

    /** Start Leo's window in the background with a command (no window popping up). */
    private fun launchLeoHidden(cmd: String) {
        AudioDuck.hold(this)
        voice?.muteFollow(25_000)
        main.removeCallbacks(releaseDuck)
        main.postDelayed(releaseDuck, 30_000)
        val i = Intent(this, LeoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(LeoActivity.EXTRA_HIDDEN_CMD, cmd.ifEmpty { " " })
        try { startActivity(i) } catch (_: Exception) { pendingWake = cmd; launchLeo() }
    }

    /**
     * Leo's window was opened once and lives in the background: give him the
     * command there (he answers out loud and acts) — the window does NOT pop up.
     * If the page can't take it, the window opens with the command instead.
     */
    private fun sendToLeo(cmd: String): Boolean {
        val leo = LeoActivity.instance ?: return false
        // opening apps from the background needs the overlay (bubble) permission
        if (LeoAccessibility.instance == null && !Settings.canDrawOverlays(this)) return false
        AudioDuck.hold(this) // keep the video/music quiet while Leo answers
        voice?.muteFollow(25_000) // his own voice must not become a follow-up command
        main.removeCallbacks(releaseDuck)
        main.postDelayed(releaseDuck, 25_000) // safety: never keep it paused for long
        return leo.deliverInBackground(cmd) {
            main.post {
                main.removeCallbacks(releaseDuck)
                releaseDuck.run()
                pendingWake = cmd
                launchLeo()
            }
        }
    }

    /** Internet that really works (not just "connected to Wi-Fi")? */
    private fun isOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }

    /** Was something actually said (not just silence/hum)? */
    private fun hasSpeech(a: ShortArray): Boolean {
        if (a.size < 16000 * 0.8) return false
        var loud = 0
        var i = 6400 // skip the tail of "leo" at the start of the clip
        while (i + 1600 <= a.size) {
            var sum = 0.0
            for (j in i until i + 1600) sum += a[j].toDouble() * a[j]
            if (Math.sqrt(sum / 1600) > 600) loud++
            i += 1600
        }
        return loud >= 3 // at least ~0.3 s of voice
    }

    /** 16 kHz mono PCM -> "data:audio/wav;base64,…" */
    private fun wavDataUrl(a: ShortArray): String {
        // quiet phone mics: raise the volume evenly (never clipping) so Whisper
        // doesn't treat the speech as silence and invent text
        var peak = 1
        for (x in a) { val v = kotlin.math.abs(x.toInt()); if (v > peak) peak = v }
        val gain = (26000.0 / peak).coerceIn(1.0, 16.0)
        val data = a.size * 2
        val bb = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(16000); bb.putInt(32000); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        for (x in a) bb.putShort((x * gain).coerceIn(-32768.0, 32767.0).toInt().toShort())
        return "data:audio/wav;base64," + android.util.Base64.encodeToString(bb.array(), android.util.Base64.NO_WRAP)
    }

    /** Give the command's audio to Leo's window (running hidden) to hear with Whisper. */
    private fun sendAudioToLeo(audio: ShortArray, hint: String, conf: Double = 0.0): Boolean {
        if (LeoAccessibility.instance == null && !Settings.canDrawOverlays(this)) return false
        val url = wavDataUrl(audio)
        val js = "(typeof window.__flWakeAudio=='function'&&(window.__flWakeAudio(" + JSONObject.quote(url) + "," + JSONObject.quote(hint) + "," + mediaWasPlaying + "," + "%.2f".format(java.util.Locale.US, conf) + "),1))||0"
        AudioDuck.hold(this)
        voice?.muteFollow(25_000)
        main.removeCallbacks(releaseDuck)
        main.postDelayed(releaseDuck, 30_000)
        val leo = LeoActivity.instance
        if (leo != null) {
            return leo.runInBackground(js) { main.post { // page not ready: fall back to the rough text
                releaseDuck.run()
                handleCommand(hint, null, 1.0)
            } }
        }
        LeoActivity.pendingJs = js
        LeoActivity.pendingHint = hint
        launchLeoHidden("")
        return true
    }

    private val releaseDuck = Runnable {
        voice?.muteFollow(0)
        if (!capturing && !appVisible) AudioDuck.release(this)
    }

    /** Will this command go to Leo in the background (not a quick screen command)? */
    private fun sendable(cmd: String): Boolean =
        LeoActivity.instance != null && !QuickCommands.isScreenCommand(cmd) &&
            (LeoAccessibility.instance != null || Settings.canDrawOverlays(this))

    /** Leo finished speaking in the background window: music/video may resume. */
    fun leoSpokeEnd() {
        main.removeCallbacks(releaseDuck)
        main.postDelayed(releaseDuck, 600)
    }

    // ---------- follow-up mode: more commands without saying "Hey Leo" again ----------
    private val FOLLOW_MS = 45_000L
    private val endFollow = Runnable { following(false) }

    /** follow-ups can chain, but at most this long after the last "Hey Leo" */
    private val FOLLOW_CAP = 4 * 60_000L
    private var followStart = 0L
    private var followOn = false

    private fun startFollowing() {
        followStart = System.currentTimeMillis()
        extendFollowing()
    }

    private fun extendFollowing() {
        if (System.currentTimeMillis() - followStart > FOLLOW_CAP) { stopFollowing(); return }
        voice?.followFor(FOLLOW_MS)
        following(true)
        main.removeCallbacks(endFollow)
        main.postDelayed(endFollow, FOLLOW_MS)
    }

    private fun stopFollowing() {
        voice?.followFor(0)
        main.removeCallbacks(endFollow)
        following(false)
    }

    /** green ring on the bubble = "still listening for your next command" */
    private fun following(on: Boolean) {
        followOn = on
        val b = bubble ?: return
        val bg = b.background as? GradientDrawable ?: return
        val d = resources.displayMetrics.density
        if (on) bg.setStroke((3 * d).toInt(), 0xFF34D399.toInt()) else bg.setStroke((2 * d).toInt(), 0xCCBAE6FD.toInt())
        if (on && !hiddenByUser) b.visibility = View.VISIBLE
    }

    /**
     * A follow-up command (no "Hey Leo"). Screen/app commands run right here;
     * other clear commands ("call mom", "send hi to Ravi") go to Leo in the
     * background — his window doesn't pop up again.
     */
    private fun onFollowHeard(said: String, free: Boolean) {
        if (appVisible || !screenOn || capturing) return
        lastHeard = said
        if (said == "bye" || said == "stop listening" || said == "stop listening leo") {
            lastResult = "stopped follow-up listening"
            stopFollowing()
            return
        }
        if (LeoAccessibility.instance == null && QuickCommands.isScreenCommand(said)) {
            lastResult = "needs Accessibility"
            askForAccessibility()
            return
        }
        var ok = try { QuickCommands.tryRun(this, said) } catch (_: Exception) { false }
        if (ok) lastResult = "done on screen (follow-up)"
        else if (free && sendToLeo(said)) { ok = true; lastResult = "follow-up handled by Leo in the background" }
        else lastResult = "follow-up not understood"
        if (ok) { buzz(); extendFollowing() }
        refresh()
    }

    /** Screen control needs the "FocusLion Leo" Accessibility switch — tell the user how. */
    private fun askForAccessibility() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tap = PendingIntent.getActivity(this, 3, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Turn on “FocusLion Leo” in Accessibility")
            .setContentText("Leo needs it to scroll, tap and type in other apps. Tap to open settings.")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try { nm.notify(NOTIF_ID + 1, n) } catch (_: SecurityException) { }
    }


    /** Bubble feedback while listening after "Hey Leo": glowing ring + gentle pulse. */
    private var pulse: android.animation.ObjectAnimator? = null
    private fun listeningUi(on: Boolean) {
        val b = bubble ?: return
        val bg = b.background as? GradientDrawable
        val d = resources.displayMetrics.density
        if (on) {
            b.visibility = View.VISIBLE // show even inside other apps while we listen
            bg?.setStroke((4 * d).toInt(), 0xFFF43F5E.toInt())
            pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                b,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
            ).apply {
                duration = 420
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        } else {
            pulse?.cancel(); pulse = null
            b.scaleX = 1f; b.scaleY = 1f
            if (followOn) bg?.setStroke((3 * d).toInt(), 0xFF34D399.toInt())
            else bg?.setStroke((2 * d).toInt(), 0xCCBAE6FD.toInt())
        }
    }

    private fun buzz() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(60, 180))
            else @Suppress("DEPRECATION") v.vibrate(60)
        } catch (_: Exception) {}
    }

    private fun stopWake() {
        if (capturing) { capturing = false; listeningUi(false); AudioDuck.release(this) }
        main.removeCallbacks(endFollow)
        following(false)
        voice?.stop()
        voice = null
    }
}
