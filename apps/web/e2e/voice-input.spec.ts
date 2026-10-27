import { expect, test, chromium, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'

/**
 * A person talks to the composer instead of typing: the browser's fake
 * microphone plays a recorded sentence, the transcription route (stubbed
 * here, a real model in the live check) returns its words, and they land
 * in the composer's text for the person to edit and send -- nothing is
 * ever sent on its own.
 *
 * The spec launches its own Chromium because Chromium's fake-media flags
 * are a launch-time choice, not a context option.
 */
const AUTH_STATE = fileURLToPath(new URL('./.auth/state.json', import.meta.url))
const VOICE_WAV = fileURLToPath(new URL('../../../backend/brownie-api/src/test/resources/voice-jordan.wav', import.meta.url))
const TRANSCRIPT = 'My name is Jordan Rivera and my phone number is 408 555 0142'

async function pageWithFakeMic(): Promise<Page> {
  const browser = await chromium.launch({
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      `--use-file-for-fake-audio-capture=${VOICE_WAV}`,
    ],
  })
  const context = await browser.newContext({ storageState: AUTH_STATE })
  const page = await context.newPage()
  // The transcription route's answer is the model's; the stubbed run fixes it to the
  // same words the WAV says, while VOICE_LIVE lets the real speech model hear them.
  if (!process.env.VOICE_LIVE) {
    await page.route('**/api/v1/workspaces/*/transcriptions', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ text: TRANSCRIPT }) }),
    )
  }
  return page
}

test('a voice note becomes editable words in the composer and then a message', async () => {
  const page = await pageWithFakeMic()
  await page.goto('/chat')
  const mic = page.getByRole('button', { name: 'Record a voice note' })
  await expect(mic).toBeVisible()
  await mic.click()
  // Recording state: the button says it is recording and offers Stop.
  await expect(page.getByRole('button', { name: /Stop recording/ })).toBeVisible()
  await page.waitForTimeout(1200)
  await page.getByRole('button', { name: /Stop recording/ }).click()
  await expect(page.locator('textarea').last()).toContainText?.(TRANSCRIPT) ?? page.waitForSelector(`textarea:has-text("${TRANSCRIPT.slice(0, 20)}")`)
  const composer = page.locator('textarea').last()
  await expect(composer).toHaveValue(new RegExp(TRANSCRIPT.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  // The person sends it; it becomes a chat turn of their own.
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText(TRANSCRIPT).first()).toBeVisible()
})
