import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { fileURLToPath } from 'node:url'

const FIXTURE_DOCX = fileURLToPath(new URL('../../../fixtures/public/templates/flowing-meeting-minutes.docx', import.meta.url))

/**
 * Teaches a brand-new template end to end through the real UI against a
 * real backend: upload a real fixture DOCX, save its auto-suggested field
 * bindings, propose and accept a rule, then activate the version. No AI
 * model call is on this path -- content-control-tag detection during
 * template teaching is deterministic DOCX-structure parsing, unlike the
 * document-side grounded-extraction path this suite deliberately never
 * drives (see e2e/README.md).
 */
test('teaches a new template from a real DOCX, proposes and accepts a rule, and activates it', async ({ page }) => {
  await page.goto('/templates/new')

  const templateName = `E2E flowing minutes ${Date.now()}`
  await page.getByLabel('Template name').fill(templateName)
  await page.locator('#template-source').setInputFiles(FIXTURE_DOCX)

  const fieldRows = page.locator('.field-row-edit')
  await expect(fieldRows.first()).toBeVisible({ timeout: 15_000 })
  await expect(page.getByRole('button', { name: 'Save fields' })).toBeEnabled()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.getByRole('button', { name: 'Save fields' }).click()
  await expect(page.getByRole('heading', { name: 'Propose a rule' })).toBeVisible()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // REQUIRED_FIELDS is the form's own default rule kind and WHOLE_TEMPLATE its default scope --
  // checking one field and submitting is the minimal real, valid proposal.
  await page.locator('.rule-fieldset input[type="checkbox"]').first().check()
  await page.getByRole('button', { name: 'Propose rule' }).click()

  const ruleRow = page.locator('.rule-row').first()
  await expect(ruleRow).toBeVisible()
  await expect(ruleRow.getByText('Require:')).toBeVisible()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await ruleRow.getByRole('button', { name: /^Accept rule:/ }).click()
  await expect(ruleRow.locator('.badge')).toHaveText('ACCEPTED')

  await page.getByRole('button', { name: 'Activate template' }).click()
  await expect(page.getByText(`"${templateName}" is now active and ready to use.`)).toBeVisible()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
