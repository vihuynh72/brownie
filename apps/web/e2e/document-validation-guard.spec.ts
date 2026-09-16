import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

/**
 * Creates a document from one of the workspace's own built-in templates
 * (auto-provisioned on first login by BuiltInTemplateProvisioningService,
 * the same as a real new user) and drives the real validate step against
 * it. The document is deliberately left empty: this app has no manual
 * field-value editing UI yet (WorkspaceView's Content pane is read-only;
 * the only way to populate a field today is the grounded-extraction path,
 * which needs a live model call this suite does not make -- see
 * e2e/README.md), so an empty document's required fields staying blocked
 * is the one real, complete, safe-to-automate proof that the export gate
 * actually refuses incomplete content when driven through a real browser.
 */
test('an empty document blocks export at validation and never offers to approve it', async ({ page }) => {
  await page.goto('/documents/new')

  await expect(page.getByLabel('Template')).toBeEnabled({ timeout: 15_000 })
  const title = `E2E empty document ${Date.now()}`
  await page.getByLabel('Title').fill(title)

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
