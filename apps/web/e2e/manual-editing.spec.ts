import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { startDocumentFromSidebar } from './documents'

/**
 * The whole document lifecycle with no model call at all: start a document
 * from a built-in template in the sidebar, type every value into the page by hand
 * (including one action-item row), save, ask Brownie for one typed change,
 * undo and redo nothing by accident, preview, export through the Export
 * window, and read the typed values back out of the downloaded Word file.
 * This is the journey a person takes when they have no transcript, or when
 * the assisted path missed something they simply know -- and it is free to
 * run on every suite run.
 */
test('a person fills a document by hand on its page and the typed values survive into the exported DOCX', async ({ page }, testInfo) => {
  // Two real renders through the isolated renderer (the preview, then the check before export) do not fit the default budget.
  test.setTimeout(150_000)
  await startDocumentFromSidebar(page, 'Flowing meeting minutes')

  // Every template field has a fill spot on the page before it holds a value.
  const title = page.getByLabel(/^Meeting title/)
  await expect(title).toBeVisible({ timeout: 15_000 })
  // The page is the template's own text: its heading is there around the fill spots.
  await expect(page.locator('.document-page').getByText('Meeting Minutes', { exact: true })).toBeVisible()
  await title.fill('Garden Club Planning Meeting')
  await page.getByLabel(/^Meeting date/).fill('2026-04-09')
  // Nothing is clicked: a pause after typing saves on its own, and the status beside Export says so.
  const status = page.locator('.save-status')
  await expect(status).toHaveText('Unsaved changes')
  await expect(status).toHaveText('Saved', { timeout: 15_000 })

  // Text typed while an autosave is in flight must survive it, neither dropped nor spliced: type
  // half, pause long enough for the save to fire, keep typing through it, then reload and read
  // back what the server kept.
  const attendees = page.getByLabel(/^Meeting attendees/)
  await attendees.pressSequentially('Priya Rao, Alex', { delay: 40 })
  await page.waitForTimeout(2_400)
  await attendees.pressSequentially(' Chen, Jose Nunez', { delay: 40 })
  await expect(attendees).toHaveValue('Priya Rao, Alex Chen, Jose Nunez')
  await expect(attendees).toBeFocused()
  await expect(status).toHaveText('Saved', { timeout: 15_000 })
  await page.reload()
  await expect(page.getByLabel(/^Meeting attendees/)).toHaveValue('Priya Rao, Alex Chen, Jose Nunez', { timeout: 15_000 })

  // The Rules card reads the text style a value takes from the template itself.
  const rules = page.getByRole('region', { name: 'Rules' })
  await expect(rules.getByText('Liberation Sans', { exact: true })).toBeVisible({ timeout: 15_000 })
  await expect(rules.getByText('11 pt', { exact: true })).toBeVisible()
  await expect(rules.getByText('could not be loaded')).toHaveCount(0)

  await page.getByRole('button', { name: 'Add row' }).click()
  await expect(page.getByLabel('Action item task, row 1', { exact: true })).toBeFocused()
  await page.getByLabel('Action item task, row 1', { exact: true }).fill('Order seedlings for the spring plot')
  await page.getByLabel('Action item owner, row 1', { exact: true }).fill('Maria Lopez')
  // A row with a blank date is never saved, by autosave or by hand; the message names the row and the column.
  await expect(page.getByText('Row 1 needs a value for Action item due')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save now' })).toBeDisabled()
  await page.getByLabel('Action item due, row 1', { exact: true }).fill('2026-04-20')
  await expect(page.getByRole('button', { name: 'Save now' })).toBeEnabled()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Save now' }).click()
  await expect(status).toHaveText('Saved', { timeout: 15_000 })

  // The reload after saving shows the server's copy of what was typed, with its own review state.
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue('Garden Club Planning Meeting')
  await expect(page.getByLabel('Action item owner, row 1', { exact: true })).toHaveValue('Maria Lopez')
  await page.getByLabel('Action item owner, row 1', { exact: true }).focus()
  await expect(page.getByRole('button', { name: 'Accept row 1' })).toBeVisible()

  // Asking Brownie for a typed change: it comes back as a proposal, and only approving it changes the
  // document -- no model call for a change.
  await page.getByLabel('How may I help you?').fill('change meeting title to Garden Club Spring Planning')
  await page.getByLabel('How may I help you?').press('Enter')
  await expect(page.getByText('Here is the change you asked for:')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.proposal').getByText('Garden Club Spring Planning')).toBeVisible()
  await page.getByRole('button', { name: /I approve, fill it in/ }).click()
  await expect(page.getByRole('region', { name: 'Conversation with Brownie' }).getByText(/^Filled in 1 value\./)).toBeVisible({ timeout: 15_000 })
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue('Garden Club Spring Planning')

  // Undo takes the document back to how it read before that change; undoing again is not needed here.
  await page.getByRole('button', { name: 'Undo the last change' }).click()
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue('Garden Club Planning Meeting', { timeout: 15_000 })
  // Redo by asking again, so the export below carries the accepted change.
  await page.getByLabel('How may I help you?').fill('change meeting title to Garden Club Spring Planning')
  await page.getByLabel('How may I help you?').press('Enter')
  await expect(page.getByRole('button', { name: /I approve, fill it in/ })).toBeVisible({ timeout: 15_000 })
  await page.getByRole('button', { name: /I approve, fill it in/ }).click()
  await expect(page.getByLabel(/^Meeting title/)).toHaveValue('Garden Club Spring Planning', { timeout: 15_000 })

  // The print preview draws the compiled PDF on demand -- a real render through the isolated
  // renderer, then PDF.js drawing page one in this browser.
  await page.getByRole('button', { name: 'Print preview' }).click()
  await page.getByRole('button', { name: 'Generate preview' }).click()
  await expect(page.getByText(/^Page 1 of \d+$/)).toBeVisible({ timeout: 90_000 })
  await expect(page.getByRole('img', { name: /^Preview of version \d+, page 1 of \d+$/ })).toBeVisible()
  await page.getByRole('button', { name: 'Page', exact: true }).click()

  // Export: a window, not a new tab. It checks this version, then approving exports it.
  await page.getByRole('button', { name: 'Export', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Export' })
  await expect(dialog.getByText('Ready to export.')).toBeVisible({ timeout: 60_000 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await dialog.getByRole('button', { name: 'Approve and export' }).click()
  await expect(dialog.getByText('Both files exported.', { exact: true })).toBeVisible({ timeout: 60_000 })

  const link = dialog.getByRole('link', { name: 'Download Word file (.docx)', exact: true })
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
  // The title Brownie changed, not the one typed first: the export carries the accepted proposal.
  expect(text).toContain('Garden Club Spring Planning')
  expect(text).toContain('Priya Rao, Alex Chen, Jose Nunez')
  expect(text).toMatch(/April 9, 2026|2026-04-09/)
  expect(text).toContain('Order seedlings for the spring plot')
  expect(text).toContain('Maria Lopez')
  expect(text).toMatch(/April 20, 2026|2026-04-20/)
  expect(text).not.toContain('No action items recorded.')

  // Closing the window returns focus to the button that opened it.
  await dialog.getByRole('button', { name: 'Close' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: 'Export', exact: true })).toBeFocused()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
