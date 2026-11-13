package com.focuslion.focuslion_app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Leo Live messaging: send an SMS or WhatsApp message for the user — right now
 * or at a scheduled time (survives app close and reboot). Leo always confirms
 * the exact text + recipient with the user BEFORE asking us to send.
 *
 *  • SMS is sent directly by the phone (SEND_SMS), even when locked.
 *  • WhatsApp has no public send API: we open the chat with the text filled
 *    in and LeoAccessibility taps Send. If the phone is locked at the
 *    scheduled time we post a "tap to send" notification instead.
 */
object Messaging {
    private const val PREFS = "leo_messages"
    private const val KEY = "scheduled"
    private const val CHANNEL = "leo_messages"

    // ---------- send now ----------
    fun sendSms(ctx: Context, number: String, text: String) {
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        sms.sendMultipartTextMessage(number, null, parts, null, null)
    }

    fun waNumber(raw: String): String {
        var n = raw.filter { it.isDigit() }
        if (n.length == 10) n = "91$n" // Indian mobile without country code
        if (n.length == 11 && n.startsWith("0")) n = "91" + n.drop(1)
        return n
    }

    /** Open the WhatsApp chat with the text and let the accessibility service tap Send. */
    fun whatsappAutoSend(ctx: Context, number: String, text: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waNumber(number)}?text=" + Uri.encode(text)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val hasWa = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
        } ?: return false
        i.setPackage(hasWa)
        LeoAccessibility.armSend(25_000)
        ctx.startActivity(i)
        return true
    }

    // ---------- scheduling ----------
    private fun load(ctx: Context): JSONArray =
        try { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    private fun save(ctx: Context, arr: JSONArray) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()

    fun schedule(ctx: Context, app: String, name: String, number: String, text: String, at: Long): JSONObject {
        val id = (System.currentTimeMillis() % 1_000_000_000).toInt()
        val o = JSONObject().put("id", id).put("app", app).put("name", name).put("number", number).put("text", text).put("at", at)
        val arr = load(ctx)
        arr.put(o)
        save(ctx, arr)
        arm(ctx, o)
        return o
    }

    fun list(ctx: Context): List<JSONObject> {
        val arr = load(ctx)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.sortedBy { it.optLong("at") }
    }

    /** Cancel by id, or by matching recipient name / text. Returns how many were cancelled. */
    fun cancel(ctx: Context, query: String): Int {
        val q = query.lowercase().trim()
        val keep = JSONArray()
        var n = 0
        for (o in list(ctx)) {
            val hit = q == "all" || o.optInt("id").toString() == q ||
                (q.isNotEmpty() && (o.optString("name").lowercase().contains(q) || o.optString("text").lowercase().contains(q)))
            if (hit) { disarm(ctx, o.optInt("id")); n++ } else keep.put(o)
        }
        save(ctx, keep)
        return n
    }

    fun rescheduleAll(ctx: Context) = list(ctx).forEach { arm(ctx, it) }

    private fun pending(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 900_000 + (id % 90_000),
            Intent(ctx, ScheduledMessageReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun arm(ctx: Context, o: JSONObject) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(o.optLong("at"), System.currentTimeMillis() + 5_000)
        val pi = pending(ctx, o.optInt("id"))
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // without "Alarms & reminders" permission Android may deliver a few minutes late
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun disarm(ctx: Context, id: Int) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id))
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    /** Alarm fired: send it (or ask the user to tap), then forget it. */
    fun fire(ctx: Context, id: Int) {
        val o = list(ctx).firstOrNull { it.optInt("id") == id } ?: return
        cancel(ctx, id.toString())
        val name = o.optString("name")
        val text = o.optString("text")
        val number = o.optString("number")
        try {
            if (o.optString("app") == "sms") {
                sendSms(ctx, number, text)
                notify(ctx, id, "✅ Leo sent your SMS to $name", text, null)
                return
            }
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val canOpen = pm.isInteractive && !km.isKeyguardLocked && Settings.canDrawOverlays(ctx) && LeoAccessibility.instance != null
            if (canOpen && whatsappAutoSend(ctx, number, text)) {
                notify(ctx, id, "✅ Leo sent your WhatsApp to $name", text, null)
            } else {
                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waNumber(number)}?text=" + Uri.encode(text)))
                notify(ctx, id, "🦁 Tap to send your WhatsApp to $name", text,
                    PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        } catch (e: Exception) {
            notify(ctx, id, "⚠ Leo couldn't send your message to $name", e.message ?: text, null)
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, body: String, tap: PendingIntent?) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Leo messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (tap != null) n.setContentIntent(tap)
        try { nm.notify(700_000 + (id % 90_000), n.build()) } catch (_: SecurityException) { /* notifications off */ }
    }
}

/** AlarmManager target for scheduled messages. */
class ScheduledMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id >= 0) Messaging.fire(context, id)
    }
}
