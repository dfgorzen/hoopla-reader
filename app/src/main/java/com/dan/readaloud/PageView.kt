package com.dan.readaloud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/** A rendered PDF page, full width, with sentence/word highlight boxes given in page fractions. */
class PageView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var sentence: List<RectF> = emptyList()
    var word: List<RectF> = emptyList()
    var onTap: ((Float, Float) -> Unit)? = null
    var onLongPress: ((Float, Float) -> Unit)? = null
    var marked: List<RectF> = emptyList()

    private val density = ctx.resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sentencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFD54F }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FF9800.toInt() }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66E53935 }
    private val src = Rect()
    private val dst = RectF()
    private val tmp = RectF()

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (width > 0 && height > 0) onTap?.invoke(e.x / width, e.y / height)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (width > 0 && height > 0) onLongPress?.invoke(e.x / width, e.y / height)
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val b = bitmap
        val h = if (b != null && b.width > 0) (w.toLong() * b.height / b.width).toInt() else (w * 1.3f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        src.set(0, 0, b.width, b.height)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, src, dst, bmpPaint)
        val pad = 1.5f * density
        val rad = 3f * density
        for (r in sentence) draw(canvas, r, sentencePaint, pad, rad)
        for (r in word) draw(canvas, r, wordPaint, pad, rad)
        for (r in marked) draw(canvas, r, markPaint, pad, rad)
    }

    private fun draw(c: Canvas, r: RectF, p: Paint, pad: Float, rad: Float) {
        tmp.set(r.left * width - pad, r.top * height - pad, r.right * width + pad, r.bottom * height + pad)
        c.drawRoundRect(tmp, rad, rad, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
