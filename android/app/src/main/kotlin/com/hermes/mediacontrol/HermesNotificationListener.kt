package com.hermes.mediacontrol

import android.content.Intent
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat

/**
 * Stub NotificationListenerService. Its only job is to give us a valid
 * ComponentName that satisfies MediaSessionManager.getActiveSessions().
 * Android requires this service to be declared and granted by the user via
 * Settings > Apps > Special app access > Notification access before the
 * MediaSessionManager will return active sessions.
 *
 * onListenerConnected fires both on first grant and after process death/restart,
 * making it the correct place to start MediaBridgeService rather than requiring
 * a manual button tap in MainActivity.
 */
class HermesNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        ContextCompat.startForegroundService(this, Intent(this, MediaBridgeService::class.java))
        ServiceStateHolder.isRunning = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopService(Intent(this, MediaBridgeService::class.java))
        ServiceStateHolder.isRunning = false
    }
}
