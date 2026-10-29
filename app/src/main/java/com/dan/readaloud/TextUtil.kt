package com.dan.readaloud

import android.graphics.RectF
import kotlin.math.abs

/** A sentence (or piece of a long one) inside a page buffer: [start, end). */
data class Seg(val start: Int, val end: Int, val text: String)

/** Page text with one screen rectangle per character (null = unknown / not on this page). */
class PageBuffer {
    val sb = StringBuilder()
    val rects = ArrayList<RectF?>()

    val length: Int get() = sb.length

    fun text(): String = sb.toString()

    private fun put(c: Char, r: RectF?) {
        if (c == ' ' && (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n')) return
        sb.append(c)
        rects.add(r)
    }

    fun append(t: CharSequence, rs: Array<RectF?>?, from: Int, to: Int) {
        for (i in from until to) {
            val c = t[i]
            if (c == '­' || c == '​' || c == '﻿') continue
            if (c.isWhitespace()) put(' ', null) else put(c, rs?.getOrNull(i))
        }
    }

    fun appendAll(o: PageBuffer) {
        for (i in 0 until o.length) put(o.sb[i], o.rects[i])
    }

    fun space() = put(' ', null)

    fun blockBreak() {
        while (sb.isNotEmpty() && sb.last() == ' ') {
            sb.setLength(sb.length - 1)
            rects.removeAt(rects.size - 1)
        }
        if (sb.isNotEmpty() && sb.last() != '\n') {
            sb.append('\n'); rects.add(null)
        }
    }
}

/** Recently spoken sentences, so repeated text can be skipped before it is read. */
class History(private val cap: Int = 1500) {
    private val order = ArrayDeque<String>()
    private val counts = HashMap<String, Int>()

    fun add(k: String) {
        if (k.isEmpty()) return
        order.addLast(k)
        counts[k] = (counts[k] ?: 0) + 1
        while (order.size > cap) {
            val old = order.removeFirst()
            val c = (counts[old] ?: 1) - 1
            if (c <= 0) counts.remove(old) else counts[old] = c
        }
    }

    operator fun contains(k: String): Boolean = counts.containsKey(k)

    fun clear() {
        order.clear(); counts.clear()
    }
}

object TextUtil {
    private const val CLOSERS = "\"'”’»)]"
    private const val TERMS = ".!?…"
    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "vs", "etc", "mt", "lt",
        "col", "gen", "capt", "sgt", "rev", "fig", "vol", "ch", "approx"
    )

    /** True if the text before endExclusive ends a sentence (ignoring closing quotes/brackets). */
    fun endsSentence(s: CharSequence, endExclusive: Int): Boolean {
        var i = endExclusive - 1
        while (i >= 0 && (s[i] == ' ' || s[i] == '\n')) i--
        while (i >= 0 && CLOSERS.indexOf(s[i]) >= 0) i--
        return i >= 0 && TERMS.indexOf(s[i]) >= 0
    }

    private fun isAbbrev(s: String, dot: Int): Boolean {
        var k = dot - 1
        while (k >= 0 && s[k].isLetter()) k--
        val w = s.substring(k + 1, dot)
        if (w.isEmpty()) return false
        if (w.length == 1 && w[0].isUpperCase()) return true   // initials: "J. R. R."
        return w.lowercase() in ABBREV
    }

    /**
     * Splits page text into sentences. A '\n' marks a block (paragraph/heading) boundary
     * and also ends a sentence unless the next block clearly continues it (starts lowercase).
     */
    fun segments(s: String, maxLen: Int = 300): List<Seg> {
        val raw = ArrayList<IntArray>()
        val n = s.length
        var segStart = 0
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c == '\n') {
                var j = i + 1
                while (j < n && (s[j] == ' ' || s[j] == '\n')) j++
                val next = if (j < n) s[j] else 'A'
                if (endsSentence(s, i) || !next.isLowerCase()) {
                    raw.add(intArrayOf(segStart, i))
                    segStart = i + 1
                }
                i++
                continue
            }
            if (TERMS.indexOf(c) >= 0) {
                var j = i + 1
                while (j < n && TERMS.indexOf(s[j]) >= 0) j++
                while (j < n && CLOSERS.indexOf(s[j]) >= 0) j++
                if (j >= n || s[j] == ' ' || s[j] == '\n') {
                    if (!(c == '.' && j == i + 1 && isAbbrev(s, i))) {
                        raw.add(intArrayOf(segStart, j))
                        segStart = j
                    }
                }
                i = j
                continue
            }
            i++
        }
        if (segStart < n) raw.add(intArrayOf(segStart, n))

        val out = ArrayList<Seg>()
        for (r in raw) {
            var a = r[0]
            val b = r[1]
            while (b - a > maxLen) {
                var cut = -1
                for (k in a + maxLen downTo a + maxLen / 3) {
                    val ch = s[k]
                    if (ch == ',' || ch == ';' || ch == ':' || ch == '—') {
                        cut = k + 1; break
                    }
                }
                if (cut < 0) {
                    cut = s.lastIndexOf(' ', a + maxLen)
                    if (cut <= a) cut = a + maxLen
                }
                addSeg(out, s, a, cut)
                a = cut
            }
            addSeg(out, s, a, b)
        }
        return out
    }

    private fun addSeg(out: MutableList<Seg>, s: String, start: Int, end: Int) {
        var a = start
        var b = end
        while (a < b && (s[a] == ' ' || s[a] == '\n')) a++
        while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\n')) b--
        if (b <= a) return
        val t = s.substring(a, b).replace('\n', ' ')
        if (t.any { it.isLetterOrDigit() }) out.add(Seg(a, b, t))
    }

    /** Comparison key: letters and digits only, lowercase. */
    fun key(t: String): String {
        val sb = StringBuilder(t.length)
        for (c in t) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    /**
     * Which sentences were already spoken recently. Long matches are skipped outright;
     * short ones ("No.", "Yes.") only when they sit next to a skipped run, so
     * legitimately repeated short lines are still read.
     */
    fun repeats(keys: List<String>, h: History): BooleanArray {
        val n = keys.size
        val earlier = HashSet<String>()
        val seen = BooleanArray(n)
        for (i in 0 until n) {
            val k = keys[i]
            seen[i] = k.length >= 2 && (k in h || k in earlier)   // spoken before, or repeated on this page
            earlier.add(k)
        }
        val skip = BooleanArray(n) { seen[it] && keys[it].length >= 24 }
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until n) {
                if (seen[i] && !skip[i] && ((i > 0 && skip[i - 1]) || (i < n - 1 && skip[i + 1]))) {
                    skip[i] = true; changed = true
                }
            }
        }
        return skip
    }

    /** Merges per-character rectangles in [start, end) into one rectangle per text line. */
    fun lineRects(rects: List<RectF?>, start: Int, end: Int): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in start until minOf(end, rects.size)) {
            val r = rects[i] ?: continue
            if (r.isEmpty) continue
            val c = cur
            if (c != null && abs(r.centerY() - c.centerY()) < c.height() * 0.5f && r.left >= c.left - 2f) {
                c.union(r)
            } else {
                val nr = RectF(r)
                out.add(nr)
                cur = nr
            }
        }
        return out
    }
}
