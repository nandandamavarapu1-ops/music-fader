# MusicFader ProGuard rules

# Keep NotificationListenerService subclass
-keep class com.musicfader.app.service.FaderNotificationListener { *; }

# Keep FaderService
-keep class com.musicfader.app.service.FaderService { *; }

# Keep data model classes if added later
-keepclassmembers class com.musicfader.app.** { *; }
