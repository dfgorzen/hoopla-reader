package com.dan.readaloud

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Thin wrapper over TextToSpeech: speak a page, get one callback when it's all spoken. */
class Speaker(context: Context, private val onReady: (Boolean) -> Unit) : UtteranceProgressListener() {
    private val main = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech

    var ready = false
        private set

    @Volatile private var lastId: String? = null
    private var doneCallback: (() -> Unit)? = null
    private var batch = 0

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

    fun speak(text: String, rate: Float, onFinished: () -> Unit) {
        stop()
        if (!ready) return
        batch++
        val b = batch
        doneCallback = onFinished
        val parts = TextUtil.chunks(text, CHUNK)
        if (parts.isEmpty()) {
            main.post { if (b == batch) finish() }
            return
        }
        tts.setSpeechRate(rate)
        lastId = "$b-${parts.size - 1}"
        parts.forEachIndexed { i, p ->
            tts.speak(p, TextToSpeech.QUEUE_ADD, null, "$b-$i")
        }
    }

    fun stop() {
        lastId = null
        doneCallback = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun finish() {
        val cb = doneCallback
        doneCallback = null
        lastId = null
        cb?.invoke()
    }

    private fun handleEnd(id: String?) {
        if (id == null || id != lastId) return
        main.post { if (id == lastId) finish() }
    }

    override fun onStart(utteranceId: String?) {}

    override fun onDone(utteranceId: String?) = handleEnd(utteranceId)

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) = handleEnd(utteranceId)

    override fun onError(utteranceId: String?, errorCode: Int) = handleEnd(utteranceId)

    companion object {
        private const val CHUNK = 1000
    }
}
