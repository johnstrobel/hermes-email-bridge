╭─── Claude Code v2.1.187 ─────────────────────────────────────────────────────╮
│                                                    │ Tips for getting        │
│                    Welcome back!                   │ started                 │
│                                                    │ Run /init to create a … │
│                       ▐▛███▜▌                      │ ─────────────────────── │
│                      ▝▜█████▛▘                     │ What's new              │
│                        ▘▘ ▝▝                       │ Added `/rewind` suppor… │
│       Sonnet 4.6 · Claude Pro ·                    │ Fixed scroll position … │
│       johnstrobel72@gmail.com's Organization       │ Fixed background agent… │
│               ~/hermes-email-bridge                │ /release-notes for more │
╰──────────────────────────────────────────────────────────────────────────────╯


❯ <<<                                                                           

● It looks like your message got cut off — you sent <<< without any content.
  What were you trying to say?

✻ Sautéed for 3s

❯ # Role & Context                                                              
  You are an expert mobile developer specializing in building lightweight       
  utility plugins for smart glasses and wearable ecosystems. You have deep      
  experience with Bluetooth Low Energy (BLE) communication, Android/iOS Media   
  Session APIs, Network Connectivity APIs, and background services.             
                                                                                
  We are designing a custom background media controller plugin for the Even     
  Realities G2 smart glasses (via the Even Hub/SDK framework) and its paired    
  companion smartphone app.                                                     
                                                                                
  # Project Objective                                                           
  Create a background-running media control app/plugin that maps physical touch 
  interactions from the G2 glasses (and by extension, the Even R1 smart ring    
  via TriSync) to control a smartphone's active media playback (Play, Pause,    
  Skip, Volume, Mute). The app must act as a lightweight BLE bridge without     
  interfering with native OS commands when inactive, while displaying critical  
  phone telemetry (battery and network status) on the HUD.                      
                                                                                
  ---                                                                           
                                                                                
  # System Architecture & Technical Requirements                                
                                                                                
  ### 1. Smartphone Companion App (Background Daemon)                           
  - **Permissions Required:** Notification Listener Service, Media Session      
  Access, Access Network State, Background Execution (ignoring battery          
  optimization).                                                                
  - **Core Logic:** - Listen for an active Android `MediaSession` or iOS        
  `MPNowPlayingInfoCenter` (Spotify, YouTube, Podcasts, etc.).                  
    - Listen for system battery changes and network connectivity state changes  
  (Wi-Fi, Cellular, Offline).                                                   
    - Maintain a persistent BLE connection to the G2 glasses.                   
    - Receive gesture data packets from the glasses and instantly map them to   
  system media key events (e.g., `KEYCODE_MEDIA_PLAY_PAUSE`).                   
                                                                                
  ### 2. G2 Glasses Plugin (Even Hub Layer)                                     
  - **UI/HUD Requirements:** A hyper-minimalist, monochrome text overlay that   
  activates briefly when a track changes, media state flips, or status changes: 
    - Line 1: `[ ▶ ] Track Name - Artist` (or `[ ‖ ] Paused` / `[ ↻ ]           
  Buffering...`)                                                                
    - Line 2: `Vol: 70% | 📱 84% 👓 65% | 📶 LTE` (Supports: `Wi-Fi`, `LTE`,    
  `5G`, `No Net`)                                                               
    - Timeout: The HUD should dismiss itself after 3 seconds of inactivity to   
  keep the display clean.                                                       
  - **Input Interception:** When the "Media Control" module is actively focused 
  on the glasses, it overrides default scrolling behavior to process media      
  commands.                                                                     
                                                                                
  ---                                                                           
                                                                                
  # Gesture Mapping Configuration                                               
                                                                                
  | Hardware Input | Native Action Override | Media App Converted Action |      
  | :--- | :--- | :--- |                                                        
  | **Single Tap** | Select / Confirm | **Play / Pause** toggle |               
  | **Double Tap** | Open Dashboard / Back | **Skip Forward** (Next Track) |    
  | **Swipe Forward** | Scroll Up | **Volume Up** |                             
  | **Swipe Backward** | Scroll Down | **Volume Down** |                        
  | **Long Press** | AI Assistant / Main Menu | **Mute / Unmute** Audio |       
                                                                                
  ---                                                                           
                                                                                
  # Your Task                                                                   
                                                                                
  1. **Architecture Blueprint:** Outline the code structure for both the        
  smartphone background service and the G2 plugin framework. Provide code       
  boilerplate snippets (Kotlin/Swift for mobile, or the relevant JavaScript/C   
  standard used in modern smart glass SDKs) demonstrating how to register the   
  BLE listener, broadcast system states, and map the touch inputs.              
  2. **Edge-Case Handling:** Write logic or describe strategies for handling    
  conflicts (e.g., what happens if multiple media apps are open, or if the user 
  receives a phone call).                                                       
                                                                                
  ### 🔍 Crucial: Critical Analysis & Insights                                  
  Before you begin writing the code, or alongside it, please analyze this       
  concept and answer the following questions to catch blind spots we might have 
  missed:                                                                       
  - **Input Conflict:** How do we cleanly exit this media control mode on the   
  glasses so the user can use their regular G2 dashboard/menus without          
  accidentally pausing their music? Should we implement a specific gesture      
  (like a triple tap) to lock/unlock the media mapping?                         
  - **Battery & Network Polling Efficiency:** To display the phone/glasses      
  battery levels and network changes on the HUD without causing massive battery 
  drain from constant BLE polling, what is the optimal update frequency or      
  event-driven strategy?                                                        
  - **Connection State Triggers:** How should the HUD handle a sudden drop in   
  the BLE media bridge connection itself? Should it display a visual toast like 
  `[ ! ] Disconnected` on the glasses, and how do we ensure an automated        
  reconnect handshake occurs seamlessly?                                        
  - **Network State Serialization:** What is the cleanest way to serialize the  
  network type (Wi-Fi vs Cellular vs Offline) into a tiny 1-byte payload to     
  minimize BLE airtime overhead?                                                

● Let me look at what's already been built in this project before designing
  anything new.

  Searched for 1 pattern, read 3 files, recalled 1 memory (ctrl+o to expand)

● Good — I have the full picture now. This project already has the Even Hub SDK
  plugin pattern working (waitForEvenAppBridge, textContainerUpgrade,
  onEvenHubEvent) and a Python HTTP server that the plugin polls. The media
  controller fits naturally into the same architecture. Let me address the
  critical analysis questions first, then write the blueprints.

  ---
  Critical Analysis

  Input Conflict: Mode Entry/Exit

  The isEventCapture: 1 flag in the container already claims gestures while the
  plugin is focused. The real problem is re-entry: if the user navigates away to
  another G2 app and back, capture resumes with no mode indicator. Three
  strategies ranked by UX cost:

  1. Auto-timeout lock (recommended): if no gesture occurs within a configurable
  idle window (e.g., 30s), the plugin transitions from "intercept mode" to
  "passive display mode" — it still shows the HUD but passes gestures through. A
  brief [ CTRL OFF ] toast signals the transition. Re-engaging requires a
  single tap while the passive HUD is visible.
  2. Triple-tap toggle: The SDK exposes CLICK_EVENT and DOUBLE_CLICK_EVENT.
  Triple-tap is not a native event type, but you can implement it in software by
  counting single taps within a 400ms window and suppressing the action until
  the window closes. Adds ~400ms latency to all single taps — acceptable for
  media but annoying.
  3. Long-press exit: Map long-press to "toggle intercept mode" instead of mute,
  and add a separate volume-long-press combo for mute. Cleanest dedicated exit
  with no latency tradeoff.

  Recommended: option 1 + option 3 together. The auto-timeout handles the common
  "walk away" case; long press gives a deliberate exit.

  ---
  Battery & Network Polling Efficiency

  Do not poll at a fixed interval. Use a purely event-driven model:

  - Phone battery: Register BatteryManager.ACTION_BATTERY_CHANGED once as a
  sticky broadcast. Android only fires this when level actually changes
  (typically 1% increments). Zero polling overhead.
  - Network state: ConnectivityManager.registerNetworkCallback() fires on
  transitions only. Don't call getActiveNetwork() on a timer.
  - Glasses battery: The Even Hub SDK fires an event when battery state changes
  — subscribe via onEvenHubEvent and check for a battery event type rather than
  polling the BLE characteristic.
  - Coalescing: Batch all three state fields into one outbound packet and
  debounce updates with a 500ms window. Three rapid state changes (network drop
  + reconnect + battery tick) send a single payload instead of three.
  - Send trigger: Only push to the HUD on actual delta — compare new state
  against last-sent state before transmitting.

  Result: BLE airtime for status is measured in packets per hour, not per
  minute.

  ---
  Connection State Triggers

  The G2 plugin side detects loss passively: when fetch() to the phone HTTP
  server fails (or times out), the plugin knows the bridge is down. It should:
  1. Immediately render [ ! ] Bridge Offline in the HUD container
  2. Enter an exponential-backoff retry loop (1s → 2s → 4s → 8s → cap at 30s)
  3. On successful reconnect, restore the last known media state and display [ ✓
  ] Reconnected for 2s

  On the Android side, if this is HTTP (not raw BLE GATT), there's no connection
  state to manage — the server just restarts if it crashes. If you ever move to
  raw BLE GATT, use BluetoothGatt.connect(device, autoConnect = true) and
  implement onConnectionStateChange with the same backoff pattern.

  ---
  Network State Serialization (1-byte encoding)

  Bit layout of a single byte:
    [7..4] = type  [3..0] = signal strength (0–15, optional, set to 0 if unused)

  0x00  Offline / No connectivity
  0x10  Wi-Fi (any strength)
  0x20  LTE / 4G
  0x30  5G (NR)
  0x40  3G / HSPA
  0x50  2G / EDGE
  0x60  Ethernet (tablet/dock mode)
  0x7F  Unknown

  Upper nibble = connection type (6 useful states, 10 reserved for future).
  Lower nibble = RSSI bucket if you ever want signal bars. For now zero-fill it.
  The entire battery+network+volume status fits in 3 bytes:
  [phone_battery_0–100][glasses_battery_0–100][network_byte].

  ---
  Architecture Blueprint

  The cleanest approach extends your existing HTTP polling pattern rather than
  introducing raw BLE GATT from scratch. The G2 plugin already polls an HTTP
  endpoint; adding POST routes for gesture commands keeps the architecture
  uniform and avoids Android BLE GATT server complexity.

  G2 Plugin (TypeScript)          Phone Companion (Android/Kotlin)
  ┌─────────────────────┐         ┌──────────────────────────────┐
  │  Gesture Intercept  │──POST──▶│  /media/command              │
  │  HUD Renderer       │◀─GET───│  /media/status               │
  │  Poll loop (3s)     │         │  MediaSessionManager         │
  └─────────────────────┘         │  BatteryManager broadcast    │
                                   │  ConnectivityManager callback│
                                   └──────────────────────────────┘

  ---
  Android Companion Service (Kotlin)

  MediaBridgeService.kt

  @AndroidEntryPoint
  class MediaBridgeService : Service() {

      private lateinit var mediaSessionManager: MediaSessionManager
      private lateinit var connectivityManager: ConnectivityManager
      private var activeController: MediaController? = null
      private val httpServer = MediaBridgeHttpServer(port = 5051, onCommand =
  ::handleCommand)

      // Shared volatile state — written by callbacks, read by HTTP server
      @Volatile var mediaState = MediaState()
      @Volatile var phoneStatus = PhoneStatus()

      // Debounce: don't spam BLE/HTTP updates on rapid state changes
      private val updateDebounce = Handler(Looper.getMainLooper())
      private val pushUpdate = Runnable { /* status is read on-demand; no push 
  needed */ }

      override fun onCreate() {
          super.onCreate()
          startForeground(NOTIF_ID, buildNotification())
          httpServer.start()
          registerBatteryReceiver()
          registerNetworkCallback()
          attachMediaSession()
      }

      // ── Battery 
  ──────────────────────────────────────────────────────────────

      private fun registerBatteryReceiver() {
          registerReceiver(object : BroadcastReceiver() {
              override fun onReceive(ctx: Context, intent: Intent) {
                  val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                  val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE,
  100)
                  phoneStatus = phoneStatus.copy(battery = (level * 100 /
  scale).coerceIn(0, 100))
              }
          }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      }

      // ── Network 
  ──────────────────────────────────────────────────────────────

      private fun registerNetworkCallback() {
          val request = NetworkRequest.Builder()
              .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
              .build()

          connectivityManager.registerNetworkCallback(request, object :
  ConnectivityManager.NetworkCallback() {
              override fun onCapabilitiesChanged(network: Network, caps: 
  NetworkCapabilities) {
                  phoneStatus = phoneStatus.copy(networkByte =
  caps.toNetworkByte())
              }
              override fun onLost(network: Network) {
                  phoneStatus = phoneStatus.copy(networkByte = NET_OFFLINE)
              }
          })
      }

      private fun NetworkCapabilities.toNetworkByte(): Byte = when {
          hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> 0x10
          hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
  resolveCellularGeneration()
          hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0x60
          else                                                  -> 0x00
      }

      private fun resolveCellularGeneration(): Byte {
          val tm = getSystemService(TelephonyManager::class.java)
          return when (tm.dataNetworkType) {
              TelephonyManager.NETWORK_TYPE_NR            -> 0x30  // 5G
              TelephonyManager.NETWORK_TYPE_LTE           -> 0x20  // LTE
              TelephonyManager.NETWORK_TYPE_HSPAP,
              TelephonyManager.NETWORK_TYPE_HSPA          -> 0x40  // 3G
              else                                        -> 0x50  // 2G / Edge
          }
      }

      // ── MediaSession 
  ─────────────────────────────────────────────────────────

      private fun attachMediaSession() {
          mediaSessionManager =
  getSystemService(MediaSessionManager::class.java)
          val component = ComponentName(this,
  NotificationListenerService::class.java)

          fun pickController(): MediaController? =
              mediaSessionManager.getActiveSessions(component)
                  .firstOrNull { it.playbackState != null }

          activeController = pickController()
          activeController?.registerCallback(mediaCallback)

          // Re-attach when active sessions change (e.g., Spotify opens after 
  YouTube)
          mediaSessionManager.addOnActiveSessionsChangedListener({ controllers
  ->
              activeController?.unregisterCallback(mediaCallback)
              activeController = controllers?.firstOrNull { it.playbackState !=
  null }
              activeController?.registerCallback(mediaCallback)
              syncMediaState()
          }, component)
      }

      private val mediaCallback = object : MediaController.Callback() {
          override fun onPlaybackStateChanged(state: PlaybackState?) =
  syncMediaState()
          override fun onMetadataChanged(metadata: MediaMetadata?)   =
  syncMediaState()
      }

      private fun syncMediaState() {
          val ctrl = activeController ?: return
          val meta  = ctrl.metadata
          val state = ctrl.playbackState

          mediaState = MediaState(
              playing   = state?.state == PlaybackState.STATE_PLAYING,
              buffering = state?.state == PlaybackState.STATE_BUFFERING,
              track     = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)  ?:
  "",
              artist    = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?:
  "",
              volume    = currentVolumePercent(),
          )
      }

      private fun currentVolumePercent(): Int {
          val am = getSystemService(AudioManager::class.java)
          val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
          return if (max > 0) cur * 100 / max else 0
      }

      // ── Command Dispatch 
  ──────────────────────────────────────────────────────

      private fun handleCommand(cmd: String) {
          val am = getSystemService(AudioManager::class.java)
          when (cmd) {
              "play_pause"  -> dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
              "next"        -> dispatchKey(KeyEvent.KEYCODE_MEDIA_NEXT)
              "vol_up"      -> am.adjustStreamVolume(
                                  AudioManager.STREAM_MUSIC,
                                  AudioManager.ADJUST_RAISE,
                                  AudioManager.FLAG_SHOW_UI)
              "vol_down"    -> am.adjustStreamVolume(
                                  AudioManager.STREAM_MUSIC,
                                  AudioManager.ADJUST_LOWER,
                                  AudioManager.FLAG_SHOW_UI)
              "mute_toggle" -> am.adjustStreamVolume(
                                  AudioManager.STREAM_MUSIC,
                                  AudioManager.ADJUST_TOGGLE_MUTE,
                                  0)
          }
      }

      private fun dispatchKey(keyCode: Int) {
          val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
          val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
          activeController?.dispatchMediaButtonEvent(down)
              ?: run {
                  // Fallback: global broadcast (works even without an active 
  MediaSession)
                  val am = getSystemService(AudioManager::class.java)
                  am.dispatchMediaKeyEvent(down)
                  am.dispatchMediaKeyEvent(up)
              }
      }

      override fun onBind(intent: Intent?): IBinder? = null

      companion object {
          const val NOTIF_ID  = 1
          const val NET_OFFLINE: Byte = 0x00
      }
  }

  MediaBridgeHttpServer.kt

  class MediaBridgeHttpServer(
      private val port: Int,
      private val onCommand: (String) -> Unit,
  ) : NanoHTTPD(port) {

      // Injected by service after construction
      var getState: (() -> Pair<MediaState, PhoneStatus>)? = null

      override fun serve(session: IHTTPSession): Response {
          return when {
              session.method == Method.GET  && session.uri == "/media/status"
  -> handleStatus()
              session.method == Method.POST && session.uri == "/media/command"
  -> handleCommand(session)
              else -> newFixedLengthResponse(Response.Status.NOT_FOUND,
  MIME_PLAINTEXT, "")
          }
      }

      private fun handleStatus(): Response {
          val (media, phone) = getState?.invoke() ?: return serverError()
          val body = JSONObject().apply {
              put("playing",  media.playing)
              put("buffering",media.buffering)
              put("track",    media.track)
              put("artist",   media.artist)
              put("volume",   media.volume)
              put("phoneBat", phone.battery)
              put("netByte",  phone.networkByte.toInt() and 0xFF)
          }.toString()
          return newFixedLengthResponse(Response.Status.OK, "application/json",
  body).apply {
              addHeader("Access-Control-Allow-Origin", "*")
          }
      }

      private fun handleCommand(session: IHTTPSession): Response {
          val body = HashMap<String, String>()
          session.parseBody(body)
          val cmd = JSONObject(body["postData"] ?: "{}").optString("cmd")
          if (cmd.isNotEmpty()) onCommand(cmd)
          return newFixedLengthResponse(Response.Status.OK, "application/json",
  "{\"ok\":true}")
      }
  }

  AndroidManifest.xml additions

  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <uses-permission android:name="android.permission.READ_PHONE_STATE" />
  <uses-permission 
  android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

  <service
      android:name=".MediaBridgeService"
      android:foregroundServiceType="mediaPlayback"
      android:exported="false" />

  <service
      android:name=".HermesNotificationListener"
      android:exported="true"
      
  android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
      <intent-filter>
          <action 
  android:name="android.service.notification.NotificationListenerService" />
      </intent-filter>
  </service>

  ---
  G2 Plugin — Media Controller (TypeScript)

  This is a second Even Hub plugin alongside Hermes. It lives in its own
  app.json with a separate package_id.

  import {
    waitForEvenAppBridge,
    TextContainerProperty,
    TextContainerUpgrade,
    CreateStartUpPageContainer,
    OsEventTypeList,
  } from '@evenrealities/even_hub_sdk'

  // ─── Config ────────────────────────────────────────────────────────────────

  const PHONE_API   = 'http://10.0.0.117:5051'
  const POLL_MS     = 3_000   // status poll interval
  const HUD_TTL_MS  = 3_000   // HUD auto-dismiss timeout
  const IDLE_TTL_MS = 30_000  // auto-exit intercept mode if no gestures

  // ─── Types ─────────────────────────────────────────────────────────────────

  interface MediaStatus {
    playing:   boolean
    buffering: boolean
    track:     string
    artist:    string
    volume:    number
    phoneBat:  number
    netByte:   number
  }

  type InterceptMode = 'active' | 'passive'  // active = gestures captured, 
  passive = display only

  // ─── Network byte → label ──────────────────────────────────────────────────

  function netLabel(byte: number): string {
    const type = byte & 0xF0
    if (type === 0x00) return 'No Net'
    if (type === 0x10) return 'Wi-Fi'
    if (type === 0x20) return 'LTE'
    if (type === 0x30) return '5G'
    if (type === 0x40) return '3G'
    if (type === 0x50) return 'Edge'
    return '?'
  }

  // ─── HUD builder ───────────────────────────────────────────────────────────

  function buildHUD(s: MediaStatus, mode: InterceptMode, disconnected: boolean):
  string {
    if (disconnected) return '[ ! ] Bridge Offline\nRetrying...'

    const statusIcon = s.buffering ? '[ ↻ ]' : s.playing ? '[ ▶ ]' : '[ ‖ ]'
    const trackLine  = s.playing || s.buffering
      ? `${statusIcon} ${s.track} - ${s.artist}`
      : `${statusIcon} Paused`

    const net        = netLabel(s.netByte)
    const modeTag    = mode === 'active' ? '●' : '○'
    const statusLine = `Vol: ${s.volume}% | 📱${s.phoneBat}% | ${net}
  ${modeTag}`

    return `${trackLine}\n${statusLine}`
  }

  // ─── Main ──────────────────────────────────────────────────────────────────

  ;(async () => {
    const bridge = await waitForEvenAppBridge()

    await bridge.createStartUpPageContainer(
      new CreateStartUpPageContainer({
        containerTotalNum: 1,
        textObject: [
          new TextContainerProperty({
            xPosition:     0,
            yPosition:     0,
            width:         576,
            height:        288,
            borderWidth:   0,
            borderColor:   5,
            paddingLength: 4,
            containerID:   2,
            containerName: 'media',
            content:       '[ ▶ ] Media\nConnecting...',
            isEventCapture: 1,
          }),
        ],
      })
    )

    // ── State ────────────────────────────────────────────────────────────────

    let lastStatus: MediaStatus | null = null
    let interceptMode: InterceptMode   = 'active'
    let disconnected                   = false
    let retryDelay                     = 1_000

    let hudTimer:   ReturnType<typeof setTimeout> | null = null
    let idleTimer:  ReturnType<typeof setTimeout> | null = null

    // ── HUD management ───────────────────────────────────────────────────────

    async function showHUD(s: MediaStatus): Promise<void> {
      const content = buildHUD(s, interceptMode, false)
      await bridge.textContainerUpgrade(
        new TextContainerUpgrade({ containerID: 2, containerName: 'media',
  content })
      )
      // Auto-dismiss after TTL
      if (hudTimer) clearTimeout(hudTimer)
      hudTimer = setTimeout(() => dismissHUD(), HUD_TTL_MS)
    }

    async function dismissHUD(): Promise<void> {
      await bridge.textContainerUpgrade(
        new TextContainerUpgrade({ containerID: 2, containerName: 'media',
  content: '' })
      )
    }

    function resetIdleTimer(): void {
      if (idleTimer) clearTimeout(idleTimer)
      idleTimer = setTimeout(() => {
        interceptMode = 'passive'
        // Show brief mode indicator
        if (lastStatus) showHUD(lastStatus)
      }, IDLE_TTL_MS)
    }

    // ── Poll loop with reconnect 
  ──────────────────────────────────────────────

    async function poll(): Promise<void> {
      try {
        const res = await fetch(`${PHONE_API}/media/status`, { signal:
  AbortSignal.timeout(2_500) })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)

        const s = await res.json() as MediaStatus
        const changed = !lastStatus
          || lastStatus.playing   !== s.playing
          || lastStatus.buffering !== s.buffering
          || lastStatus.track     !== s.track
          || lastStatus.volume    !== s.volume
          || lastStatus.phoneBat  !== s.phoneBat
          || lastStatus.netByte   !== s.netByte

        if (disconnected) {
          disconnected = false
          retryDelay   = 1_000
          // Show reconnect toast briefly
          await bridge.textContainerUpgrade(
            new TextContainerUpgrade({ containerID: 2, containerName: 'media',
  content: '[ ✓ ] Reconnected' })
          )
          setTimeout(() => lastStatus && showHUD(lastStatus), 2_000)
        }

        if (changed) {
          lastStatus = s
          await showHUD(s)
        }

        setTimeout(poll, POLL_MS)
      } catch {
        if (!disconnected) {
          disconnected = true
          await bridge.textContainerUpgrade(
            new TextContainerUpgrade({ containerID: 2, containerName: 'media',
  content: '[ ! ] Bridge Offline\nRetrying...' })
          )
        }
        retryDelay = Math.min(retryDelay * 2, 30_000)
        setTimeout(poll, retryDelay)
      }
    }

    // ── Command sender 
  ────────────────────────────────────────────────────────

    async function sendCommand(cmd: string): Promise<void> {
      try {
        await fetch(`${PHONE_API}/media/command`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body:    JSON.stringify({ cmd }),
          signal:  AbortSignal.timeout(1_000),
        })
      } catch {
        // Best-effort; poll loop will detect disconnect if sustained
      }
    }

    // ── Gesture handling 
  ──────────────────────────────────────────────────────

    // Software triple-tap: count single taps in a 400ms window
    let tapCount = 0
    let tapTimer: ReturnType<typeof setTimeout> | null = null

    function handleSingleTap(): void {
      tapCount++
      if (tapTimer) clearTimeout(tapTimer)
      tapTimer = setTimeout(async () => {
        const count = tapCount
        tapCount = 0
        if (count >= 3) {
          // Triple tap: toggle intercept mode
          interceptMode = interceptMode === 'active' ? 'passive' : 'active'
          if (lastStatus) await showHUD(lastStatus)
        } else if (count === 1 && interceptMode === 'active') {
          await sendCommand('play_pause')
          resetIdleTimer()
        }
        // count === 2 falls through — handled by DOUBLE_CLICK_EVENT below
      }, 400)
    }

    bridge.onEvenHubEvent(async event => {
      const sysType  = event.sysEvent?.eventType  ?? null
      const textType = event.textEvent?.eventType ?? null
      const type     = sysType ?? textType

      switch (type) {
        case OsEventTypeList.CLICK_EVENT:
          handleSingleTap()
          break

        case OsEventTypeList.DOUBLE_CLICK_EVENT:
          if (interceptMode === 'active') {
            tapCount = 0  // cancel any pending triple-tap window
            if (tapTimer) clearTimeout(tapTimer)
            await sendCommand('next')
            resetIdleTimer()
          } else {
            // Passive mode: double tap exits the plugin back to G2 dashboard
            bridge.shutDownPageContainer(2)
          }
          break

        case OsEventTypeList.LONG_PRESS_EVENT:
          if (interceptMode === 'active') {
            await sendCommand('mute_toggle')
            resetIdleTimer()
          }
          break

        case OsEventTypeList.SWIPE_FORWARD_EVENT:
          if (interceptMode === 'active') {
            await sendCommand('vol_up')
            resetIdleTimer()
          }
          break

        case OsEventTypeList.SWIPE_BACKWARD_EVENT:
          if (interceptMode === 'active') {
            await sendCommand('vol_down')
            resetIdleTimer()
          }
          break
      }
    })

    // Start polling
    poll()
    resetIdleTimer()
  })()

  ---
  Edge Case Handling

  Multiple Active Media Apps

  getActiveSessions() returns controllers sorted by recency. The service picks
  firstOrNull { it.playbackState != null } — the most recently active app with a
  non-null playback state wins. When a phone call starts,
  AudioManager.AudioFocusRequest will duck/suspend music; the MediaSession state
  transitions to STATE_PAUSED, which the callback catches immediately and
  pushes to the HUD as [ ‖ ] Paused.

  // Re-attachment handles the "Spotify pauses, Phone app takes focus" case 
  automatically:
  mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
      activeController?.unregisterCallback(mediaCallback)
      // controllers is sorted: most recently active first
      activeController = controllers?.firstOrNull { it.playbackState != null }
      activeController?.registerCallback(mediaCallback)
      syncMediaState()
  }, component)

  Phone Call Interception

  Register an additional TelephonyCallback (API 31+) or PhoneStateListener
  (legacy):

  val tm = getSystemService(TelephonyManager::class.java)
  tm.registerTelephonyCallback(mainExecutor, object : TelephonyCallback(),
  TelephonyCallback.CallStateListener {
      override fun onCallStateChanged(state: Int) {
          when (state) {
              TelephonyManager.CALL_STATE_RINGING,
              TelephonyManager.CALL_STATE_OFFHOOK -> {
                  // Suspend media command dispatch during call
                  mediaState = mediaState.copy(inCall = true)
              }
              TelephonyManager.CALL_STATE_IDLE -> {
                  mediaState = mediaState.copy(inCall = false)
                  syncMediaState()  // re-sync after call ends
              }
          }
      }
  })

  On the G2 side, check inCall in the status response and render [ ☎ ] On Call
  instead of track info. Suppress all media commands while inCall is true — the
  plugin drops the gesture silently to avoid accidentally ending a call.

  Volume Clamping

  ADJUST_RAISE on Android clamps at max volume silently. No special handling
  needed, but echo the actual post-adjustment volume back in the next
  /media/status poll so the HUD reflects reality rather than assuming the
  adjustment succeeded.

  ---
  Summary of what to build: two artifacts — an Android foreground service
  (MediaBridgeService + MediaBridgeHttpServer using NanoHTTPD) that exposes two
  HTTP routes on port 5051, and a new Even Hub plugin (g2-plugin-media/) that
  follows the exact same TypeScript/Vite structure as the existing Hermes
  plugin. No BLE GATT plumbing required — the HTTP bridge the project already
  uses handles everything.

✻ Sautéed for 3m 44s

※ recap: Designing a media controller plugin for Even Realities G2 smart glasses that maps touch gestures to phone media controls and displays track/battery/network info on the HUD. Next step is implementing the Android companion service and G2 TypeScript plugin. (disable recaps in /config)