package com.dan.readaloud

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private var testTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        }
        setContentView(scroll)

        col.addView(heading("Read Aloud", 26f))
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(status)

        col.addView(heading("Setup", 18f))
        col.addView(button("1. Turn on \"Read Aloud page reader\"") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(note("Greyed out? Open App info → ⋮ menu → \"Allow restricted settings\", then try again."))
        col.addView(button("App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("2. Text-to-speech engine & voice") { openTtsSettings() })
        col.addView(button("Test voice") { testVoice() })

        col.addView(heading("Reading", 18f))
        col.addView(slider("Speech rate", 50, 300, (prefs.speechRate * 100).roundToInt(),
            { String.format("%.2f×", it / 100f) }) { prefs.speechRate = it / 100f })
        col.addView(slider("Wait after page turn", 300, 3000, prefs.turnDelayMs,
            { "$it ms" }) { prefs.turnDelayMs = it })

        col.addView(label("Page turn"))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rSwipe = RadioButton(this).apply { id = View.generateViewId(); text = "Swipe left" }
        val rTap = RadioButton(this).apply { id = View.generateViewId(); text = "Tap right edge" }
        rg.addView(rSwipe)
        rg.addView(rTap)
        rg.check(if (prefs.turnMode == 1) rTap.id else rSwipe.id)
        rg.setOnCheckedChangeListener { _, checked -> prefs.turnMode = if (checked == rTap.id) 1 else 0 }
        col.addView(rg)

        col.addView(slider("Ignore top of screen", 0, 25, prefs.topMarginPct,
            { "$it%" }) { prefs.topMarginPct = it })
        col.addView(slider("Ignore bottom of screen", 0, 25, prefs.bottomMarginPct,
            { "$it%" }) { prefs.bottomMarginPct = it })
        col.addView(note("Raise these if it reads chapter titles or page numbers in the margins."))

        col.addView(Switch(this).apply {
            text = "Keep sentences split across pages together"
            isChecked = prefs.carryFragments
            setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, on -> prefs.carryFragments = on }
        })

        col.addView(label("Show the controls in these apps (blank = all apps)"))
        col.addView(EditText(this).apply {
            setText(prefs.packages)
            textSize = 14f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.packages = s?.toString() ?: ""
                }
            })
        })

        col.addView(heading("How to use", 18f))
        col.addView(note(
            "Open a book in hoopla. A small ▶ control appears. Tap it to start; it reads the page, " +
                "turns it, and keeps going. ⏭ skips a page, ⠇ drags the control. Reading stops when " +
                "the page stops changing or you leave the app. The screen stays on while reading."
        ))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        testTts?.shutdown()
        testTts = null
        super.onDestroy()
    }

    private fun refreshStatus() {
        val engines = packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.loadLabel(packageManager).toString() }
        val svc = if (isServiceEnabled()) "ON" else "OFF"
        val tts = if (engines.isEmpty()) "none installed" else engines.joinToString(", ")
        val last = prefs.lastPackage.ifEmpty { "—" }
        status.text = "Reader service: $svc\nTTS engines: $tts\nLast app seen: $last"
    }

    private fun isServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ReaderService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun testVoice() {
        testTts?.shutdown()
        testTts = TextToSpeech(this) { st ->
            runOnUiThread {
                if (st == TextToSpeech.SUCCESS) {
                    testTts?.setSpeechRate(prefs.speechRate)
                    testTts?.speak("This is how your books will sound.", TextToSpeech.QUEUE_FLUSH, null, "test")
                } else {
                    Toast.makeText(this, "No text-to-speech engine installed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- tiny view helpers ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.75f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun slider(
        name: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val tv = TextView(this).apply {
            text = "$name: ${fmt(value)}"
            textSize = 15f
        }
        val sb = SeekBar(this).apply {
            this.max = max
            this.min = min
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$name: ${fmt(p)}"
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv)
        box.addView(sb)
        return box
    }
}
