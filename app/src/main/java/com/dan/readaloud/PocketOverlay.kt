package com.dan.readaloud

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pocket mode: a full-screen black layer at minimum brightness that keeps the screen on
 * (black OLED pixels are off) while the reader keeps turning pages underneath.
 * It swallows touches so a pocket can't poke hoopla, letting them through only while the
 * reader's own page-turn swipe runs.
 *
 * Any tap wakes it: brightness comes back and a large "Exit pocket mode" button shows for
 * a few seconds; tap it to exit, or leave it and the screen goes dark again.
 */
class PocketOverlay(ctx: AccessibilityService, private val onExit: () -> Unit) {
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val sleep = Runnable { setAwake(false) }

    private val hint = TextView(ctx).apply {
        text = "Reading · tap to wake"
        textSize = 16f
        setTextColor(0xFF3A3A3A.toInt())
        gravity = Gravity.CENTER
    }

    private val exitButton = TextView(ctx).apply {
        text = "Exit pocket mode"
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        val p = (24 * density).toInt()
        setPadding(p * 2, p, p * 2, p)
        background = GradientDrawable().apply {
            cornerRadius = 40 * density
            setColor(0xFF2E5E4E.toInt())
        }
        visibility = View.GONE
        setOnClickListener { onExit() }
    }

    private val subHint = TextView(ctx).apply {
        text = "Pages keep turning while the screen is dark.\nPressing volume up + down together also exits."
        textSize = 14f
        setTextColor(0xFF9A9A9A.toInt())
        gravity = Gravity.CENTER
        setPadding(0, (20 * density).toInt(), 0, 0)
        visibility = View.GONE
    }

    private val view = FrameLayout(ctx).apply {
        setBackgroundColor(Color.BLACK)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(hint)
            addView(exitButton)
            addView(subHint)
        }
        addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // A tap anywhere (outside the button) wakes the screen.
        setOnClickListener { setAwake(true) }
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
        screenBrightness = DIM
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    var visible = false
        private set

    fun show() {
        if (visible) return
        setAwake(false, apply = false)
        try {
            wm.addView(view, lp)
            visible = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        h.removeCallbacks(sleep)
        if (!visible) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
        visible = false
    }

    private fun setAwake(awake: Boolean, apply: Boolean = true) {
        h.removeCallbacks(sleep)
        hint.visibility = if (awake) View.GONE else View.VISIBLE
        exitButton.visibility = if (awake) View.VISIBLE else View.GONE
        subHint.visibility = if (awake) View.VISIBLE else View.GONE
        lp.screenBrightness = if (awake) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else DIM
        if (apply && visible) {
            try {
                wm.updateViewLayout(view, lp)
            } catch (_: Exception) {
            }
        }
        if (awake) h.postDelayed(sleep, AWAKE_MS)
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
        private const val DIM = 0.01f
        private const val AWAKE_MS = 5000L
    }
}
