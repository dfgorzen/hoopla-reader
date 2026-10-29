package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Pocket mode: a full-screen black layer at minimum brightness that keeps the screen on
 * (black OLED pixels are off) while the reader keeps turning pages underneath.
 * It swallows touches so a pocket can't poke hoopla; it lets them through only while the
 * reader's own page-turn swipe is being performed. Hold it for 2 s to exit.
 */
class PocketOverlay(ctx: AccessibilityService, private val onExit: () -> Unit) {
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private val exitHold = Runnable { onExit() }

    @SuppressLint("ClickableViewAccessibility")
    private val view = View(ctx).apply {
        setBackgroundColor(Color.BLACK)
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> h.postDelayed(exitHold, HOLD_MS)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> h.removeCallbacks(exitHold)
            }
            true
        }
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        screenBrightness = 0.0f
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
        h.removeCallbacks(exitHold)
        if (!visible) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
        visible = false
    }

    /** Let touches (our own page-turn swipe) reach the app underneath, or block them again. */
    fun passThrough(on: Boolean) {
        if (!visible) return
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val HOLD_MS = 2000L
    }
}
