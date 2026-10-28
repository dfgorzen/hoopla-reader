package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Full-screen, touch-through layer that paints the current sentence and word. */
class HighlightOverlay(ctx: AccessibilityService) {
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density

    private var sentence: List<RectF> = emptyList()
    private var word: List<RectF> = emptyList()

    private val sentencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x47FFD54F
        style = Paint.Style.FILL
    }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8AFF9800.toInt()
        style = Paint.Style.FILL
    }

    private val view = object : View(ctx) {
        private val loc = IntArray(2)
        private val tmp = RectF()

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            val pad = 1.5f * density
            val rad = 4f * density
            for (r in sentence) paint(canvas, r, sentencePaint, pad, rad)
            for (r in word) paint(canvas, r, wordPaint, pad, rad)
        }

        private fun paint(c: Canvas, r: RectF, p: Paint, pad: Float, rad: Float) {
            tmp.set(r.left - loc[0] - pad, r.top - loc[1] - pad, r.right - loc[0] + pad, r.bottom - loc[1] + pad)
            c.drawRoundRect(tmp, rad, rad, p)
        }
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    var visible = false
        private set

    fun show() {
        if (visible) return
        try {
            wm.addView(view, lp)
            visible = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        clear()
        if (!visible) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
        visible = false
    }

    fun set(sentenceRects: List<RectF>, wordRects: List<RectF>) {
        sentence = sentenceRects
        word = wordRects
        view.invalidate()
    }

    fun clear() = set(emptyList(), emptyList())
}
