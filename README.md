# MusicFader

Auto-fades volume in at the start and out near the end of every track, system-wide across any music app — Spotify, YouTube Music, JioSaavn, Apple Music, Pocket Casts, and more.

## How it works

| Component | Role |
|---|---|
| `FaderNotificationListener` | Subclass of `NotificationListenerService`. The system gives it access to `MediaSessionManager.getActiveSessions()` after the user enables Notification Access. It watches every active `MediaController` for metadata and playback-state changes, then forwards events to `FaderService` via explicit `startService()` calls. |
| `FaderService` | `LifecycleService` foreground service. Receives events and runs coroutine-based smooth fades on `AudioManager.STREAM_MUSIC`. Saves the user's current volume before touching it and restores it on fade-out. |
| `VolumeController` | Pure helper that wraps `AudioManager`. Runs 50-step interpolation over the requested duration via `delay()` in a coroutine — cancellable at any time. |
| `Prefs` | `SharedPreferences` wrapper — enabled flag, fade-in ms, fade-out ms. |
| `MainActivity` | Dark-themed single-screen UI: on/off toggle, two seek-bars (1–10 s), permission banner. |

## Build requirements

- Android Studio Hedgehog or newer (AGP 8.3+)
- Kotlin 1.9
- Min SDK 23 (Android 6.0), Target SDK 34

## Setup

1. Open the project in Android Studio.
2. Build and install on your device (`Run ▶`).
3. The yellow banner in the app will prompt you if Notification Access is not yet granted. Tap **Open Settings** and enable **MusicFader** in the Notification Access list.  
   > This is the only permission that cannot be auto-granted — Android requires the user to do it manually.
4. Flip the toggle on.
5. Play any song in any app. The volume will fade in over your chosen duration.

## Fade-out behaviour

Fade-out at track end only works when the media app reports a track duration in its `MediaMetadata` (`METADATA_KEY_DURATION`). Spotify, YouTube Music, and most local players do. Some streaming apps omit it; those tracks will get fade-in only.

## Notification actions

The persistent notification has two quick actions:

- **Pause / Resume** — toggles fading without killing the service (syncs back to the UI).
- **Stop** — cancels fades, restores volume, and stops the foreground service.

## Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Required to run a foreground service. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Foreground service type for media-related services on Android 14+. |
| `POST_NOTIFICATIONS` | Shows the persistent notification on Android 13+. |
| Notification Access (special) | Grants access to `MediaSessionManager.getActiveSessions()` so the app can observe any media app without being bundled with it. |

No internet, storage, microphone, or location permissions are needed.

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/musicfader/app/
│   ├── service/
│   │   ├── FaderNotificationListener.kt   # MediaSession watcher
│   │   └── FaderService.kt                # Foreground service + fade logic
│   ├── ui/
│   │   └── MainActivity.kt                # UI
│   └── util/
│       ├── Prefs.kt                       # SharedPreferences wrapper
│       └── VolumeController.kt            # AudioManager fade helper
└── res/
    ├── drawable/   ic_launcher, ic_notification, ic_warning
    ├── layout/     activity_main.xml
    └── values/     colors, strings, themes
```
