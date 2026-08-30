package com.highsockscapital.sunshine.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * SunshineAccessibilityService — Sunshine's eyes into the whole device.
 *
 * Tier-1 "Primary Engine" of the agent architecture:
 *  - persists across reboots via the system Accessibility registry
 *  - zero recurring permissions once granted (survives Shizuku restarts)
 *  - native tree reading, gestures, and input injection
 *
 * Current scope: EYES + HANDS.
 *  - eyes: tree dumps, focus events, and [describeScreen] snapshots
 *  - hands: taps, swipes, scrolls, text injection, global navigation
 *  - every hand-move logs to logcat and reports success/failure
 *
 * The constitution clause "act freely, but logged" applies:
 * action outcomes are emitted so Sunshine's notebook stays honest.
 */
class SunshineAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SunshineA11y"

        @Volatile
        var instance: SunshineAccessibilityService? = null
            private set

        fun isActive(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Harden the config: be explicit about what we listen to.
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        Log.i(TAG, "Sunshine eyes are open. Packages now visible.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val className = event.className?.toString() ?: "unknown"

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.i(TAG, "window-changed pkg=$pkg class=$className")
                // Dump a compact tree snapshot so Sunshine can reason about layout.
                dumpCurrentTree(pkg)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val text = event.text?.joinToString(" ")?.trim().orEmpty()
                Log.d(TAG, "focus pkg=$pkg class=$className text=\"${text.take(80)}\"")
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Sunshine eyes interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "Sunshine eyes closed")
    }

    /**
     * Dump a flattened textual view of the active window tree.
     * Eyes-only for now — logs a structural map Sunshine can consume.
     */
    private fun dumpCurrentTree(contextPkg: String) {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "No active window for pkg=$contextPkg")
            return
        }

        val builder = StringBuilder()
        builder.append("=== Sunshine tree dump for ").append(contextPkg).append('\n')
        val screenBounds = Rect()
        root.getBoundsInScreen(screenBounds)
        builder.append("bounds=").append(screenBounds.toShortString()).append('\n')

        dumpNode(root, 0, builder, maxDepth = 12)

        // Split big dumps into chunks so logcat doesn't truncate
        val dump = builder.toString()
        val chunkSize = 3500
        var index = 0
        while (index < dump.length) {
            val end = minOf(index + chunkSize, dump.length)
            Log.d(TAG, dump.substring(index, end))
            index = end
        }
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: StringBuilder,
        maxDepth: Int
    ) {
        if (node == null || depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString()?.replace('\n', ' ')?.take(60)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val clickable = if (node.isClickable) " [C]" else ""
        val scrollable = if (node.isScrollable) " [S]" else ""
        val focusable = if (node.isFocusable) " [F]" else ""

        out.append(indent)
            .append(cls)
            .append(" id=").append(viewId)
            .append(clickable).append(scrollable).append(focusable)
            .append(" bounds=").append(bounds.toShortString())
            .append(if (text.isNullOrEmpty()) "" else " text=\"$text\"")
            .append('\n')

        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), depth + 1, out, maxDepth)
        }
    }

    // =====================================================================
    // HANDS — upgraded by the constitution (2026-08-29).
    // Every action returns success/failure so the caller can log honestly.
    // =====================================================================

    /**
     * Tap raw screen coordinates. Uses a gesture — truthful even on nodes
     * that lie about being clickable.
     */
    fun tapAt(x: Float, y: Float, onResult: (Boolean) -> Unit = {}): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            gestureCallback(onResult, "tap($x,$y)"),
            Handler(Looper.getMainLooper())
        ).also { dispatched ->
            if (!dispatched) onResult(false)
        }
    }

    /**
     * Swipe from one point to another, e.g. scroll a feed.
     */
    fun swipe(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 300L,
        onResult: (Boolean) -> Unit = {}
    ): Boolean {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            gestureCallback(onResult, "swipe($fromX,$fromY → $toX,$toY)"),
            Handler(Looper.getMainLooper())
        ).also { dispatched ->
            if (!dispatched) onResult(false)
        }
    }

    /**
     * Click the first visible node whose text or content-description
     * contains [text] (case-insensitive). Walks up to a clickable ancestor
     * so nested labels on a button still work.
     */
    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(text)
        val hit = matches?.firstOrNull() ?: return false
        return clickNodeAndAncestors(hit)
    }

    /**
     * Click the first node matching view id suffix, e.g. "btn_search".
     */
    fun clickViewId(idSuffix: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // findAccessibilityNodeInfosByViewId() requires the fully-qualified
        // name; walk the tree so `"btn_search"` matches `com.app:id/btn_search`.
        val hit = findByViewIdSuffix(root, idSuffix) ?: return false
        return clickNodeAndAncestors(hit)
    }

    /**
     * Set text into the currently focused editable node — safer than
     * synthetic keystrokes for Compose and web views.
     */
    fun typeIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "typeIntoFocused \"${text.take(30)}\" → $ok")
        return ok
    }

    /** Scroll the first scrollable container forward/down once. */
    fun scrollForward(): Boolean = firstScrollable()?.let {
        it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            .also { ok -> Log.i(TAG, "scrollForward → $ok") }
    } ?: false

    /** Scroll the first scrollable container backward/up once. */
    fun scrollBackward(): Boolean = firstScrollable()?.let {
        it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            .also { ok -> Log.i(TAG, "scrollBackward → $ok") }
    } ?: false

    /** System-level back button. */
    fun pressBack(): Boolean =
        performGlobalAction(GLOBAL_ACTION_BACK).also {
            Log.i(TAG, "back → $it")
        }

    /** System-level home. */
    fun pressHome(): Boolean =
        performGlobalAction(GLOBAL_ACTION_HOME).also {
            Log.i(TAG, "home → $it")
        }

    /** System-level recents overview. */
    fun openRecents(): Boolean =
        performGlobalAction(GLOBAL_ACTION_RECENTS).also {
            Log.i(TAG, "recents → $it")
        }

    /** Pull down the notification shade. */
    fun openNotifications(): Boolean =
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS).also {
            Log.i(TAG, "notifications → $it")
        }

    /**
     * Flattened snapshot of the current UI, one line per node,
     * for Sunshine's toolkit consumption — eyes she can actually use.
     */
    fun describeScreen(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val lines = mutableListOf<String>()
        collectNodeDescriptions(root, 0, lines)
        return lines
    }

    // ------------------------------------------------------------------

    private fun gestureCallback(
        onResult: (Boolean) -> Unit,
        label: String
    ) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.i(TAG, "gesture $label completed")
            onResult(true)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.w(TAG, "gesture $label cancelled")
            onResult(false)
        }
    }

    private fun clickNodeAndAncestors(node: AccessibilityNodeInfo?): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "clicked ${current.className} id=${current.viewIdResourceName}")
                return true
            }
            current = current.parent
        }
        Log.w(TAG, "no clickable ancestor found")
        return false
    }

    private fun findByViewIdSuffix(
        node: AccessibilityNodeInfo?,
        suffix: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName?.endsWith(suffix) == true) return node
        for (i in 0 until node.childCount) {
            findByViewIdSuffix(node.getChild(i), suffix)?.let { return it }
        }
        return null
    }

    private fun firstScrollable(
        node: AccessibilityNodeInfo? = rootInActiveWindow
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            firstScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun collectNodeDescriptions(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<String>
    ) {
        if (node == null || depth > 12) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString()?.replace('\n', ' ')?.take(60)
        val desc = node.contentDescription?.toString()?.take(60)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
        out.add(
            "${"  ".repeat(depth)}$cls id=$id" +
                (if (node.isClickable) " [C]" else "") +
                (if (node.isScrollable) " [S]" else "") +
                " b=${bounds.toShortString()}" +
                (text?.let { " t=\"$it\"" } ?: "") +
                (desc?.let { " d=\"$it\"" } ?: "")
        )
        for (i in 0 until node.childCount) {
            collectNodeDescriptions(node.getChild(i), depth + 1, out)
        }
    }
}
