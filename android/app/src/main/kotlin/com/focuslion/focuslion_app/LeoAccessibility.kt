package com.focuslion.focuslion_app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Leo Live's "hands and eyes" — an Accessibility service the user turns on
 * once (Settings › Accessibility › FocusLion Leo). It lets Leo:
 *  • open the power menu (to power off / restart), lock the screen, take a
 *    screenshot, go home/back/recents, pull down notifications;
 *  • read the LAST screen you were on in another app, so you can switch to
 *    Leo and ask "what should I reply?" or "summarise this".
 * Screen text is kept only in memory (the latest snapshot), never saved, and
 * only leaves the phone when the user asks Leo about it.
 */
class LeoAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: LeoAccessibility? = null
        @Volatile private var lastText: String = ""
        @Volatile private var lastApp: String = ""
        @Volatile private var lastAt: Long = 0
        @Volatile private var lastPkg: String = ""
        fun lastScreen(): Triple<String, String, Long> = Triple(lastApp, lastText, lastAt)
        fun lastPackage(): String = lastPkg

        // Leo just opened a WhatsApp chat with the user's confirmed text:
        // tap its Send button once, within this window
        @Volatile private var sendUntil: Long = 0
        fun armSend(ms: Long) { sendUntil = System.currentTimeMillis() + ms }
    }

    private var lastSnap = 0L

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (sendUntil > System.currentTimeMillis() && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) {
            if (tapWhatsappSend(pkg)) sendUntil = 0
        }
        // ignore our own app, the launcher-ish system UI and the keyboard
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg == homePackage()) return
        val now = System.currentTimeMillis()
        // throttle, but never drop the latest state: snapshot at most every 700 ms
        if (now - lastSnap < 700) return
        lastSnap = now
        val root = rootInActiveWindow ?: return
        // the active window may already be FocusLion (or another app) — only
        // keep text that really belongs to the app this event came from
        if (root.packageName?.toString() != pkg) return
        val sb = StringBuilder()
        collect(root, sb, 0)
        val text = sb.toString().trim()
        if (text.length < 3) return
        lastText = text.take(6000)
        lastApp = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        lastAt = now
        lastPkg = pkg
    }

    private var homePkg: String? = null
    private fun homePackage(): String {
        homePkg?.let { return it }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val p = packageManager.resolveActivity(i, 0)?.activityInfo?.packageName ?: ""
        homePkg = p
        return p
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 40 || sb.length > 7000) return
        // never capture passwords / secure fields
        if (n.isPassword) return
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) sb.append(t).append('\n') else if (d.isNotEmpty() && d.length < 80) sb.append(d).append('\n')
        for (i in 0 until n.childCount) collect(n.getChild(i), sb, depth + 1)
    }

    /** Find WhatsApp's Send button (by id, then by label) and tap it once. */
    private fun tapWhatsappSend(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
        val target = byId.firstOrNull { it.isVisibleToUser && it.isEnabled }
            ?: findByDesc(root, setOf("send", "पाठवा", "भेजें", "పంపు", "పంపండి"))
            ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findByDesc(n: AccessibilityNodeInfo?, labels: Set<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        if (d != null && d in labels && n.isClickable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findByDesc(n.getChild(i), labels, depth + 1)?.let { return it }
        return null
    }

    override fun onInterrupt() {}

    // ---------- hands on the current screen (the app the user is looking at) ----------

    /** Scroll the main list/page of the current app. */
    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() == packageName) return false // never scroll Leo himself
        val target = biggestScrollable(root)
        if (target != null && target.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        return swipe(forward) // apps without accessible scroll views
    }

    private fun biggestScrollable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        var best: AccessibilityNodeInfo? = if (n.isScrollable && n.isVisibleToUser) n else null
        var bestArea = best?.let { area(it) } ?: 0
        for (i in 0 until n.childCount) {
            val c = biggestScrollable(n.getChild(i), depth + 1) ?: continue
            val a = area(c)
            if (a > bestArea) { best = c; bestArea = a }
        }
        return best
    }

    private fun area(n: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect(); n.getBoundsInScreen(r); return r.width() * r.height()
    }

    /** A finger swipe up/down the middle of the screen. */
    private fun swipe(forward: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (forward) dm.heightPixels * 0.72f to dm.heightPixels * 0.28f else dm.heightPixels * 0.28f to dm.heightPixels * 0.72f
        val path = android.graphics.Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** Tap the visible button/item whose text (or label) matches — "tap Storage". */
    fun tapText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() == packageName) return false
        val q = label.trim().lowercase()
        if (q.isEmpty()) return false
        val hits = root.findAccessibilityNodeInfosByText(label.trim()).filter { it.isVisibleToUser }
        // prefer an exact match, then the shortest label that contains it
        val node = hits.minByOrNull {
            val t = (it.text ?: it.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
            if (t == q) 0 else if (t.startsWith(q)) 1 + t.length else 1000 + t.length
        } ?: return false
        var c: AccessibilityNodeInfo? = node
        while (c != null && !c.isClickable) c = c.parent
        return (c ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Type into the focused text box of the current app — "type hello". */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() == packageName) return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return false
        val hint = Build.VERSION.SDK_INT >= 26 && field.isShowingHintText
        val existing = field.text?.toString().orEmpty().takeIf { field.isEditable && !hint } ?: ""
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (existing + (if (existing.isEmpty()) "" else " ") + text).trim())
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun firstEditable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstEditable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    fun global(action: String): Boolean {
        val a = when (action) {
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(a)
    }
}
