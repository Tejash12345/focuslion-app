package com.focuslion.focuslion_app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Builds the full-screen lion block UI programmatically (no XML resources).
 * Shared by the WindowManager overlay in [GuardService] and the legacy
 * [BlockActivity] fallback. [onDismiss] runs when the user taps the button.
 *
 * The lion is animated natively: it pounces in, breathes, and periodically
 * shakes — works instantly, offline, and with no watermark.
 */
fun buildBlockView(
    context: Context,
    appName: String,
    reason: String,
    allowedWindow: String,
    limit: Int,
    usedMin: Int,
    onDismiss: () -> Unit,
): View {
    val lines: List<String> = if (reason == "limit") {
        listOf(
            "You've reached today's limit for $appName.",
            if (usedMin >= 0) "Used today: $usedMin min of your $limit min limit."
            else "Your daily limit of $limit minutes is used up.",
            "Come back tomorrow — focus on your goals now. 🌅",
        )
    } else {
        listOf(
            "$appName is locked right now.",
            "Your allowed time is $allowedWindow.",
            "Right now belongs to your goals. 🌅",
        )
    }
    return lionScreen(context, "ROAAAR! Not now.", lines, "Back to my goals  →", onDismiss)
}

/**
 * A lighter version of the lion screen used for scheduled roar reminders.
 * Same animated lion, friendlier copy, and a simple dismiss button.
 */
fun buildReminderView(
    context: Context,
    message: String,
    onDismiss: () -> Unit,
): View {
    val lines = listOf(message, "Take a breath and get back to what matters. 🌅")
    return lionScreen(context, "🦁 ROAAAR!", lines, "Got it  →", onDismiss)
}

// ---- shared builder ----

private fun lionScreen(
    context: Context,
    headline: String,
    lines: List<String>,
    ctaText: String,
    onDismiss: () -> Unit,
): View {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(80, 0, 80, 0)
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#1a0e02"), Color.parseColor("#2d1503"), Color.parseColor("#0b0d14")),
        )
        // opaque + clickable so touches can't fall through to the app behind
        isClickable = true
    }

    fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false, topMargin: Int = 0): TextView =
        TextView(context).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = topMargin
            layoutParams = lp
        }

    // The lion lives in a wide box (tag "lionBox") so the guard can drop a 3D
    // WebView lion in on top of the emoji when online. Made large with headroom
    // so big animations (e.g. the jump) are shown fully and never cut off.
    val density = context.resources.displayMetrics.density
    val boxH = (360 * density).toInt()
    val lionBox = FrameLayout(context).apply {
        tag = "lionBox"
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            boxH,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }
    val lion = TextView(context).apply {
        text = "🦁"
        textSize = 96f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }
    lionBox.addView(lion)
    root.addView(lionBox)
    root.addView(text(headline, 28f, Color.parseColor("#FFF8E7"), bold = true, topMargin = 24))
    lines.forEachIndexed { i, line ->
        if (i == 0) {
            root.addView(text(line, 17f, Color.parseColor("#FFE3B3"), topMargin = 28))
        } else {
            root.addView(text(line, 15f, Color.parseColor("#D9C29A"), topMargin = if (i == 1) 10 else 6))
        }
    }

    val btn = Button(context).apply {
        text = ctaText
        textSize = 16f
        setTextColor(Color.parseColor("#241a05"))
        isAllCaps = false
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#FFB454"), Color.parseColor("#FF9D4D")),
        ).apply { cornerRadius = 60f }
        setPadding(80, 36, 80, 36)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = 64
        lp.gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = lp
        setOnClickListener { onDismiss() }
    }
    root.addView(btn)

    animateLion(lion)

    // immersive full screen
    @Suppress("DEPRECATION")
    root.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    )

    return root
}

/** Pounce in with an overshoot, then breathe forever and shake every few seconds. */
private fun animateLion(lion: TextView) {
    lion.scaleX = 0.3f
    lion.scaleY = 0.3f
    lion.alpha = 0f
    lion.animate()
        .scaleX(1f).scaleY(1f).alpha(1f)
        .setStartDelay(80)
        .setDuration(520)
        .setInterpolator(OvershootInterpolator(2.6f))
        .withEndAction {
            ObjectAnimator.ofPropertyValuesHolder(
                lion,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            ).apply {
                duration = 850
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
        .start()

    // periodic angry shake, stops automatically once the overlay is removed
    val shake = object : Runnable {
        override fun run() {
            if (!lion.isAttachedToWindow) return
            ObjectAnimator.ofFloat(lion, View.ROTATION, 0f, -8f, 8f, -6f, 6f, 0f).apply {
                duration = 450
                start()
            }
            lion.postDelayed(this, 3200)
        }
    }
    lion.postDelayed(shake, 1200)
}
