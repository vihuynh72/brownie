import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

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

test('a real document goes from empty to a real, exported DOCX/PDF through the actual AI-fill path', async ({ page }) => {
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

  const downloadLink = page.getByRole('link', { name: /download/i }).first()
  await expect(downloadLink).toBeVisible({ timeout: 15_000 })
  const href = await downloadLink.getAttribute('href')
  expect(href).toMatch(/\/api\/v1\/workspaces\/\d+\/uploads\/\d+\/download/)

  const downloadResponse = await page.request.get(href!)
  expect(downloadResponse.ok()).toBe(true)
  expect(Number(downloadResponse.headers()['content-length'])).toBeGreaterThan(0)

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
