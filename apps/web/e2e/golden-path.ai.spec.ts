import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'

/**
 * The real thing: create a document, attach a real source, run the actual
 * grounded-extraction model call through a real running brownie-worker,
 * apply and accept the result, then validate, approve, and export -- the
 * complete journey the rest of this suite deliberately stops short of,
 * because this one spends a small amount of real money on a real OpenAI
 * call every time it runs. Not part of `npm run test:e2e`'s default
 * selection -- run explicitly with:
 *
 *   BROWNIE_E2E_INCLUDE_AI=1 npx playwright test golden-path.ai
 *
 * and only once brownie-worker (not just brownie-api) is running against
 * the same local stack -- this transcript's own extraction job never
 * leaves PENDING otherwise. See e2e/README.md.
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

  await page.goto('/documents/new')
  await page.getByLabel('Template').selectOption({ label: 'Flowing meeting minutes' })
  const title = `E2E golden path ${Date.now()}`
  await page.getByLabel('Title').fill(title)
  await page.getByRole('button', { name: 'Create document' }).click()
  await page.waitForURL(/\/documents\/\d+$/)

  await page.setInputFiles('#attach-source', {
    name: 'transcript.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from(TRANSCRIPT, 'utf-8'),
  })
  await expect(page.getByText(/^Source #\d+ attached\.$/)).toBeVisible({ timeout: 15_000 })

  await page.getByRole('tab', { name: 'Assist' }).click()
  await page.getByRole('button', { name: 'Try grounded extraction' }).click()

  await expect(page.getByRole('button', { name: 'Apply to document' })).toBeVisible({ timeout: 90_000 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Apply to document' }).click()
  await expect(page.getByText('Proposed changes')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText(/Weekly Robotics Club Sync/i)).toBeVisible()
  await expect(page.getByText(/action items? proposed/i)).toBeVisible()

  await page.getByRole('button', { name: 'Accept and update document' }).click()
  await expect(page.getByText('Applied to the document.')).toBeVisible({ timeout: 15_000 })

  await page.getByRole('tab', { name: 'Checks' }).click()
  await page.getByRole('button', { name: 'Validate this revision' }).click()
  await expect(page.getByText('Ready to export')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('button', { name: 'Approve for export' })).toBeEnabled()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Approve for export' }).click()
  const exportButton = page.getByRole('button', { name: 'Export', exact: true })
  await expect(exportButton).toBeVisible({ timeout: 15_000 })
  await exportButton.click()

  await expect(page.getByText('Both files exported.', { exact: true })).toBeVisible({ timeout: 15_000 })
  let exportedText = ''
  for (const format of ['DOCX', 'PDF'] as const) {
    const link = page.getByRole('link', { name: `Download ${format}`, exact: true })
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
