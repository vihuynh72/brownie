import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

/**
 * Creates a document from one of the workspace's own built-in templates
 * (auto-provisioned on first login by BuiltInTemplateProvisioningService,
 * the same as a real new user) and drives the real validate step against
 * it while it is still empty: the export gate must refuse a document whose
 * required fields are blank, and must never offer approval for it. The
 * filled-in half of the same journey is manual-editing.spec.ts.
 */
test('an empty document blocks export at validation and never offers to approve it', async ({ page }) => {
  await page.goto('/documents/new')

  await expect(page.getByLabel('Template', { exact: true })).toBeEnabled({ timeout: 15_000 })
  const title = `E2E empty document ${Date.now()}`
  await page.getByLabel('Title', { exact: true }).fill(title)

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Create document' }).click()
  await page.waitForURL(/\/documents\/\d+$/)

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('tab', { name: 'Checks' }).click()
  await page.getByRole('button', { name: 'Validate this revision' }).click()

  await expect(page.getByText('Cannot export')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('button', { name: 'Approve for export' })).toHaveCount(0)

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
