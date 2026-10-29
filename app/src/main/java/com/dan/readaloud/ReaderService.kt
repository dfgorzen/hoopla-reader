package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Read loop: grab the text on this page -> drop anything already spoken -> speak it
 * sentence by sentence with highlighting -> turn page -> repeat.
 *
 * A page whose text is identical to the last one is treated as "not turned yet"
 * (the reader hasn't redrawn) and re-checked, then re-swiped. A page with nothing new
 * on it is skipped straight away so the story keeps moving.
 */
class ReaderService : AccessibilityService(), Speaker.Listener {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private var overlay: Overlay? = null
    private var highlight: HighlightOverlay? = null
    private val history = History()

    private var playing = false
    private var carry = ""         // unfinished sentence carried into the next page
    private var pendingCarry = ""  // carry produced by the page being spoken
    private var lastRaw = ""       // text of the last page we accepted
    private var staleTries = 0
    private var emptyTurns = 0
    private var blankTries = 0

    private var pageRects: List<RectF?> = emptyList()
    private var pageSegs: List<Seg> = emptyList()
    private var sentenceRects: List<RectF> = emptyList()
    private var wordEvents = 0

    private val readRunnable = Runnable { readCurrentPage() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        speaker = Speaker(this) { ok ->
            if (!ok) toast("No text-to-speech engine found. Install one (e.g. Sherpa TTS or RHVoice) and select it in Text-to-speech settings.")
        }
        highlight = HighlightOverlay(this)
        overlay = Overlay(
            this, prefs,
            onPlayPause = { toggle() },
            onNext = { skipPage() },
            onRestart = { restartPage() },
            onDump = { dumpScreen() },
        )
        updateOverlayFor(targetRoot()?.packageName?.toString())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = targetRoot()?.packageName?.toString() ?: event.packageName?.toString() ?: return
        updateOverlayFor(pkg)
    }

    private fun updateOverlayFor(pkg: String?) {
        if (pkg == null || pkg == packageName || pkg == "com.android.systemui") return
        if (pkg != prefs.lastPackage) prefs.lastPackage = pkg
        val targets = prefs.targets()
        if (targets.isEmpty() || pkg in targets) {
            highlight?.show()
            overlay?.show()
        } else {
            if (playing) pause()
            overlay?.hide()
            highlight?.hide()
        }
    }

    override fun onInterrupt() {
        pause()
    }

    override fun onDestroy() {
        instance = null
        h.removeCallbacksAndMessages(null)
        if (::speaker.isInitialized) speaker.shutdown()
        overlay?.hide()
        highlight?.hide()
        overlay = null
        highlight = null
        super.onDestroy()
    }

    // ---- controls ----

    private fun toggle() {
        if (playing) pause() else play()
    }

    private fun play() {
        if (!speaker.ready) {
            toast("Text-to-speech isn't ready. Install a TTS engine and choose it in Text-to-speech settings.")
            return
        }
        playing = true
        overlay?.setPlaying(true)
        lastRaw = ""
        staleTries = 0
        emptyTurns = 0
        blankTries = 0
        readCurrentPage()
    }

    private fun pause() {
        playing = false
        h.removeCallbacks(readRunnable)
        if (::speaker.isInitialized) speaker.stop()
        highlight?.clear()
        overlay?.setPlaying(false)
    }

    /** Long-press play: forget what was read and start this page from the top. */
    private fun restartPage() {
        pause()
        history.clear()
        carry = ""
        pendingCarry = ""
        toast("Reading this page from the top")
        play()
    }

    private fun skipPage() {
        h.removeCallbacks(readRunnable)
        speaker.stop()
        highlight?.clear()
        carry = ""
        pendingCarry = ""
        staleTries = 0
        turnPage { if (playing) scheduleRead(prefs.turnDelayMs.toLong()) }
    }

    // ---- read loop ----

    private fun scheduleRead(delay: Long) {
        h.removeCallbacks(readRunnable)
        h.postDelayed(readRunnable, delay)
    }

    private fun readCurrentPage() {
        if (!playing) return
        val root = targetRoot()
        if (root == null) {
            retryBlank(); return
        }
        val win = Rect()
        root.getBoundsInScreen(win)

        val page = PageBuffer()
        val res = PageExtractor.extract(root, win, prefs.topMarginPct, prefs.bottomMarginPct, page)
        if (res.added == 0) {
            retryBlank(); return
        }
        blankTries = 0
        val raw = page.text()

        // Same text as the page we just read: the reader hasn't redrawn yet.
        if (raw == lastRaw) {
            staleTries++
            if (staleTries <= 2) {
                scheduleRead(600)
            } else {
                staleTries = 0
                turnAgain()
            }
            return
        }
        staleTries = 0
        lastRaw = raw

        // Prepend the sentence left unfinished on the previous page.
        val buf = PageBuffer()
        if (carry.isNotEmpty()) {
            buf.append(carry, null, 0, carry.length)
            if (res.firstTruncated == false) buf.blockBreak() else buf.space()
        }
        buf.appendAll(page)
        val text = buf.text()

        var segs = TextUtil.segments(text)
        var newCarry = ""
        if (prefs.carryFragments && segs.isNotEmpty()) {
            val last = segs.last()
            val incomplete = !TextUtil.endsSentence(text, last.end)
            if (incomplete && (res.lastTruncated ?: true) && last.text.length <= MAX_CARRY) {
                newCarry = last.text
                segs = segs.dropLast(1)
            }
        }

        // Look ahead: drop sentences already spoken (text repeated across pages).
        val skip = TextUtil.repeats(segs.map { TextUtil.key(it.text) }, history)
        val toSpeak = segs.filterIndexed { i, _ -> !skip[i] }

        if (toSpeak.isEmpty()) {
            if (newCarry.isNotEmpty() && newCarry != carry) {
                // Only the start of a sentence here; hold it and move on.
                carry = newCarry
                emptyTurns = 0
                turnPage { scheduleRead(prefs.turnDelayMs.toLong()) }
            } else {
                turnAgain()
            }
            return
        }

        emptyTurns = 0
        pendingCarry = newCarry
        pageRects = ArrayList(buf.rects)
        pageSegs = toSpeak
        sentenceRects = emptyList()
        speaker.speak(toSpeak.map { it.text }, prefs.speechRate, this)
    }

    /** Nothing new on screen: turn again, but give up after several empty pages. */
    private fun turnAgain() {
        emptyTurns++
        if (emptyTurns > 4) {
            pause()
            toast("Stopped: no new text after several page turns (end of book?)")
            return
        }
        turnPage { scheduleRead(prefs.turnDelayMs.toLong()) }
    }

    private fun retryBlank() {
        blankTries++
        if (blankTries > 5) {
            pause()
            toast("No readable text found on this screen.")
        } else {
            scheduleRead(700)
        }
    }

    // ---- speech callbacks ----

    override fun onSegmentStart(index: Int) {
        if (!prefs.highlight) return
        val s = pageSegs.getOrNull(index) ?: return
        sentenceRects = TextUtil.lineRects(pageRects, s.start, s.end)
        highlight?.set(sentenceRects, emptyList())
    }

    override fun onWord(index: Int, start: Int, end: Int) {
        wordEvents++
        if (!prefs.highlight) return
        val s = pageSegs.getOrNull(index) ?: return
        val a = (s.start + start).coerceIn(s.start, s.end)
        val b = (s.start + end).coerceIn(a, s.end)
        highlight?.set(sentenceRects, TextUtil.lineRects(pageRects, a, b))
    }

    override fun onSegmentDone(index: Int) {
        val s = pageSegs.getOrNull(index) ?: return
        history.add(TextUtil.key(s.text))
    }

    override fun onAllDone() {
        if (!playing) return
        highlight?.clear()
        carry = pendingCarry
        pendingCarry = ""
        turnPage { scheduleRead(prefs.turnDelayMs.toLong()) }
    }

    // ---- helpers ----

    /** Root of the app window being read, never our own overlays. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() != packageName) return active
        return try {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() != packageName }
        } catch (_: Exception) {
            null
        }
    }

    /** Swipes (or taps) to the next page; [after] runs once the gesture has finished. */
    private fun turnPage(after: () -> Unit) {
        highlight?.clear()
        val b = Rect()
        targetRoot()?.getBoundsInScreen(b)
        if (b.isEmpty) {
            val dm = resources.displayMetrics
            b.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val y = b.top + b.height() * 0.5f
        val path = Path()
        val stroke = if (prefs.turnMode == 1) {
            path.moveTo(b.left + b.width() * 0.94f, y)
            GestureDescription.StrokeDescription(path, 0, 60)
        } else {
            path.moveTo(b.left + b.width() * 0.85f, y)
            path.lineTo(b.left + b.width() * 0.15f, y)
            GestureDescription.StrokeDescription(path, 0, 250)
        }
        var fired = false
        val once = { if (!fired) { fired = true; after() } }
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = once()
                override fun onCancelled(gestureDescription: GestureDescription?) = once()
            },
            null
        )
        if (!ok) once()
    }

    // ---- diagnostics (long-press ⏭) ----

    private fun dumpScreen() {
        if (playing) pause()
        toast("Capturing screen info…")
        h.postDelayed({ writeDump() }, 300)
    }

    private fun writeDump() {
        val sb = StringBuilder()
        try {
            val ver = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) { "?" }
            sb.appendLine("Read Aloud $ver diagnostic dump")
            sb.appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK ${android.os.Build.VERSION.SDK_INT}")
            sb.appendLine("settings: rate=${prefs.speechRate} turnMode=${prefs.turnMode} delay=${prefs.turnDelayMs} top=${prefs.topMarginPct}% bottom=${prefs.bottomMarginPct}% carry=${prefs.carryFragments} highlight=${prefs.highlight}")
            sb.appendLine("word timing events from TTS so far: $wordEvents")
            sb.appendLine()
            sb.appendLine("== windows ==")
            try {
                for (w in windows) {
                    val b = Rect(); w.getBoundsInScreen(b)
                    sb.appendLine("type=${w.type} layer=${w.layer} active=${w.isActive} ${b.toShortString()} pkg=${w.root?.packageName}")
                }
            } catch (e: Exception) { sb.appendLine("windows error: $e") }

            val root = targetRoot()
            if (root == null) {
                sb.appendLine("no target root")
            } else {
                val win = Rect(); root.getBoundsInScreen(win)
                val hgt = win.height()
                val band = Rect(win.left, win.top + hgt * prefs.topMarginPct / 100, win.right, win.bottom - hgt * prefs.bottomMarginPct / 100)
                sb.appendLine()
                sb.appendLine("target pkg=${root.packageName} window=${win.toShortString()} band=${band.toShortString()}")
                sb.appendLine()
                sb.appendLine("== extracted page ==")
                val page = PageBuffer()
                val res = PageExtractor.extract(root, win, prefs.topMarginPct, prefs.bottomMarginPct, page)
                sb.appendLine("chars=${res.added} charPositions=${res.hasCharPositions} firstTruncated=${res.firstTruncated} lastTruncated=${res.lastTruncated}")
                val text = page.text()
                val segs = TextUtil.segments(text)
                val skip = TextUtil.repeats(segs.map { TextUtil.key(it.text) }, history)
                segs.forEachIndexed { i, s ->
                    val lines = TextUtil.lineRects(page.rects, s.start, s.end)
                    sb.append(if (skip[i]) "SKIP " else "     ")
                    sb.append("lines=").append(lines.size).append(' ')
                    sb.append(lines.firstOrNull()?.toShortString() ?: "-").append(' ')
                    sb.appendLine(Diagnostics.snip(s.text))
                }
                sb.appendLine()
                sb.appendLine("== accessibility tree ==")
                Diagnostics.tree(root, band, sb)
            }
        } catch (e: Exception) {
            sb.appendLine("dump error: $e")
        }
        saveAndShare(sb.toString())
    }

    private fun saveAndShare(text: String) {
        val name = "readaloud-dump-${System.currentTimeMillis() / 1000}.txt"
        val uri = try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val u = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (u != null) contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray()) }
            u
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            toast("Couldn't save the dump")
            return
        }
        toast("Saved Downloads/$name")
        try {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(send, "Share dump").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        h.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val MAX_CARRY = 400

        @Volatile
        var instance: ReaderService? = null
            private set
    }
}
