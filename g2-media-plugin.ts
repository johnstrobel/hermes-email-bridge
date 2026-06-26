import {
  waitForEvenAppBridge,
  TextContainerProperty,
  TextContainerUpgrade,
  CreateStartUpPageContainer,
  OsEventTypeList,
} from '@evenrealities/even_hub_sdk'

// ─── Configuration ────────────────────────────────────────────────────────────

const PHONE_API    = 'http://10.0.0.117:5051'
const POLL_MS      = 3_000   // status poll interval (ms)
const HUD_TTL_MS   = 3_000   // time before HUD auto-clears (ms)
const IDLE_TTL_MS  = 30_000  // time before intercept mode auto-releases (ms)
const TAP_WINDOW   = 400     // window to count taps for triple-tap detection (ms)
const CMD_TIMEOUT  = 1_000   // HTTP command request timeout (ms)
const POLL_TIMEOUT = 2_500   // HTTP status poll timeout (ms)
const CONTAINER_ID = 2       // must not collide with Hermes plugin (containerID 1)
const MAX_RETRY_MS = 30_000  // cap on exponential backoff delay (ms)

// ─── Types ────────────────────────────────────────────────────────────────────

interface MediaStatus {
  playing:   boolean
  buffering: boolean
  track:     string
  artist:    string
  volume:    number   // 0–100
  phoneBat:  number   // 0–100
  netByte:   number   // encoded per 1-byte scheme (see netLabel)
  inCall:    boolean
}

// 'active'  → gestures are intercepted and mapped to media commands
// 'passive' → HUD displays info but gestures pass through to G2 OS
type InterceptMode = 'active' | 'passive'

// ─── Network byte decoder ─────────────────────────────────────────────────────
//
// Upper nibble encodes connection type:
//   0x00 = Offline  0x10 = Wi-Fi  0x20 = LTE
//   0x30 = 5G       0x40 = 3G     0x50 = Edge
// Lower nibble reserved for signal strength (ignored here, zero-filled by phone).

function netLabel(byte: number): string {
  switch (byte & 0xF0) {
    case 0x10: return 'Wi-Fi'
    case 0x20: return 'LTE'
    case 0x30: return '5G'
    case 0x40: return '3G'
    case 0x50: return 'Edge'
    default:   return 'No Net'
  }
}

// ─── HUD content builder ──────────────────────────────────────────────────────

function buildHUD(s: MediaStatus, mode: InterceptMode): string {
  // Line 1: playback state
  let line1: string
  if (s.inCall) {
    line1 = '[ ☎ ] On Call'
  } else if (s.buffering) {
    line1 = '[ ↻ ] Buffering...'
  } else if (s.playing) {
    const track  = s.track  || 'Unknown Track'
    const artist = s.artist || 'Unknown Artist'
    line1 = `[ ▶ ] ${track} - ${artist}`
  } else {
    line1 = '[ ‖ ] Paused'
  }

  // Line 2: telemetry
  // mode indicator: filled circle = capturing gestures, open circle = passive
  const modeTag = mode === 'active' ? '●' : '○'
  const net     = netLabel(s.netByte)
  const line2   = `Vol: ${s.volume}% | 📱${s.phoneBat}% | ${net} ${modeTag}`

  return `${line1}\n${line2}`
}

function buildDisconnectedHUD(): string {
  return '[ ! ] Bridge Offline\nRetrying...'
}

function buildReconnectedHUD(): string {
  return '[ ✓ ] Reconnected'
}

function buildModeHUD(mode: InterceptMode): string {
  return mode === 'active'
    ? '[ ● ] Media Control ON\nTriple-tap to release'
    : '[ ○ ] Media Control OFF\nTriple-tap to capture'
}

// ─── Main ─────────────────────────────────────────────────────────────────────

;(async () => {
  console.log('media-plugin: start')

  const bridge = await waitForEvenAppBridge()
  console.log('media-plugin: bridge ready')

  const createResult = await bridge.createStartUpPageContainer(
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
          containerID:   CONTAINER_ID,
          containerName: 'media',
          content:       '[ ▶ ] Media\nConnecting...',
          isEventCapture: 1,
        }),
      ],
    })
  )
  console.log('media-plugin: container created', createResult)

  // ── Plugin state ────────────────────────────────────────────────────────────

  let lastStatus:    MediaStatus | null = null
  let interceptMode: InterceptMode      = 'active'
  let disconnected                      = false
  let retryDelay                        = 1_000

  let hudTimer:  ReturnType<typeof setTimeout> | null = null
  let idleTimer: ReturnType<typeof setTimeout> | null = null

  // Triple-tap detection state
  let tapCount = 0
  let tapTimer: ReturnType<typeof setTimeout> | null = null

  // ── HUD helpers ─────────────────────────────────────────────────────────────

  async function setHUD(content: string): Promise<void> {
    try {
      await bridge.textContainerUpgrade(
        new TextContainerUpgrade({
          containerID:   CONTAINER_ID,
          containerName: 'media',
          content,
        })
      )
    } catch (e) {
      console.log('media-plugin: HUD update failed', String(e))
    }
  }

  async function showStatusHUD(s: MediaStatus): Promise<void> {
    if (hudTimer) clearTimeout(hudTimer)
    await setHUD(buildHUD(s, interceptMode))
    hudTimer = setTimeout(() => setHUD(''), HUD_TTL_MS)
  }

  async function showToast(content: string, durationMs = 2_000): Promise<void> {
    if (hudTimer) clearTimeout(hudTimer)
    await setHUD(content)
    hudTimer = setTimeout(() => {
      // After toast, restore status HUD if we have state
      if (lastStatus) showStatusHUD(lastStatus)
      else setHUD('')
    }, durationMs)
  }

  // ── Idle timer ──────────────────────────────────────────────────────────────
  // If no gesture occurs within IDLE_TTL_MS, release intercept mode automatically
  // so the user can navigate the G2 OS without fighting media capture.

  function resetIdleTimer(): void {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(async () => {
      if (interceptMode === 'active') {
        interceptMode = 'passive'
        console.log('media-plugin: idle timeout, released to passive')
        await showToast(buildModeHUD('passive'))
      }
    }, IDLE_TTL_MS)
  }

  // ── Status polling with exponential backoff ──────────────────────────────────

  async function poll(): Promise<void> {
    try {
      const res = await fetch(`${PHONE_API}/media/status`, {
        signal: AbortSignal.timeout(POLL_TIMEOUT),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)

      const s = await res.json() as MediaStatus

      // Reconnect toast
      if (disconnected) {
        disconnected = false
        retryDelay   = 1_000
        console.log('media-plugin: reconnected')
        await showToast(buildReconnectedHUD(), 2_000)
      }

      // Only re-render if something actually changed
      if (hasChanged(lastStatus, s)) {
        lastStatus = s
        await showStatusHUD(s)
      }

      setTimeout(poll, POLL_MS)
    } catch (e) {
      console.log('media-plugin: poll error', String(e))

      if (!disconnected) {
        disconnected = true
        await setHUD(buildDisconnectedHUD())
        if (hudTimer) clearTimeout(hudTimer)
      }

      // Exponential backoff: 1s → 2s → 4s → … → 30s cap
      retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS)
      console.log(`media-plugin: retry in ${retryDelay}ms`)
      setTimeout(poll, retryDelay)
    }
  }

  function hasChanged(prev: MediaStatus | null, next: MediaStatus): boolean {
    if (!prev) return true
    return (
      prev.playing   !== next.playing   ||
      prev.buffering !== next.buffering ||
      prev.track     !== next.track     ||
      prev.artist    !== next.artist    ||
      prev.volume    !== next.volume    ||
      prev.phoneBat  !== next.phoneBat  ||
      prev.netByte   !== next.netByte   ||
      prev.inCall    !== next.inCall
    )
  }

  // ── Command dispatch ────────────────────────────────────────────────────────

  async function sendCommand(cmd: string): Promise<void> {
    if (disconnected) {
      console.log('media-plugin: skipping command, bridge offline')
      return
    }
    try {
      await fetch(`${PHONE_API}/media/command`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ cmd }),
        signal:  AbortSignal.timeout(CMD_TIMEOUT),
      })
      console.log('media-plugin: sent command', cmd)
    } catch (e) {
      console.log('media-plugin: command failed', cmd, String(e))
    }
  }

  // ── Triple-tap detection ────────────────────────────────────────────────────
  // Counts CLICK_EVENTs within TAP_WINDOW ms.
  // 1 tap  → play/pause  (in active mode)
  // 2 taps → handled separately by DOUBLE_CLICK_EVENT (OS fires this natively)
  // 3 taps → toggle intercept mode
  //
  // Note: when intercepting a triple-tap, the action fires after TAP_WINDOW ms,
  // introducing a ~400ms delay on all single-tap actions. This is acceptable for
  // media control and is the cleanest way to carve out a dedicated mode-toggle
  // gesture without requiring a dedicated hardware button.

  function handleTap(): void {
    tapCount++
    if (tapTimer) clearTimeout(tapTimer)

    tapTimer = setTimeout(async () => {
      const count = tapCount
      tapCount = 0

      if (count >= 3) {
        // Toggle intercept mode
        interceptMode = interceptMode === 'active' ? 'passive' : 'active'
        console.log('media-plugin: mode toggled to', interceptMode)
        await showToast(buildModeHUD(interceptMode))
        if (interceptMode === 'active') resetIdleTimer()
      } else if (count === 1 && interceptMode === 'active') {
        await sendCommand('play_pause')
        resetIdleTimer()
      }
      // count === 2 is a double-tap — the OS fires DOUBLE_CLICK_EVENT natively,
      // so we handle it there and ignore the two CLICK_EVENTs that preceded it.
      // By the time this timer fires the double-tap branch has already run.
    }, TAP_WINDOW)
  }

  // ── Gesture event handler ───────────────────────────────────────────────────

  bridge.onEvenHubEvent(async event => {
    const sysType  = event.sysEvent?.eventType  ?? null
    const textType = event.textEvent?.eventType ?? null
    const type     = sysType ?? textType

    console.log('media-plugin: event', type)

    switch (type) {
      // Single tap — deferred via triple-tap window
      case OsEventTypeList.CLICK_EVENT:
        handleTap()
        break

      // Double tap — skip forward in active mode, or exit plugin in passive mode
      case OsEventTypeList.DOUBLE_CLICK_EVENT:
        // Cancel any pending single-tap timer so handleTap doesn't also fire
        if (tapTimer) clearTimeout(tapTimer)
        tapCount = 0

        if (interceptMode === 'active') {
          await sendCommand('next')
          resetIdleTimer()
        } else {
          // In passive mode, double tap exits the plugin back to the G2 dashboard
          console.log('media-plugin: passive double-tap, shutting down')
          bridge.shutDownPageContainer(CONTAINER_ID)
        }
        break

      // Long press — mute/unmute toggle
      case OsEventTypeList.LONG_PRESS_EVENT:
        if (interceptMode === 'active') {
          await sendCommand('mute_toggle')
          resetIdleTimer()
        }
        break

      // Swipe forward — volume up
      case OsEventTypeList.SWIPE_FORWARD_EVENT:
        if (interceptMode === 'active') {
          await sendCommand('vol_up')
          resetIdleTimer()
        }
        break

      // Swipe backward — volume down
      case OsEventTypeList.SWIPE_BACKWARD_EVENT:
        if (interceptMode === 'active') {
          await sendCommand('vol_down')
          resetIdleTimer()
        }
        break

      default:
        break
    }
  })

  // ── Boot ────────────────────────────────────────────────────────────────────

  resetIdleTimer()
  poll()

  console.log('media-plugin: running, intercept mode =', interceptMode)
})()
