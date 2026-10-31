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
 * on-device OCR for pages that are only images. Every method blocks; call them from one
 * background thread.
 */
class PdfDoc(private val ctx: Context, val file: File) {
    private var pd: PDDocument? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var recognizer: TextRecognizer? = null
    private val cache = HashMap<Int, PdfPage>()
    private val order = ArrayDeque<Int>()

    var pageCount = 0
        private set

    /** Ignore text in the top/bottom of each page (running headers, page numbers). */
    @Volatile var topPct = 6
    @Volatile var bottomPct = 6

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
        var p = try { extractText(i) } catch (_: Exception) { PdfPage("", emptyList(), false) }
        if (letters(p.text) < MIN_LETTERS) {
            val o = try { ocr(i) } catch (_: Exception) { null }
            if (o != null && letters(o.text) > letters(p.text)) p = o
        }
        cache[i] = p
        order.addLast(i)
        while (order.size > CACHE_PAGES) cache.remove(order.removeFirst())
        return p
    }

    private fun letters(s: String) = s.count { it.isLetter() }

    private fun extractText(i: Int): PdfPage {
        val doc = pd ?: return PdfPage("", emptyList(), false)
        val pg = doc.getPage(i)
        val crop = pg.cropBox
        val rot = ((pg.rotation % 360) + 360) % 360
        val w = if (rot == 90 || rot == 270) crop.height else crop.width
        val h = if (rot == 90 || rot == 270) crop.width else crop.height
        if (w <= 0f || h <= 0f) return PdfPage("", emptyList(), false)

        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
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
                // Join words hyphenated across a line break ("exam-" + "ple").
                val n = sb.length
                if (n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter()) {
                    sb.setLength(n - 1); boxes.removeAt(boxes.size - 1)
                    return
                }
                sb.append(' '); boxes.add(null)
            }

            override fun writeParagraphEnd() {
                sb.append('\n'); boxes.add(null)
            }
        }
        stripper.setSortByPosition(true)
        stripper.setStartPage(i + 1)
        stripper.setEndPage(i + 1)
        stripper.getText(doc)
        return build(sb, boxes, false)
    }

    private fun ocr(i: Int): PdfPage? {
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
        for (block in result.textBlocks) {
            for (line in block.lines) {
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
            }
            sb.append('\n'); boxes.add(null)
        }
        return build(sb, boxes, true)
    }

    /** Drops header/footer text and normalises spacing into a PageBuffer. */
    private fun build(sb: CharSequence, boxes: List<RectF?>, ocr: Boolean): PdfPage {
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
            if (c == '\n') { flush(); buf.blockBreak(); continue }
            if (b != null && (b.centerY() < top || b.centerY() > bottom)) continue
            run.append(c); runBoxes.add(b)
        }
        flush()
        return PdfPage(buf.text(), ArrayList(buf.rects), ocr)
    }

    companion object {
        private const val MIN_LETTERS = 20
        private const val CACHE_PAGES = 12
        private const val OCR_PX = 2200f
    }
}
