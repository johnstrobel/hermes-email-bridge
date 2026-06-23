import {
  waitForEvenAppBridge,
  TextContainerProperty,
  CreateStartUpPageContainer,
  OsEventTypeList,
} from '@evenrealities/even_hub_sdk'

const HERMES_URL = 'http://10.0.0.117:5050/latest'
const POLL_INTERVAL_MS = 10_000

const bridge = await waitForEvenAppBridge()

const mainText = new TextContainerProperty({
  xPosition: 0,
  yPosition: 0,
  width: 576,
  height: 288,
  borderWidth: 0,
  borderColor: 5,
  paddingLength: 4,
  containerID: 1,
  containerName: 'main',
  content: 'Hermes\nWaiting for emails...',
  isEventCapture: 1,
})

await bridge.createStartUpPageContainer(
  new CreateStartUpPageContainer({
    containerTotalNum: 1,
    textObject: [mainText],
  }),
)

let lastContent = ''

async function pollHermes(): Promise<void> {
  try {
    const res = await fetch(HERMES_URL)
    if (!res.ok) return
    const data = await res.json() as { subject: string; summary: string }
    const content = `${data.subject}\n\n${data.summary}`
    if (content === lastContent) return
    lastContent = content
    await bridge.textContainerUpgrade({
      containerID: 1,
      containerName: 'main',
      contentOffset: 0,
      contentLength: content.length,
      content,
    })
  } catch {
    // bridge not ready or server unreachable
  }
}

setInterval(pollHermes, POLL_INTERVAL_MS)
pollHermes()

const unsubscribe = bridge.onEvenHubEvent(event => {
  const sysType = event.sysEvent?.eventType ?? null
  const textType = event.textEvent?.eventType ?? null

  if (
    sysType === OsEventTypeList.DOUBLE_CLICK_EVENT ||
    textType === OsEventTypeList.DOUBLE_CLICK_EVENT
  ) {
    bridge.shutDownPageContainer(1)
    return
  }

  if (
    sysType === OsEventTypeList.SYSTEM_EXIT_EVENT ||
    sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT
  ) {
    unsubscribe()
  }
})
