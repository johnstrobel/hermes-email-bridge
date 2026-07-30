import {
  waitForEvenAppBridge,
  TextContainerProperty,
  TextContainerUpgrade,
  CreateStartUpPageContainer,
  OsEventTypeList,
} from '@evenrealities/even_hub_sdk'

// Replace with your Android phone's local IP address
const PHONE_IP = '10.0.0.117'
const PHONE_BRIDGE_URL = `http://${PHONE_IP}:5051`
const POLL_INTERVAL_MS = 3_000

type PhoneStatusResponse = {
  playing: boolean
  buffering: boolean
  track: string
  artist: string
  volume: number
  phoneBat: number
  netByte: number
  inCall: boolean
}

function parseNetworkType(netByte: number): string {
  const type = netByte & 0xf0
  switch (type) {
    case 0x10: return 'Wi-Fi'
    case 0x20: return 'LTE'
    case 0x30: return '5G'
    case 0x40: return '3G'
    case 0x50: return 'EDGE'
    case 0x60: return 'Ethernet'
    default: return 'Offline'
  }
}

;(async () => {
  console.log('g2-media: initializing bridge')

  const bridge = await waitForEvenAppBridge()
  console.log('g2-media: app bridge ready')

  await bridge.createStartUpPageContainer(
    new CreateStartUpPageContainer({
      containerTotalNum: 1,
      textObject: [
        new TextContainerProperty({
          xPosition: 0,
          yPosition: 0,
          width: 576,
          height: 288,
          borderWidth: 0,
          borderColor: 5,
          paddingLength: 4,
          containerID: 1,
          containerName: 'main',
          content: 'Media Control\nConnecting to phone...',
          isEventCapture: 1,
        }),
      ],
    })
  )

  let lastStatus: PhoneStatusResponse | null = null

  // Keep display to 4 lines max — longer content triggers native text
  // container scrolling which consumes scroll events before our handler sees them.
  function buildDisplay(status: PhoneStatusResponse | null): string {
    if (!status) return 'No connection to phone\n\nTap:Play  2x:Next  Up:Vol+  Dn:Vol-'
    const net      = parseNetworkType(status.netByte)
    const stateStr = status.buffering ? 'Buffering' : (status.playing ? 'Playing' : 'Paused')
    const track    = status.track ? `${status.track} - ${status.artist || 'Unknown'}` : 'No Active Track'
    const info     = `${stateStr} | Bat:${status.phoneBat}% | ${net} | Vol:${status.volume}%`
    return `${track}\n${info}\n\nTap:Play  2x:Next  Up:Vol+  Dn:Vol-`
  }

  async function updateDisplay(): Promise<void> {
    const content = buildDisplay(lastStatus)
    await bridge.textContainerUpgrade(new TextContainerUpgrade({
      containerID: 1, containerName: 'main',
      contentOffset: 0, contentLength: content.length, content,
    }))
  }

  async function pollStatus(): Promise<void> {
    try {
      const res = await fetch(`${PHONE_BRIDGE_URL}/media/status`)
      if (!res.ok) return
      lastStatus = (await res.json()) as PhoneStatusResponse
      await updateDisplay()
    } catch (e) {
      console.log('poll status error:', String(e))
    }
  }

  async function sendCommand(cmd: string): Promise<void> {
    try {
      await fetch(`${PHONE_BRIDGE_URL}/media/command?cmd=${encodeURIComponent(cmd)}`)
      setTimeout(pollStatus, 400)
    } catch (e) {
      console.log('send command error:', String(e))
    }
  }

  await updateDisplay()
  setInterval(pollStatus, POLL_INTERVAL_MS)
  pollStatus()

  bridge.onEvenHubEvent(async event => {
    const sysType  = event.sysEvent?.eventType  ?? null
    const textType = event.textEvent?.eventType ?? null

    if (sysType === null && textType === null) {
      await sendCommand('play_pause')
    } else if (sysType === OsEventTypeList.DOUBLE_CLICK_EVENT) {
      await sendCommand('next')
    } else if (textType === OsEventTypeList.SCROLL_TOP_EVENT) {
      await sendCommand('vol_up')
    } else if (textType === OsEventTypeList.SCROLL_BOTTOM_EVENT) {
      await sendCommand('vol_down')
    }
  })
})()
