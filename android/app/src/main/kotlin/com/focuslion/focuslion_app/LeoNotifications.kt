package com.focuslion.focuslion_app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * "Leo, any new messages?" — keeps the latest message-style notifications
 * (WhatsApp, SMS, Telegram, Instagram, Gmail…) IN MEMORY ONLY so Leo can tell
 * you who sent what. Opt-in via Settings › Notification access. Nothing is
 * stored on disk or uploaded unless you ask Leo about your messages, and
 * one-time codes (OTPs) are masked.
 */
class LeoNotifications : NotificationListenerService() {

    data class Item(val app: String, val from: String, val text: String, val at: Long)

    companion object {
        @Volatile var connected = false
        private val items = ArrayDeque<Item>()
        private const val MAX = 40
        private val OTP = Regex("\\b\\d{4,8}\\b")
        private val SKIP_PKGS = setOf("android", "com.android.systemui", "com.focuslion.focuslion_app")

        @Synchronized fun add(i: Item) {
            items.removeAll { it.app == i.app && it.from == i.from && it.text == i.text }
            items.addFirst(i)
            while (items.size > MAX) items.removeLast()
        }

        @Synchronized fun recent(sinceMs: Long): List<Item> = items.filter { it.at >= sinceMs }

        @Synchronized fun clear() = items.clear()

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, LeoNotifications::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        /** Mask verification codes so they never reach the AI. */
        fun safe(text: String): String =
            if (Regex("(?i)otp|verification|code|password|pin").containsMatchIn(text)) OTP.replace(text, "••••") else text
    }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName in SKIP_PKGS || n.isOngoing) return
        val e = n.notification.extras ?: return
        val cat = n.notification.category
        if (cat != null && cat != Notification.CATEGORY_MESSAGE && cat != Notification.CATEGORY_EMAIL &&
            cat != Notification.CATEGORY_SOCIAL && cat != Notification.CATEGORY_CALL) return
        // group summaries ("5 new messages") add nothing
        if (n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString()
        } catch (_: Exception) { n.packageName }
        add(Item(app, title, safe(text).take(400), n.postTime))
    }
}
