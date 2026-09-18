import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

/**
 * The whole document lifecycle with no model call at all: create a document
 * from a built-in template, type every value in by hand (including one
 * action-item row), save, validate, approve, export, and read the typed
 * values back out of the downloaded Word file. This is the journey a person
 * takes when they have no transcript, or when the assisted path missed
 * something they simply know -- and it is free to run on every suite run.
 */
test('a person fills a document by hand and the typed values survive into the exported DOCX', async ({ page }, testInfo) => {
  // Two real renders through the isolated renderer (the preview, then validation) do not fit the default budget.
  test.setTimeout(120_000)
  await page.goto('/documents/new')
  await page.getByLabel('Template', { exact: true }).selectOption({ label: 'Flowing meeting minutes' })
  await page.getByLabel('Title', { exact: true }).fill(`E2E manual editing ${Date.now()}`)
  await page.getByRole('button', { name: 'Create document' }).click()
  await page.waitForURL(/\/documents\/\d+$/)

  // Every template field has a control before it holds a value.
  const title = page.getByLabel(/^Meeting title/)
  await expect(title).toBeVisible({ timeout: 15_000 })
  await title.fill('Garden Club Planning Meeting')
  await page.getByLabel(/^Meeting date/).fill('2026-04-09')
  // Nothing is clicked: a pause after typing saves on its own, and the page says so.
  await expect(page.getByText('Unsaved changes. Brownie saves a moment after you stop typing.')).toBeVisible()
  // The visible status in the save bar; the page's live region says the same thing for screen readers.
  await expect(page.locator('form.field-list').getByText('Saved.')).toBeVisible({ timeout: 15_000 })

  // Text typed while an autosave is in flight must survive it, neither dropped nor spliced: type
  // half, pause long enough for the save to fire, keep typing through it, then reload and read
  // back what the server kept.
  const attendees = page.getByLabel(/^Meeting attendees/)
  await attendees.pressSequentially('Priya Rao, Alex', { delay: 40 })
  await page.waitForTimeout(2_400)
  await attendees.pressSequentially(' Chen, Jose Nunez', { delay: 40 })
  await expect(attendees).toHaveValue('Priya Rao, Alex Chen, Jose Nunez')
  await expect(attendees).toBeFocused()
  await expect(page.locator('form.field-list').getByText('Saved.')).toBeVisible({ timeout: 15_000 })
  await page.reload()
  await expect(page.getByLabel(/^Meeting attendees/)).toHaveValue('Priya Rao, Alex Chen, Jose Nunez', { timeout: 15_000 })

  await page.getByRole('button', { name: 'Add row' }).click()
  await page.getByLabel('Action item task, row 1').fill('Order seedlings for the spring plot')
  await page.getByLabel('Action item owner, row 1').fill('Maria Lopez')
  // A row with a blank date is never saved, by autosave or by hand; the message names the row and the column.
  await expect(page.getByText('Row 1 needs a value for Action item due')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save now' })).toBeDisabled()
  await page.getByLabel('Action item due, row 1').fill('2026-04-20')
  await expect(page.getByRole('button', { name: 'Save now' })).toBeEnabled()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Save now' }).click()
  // The visible status in the save bar; the page's live region says the same thing for screen readers.
  await expect(page.locator('form.field-list').getByText('Saved.')).toBeVisible({ timeout: 15_000 })

  // The reload after saving shows the server's copy of what was typed, with its own review state.
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue('Garden Club Planning Meeting')
  await expect(page.getByLabel('Action item owner, row 1')).toHaveValue('Maria Lopez')
  await expect(page.getByRole('button', { name: 'Accept row 1' })).toBeVisible()

  // The Rules tab reads the document's own template version, which is activated and has no draft.
  await page.getByRole('tab', { name: 'Rules' }).click()
  await expect(page.getByText(/accepted rules on this template version|^[A-Z_]+Whole template/).first()).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('Could not load the rules')).toHaveCount(0)

  // The Assist composer: a typed request is shown as a plan with its scope first, runs only on request,
  // and comes back as a proposal that the ordinary accept step applies -- no model call for a change.
  await page.getByRole('tab', { name: 'Assist' }).click()
  await page.getByLabel('Ask Assist').fill('change meeting title to Garden Club Spring Planning')
  await page.getByRole('button', { name: 'Interpret' }).click()
  await expect(page.getByText('What Assist would do')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('Change Meeting title to "Garden Club Spring Planning"')).toBeVisible()
  await expect(page.getByText('Garden Club Planning Meeting', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Do it' }).click()
  await expect(page.getByText('Proposed changes')).toBeVisible({ timeout: 15_000 })
  await page.getByRole('button', { name: 'Accept and update document' }).click()
  await expect(page.getByText('Applied to the document.')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue('Garden Club Spring Planning')

  // The preview pane draws the compiled PDF beside the editor on demand -- a real render through
  // the isolated renderer, then PDF.js drawing page one in this browser.
  await page.getByRole('button', { name: 'Generate preview' }).click()
  await expect(page.getByText(/^Page 1 of \d+$/)).toBeVisible({ timeout: 90_000 })
  await expect(page.getByRole('img', { name: /^Preview of version \d+, page 1 of \d+$/ })).toBeVisible()

  await page.getByRole('tab', { name: 'Checks' }).click()
  await page.getByRole('button', { name: 'Validate this revision' }).click()
  await expect(page.getByText('Ready to export')).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: 'Approve for export' }).click()
  const exportButton = page.getByRole('button', { name: 'Export', exact: true })
  await expect(exportButton).toBeVisible({ timeout: 15_000 })
  await exportButton.click()
  await expect(page.getByText('Both files exported.', { exact: true })).toBeVisible({ timeout: 60_000 })

  const link = page.getByRole('link', { name: 'Download DOCX', exact: true })
  const downloadPromise = page.waitForEvent('download')
  await link.click()
  const download = await downloadPromise
  expect(await download.failure()).toBeNull()
  const filePath = testInfo.outputPath('hand-filled-minutes.docx')
  await download.saveAs(filePath)
  const bytes = await readFile(filePath)
  expect(bytes.subarray(0, 2).toString()).toBe('PK')
  // unzip is present on the supported macOS/Linux development hosts; inspect the real Word XML.
  const text = execFileSync('unzip', ['-p', filePath, 'word/document.xml'], { encoding: 'utf8' }).replace(/<[^>]*>/g, '')
  // The title Assist changed, not the one typed first: the export carries the accepted proposal.
  expect(text).toContain('Garden Club Spring Planning')
  expect(text).toContain('Priya Rao, Alex Chen, Jose Nunez')
  expect(text).toMatch(/April 9, 2026|2026-04-09/)
  expect(text).toContain('Order seedlings for the spring plot')
  expect(text).toContain('Maria Lopez')
  expect(text).toMatch(/April 20, 2026|2026-04-20/)
  expect(text).not.toContain('No action items recorded.')

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
