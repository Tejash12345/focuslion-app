package com.focuslion.focuslion_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After reboot: restart the guard (if it was on) and re-arm roar reminders. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("guard_on", false)
        if (on) {
            context.startForegroundService(Intent(context, GuardService::class.java))
        }
        ReminderScheduler.rescheduleAll(context)
    }
}
