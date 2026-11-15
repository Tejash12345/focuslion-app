package com.focuslion.focuslion_app

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Leo Live "phone powers". The web app (via the FLDevice JS channel -> Dart ->
 * the "device" method) asks for one action; we do it and return
 * {ok, msg, ...data}. Anything that reaches other people (calls, SMS,
 * WhatsApp) only OPENS the app ready to go — the user presses call/send.
 */
object DeviceControl {

    fun handle(activity: Activity, args: Map<String, Any?>): Map<String, Any?> {
        val a = args["a"]?.toString() ?: ""
        return try {
            when (a) {
                "open_app" -> openApp(activity, str(args, "name"))
                "call" -> call(activity, str(args, "who"), args["direct"] == true)
                "sms" -> sms(activity, str(args, "who"), str(args, "text"))
                "whatsapp" -> whatsapp(activity, str(args, "who"), str(args, "text"))
                "alarm" -> alarm(activity, int(args, "hour"), int(args, "minute"), str(args, "label"))
                "timer" -> timer(activity, int(args, "seconds"), str(args, "label"))
                "torch" -> torch(activity, args["on"] != false)
                "volume" -> volume(activity, str(args, "level"))
                "settings" -> settings(activity, str(args, "panel"))
                "youtube" -> view(activity, "https://www.youtube.com/results?search_query=" + Uri.encode(str(args, "query")), "com.google.android.youtube", "Searching YouTube")
                "music" -> music(activity, str(args, "query"))
                "maps" -> maps(activity, str(args, "query"), args["navigate"] == true)
                "web" -> view(activity, "https://www.google.com/search?q=" + Uri.encode(str(args, "query")), null, "Searching Google")
                "camera" -> start(activity, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera opened")
                "battery" -> battery(activity)
                "dnd" -> dnd(activity, args["on"] != false)
                "ringer" -> ringer(activity, str(args, "mode"))
                "media" -> media(activity, str(args, "key"))
                "brightness" -> brightness(activity, str(args, "level"))
                "email" -> start(activity, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(str(args, "to"))))
                    .putExtra(Intent.EXTRA_SUBJECT, str(args, "subject")).putExtra(Intent.EXTRA_TEXT, str(args, "body")),
                    "Email is ready — tap send")
                "calendar" -> calendar(activity, str(args, "title"), str(args, "start"), int(args, "minutes"))
                "share" -> start(activity, Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, str(args, "text")), "Share"),
                    "Pick where to share")
                "copy" -> copy(activity, str(args, "text"))
                "video" -> start(activity, Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), "Video camera opened")
                "url" -> view(activity, str(args, "url").let { if (it.startsWith("http")) it else "https://$it" }, null, "Opened ${str(args, "url")}")
                "info" -> info(activity)
                "screen_time" -> screenTime(activity)
                "power_menu", "lock", "screenshot", "home", "back", "recents", "notifications", "quick_settings" -> globalAction(activity, a)
                "read_screen" -> readScreen(activity)
                "location" -> location(activity)
                "agenda" -> agenda(activity, int(args, "days"))
                // act on the app underneath (Leo's window steps aside first — see LeoActivity)
                // AI-chosen details (amount / speed / times / auto) go through the same scroll engine
                "scroll" -> onScreen(activity, "Scrolling") {
                    val phrase = listOf("scroll", str(args, "dir").ifEmpty { "down" },
                        when (str(args, "amount")) { "little" -> "a little"; "lot" -> "a lot"; "to end" -> if (str(args, "dir") == "up") "to the top" else "to the bottom"; else -> "" },
                        when (str(args, "speed")) { "slow" -> "slowly"; "fast" -> "fast"; else -> "" },
                        if (args["auto"] == true) "keep" else "",
                        int(args, "times").takeIf { it in 2..5 }?.let { "$it times" } ?: "",
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    QuickCommands.tryRun(activity, phrase)
                }
                "stop_scroll" -> onScreen(activity, "Stopped scrolling") { it.stopAutoScroll(); true }
                "tap" -> onScreen(activity, "Tapped ${str(args, "text")}") { it.tapText(str(args, "text")) }
                "type" -> onScreen(activity, "Typed it") { it.typeText(str(args, "text")) }
                "swipe" -> onScreen(activity, "Swiped") { it.swipeSideways(str(args, "dir") != "right") }
                "search_app" -> onScreen(activity, "Searching for ${str(args, "query")}") { it.searchFor(str(args, "query")) { } }
                "alarm_off" -> alarmOff(activity, str(args, "label"))
                "timer_off" -> start(activity, Intent(AlarmClock.ACTION_DISMISS_TIMER), "Timer stopped")
                "read_messages" -> readMessages(activity, int(args, "minutes"))
                "bubble_start" -> bubbleStart(activity, args["wake"] == true, args["bubble"] != false)
                "leo_status" -> leoStatus(activity)
                // a phone/screen command heard by the cloud recognizer: run it right here
                "quick" -> if (QuickCommands.tryRun(activity, str(args, "text"))) {
                    LeoBubbleService.instance?.leoSpokeEnd() // nothing to say: let the music/video resume
                    ok("Done")
                } else fail("not a quick command")
                "voice_status" -> voiceStatus(activity)
                "voice_enroll" -> {
                    val s = LeoBubbleService.instance
                    if (s == null) fail("Turn on “Hey Leo” first") else {
                        val r = s.enrollVoice(); if (r == "ok") ok("Recording — keep talking for 12 seconds") else fail(r)
                    }
                }
                "voice_lock" -> {
                    if (args["on"] == true && VoicePrint.print(activity) == null) fail("Record your voice first")
                    else {
                        VoicePrint.setLock(activity, args["on"] == true, (args["strict"] as? Number)?.toInt())
                        LeoBubbleService.instance?.restartWake()
                        ok(if (args["on"] == true) "Voice Lock on — Leo answers only your voice" else "Voice Lock off")
                    }
                }
                "voice_forget" -> { VoicePrint.clear(activity); LeoBubbleService.instance?.restartWake(); ok("Voice print deleted") }
                "perm_status" -> permStatus(activity)
                "open_perm" -> openPerm(activity, str(args, "which"))
                "bubble_stop" -> { LeoBubbleService.stop(activity); ok("Leo bubble off") }
                "take_route" -> { val r = LeoBubbleService.takeRoute(); if (r == null) fail("none") else ok(r) }
                "take_wake" -> {
                    // still hearing the command after "Hey Leo" -> the web app asks again shortly
                    if (LeoBubbleService.instance?.isCapturing() == true) fail("wait")
                    else { val w = LeoBubbleService.takeWake(); if (w == null) fail("none") else ok(w) }
                }
                "close_app" -> closeApp(activity, str(args, "name"))
                "send_sms" -> sendSmsNow(activity, str(args, "who"), str(args, "text"))
                "send_whatsapp" -> sendWhatsappNow(activity, str(args, "who"), str(args, "text"))
                "send_bulk" -> sendBulk(activity, str(args, "app"), args["who"], str(args, "text"))
                "schedule_message" -> scheduleMessage(activity, str(args, "app"), str(args, "who"), str(args, "text"), str(args, "at"))
                "scheduled_list" -> scheduledList(activity)
                "scheduled_cancel" -> ok("Cancelled ${Messaging.cancel(activity, str(args, "query"))} scheduled message(s)")
                else -> fail("Unknown phone action: $a")
            }
        } catch (e: Throwable) { // Throwable: also catches NoSuchMethodError on old Android
            fail(e.message ?: "That didn't work on this phone")
        }
    }

    // ---------- helpers ----------
    private fun str(m: Map<String, Any?>, k: String) = m[k]?.toString()?.trim() ?: ""
    private fun int(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toInt() ?: m[k]?.toString()?.toIntOrNull() ?: 0
    private fun ok(msg: String, extra: Map<String, Any?> = emptyMap()) = mapOf("ok" to true, "msg" to msg) + extra
    private fun fail(msg: String) = mapOf("ok" to false, "msg" to msg)

    private fun start(activity: Activity, intent: Intent, msg: String): Map<String, Any?> = try {
        // own task: Back from that app never lands inside Leo's window
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ok(msg)
    } catch (e: ActivityNotFoundException) {
        fail("No app on this phone can do that")
    }

    private fun view(activity: Activity, url: String, pkg: String?, msg: String): Map<String, Any?> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (pkg != null) {
            try { activity.startActivity(Intent(i).setPackage(pkg)); return ok(msg) } catch (_: ActivityNotFoundException) { /* use any app */ }
        }
        return start(activity, i, msg)
    }

    // ---------- apps ----------
    private val aliases = mapOf(
        "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android", "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music", "telegram" to "org.telegram.messenger",
        "facebook" to "com.facebook.katana", "snapchat" to "com.snapchat.android",
        "phonepe" to "com.phonepe.app", "gpay" to "com.google.android.apps.nbu.paisa.user",
        "google pay" to "com.google.android.apps.nbu.paisa.user", "paytm" to "net.one97.paytm",
        "play store" to "com.android.vending", "x" to "com.twitter.android", "twitter" to "com.twitter.android",
    )

    private fun openApp(activity: Activity, nameRaw: String): Map<String, Any?> {
        val name = nameRaw.lowercase().removeSuffix(" app").trim()
        if (name.isEmpty()) return fail("Which app?")
        val pm = activity.packageManager
        if (name == "camera") return start(activity, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera opened")
        if (name == "settings") return start(activity, Intent(Settings.ACTION_SETTINGS), "Settings opened")
        aliases[name]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { activity.startActivity(it); return ok("Opened ${nameRaw.trim()}") }
        }
        // fuzzy match against every launchable app's label
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        var best: Pair<Int, String>? = null
        var bestLabel = ""
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val l = label.lowercase()
            val score = when {
                l == name -> 100
                l.startsWith(name) -> 80
                l.contains(name) -> 60
                name.contains(l) && l.length > 2 -> 40
                else -> 0
            }
            if (score > (best?.first ?: 0)) { best = score to ri.activityInfo.packageName; bestLabel = label }
        }
        val pkg = best?.second ?: return fail("I couldn't find an app called $nameRaw")
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return fail("I couldn't open $bestLabel")
        activity.startActivity(intent)
        return ok("Opened $bestLabel")
    }

    // ---------- people ----------
    /**
     * "Mom" -> (display name, number) from contacts; digits pass straight
     * through. Leo may send alternatives "Amma|Mom|Mother" — first hit wins.
     */
    internal fun resolve(activity: Activity, whoAll: String): Pair<String, String>? {
        for (who in whoAll.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
            resolveOne(activity, who)?.let { return it }
        }
        return null
    }

    private fun resolveOne(activity: Activity, who: String): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 5 && digits.length >= who.count { !it.isWhitespace() } - 2) return who to digits
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val q = who.lowercase()
        var best: Triple<Int, String, String>? = null
        activity.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val l = n.lowercase()
                val s = when {
                    l == q -> 100
                    l.startsWith(q) -> 80
                    l.split(" ").any { it == q } -> 70
                    l.contains(q) -> 50
                    else -> 0
                }
                if (s > (best?.first ?: 0)) best = Triple(s, n, num)
            }
        }
        return best?.let { it.second to it.third }
    }

    private fun call(activity: Activity, who: String, direct: Boolean = false): Map<String, Any?> {
        val r = resolve(activity, who) ?: return fail("I couldn't find ${who.substringBefore('|')} in your contacts")
        // instant mode (user turned confirmation off): place the call straight away
        if (direct && ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            return start(activity, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(r.second))), "Calling ${r.first}")
        }
        // ACTION_DIAL opens the dialer with the number filled in — you press call
        return start(activity, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(r.second))), "Ready to call ${r.first}")
    }

    private fun sms(activity: Activity, who: String, text: String): Map<String, Any?> {
        val r = resolve(activity, who) ?: return fail("I couldn't find ${who.substringBefore('|')} in your contacts")
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(r.second))).putExtra("sms_body", text)
        return start(activity, i, "Message to ${r.first} is ready — tap send")
    }

    private fun whatsapp(activity: Activity, who: String, text: String): Map<String, Any?> {
        val r = resolve(activity, who) ?: return fail("I couldn't find ${who.substringBefore('|')} in your contacts")
        var num = r.second.filter { it.isDigit() }
        if (num.length == 10) num = "91$num" // Indian mobile without country code
        if (num.length == 11 && num.startsWith("0")) num = "91" + num.drop(1)
        val url = "https://wa.me/$num" + if (text.isNotEmpty()) "?text=" + Uri.encode(text) else ""
        return view(activity, url, "com.whatsapp", "WhatsApp chat with ${r.first} is ready — tap send")
    }

    // ---------- clock ----------
    private fun alarm(activity: Activity, hour: Int, minute: Int, label: String): Map<String, Any?> {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return start(activity, i, "Alarm set for %02d:%02d".format(hour, minute))
    }

    private fun timer(activity: Activity, seconds: Int, label: String): Map<String, Any?> {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val m = seconds / 60
        return start(activity, i, if (m > 0) "Timer set for $m min" else "Timer set for $seconds sec")
    }

    // ---------- hardware ----------
    private fun torch(activity: Activity, on: Boolean): Map<String, Any?> {
        val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("This phone has no flashlight")
        cm.setTorchMode(id, on)
        return ok(if (on) "Flashlight on" else "Flashlight off")
    }

    private fun volume(activity: Activity, level: String): Map<String, Any?> {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val flags = AudioManager.FLAG_SHOW_UI
        when (level.lowercase()) {
            "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, flags)
            "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, flags)
            "mute" -> am.setStreamVolume(s, 0, flags)
            "max" -> am.setStreamVolume(s, max, flags)
            else -> {
                val pct = level.filter { it.isDigit() }.toIntOrNull() ?: return fail("Say up, down, mute, max or a percent")
                am.setStreamVolume(s, (max * pct.coerceIn(0, 100) / 100.0).toInt(), flags)
            }
        }
        val now = am.getStreamVolume(s) * 100 / max.coerceAtLeast(1)
        return ok("Volume $now%")
    }

    private fun settings(activity: Activity, panel: String): Map<String, Any?> {
        QuickCommands.settingsIntent(panel)?.let { return start(activity, it, "Opened ${panel.ifEmpty { "settings" }} settings") }
        val p = panel.lowercase()
        val action = when {
            p.contains("wifi") || p.contains("wi-fi") ->
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            p.contains("internet") || p.contains("data") ->
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            p.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            p.contains("volume") || p.contains("sound") ->
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            p.contains("display") || p.contains("bright") -> Settings.ACTION_DISPLAY_SETTINGS
            p.contains("battery") -> Intent.ACTION_POWER_USAGE_SUMMARY
            p.contains("location") || p.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            p.contains("hotspot") || p.contains("tether") -> Settings.ACTION_WIRELESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return start(activity, Intent(action), "Opened ${panel.ifEmpty { "settings" }}")
    }

    // ---------- media & places ----------
    private fun music(activity: Activity, query: String): Map<String, Any?> {
        if (query.isNotEmpty()) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            try { activity.startActivity(i); return ok("Playing $query") } catch (_: ActivityNotFoundException) { }
        }
        return view(activity, "https://music.youtube.com/search?q=" + Uri.encode(query), "com.google.android.apps.youtube.music", "Searching music for $query")
    }

    private fun maps(activity: Activity, query: String, navigate: Boolean): Map<String, Any?> {
        val uri = if (navigate) "google.navigation:q=" + Uri.encode(query) else "geo:0,0?q=" + Uri.encode(query)
        return start(activity, Intent(Intent.ACTION_VIEW, Uri.parse(uri)), if (navigate) "Starting directions to $query" else "Showing $query on the map")
    }

    // ---------- focus & sound ----------
    /** Do Not Disturb — perfect for study sessions. Needs a one-time grant in Settings. */
    private fun dnd(activity: Activity, on: Boolean): Map<String, Any?> {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return fail("Allow FocusLion under Do Not Disturb access, then ask me again")
        }
        nm.setInterruptionFilter(
            if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return ok(if (on) "Do Not Disturb on" else "Do Not Disturb off")
    }

    private fun ringer(activity: Activity, mode: String): Map<String, Any?> {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val m = mode.lowercase()
        if (m.contains("silent")) {
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return fail("Allow FocusLion under Do Not Disturb access to use silent mode")
            }
        }
        am.ringerMode = when {
            m.contains("silent") -> AudioManager.RINGER_MODE_SILENT
            m.contains("vibrat") -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return ok("Phone on ${if (m.contains("silent")) "silent" else if (m.contains("vibrat")) "vibrate" else "ring"}")
    }

    /** play / pause / next / previous for whatever app is playing music. */
    private fun media(activity: Activity, key: String): Map<String, Any?> {
        val code = when (key.lowercase()) {
            "pause", "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "resume" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "next", "skip" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return ok("Music: ${key.ifEmpty { "play/pause" }}")
    }

    /** Screen brightness — needs "Modify system settings" once; otherwise just this app. */
    private fun brightness(activity: Activity, level: String): Map<String, Any?> {
        val cur = try { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
        val target = when (level.lowercase()) {
            "up" -> (cur + 50).coerceAtMost(255)
            "down" -> (cur - 50).coerceAtLeast(5)
            "max", "full" -> 255
            "min", "low" -> 10
            else -> ((level.filter { it.isDigit() }.toIntOrNull() ?: 50).coerceIn(1, 100) * 255 / 100)
        }
        if (Settings.System.canWrite(activity)) {
            Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            return ok("Brightness ${target * 100 / 255}%")
        }
        // no system permission: dim/brighten FocusLion itself and offer the switch
        activity.runOnUiThread {
            val lp = activity.window.attributes
            lp.screenBrightness = target / 255f
            activity.window.attributes = lp
        }
        activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}")))
        return ok("Brightness set in FocusLion — allow \"Modify system settings\" so I can change the whole phone")
    }

    // ---------- productivity ----------
    /** Adds an event to the calendar app (pre-filled; the user taps save). start = "YYYY-MM-DDTHH:mm". */
    private fun calendar(activity: Activity, title: String, startAt: String, minutes: Int): Map<String, Any?> {
        val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
        try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
            val begin = fmt.parse(startAt)?.time
            if (begin != null) {
                i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + (if (minutes > 0) minutes else 60) * 60_000L)
            }
        } catch (_: Exception) { /* open without a time */ }
        return start(activity, i, "Calendar event ready — tap save")
    }

    private fun copy(activity: Activity, text: String): Map<String, Any?> {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Leo", text))
        return ok("Copied to clipboard")
    }

    /** Battery, storage, network — so Leo can answer "how's my phone?". */
    private fun info(activity: Activity): Map<String, Any?> {
        val bm = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val totalGb = stat.totalBytes / 1_000_000_000.0
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "online"
        }
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ok(
            "Battery $level%" + (if (bm.isCharging) " (charging)" else "") + " · %.1f of %.0f GB free · %s".format(freeGb, totalGb, net),
            mapOf("level" to level, "charging" to bm.isCharging, "freeGb" to freeGb, "network" to net, "model" to "${Build.MANUFACTURER} ${Build.MODEL}"),
        )
    }

    /** Run an accessibility action on the app underneath, after Leo has stepped aside. */
    private fun onScreen(activity: Activity, msg: String, act: (LeoAccessibility) -> Boolean): Map<String, Any?> {
        val svc = LeoAccessibility.instance ?: return needAccessibility(activity)
        // from the full FocusLion app the "screen" is FocusLion itself — nothing to act on
        if (activity !is LeoActivity) return fail("Switch to that app first, then say \"Hey Leo\" and the command")
        // LeoActivity has already stepped aside and waited; report the REAL result
        val done = try { act(svc) } catch (_: Exception) { false }
        return if (done) ok(msg) else fail("I couldn't do that on this screen")
    }

    // ---------- where am I (for weather) ----------
    private fun location(activity: Activity): Map<String, Any?> {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7401)
            return fail("Allow location for FocusLion, then ask me again — or tell me the city")
        }
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val loc = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { @Suppress("MissingPermission") lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time } ?: return fail("I couldn't get your location — tell me the city")
        return ok("Location found", mapOf("lat" to loc.latitude, "lon" to loc.longitude))
    }

    // ---------- calendar: "what's on my calendar today?" ----------
    private fun agenda(activity: Activity, daysRaw: Int): Map<String, Any?> {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALENDAR), 7402)
            return fail("Allow calendar access for FocusLion, then ask me again")
        }
        val days = daysRaw.coerceIn(1, 7)
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val end = start + days * 86_400_000L
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
        }.build()
        val cols = arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
            android.provider.CalendarContract.Instances.ALL_DAY, android.provider.CalendarContract.Instances.EVENT_LOCATION)
        val fmt = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.ENGLISH)
        val out = ArrayList<String>()
        // only calendars the user shows, and not events they declined
        val where = android.provider.CalendarContract.Instances.VISIBLE + "=1 AND " +
            android.provider.CalendarContract.Instances.SELF_ATTENDEE_STATUS + "!=" + android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        activity.contentResolver.query(uri, cols, where, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 15) {
                val title = c.getString(0) ?: continue
                val begin = c.getLong(1)
                val allDay = c.getInt(2) == 1
                val place = c.getString(3).orEmpty()
                out += (if (allDay) "All day" else fmt.format(java.util.Date(begin))) + " — " + title + (if (place.isNotBlank()) " at $place" else "")
            }
        }
        return ok(if (out.isEmpty()) "Nothing on your calendar ${if (days == 1) "today" else "in the next $days days"}" else "Calendar: " + out.joinToString(" | "))
    }

    // ---------- alarms off ----------
    /** Turn off the ringing/next alarm, or one by label ("gym"), via the Clock app. */
    private fun alarmOff(activity: Activity, label: String): Map<String, Any?> {
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        if (label.isNotBlank() && label.lowercase() !in setOf("all", "every", "alarm", "the alarm")) {
            i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        } else if (label.lowercase() in setOf("all", "every")) {
            i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
        } else {
            i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        return start(activity, i, if (label.isBlank()) "Alarm turned off" else "Turned off \"$label\" alarm")
    }

    // ---------- messages: "any new messages? who sent what?" ----------
    private fun readMessages(activity: Activity, minutes: Int): Map<String, Any?> {
        if (!LeoNotifications.hasAccess(activity)) {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return fail("Turn on Notification access for FocusLion, then ask me again")
        }
        val since = System.currentTimeMillis() - (if (minutes > 0) minutes else 24 * 60) * 60_000L
        val list = LeoNotifications.recent(since)
        if (list.isEmpty()) return ok("No new messages since I started watching")
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)
        return ok("Recent messages (newest first): " + list.take(20).joinToString(" | ") {
            "${fmt.format(java.util.Date(it.at))} ${it.app} — ${it.from}: ${it.text.take(160)}"
        })
    }

    // ---------- Leo everywhere: floating bubble + offline "Hey Leo" ----------
    private fun bubbleStart(activity: Activity, wake: Boolean, bubble: Boolean): Map<String, Any?> {
        // needed for the bubble AND for Leo to pop up from the background on "Hey Leo"
        if (!Settings.canDrawOverlays(activity)) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            return fail("Allow \"Display over other apps\" for FocusLion, then turn it on again")
        }
        val micOk = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (wake && !micOk) {
            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), 7403)
        }
        // battery savers kill background listeners — ask once to run unrestricted
        val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val prefs = activity.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
        if (wake && !pm.isIgnoringBatteryOptimizations(activity.packageName) && !prefs.getBoolean("asked_battery", false)) {
            prefs.edit().putBoolean("asked_battery", true).apply()
            try {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
            } catch (_: Exception) {}
        }
        LeoBubbleService.start(activity, wake && micOk, bubble)
        return ok(when {
            wake && !micOk -> "Allow the microphone for \"Hey Leo\""
            wake -> "\"Hey Leo\" is on"
            else -> "Leo bubble on"
        })
    }

    // ---------- easy setup: what Leo is allowed to do, and one tap to fix each ----------
    private fun granted(activity: Activity, perm: String) =
        ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED

    private fun permStatus(activity: Activity): Map<String, Any?> {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val st = mapOf(
            "mic" to granted(activity, Manifest.permission.RECORD_AUDIO),
            "accessibility" to (LeoAccessibility.instance != null),
            "overlay" to Settings.canDrawOverlays(activity),
            "battery" to pm.isIgnoringBatteryOptimizations(activity.packageName),
            "notifications" to LeoNotifications.hasAccess(activity),
            "contacts" to granted(activity, Manifest.permission.READ_CONTACTS),
            "sms" to granted(activity, Manifest.permission.SEND_SMS),
            "phone" to granted(activity, Manifest.permission.CALL_PHONE),
            "calendar" to granted(activity, Manifest.permission.READ_CALENDAR),
            "location" to granted(activity, Manifest.permission.ACCESS_COARSE_LOCATION),
            // phones that kill apps when closed (Xiaomi, Oppo, Vivo, Realme, Huawei…) need Auto-start
            "autostart" to (!needsAutostart() ||
                activity.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).getBoolean("autostart_seen", false)),
        )
        val missing = st.filterValues { it == false }.keys
        return ok(if (missing.isEmpty()) "All set" else "Missing: ${missing.joinToString()}", st)
    }

    private fun needsAutostart(): Boolean = Build.MANUFACTURER.lowercase() in
        setOf("xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo", "huawei", "honor", "oneplus", "letv", "meizu", "asus")

    /** One tap: ask for the permission, or open the exact settings screen. */
    private fun openPerm(activity: Activity, which: String): Map<String, Any?> {
        fun ask(vararg perms: String): Map<String, Any?> {
            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(*perms), 7500)
            return ok("Asking…")
        }
        fun open(intent: Intent): Map<String, Any?> = start(activity, intent, "Opening settings…")
        val pkg = Uri.parse("package:${activity.packageName}")
        return when (which) {
            "mic" -> ask(Manifest.permission.RECORD_AUDIO)
            "contacts" -> ask(Manifest.permission.READ_CONTACTS)
            "sms" -> ask(Manifest.permission.SEND_SMS)
            "phone" -> ask(Manifest.permission.CALL_PHONE)
            "calendar" -> ask(Manifest.permission.READ_CALENDAR)
            "location" -> ask(Manifest.permission.ACCESS_COARSE_LOCATION)
            "accessibility" -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "overlay" -> open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            "battery" -> open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            "notifications" -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "autostart" -> {
                activity.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE).edit().putBoolean("autostart_seen", true).apply()
                val tries = listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                    "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
                )
                for ((p, c) in tries) {
                    val i = Intent().setComponent(android.content.ComponentName(p, c)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { activity.startActivity(i); return ok("Turn ON FocusLion in this list") } catch (_: Exception) { }
                }
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            }
            else -> fail("Unknown permission")
        }
    }

    /** "Hey Leo" health check for the settings screen. */
    private fun voiceStatus(activity: Activity): Map<String, Any?> {
        val enrolled = VoicePrint.print(activity) != null
        val lock = VoicePrint.locked(activity)
        val st = LeoBubbleService.instance?.enrollState ?: ""
        val msg = when {
            st == "preparing" -> "Getting ready…"
            st == "recording" -> "Recording — keep talking…"
            st.startsWith("failed") -> st.removePrefix("failed: ")
            lock -> "On — Leo answers only your voice ✓"
            enrolled -> "Voice recorded — Voice Lock is off"
            else -> "Off — record your voice to turn it on"
        }
        return ok(msg, mapOf("lock" to lock, "enrolled" to enrolled, "strict" to VoicePrint.strict(activity), "state" to st,
            "score" to VoicePrint.lastScore))
    }

    private fun leoStatus(activity: Activity): Map<String, Any?> {
        val st = LeoBubbleService.status()
        val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val mic = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val battery = pm.isIgnoringBatteryOptimizations(activity.packageName)
        val overlay = Settings.canDrawOverlays(activity)
        val msg = when {
            !mic -> "Microphone not allowed"
            !overlay -> "Allow \"Display over other apps\""
            st["running"] != true -> "Not running — open FocusLion once"
            st["wake"] != true -> "Off"
            st["model"] != true -> "Downloading the voice model (needs internet once)…"
            // inside FocusLion / Leo the app's own listener takes over: that's normal
            st["appVisible"] == true -> "Ready ✓ — listens for “Hey Leo” as soon as you leave FocusLion (inside the app, Leo's own mic is used)"
            st["screenOn"] == false -> "Screen is off — listening resumes when you turn it on ✓"
            (st["enroll"] as? String) in setOf("preparing", "recording") -> "Paused while recording your voice…"
            st["lockWaiting"] == true -> "Getting Voice Lock ready (needs internet once)…"
            st["micBlocked"] == true -> "Android paused the background mic — tap the “Leo” notification or open FocusLion once"
            st["listening"] != true && (st["lastEnd"] as? String) == "mic" ->
                "Another app is using the microphone (call, recorder, voice typing) — Leo retries every few seconds"
            st["listening"] != true && (st["lastEnd"] as? String).orEmpty().startsWith("error") ->
                "Mic problem: ${(st["lastEnd"] as String).removePrefix("error: ")} — retrying…"
            st["listening"] != true -> "Starting the mic…"
            !battery -> "Listening — but battery saver may stop it (set FocusLion to Unrestricted)"
            else -> "Listening for “Hey Leo” ✓"
        }
        val a11y = LeoAccessibility.instance != null
        val heard = (st["lastHeard"] as? String).orEmpty()
        val full = msg + (if (!a11y) " · Turn on Accessibility → FocusLion Leo for scroll/tap" else "") +
            (if (heard.isNotEmpty()) " · Last heard: “$heard” → ${st["lastResult"]}" else "")
        return ok(full, st + mapOf("leoLog" to (st["log"] ?: ""), "mic" to mic, "battery" to battery, "overlay" to overlay, "accessibility" to a11y))
    }

    /** "close Messages" — go home and stop that app's background process. */
    private fun closeApp(activity: Activity, nameRaw: String): Map<String, Any?> {
        val name = nameRaw.lowercase().removeSuffix(" app").trim()
        val pm = activity.packageManager
        var pkg: String? = aliases[name]
        var label = nameRaw
        if (pkg == null && name.isNotEmpty()) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .firstOrNull { it.first.lowercase() == name || it.first.lowercase().startsWith(name) || it.first.lowercase().contains(name) }
                ?.let { pkg = it.second; label = it.first }
        }
        if (pkg == null && name.isEmpty()) {
            // "close this app": the last app the accessibility service saw
            pkg = LeoAccessibility.lastPackage().ifEmpty { null }
            label = LeoAccessibility.lastScreen().first
        }
        val target = pkg ?: return fail("I couldn't find an app called $nameRaw")
        if (target == activity.packageName) return fail("I can't close myself — say bye instead")
        // go home first so the app is in the background, then stop it
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LeoAccessibility.instance?.global("home") ?: activity.startActivity(home)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                (activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(target)
            } catch (_: Exception) {}
        }, 700)
        return ok("Closed $label")
    }

    // ---------- accessibility powers (user enables "FocusLion Leo" once) ----------
    private fun needAccessibility(activity: Activity): Map<String, Any?> {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return fail("Turn on \"FocusLion Leo\" in Accessibility settings, then ask me again")
    }

    private fun globalAction(activity: Activity, a: String): Map<String, Any?> {
        val svc = LeoAccessibility.instance ?: return needAccessibility(activity)
        if (!svc.global(a)) return fail("This phone doesn't support that")
        return ok(when (a) {
            "power_menu" -> "Power menu open — tap Power off or Restart"
            "lock" -> "Screen locked"
            "screenshot" -> "Screenshot taken"
            "home" -> "Home"
            "back" -> "Back"
            "recents" -> "Recent apps"
            "notifications" -> "Notifications open"
            else -> "Quick settings open"
        })
    }

    /** Text of the last screen the user had open in another app (kept in memory only). */
    private fun readScreen(activity: Activity): Map<String, Any?> {
        if (LeoAccessibility.instance == null) return needAccessibility(activity)
        val (app, text, at) = LeoAccessibility.lastScreen()
        if (text.isEmpty()) return fail("Open the app you want me to read, then come back and ask me")
        val ago = (System.currentTimeMillis() - at) / 60_000
        return ok("Screen from $app (${if (ago < 1) "just now" else "$ago min ago"}):\n$text", mapOf("app" to app))
    }

    // ---------- messaging (Leo confirms text + recipient with the user first) ----------
    private fun hasSmsPermission(activity: Activity) =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun sendSmsNow(activity: Activity, who: String, text: String): Map<String, Any?> {
        if (text.isBlank()) return fail("What should the message say?")
        val r = resolve(activity, who) ?: return fail("I couldn't find ${who.substringBefore('|')} in your contacts")
        if (!hasSmsPermission(activity)) return sms(activity, r.second, text) // fall back to ready-to-send
        Messaging.sendSms(activity, r.second, text)
        return ok("SMS sent to ${r.first}")
    }

    private fun sendWhatsappNow(activity: Activity, who: String, text: String): Map<String, Any?> {
        if (text.isBlank()) return fail("What should the message say?")
        val r = resolve(activity, who) ?: return fail("I couldn't find ${who.substringBefore('|')} in your contacts")
        if (LeoAccessibility.instance == null) {
            // can't tap Send without the accessibility switch — open it ready to send
            return whatsapp(activity, r.second, text)
        }
        if (!Messaging.whatsappAutoSend(activity, r.second, text)) return fail("WhatsApp isn't installed")
        return ok("Sending your WhatsApp to ${r.first}")
    }

    /** One message to up to 20 named people (SMS or WhatsApp). */
    private fun sendBulk(activity: Activity, appRaw: String, whoRaw: Any?, text: String): Map<String, Any?> {
        if (text.isBlank()) return fail("What should the message say?")
        val names = when (whoRaw) {
            is List<*> -> whoRaw.map { it.toString() }
            else -> whoRaw?.toString()?.split(",", ";")?.map { it.trim() } ?: emptyList()
        }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return fail("Who should I send it to?")
        if (names.size > 20) return fail("I can send to up to 20 people at once — name fewer people")
        val app = if (appRaw.lowercase().contains("sms") || appRaw.lowercase().contains("text")) "sms" else "whatsapp"
        if (app == "sms" && !hasSmsPermission(activity)) return fail("Allow SMS for FocusLion so I can send it")
        if (app == "whatsapp" && LeoAccessibility.instance == null) return needAccessibility(activity)
        val found = ArrayList<Pair<String, String>>()
        val missing = ArrayList<String>()
        for (n in names) { val r = resolve(activity, n); if (r != null) found += r else missing += n.substringBefore('|') }
        if (found.isEmpty()) return fail("I couldn't find ${missing.joinToString()} in your contacts")
        Messaging.bulk(activity, app, found.distinctBy { it.second.filter { c -> c.isDigit() }.takeLast(10) }, text)
        val who = found.joinToString { it.first }
        return ok("Sending ${if (app == "sms") "SMS" else "WhatsApp"} to $who" +
            (if (missing.isNotEmpty()) " (couldn't find ${missing.joinToString()})" else ""))
    }

    /** at = "YYYY-MM-DDTHH:mm" in local time. */
    private fun scheduleMessage(activity: Activity, appRaw: String, who: String, text: String, at: String): Map<String, Any?> {
        if (text.isBlank()) return fail("What should the message say?")
        val app = if (appRaw.lowercase().contains("sms") || appRaw.lowercase().contains("text")) "sms" else "whatsapp"
        val r = resolve(activity, who) ?: return fail("I couldn't find ${who.substringBefore('|')} in your contacts")
        val whenMs = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(at)?.time
        } catch (_: Exception) { null } ?: return fail("I didn't get the time for that message")
        if (whenMs < System.currentTimeMillis() - 60_000) return fail("That time has already passed")
        if (app == "sms" && !hasSmsPermission(activity)) return fail("Allow SMS for FocusLion so I can send it on time")
        Messaging.schedule(activity, app, r.first, r.second, text, whenMs)
        val t = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(whenMs))
        val note = if (!Messaging.canExact(activity)) " (allow \"Alarms & reminders\" for FocusLion to send it exactly on time)" else ""
        return ok("Scheduled ${if (app == "sms") "SMS" else "WhatsApp"} to ${r.first} for $t$note")
    }

    private fun scheduledList(activity: Activity): Map<String, Any?> {
        val all = Messaging.list(activity)
        if (all.isEmpty()) return ok("No scheduled messages")
        val fmt = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.ENGLISH)
        return ok("Scheduled messages: " + all.joinToString("; ") {
            "${fmt.format(java.util.Date(it.optLong("at")))} → ${it.optString("name")} (${it.optString("app")}): \"${it.optString("text").take(60)}\""
        })
    }

    /** Today's screen time per app (top 8) — powers "how long was I on Instagram?". */
    private fun screenTime(activity: Activity): Map<String, Any?> {
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), activity.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), activity.packageName)
        }
        val granted = mode == android.app.AppOpsManager.MODE_ALLOWED
        if (!granted) {
            activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return fail("Allow Usage access for FocusLion, then ask me again")
        }
        val usm = activity.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }
        val stats = usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())
        val pm = activity.packageManager
        val top = stats.values
            .filter { it.totalTimeInForeground > 60_000 && it.packageName != activity.packageName }
            .sortedByDescending { it.totalTimeInForeground }
            .take(8)
            .map {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (_: Exception) { it.packageName }
                label to (it.totalTimeInForeground / 60_000).toInt()
            }
        val total = top.sumOf { it.second }
        val line = top.joinToString(", ") { "${it.first} ${it.second} min" }
        return ok(if (top.isEmpty()) "No screen time recorded today yet" else "Today: $line (top apps total ${total} min)",
            mapOf("apps" to top.map { mapOf("app" to it.first, "minutes" to it.second) }, "totalMin" to total))
    }

    private fun battery(activity: Activity): Map<String, Any?> {
        val bm = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return ok("Battery $level%" + if (charging) ", charging" else "", mapOf("level" to level, "charging" to charging))
    }
}
