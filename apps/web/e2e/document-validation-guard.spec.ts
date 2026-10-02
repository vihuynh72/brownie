import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { startDocumentFromSidebar } from './documents'

/**
 * Starts a document from one of the workspace's own built-in templates
 * (auto-provisioned on first login by BuiltInTemplateProvisioningService,
 * the same as a real new user) by pressing it in the sidebar, and opens
 * Export while it is still empty: the check Export runs must refuse a
 * document whose required fields are blank, and must never offer to
 * approve it. The filled-in half of the same journey is
 * manual-editing.spec.ts.
 */
test('an empty document blocks export when it is checked and never offers to approve it', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('#app-sidebar').getByRole('button', { name: 'Flowing meeting minutes', exact: true })).toBeVisible({
    timeout: 15_000,
  })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await startDocumentFromSidebar(page, 'Flowing meeting minutes')
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible({ timeout: 15_000 })

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Export', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Export' })
  await expect(dialog.getByText('This version cannot be exported yet.')).toBeVisible({ timeout: 60_000 })
  await expect(dialog.getByRole('button', { name: 'Approve and export' })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: /^Go to Meeting title/ })).toBeVisible()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // A finding leads back to the fill spot it is about.
  await dialog.getByRole('button', { name: /^Go to Meeting title/ }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByLabel(/^Meeting title/)).toBeFocused()
})
