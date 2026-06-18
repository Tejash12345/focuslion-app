package com.focuslion.focuslion_app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.util.Calendar

/**
 * Schedules daily "roar reminders". Each reminder is a time of day; at that time
 * [ReminderReceiver] fires and shows the animated lion overlay with a roar.
 *
 * Uses inexact daily repeating alarms (no special permission, battery-friendly);
 * the actual time may drift by a few minutes, which is fine for a wellness nudge.
 */
object ReminderScheduler {
    const val PREFS = GuardService.PREFS // share the guard prefs file
    const val KEY_REMINDERS = "reminders_json" // JSON array of {"h":int,"m":int,"label":str?}
    const val ACTION_FIRE = "com.focuslion.focuslion_app.REMINDER_FIRE"

    data class Reminder(val h: Int, val m: Int, val label: String)

    fun load(context: Context): List<Reminder> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMINDERS, "[]") ?: "[]"
        val out = ArrayList<Reminder>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Reminder(o.optInt("h"), o.optInt("m"), o.optString("label", "")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Cancel previously-scheduled reminders, persist the new set, and schedule it. */
    fun setReminders(context: Context, json: String) {
        load(context).forEach { cancel(context, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_REMINDERS, json).apply()
        load(context).forEach { schedule(context, it) }
    }

    /** Re-arm all saved reminders (used after reboot). */
    fun rescheduleAll(context: Context) {
        load(context).forEach { schedule(context, it) }
    }

    private fun requestCode(r: Reminder): Int = r.h * 60 + r.m

    private fun pendingIntent(context: Context, r: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("label", r.label)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(r), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, r.h)
            set(Calendar.MINUTE, r.m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context, r),
        )
    }

    private fun cancel(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, r))
    }
}
