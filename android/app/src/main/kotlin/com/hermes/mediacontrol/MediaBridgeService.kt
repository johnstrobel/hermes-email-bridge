package com.hermes.mediacontrol

import android.app.*
import android.content.*
import android.media.*
import android.media.session.*
import android.net.*
import android.os.*
import android.telephony.*
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.hermes.mediacontrol.model.MediaState
import com.hermes.mediacontrol.model.PhoneStatus
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_EDGE
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_ETHERNET
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_LTE
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_OFFLINE
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_3G
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_5G
import com.hermes.mediacontrol.model.PhoneStatus.Companion.NET_WIFI

/**
 * Foreground service that acts as the bridge between the G2 glasses plugin
 * and the phone's media stack. Responsibilities:
 *
 *  1. Runs an embedded HTTP server (port 5051) with two routes:
 *       GET  /media/status  — G2 plugin polls this every 3s
 *       POST /media/command — G2 plugin calls this on gesture events
 *
 *  2. Tracks the active MediaSession and updates [mediaState] on every
 *     playback/metadata change via MediaController.Callback.
 *
 *  3. Tracks phone battery via a sticky BroadcastReceiver (zero-polling).
 *
 *  4. Tracks network connectivity via ConnectivityManager.NetworkCallback
 *     (event-driven, no polling).
 *
 *  5. Tracks call state via TelephonyCallback (API 31+) or the legacy
 *     PhoneStateListener, so media commands are suppressed during calls.
 *
 * Both [mediaState] and [phoneStatus] are @Volatile — written on whatever
 * thread the OS callback fires on, read by NanoHTTPD's worker thread.
 */
class MediaBridgeService : Service() {

    // ── System services ────────────────────────────────────────────────────────

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    // ── Shared state (volatile: written by callbacks, read by HTTP server) ─────

    @Volatile var mediaState  = MediaState()
    @Volatile var phoneStatus = PhoneStatus()

    // ── Active MediaController ─────────────────────────────────────────────────

    private var activeController: MediaController? = null

    // ── HTTP server ────────────────────────────────────────────────────────────

    private val httpServer = MediaBridgeHttpServer(PORT)

    // ── Registered listeners (kept for cleanup in onDestroy) ──────────────────

    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyCallback: Any? = null   // TelephonyCallback (API 31+) or PhoneStateListener

    // ── Handler for main-thread work (volume re-sync after commands) ───────────

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── ComponentName of our NotificationListenerService stub ─────────────────
    // Required by MediaSessionManager.getActiveSessions().

    private val listenerComponent by lazy {
        ComponentName(this, HermesNotificationListener::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        audioManager        = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        httpServer.getState  = { Pair(mediaState, phoneStatus) }
        httpServer.onCommand = ::handleCommand
        httpServer.start()

        registerBatteryReceiver()
        registerNetworkCallback()
        registerTelephonyCallback()
        attachMediaSessionListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // OS restarts service if killed; no command data needed

    override fun onDestroy() {
        super.onDestroy()
        httpServer.stop()
        batteryReceiver?.let { unregisterReceiver(it) }
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        activeController?.unregisterCallback(mediaControllerCallback)
        unregisterTelephonyCallback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Media Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description    = "Hermes G2 media control bridge"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Hermes Media Bridge")
            .setContentText("Listening for G2 media commands on :$PORT")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Battery — sticky broadcast, zero polling overhead
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    phoneStatus = phoneStatus.copy(
                        battery = (level * 100 / scale).coerceIn(0, 100)
                    )
                }
            }
        }
        // Registering with ACTION_BATTERY_CHANGED returns the current sticky intent
        // immediately, so we get an initial reading without a separate query.
        val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { batteryReceiver?.onReceive(this, it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network — event-driven via NetworkCallback
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                phoneStatus = phoneStatus.copy(networkByte = caps.toNetworkByte())
            }
            override fun onLost(network: Network) {
                phoneStatus = phoneStatus.copy(networkByte = NET_OFFLINE)
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun NetworkCapabilities.toNetworkByte(): Int = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NET_WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> resolveCellularGeneration()
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NET_ETHERNET
        else                                                  -> NET_OFFLINE
    }

    private fun resolveCellularGeneration(): Int {
        val tm = getSystemService(TelephonyManager::class.java)
        return when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR                 -> NET_5G
            TelephonyManager.NETWORK_TYPE_LTE                -> NET_LTE
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS               -> NET_3G
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_GSM                -> NET_EDGE
            else                                             -> NET_LTE  // conservative fallback
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Telephony — call state, API 31+ path with legacy fallback
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun registerTelephonyCallback() {
        try {
            val tm = getSystemService(TelephonyManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCallState(state)
                }
                telephonyCallback = cb
                tm.registerTelephonyCallback(mainExecutor, cb)
            } else {
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        onCallState(state)
                }
                telephonyCallback = listener
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: SecurityException) {
            // READ_PHONE_STATE not granted — call state monitoring disabled, service continues
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterTelephonyCallback() {
        val tm  = getSystemService(TelephonyManager::class.java)
        val cb  = telephonyCallback ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.unregisterTelephonyCallback(cb as TelephonyCallback)
        } else {
            tm.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    private fun onCallState(state: Int) {
        val inCall = state == TelephonyManager.CALL_STATE_RINGING ||
                     state == TelephonyManager.CALL_STATE_OFFHOOK
        mediaState = mediaState.copy(inCall = inCall)
        if (!inCall) {
            // Call ended — re-sync playback state in case music resumed
            mainHandler.post { syncMediaState() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaSession — track the most recently active session
    // ─────────────────────────────────────────────────────────────────────────

    private fun attachMediaSessionListener() {
        activeController = pickActiveController()
        activeController?.registerCallback(mediaControllerCallback)
        syncMediaState()

        // Re-attach whenever the active session set changes (e.g., Spotify opens after
        // a YouTube video ends, or the user switches apps).
        mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
            activeController?.unregisterCallback(mediaControllerCallback)
            // getActiveSessions() returns sessions sorted most-recently-active first.
            activeController = controllers?.firstOrNull { it.playbackState != null }
            activeController?.registerCallback(mediaControllerCallback)
            syncMediaState()
        }, listenerComponent)
    }

    private fun pickActiveController(): MediaController? =
        try {
            mediaSessionManager
                .getActiveSessions(listenerComponent)
                .firstOrNull { it.playbackState != null }
        } catch (e: SecurityException) {
            // Notification listener permission not yet granted by the user.
            // MediaBridgeService will still run; it just won't know which session is active
            // until the user grants access in Settings > Apps > Notification access.
            null
        }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = syncMediaState()
        override fun onMetadataChanged(metadata: MediaMetadata?)   = syncMediaState()
    }

    private fun syncMediaState() {
        val ctrl = activeController
        if (ctrl == null) {
            mediaState = mediaState.copy(
                playing = false, buffering = false, track = "", artist = ""
            )
            return
        }
        val state = ctrl.playbackState
        val meta  = ctrl.metadata
        mediaState = mediaState.copy(
            playing   = state?.state == PlaybackState.STATE_PLAYING,
            buffering = state?.state == PlaybackState.STATE_BUFFERING,
            track     = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: "",
            artist    = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            volume    = currentVolumePercent(),
        )
    }

    private fun currentVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (cur * 100 / max).coerceIn(0, 100) else 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command dispatch — called from NanoHTTPD worker thread
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCommand(cmd: String) {
        // Drop all media commands while a call is active to avoid accidents
        if (mediaState.inCall) return

        when (cmd) {
            "play_pause"  -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next"        -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "vol_up"      -> audioManager.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_RAISE,
                                AudioManager.FLAG_SHOW_UI)
            "vol_down"    -> audioManager.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI)
            "mute_toggle" -> audioManager.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_TOGGLE_MUTE,
                                0)
        }

        // Re-read actual volume after adjustment so the HUD reflects reality
        // (ADJUST_RAISE silently clamps at max — we want the real post-adjust value).
        mainHandler.post {
            mediaState = mediaState.copy(volume = currentVolumePercent())
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
        val ctrl = activeController
        if (ctrl != null) {
            // Preferred: target the specific session owning playback
            ctrl.dispatchMediaButtonEvent(down)
            ctrl.dispatchMediaButtonEvent(up)
        } else {
            // Fallback: global broadcast works for most players even without a session
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val PORT             = 5051
        const val NOTIF_CHANNEL_ID = "hermes_media_bridge"
        const val NOTIF_ID         = 1001
    }
}
