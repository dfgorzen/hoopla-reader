package com.dan.readaloud

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Shows the PDF page being read, with highlighting; tap a sentence to read from there. */
class PdfActivity : Activity(), PdfReadService.Listener {
    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var pageView: PageView
    private lateinit var pageLabel: TextView
    private lateinit var playBtn: Button

    private var service: PdfReadService? = null
    private var pendingFile: File? = null
    private var pendingTitle = ""

    private val renderExec = Executors.newSingleThreadExecutor()
    private var renderPath: String? = null
    private var renderPfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var renderToken = 0

    private var pageCount = 0
    private var shownPage = -1
    private var pageData: PdfPage? = null
    private var curStart = 0
    private var curEnd = 0
    private var wordA = -1
    private var wordB = -1

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as PdfReadService.LocalBinder).service
            service = s
            s.addListener(this@PdfActivity)
            val f = pendingFile
            if (f != null) {
                pendingFile = null
                s.openDocument(f, pendingTitle)
            } else if (s.path == null) {
                val last = prefs.lastPdfPath
                if (last != null && File(last).exists()) s.openDocument(File(last), prefs.lastPdfTitle)
                else titleView.text = "Open a PDF from the Read Aloud screen, or Share one to Read Aloud."
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        titleView = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(6))
            maxLines = 2
        }
        pageView = PageView(this).apply {
            onTap = { x, y -> tapAt(x, y) }
            onLongPress = { x, y -> markLine(x, y) }
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.DKGRAY)
            addView(pageView)
        }
        pageLabel = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }
        playBtn = button(PLAY) { service?.toggle() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(button(PREV) { service?.let { it.seek(shownPage - 1, 0) } })
            addView(playBtn)
            addView(button(NEXT) { service?.let { it.seek(shownPage + 1, 0) } })
            addView(pageLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(titleView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        }
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PdfReadService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.removeListener(this)
        service = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onDestroy() {
        renderExec.execute { closeRenderer() }
        renderExec.shutdown()
        super.onDestroy()
    }

    // ---- opening files ----

    private fun handleIntent(intent: Intent?) {
        val uri = incomingUri(intent) ?: return
        titleView.text = "Opening…"
        Thread {
            val imported = try { importPdf(uri) } catch (_: Exception) { null }
            runOnUiThread {
                if (imported == null) {
                    Toast.makeText(this, "Couldn't open that file.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val s = service
                if (s != null) s.openDocument(imported.first, imported.second)
                else { pendingFile = imported.first; pendingTitle = imported.second }
            }
        }.start()
    }

    private fun incomingUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        }
        return intent.data
    }

    /** Copies the PDF into app storage so it can be read later (and with the screen off). */
    private fun importPdf(uri: Uri): Pair<File, String> {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: throw IllegalArgumentException())
            return f to f.nameWithoutExtension
        }
        var name = "document.pdf"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").ifBlank { "document.pdf" }
        val fileName = if (safe.lowercase().endsWith(".pdf")) safe else "$safe.pdf"
        val dir = File(filesDir, "pdfs").apply { mkdirs() }
        val f = File(dir, fileName)
        if (!(f.exists() && size > 0 && f.length() == size)) {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(f).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("no stream")
        }
        return f to fileName.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    // ---- service callbacks ----

    override fun onDocument(title: String, pageCount: Int) {
        titleView.text = title
        this.pageCount = pageCount
        val p = service?.path
        if (p != null && p != renderPath) {
            renderPath = p
            renderExec.execute {
                closeRenderer()
                try {
                    val f = ParcelFileDescriptor.open(File(p), ParcelFileDescriptor.MODE_READ_ONLY)
                    renderPfd = f
                    renderer = PdfRenderer(f)
                } catch (_: Exception) {
                }
            }
            shownPage = -1
        }
    }

    override fun onPosition(page: Int, start: Int, end: Int) {
        curStart = start
        curEnd = end
        wordA = -1
        wordB = -1
        if (page != shownPage) showPage(page) else applyHighlight(true)
    }

    override fun onWord(page: Int, start: Int, end: Int) {
        if (page != shownPage) return
        wordA = start
        wordB = end
        applyHighlight(false)
    }

    override fun onState(playing: Boolean) {
        playBtn.text = if (playing) PAUSE else PLAY
    }

    override fun onPageReload(page: Int) {
        if (page != shownPage) return
        service?.pageData(page) { d ->
            if (page == shownPage) {
                pageData = d
                applyHighlight(false)
            }
        }
    }

    override fun onMessage(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---- page display ----

    private fun showPage(p: Int) {
        shownPage = p
        pageLabel.text = "Page ${p + 1} of $pageCount"
        pageData = null
        pageView.sentence = emptyList()
        pageView.word = emptyList()
        val width = maxOf(pageView.width, resources.displayMetrics.widthPixels)
        val token = ++renderToken
        renderExec.execute {
            val bmp = renderPage(p, width)
            runOnUiThread {
                if (token != renderToken) return@runOnUiThread
                pageView.bitmap = bmp
                scroll.scrollTo(0, 0)
                applyHighlight(true)
            }
        }
        service?.pageData(p) { d ->
            if (p == shownPage) {
                pageData = d
                applyHighlight(true)
            }
        }
    }

    private fun renderPage(p: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        return try {
            val page = r.openPage(p)
            try {
                val h = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun closeRenderer() {
        try { renderer?.close() } catch (_: Exception) {}
        try { renderPfd?.close() } catch (_: Exception) {}
        renderer = null
        renderPfd = null
    }

    private fun applyHighlight(follow: Boolean) {
        val d = pageData ?: return
        pageView.sentence = if (curEnd > curStart) TextUtil.lineRects(d.boxes, curStart, curEnd) else emptyList()
        pageView.word = if (wordA >= 0 && wordB > wordA) TextUtil.lineRects(d.boxes, wordA, wordB) else emptyList()
        pageView.invalidate()
        if (!follow) return
        val first = pageView.sentence.firstOrNull() ?: return
        val y = (first.top * pageView.height).roundToInt()
        val top = scroll.scrollY
        val vh = scroll.height
        if (vh > 0 && (y < top + vh / 10 || y > top + vh * 3 / 4)) {
            scroll.smoothScrollTo(0, maxOf(0, y - vh / 4))
        }
    }

    /** Index of the character nearest to (x, y), or -1 if nothing is close. */
    private fun charAt(d: PdfPage, x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in d.boxes.indices) {
            val r = d.boxes[i] ?: continue
            val dx = maxOf(r.left - x, 0f, x - r.right)
            val dy = maxOf(r.top - y, 0f, y - r.bottom)
            val dist = dx * dx + dy * dy
            if (dist < bestD) { bestD = dist; best = i }
        }
        return if (best < 0 || bestD > 0.003f) -1 else best
    }

    /** Long-press: offer to skip that line (e.g. a running header) on every page. */
    private fun markLine(x: Float, y: Float) {
        val d = pageData ?: return
        val hit = charAt(d, x, y)
        if (hit < 0) return
        val ref = d.boxes[hit] ?: return
        fun sameLine(i: Int): Boolean {
            val b = d.boxes[i] ?: return d.text[i] == ' '
            return kotlin.math.abs(b.centerY() - ref.centerY()) < ref.height() * 0.5f
        }
        var a = hit
        var b = hit + 1
        while (a > 0 && d.text[a - 1] != '\n' && sameLine(a - 1)) a--
        while (b < d.text.length && d.text[b] != '\n' && sameLine(b)) b++
        val text = d.text.substring(a, b).trim()
        if (text.isEmpty()) return
        pageView.marked = TextUtil.lineRects(d.boxes, a, b)
        pageView.invalidate()
        android.app.AlertDialog.Builder(this)
            .setTitle("Skip this text?")
            .setMessage("\u201C$text\u201D\n\nLines like this (page numbers ignored) will be left out on every page of this PDF.")
            .setPositiveButton("Skip it") { _, _ -> service?.addSkip(text) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear skip list") { _, _ ->
                service?.clearSkips()
                Toast.makeText(this, "Skip list cleared for this PDF", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener { pageView.marked = emptyList(); pageView.invalidate() }
            .show()
    }

    /** Tap on the page: read from the start of that sentence. */
    private fun tapAt(x: Float, y: Float) {
        val d = pageData ?: return
        val best = charAt(d, x, y)
        if (best < 0) return
        val seg = TextUtil.segments(d.text).firstOrNull { best >= it.start && best < it.end } ?: return
        service?.seek(shownPage, seg.start, andPlay = true)
    }

    // ---- helpers ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        private const val PLAY = "▶︎"
        private const val PAUSE = "⏸︎"
        private const val PREV = "⏮︎"
        private const val NEXT = "⏭︎"
    }
}
