import { test, expect, type Page } from '@playwright/test'

/**
 * The workspace at the widths a review found it broken at: a laptop with a
 * side panel open (1126px), a phone (406px), and the default desktop with
 * the preview pane open. Nothing may scroll the page sideways, every
 * inspector tab must sit inside the viewport, and the action-item row
 * controls must be the thing under the pointer, not the preview card.
 */
async function openADocumentWithARow(page: Page): Promise<void> {
  await page.goto('/documents/new')
  await page.getByLabel('Template').selectOption({ label: 'Flowing meeting minutes' })
  await page.getByLabel('Title').fill(`E2E layout ${Date.now()}`)
  await page.getByRole('button', { name: 'Create document' }).click()
  await page.waitForURL(/\/documents\/\d+$/)
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible({ timeout: 15_000 })
  await page.getByRole('button', { name: 'Add row' }).click()
}

async function expectNoSidewaysScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow, 'page must not be wider than the viewport').toBeLessThanOrEqual(0)
}

async function expectTabsInsideViewport(page: Page): Promise<void> {
  const width = await page.evaluate(() => window.innerWidth)
  for (const name of ['Assist', 'Rules', 'Sources', 'Checks', 'History']) {
    const box = await page.getByRole('tab', { name }).boundingBox()
    expect(box, `${name} tab has a box`).not.toBeNull()
    expect(box!.x + box!.width, `${name} tab ends inside the viewport`).toBeLessThanOrEqual(width + 1)
  }
}

test('at a laptop-with-side-panel width the workspace fits and every tab is reachable', async ({ page }) => {
  await page.setViewportSize({ width: 1126, height: 800 })
  await openADocumentWithARow(page)
  await expectNoSidewaysScroll(page)
  await expectTabsInsideViewport(page)
})

test('at phone width the tab strip wraps instead of running off the screen', async ({ page }) => {
  await page.setViewportSize({ width: 406, height: 800 })
  await openADocumentWithARow(page)
  await page.getByRole('button', { name: 'Show details' }).click()
  await expectNoSidewaysScroll(page)
  await expectTabsInsideViewport(page)
})

test('with the preview open on a desktop, the action-item controls are the thing under the pointer', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openADocumentWithARow(page)
  if ((await page.getByRole('button', { name: 'Show preview' }).count()) > 0) {
    await page.getByRole('button', { name: 'Show preview' }).click()
  }
  await expect(page.getByRole('heading', { name: 'Preview' })).toBeVisible()
  await expectNoSidewaysScroll(page)
  const remove = page.getByRole('button', { name: 'Remove row 1' })
  await remove.scrollIntoViewIfNeeded()
  const box = (await remove.boundingBox())!
  const hit = await page.evaluate(
    ([x, y]) => (document.elementFromPoint(x, y) as HTMLElement | null)?.closest('button')?.textContent?.trim() ?? null,
    [box.x + box.width / 2, box.y + box.height / 2],
  )
  expect(hit).toBe('Remove')
  await remove.click()
  await expect(page.getByLabel('Action item task, row 1')).toHaveCount(0)
})
