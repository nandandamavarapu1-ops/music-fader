package com.musicfader.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.musicfader.app.R
import com.musicfader.app.ui.MainActivity
import com.musicfader.app.util.Prefs
import com.musicfader.app.util.VolumeController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns all fade logic.
 *
 * Volume lifecycle per track:
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │  TRACK_CHANGED / PLAYBACK_STARTED                    │
 *   │       ↓                                              │
 *   │  Save user volume → Fade-In (0 → userVol)            │
 *   │       ↓  (if track duration is known)                │
 *   │  Wait until (duration − fadeOutMs) has elapsed       │
 *   │       ↓                                              │
 *   │  Fade-Out (userVol → 0)                              │
 *   │       ↓                                              │
 *   │  Restore userVol immediately so next track starts OK │
 *   └──────────────────────────────────────────────────────┘
 *
 *  On PLAYBACK_STOPPED we cancel any in-progress fade and restore volume.
 */
class FaderService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var volumeController: VolumeController

    // The volume level before we touched it; we restore to this after fade-out.
    private var savedUserVolume: Int = -1

    // Coroutine jobs so we can cancel mid-fade when needed.
    private var fadeInJob: Job? = null
    private var fadeOutJob: Job? = null
    private var fadeOutSchedulerJob: Job? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        volumeController = VolumeController(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "FaderService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            FaderNotificationListener.ACTION_TRACK_CHANGED -> {
                val duration = intent.getLongExtra(
                    FaderNotificationListener.EXTRA_TRACK_DURATION_MS, -1L
                )
                Log.d(TAG, "Track changed, duration=${duration}ms")
                if (prefs.isEnabled) handleTrackChanged(duration)
            }

            FaderNotificationListener.ACTION_PLAYBACK_STARTED -> {
                Log.d(TAG, "Playback started")
                if (prefs.isEnabled) handlePlaybackStarted()
            }

            FaderNotificationListener.ACTION_PLAYBACK_STOPPED -> {
                Log.d(TAG, "Playback stopped")
                cancelAllFades(restoreVolume = true)
            }

            ACTION_TOGGLE -> {
                prefs.isEnabled = !prefs.isEnabled
                updateNotification()
                if (!prefs.isEnabled) cancelAllFades(restoreVolume = true)
                // Broadcast so the UI can update its toggle without polling.
                sendBroadcast(Intent(ACTION_STATE_CHANGED))
            }

            ACTION_STOP_SERVICE -> {
                cancelAllFades(restoreVolume = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        cancelAllFades(restoreVolume = true)
        super.onDestroy()
        Log.d(TAG, "FaderService destroyed")
    }

    // -------------------------------------------------------------------------
    // Fade logic
    // -------------------------------------------------------------------------

    /**
     * Called when the MediaController reports new metadata (new song).
     * We cancel any pending fade-out from the previous track, then do a
     * fresh fade-in for the new track.
     */
    private fun handleTrackChanged(trackDurationMs: Long) {
        cancelAllFades(restoreVolume = false)

        // Capture the current (user-set) volume as the target for the fade-in.
        // If we already saved it and haven't restored it yet (e.g. a track
        // changed before fade-out finished) reuse the saved value.
        if (savedUserVolume < 0) {
            savedUserVolume = volumeController.currentVolume
        }
        val target = savedUserVolume.coerceAtLeast(1)

        fadeInJob = lifecycleScope.launch {
            Log.d(TAG, "Fade-in start → target=$target over ${prefs.fadeInDurationMs}ms")
            volumeController.fadeIn(target, prefs.fadeInDurationMs)
            Log.d(TAG, "Fade-in complete")

            // Schedule fade-out if the track reports a valid duration.
            if (trackDurationMs > 0) {
                scheduleTrackFadeOut(trackDurationMs, target)
            }
        }
    }

    /**
     * Called on PLAYBACK_STARTED (e.g. un-pause). If we're not mid-fade, start
     * a fade-in from whatever volume is current.
     */
    private fun handlePlaybackStarted() {
        if (fadeInJob?.isActive == true) return  // already fading in

        if (savedUserVolume < 0) {
            savedUserVolume = volumeController.currentVolume
        }
        val target = savedUserVolume.coerceAtLeast(1)

        fadeInJob = lifecycleScope.launch {
            Log.d(TAG, "Playback-started fade-in → target=$target")
            volumeController.fadeIn(target, prefs.fadeInDurationMs)
        }
    }

    /**
     * Waits until (duration − fadeOutMs) have elapsed after the track started,
     * then performs a fade-out followed by an immediate volume restore so the
     * next track doesn't begin at zero.
     */
    private fun scheduleTrackFadeOut(durationMs: Long, fromVolume: Int) {
        val fadeOutMs = prefs.fadeOutDurationMs.toLong()
        val delayMs = durationMs - fadeOutMs - FADE_OUT_HEADROOM_MS

        if (delayMs <= 0) {
            Log.d(TAG, "Track too short to schedule fade-out (duration=${durationMs}ms)")
            return
        }

        fadeOutSchedulerJob = lifecycleScope.launch {
            Log.d(TAG, "Fade-out scheduled in ${delayMs}ms")
            delay(delayMs)

            if (!prefs.isEnabled) return@launch

            Log.d(TAG, "Fade-out start ← from=$fromVolume over ${fadeOutMs}ms")
            fadeOutJob = launch {
                volumeController.fadeOut(fromVolume, fadeOutMs.toInt())
                // Restore so the next track starts at the right volume (the
                // next TRACK_CHANGED will re-fade from 0 anyway, but this
                // prevents a jarring moment if there's a gap).
                volumeController.setVolume(fromVolume)
                savedUserVolume = -1
                Log.d(TAG, "Fade-out complete, volume restored to $fromVolume")
            }
        }
    }

    private fun cancelAllFades(restoreVolume: Boolean) {
        fadeInJob?.cancel()
        fadeOutSchedulerJob?.cancel()
        fadeOutJob?.cancel()

        if (restoreVolume && savedUserVolume >= 0) {
            volumeController.setVolume(savedUserVolume)
            Log.d(TAG, "Fades cancelled, volume restored to $savedUserVolume")
            savedUserVolume = -1
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MusicFader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MusicFader is running in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FaderService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FaderService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val status = if (prefs.isEnabled) "Active – fading enabled" else "Standby – fading paused"
        val toggleLabel = if (prefs.isEnabled) "Pause" else "Resume"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MusicFader")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "FaderService"
        private const val CHANNEL_ID = "musicfader_channel"
        private const val NOTIFICATION_ID = 1001

        /** Extra buffer before the nominal end of track to avoid cutting off. */
        private const val FADE_OUT_HEADROOM_MS = 500L

        const val ACTION_TOGGLE = "com.musicfader.app.TOGGLE"
        const val ACTION_STOP_SERVICE = "com.musicfader.app.STOP_SERVICE"
        const val ACTION_STATE_CHANGED = "com.musicfader.app.STATE_CHANGED"
    }
}
