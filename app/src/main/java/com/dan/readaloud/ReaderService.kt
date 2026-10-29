package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.KeyEvent
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
    private var cursor: PageExtractor.Cursor? = null         // where the current page starts
    private var pendingCursor: PageExtractor.Cursor? = null  // where the next page starts
    private var lastPageKey = ""   // screen identity of the last page we accepted
    private var staleTries = 0
    private var emptyTurns = 0
    private var blankTries = 0
    private var posRetries = 0
    private var avgPageChars = 1500f

    // Page-turn timing, for the diagnostic dump.
    private var turnStartedAt = 0L
    private var readStartedSpeakAt = 0L
    private var pendingCount = 0
    private var staleCount = 0
    private var awaitingFirstWord = false
    private var lastTurnReport = "no page turn yet"
    private var settlingPlace = ""
    private var settleTries = 0

    // Pocket mode and screen-off reading.
    private var pocket: PocketOverlay? = null
    private var pocketOn = false
    private var speaking = false
    private var chapterTexts: List<String>? = null   // all paragraphs of the current chapter
    private var blindMode = false    // screen is off: reading the chapter from memory
    private var blindAtEnd = false
    private var catchTries = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var volUpDown = false
    private var volDownDown = false
    private val catchRunnable = Runnable { catchStep() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> onUnlocked()
                Intent.ACTION_SCREEN_ON ->
                    if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) onUnlocked()
            }
        }
    }

    private var pageRects: List<RectF?> = emptyList()
    private var pageSegs: List<Seg> = emptyList()
    private var sentenceRects: List<RectF> = emptyList()
    private var wordEvents = 0
    private var pageText = ""

    // Estimated word highlight for TTS engines that don't report word timing.
    private var curSeg = -1
    private var segStartAt = 0L
    private var charsPerMs = 0f
    private var calibratedRate = 0f
    private val wordTick = object : Runnable {
        override fun run() {
            if (!playing || wordEvents > 0) return
            val s = pageSegs.getOrNull(curSeg) ?: return
            val len = s.end - s.start
            if (len <= 0) return
            val elapsed = android.os.SystemClock.uptimeMillis() - segStartAt
            val pos = (elapsed * charsPerMs).toInt().coerceIn(0, len - 1)
            var a = s.start + pos
            var b = a
            while (a > s.start && !pageText[a - 1].isWhitespace()) a--
            while (b < s.end && !pageText[b].isWhitespace()) b++
            highlight?.set(sentenceRects, TextUtil.lineRects(pageRects, a, b))
            h.postDelayed(this, 90)
        }
    }

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
            onPocket = { enterPocket() },
        )
        pocket = PocketOverlay(this) { exitPocket() }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(screenReceiver, f)
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
            if (!pocketOn) overlay?.show()
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
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        releaseWake()
        pocket?.hide()
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
        lastPageKey = ""
        staleTries = 0
        emptyTurns = 0
        blankTries = 0
        posRetries = 0
        readCurrentPage()
    }

    private fun pause() {
        playing = false
        speaking = false
        blindMode = false
        releaseWake()
        h.removeCallbacks(readRunnable)
        h.removeCallbacks(catchRunnable)
        h.removeCallbacks(wordTick)
        if (::speaker.isInitialized) speaker.stop()
        highlight?.clear()
        overlay?.setPlaying(false)
    }

    /** Long-press play: forget what was read and start this page from the top. */
    private fun restartPage() {
        pause()
        history.clear()
        cursor = null
        pendingCursor = null
        toast("Reading this page from the top")
        play()
    }

    private fun skipPage() {
        h.removeCallbacks(readRunnable)
        h.removeCallbacks(wordTick)
        speaker.stop()
        highlight?.clear()
        cursor = null
        pendingCursor = null
        staleTries = 0
        turnPage { if (playing) afterTurn() }
    }

    // ---- read loop ----

    private fun scheduleRead(delay: Long) {
        h.removeCallbacks(readRunnable)
        h.postDelayed(readRunnable, delay)
    }

    /** After a swipe: look early, then keep polling until the new page is ready. */
    private fun afterTurn() {
        if (blindMode) return
        turnStartedAt = android.os.SystemClock.uptimeMillis()
        pendingCount = 0
        staleCount = 0
        settlingPlace = ""
        settleTries = 0
        awaitingFirstWord = true
        scheduleRead(FIRST_LOOK_MS)
    }

    private fun readCurrentPage() {
        if (!playing || blindMode) return
        val root = targetRoot()
        if (root == null) {
            retryBlank(); return
        }
        val win = Rect()
        root.getBoundsInScreen(win)

        val page = PageBuffer()
        val res = PageExtractor.extract(
            root, win, prefs.topMarginPct, prefs.bottomMarginPct, cursor, page, avgPageChars.toInt()
        )
        // Glyph positions still loading after the page turn: check again shortly.
        if (res.pending && posRetries < 8) {
            posRetries++
            pendingCount++
            scheduleRead(POLL_MS.toLong())
            return
        }
        posRetries = 0

        // Screen still shows the page we just read (or nothing after the bookmark):
        // the reader hasn't redrawn yet.
        val stale = (res.pageKey.isNotEmpty() && res.pageKey == lastPageKey) ||
            (res.added == 0 && res.fromCursor)
        if (stale) {
            staleTries++
            staleCount++
            // Keep checking every POLL_MS; re-swipe once the page-turn wait setting has passed.
            if (staleTries * POLL_MS < maxOf(prefs.turnDelayMs * 2, 1200)) {
                scheduleRead(POLL_MS.toLong())
            } else {
                staleTries = 0
                turnAgain()
            }
            return
        }
        if (res.added == 0) {
            retryBlank(); return
        }
        // Just after a swipe the page may still be sliding: only read once the first
        // line sits in the same place on two looks in a row.
        if (awaitingFirstWord && res.gPlace.isNotEmpty()) {
            val place = res.pageKey + "@" + res.gPlace
            if (place != settlingPlace && settleTries < 6) {
                settlingPlace = place
                settleTries++
                scheduleRead(SETTLE_MS)
                return
            }
        }
        settlingPlace = ""
        settleTries = 0
        blankTries = 0
        staleTries = 0
        lastPageKey = res.pageKey
        chapterTexts = res.leafTexts
        // Learn how much text a full page holds (used for pages with no positioned text).
        if (res.pageEnded && !res.pageKey.startsWith("blind") && res.added in 300..6000) {
            avgPageChars = avgPageChars * 0.7f + res.added * 0.3f
        }

        val text = page.text()
        var segs = TextUtil.segments(text)
        var next = res.next

        // A sentence cut off at the page end: stop before it and start the next page there.
        if (prefs.carryFragments && res.pageEnded && segs.size > 1) {
            val last = segs.last()
            if (!TextUtil.endsSentence(text, last.end) && last.text.length <= MAX_CARRY) {
                val c = res.cursorFor(page.src.getOrElse(last.start) { -1L })
                if (c != null) {
                    next = c
                    segs = segs.dropLast(1)
                }
            }
        }

        // Look ahead: drop sentences already spoken (text repeated across pages).
        val skip = TextUtil.repeats(segs.map { TextUtil.key(it.text) }, history)
        val toSpeak = segs.filterIndexed { i, _ -> !skip[i] }

        if (toSpeak.isEmpty()) {
            if (next != null && next != cursor) {
                cursor = next
                emptyTurns = 0
                turnPage { afterTurn() }
            } else {
                turnAgain()
            }
            return
        }

        emptyTurns = 0
        pendingCursor = next
        pageRects = ArrayList(page.rects)
        pageText = text
        pageSegs = toSpeak
        curSeg = -1
        sentenceRects = emptyList()
        readStartedSpeakAt = android.os.SystemClock.uptimeMillis()
        speaking = true
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
        turnPage { afterTurn() }
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
        curSeg = index
        segStartAt = android.os.SystemClock.uptimeMillis()
        if (prefs.speechRate != calibratedRate) {
            calibratedRate = prefs.speechRate
            charsPerMs = 0.014f * calibratedRate   // ~14 chars/s at 1.0×, refined below
        }
        h.removeCallbacks(wordTick)
        if (awaitingFirstWord) {
            awaitingFirstWord = false
            lastTurnReport = "speech resumed ${segStartAt - turnStartedAt} ms after the swipe finished " +
                "(position-loading retries $pendingCount, page-not-turned retries $staleCount, " +
                "page read in ${readStartedSpeakAt - turnStartedAt} ms, TTS start ${segStartAt - readStartedSpeakAt} ms)"
        }
        if (!prefs.highlight) return
        val s = pageSegs.getOrNull(index) ?: return
        sentenceRects = TextUtil.lineRects(pageRects, s.start, s.end)
        highlight?.set(sentenceRects, emptyList())
        if (wordEvents == 0) h.post(wordTick)
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
        h.removeCallbacks(wordTick)
        val s = pageSegs.getOrNull(index) ?: return
        history.add(TextUtil.key(s.text))
        // Calibrate the word estimate from how long this sentence really took.
        val dur = android.os.SystemClock.uptimeMillis() - segStartAt
        val len = s.end - s.start
        if (index == curSeg && dur > 300 && len >= 15) {
            val cps = len.toFloat() / dur
            charsPerMs = if (charsPerMs <= 0f) cps else charsPerMs * 0.7f + cps * 0.3f
        }
    }

    override fun onAllDone() {
        h.removeCallbacks(wordTick)
        speaking = false
        if (!playing) return
        highlight?.clear()
        if (blindMode && blindAtEnd) return   // said "end of chapter"; wait for unlock
        cursor = pendingCursor
        pendingCursor = null
        if (blindMode) {
            h.post { speakBlindNext() }
            return
        }
        turnPage { afterTurn() }
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
        val once = {
            if (!fired) {
                fired = true
                pocket?.passThrough(false)
                after()
            }
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (pocketOn) {
            // Let our own swipe through the black layer, then block touches again.
            pocket?.passThrough(true)
            h.postDelayed({ dispatch(gesture, once) }, 60)
            return
        }
        dispatch(gesture, once)
    }

    private fun dispatch(gesture: GestureDescription, once: () -> Unit) {
        val ok = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = once()
                override fun onCancelled(gestureDescription: GestureDescription?) = once()
            },
            null
        )
        if (!ok) once()
    }

    // ---- pocket mode ----

    private fun enterPocket() {
        if (pocketOn) return
        pocketOn = true
        highlight?.clear()
        overlay?.hide()
        pocket?.show()
        toast("Pocket mode. To exit: hold the screen 2 s, or press both volume keys together.")
    }

    private fun exitPocket() {
        if (!pocketOn) return
        pocketOn = false
        pocket?.hide()
        overlay?.show()
    }

    /** Both volume keys down together = leave pocket mode. Volume otherwise works as normal. */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || !pocketOn) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volUpDown = down
            KeyEvent.KEYCODE_VOLUME_DOWN -> volDownDown = down
            else -> return false
        }
        if (volUpDown && volDownDown) {
            volUpDown = false
            volDownDown = false
            h.post { exitPocket() }
            return true
        }
        return false
    }

    // ---- screen off: keep reading the chapter from memory ----

    private fun onScreenOff() {
        if (!playing || !prefs.screenOffReading || chapterTexts == null || blindMode) return
        blindMode = true
        blindAtEnd = false
        acquireWake()
        h.removeCallbacks(readRunnable)
        h.removeCallbacks(wordTick)
        h.removeCallbacks(catchRunnable)
        // A page still being spoken finishes first (onAllDone continues from memory);
        // otherwise we were between pages: carry on from the bookmark now.
        if (!speaking) speakBlindNext()
    }

    private fun onUnlocked() {
        if (!blindMode) return
        blindMode = false
        blindAtEnd = false
        releaseWake()
        if (!playing) return
        // Stop mid-sentence; the bookmark is the start of the part being read, and
        // sentences already spoken are skipped when it is read again.
        speaker.stop()
        speaking = false
        pendingCursor = null
        catchTries = 0
        h.postDelayed(catchRunnable, 700)
    }

    /** Speak about a page of the chapter after the bookmark, from the remembered text. */
    private fun speakBlindNext() {
        if (!playing || !blindMode) return
        val texts = chapterTexts
        val c = cursor
        if (texts == null || c == null) { blindEnd(false); return }
        val idx = if (c.leaf in texts.indices && texts[c.leaf] == c.leafText) c.leaf else texts.indexOf(c.leafText)
        if (idx < 0) { blindEnd(false); return }

        val buf = PageBuffer()
        var started = false
        var prevEndsSpace = false
        var total = 0
        var reachedEnd = true
        for (li in idx until texts.size) {
            val t = texts[li]
            val from = if (li == idx) c.char.coerceIn(0, t.length) else 0
            if (from >= t.length) continue
            if (started) {
                val c0 = t[from]
                if (!(prevEndsSpace || c0.isWhitespace() || ",.;:!?’”)]".indexOf(c0) >= 0)) buf.blockBreak()
            }
            buf.append(t, null, from, t.length, li)
            started = true
            prevEndsSpace = t.last().isWhitespace()
            total += t.length - from
            if (total > avgPageChars * 3) { reachedEnd = li == texts.size - 1; break }
        }
        val text = buf.text()
        val segs = TextUtil.segments(text)
        if (segs.isEmpty()) { blindEnd(true); return }

        val batch = ArrayList<Seg>()
        var len = 0
        for (s in segs) {
            batch.add(s); len += s.text.length
            if (len >= avgPageChars) break
        }
        val nextSeg = segs.getOrNull(batch.size)
        pendingCursor = when {
            nextSeg != null -> cursorAt(buf, nextSeg.start, texts)
            reachedEnd -> PageExtractor.Cursor(texts.size - 1, texts.last().length, texts.last())
            else -> cursorAt(buf, buf.length - 1, texts)
        }

        val skip = TextUtil.repeats(batch.map { TextUtil.key(it.text) }, history)
        val toSpeak = batch.filterIndexed { i, _ -> !skip[i] }
        if (toSpeak.isEmpty()) {
            cursor = pendingCursor
            if (nextSeg == null && reachedEnd) blindEnd(true) else h.post { speakBlindNext() }
            return
        }
        pageRects = emptyList()   // nothing to highlight with the screen off
        pageText = text
        pageSegs = toSpeak
        curSeg = -1
        speaking = true
        speaker.speak(toSpeak.map { it.text }, prefs.speechRate, this)
    }

    private fun cursorAt(buf: PageBuffer, index: Int, texts: List<String>): PageExtractor.Cursor? {
        var i = index.coerceIn(0, buf.length - 1)
        while (i < buf.length && buf.src[i] < 0) i++
        if (i >= buf.length) return null
        val s = buf.src[i]
        val li = (s ushr 32).toInt()
        val ci = (s and 0xffffffffL).toInt()
        return if (li in texts.indices) PageExtractor.Cursor(li, ci, texts[li]) else null
    }

    /** End of what we can read with the screen off: say so once, then wait for unlock. */
    private fun blindEnd(chapterEnd: Boolean) {
        blindAtEnd = true
        pageSegs = emptyList()
        val msg = if (chapterEnd) "End of chapter. Unlock your phone to keep reading."
        else "Unlock your phone to keep reading."
        speaking = true
        speaker.speak(listOf(msg), prefs.speechRate, this)
    }

    /** After unlocking: swipe hoopla forward until the bookmark is on screen, then read normally. */
    private fun catchStep() {
        if (!playing) return
        val root = targetRoot()
        val pkg = root?.packageName?.toString()
        val targets = prefs.targets()
        if (root == null || pkg == "com.android.systemui" || (targets.isNotEmpty() && pkg !in targets)) {
            if (++catchTries < 30) h.postDelayed(catchRunnable, 500) else pause()
            return
        }
        val win = Rect()
        root.getBoundsInScreen(win)
        val res = PageExtractor.extract(
            root, win, prefs.topMarginPct, prefs.bottomMarginPct, cursor, PageBuffer(), avgPageChars.toInt()
        )
        if (res.pending && catchTries++ < 40) {
            h.postDelayed(catchRunnable, POLL_MS.toLong()); return
        }
        val d = res.cursorDelta
        if (d != null && d > res.pageSpan && res.pageSpan > 0 && catchTries++ < 200) {
            // Our place is further on: flip forward.
            turnPage { h.postDelayed(catchRunnable, 650) }
            return
        }
        // On this page (or the bookmark is gone, e.g. a new chapter): carry on normally.
        lastPageKey = ""
        awaitingFirstWord = false
        staleTries = 0
        emptyTurns = 0
        readCurrentPage()
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadAloud:screenoff").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
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
                val res = PageExtractor.extract(root, win, prefs.topMarginPct, prefs.bottomMarginPct, cursor, page, avgPageChars.toInt())
                sb.appendLine("learned page size: ${avgPageChars.toInt()} chars")
                sb.appendLine("last page turn: $lastTurnReport")
                sb.appendLine("chars=${res.added} charPositions=${res.hasCharPositions} webView=${res.usedWebView} pageEnded=${res.pageEnded}")
                sb.appendLine("word estimate: ${charsPerMs * 1000f} chars/s")
                sb.appendLine("positions pending: ${res.pending}")
                sb.appendLine("page key: ${res.pageKey} (last accepted: $lastPageKey)")
                val c = cursor
                sb.appendLine("bookmark in: " + (if (c == null) "none" else "leaf ${c.leaf} char ${c.char} \"${Diagnostics.snip(c.leafText.drop(c.char))}\""))
                sb.appendLine("fromBookmark=${res.fromCursor} ${res.note}")
                val nx = res.next
                sb.appendLine("bookmark out: " + (if (nx == null) "none" else "leaf ${nx.leaf} char ${nx.char} \"${Diagnostics.snip(nx.leafText.drop(nx.char))}\""))
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
        private const val FIRST_LOOK_MS = 250L
        private const val POLL_MS = 200
        private const val SETTLE_MS = 200L

        @Volatile
        var instance: ReaderService? = null
            private set
    }
}
