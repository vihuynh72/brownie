import { test, expect } from '@playwright/test'

const QA = process.env.HOME + '/brownie-scratch/autopilot/qa'
const FORM = process.env.HOME + '/brownie-scratch/autopilot/forms/sweep/two-page-application.pdf'
const NAME = 'two-page-application'

test('typed answers in the document chat fill the form', async ({ page }) => {
  test.setTimeout(360_000)
  await page.goto('/')
  const sidebar = page.locator('aside, nav, [role="navigation"]').first()
  const add = sidebar.getByRole('button', { name: 'Add a template from a file', exact: true })
  await add.waitFor({ timeout: 30_000 })
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), add.click()])
  await chooser.setFiles(FORM)
  await sidebar.getByRole('button', { name: `Start a document from ${NAME}`, exact: false }).waitFor({ timeout: 180_000 })
  await page.screenshot({ path: `${QA}/chat-fill-template.png` })

  await sidebar.getByRole('button', { name: `Start a document from ${NAME}`, exact: false }).click()
  await page.waitForTimeout(4000)
  // Keep the places Brownie found if a review is offered.
  const keep = page.getByRole('button', { name: /Keep all the places|keep/i }).first()
  if (await keep.count()) await keep.click().catch(() => {})

  const composer = page.locator('textarea').last()
  await composer.fill('my name is Jordan Rivera, email jordan.rivera@example.com, phone (408) 555-0142, born March 14 1996')
  await page.screenshot({ path: `${QA}/chat-fill-typed.png` })
  await composer.press('Enter')

  // The message becomes a source; the run proposes; the person accepts.
  const approve = page.getByRole('button', { name: /I approve, fill it in|Approve|Accept/ }).first()
  await approve.waitFor({ timeout: 240_000 })
  await page.screenshot({ path: `${QA}/chat-fill-proposal.png` })
  await approve.click()
  await page.waitForTimeout(3000)
  await page.screenshot({ path: `${QA}/chat-fill-filled.png` })
  await expect(page.getByText(/Jordan Rivera/).first()).toBeVisible({ timeout: 20_000 })
})
