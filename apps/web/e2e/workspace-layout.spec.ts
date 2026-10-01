import { test, expect, type Page } from '@playwright/test'
import { startDocumentFromSidebar } from './documents'

/**
 * The workspace at the widths that matter: a laptop with the sidebar open
 * (1126px), a phone (406px), and a wide desktop (1440px). Nothing may scroll
 * the page sideways; where the document and Brownie's panel cannot sit side
 * by side a switch shows one at a time; and the bar about a selected row is
 * the thing under the pointer, not the page beneath it.
 */
async function openADocumentWithARow(page: Page): Promise<void> {
  await startDocumentFromSidebar(page, 'Flowing meeting minutes')
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible({ timeout: 15_000 })
  await page.getByRole('button', { name: 'Add row' }).click()
}

async function expectNoSidewaysScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow, 'page must not be wider than the viewport').toBeLessThanOrEqual(0)
}

async function expectInsideViewport(page: Page, name: string, box: { x: number; width: number } | null): Promise<void> {
  const width = await page.evaluate(() => window.innerWidth)
  expect(box, `${name} has a box`).not.toBeNull()
  expect(box!.x + box!.width, `${name} ends inside the viewport`).toBeLessThanOrEqual(width + 1)
}

test('at a laptop width with the sidebar open, the document and Brownie sit side by side and nothing overflows', async ({ page }) => {
  await page.setViewportSize({ width: 1126, height: 800 })
  await openADocumentWithARow(page)
  await expectNoSidewaysScroll(page)
  await expect(page.getByLabel('How may I help you?')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Brownie', exact: true })).toBeHidden()
  await expectInsideViewport(page, 'Export', await page.getByRole('button', { name: 'Export', exact: true }).boundingBox())
})

test('at phone width a switch shows the document or Brownie, one at a time, without running off the screen', async ({ page }) => {
  await page.setViewportSize({ width: 406, height: 800 })
  await openADocumentWithARow(page)
  await expectNoSidewaysScroll(page)
  await expect(page.getByLabel('How may I help you?')).toBeHidden()

  await page.getByRole('button', { name: 'Brownie', exact: true }).click()
  await expect(page.getByLabel('How may I help you?')).toBeVisible()
  await expect(page.getByLabel(/^Meeting title/)).toBeHidden()
  await expectNoSidewaysScroll(page)
  await expectInsideViewport(page, 'Send', await page.getByRole('button', { name: 'Send', exact: true }).boundingBox())

  await page.getByRole('button', { name: 'Document', exact: true }).click()
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible()
  await expectInsideViewport(page, 'Export', await page.getByRole('button', { name: 'Export', exact: true }).boundingBox())
})

test('on a wide desktop, the bar about a selected row is the thing under the pointer, and removing the row works', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openADocumentWithARow(page)
  await expectNoSidewaysScroll(page)
  await page.getByLabel('Action item task, row 1', { exact: true }).focus()
  const remove = page.getByRole('button', { name: 'Remove row 1' })
  await expect(remove).toBeVisible()
  const box = (await remove.boundingBox())!
  const hit = await page.evaluate(
    ([x, y]) => (document.elementFromPoint(x, y) as HTMLElement | null)?.closest('button')?.textContent?.trim() ?? null,
    [box.x + box.width / 2, box.y + box.height / 2],
  )
  expect(hit).toBe('Remove row 1')
  await remove.click()
  await expect(page.getByLabel('Action item task, row 1', { exact: true })).toHaveCount(0)
})

test('where the document scrolls on its own, the scroll keys move it once it has focus', async ({ page }) => {
  await page.setViewportSize({ width: 1372, height: 620 })
  await openADocumentWithARow(page)
  const pane = page.getByRole('region', { name: 'Document area' })
  await pane.focus()
  await page.keyboard.press('PageDown')
  await expect.poll(() => pane.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  // The page itself stays put: only the document moved.
  expect(await page.evaluate(() => window.scrollY)).toBe(0)
})
