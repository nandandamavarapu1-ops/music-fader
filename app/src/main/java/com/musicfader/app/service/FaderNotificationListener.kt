package com.musicfader.app.service

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * NotificationListenerService subclass.
 *
 * The system grants this service access to MediaSessionManager.getActiveSessions()
 * once the user enables Notification Access for MusicFader in Settings.
 *
 * Responsibilities:
 *  - Watch all active MediaControllers for playback-state and metadata changes.
 *  - Forward "track changed" and "playback started/stopped" events to FaderService.
 */
class FaderNotificationListener : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())

    // Track which (packageName + mediaId) combo we last saw to detect actual track changes.
    private var lastTrackId: String = ""
    private var lastPlaybackState: Int = PlaybackState.STATE_NONE

    // Listener for the list of active sessions changing (new app starts playing, etc.)
    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.d(TAG, "Active sessions changed: ${controllers?.size ?: 0}")
            reattachControllers(controllers)
        }

    // We keep one callback per active MediaController.
    private val controllerCallbacks = mutableMapOf<String, MediaController.Callback>()
    private val activeControllers = mutableListOf<MediaController>()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        sessionManager =
            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

        val component = ComponentName(this, FaderNotificationListener::class.java)
        sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component, handler)

        // Bootstrap with whatever is already active.
        reattachControllers(sessionManager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected")
        detachAllControllers()
        if (::sessionManager.isInitialized) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
    }

    // -------------------------------------------------------------------------
    // Controller management
    // -------------------------------------------------------------------------

    private fun reattachControllers(controllers: List<MediaController>?) {
        detachAllControllers()
        controllers?.forEach { attachController(it) }
    }

    private fun attachController(controller: MediaController) {
        val pkg = controller.packageName
        val callback = object : MediaController.Callback() {

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val newState = state?.state ?: PlaybackState.STATE_NONE
                Log.d(TAG, "[$pkg] Playback state → $newState")

                if (newState == PlaybackState.STATE_PLAYING &&
                    lastPlaybackState != PlaybackState.STATE_PLAYING
                ) {
                    // Playback resumed (could be same track un-paused or new track).
                    notifyFaderService(ACTION_PLAYBACK_STARTED)
                } else if (newState == PlaybackState.STATE_PAUSED ||
                    newState == PlaybackState.STATE_STOPPED
                ) {
                    notifyFaderService(ACTION_PLAYBACK_STOPPED)
                }
                lastPlaybackState = newState
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val trackId = buildTrackId(pkg, metadata)
                Log.d(TAG, "[$pkg] Metadata → $trackId")
                if (trackId != lastTrackId && trackId.isNotBlank()) {
                    lastTrackId = trackId
                    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                    notifyFaderService(ACTION_TRACK_CHANGED, duration)
                }
            }
        }

        controller.registerCallback(callback, handler)
        controllerCallbacks[pkg] = callback
        activeControllers.add(controller)
    }

    private fun detachAllControllers() {
        activeControllers.forEach { ctrl ->
            controllerCallbacks[ctrl.packageName]?.let { ctrl.unregisterCallback(it) }
        }
        controllerCallbacks.clear()
        activeControllers.clear()
    }

    // -------------------------------------------------------------------------
    // IPC to FaderService
    // -------------------------------------------------------------------------

    private fun notifyFaderService(action: String, durationMs: Long = -1L) {
        val intent = Intent(this, FaderService::class.java).apply {
            this.action = action
            if (durationMs > 0) putExtra(EXTRA_TRACK_DURATION_MS, durationMs)
        }
        startService(intent)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildTrackId(pkg: String, metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        return "$pkg|$artist|$title|$mediaId"
    }

    companion object {
        const val TAG = "FaderListener"

        const val ACTION_TRACK_CHANGED = "com.musicfader.app.TRACK_CHANGED"
        const val ACTION_PLAYBACK_STARTED = "com.musicfader.app.PLAYBACK_STARTED"
        const val ACTION_PLAYBACK_STOPPED = "com.musicfader.app.PLAYBACK_STOPPED"
        const val EXTRA_TRACK_DURATION_MS = "track_duration_ms"
    }
}
