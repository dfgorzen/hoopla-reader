package com.dan.readaloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** One page's text, with a box per character in page fractions (0..1, top-left origin). */
class PdfPage(val text: String, val boxes: List<RectF?>, val ocr: Boolean)

/**
 * A PDF file: text extraction with PDFBox (with character positions), falling back to
 * on-device OCR for pages that are only images. Running headers/footers (title, author,
 * page numbers repeated on every page) are removed. Every method blocks; call them from
 * one background thread.
 */
class PdfDoc(private val ctx: Context, val file: File) {
    /** Text as extracted, with line boundaries, before any filtering. */
    private class RawPage(val sb: CharSequence, val boxes: List<RectF?>, val lines: List<IntArray>, val ocr: Boolean) {
        val letters = sb.count { it.isLetter() }
    }

    private var pd: PDDocument? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var recognizer: TextRecognizer? = null
    private val cache = HashMap<Int, PdfPage>()
    private val order = ArrayDeque<Int>()
    private val rawCache = HashMap<Int, RawPage>()
    private val rawOrder = ArrayDeque<Int>()
    private val edgeCache = HashMap<Int, Set<String>>()

    var pageCount = 0
        private set

    /** Hard cut: ignore text in the top/bottom N% of each page. */
    @Volatile var topPct = 6
    @Volatile var bottomPct = 6
    /** Drop lines near the top/bottom that repeat on nearby pages, and bare page numbers. */
    @Volatile var dropRunningHeads = true
    /** Line keys the user marked to skip (see [keyOf]). */
    @Volatile var skipKeys: Set<String> = emptySet()
        private set

    /** Change the skip list; call on the document thread. */
    fun setSkipKeys(keys: Set<String>) {
        skipKeys = keys
        cache.clear()
        order.clear()
    }

    /** Opens the file. Returns an error message, or null on success. */
    fun open(): String? {
        return try {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            val d = PDDocument.load(file)
            pd = d
            pageCount = d.numberOfPages
            try {
                val f = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = f
                renderer = PdfRenderer(f)
            } catch (_: Exception) {
                // OCR just won't be available.
            }
            if (pageCount == 0) "This PDF has no pages." else null
        } catch (e: InvalidPasswordException) {
            "This PDF is password-protected."
        } catch (e: Exception) {
            "Couldn't open this PDF (${e.javaClass.simpleName})."
        }
    }

    fun close() {
        try { pd?.close() } catch (_: Exception) {}
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        pd = null; renderer = null; pfd = null; recognizer = null
    }

    fun page(i: Int): PdfPage {
        cache[i]?.let { return it }
        val raw = raw(i)
        val drop = HashSet<Int>()
        if (dropRunningHeads) drop.addAll(runningHeads(i, raw))
        if (skipKeys.isNotEmpty()) drop.addAll(userSkips(raw))
        val p = build(raw, drop)
        cache[i] = p
        order.addLast(i)
        while (order.size > CACHE_PAGES) cache.remove(order.removeFirst())
        return p
    }

    // ---- running header/footer detection ----

    /** Indices (into raw.lines) of header/footer lines on page i. */
    private fun runningHeads(i: Int, raw: RawPage): Set<Int> {
        val out = HashSet<Int>()
        var neighbours: List<Set<String>>? = null
        for ((li, line) in raw.lines.withIndex()) {
            val cy = centerY(raw, line) ?: continue
            if (cy > EDGE && cy < 1f - EDGE) continue
            val k = key(raw.sb, line)
            if (k.isEmpty()) continue
            if (isPageNumber(k)) { out.add(li); continue }
            if (k.length < 3) continue
            val nb = neighbours ?: ((i - NEIGHBOURS)..(i + NEIGHBOURS))
                .filter { it != i && it in 0 until pageCount }
                .map { edgeKeys(it) }
                .also { neighbours = it }
            if (nb.any { k in it }) out.add(li)
        }
        return out
    }

    /** Lines matching the user's skip list: exact anywhere, or containing it near the edges. */
    private fun userSkips(raw: RawPage): Set<Int> {
        val keys = skipKeys
        val out = HashSet<Int>()
        for ((li, line) in raw.lines.withIndex()) {
            val k = key(raw.sb, line)
            if (k.isEmpty()) continue
            if (k in keys) { out.add(li); continue }
            val cy = centerY(raw, line) ?: continue
            if ((cy < EDGE || cy > 1f - EDGE) && keys.any { it.length >= 4 && k.contains(it) }) out.add(li)
        }
        return out
    }

    /** Keys of the lines near the top/bottom of page j. */
    private fun edgeKeys(j: Int): Set<String> {
        edgeCache[j]?.let { return it }
        val raw = try { raw(j) } catch (_: Exception) { null }
        val keys = HashSet<String>()
        if (raw != null) {
            for (line in raw.lines) {
                val cy = centerY(raw, line) ?: continue
                if (cy > EDGE && cy < 1f - EDGE) continue
                val k = key(raw.sb, line)
                if (k.length >= 3) keys.add(k)
            }
        }
        edgeCache[j] = keys
        return keys
    }

    private fun centerY(raw: RawPage, line: IntArray): Float? {
        var sum = 0f
        var n = 0
        for (k in line[0] until minOf(line[1], raw.boxes.size)) {
            val b = raw.boxes[k] ?: continue
            sum += b.centerY(); n++
        }
        return if (n == 0) null else sum / n
    }

    private fun key(sb: CharSequence, line: IntArray): String =
        keyOf(sb.subSequence(line[0], minOf(line[1], sb.length)))

    private fun isPageNumber(k: String): Boolean =
        PAGE_NUMBER.matches(k) || (k.length <= 6 && ROMAN.matches(k))

    // ---- extraction ----

    private fun raw(i: Int): RawPage {
        rawCache[i]?.let { return it }
        var r = try { extractText(i) } catch (_: Exception) { RawPage("", emptyList(), emptyList(), false) }
        if (r.letters < MIN_LETTERS) {
            val o = try { ocr(i) } catch (_: Exception) { null }
            if (o != null && o.letters > r.letters) r = o
        }
        rawCache[i] = r
        rawOrder.addLast(i)
        while (rawOrder.size > CACHE_PAGES) rawCache.remove(rawOrder.removeFirst())
        return r
    }

    private fun extractText(i: Int): RawPage {
        val empty = RawPage("", emptyList(), emptyList(), false)
        val doc = pd ?: return empty
        val pg = doc.getPage(i)
        val crop = pg.cropBox
        val rot = ((pg.rotation % 360) + 360) % 360
        val w = if (rot == 90 || rot == 270) crop.height else crop.width
        val h = if (rot == 90 || rot == 270) crop.width else crop.height
        if (w <= 0f || h <= 0f) return empty

        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
        val lines = ArrayList<IntArray>()
        var lineStart = 0
        fun endLine() {
            if (sb.length > lineStart) lines.add(intArrayOf(lineStart, sb.length))
        }
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                for (tp in textPositions ?: return) {
                    val u = tp.unicode ?: continue
                    val x = tp.xDirAdj
                    val y = tp.yDirAdj
                    val tw = tp.widthDirAdj
                    val th = maxOf(tp.heightDir, 1f)
                    val box = RectF(x / w, (y - th * 1.25f) / h, (x + tw) / w, (y + th * 0.3f) / h)
                    for (ch in u) { sb.append(ch); boxes.add(box) }
                }
            }

            override fun writeString(text: String?) {
                for (ch in text ?: return) { sb.append(ch); boxes.add(null) }
            }

            override fun writeWordSeparator() {
                sb.append(' '); boxes.add(null)
            }

            override fun writeLineSeparator() {
                val n = sb.length
                // Join words hyphenated across a line break ("exam-" + "ple").
                if (n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter()) {
                    sb.setLength(n - 1); boxes.removeAt(boxes.size - 1)
                    endLine()
                    lineStart = sb.length
                    return
                }
                endLine()
                sb.append(' '); boxes.add(null)
                lineStart = sb.length
            }

            override fun writeParagraphEnd() {
                endLine()
                sb.append('\n'); boxes.add(null)
                lineStart = sb.length
            }
        }
        stripper.setSortByPosition(true)
        stripper.setStartPage(i + 1)
        stripper.setEndPage(i + 1)
        stripper.getText(doc)
        endLine()
        return RawPage(sb, boxes, lines, false)
    }

    private fun ocr(i: Int): RawPage? {
        val r = renderer ?: return null
        val bmp: Bitmap
        val page = r.openPage(i)
        try {
            val scale = OCR_PX / maxOf(page.width, page.height).toFloat()
            bmp = Bitmap.createBitmap(
                maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()),
                Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } finally {
            page.close()
        }
        val rec = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        val result = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        bmp.recycle()

        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
        val lines = ArrayList<IntArray>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val start = sb.length
                for (el in line.elements) {
                    val t = el.text
                    val bb = el.boundingBox
                    for (k in t.indices) {
                        sb.append(t[k])
                        boxes.add(
                            if (bb == null) null else {
                                val cw = bb.width().toFloat() / t.length
                                RectF((bb.left + k * cw) / bw, bb.top / bh, (bb.left + (k + 1) * cw) / bw, bb.bottom / bh)
                            }
                        )
                    }
                    sb.append(' '); boxes.add(null)
                }
                if (sb.length > start) lines.add(intArrayOf(start, sb.length))
            }
            sb.append('\n'); boxes.add(null)
        }
        return RawPage(sb, boxes, lines, true)
    }

    /** Drops header/footer text and normalises spacing into a PageBuffer. */
    private fun build(raw: RawPage, dropLines: Set<Int>): PdfPage {
        val sb = raw.sb
        val boxes = raw.boxes
        val dropped = BooleanArray(sb.length)
        for (li in dropLines) {
            val l = raw.lines.getOrNull(li) ?: continue
            for (k in l[0] until minOf(l[1], sb.length)) dropped[k] = true
        }
        val top = topPct / 100f
        val bottom = 1f - bottomPct / 100f
        val buf = PageBuffer()
        val run = StringBuilder()
        val runBoxes = ArrayList<RectF?>()
        fun flush() {
            if (run.isNotEmpty()) buf.append(run, runBoxes.toTypedArray(), 0, run.length)
            run.setLength(0); runBoxes.clear()
        }
        for (k in 0 until minOf(sb.length, boxes.size)) {
            val c = sb[k]
            val b = boxes[k]
            if (dropped[k]) continue
            if (c == '\n') { flush(); buf.blockBreak(); continue }
            if (b != null && (b.centerY() < top || b.centerY() > bottom)) continue
            run.append(c); runBoxes.add(b)
        }
        flush()
        return PdfPage(buf.text(), ArrayList(buf.rects), raw.ocr)
    }

    companion object {
        /** Lowercase letters with every run of digits as '#': "Page 12 · Smith" -> "page#smith". */
        fun keyOf(s: CharSequence): String {
            val out = StringBuilder()
            for (c in s) {
                when {
                    c.isDigit() -> if (out.isEmpty() || out.last() != '#') out.append('#')
                    c.isLetter() -> out.append(c.lowercaseChar())
                }
            }
            return out.toString()
        }

        private const val MIN_LETTERS = 20
        private const val CACHE_PAGES = 16
        private const val OCR_PX = 2200f
        /** Header/footer zone: top and bottom 12% of the page. */
        private const val EDGE = 0.12f
        /** Compare with this many pages on each side (2 covers alternating title/author heads). */
        private const val NEIGHBOURS = 2
        private val PAGE_NUMBER = Regex("^(page|p)?#((of)?#)?$")
        private val ROMAN = Regex("^(?=.)c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")
    }
}
