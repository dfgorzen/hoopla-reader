package com.dan.readaloud

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls the visible page text out of the reading app's accessibility tree,
 * in document order, skipping buttons/images and the header/footer bands.
 */
object PageExtractor {
    private val skipClasses = listOf("Button", "EditText", "Image", "SeekBar", "ProgressBar", "Switch", "CheckBox")

    fun extract(root: AccessibilityNodeInfo, window: Rect, topPct: Int, bottomPct: Int): String {
        val h = window.height()
        val band = Rect(
            window.left,
            window.top + h * topPct / 100,
            window.right,
            window.bottom - h * bottomPct / 100
        )
        val out = ArrayList<String>()
        walk(root, out, band, 0)
        return TextUtil.clean(out.joinToString(" "))
    }

    /** Returns true if this subtree contributed text. Prefers children's text over a parent's. */
    private fun walk(n: AccessibilityNodeInfo, out: MutableList<String>, band: Rect, depth: Int): Boolean {
        if (depth > 120 || !n.isVisibleToUser) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.bottom <= band.top || r.top >= band.bottom || r.right <= band.left || r.left >= band.right) return false
        val cls = n.className?.toString().orEmpty()
        if (skipClasses.any { cls.contains(it) }) return false

        var added = false
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (walk(c, out, band, depth + 1)) added = true
        }
        if (added) return true

        if (!band.contains(r.centerX(), r.centerY())) return false
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return false
        out.add(t)
        return true
    }
}
