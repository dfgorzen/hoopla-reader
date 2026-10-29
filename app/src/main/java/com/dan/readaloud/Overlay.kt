package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Small draggable pill: [drag] [play/pause] [next page]. No overlay permission needed. */
class Overlay(
    private val ctx: AccessibilityService,
    private val prefs: Prefs,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onRestart: () -> Unit,
    private val onDump: () -> Unit,
) {
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val root: LinearLayout
    private val playBtn: TextView

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    var visible = false
        private set

    init {
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.overlayX
        lp.y = prefs.overlayY
        if (lp.x < 0 || lp.y < 0) {
            lp.x = dp(12)
            lp.y = (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt()
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xD9202124.toInt())
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val handle = makeButton(GRIP).apply { alpha = 0.6f }
        playBtn = makeButton(PLAY)
        val next = makeButton(NEXT)
        root.addView(handle)
        root.addView(playBtn)
        root.addView(next)

        playBtn.setOnClickListener { onPlayPause() }
        playBtn.setOnLongClickListener { onRestart(); true }
        next.setOnClickListener { onNext() }
        next.setOnLongClickListener { onDump(); true }
        handle.setOnTouchListener(DragListener())
    }

    fun show() {
        if (visible) return
        try {
            wm.addView(root, lp)
            visible = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        if (!visible) return
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        visible = false
    }

    fun setPlaying(playing: Boolean) {
        playBtn.text = if (playing) PAUSE else PLAY
        root.keepScreenOn = playing
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun makeButton(label: String) = TextView(ctx).apply {
        text = label
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    if (visible) wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.overlayX = lp.x
                    prefs.overlayY = lp.y
                }
            }
            return true
        }
    }

    companion object {
        private const val GRIP = "⠇"
        private const val PLAY = "▶︎"
        private const val PAUSE = "⏸︎"
        private const val NEXT = "⏭︎"
    }
}
