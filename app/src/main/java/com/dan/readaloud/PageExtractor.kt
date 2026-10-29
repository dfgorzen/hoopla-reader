package com.dan.readaloud

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls the text that is actually on screen out of the reading app's accessibility tree.
 *
 * What hoopla (a WebView) exposes, from a diagnostic dump:
 *  - The whole chapter is in the tree. Pages before/after the visible one are squashed
 *    into 0–2 px strips at the left/right screen edge; their characters get zero- or
 *    2-px-wide rectangles.
 *  - Spaces at line wraps report the whole paragraph's box (tall, full width).
 *  - The chapter title and page counter are native views outside the WebView.
 *
 * So: read only inside the WebView, ignore degenerate/oversized character boxes, and
 * treat the visible page as one contiguous run of the document — once text has started,
 * the first character that lands off the page (or on top of text already taken, i.e. a
 * later page drawn at the same spot) ends the page.
 */
object PageExtractor {
    class Result(
        val added: Int,
        /** First block starts partway through its node (continues from the previous page). null = unknown. */
        val firstTruncated: Boolean?,
        /** Last block is cut off at the page end. null = unknown. */
        val lastTruncated: Boolean?,
        val hasCharPositions: Boolean,
        val usedWebView: Boolean,
    )

    private class Leaf(val node: AccessibilityNodeInfo, val text: CharSequence, val bounds: Rect)

    private const val KEY = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
    private const val CHUNK = 1000
    private const val MIN_W = 3f
    private val skipClasses = listOf("Button", "EditText", "Image", "SeekBar", "ProgressBar", "Switch", "CheckBox")

    fun extract(root: AccessibilityNodeInfo, window: Rect, topPct: Int, bottomPct: Int, into: PageBuffer): Result {
        val web = findContentWebView(root)
        val band: Rect
        val start: AccessibilityNodeInfo
        if (web != null) {
            band = Rect()
            web.getBoundsInScreen(band)
            if (!band.intersect(window)) band.set(window)
            start = web
        } else {
            val h = window.height()
            band = Rect(window.left, window.top + h * topPct / 100, window.right, window.bottom - h * bottomPct / 100)
            start = root
        }

        val leaves = ArrayList<Leaf>()
        collect(start, leaves, band, 0)

        val occupied = Occupancy()
        val startLen = into.length
        var firstTrunc: Boolean? = null
        var lastTrunc: Boolean? = null
        var prevLast: RectF? = null
        var started = false
        var anyChars = false

        for (leaf in leaves) {
            val t = leaf.text
            val raw = charRects(leaf.node, t.length)
            var from: Int
            var to: Int
            var startTrunc: Boolean? = null
            var endTrunc: Boolean? = null
            var firstRect: RectF? = null
            var lastRect: RectF? = null
            var pageEnds = false
            var clean: Array<RectF?>? = null

            if (raw != null) {
                anyChars = true
                val cr = sanitize(raw)
                clean = cr
                var first = -1
                var last = -1
                var hasValid = false
                for (i in cr.indices) {
                    val r = cr[i] ?: continue
                    hasValid = true
                    val onPage = band.contains(r.centerX().toInt(), r.centerY().toInt()) &&
                        !occupied.hits(r.centerX(), r.centerY())
                    if (onPage) {
                        if (first < 0) first = i
                        last = i
                    } else if (first >= 0) {
                        pageEnds = true   // text ran off this page
                        break
                    }
                }
                if (first < 0) {
                    // Nothing of this node is on the visible page.
                    if (started && hasValid) break      // a later page: we're done
                    if (started && leaf.bounds.width() < 8) break
                    continue                             // an earlier page: keep looking
                }
                for (i in first..last) cr[i]?.let { occupied.add(it) }
                from = first
                to = last + 1
                var lead = 0
                while (lead < t.length && t[lead].isWhitespace()) lead++
                var tail = t.length - 1
                while (tail >= 0 && t[tail].isWhitespace()) tail--
                // Unknown-position characters (spaces) at either end belong with the run.
                while (from > lead && cr[from - 1] == null && !t[from - 1].isLetterOrDigit()) from--
                while (to <= tail && cr[to] == null && !t[to].isLetterOrDigit()) to++
                startTrunc = from > lead
                endTrunc = pageEnds || to <= tail
                firstRect = cr[first]
                lastRect = cr[last]
            } else {
                // No character positions: whole node if its box is really on screen.
                if (leaf.bounds.width() < 8 || !band.contains(leaf.bounds.centerX(), leaf.bounds.centerY())) {
                    if (started && leaf.bounds.width() < 8) break
                    continue
                }
                from = 0
                to = t.length
            }

            if (started) {
                // Same line as the previous piece = inline element (italics, link): join directly.
                val pl = prevLast
                val inline = pl != null && firstRect != null &&
                    firstRect.centerY() > pl.top && firstRect.centerY() < pl.bottom
                if (!inline) into.blockBreak()
            } else {
                if (into.length > 0) into.space()
                firstTrunc = startTrunc
            }
            into.append(t, clean, from, to)
            started = true
            prevLast = lastRect
            lastTrunc = endTrunc
            if (pageEnds) break
        }
        return Result(into.length - startLen, firstTrunc, lastTrunc, anyChars, web != null)
    }

    /** Largest visible WebView: the book page. Null for native readers. */
    private fun findContentWebView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            val cls = n.className?.toString().orEmpty()
            if (cls.endsWith("WebView") && n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val a = r.width().toLong() * r.height()
                if (a > bestArea) {
                    bestArea = a; best = n
                }
                return   // the page text is inside; no need to look deeper
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return if (bestArea > 0) best else null
    }

    /**
     * Drops boxes that can't be a real glyph on screen: empty, squashed to the screen edge
     * (other pages), or much taller than a text line (line-wrap spaces report the paragraph box).
     */
    private fun sanitize(rs: Array<RectF?>): Array<RectF?> {
        val heights = ArrayList<Float>()
        for (r in rs) if (r != null && r.width() >= MIN_W && r.height() > 0f) heights.add(r.height())
        val out = arrayOfNulls<RectF>(rs.size)
        if (heights.isEmpty()) return out
        heights.sort()
        val maxH = heights[heights.size / 2] * 1.6f
        for (i in rs.indices) {
            val r = rs[i] ?: continue
            if (r.width() < MIN_W || r.height() <= 0f || r.height() > maxH) continue
            out[i] = r
        }
        return out
    }

    /** Screen spots already covered by accepted text, bucketed by row. */
    private class Occupancy {
        private val rows = HashMap<Int, ArrayList<RectF>>()

        fun add(r: RectF) {
            val inset = RectF(r)
            inset.inset(r.width() * 0.2f, r.height() * 0.2f)
            val k0 = (inset.top / 32f).toInt()
            val k1 = (inset.bottom / 32f).toInt()
            for (k in k0..k1) rows.getOrPut(k) { ArrayList() }.add(inset)
        }

        fun hits(x: Float, y: Float): Boolean {
            val list = rows[(y / 32f).toInt()] ?: return false
            for (r in list) if (r.contains(x, y)) return true
            return false
        }
    }

    /** Depth-first, document order. Keeps the deepest nodes that carry text. */
    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Leaf>, band: Rect, depth: Int): Boolean {
        if (depth > 150 || !n.isVisibleToUser) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        // Keep squashed (0–2 px wide) nodes at the screen edges: they mark earlier/later pages.
        if (r.bottom < band.top || r.top > band.bottom || r.right < band.left || r.left > band.right) return false
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
