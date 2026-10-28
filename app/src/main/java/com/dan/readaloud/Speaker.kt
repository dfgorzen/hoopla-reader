package com.dan.readaloud

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Speaks a page as one utterance per sentence, reporting progress on the main thread:
 * sentence start/done, word ranges (if the TTS engine supports it), and page done.
 */
class Speaker(context: Context, private val onReady: (Boolean) -> Unit) : UtteranceProgressListener() {
    interface Listener {
        fun onSegmentStart(index: Int)
        fun onSegmentDone(index: Int)
        fun onWord(index: Int, start: Int, end: Int)
        fun onAllDone()
    }

    private val main = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech

    var ready = false
        private set

    private var batch = 0
    private var count = 0
    private var listener: Listener? = null

    init {
        // Post: on failure the listener can fire synchronously inside the constructor.
        tts = TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setOnUtteranceProgressListener(this)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        onReady(ready)
    }

    fun speak(parts: List<String>, rate: Float, l: Listener) {
        stop()
        if (!ready) return
        batch++
        val b = batch
        listener = l
        count = parts.size
        if (parts.isEmpty()) {
            main.post { if (b == batch) finishAll() }
            return
        }
        tts.setSpeechRate(rate)
        parts.forEachIndexed { i, p ->
            tts.speak(p, TextToSpeech.QUEUE_ADD, null, "$b:$i")
        }
    }

    fun stop() {
        batch++
        listener = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun finishAll() {
        val l = listener
        listener = null
        l?.onAllDone()
    }

    private fun parse(id: String?): IntArray? {
        if (id == null) return null
        val k = id.indexOf(':')
        if (k < 0) return null
        val b = id.substring(0, k).toIntOrNull() ?: return null
        val i = id.substring(k + 1).toIntOrNull() ?: return null
        return intArrayOf(b, i)
    }

    private fun ended(id: String?) {
        val p = parse(id) ?: return
        main.post {
            if (p[0] != batch) return@post
            listener?.onSegmentDone(p[1])
            if (p[0] == batch && p[1] == count - 1) finishAll()
        }
    }

    override fun onStart(utteranceId: String?) {
        val p = parse(utteranceId) ?: return
        main.post { if (p[0] == batch) listener?.onSegmentStart(p[1]) }
    }

    override fun onDone(utteranceId: String?) = ended(utteranceId)

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) = ended(utteranceId)

    override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId)

    override fun onStop(utteranceId: String?, interrupted: Boolean) {}

    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
        val p = parse(utteranceId) ?: return
        main.post { if (p[0] == batch) listener?.onWord(p[1], start, end) }
    }
}
