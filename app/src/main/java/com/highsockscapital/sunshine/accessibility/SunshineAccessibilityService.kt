package com.highsockscapital.sunshine.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
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
 * Current scope: EYES ONLY.
 *  - dumps the foreground window's node tree to logs
 *  - exposes the live instance via [instance] for future hands (click/scroll/type)
 *
 * The constitution clause "Sunshine's tools — act freely, but logged" applies:
 * every event batch is summarized before touching logcat.
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

    // ---- Future hands (kept deliberately private until the constitution
    //      upgrades them from eyes to limbs) ----

    /**
     * Global action helper placeholder — back/home/recents/notifications etc.
     * Not exposed yet; reserved for when Tier-1 hands come online.
     */
    @Suppress("unused")
    private fun performGlobal(action: Int): Boolean =
        performGlobalAction(action)

    /**
     * Find the first node matching a text match; not wired up yet.
     * Parked here so the diff that adds hands stays small and reviewable.
     */
    @Suppress("unused")
    private fun findByText(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val matches = root.findAccessibilityNodeInfosByText(query)
        return matches?.firstOrNull()
    }
}
