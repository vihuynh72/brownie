import { test, expect, chromium, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const QA = process.env.HOME + '/brownie-scratch/autopilot/qa'
const AUTH_STATE = 'e2e/.auth/state.json'
const VOICE_WAV = fileURLToPath(new URL('../../../backend/brownie-api/src/test/resources/voice-jordan.wav', import.meta.url))
const NAME = 'two-page-application'

async function pageWithFakeMic(): Promise<Page> {
  const browser = await chromium.launch({
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      `--use-file-for-fake-audio-capture=${VOICE_WAV}`,
    ],
  })
  return (await browser.newContext({ storageState: AUTH_STATE })).newPage()
}

test('spoken answers in the document chat fill the form', async () => {
  test.setTimeout(360_000)
  const page = await pageWithFakeMic()
  await page.goto('/')
  const sidebar = page.locator('aside, nav, [role="navigation"]').first()
  const start = sidebar.getByRole('button', { name: `Start a document from ${NAME}`, exact: false })
  if (!(await start.count())) {
    const add = sidebar.getByRole('button', { name: 'Add a template from a file', exact: true })
    const [chooser] = await Promise.all([page.waitForEvent('filechooser'), add.click()])
    await chooser.setFiles(process.env.HOME + '/brownie-scratch/autopilot/forms/sweep/two-page-application.pdf')
    await start.waitFor({ timeout: 180_000 })
  }
  await start.click()
  await page.waitForTimeout(4000)
  const keep = page.getByRole('button', { name: /Keep all the places/i }).first()
  if (await keep.count()) await keep.click().catch(() => {})

  const mic = page.getByRole('button', { name: 'Record a voice note' })
  await expect(mic).toBeVisible({ timeout: 15_000 })
  await mic.click()
  await expect(page.getByRole('button', { name: /Stop recording/ })).toBeVisible()
  await page.waitForTimeout(5_000)
  await page.getByRole('button', { name: /Stop recording/ }).click()
  const composer = page.locator('textarea').last()
  await expect(composer).toHaveValue(/Jordan Rivera/, { timeout: 30_000 })
  await page.screenshot({ path: `${QA}/chat-fill-voice-transcript.png` })
  await composer.press('Enter')

  const approve = page.getByRole('button', { name: /I approve, fill it in|Approve|Accept/ }).first()
  await approve.waitFor({ timeout: 240_000 })
  await page.screenshot({ path: `${QA}/chat-fill-voice-proposal.png` })
  await approve.click()
  await page.waitForTimeout(3000)
  await page.screenshot({ path: `${QA}/chat-fill-voice-filled.png` })
  await expect(page.getByText(/Jordan Rivera/).first()).toBeVisible({ timeout: 20_000 })
})
