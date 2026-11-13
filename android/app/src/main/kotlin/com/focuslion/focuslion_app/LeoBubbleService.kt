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
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
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

        fun start(ctx: Context, wake: Boolean) {
            val i = Intent(ctx, LeoBubbleService::class.java).putExtra(EXTRA_WAKE, wake)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LeoBubbleService::class.java)) }

        fun markAppVisible(v: Boolean) {
            appVisible = v
            instance?.refresh()
        }

        fun takeWake(): String? { val p = pendingWake; pendingWake = null; return p }
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
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var screenOn = true
    private var lastFire = 0L

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            refresh()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        wakeWanted = intent?.getBooleanExtra(EXTRA_WAKE, wakeWanted) ?: wakeWanted
        hiddenByUser = false
        try {
            goForeground(if (wakeWanted && model == null) "Getting “Hey Leo” ready…" else readyText())
        } catch (e: Exception) {
            // Android may refuse a mic service started from the background — just stop
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        showBubble()
        if (wakeWanted) prepareModel() else stopWake()
        refresh()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenRx, f)
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        stopWake()
        model?.close(); model = null
        removeBubble()
        super.onDestroy()
    }

    private fun readyText() = if (wakeWanted) "Say “Hey Leo” or tap the lion" else "Tap the lion to talk to Leo"

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
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
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
            if (outside && wakeWanted && !capturing) { if (speech == null) startWake() }
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
                    download(dir)
                    ready.createNewFile()
                }
                val root = dir.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").exists() } ?: dir
                val m = Model(root.absolutePath)
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

    private fun download(dir: File) {
        dir.deleteRecursively(); dir.mkdirs()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
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

    private fun startWake() {
        val m = model ?: return
        if (speech != null || capturing) return
        try {
            // grammar = only the wake phrases: tiny, accurate, low battery
            val rec = Recognizer(m, 16000f, "[\"hey leo\", \"hi leo\", \"okay leo\", \"hello leo\", \"ok leo\", \"[unk]\"]")
            val s = SpeechService(rec, 16000f)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(h: String?) { check(h, "partial") }
                override fun onResult(h: String?) { check(h, "text") }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { main.postDelayed({ stopWake(); startWake() }, 3000) }
                override fun onTimeout() {}
            })
            speech = s
        } catch (_: Exception) {
            updateNotif("Couldn't start “Hey Leo” — tap the lion instead")
        }
    }

    private fun check(json: String?, key: String) {
        val t = try { JSONObject(json ?: "{}").optString(key) } catch (_: Exception) { "" }
        if (!capturing && Regex("\\b(hey|hi|okay|ok|hello) leo\\b").containsMatchIn(t)) {
            if (appVisible || !screenOn) return
            main.post { captureCommand() }
        }
    }

    // ---------- after "Hey Leo": catch the command in the same breath ----------
    @Volatile private var capturing = false
    private var cmdSpeech: SpeechService? = null

    /** True while the command after "Hey Leo" is still being heard. */
    fun isCapturing() = capturing

    /**
     * "Hey Leo, open WhatsApp": Leo's window opens INSTANTLY (feedback), while
     * a free-form offline recognizer keeps listening for the command. When it
     * is done we hand the text over ("" = no command, Leo listens himself).
     * If nothing is said within ~1.6 s we hand over at once — no waiting.
     */
    private fun captureCommand() {
        if (capturing) return
        val m = model ?: run { openLeo(""); return }
        capturing = true
        pendingWake = null
        stopWake()
        buzz()
        listeningUi(true) // the bubble glows while Leo listens for the command
        var done = false
        var spoke = false
        fun finish(text: String) {
            if (done) return
            done = true
            main.post {
                cmdSpeech?.let { try { it.stop(); it.shutdown() } catch (_: Exception) {} }
                cmdSpeech = null
                listeningUi(false)
                val cmd = text.replace(Regex("^(hey|hi|okay|ok|hello)?\\s*leo\\b[ ,]*"), "").trim()
                capturing = false
                // screen commands ("scroll down", "tap storage", "go back"…) run right
                // here on the app you're using — no Leo window popping up over it
                if (cmd.isNotEmpty() && QuickCommands.tryRun(this, cmd)) {
                    buzz()
                    refresh()
                    return@post
                }
                pendingWake = cmd
                launchLeo()
                refresh()
            }
        }
        try {
            val rec = Recognizer(m, 16000f)
            val s = SpeechService(rec, 16000f)
            val l = object : RecognitionListener {
                override fun onPartialResult(h: String?) {
                    val p = try { JSONObject(h ?: "{}").optString("partial") } catch (_: Exception) { "" }
                    if (p.isNotBlank() && p.trim() !in setOf("leo", "hey leo", "the", "a", "huh")) spoke = true
                }
                override fun onResult(h: String?) {
                    val t = try { JSONObject(h ?: "{}").optString("text") } catch (_: Exception) { "" }
                    if (t.isNotBlank() && t.trim() !in setOf("leo", "hey leo", "the", "a", "huh")) finish(t)
                }
                override fun onFinalResult(h: String?) {
                    finish(try { JSONObject(h ?: "{}").optString("text") } catch (_: Exception) { "" })
                }
                override fun onError(e: Exception?) { finish("") }
                override fun onTimeout() { finish("") }
            }
            s.startListening(l, 6000)
            cmdSpeech = s
            // silence right after "Hey Leo" -> hand over immediately (Leo listens himself)
            main.postDelayed({ if (!done && !spoke) finish("") }, 1600)
            main.postDelayed({ if (!done) finish("") }, 6500)
        } catch (_: Exception) {
            capturing = false
            pendingWake = ""
        }
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
            bg?.setStroke((2 * d).toInt(), 0xCCBAE6FD.toInt())
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
        speech?.let { try { it.stop(); it.shutdown() } catch (_: Exception) {} }
        speech = null
    }
}
