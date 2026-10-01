import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { startDocumentFromSidebar } from './documents'
import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'

/**
 * The real thing: start a document from a template in the sidebar, attach a real source, ask Brownie in
 * words to fill it, run the actual grounded-extraction model call through a
 * real running brownie-worker, approve what it found, then check, approve
 * and export through the Export window -- the
 * complete journey the rest of this suite deliberately stops short of,
 * because this one spends a small amount of real money on a real OpenAI
 * call every time it runs. Not part of `npm run test:e2e`'s default
 * selection -- run explicitly with:
 *
 *   BROWNIE_E2E_INCLUDE_AI=1 npx playwright test golden-path.ai
 *
 * and only once brownie-worker (not just brownie-api) is running against
 * the same local stack -- this transcript's own extraction job never
 * leaves PENDING otherwise. See the End-to-end tests section of the repository README.
 */
test.skip(!process.env.BROWNIE_E2E_INCLUDE_AI, 'Spends a real OpenAI call -- opt in with BROWNIE_E2E_INCLUDE_AI=1.')

const TRANSCRIPT = `Weekly Robotics Club Sync

The meeting was called to order by Priya Rao. The meeting title is
"Weekly Robotics Club Sync" and it took place on 2026-03-05.

Alex Chen agreed to finish wiring the practice robot by 2026-03-12.

Jose Nunez will confirm the van reservation for the regional
competition by 2026-03-10.
`

test('a real document goes from empty to a real, exported DOCX/PDF through the actual AI-fill path', async ({ page }, testInfo) => {
  test.setTimeout(120_000)

  await startDocumentFromSidebar(page, 'Flowing meeting minutes')

  // A source is added from Brownie's panel: the + beside the message box opens the upload.
  await page.getByRole('button', { name: 'Add a source' }).click()
  await page.setInputFiles('#attach-source', {
    name: 'transcript.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from(TRANSCRIPT, 'utf-8'),
  })
  await expect(page.locator('.source-card').getByText('transcript.txt', { exact: true })).toBeVisible({ timeout: 15_000 })

  // Asked in words, as the owner's design has it: Brownie reads the transcript.
  await page.getByLabel('How may I help you?').fill('fill this from the transcript')
  await page.getByLabel('How may I help you?').press('Enter')
  // Stop appears once the server has accepted the reading, so it exists before the reload below.
  await expect(page.getByRole('button', { name: 'Stop reading' })).toBeVisible({ timeout: 15_000 })

  // A reload while the reading is in flight must not strand it: the workspace finds the
  // document's sources and its latest run on the server again and carries on from there.
  await page.reload()
  await expect(page.locator('.source-card').getByText('transcript.txt', { exact: true })).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('button', { name: 'Show what I found' })).toBeVisible({ timeout: 90_000 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Show what I found' }).click()
  const proposal = page.locator('.proposal')
  await expect(proposal.getByText('Here is what I found in transcript.txt:')).toBeVisible({ timeout: 15_000 })
  await expect(proposal.getByText(/Weekly Robotics Club Sync/i)).toBeVisible()
  await expect(proposal.getByText(/^Row 1:/)).toBeVisible()

  await page.getByRole('button', { name: /I approve, fill it in/ }).click()
  await expect(page.getByRole('region', { name: 'Conversation with Brownie' }).getByText(/^Filled in \d+ values?\./)).toBeVisible({ timeout: 15_000 })
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue(/Weekly Robotics Club Sync/i)

  // A value Brownie filled carries its evidence: the bar about the fill spot opens the cited passage.
  await page.getByLabel(/^Meeting title/).focus()
  await page.getByRole('button', { name: /^Where it came from/ }).click()
  const evidence = page.locator('#evidence-meeting\\.title')
  await expect(evidence).toContainText('From transcript.txt', { timeout: 15_000 })
  await expect(evidence).toContainText(/Weekly Robotics Club Sync/)

  await page.getByRole('button', { name: 'Export', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Export' })
  await expect(dialog.getByText('Ready to export.')).toBeVisible({ timeout: 60_000 })
  await expect(dialog.getByRole('button', { name: 'Approve and export' })).toBeEnabled()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await dialog.getByRole('button', { name: 'Approve and export' }).click()
  await expect(dialog.getByText('Both files exported.', { exact: true })).toBeVisible({ timeout: 60_000 })
  let exportedText = ''
  for (const format of ['DOCX', 'PDF'] as const) {
    const link = dialog.getByRole('link', { name: format === 'DOCX' ? 'Download Word file (.docx)' : 'Download PDF', exact: true })
    await expect(link).toBeVisible()
    const downloadPromise = page.waitForEvent('download')
    await link.click()
    const download = await downloadPromise
    expect(await download.failure()).toBeNull()
    const filePath = testInfo.outputPath(`meeting-minutes.${format.toLowerCase()}`)
    await download.saveAs(filePath)
    const bytes = await readFile(filePath)
    expect(bytes.byteLength).toBeGreaterThan(0)
    if (format === 'DOCX') {
      expect(bytes.subarray(0, 2).toString()).toBe('PK')
      // unzip is present on the supported macOS/Linux development hosts.
      // Inspect the actual downloaded Word XML, not only Content-Length.
      const xml = execFileSync('unzip', ['-p', filePath, 'word/document.xml'], { encoding: 'utf8' })
      const text = xml.replace(/<[^>]*>/g, '')
      exportedText = text
      expect(text).toContain('Weekly Robotics Club Sync')
      // The qualified template renders ISO dates as human-readable dates.
      expect(text).toMatch(/March 5, 2026|2026-03-05/)
      expect(text).toContain('Alex Chen')
      expect(text).toContain('Jose Nunez')
    } else {
      expect(bytes.subarray(0, 5).toString()).toBe('%PDF-')
    }
  }

  // The two explicit commitments in this transcript must survive all the way into the exported
  // file -- a set of minutes that reports "No action items recorded" over a transcript that plainly
  // contains two is not a complete result, however cleanly every step before it succeeded.
  expect(exportedText, 'The exported minutes must retain the robot-wiring task').toMatch(/wiring.*practice robot/i)
  expect(exportedText, 'The exported minutes must retain the van-reservation task').toMatch(/van reservation/i)
  expect(exportedText, 'Explicit action items must not become a no-items statement').not.toContain('No action items recorded.')

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
