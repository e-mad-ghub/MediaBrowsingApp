package com.example.generalmediabrowser.media

import android.service.notification.NotificationListenerService

/**
 * Marker service to grant the app access to observe active media sessions.
 * The app relies on this service being enabled by the user in notification access settings.
 */
class MediaSessionListenerService : NotificationListenerService()
