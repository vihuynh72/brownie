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
  // Let the whole spoken sentence play through the fake microphone before stopping.
  await page.waitForTimeout(5_000)
  await page.getByRole('button', { name: /Stop recording/ }).click()
  const composer = page.locator('textarea').last()
  // The stub answers with the whole sentence; the real model's own words may
  // punctuate the number differently, so what must land is the name and the digits.
  await expect(composer).toHaveValue(/Jordan Rivera/, { timeout: 20_000 })
  const heard = await composer.inputValue()
  expect(heard).toMatch(/555/)
  // The person sends it; it becomes a chat turn of their own.
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText(/Jordan Rivera/).first()).toBeVisible()
})

test('a spoken request in a document chat fills a field', async () => {
  test.skip(!process.env.VOICE_LIVE, 'live proof only: the real model hears the fake microphone')
  const { uploadPdfFromHome, FILLABLE_PDF } = await import('./pdfForms')
  const { startDocumentFromSidebar } = await import('./documents')
  const page = await pageWithFakeMic()
  const templateId = await uploadPdfFromHome(page, FILLABLE_PDF, 'Voice fill form')
  const doc = await startDocumentFromSidebar(page, 'Voice fill form')
  const mic = page.getByRole('button', { name: 'Record a voice note' })
  await expect(mic).toBeVisible()
  await mic.click()
  await expect(page.getByRole('button', { name: /Stop recording/ })).toBeVisible()
  await page.waitForTimeout(5_000)
  await page.getByRole('button', { name: /Stop recording/ }).click()
  const composer = page.locator('textarea').last()
  await expect(composer).toHaveValue(/Jordan Rivera/, { timeout: 20_000 })
  await page.getByRole('button', { name: 'Send' }).click()
  // The document chat acts on the words: a field takes the spoken name.
  await expect(page.getByText(/Jordan Rivera/).first()).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: `${process.env.HOME}/brownie-scratch/autopilot/qa/voice-composer-and-field.png`, fullPage: true })
})
