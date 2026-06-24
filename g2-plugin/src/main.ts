import {
  waitForEvenAppBridge,
  TextContainerProperty,
  TextContainerUpgrade,
  CreateStartUpPageContainer,
  OsEventTypeList,
} from '@evenrealities/even_hub_sdk'

const HERMES_URL = 'http://10.0.0.117:5050/emails'
const POLL_INTERVAL_MS = 15_000

type EmailSummary = { subject: string; summary: string }

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
  content: 'Hermes\nLoading emails...',
  isEventCapture: 1,
})

await bridge.createStartUpPageContainer(
  new CreateStartUpPageContainer({
    containerTotalNum: 1,
    textObject: [mainText],
  }),
)

let emails: EmailSummary[] = []
let currentIndex = 0

function buildDisplay(index: number): string {
  if (emails.length === 0) return 'Hermes\nNo emails yet.'
  const e = emails[index]
  const counter = `[${index + 1}/${emails.length}]`
  return `${counter} ${e.subject}\n\n${e.summary}`
}

async function updateDisplay(): Promise<void> {
  const content = buildDisplay(currentIndex)
  await bridge.textContainerUpgrade(
    new TextContainerUpgrade({
      containerID: 1,
      containerName: 'main',
      contentOffset: 0,
      contentLength: content.length,
      content,
    })
  )
}

async function pollHermes(): Promise<void> {
  try {
    const res = await fetch(HERMES_URL)
    if (!res.ok) return
    const data = await res.json() as EmailSummary[]
    if (data.length === 0) return
    emails = data
    // clamp index in case list shrank
    if (currentIndex >= emails.length) currentIndex = 0
    await updateDisplay()
  } catch {
    // server unreachable
  }
}

setInterval(pollHermes, POLL_INTERVAL_MS)
pollHermes()

const unsubscribe = bridge.onEvenHubEvent(async event => {
  const sysType = event.sysEvent?.eventType ?? null
  const textType = event.textEvent?.eventType ?? null

  if (
    sysType === OsEventTypeList.DOUBLE_CLICK_EVENT ||
    textType === OsEventTypeList.DOUBLE_CLICK_EVENT
  ) {
    bridge.shutDownPageContainer(1)
    return
  }

  if (sysType === OsEventTypeList.CLICK_EVENT || textType === OsEventTypeList.CLICK_EVENT) {
    if (emails.length > 0) {
      currentIndex = (currentIndex + 1) % emails.length
      await updateDisplay()
    }
    return
  }

  if (
    sysType === OsEventTypeList.SYSTEM_EXIT_EVENT ||
    sysType === OsEventTypeList.ABNORMAL_EXIT_EVENT
  ) {
    unsubscribe()
  }
})
