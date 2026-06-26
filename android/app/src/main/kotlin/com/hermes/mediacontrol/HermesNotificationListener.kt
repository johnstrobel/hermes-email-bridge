package com.hermes.mediacontrol

import android.service.notification.NotificationListenerService

/**
 * Stub NotificationListenerService. Its only job is to give us a valid
 * ComponentName that satisfies MediaSessionManager.getActiveSessions().
 * Android requires this service to be declared and granted by the user via
 * Settings > Apps > Special app access > Notification access before the
 * MediaSessionManager will return active sessions.
 *
 * No notification interception logic lives here — we only need the
 * permission, not the notification stream.
 */
class HermesNotificationListener : NotificationListenerService()
