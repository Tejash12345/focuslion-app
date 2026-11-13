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
        val t = raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\b(please|leo|now|for me|the screen)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return false
        val a = LeoAccessibility.instance

        // ---- scrolling ----
        Regex("^(scroll|swipe|page|move)( (down|up|more|back up))?( a bit| more)?$|^next page$|^go (down|up)$").find(t)?.let {
            val up = t.contains("up") || t.contains("back")
            return a?.scroll(!up) ?: false
        }
        // ---- system navigation ----
        when {
            Regex("^(go )?back$").matches(t) -> return a?.global("back") ?: false
            Regex("^(go )?(to )?home( screen)?$").matches(t) -> return a?.global("home") ?: false
            Regex("^(show )?(my )?recent( apps)?$").matches(t) -> return a?.global("recents") ?: false
            Regex("^(show |open |pull down )?(my )?notifications$").matches(t) -> return a?.global("notifications") ?: false
            Regex("^(open )?quick settings$").matches(t) -> return a?.global("quick_settings") ?: false
            Regex("^(take (a )?)?screen ?shot$").matches(t) -> return a?.global("screenshot") ?: false
            Regex("^lock( (the|my) (phone|screen))?$").matches(t) -> return a?.global("lock") ?: false
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
