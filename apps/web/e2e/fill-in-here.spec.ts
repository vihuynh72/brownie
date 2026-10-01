import { execFileSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { test, expect, type Page } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

/**
 * Adding fill spots to an open Word document, against the real API: one where words are selected on
 * the page ("Fill in here" beside them), one entirely from the keyboard ("Add a fill spot", a line from
 * the list, a place in it, a name), with the menu on the page text in between. Each change makes a new
 * version of the form with the spot in its file, proved by a real render, so the values typed into the
 * new spots come out in the exported Word file.
 *
 * The form is the built-in table-led minutes, uploaded under a name of its own so that the built-in
 * templates other tests use are never changed, and put in the Trash Bin at the end.
 */

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
const TAGGED_FORM = fileURLToPath(new URL('../../../fixtures/public/templates/table-led-meeting-minutes.docx', import.meta.url))

async function trashTemplatesNamed(page: Page, name: string): Promise<void> {
  const workspaceId = (await (await page.request.get('/api/v1/me')).json()).memberships[0].workspaceId
  const templates = (await (await page.request.get(`/api/v1/workspaces/${workspaceId}/templates`)).json()) as { id: number; displayName: string }[]
  const xsrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
  for (const template of templates.filter((candidate) => candidate.displayName === name)) {
    await page.request.post(`/api/v1/workspaces/${workspaceId}/templates/${template.id}/trash`, { headers: { 'X-XSRF-TOKEN': xsrf } })
  }
}

test('a person adds fill spots to an open form with the pointer and with the keyboard, and their values are exported', async ({ page }, testInfo) => {
  // Uploading renders the form once, each added spot renders it again, and the check before export once more.
  test.setTimeout(240_000)
  const name = `E2E fill in here ${Date.now()}`

  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Upload your documents', exact: true })).toBeVisible({ timeout: 15_000 })
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload your documents', exact: true }).click()
  await (await chooser).setFiles({ name: `${name}.docx`, mimeType: DOCX_TYPE, buffer: await readFile(TAGGED_FORM) })
  await page.waitForURL(/\/documents\/\d+$/, { timeout: 60_000 })
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible({ timeout: 15_000 })
  const sheet = page.locator('.document-page__sheet')
  const chat = page.getByRole('region', { name: 'Conversation with Brownie' })

  // ---- With the pointer: words selected on the page, then "Fill in here" beside them.
  const decisions = sheet.getByText('Decisions', { exact: true })
  await decisions.selectText()
  const fillHere = page.getByRole('button', { name: 'Fill in here', exact: true })
  await expect(fillHere).toBeVisible()
  // Above the words selected, or beside them: never over them or over the line after them.
  const words = (await decisions.boundingBox())!
  const bar = (await fillHere.boundingBox())!
  expect(bar.y + bar.height <= words.y || bar.x >= words.x + words.width || bar.x + bar.width <= words.x).toBe(true)
  await fillHere.click()
  const dialog = page.getByRole('dialog', { name: 'Add a fill spot' })
  await expect(dialog.getByRole('heading', { name: 'What goes here?' })).toBeVisible()
  // The bar stands down while the dialog is open.
  await expect(fillHere).toBeHidden()
  // Selected words would be replaced; the value goes after the heading instead.
  await dialog.getByRole('button', { name: 'Choose another place' }).click()
  await dialog.getByRole('radio', { name: 'At the end of the paragraph' }).check()
  await dialog.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(dialog.getByLabel('Name', { exact: true })).toBeFocused()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await dialog.getByLabel('Name', { exact: true }).fill('Decided by')
  await dialog.getByRole('button', { name: 'Add the fill spot' }).click()
  await expect(dialog.getByRole('status')).toHaveText('Adding the fill spot\u2026 Brownie is checking the form still prints correctly.')

  // The new spot takes focus in the page, and the chat says what was done, with Undo beside it.
  const decidedBy = page.getByLabel(/^Decided by/)
  await expect(decidedBy).toBeFocused({ timeout: 90_000 })
  await expect(dialog).toBeHidden()
  await expect(chat.getByText(/^Added a fill spot for Decided by at the end of the paragraph "Decisions"\./)).toBeVisible()
  await expect(chat.getByRole('button', { name: 'Undo adding Decided by' })).toBeVisible()
  await decidedBy.fill('Priya Rao')

  // ---- The menu on the page text: in place of the browser's, and closed with Escape.
  await sheet.getByText('Action Items', { exact: true }).click({ button: 'right' })
  const menu = page.getByRole('menu', { name: 'Page text' })
  await expect(menu).toBeVisible()
  await expect(page.getByText("Shift+right-click shows your browser's menu")).toBeVisible()
  await expect(menu.getByRole('menuitem', { name: 'Fill in here' })).toBeFocused()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await page.keyboard.press('Escape')
  await expect(menu).toBeHidden()

  // ---- With the keyboard alone: the button, a line from the list, a place in it, a name.
  await page.getByRole('button', { name: 'Add a fill spot', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(dialog.getByLabel('Find a paragraph')).toBeFocused()
  await page.keyboard.type('Action Items')
  await page.keyboard.press('ArrowDown')
  await expect(dialog.getByRole('listbox', { name: 'Which paragraph?' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(dialog.getByRole('radio', { name: 'At the end of the paragraph' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(dialog.getByLabel('Name', { exact: true })).toBeFocused()
  await page.keyboard.type('Items reviewed by')
  await page.keyboard.press('Enter')
  const reviewedBy = page.getByLabel(/^Items reviewed by/)
  await expect(reviewedBy).toBeFocused({ timeout: 90_000 })
  await page.keyboard.type('Alex Chen')
  // The new spots are the page's own now: both are counted, and a pause saves what was typed.
  await expect(page.locator('.save-status')).toHaveText('Saved', { timeout: 15_000 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // ---- Exported, the values are in the Word file where the spots were added.
  await page.getByRole('button', { name: 'Export', exact: true }).click()
  const exportDialog = page.getByRole('dialog', { name: 'Export' })
  await expect(exportDialog.getByText('Ready to export.')).toBeVisible({ timeout: 60_000 })
  await exportDialog.getByRole('button', { name: 'Approve and export' }).click()
  await expect(exportDialog.getByText('Both files exported.', { exact: true })).toBeVisible({ timeout: 60_000 })
  const downloadPromise = page.waitForEvent('download')
  await exportDialog.getByRole('link', { name: 'Download Word file (.docx)', exact: true }).click()
  const download = await downloadPromise
  const filePath = testInfo.outputPath('fill-in-here.docx')
  await download.saveAs(filePath)
  const text = execFileSync('unzip', ['-p', filePath, 'word/document.xml'], { encoding: 'utf8' }).replace(/<[^>]*>/g, '')
  expect(text).toMatch(/Decisions\s*Priya Rao/)
  expect(text).toMatch(/Action Items\s*Alex Chen/)
  await exportDialog.getByRole('button', { name: 'Close' }).click()

  await trashTemplatesNamed(page, name)
})
