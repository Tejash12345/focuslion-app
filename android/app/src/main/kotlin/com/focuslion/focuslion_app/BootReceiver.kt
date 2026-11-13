package com.focuslion.focuslion_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After reboot: restart the guard (if it was on) and re-arm roar reminders. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // FocusLion was updated: bring Leo back (the update stopped him)
            val lp = context.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
            if (lp.getBoolean("on", false)) {
                try { LeoBubbleService.start(context, lp.getBoolean("wake", false), lp.getBoolean("bubble", true)) } catch (_: Exception) {}
            }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("guard_on", false)
        if (on) {
            context.startForegroundService(Intent(context, GuardService::class.java))
        }
        ReminderScheduler.rescheduleAll(context)
        Messaging.rescheduleAll(context) // Leo's scheduled messages
        // bring Leo's bubble / "Hey Leo" back after a restart (Android 14+ may refuse
        // the microphone part until FocusLion is opened once — the service handles that)
        val lp = context.getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
        if (lp.getBoolean("on", false)) {
            try { LeoBubbleService.start(context, lp.getBoolean("wake", false), lp.getBoolean("bubble", true)) } catch (_: Exception) {}
        }
    }
}
