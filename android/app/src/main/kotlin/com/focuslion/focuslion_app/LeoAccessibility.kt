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

    /**
     * The window of the app the user is actually looking at (YouTube,
     * WhatsApp…). rootInActiveWindow can be FocusLion's own floating bubble
     * overlay, which made "scroll down" refuse and open Leo instead — so we
     * look through the on-screen windows (top first) for a real app window
     * that isn't ours.
     */
    fun appRoot(): AccessibilityNodeInfo? {
        try {
            val apps = windows
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
            for (w in apps) {
                val r = w.root ?: continue
                if (r.packageName?.toString() != packageName) return r
            }
        } catch (_: Exception) { }
        val r = rootInActiveWindow ?: return null
        return if (r.packageName?.toString() == packageName) null else r
    }

    /** Scroll the main list/page of the current app (vertical lists first). */
    fun scroll(forward: Boolean): Boolean {
        val root = appRoot() ?: return false
        if (root.packageName?.toString() == packageName) return false // never scroll Leo himself
        // prefer a node that can scroll DOWN/UP (vertical) over tab pagers
        if (Build.VERSION.SDK_INT >= 23) {
            val dir = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            val v = biggestScrollable(root, { n -> n.actionList.any { it.id == dir.id } })
            if (v != null && v.performAction(dir.id)) return true
        }
        val target = biggestScrollable(root, { true })
        if (target != null && target.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        return swipe(forward) // apps without accessible scroll views
    }

    private fun biggestScrollable(n: AccessibilityNodeInfo?, ok: (AccessibilityNodeInfo) -> Boolean, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        var best: AccessibilityNodeInfo? = if (n.isScrollable && n.isVisibleToUser && ok(n)) n else null
        var bestArea = best?.let { area(it) } ?: 0
        for (i in 0 until n.childCount) {
            val c = biggestScrollable(n.getChild(i), ok, depth + 1) ?: continue
            val a = area(c)
            if (a > bestArea) { best = c; bestArea = a }
        }
        return best
    }

    private fun area(n: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect(); n.getBoundsInScreen(r); return r.width() * r.height()
    }

    /** A finger swipe up/down the middle of the screen (needs canPerformGestures). */
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

    // ---------- smooth, human-like scrolling (any app) ----------

    /**
     * Scroll by a fraction of the screen with a finger-like drag. `fraction`
     * 0.15 = a little, 0.45 = normal, 0.75 = a lot; slower `ms` = smoother.
     */
    fun scrollBy(up: Boolean, fraction: Float, ms: Long): Boolean {
        if (appRoot() == null) return false
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val span = dm.heightPixels * fraction.coerceIn(0.08f, 0.8f)
        val mid = dm.heightPixels * 0.55f
        val (y1, y2) = if (!up) (mid + span / 2) to (mid - span / 2) else (mid - span / 2) to (mid + span / 2)
        val path = android.graphics.Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, ms.coerceIn(120, 2000)))
            .build()
        return dispatchGesture(g, null, null)
    }

    private val scrollLoop = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile var autoScrolling = false
        private set

    /** Keep scrolling gently (reading mode) until stopAutoScroll() — max 15 minutes. */
    fun startAutoScroll(up: Boolean, intervalMs: Long, fraction: Float) {
        stopAutoScroll()
        autoScrolling = true
        val until = System.currentTimeMillis() + 15 * 60_000
        val tick = object : Runnable {
            override fun run() {
                if (!autoScrolling || System.currentTimeMillis() > until) { autoScrolling = false; return }
                scrollBy(up, fraction, (intervalMs * 0.6).toLong())
                scrollLoop.postDelayed(this, intervalMs)
            }
        }
        scrollLoop.post(tick)
    }

    fun stopAutoScroll(): Boolean {
        val was = autoScrolling
        autoScrolling = false
        scrollLoop.removeCallbacksAndMessages(null)
        return was
    }

    /** Swipe left/right (stories, tabs, photo galleries). */
    fun swipeSideways(toLeft: Boolean): Boolean {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.5f
        val (x1, x2) = if (toLeft) dm.widthPixels * 0.82f to dm.widthPixels * 0.18f else dm.widthPixels * 0.18f to dm.widthPixels * 0.82f
        val path = android.graphics.Path().apply { moveTo(x1, y); lineTo(x2, y) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 240))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** Is this text box a SEARCH box (not a chat/comment box, where Enter = Send)? */
    private fun isSearchField(n: AccessibilityNodeInfo?): Boolean {
        if (n == null || !n.isEditable || n.isPassword) return false
        val id = n.viewIdResourceName?.lowercase().orEmpty()
        val cls = n.className?.toString()?.lowercase().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.lowercase().orEmpty() else ""
        val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
        return "search" in id || "search" in cls || "search" in hint || "search" in desc || "query" in id
    }

    /**
     * "search for lofi music": open the app's search, type into the SEARCH box
     * (never a chat box — Enter there would send a message), press Enter.
     * Returns whether a search box could be opened; `after` gets the final result.
     */
    fun searchFor(query: String, after: (Boolean) -> Unit): Boolean {
        val root = appRoot() ?: return false
        if (root.packageName?.toString() == packageName) return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val opened = isSearchField(focused) || tapText("search") || tapText("search youtube") || tapText("search instagram")
        if (!opened) return false
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({
            val box = appRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (!isSearchField(box) || !typeText(query, replace = true)) { after(false); return@postDelayed }
            h.postDelayed({ after(pressEnter()) }, 250)
        }, 700)
        return true
    }

    /** Press the keyboard's Enter/Search key in the focused field (Android 11+). */
    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val f = appRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Buttons Leo never taps by voice — losing data or money must stay a human tap. */
    private val DANGER = Regex("(?i)\\b(delete|remove|uninstall|pay|payment|buy|purchase|confirm|transfer|send money|reset|erase|clear all|clear data|format|log ?out|sign ?out|block|report|unsubscribe|subscribed|unlike|upi|deactivate)\\b|₹")

    /**
     * Tap the visible item whose text is exactly what the user said ("tap
     * Storage"), or the single item containing it as a whole word. Never taps
     * dangerous buttons (delete, pay, uninstall, …).
     */
    fun tapText(label: String): Boolean {
        val root = appRoot() ?: return false
        if (root.packageName?.toString() == packageName) return false
        val q = label.trim().lowercase()
        if (q.length < 2) return false
        val hits = root.findAccessibilityNodeInfosByText(label.trim()).filter { it.isVisibleToUser }
        fun text(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
        val exact = hits.filter { text(it) == q }
        val word = hits.filter { Regex("(^|\\W)" + Regex.escape(q) + "(\\W|$)").containsMatchIn(text(it)) }
        // icon buttons are often labelled "Like this video along with …", "Share" …:
        // accept the single CLICKABLE item whose label starts with the words said
        val starts = hits.filter { text(it).startsWith("$q ") }
            .filter { it.isClickable || it.parent?.isClickable == true }
        // several exact matches (a feed: every post has "Like") -> the one nearest
        // the middle of the screen, i.e. the post the user is looking at
        val midY = resources.displayMetrics.heightPixels / 2
        val centered = exact.minByOrNull { n -> val r = android.graphics.Rect(); n.getBoundsInScreen(r); kotlin.math.abs(r.centerY() - midY) }
        val node = centered ?: word.singleOrNull() ?: starts.singleOrNull() ?: return false
        if (DANGER.containsMatchIn(text(node))) return false
        var c: AccessibilityNodeInfo? = node
        var hops = 0
        while (c != null && !c.isClickable && hops < 3) { c = c.parent; hops++ }
        val target = if (c?.isClickable == true) c else node
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Type into the focused text box of the current app — "type hello". Never into passwords. */
    fun typeText(text: String, replace: Boolean = false): Boolean {
        val root = appRoot() ?: return false
        if (root.packageName?.toString() == packageName) return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return false
        if (field.isPassword) return false
        // append to what's already typed (Android 8+ can tell real text from the hint);
        // a search replaces the old query instead
        val existing = if (!replace && Build.VERSION.SDK_INT >= 26 && field.isEditable && !field.isShowingHintText) field.text?.toString().orEmpty() else ""
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
