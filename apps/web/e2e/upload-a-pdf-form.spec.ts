import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { FILLABLE_PDF, exportAsPdf, pdfFieldValues, trashTemplatesNamed, uploadPdfFromHome } from './pdfForms'

/**
 * A PDF with fields of its own to type in, from Home to the exported file, against the real API:
 * the pages are drawn as they print with a fill spot over each field, two of them are filled (one in
 * Vietnamese), the export offers a PDF and nothing else, and the file downloaded holds the values in
 * the form's own fields, read back with PDF.js.
 */

// Written with escapes so the words are the same whatever the editor: "Nguyen Thi Minh Khai" with its marks.
const VIETNAMESE_NAME = 'Nguyễn Thị Minh Khai'
const SECOND_VALUE = '12 Hoa Lan Street'

test('a PDF form is filled on its own pages and exported as a PDF holding the values in its fields', async ({ page }, testInfo) => {
  // Exporting checks the version first, which fills and reads back the PDF on the server.
  test.setTimeout(180_000)
  const name = `E2E PDF application ${Date.now()}`
  await uploadPdfFromHome(page, FILLABLE_PDF, name)

  // The form's own field for the name keeps the name the form gives it, laid over the page where it prints.
  const fullName = page.getByLabel(/^Full name/)
  await expect(fullName).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('[data-page="1"]').getByLabel(/^Full name/)).toBeVisible()
  // The page's words are also there as text, for anyone who cannot see the picture of the page.
  await page.getByText('Text on this page').click()
  await expect(page.locator('.pdf-form-page__lines').first()).toContainText('Full name:')
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await fullName.fill(VIETNAMESE_NAME)
  // The next place in the keyboard's order, below the name: whatever the form calls it, it takes a line of text.
  const spots = page.locator('[data-page="1"] .pdf-form-page__spot textarea:not([readonly])')
  const second = spots.nth(1)
  await second.fill(SECOND_VALUE)
  // A pause in typing saves it.
  await expect(page.locator('.save-status')).toHaveText('Unsaved changes')
  await expect(page.locator('.save-status')).toHaveText('Saved', { timeout: 15_000 })

  // A form's own field is placed and styled by the form: the bar about it says so, and offers no text size.
  await fullName.focus()
  await fullName.press('Alt+Enter')
  const bar = page.locator('section.selection-bar')
  await expect(bar).toContainText('This box belongs to the PDF’s own form')
  await expect(bar.getByRole('button', { name: /^Text size/ })).toHaveCount(0)
  await bar.press('Escape')

  const bytes = await exportAsPdf(page, testInfo, 'membership-application.pdf')
  const values = await pdfFieldValues(bytes)
  expect(values.get('fullName')).toBe(VIETNAMESE_NAME)
  expect([...values.values()]).toContain(SECOND_VALUE)

  await trashTemplatesNamed(page, name)
})
