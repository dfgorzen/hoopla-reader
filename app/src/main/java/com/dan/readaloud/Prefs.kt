package com.dan.readaloud

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    /** 0 = swipe left, 1 = tap right edge */
    var turnMode: Int
        get() = sp.getInt("turnMode", 0)
        set(v) = sp.edit().putInt("turnMode", v).apply()

    var turnDelayMs: Int
        get() = sp.getInt("turnDelay", 900)
        set(v) = sp.edit().putInt("turnDelay", v).apply()

    /** Ignore text whose center is in the top/bottom N% of the window (headers, page numbers). */
    var topMarginPct: Int
        get() = sp.getInt("topPct", 7)
        set(v) = sp.edit().putInt("topPct", v).apply()

    var bottomMarginPct: Int
        get() = sp.getInt("bottomPct", 7)
        set(v) = sp.edit().putInt("bottomPct", v).apply()

    /** Hold back a sentence cut off at the page end and speak it whole with the next page. */
    var carryFragments: Boolean
        get() = sp.getBoolean("carry", true)
        set(v) = sp.edit().putBoolean("carry", v).apply()

    /** Comma-separated package names where the control shows. Blank = every app. */
    var packages: String
        get() = sp.getString("packages", DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(v) = sp.edit().putString("packages", v).apply()

    var lastPackage: String
        get() = sp.getString("lastPkg", "") ?: ""
        set(v) = sp.edit().putString("lastPkg", v).apply()

    var overlayX: Int
        get() = sp.getInt("ovX", -1)
        set(v) = sp.edit().putInt("ovX", v).apply()

    var overlayY: Int
        get() = sp.getInt("ovY", -1)
        set(v) = sp.edit().putInt("ovY", v).apply()

    fun targets(): Set<String> =
        packages.split(',', ' ', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        const val DEFAULT_PACKAGES = "com.hoopladigital.android"
    }
}
