package com.dan.readaloud

import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

/** Text dump of what the reading app exposes, for tuning the extractor. */
object Diagnostics {
    private const val MAX_NODES = 4000
    private const val MAX_CHAR_PROBES = 80

    fun tree(root: AccessibilityNodeInfo, band: Rect, sb: StringBuilder) {
        val budget = intArrayOf(MAX_NODES, MAX_CHAR_PROBES)
        walk(root, band, sb, 0, budget)
        if (budget[0] <= 0) sb.appendLine("… node limit reached")
    }

    private fun walk(n: AccessibilityNodeInfo, band: Rect, sb: StringBuilder, depth: Int, budget: IntArray) {
        if (budget[0]-- <= 0) return
        val r = Rect()
        n.getBoundsInScreen(r)
        val t = n.text
        sb.append("  ".repeat(min(depth, 40)))
        sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { sb.append(" #").append(it.substringAfterLast('/')) }
        sb.append(' ').append(r.toShortString())
        if (!n.isVisibleToUser) sb.append(" INVIS")
        if (!Rect.intersects(r, band)) sb.append(" OFFBAND")
        if (n.childCount > 0) sb.append(" kids=").append(n.childCount)
        if (n.isScrollable) sb.append(" SCROLLABLE")
        n.contentDescription?.let { if (it.isNotBlank()) sb.append(" desc=\"").append(snip(it)).append('"') }
        if (!t.isNullOrBlank()) {
            sb.append(" len=").append(t.length).append(" text=\"").append(snip(t)).append('"')
            val ex = n.availableExtraData
            if (ex.isNotEmpty()) sb.append(" extra=").append(ex.joinToString(",") { it.substringAfterLast('.') })
            sb.appendLine()
            if (Rect.intersects(r, band) && budget[1]-- > 0) charInfo(n, t, band, sb, depth + 1)
        } else {
            sb.appendLine()
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, band, sb, depth + 1, budget)
        }
    }

    private fun charInfo(n: AccessibilityNodeInfo, t: CharSequence, band: Rect, sb: StringBuilder, depth: Int) {
        val ind = "  ".repeat(min(depth, 40)) + "· "
        val rs = PageExtractor.charRects(n, t.length)
        if (rs == null) {
            sb.append(ind).appendLine("chars: no positions")
            return
        }
        var nulls = 0
        var empties = 0
        val distinct = HashSet<String>()
        var first = -1
        var last = -1
        for (i in rs.indices) {
            val r = rs[i]
            if (r == null) { nulls++; continue }
            if (r.isEmpty) empties++
            if (distinct.size < 1000) distinct.add(r.toShortString())
            if (!r.isEmpty && band.contains(r.centerX().toInt(), r.centerY().toInt())) {
                if (first < 0) first = i
                last = i
            }
        }
        sb.append(ind).append("chars: n=").append(rs.size).append(" null=").append(nulls)
            .append(" empty=").append(empties).append(" distinct=").append(distinct.size)
            .append(" visible=").append(first).append("..").append(last).appendLine()
        val probes = linkedSetOf(0, 1, 2, rs.size / 2, rs.size - 2, rs.size - 1)
        if (first >= 0) { probes.add(first); probes.add(last); if (last + 1 < rs.size) probes.add(last + 1) }
        for (i in probes) {
            if (i < 0 || i >= rs.size) continue
            sb.append(ind).append("  [").append(i).append("] '").append(printable(t[i])).append("' ")
                .appendLine(rs[i]?.toShortString() ?: "null")
        }
    }

    fun snip(cs: CharSequence): String {
        val s = cs.toString().replace('\n', '⏎')
        return if (s.length <= 70) s else s.take(45) + " … " + s.takeLast(20)
    }

    private fun printable(c: Char): String = when (c) {
        '\n' -> "\\n"; ' ' -> "␣"; else -> c.toString()
    }

    fun rect(r: RectF?): String = r?.toShortString() ?: "null"
}
