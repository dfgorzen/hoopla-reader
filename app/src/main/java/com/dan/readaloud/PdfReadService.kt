package com.dan.readaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Reads a PDF aloud, page after page, independent of the screen: a foreground service
 * (keeps going with the screen off), with play/pause in its notification. The PDF screen
 * binds to it to show the page and highlight what's being read.
 */
class PdfReadService : Service(), Speaker.Listener {
    interface Listener {
        fun onDocument(title: String, pageCount: Int) {}
        fun onPosition(page: Int, start: Int, end: Int) {}
        fun onWord(page: Int, start: Int, end: Int) {}
        fun onState(playing: Boolean) {}
        fun onMessage(msg: String) {}
    }

    inner class LocalBinder : Binder() {
        val service: PdfReadService get() = this@PdfReadService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private val listeners = LinkedHashSet<Listener>()

    var doc: PdfDoc? = null
        private set
    var path: String? = null
        private set
    var title: String = ""
        private set
    var page = 0
        private set
    var charPos = 0
        private set
    var playing = false
        private set

    private var loadToken = 0
    private var speakToken = 0
    private var segPage = 0
    private var pageSegs: List<Seg> = emptyList()
    private var pageText = ""
    private var curSeg = -1
    private var segStartAt = 0L
    private var charsPerMs = 0f
    private var calibratedRate = 0f
    private var wordEvents = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            pause()   // headphones unplugged / disconnected
        }
    }

    private val wordTick = object : Runnable {
        override fun run() {
            if (!playing || wordEvents > 0) return
            val s = pageSegs.getOrNull(curSeg) ?: return
            val len = s.end - s.start
            if (len <= 0) return
            val pos = ((SystemClock.uptimeMillis() - segStartAt) * charsPerMs).toInt().coerceIn(0, len - 1)
            var a = s.start + pos
            var b = a
            while (a > s.start && !pageText[a - 1].isWhitespace()) a--
            while (b < s.end && !pageText[b].isWhitespace()) b++
            listeners.forEach { it.onWord(segPage, a, b) }
            main.postDelayed(this, 90)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this) { ok ->
            if (!ok) message("No text-to-speech engine found. Install one and choose it in Text-to-speech settings.")
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Reading", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) toggle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pause()
        speaker.shutdown()
        val d = doc
        exec.execute { d?.close() }
        exec.shutdown()
        super.onDestroy()
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        val d = doc
        if (d != null) {
            l.onDocument(title, d.pageCount)
            l.onPosition(page, charPos, charPos)
        }
        l.onState(playing)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun openDocument(file: File, name: String) {
        val d0 = doc
        if (file.absolutePath == path && d0 != null) {
            listeners.forEach { it.onDocument(title, d0.pageCount); it.onPosition(page, charPos, charPos) }
            return
        }
        pause()
        doc = null
        path = file.absolutePath
        title = name
        val token = ++loadToken
        exec.execute {
            d0?.close()
            val d = PdfDoc(applicationContext, file)
            d.topPct = prefs.topMarginPct
            d.bottomPct = prefs.bottomMarginPct
            d.dropRunningHeads = prefs.pdfSkipHeads
            val err = d.open()
            main.post {
                if (token != loadToken) {
                    exec.execute { d.close() }
                    return@post
                }
                if (err != null) {
                    message(err)
                    return@post
                }
                doc = d
                val saved = prefs.pdfPosition(file.absolutePath)
                page = saved.first.coerceIn(0, d.pageCount - 1)
                charPos = saved.second
                prefs.lastPdfPath = file.absolutePath
                prefs.lastPdfTitle = name
                listeners.forEach { it.onDocument(name, d.pageCount); it.onPosition(page, charPos, charPos) }
            }
        }
    }

    /** Text and character boxes of a page, delivered on the main thread. */
    fun pageData(i: Int, cb: (PdfPage) -> Unit) {
        val d = doc ?: return
        exec.execute {
            val p = try { d.page(i) } catch (_: Exception) { PdfPage("", emptyList(), false) }
            main.post { cb(p) }
        }
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun play() {
        if (doc == null) return
        if (!speaker.ready) {
            message("Text-to-speech isn't ready yet.")
            return
        }
        playing = true
        try { startService(Intent(this, PdfReadService::class.java)) } catch (_: Exception) {}
        try {
            startForeground(NOTE_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) {}
        acquireWake()
        requestFocus()
        if (!noisyRegistered) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_EXPORTED)
            noisyRegistered = true
        }
        listeners.forEach { it.onState(true) }
        speakFrom(page, charPos)
    }

    fun pause() {
        val was = playing
        playing = false
        speakToken++
        if (::speaker.isInitialized) speaker.stop()
        main.removeCallbacks(wordTick)
        releaseWake()
        abandonFocus()
        if (noisyRegistered) {
            try { unregisterReceiver(noisy) } catch (_: Exception) {}
            noisyRegistered = false
        }
        if (was) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            listeners.forEach { it.onState(false) }
        }
    }

    /** Jump to a page/character; keeps playing if it was, or starts if [andPlay]. */
    fun seek(p: Int, pos: Int, andPlay: Boolean = playing) {
        val d = doc ?: return
        page = p.coerceIn(0, d.pageCount - 1)
        charPos = pos
        savePos()
        speakToken++
        speaker.stop()
        main.removeCallbacks(wordTick)
        listeners.forEach { it.onPosition(page, pos, pos) }
        if (andPlay) {
            if (playing) speakFrom(page, pos) else play()
        }
    }

    private fun speakFrom(p: Int, pos: Int) {
        val d = doc ?: return
        val token = ++speakToken
        exec.execute {
            val pg = try { d.page(p) } catch (_: Exception) { PdfPage("", emptyList(), false) }
            main.post {
                if (token != speakToken || !playing) return@post
                val segs = TextUtil.segments(pg.text).filter { it.end > pos }
                if (segs.isEmpty()) {
                    // Blank (or finished) page: move on.
                    if (p + 1 < d.pageCount) {
                        page = p + 1; charPos = 0; savePos()
                        listeners.forEach { it.onPosition(page, 0, 0) }
                        speakFrom(p + 1, 0)
                    } else {
                        endOfDocument()
                    }
                    return@post
                }
                page = p
                segPage = p
                pageText = pg.text
                pageSegs = segs
                curSeg = -1
                speaker.speak(segs.map { it.text }, prefs.speechRate, this)
                updateNotification()
            }
            // Get the next page ready while this one is read.
            try { if (p + 1 < d.pageCount) d.page(p + 1) } catch (_: Exception) {}
        }
    }

    private fun endOfDocument() {
        pause()
        message("End of document")
    }

    private fun savePos() {
        path?.let { prefs.setPdfPosition(it, page, charPos) }
    }

    // ---- speech callbacks ----

    override fun onSegmentStart(index: Int) {
        curSeg = index
        segStartAt = SystemClock.uptimeMillis()
        if (prefs.speechRate != calibratedRate) {
            calibratedRate = prefs.speechRate
            charsPerMs = 0.014f * calibratedRate
        }
        val s = pageSegs.getOrNull(index) ?: return
        charPos = s.start
        savePos()
        listeners.forEach { it.onPosition(segPage, s.start, s.end) }
        main.removeCallbacks(wordTick)
        if (wordEvents == 0) main.post(wordTick)
    }

    override fun onWord(index: Int, start: Int, end: Int) {
        wordEvents++
        val s = pageSegs.getOrNull(index) ?: return
        listeners.forEach { it.onWord(segPage, s.start + start, s.start + end) }
    }

    override fun onSegmentDone(index: Int) {
        main.removeCallbacks(wordTick)
        val s = pageSegs.getOrNull(index) ?: return
        val dur = SystemClock.uptimeMillis() - segStartAt
        val len = s.end - s.start
        if (index == curSeg && dur > 300 && len >= 15) {
            val cps = len.toFloat() / dur
            charsPerMs = charsPerMs * 0.7f + cps * 0.3f
        }
    }

    override fun onAllDone() {
        main.removeCallbacks(wordTick)
        if (!playing) return
        val d = doc ?: return
        val next = segPage + 1
        if (next < d.pageCount) {
            page = next; charPos = 0; savePos()
            speakFrom(next, 0)
        } else {
            endOfDocument()
        }
    }

    // ---- system bits ----

    private fun message(msg: String) {
        if (listeners.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        else listeners.forEach { it.onMessage(msg) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PdfActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, PdfReadService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_launcher_fg)
        val count = doc?.pageCount ?: 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title.ifEmpty { "Read Aloud" })
            .setContentText("Page ${page + 1} of $count")
            .setContentIntent(open)
            .setOngoing(playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(icon, if (playing) "Pause" else "Play", toggle).build())
            .build()
    }

    private fun updateNotification() {
        if (!playing) return
        getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, buildNotification())
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadAloud:pdf").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    main.post { pause() }
                }
            }
            .build()
        am.requestAudioFocus(req)
        focus = req
    }

    private fun abandonFocus() {
        val f = focus ?: return
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f)
        focus = null
    }

    companion object {
        const val ACTION_TOGGLE = "com.dan.readaloud.PDF_TOGGLE"
        private const val CHANNEL = "reading"
        private const val NOTE_ID = 7
    }
}
