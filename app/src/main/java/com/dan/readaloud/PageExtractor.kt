package com.dan.readaloud

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls the text that is actually on screen out of the reading app's accessibility tree.
 *
 * Ebook readers lay a chapter out as columns, so one paragraph node can span the current
 * page and the next. Asking for per-character screen positions lets us keep only the
 * characters on this page, and gives the rectangles used for highlighting.
 * Falls back to whole nodes when the app doesn't report character positions.
 */
object PageExtractor {
    class Result(
        val added: Int,
        /** First block starts partway through its node (continues from the previous page). null = unknown. */
        val firstTruncated: Boolean?,
        /** Last block is cut off at the page end. null = unknown. */
        val lastTruncated: Boolean?,
        val hasCharPositions: Boolean,
    )

    private class Leaf(val node: AccessibilityNodeInfo, val text: CharSequence, val bounds: Rect)

    private const val KEY = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
    private const val CHUNK = 1000
    private val skipClasses = listOf("Button", "EditText", "Image", "SeekBar", "ProgressBar", "Switch", "CheckBox")

    fun extract(root: AccessibilityNodeInfo, window: Rect, topPct: Int, bottomPct: Int, into: PageBuffer): Result {
        val h = window.height()
        val band = Rect(
            window.left,
            window.top + h * topPct / 100,
            window.right,
            window.bottom - h * bottomPct / 100
        )
        val leaves = ArrayList<Leaf>()
        collect(root, leaves, band, 0)

        val startLen = into.length
        var firstTrunc: Boolean? = null
        var lastTrunc: Boolean? = null
        var prevLast: RectF? = null
        var any = false
        var anyChars = false

        for (leaf in leaves) {
            val t = leaf.text
            val rs = charRects(leaf.node, t.length)
            var from = 0
            var to = t.length
            var startTrunc: Boolean? = null
            var endTrunc: Boolean? = null
            var firstRect: RectF? = null
            var lastRect: RectF? = null

            if (rs != null) {
                var first = -1
                var last = -1
                for (i in rs.indices) {
                    val r = rs[i] ?: continue
                    if (r.isEmpty) continue
                    if (band.contains(r.centerX().toInt(), r.centerY().toInt())) {
                        if (first < 0) first = i
                        last = i
                    }
                }
                if (first < 0) continue   // node is entirely on another page
                from = first
                to = last + 1
                var lead = 0
                while (lead < t.length && t[lead].isWhitespace()) lead++
                var tail = t.length - 1
                while (tail >= 0 && t[tail].isWhitespace()) tail--
                startTrunc = first > lead
                endTrunc = last < tail
                firstRect = rs[first]
                lastRect = rs[last]
                anyChars = true
            } else {
                if (!band.contains(leaf.bounds.centerX(), leaf.bounds.centerY())) continue
            }

            if (any) {
                // Same line as the previous piece = inline element (italics, link): join directly.
                val pl = prevLast
                val inline = pl != null && firstRect != null &&
                    firstRect.centerY() > pl.top && firstRect.centerY() < pl.bottom
                if (!inline) into.blockBreak()
            } else {
                if (into.length > 0) into.space()
                firstTrunc = startTrunc
            }
            into.append(t, rs, from, to)
            any = true
            prevLast = lastRect
            lastTrunc = endTrunc
        }
        return Result(into.length - startLen, firstTrunc, lastTrunc, anyChars)
    }

    /** Depth-first, document order. Keeps the deepest nodes that carry text. */
    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Leaf>, band: Rect, depth: Int): Boolean {
        if (depth > 150 || !n.isVisibleToUser) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        if (!Rect.intersects(r, band)) return false
        val cls = n.className?.toString().orEmpty()
        if (skipClasses.any { cls.contains(it) }) return false

        var added = false
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (collect(c, out, band, depth + 1)) added = true
        }
        if (added) return true

        val t = n.text ?: return false
        if (t.isBlank()) return false
        out.add(Leaf(n, t, r))
        return true
    }

    fun charRects(n: AccessibilityNodeInfo, len: Int): Array<RectF?>? {
        if (len == 0) return null
        val out = arrayOfNulls<RectF>(len)
        var any = false
        var start = 0
        try {
            while (start < len) {
                val l = minOf(CHUNK, len - start)
                val args = Bundle()
                args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, l)
                if (!n.refreshWithExtraData(KEY, args)) break
                @Suppress("DEPRECATION")
                val arr = n.extras.getParcelableArray(KEY) ?: break
                for (j in 0 until minOf(arr.size, l)) {
                    val r = arr[j] as? RectF ?: continue
                    out[start + j] = r
                    any = true
                }
                start += l
            }
        } catch (_: Exception) {
        }
        return if (any) out else null
    }
}
