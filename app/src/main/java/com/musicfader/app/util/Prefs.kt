package com.musicfader.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences so every component reads/writes
 * the same keys without hard-coding strings everywhere.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Duration in milliseconds for the fade-in at the start of a track. */
    var fadeInDurationMs: Int
        get() = prefs.getInt(KEY_FADE_IN_MS, DEFAULT_FADE_MS)
        set(v) = prefs.edit().putInt(KEY_FADE_IN_MS, v).apply()

    /** Duration in milliseconds for the fade-out before the end of a track. */
    var fadeOutDurationMs: Int
        get() = prefs.getInt(KEY_FADE_OUT_MS, DEFAULT_FADE_MS)
        set(v) = prefs.edit().putInt(KEY_FADE_OUT_MS, v).apply()

    companion object {
        private const val PREFS_NAME = "musicfader_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FADE_IN_MS = "fade_in_ms"
        private const val KEY_FADE_OUT_MS = "fade_out_ms"
        const val DEFAULT_FADE_MS = 3000
    }
}
