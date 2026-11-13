package com.focuslion.focuslion_app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Commands that act on the screen you're on — run right there after "Hey Leo",
 * WITHOUT opening Leo's window (it would cover the app you're controlling):
 *   scroll down/up · tap <text> · open <thing on screen / settings page / app>
 *   type <text> · send · go back · go home · recent apps · notifications ·
 *   screenshot · lock
 * Returns true when handled; anything else goes to Leo's window as usual.
 */
object QuickCommands {

    fun tryRun(ctx: Context, raw: String): Boolean {
        val t = normalize(raw)
        if (t.isEmpty()) return false
        val a = LeoAccessibility.instance
        val words = t.split(" ")

        // ---- scrolling (forgiving: "scroll", "scroll down a bit", "school done"…) ----
        val isScroll = (words[0] == "scroll" && words.size <= 5 && words.drop(1).all { it in SCROLL_WORDS }) ||
            Regex("^(go|move|page|swipe) (down|up)( more| to top)?$").matches(t) || t == "next page" || t == "page down" || t == "page up"
        if (isScroll) {
            val up = words.any { it == "up" || it == "top" || it == "upwards" || it == "back" }
            // a recognised command never falls back to Leo's window, even if this screen can't scroll
            a?.scroll(!up)
            return a != null
        }
        // ---- reels / shorts / videos: next & previous ----
        when (t) {
            "next", "next video", "next reel", "next short", "next one", "next song", "go next", "play next video", "play next" -> { a?.scroll(true); return a != null }
            // during an ad, "skip" means the Skip button; otherwise next video
            "skip" -> return a?.tapText("skip ad") == true || a?.tapText("skip") == true || a?.scroll(true) == true
            "previous", "previous video", "previous reel", "go previous", "last one", "previous song" -> { a?.scroll(false); return a != null }
            "swipe left", "next story", "next photo", "next tab" -> { a?.swipeSideways(true); return a != null }
            "swipe right", "previous story", "previous photo", "previous tab" -> { a?.swipeSideways(false); return a != null }
        }
        // ---- media in any app ----
        when (t) {
            "pause", "pause it", "pause video", "pause the video", "pause music", "stop", "stop video", "stop music", "stop it" -> return mediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            "play", "resume", "play video", "resume video", "play music" -> return mediaKey(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            "skip ad", "skip the ad", "skip ads", "skip add", "skip this ad", "skip it" -> return a?.tapText("skip ad") == true || a?.tapText("skip") == true
            "like", "like this", "like video", "like this video", "like it" -> return a?.tapText("like") ?: false
            "subscribe", "subscribe to this channel", "subscribe channel", "subscribe to channel" -> return a?.tapText("subscribe") ?: false
            "share", "share this" -> return a?.tapText("share") ?: false
            "comment", "comments", "open comments" -> return a?.tapText("comment") == true || a?.tapText("comments") == true
        }
        // ---- search inside the app you're using ----
        Regex("^search( for)? (.+)$").find(t)?.let { m ->
            if (LEO_OWNS.containsMatchIn(m.groupValues[2])) return false // "search messages from Ravi" -> Leo
            val svc = a ?: return false
            return svc.searchFor(m.groupValues[2]) { } // false -> Leo's window handles it
        }
        // ---- system navigation ----
        when {
            Regex("^(go |press |tap )?back$").matches(t) -> return a?.global("back") ?: false
            Regex("^(go )?(to )?home( screen)?$").matches(t) -> return a?.global("home") ?: false
            Regex("^(show )?(my )?recent( apps)?$").matches(t) -> return a?.global("recents") ?: false
            Regex("^(show |open |pull down )?(my )?notifications$").matches(t) -> return a?.global("notifications") ?: false
            Regex("^(open )?quick settings$").matches(t) -> return a?.global("quick_settings") ?: false
            Regex("^(take (a )?)?screen ?shot$").matches(t) -> return a?.global("screenshot") ?: false
            Regex("^lock( (my )?(phone|screen))?$").matches(t) -> return a?.global("lock") ?: false
            t == "send" || t == "tap send" || t == "send it" -> return a?.tapText("Send") ?: false
        }
        // ---- typing into the focused box: only "type …" ----
        Regex("^type (.+)$").find(t)?.let { m ->
            // keep the user's words, minus the wake/filler words
            val original = Regex("(?i)^\\s*(please |hey |hi |ok |okay )*(leo[, ]*)?(please )?type\\s+").replace(raw.trim(), "")
            return a?.typeText(original.ifBlank { m.groupValues[1] }) ?: false
        }
        // ---- tap something on screen (exact text; never dangerous buttons) ----
        Regex("^(tap|click|press|hit)( on)? (.+)$").find(t)?.let { m ->
            return a?.tapText(m.groupValues[3]) ?: false
        }
        // ---- open: an exact on-screen item, a settings page, or an app ----
        Regex("^(open|go to)( my)? (.+?)( settings| setting| app)?$").find(t)?.let { m ->
            val thing = m.groupValues[3].trim()
            val saidSettings = m.groupValues[4].contains("setting")
            // too vague, or something Leo himself handles (his window answers these)
            if (thing.length < 3 || thing in VAGUE || LEO_OWNS.containsMatchIn(thing)) return false
            if (saidSettings) {
                if (thing == "settings") return start(ctx, Intent(Settings.ACTION_SETTINGS))
                settingsIntent(thing)?.let { return start(ctx, it) }
                return false
            }
            if (thing == "settings") return start(ctx, Intent(Settings.ACTION_SETTINGS))
            if (a?.tapText(thing) == true) return true // e.g. "open Storage" inside Settings
            if (openApp(ctx, thing)) return true
            exactSettingsIntent(thing)?.let { return start(ctx, it) }
            return false
        }
        return false
    }

    private val VAGUE = setOf("it", "this", "that", "me", "them", "something", "anything", "the app", "app")
    /** Leo's own features — never hijack these into a random app or settings page. */
    private val LEO_OWNS = Regex("\\b(tasks?|messages?|notes?|timetable|timer|focus|planner|calendar|agenda|weather|news|screen ?time|flashcards?|quiz|study|exam|mission|leo|focuslion)\\b")

    /** Settings page only when the name IS a settings page ("storage", "wi-fi"…). */
    private fun exactSettingsIntent(thing: String): Intent? =
        if (thing in setOf("storage", "battery", "display", "sound", "wifi", "wi fi", "bluetooth", "location", "apps",
                "notifications", "accessibility", "developer options", "about phone", "airplane mode", "hotspot", "vpn", "nfc"))
            settingsIntent(thing) else null

    private val SCROLL_WORDS = setOf("down", "up", "more", "to", "top", "bottom", "back", "upwards", "screen", "page", "slowly", "once", "twice")

    /** Filler / politeness words people (and the offline recognizer) add. */
    private val FILLER = setOf(
        "please", "leo", "hey", "hi", "hello", "ok", "okay", "now", "the", "a", "an", "uh", "um", "umm", "ah", "er",
        "just", "can", "could", "would", "will", "you", "u", "then", "little", "bit", "me", "quickly", "again", "pls",
    )

    /** Words the small offline model commonly mishears, mapped back. */
    private val FIXES = mapOf(
        "school" to "scroll", "scrawl" to "scroll", "crawl" to "scroll", "scrolled" to "scroll", "scrolling" to "scroll",
        "squirrel" to "scroll", "scrool" to "scroll", "screw" to "scroll", "sroll" to "scroll", "stroll" to "scroll", "scrolls" to "scroll",
        "done" to "down", "dawn" to "down", "town" to "down", "don" to "down", "downward" to "down", "downwards" to "down",
        "nest" to "next", "necks" to "next", "text" to "next", "positive" to "pause", "paws" to "pause", "pas" to "pause",
        "bag" to "back", "beck" to "back", "hoem" to "home", "tab" to "tap", "type" to "type", "like" to "like",
    )

    /** lowercase, no punctuation, mishearings fixed, filler words removed. */
    fun normalize(raw: String): String {
        val cleaned = raw.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace("the screen", " ").replace("for me", " ")
        val toks = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        // "text"/"tab" only mean "next"/"tap" in a short command, not "text Ravi …"
        val fixed = toks.map { w -> if (toks.size > 2 && (w == "text" || w == "tab")) w else FIXES[w] ?: w }
        // keep filler words inside "type …" (the user's message)
        val i = fixed.indexOf("type")
        val head = if (i >= 0) fixed.subList(0, i + 1) else fixed
        val tail = if (i >= 0) fixed.subList(i + 1, fixed.size) else emptyList()
        return (head.filter { it !in FILLER } + tail).joinToString(" ").trim()
    }

    /** play / pause whatever app is playing (YouTube, Spotify, …). */
    private fun mediaKey(ctx: Context, code: Int): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        // Leo just gave audio focus back and the player is resuming: send the key a
        // moment later so "pause" isn't undone by that resume
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }, 400)
        true
    } catch (_: Exception) { false }

    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    private fun openApp(ctx: Context, name: String): Boolean {
        if (name.length < 3) return false
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val word = Regex("(^|\\s)" + Regex.escape(name) + "(\\s|$)")
        val hit = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { (label, _) -> label.lowercase() == name || word.containsMatchIn(label.lowercase()) }
            .minByOrNull { (label, _) -> if (label.lowercase() == name) 0 else label.length } ?: return false
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return false
        return start(ctx, i)
    }

    /** "storage" -> the Storage settings page, etc. Shared with DeviceControl. */
    fun settingsIntent(panelRaw: String): Intent? {
        val p = panelRaw.lowercase().replace("settings", "").replace("setting", "").trim()
        val action = when {
            p.isEmpty() -> null
            p.contains("wifi") || p.contains("wi fi") || p.contains("wi-fi") ->
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            p.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            p.contains("storage") || p.contains("memory") || p.contains("space") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            p.contains("battery") || p.contains("power") -> Intent.ACTION_POWER_USAGE_SUMMARY
            p.contains("display") || p.contains("bright") || p.contains("screen") -> Settings.ACTION_DISPLAY_SETTINGS
            p.contains("sound") || p.contains("volume") || p.contains("ringtone") -> Settings.ACTION_SOUND_SETTINGS
            p.contains("notification") -> if (Build.VERSION.SDK_INT >= 26) "android.settings.NOTIFICATION_SETTINGS" else Settings.ACTION_SETTINGS
            p == "apps" || p.contains("applications") || p.contains("app list") || p.contains("installed") -> Settings.ACTION_APPLICATION_SETTINGS
            p.contains("location") || p.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            p.contains("data usage") || p.contains("mobile data") -> Settings.ACTION_DATA_ROAMING_SETTINGS
            p.contains("internet") || p.contains("network") -> Settings.ACTION_WIRELESS_SETTINGS
            p.contains("hotspot") || p.contains("tether") -> Settings.ACTION_WIRELESS_SETTINGS
            p.contains("airplane") || p.contains("flight") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            p.contains("language") || p.contains("keyboard") -> Settings.ACTION_LOCALE_SETTINGS
            p.contains("date") || p.contains("time") -> Settings.ACTION_DATE_SETTINGS
            p.contains("security") || p.contains("lock screen") || p.contains("password") -> Settings.ACTION_SECURITY_SETTINGS
            p.contains("privacy") -> Settings.ACTION_PRIVACY_SETTINGS
            p.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            p.contains("about") || p.contains("phone info") || p.contains("device info") -> Settings.ACTION_DEVICE_INFO_SETTINGS
            p.contains("developer") -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            p.contains("vpn") -> Settings.ACTION_VPN_SETTINGS
            p.contains("nfc") -> Settings.ACTION_NFC_SETTINGS
            p.contains("account") -> Settings.ACTION_SYNC_SETTINGS
            p.contains("default app") -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            else -> null
        } ?: return null
        return Intent(action)
    }
}
