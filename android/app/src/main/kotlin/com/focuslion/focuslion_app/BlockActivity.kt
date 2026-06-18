package com.focuslion.focuslion_app

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle

/**
 * Full-screen lion block screen as a standalone Activity.
 *
 * NOTE: this is a legacy fallback. The guard now draws the block screen as a
 * system overlay from [GuardService] (see buildBlockView), because launching an
 * Activity from a background service is blocked on Android 14+ (target SDK 34+).
 * The shared UI lives in BlockView.kt so both paths stay in sync.
 */
class BlockActivity : Activity() {

    private var roar: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // real lion roar — Growcott et al., CC BY 4.0, via Wikimedia Commons.
        // ALARM usage so it's heard even when media volume is low or muted.
        try {
            val mp = MediaPlayer()
            val afd = resources.openRawResourceFd(R.raw.lion_roar)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setVolume(1f, 1f)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            roar = mp
        } catch (_: Exception) {
        }

        val view = buildBlockView(
            context = this,
            appName = intent.getStringExtra("appName") ?: "This app",
            reason = intent.getStringExtra("reason") ?: "schedule",
            allowedWindow = intent.getStringExtra("window") ?: "your allowed time",
            limit = intent.getIntExtra("limit", 0),
            usedMin = intent.getIntExtra("usedMin", -1),
            onDismiss = { goHome() },
        )
        setContentView(view)
    }

    override fun onDestroy() {
        try {
            roar?.release()
        } catch (_: Exception) {
        }
        roar = null
        super.onDestroy()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // no escaping past the lion — back goes home
        goHome()
    }
}
