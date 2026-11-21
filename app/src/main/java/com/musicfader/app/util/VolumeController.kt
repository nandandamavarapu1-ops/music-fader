package com.musicfader.app.util

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.delay

/**
 * Wraps AudioManager to perform smooth volume fades on the STREAM_MUSIC channel.
 *
 * All fade functions are suspend functions so callers can cancel them from a
 * coroutine scope without leaving volume in a half-faded state.
 */
class VolumeController(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val maxVolume: Int
        get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    var currentVolume: Int
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        private set(v) = audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            v.coerceIn(0, maxVolume),
            0  // no UI flag – silent adjustment
        )

    /**
     * Fades from 0 → [targetVolume] over [durationMs] milliseconds.
     * Saves the user's current volume before starting so it can be restored
     * after the fade-out later.
     */
    suspend fun fadeIn(targetVolume: Int, durationMs: Int) {
        if (durationMs <= 0) {
            currentVolume = targetVolume
            return
        }
        val steps = FADE_STEPS.coerceAtMost(targetVolume)
        if (steps == 0) return

        val stepDelay = durationMs.toLong() / steps
        currentVolume = 0

        for (step in 1..steps) {
            val next = (targetVolume * step / steps.toFloat()).toInt()
            currentVolume = next
            delay(stepDelay)
        }
        currentVolume = targetVolume
    }

    /**
     * Fades from [fromVolume] → 0 over [durationMs] milliseconds.
     */
    suspend fun fadeOut(fromVolume: Int, durationMs: Int) {
        if (durationMs <= 0) {
            currentVolume = 0
            return
        }
        val steps = FADE_STEPS.coerceAtMost(fromVolume)
        if (steps == 0) return

        val stepDelay = durationMs.toLong() / steps

        for (step in steps downTo 1) {
            val next = (fromVolume * step / steps.toFloat()).toInt()
            currentVolume = next
            delay(stepDelay)
        }
        currentVolume = 0
    }

    /** Instantly restores the stream to [volume]. */
    fun setVolume(volume: Int) {
        currentVolume = volume
    }

    companion object {
        /** Number of discrete steps across the full fade arc. */
        private const val FADE_STEPS = 50
    }
}
