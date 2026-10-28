package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Read loop: grab visible page text -> speak it -> turn page -> wait -> repeat.
 * Stops when the page text stops changing (end of book) or no text is found.
 */
class ReaderService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private var overlay: Overlay? = null

    private var playing = false
    private var lastPageText = ""
    private var carry = ""       // unfinished sentence held over for the next page
    private var pageCarry = ""   // carry that belongs to the page currently on screen (for resume)
    private var sameCount = 0
    private var blankTries = 0

    private val readRunnable = Runnable { readCurrentPage() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        speaker = Speaker(this) { ok ->
            if (!ok) toast("No text-to-speech engine found. Install one (e.g. Sherpa TTS or RHVoice) and select it in Text-to-speech settings.")
        }
        overlay = Overlay(this, prefs, onPlayPause = { toggle() }, onNext = { skipPage() })
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
            overlay?.show()
        } else {
            if (playing) pause()
            overlay?.hide()
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
        overlay = null
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
        carry = pageCarry
        lastPageText = ""
        sameCount = 0
        blankTries = 0
        readCurrentPage()
    }

    private fun pause() {
        playing = false
        h.removeCallbacks(readRunnable)
        if (::speaker.isInitialized) speaker.stop()
        overlay?.setPlaying(false)
    }

    private fun skipPage() {
        h.removeCallbacks(readRunnable)
        speaker.stop()
        carry = ""
        pageCarry = ""
        lastPageText = ""
        sameCount = 0
        turnPage()
        if (playing) h.postDelayed(readRunnable, prefs.turnDelayMs.toLong())
    }

    // ---- read loop ----

    private fun readCurrentPage() {
        if (!playing) return
        val root = targetRoot()
        if (root == null) {
            retryBlank(); return
        }
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val text = PageExtractor.extract(root, bounds, prefs.topMarginPct, prefs.bottomMarginPct)
        if (text.isBlank()) {
            retryBlank(); return
        }
        blankTries = 0

        if (text == lastPageText) {
            // Page hasn't changed yet: wait once, re-swipe once, then give up.
            sameCount++
            when {
                sameCount >= 4 -> {
                    pause()
                    toast("Stopped: the page isn't changing (end of book?)")
                }
                sameCount == 2 -> {
                    turnPage(); h.postDelayed(readRunnable, prefs.turnDelayMs.toLong())
                }
                else -> h.postDelayed(readRunnable, prefs.turnDelayMs.toLong())
            }
            return
        }

        sameCount = 0
        lastPageText = text
        pageCarry = carry
        var toSpeak = if (carry.isEmpty()) text else "$carry $text"
        carry = ""
        if (prefs.carryFragments) {
            val (now, later) = TextUtil.splitTrailingFragment(toSpeak)
            toSpeak = now
            carry = later
        }
        speaker.speak(toSpeak, prefs.speechRate) { onPageSpoken() }
    }

    private fun onPageSpoken() {
        if (!playing) return
        pageCarry = carry
        turnPage()
        h.postDelayed(readRunnable, prefs.turnDelayMs.toLong())
    }

    private fun retryBlank() {
        blankTries++
        if (blankTries > 5) {
            pause()
            toast("No readable text found on this screen.")
        } else {
            h.postDelayed(readRunnable, 700)
        }
    }

    // ---- helpers ----

    /** Root of the app window being read, never our own overlay. */
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

    private fun turnPage() {
        val b = Rect()
        val root = targetRoot()
        if (root != null) root.getBoundsInScreen(b)
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
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun toast(msg: String) {
        h.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    companion object {
        @Volatile
        var instance: ReaderService? = null
            private set
    }
}
