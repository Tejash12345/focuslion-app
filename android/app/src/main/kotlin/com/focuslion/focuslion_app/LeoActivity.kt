package com.focuslion.focuslion_app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

/**
 * The "Leo only" pop-up: opened by the floating bubble and by "Hey Leo".
 * It shows just the /leo page (orb + conversation) in its own small task, so
 * closing Leo ("bye", ✕, back) returns you to the app you were using — the
 * full FocusLion app never opens unless you ask Leo for a FocusLion page.
 *
 * It carries its own copy of the web bridges the main app has in Dart:
 * FLDevice (phone powers), FLSpeak (phone voice) and FLLeo (close).
 */
class LeoActivity : Activity() {

    companion object {
        /** the live Leo window (kept alive in the background between uses) */
        @Volatile var instance: LeoActivity? = null
        private const val URL = "https://new-app-ruddy-nine.vercel.app/leo"
        private const val HOST = "new-app-ruddy-nine.vercel.app"
        private const val REQ_PERMS = 5101
        private const val REQ_FILE = 5102
        private val ON_SCREEN = setOf("scroll", "stop_scroll", "tap", "type", "swipe", "search_app", "home", "back", "recents", "notifications", "quick_settings", "screenshot", "lock", "power_menu")
        private val LEAVES = setOf("open_app", "settings", "youtube", "music", "maps", "web", "url", "camera", "video", "call", "sms",
            "whatsapp", "email", "calendar", "share", "send_whatsapp", "send_bulk", "close_app")
    }

    private lateinit var web: WebView
    private var token = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingWebPerm: PermissionRequest? = null
    private var pendingCmd: String? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF02040C.toInt())
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
        }
        web.addJavascriptInterface(DeviceBridge(), "FLDevice")
        web.addJavascriptInterface(SpeakBridge(), "FLSpeak")
        web.addJavascriptInterface(LeoBridge(), "FLLeo")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                // per-page token: embedded iframes can't send phone commands
                token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                view.evaluateJavascript(
                    "window.__FLSpeakV2=true;window.__FLDeviceV1=true;window.__FLLeoStandalone=true;window.__FLDeviceToken=${JSONObject.quote(token)};",
                    null,
                )
            }
            // the page's renderer crashed / was killed: close cleanly (next "Hey Leo" reopens)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                pageReady = false
                if (instance === this@LeoActivity) instance = null
                try { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() } catch (_: Exception) { }
                finish()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPerm = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
                    }
                }
            }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(Intent.createChooser(params.createIntent(), "Show Leo"), REQ_FILE)
                    true
                } catch (_: Exception) { fileCb = null; false }
            }
        }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { js("window.__flSpeakEnded && window.__flSpeakEnded();") }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { js("window.__flSpeakEnded && window.__flSpeakEnded();") }
            })
        }
        web.loadUrl(URL)
        instance = this
    }

    @Volatile private var pageReady = false

    /**
     * A command heard after "Hey Leo" while this window is in the background:
     * run it here without bringing the window up (Leo answers out loud and
     * acts). Returns false if the page isn't ready — then the window opens.
     */
    fun deliverInBackground(cmd: String, onFail: () -> Unit): Boolean {
        if (!pageReady || isFinishing || isDestroyed) return false
        runOnUiThread {
            try {
                // "1" only if the page really took the command; else open the window
                web.evaluateJavascript(
                    "(typeof window.__flWake=='function'&&(window.__flWake(${JSONObject.quote(cmd)}),1))||0",
                ) { r -> if (r?.trim() != "1") onFail() }
            } catch (_: Exception) { onFail() }
        }
        return true
    }

    private var resumed = false

    override fun onResume() {
        super.onResume()
        resumed = true
        web.onResume()
        LeoBubbleService.markAppVisible(true) // hide the bubble + pause "Hey Leo" while Leo is open
        AudioDuck.hold(this) // pause YouTube/music while Leo listens — it isn't heard as commands
    }

    override fun onPause() {
        resumed = false
        // NOT web.onPause(): the page keeps running in the background so later
        // "Hey Leo" commands are handled without opening this window again
        AudioDuck.release(this) // Leo stepped aside: music/video carries on
        LeoBubbleService.markAppVisible(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    // Keep Leo's window alive in the background so the next "Hey Leo" opens
    // instantly (no reload); it just steps aside and you're back in your app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }

    private fun js(code: String) = runOnUiThread { try { web.evaluateJavascript(code, null) } catch (_: Exception) {} }

    private fun reply(id: String?, res: Map<String, Any?>) {
        if (id == null) return
        js("window.__flDeviceResult && window.__flDeviceResult(${JSONObject.quote(id)}, ${JSONObject(res)});")
    }

    // ---------- FLDevice: phone powers ----------
    inner class DeviceBridge {
        @JavascriptInterface
        fun postMessage(raw: String) = runOnUiThread { handleDevice(raw) }
    }

    private fun handleDevice(raw: String) {
        val cmd = try { JSONObject(raw) } catch (_: Exception) { return }
        if (token.isEmpty() || cmd.optString("token") != token) return // not from our page
        cmd.remove("token")
        val id = cmd.optString("id").ifEmpty { null }
        val a = cmd.optString("a")
        // contacts / SMS permissions first (then the command runs automatically)
        val who = cmd.optString("who")
        val needs = ArrayList<String>()
        if (a in setOf("call", "sms", "whatsapp", "send_sms", "send_whatsapp", "schedule_message", "send_bulk") && (who.any { it.isLetter() } || a == "send_bulk") &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) needs += Manifest.permission.READ_CONTACTS
        if (a == "location" && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needs += Manifest.permission.ACCESS_COARSE_LOCATION
        if (a == "agenda" && checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            needs += Manifest.permission.READ_CALENDAR
        if (a == "call" && cmd.optBoolean("direct") &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) needs += Manifest.permission.CALL_PHONE
        if ((a == "send_sms" || ((a == "schedule_message" || a == "send_bulk") && cmd.optString("app").contains("sms", true))) &&
            checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) needs += Manifest.permission.SEND_SMS
        if (needs.isNotEmpty()) {
            cmd.put("token", token) // re-run this exact command once permission is granted
            pendingCmd = cmd.toString()
            // asked from the background: come forward so the permission prompt shows
            if (!resumed) try {
                startActivity(Intent(this, LeoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (_: Exception) { }
            requestPermissions(needs.toTypedArray(), REQ_PERMS)
            return
        }
        when (a) {
            "listen" -> OfflineListen.listen(this, cmd.optString("lang")) { reply(id, it) }
            "wake_start", "wake_stop", "bubble_start", "bubble_stop" -> reply(id, mapOf("ok" to true, "msg" to "n/a in Leo window"))
            "open_focuslion" -> {
                // hand the page to the full app and step aside
                LeoBubbleService.pendingRoute = cmd.optString("path").ifEmpty { "/" }
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                reply(id, mapOf("ok" to true, "msg" to "Opening FocusLion"))
                finish()
            }
            in ON_SCREEN -> {
                // scroll / tap / type act on the app underneath: step aside first,
                // wait until that app is really in front, then act and report back
                moveTaskToBack(true)
                val args = toMap(cmd)
                fun attempt(left: Int) {
                    web.postDelayed({
                        val front = LeoAccessibility.instance?.rootInActiveWindow?.packageName?.toString()
                        if (front == packageName && left > 0) attempt(left - 1)
                        else reply(id, DeviceControl.handle(this, args))
                    }, 350)
                }
                attempt(6)
            }
            else -> {
                val res = DeviceControl.handle(this, toMap(cmd))
                reply(id, res)
                // it opened another app/screen: get out of the way (don't come back on Back)
                if (res["ok"] == true && a in LEAVES) moveTaskToBack(true)
            }
        }
    }

    private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
        when (val v = o.get(k)) {
            JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { v.get(it) }
            else -> v
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        pendingWebPerm?.let { req ->
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) req.grant(req.resources) else req.deny()
            pendingWebPerm = null
        }
        pendingCmd?.let { c -> pendingCmd = null; handleDevice(c) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        }
    }

    // ---------- FLSpeak: the phone's own voice (fallback when the AI voice is off) ----------
    inner class SpeakBridge {
        @JavascriptInterface
        fun postMessage(raw: String) = runOnUiThread {
            val t = tts ?: return@runOnUiThread
            if (!raw.trim().startsWith("{")) { speak(raw, "", 1f); return@runOnUiThread }
            val c = try { JSONObject(raw) } catch (_: Exception) { return@runOnUiThread }
            when (c.optString("a")) {
                "speak", "resume" -> speak(c.optString("text"), c.optString("lang"), c.optDouble("rate", 0.95).toFloat())
                else -> t.stop()
            }
        }
    }

    private fun speak(text: String, lang: String, rate: Float) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) { js("window.__flSpeakEnded && window.__flSpeakEnded();"); return }
        if (lang.isNotEmpty()) {
            val r = t.setLanguage(Locale.forLanguageTag(lang))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                js("window.__flSpeakNoVoice && window.__flSpeakNoVoice(${JSONObject.quote(lang)});")
                return
            }
        }
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leo-${System.currentTimeMillis()}")
    }

    // ---------- FLLeo: close the pop-up ----------
    inner class LeoBridge {
        @JavascriptInterface
        fun postMessage(msg: String) = runOnUiThread {
            when (msg) {
                "close" -> moveTaskToBack(true)
                "spoke_end" -> if (!resumed) LeoBubbleService.instance?.leoSpokeEnd()
            }
        }
    }
}
