package com.dan.readaloud

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls the text of the current page out of the reading app's accessibility tree.
 *
 * What hoopla (a WebView) exposes, from diagnostic dumps:
 *  - The whole chapter is in the tree, in reading order, on every page.
 *  - Pages before/after the visible one are squashed into 0–2 px strips at the
 *    left/right screen edge.
 *  - Line-wrap spaces report the whole paragraph's box.
 *  - The part of a paragraph that continues onto the next page is mis-positioned:
 *    drawn over the current page, and on the next page reported as the previous page.
 *  - Right after a page turn, glyph positions aren't loaded yet (every character
 *    reports the paragraph box) for a fraction of a second.
 *
 * So positions can't be trusted to say where a page STARTS. Instead the reader keeps a
 * bookmark (paragraph + character) of where the previous page stopped, and reads on from
 * there. Positions are used only to find where the page ENDS, and for highlighting.
 * Without a bookmark (first page, or after jumping around) the start is the first
 * character actually positioned on screen.
 */
object PageExtractor {
    /** A position in the document: leaf (text node) index + character, with the leaf's text to re-identify it. */
    data class Cursor(val leaf: Int, val char: Int, val leafText: String)

    class Result(
        val added: Int,
        val hasCharPositions: Boolean,
        val usedWebView: Boolean,
        /** Some paragraphs' glyph positions weren't loaded yet; reading again shortly will be more accurate. */
        val pending: Boolean,
        /** Started from the bookmark rather than from the screen. */
        val fromCursor: Boolean,
        /** Stopped because the page ended (more text follows). */
        val pageEnded: Boolean,
        /** Where the next page starts; null = end of the text. */
        val next: Cursor?,
        /** Identity of the first character drawn on screen: changes when the page turns. */
        val pageKey: String,
        val leafTexts: List<String>,
        val note: String,
    ) {
        fun cursorFor(src: Long): Cursor? {
            if (src < 0) return null
            val li = (src ushr 32).toInt()
            val ci = (src and 0xffffffffL).toInt()
            return if (li in leafTexts.indices) Cursor(li, ci, leafTexts[li]) else null
        }
    }

    private class Leaf(val node: AccessibilityNodeInfo, val text: CharSequence, val bounds: Rect)

    private const val KEY = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
    private const val CHUNK = 1000
    private const val MIN_W = 3f
    private const val BACK_WINDOW = 2500   // bookmark may be this many chars before the screen start
    private const val AHEAD_WINDOW = 1500  // ...or this many after it
    private val skipClasses = listOf("Button", "EditText", "Image", "SeekBar", "ProgressBar", "Switch", "CheckBox")

    fun extract(
        root: AccessibilityNodeInfo, window: Rect, topPct: Int, bottomPct: Int,
        cursor: Cursor?, into: PageBuffer, pageChars: Int = 1500,
    ): Result {
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
        val texts = leaves.map { it.text.toString() }
        val n = leaves.size

        var pending = false
        var anyChars = false
        val cache = arrayOfNulls<Array<RectF?>>(n)
        val queried = BooleanArray(n)
        fun rectsFor(i: Int): Array<RectF?>? {
            if (queried[i]) return cache[i]
            queried[i] = true
            val l = leaves[i]
            if (l.bounds.width() < 8) return null           // squashed: another page
            val raw = charRects(l.node, l.text.length)
            if (raw == null) {
                // A node that offers character positions but returned none: still loading.
                if (l.node.availableExtraData.contains(KEY) && Rect.intersects(l.bounds, band)) pending = true
                return null
            }
            anyChars = true
            if (notLoaded(raw)) {
                pending = true
                return null
            }
            val s = sanitize(raw)
            cache[i] = s
            return s
        }
        fun rightClamped(l: Leaf) = l.bounds.width() < 8 && l.bounds.left >= band.right - 4
        fun onScreen(r: RectF) = band.contains(r.centerX().toInt(), r.centerY().toInt())

        // 1. Where the screen says the page starts.
        var gLeaf = -1
        var gChar = 0
        for (i in 0 until n) {
            val l = leaves[i]
            if (rightClamped(l)) break
            val rs = rectsFor(i)
            if (rs != null) {
                val j = rs.indices.firstOrNull { k -> rs[k]?.let { onScreen(it) } == true }
                if (j != null) { gLeaf = i; gChar = j; break }
            } else if (l.bounds.width() >= 8 && band.contains(l.bounds.centerX(), l.bounds.centerY())) {
                gLeaf = i; gChar = 0; break
            }
        }
        var pageKey = if (gLeaf >= 0) "$gLeaf:$gChar:${texts[gLeaf].hashCode()}" else ""

        // 2. Use the bookmark if it is close to that.
        val prefix = IntArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + texts[i].length
        var sLeaf = gLeaf
        var sChar = gChar
        var fromCursor = false
        var note = ""
        var blindParagraph = false
        if (cursor != null && gLeaf < 0) {
            // Nothing positioned on screen: the page is the middle of one long paragraph
            // that hoopla files under an earlier page. Read on from the bookmark to the end
            // of that paragraph.
            val idx = if (cursor.leaf in 0 until n && texts[cursor.leaf] == cursor.leafText) cursor.leaf
            else texts.indexOf(cursor.leafText)
            if (idx >= 0) {
                sLeaf = idx; sChar = cursor.char; fromCursor = true; blindParagraph = true
                pageKey = "blind:$idx:${cursor.char}"
                note = "no positioned text; reading bookmark paragraph"
            }
        }
        if (cursor != null && gLeaf >= 0) {
            val idx = if (cursor.leaf in 0 until n && texts[cursor.leaf] == cursor.leafText) cursor.leaf
            else texts.indexOf(cursor.leafText)
            if (idx >= 0) {
                val posC = prefix[idx] + cursor.char.coerceIn(0, texts[idx].length)
                val posG = prefix[gLeaf] + gChar
                if (posC >= posG - BACK_WINDOW && posC <= posG + AHEAD_WINDOW) {
                    sLeaf = idx; sChar = cursor.char; fromCursor = true
                    note = "bookmark ${posC - posG} chars from screen start"
                } else note = "bookmark too far (${posC - posG}); using screen start"
            } else note = "bookmark paragraph not found; using screen start"
        }
        if (sLeaf < 0) {
            return Result(0, anyChars, web != null, pending, false, false, null, pageKey, texts, "no text on screen")
        }

        // 3. Read on until the page ends.
        val occupied = Occupancy()
        val startLen = into.length
        var seenOnPage = false
        var lastTop = Float.NaN
        var lastH = 0f
        var started = false
        var prevLast: RectF? = null
        var prevEndsSpace = false
        var ended = false
        var next: Cursor? = null
        var lastRect: RectF? = null

        /** r comes after `last` in reading order: on a lower line, or later on the same line. */
        fun isAfter(r: RectF, last: RectF): Boolean =
            r.top >= last.top + 0.5f * last.height() ||
                (kotlin.math.abs(r.centerY() - last.centerY()) < 0.5f * last.height() && r.left >= last.left)

        /**
         * A page end at leaf [li] may be fake: hoopla mis-positions inline elements (italic
         * words) far from where they're drawn. If the next text carries on normally right
         * after what we've taken, the page hasn't really ended.
         */
        fun falseEnd(li: Int): Boolean {
            val last = lastRect ?: return false
            for (k in li + 1 until minOf(n, li + 4)) {
                val l = leaves[k]
                if (rightClamped(l)) return false
                val rs = rectsFor(k) ?: continue
                val r = rs.firstOrNull { it != null } ?: continue
                return onScreen(r) && !occupied.hits(r.centerX(), r.centerY()) && isAfter(r, last)
            }
            return false
        }

        // Bookmark is partway down this page (the top was already read): mark that text's
        // spots as taken so a misplaced continuation landing there is still caught.
        if (fromCursor && gLeaf >= 0 && (sLeaf > gLeaf || (sLeaf == gLeaf && sChar > gChar))) {
            for (li in gLeaf..sLeaf) {
                val rs = rectsFor(li) ?: continue
                val a = if (li == gLeaf) gChar else 0
                val b = if (li == sLeaf) sChar.coerceAtMost(rs.size) else rs.size
                for (i in a until b) {
                    val r = rs[i] ?: continue
                    if (!onScreen(r)) continue
                    occupied.add(r)
                    lastTop = r.top
                    lastH = r.height()
                    lastRect = r
                    seenOnPage = true
                }
            }
        }

        for (li in sLeaf until n) {
            val l = leaves[li]
            val t = l.text
            val from = if (li == sLeaf) sChar.coerceIn(0, t.length) else 0
            if (from >= t.length) continue
            if (seenOnPage && rightClamped(l)) {
                ended = true; next = Cursor(li, 0, texts[li]); break
            }
            val rs = rectsFor(li)
            val keep = arrayOfNulls<RectF>(t.length)
            var cut = t.length
            if (blindParagraph && t.length - from > pageChars) {
                // About one page's worth, ending at a word break.
                var c = from + pageChars
                while (c > from + pageChars / 2 && !t[c - 1].isWhitespace()) c--
                cut = c
            }
            var firstR: RectF? = null
            var lastR: RectF? = null

            if (rs == null) {
                if (l.bounds.width() >= 8) {
                    // On screen but no usable positions: whole paragraph, by its box.
                    if (seenOnPage && !lastTop.isNaN() && l.bounds.top < lastTop - 0.25f * lastH) {
                        ended = true; next = Cursor(li, from, texts[li]); break
                    }
                    if (band.contains(l.bounds.centerX(), l.bounds.centerY())) {
                        seenOnPage = true
                        lastTop = l.bounds.top.toFloat()
                        lastH = 0f
                    }
                }
                // Squashed paragraphs before the screen text are read too: they are
                // the part between the bookmark and this page that hoopla misplaces.
            } else {
                for (i in from until t.length) {
                    val r = rs[i] ?: continue
                    // Off the visible area, or drawn on top of text already taken (hoopla puts
                    // the next page's continuation of a paragraph over the current page).
                    val on = onScreen(r) && !occupied.hits(r.centerX(), r.centerY())
                    if (on) {
                        keep[i] = r
                        occupied.add(r)
                        lastTop = r.top
                        lastH = r.height()
                        lastRect = r
                        seenOnPage = true
                        if (firstR == null) firstR = r
                        lastR = r
                    } else if (seenOnPage) {
                        if (falseEnd(li)) {
                            // Misplaced inline element: keep the rest of it as text, no highlight.
                            for (k in i until t.length) keep[k] = null
                            break
                        }
                        cut = i   // ran off this page
                        break
                    }
                    // else: before the page's first drawn character (bookmark region): keep the text.
                }
            }

            if (cut > from) {
                if (started) {
                    val pl = prevLast
                    val geomInline = pl != null && firstR != null &&
                        firstR.centerY() > pl.top && firstR.centerY() < pl.bottom
                    val c0 = t[from]
                    val textInline = prevEndsSpace || c0.isWhitespace() || ",.;:!?’”)]".indexOf(c0) >= 0
                    if (!geomInline && !textInline) into.blockBreak()
                } else if (into.length > 0) {
                    into.space()
                }
                into.append(t, keep, from, cut, li)
                started = true
                if (lastR != null) prevLast = lastR
                prevEndsSpace = t[cut - 1].isWhitespace()
            }
            if (cut < t.length) {
                ended = true; next = Cursor(li, cut, texts[li]); break
            }
            if (blindParagraph) {
                // Ran to the end of the paragraph.
                ended = li + 1 < n
                next = if (li + 1 < n) Cursor(li + 1, 0, texts[li + 1]) else null
                break
            }
        }
        if (!seenOnPage && fromCursor && !blindParagraph) {
            // Bookmark is past everything on screen: the page hasn't turned yet.
            into.sb.setLength(startLen); into.rects.subList(startLen, into.rects.size).clear()
            into.src.subList(startLen, into.src.size).clear()
            return Result(0, anyChars, web != null, pending, true, false, cursor, pageKey, texts, "$note; nothing on screen after bookmark")
        }
        return Result(
            into.length - startLen, anyChars, web != null, pending, fromCursor,
            ended, next, pageKey, texts, note
        )
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

    /**
     * Before its glyphs are laid out, WebView reports the paragraph's own box for every
     * character. Real glyph boxes are nearly all distinct, so many identical ones = not loaded.
     * (Squashed boxes of other pages are ignored here: they're narrower than MIN_W.)
     */
    private fun notLoaded(rs: Array<RectF?>): Boolean {
        val counts = HashMap<RectF, Int>()
        var n = 0
        var maxc = 0
        for (r in rs) {
            if (r == null || r.width() < MIN_W) continue
            n++
            val c = (counts[r] ?: 0) + 1
            counts[r] = c
            if (c > maxc) maxc = c
        }
        return n >= 8 && maxc >= maxOf(4, n / 4)
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
        // Keep squashed (0–2 px wide) nodes at the screen edges: they are the rest of the chapter.
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
