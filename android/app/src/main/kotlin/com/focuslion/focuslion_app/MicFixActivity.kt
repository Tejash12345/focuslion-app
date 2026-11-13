package com.focuslion.focuslion_app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible, instant screen that gives Leo his microphone back.
 *
 * Android mutes the mic of a background service that was (re)started while
 * FocusLion was closed (after an update, a reboot, or the system killing it).
 * The mic is only granted to a service started while the app is on screen —
 * so this transparent screen opens for a moment, restarts Leo's service from
 * the foreground, and closes. Nothing is shown to the user.
 */
class MicFixActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val lp = getSharedPreferences("leo_bubble", Context.MODE_PRIVATE)
        try { LeoBubbleService.start(this, lp.getBoolean("wake", true), lp.getBoolean("bubble", true)) } catch (_: Exception) { }
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }, 400)
    }
}
