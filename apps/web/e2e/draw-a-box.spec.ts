import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { FLAT_PDF, exportAsPdf, pdfText, screenPoint, scrollPageTo, trashTemplatesNamed, uploadPdfFromHome } from './pdfForms'

/**
 * A PDF with no fields of its own (its blanks are printed), against the real API: a box drawn with
 * the pointer and another placed from the keyboard, next to one of the page's lines, each named in
 * the dialog that follows, said in the chat with an Undo, filled, and written into the exported PDF.
 */

const DRAWN_VALUE = 'Call Mai on 0901 234 567'
const PLACED_VALUE = 'Trước thứ Sáu'

test('boxes drawn with the pointer and placed from the keyboard are filled and exported in the PDF', async ({ page }, testInfo) => {
  // Each box makes a new version of the form, checked by filling it once, and exporting checks the version again.
  test.setTimeout(180_000)
  const name = `E2E flat sign-up ${Date.now()}`
  await uploadPdfFromHome(page, FLAT_PDF, name)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // ---- With the pointer: a box across an empty part of the page, below the table and above the signature.
  const draw = page.getByRole('button', { name: 'Draw a box', exact: true })
  await draw.click()
  await expect(draw).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('status').filter({ hasText: 'Drag across the page where the box goes' })).toBeVisible()
  // Escape stops drawing without drawing anything.
  await page.keyboard.press('Escape')
  await expect(draw).toHaveAttribute('aria-pressed', 'false')
  await draw.click()

  await scrollPageTo(page, 1, 369)
  const from = await screenPoint(page, 1, 72, 360)
  const to = await screenPoint(page, 1, 320, 378)
  await page.mouse.move(from.x, from.y)
  await page.mouse.down()
  await page.mouse.move(to.x, to.y, { steps: 8 })
  await page.mouse.up()

  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: 'Name the fill spot' })).toBeVisible({ timeout: 15_000 })
  await expect(dialog.getByRole('radio', { name: 'Make the text smaller to fit (down to 6 pt)' })).toBeChecked()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await dialog.getByRole('textbox', { name: 'Name' }).fill('Emergency contact')
  await dialog.getByRole('button', { name: 'Add the fill spot' }).click()

  const drawn = page.getByLabel('Emergency contact', { exact: true })
  await expect(drawn).toBeFocused({ timeout: 60_000 })
  const chat = page.getByRole('region', { name: 'Conversation with Brownie' })
  await expect(chat.getByText('Added a fill spot for Emergency contact. New documents from this form will have it too.')).toBeVisible()
  await expect(chat.getByRole('button', { name: 'Undo adding Emergency contact' })).toBeVisible()
  await drawn.fill(DRAWN_VALUE)

  // ---- Zoom: the page is drawn larger, and the box stays on its place on the page.
  const paper = page.locator('[data-page="1"]')
  const placeOnPage = async () => {
    const [sheet, spot] = [await paper.boundingBox(), await drawn.boundingBox()]
    if (!sheet || !spot) throw new Error('The page or its box is not drawn.')
    return { width: sheet.width, x: (spot.x - sheet.x) / sheet.width, y: (spot.y - sheet.y) / sheet.width }
  }
  const fitted = await placeOnPage()
  const zoom = page.getByRole('group', { name: 'Zoom' })
  await zoom.getByRole('button', { name: 'Zoom in' }).click()
  await expect(page.getByRole('status').filter({ hasText: /^Zoomed to \d+%\.$/ })).toBeVisible()
  const zoomed = await placeOnPage()
  expect(zoomed.width).toBeGreaterThan(fitted.width)
  expect(zoomed.x).toBeCloseTo(fitted.x, 2)
  expect(zoomed.y).toBeCloseTo(fitted.y, 2)
  await zoom.getByRole('button', { name: 'Fit width' }).click()
  await expect(zoom.getByRole('button', { name: 'Fit width' })).toHaveAttribute('aria-pressed', 'true')

  // ---- From the keyboard: Add a fill spot, then a line of the page, then a name.
  await page.getByRole('button', { name: 'Add a fill spot', exact: true }).click()
  await expect(dialog.getByRole('heading', { name: 'Add a fill spot' })).toBeVisible()
  await dialog.getByRole('searchbox', { name: 'Find a line' }).fill('front desk')
  await dialog.getByRole('listbox', { name: 'Next to a line' }).selectOption({ label: 'Please return this form to the front desk.' })
  await dialog.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(dialog.getByRole('heading', { name: 'Name the fill spot' })).toBeVisible({ timeout: 15_000 })
  await expect(dialog.getByText('next to “Please return this form to the front desk.”')).toBeVisible()
  await dialog.getByRole('textbox', { name: 'Name' }).fill('Return by')
  await dialog.getByRole('button', { name: 'Add the fill spot' }).click()

  const placed = page.getByLabel('Return by', { exact: true })
  await expect(placed).toBeFocused({ timeout: 60_000 })
  await expect(chat.getByText('Added a fill spot for Return by. New documents from this form will have it too.')).toBeVisible()
  // The value typed into the first box is still there on the new version.
  await expect(drawn).toHaveValue(DRAWN_VALUE)
  await placed.fill(PLACED_VALUE)

  // ---- Moving a box with the keyboard: one point at a time, all moves sent together on Done.
  await page.getByRole('button', { name: 'Edit boxes', exact: true }).click()
  const handle = page.getByRole('button', { name: 'Move or resize Return by' })
  await handle.focus()
  await handle.press('ArrowUp')
  await handle.press('Shift+ArrowUp')
  await page.getByRole('button', { name: 'Done', exact: true }).click()
  await expect(chat.getByText('Moved the box for Return by. New documents from this form will have the change too.')).toBeVisible({ timeout: 60_000 })
  await expect(placed).toHaveValue(PLACED_VALUE)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  const bytes = await exportAsPdf(page, testInfo, 'volunteer-sign-up.pdf')
  const text = await pdfText(bytes)
  expect(text).toContain(DRAWN_VALUE)
  expect(text).toContain(PLACED_VALUE)
  // The page's own words are still there around the values.
  expect(text).toContain('Volunteer sign-up')

  await trashTemplatesNamed(page, name)
})
