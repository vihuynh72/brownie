import { test, expect, type Page } from '@playwright/test'

/**
 * The application shell: the navigation beside every page, and what
 * happens when someone who is not signed in reaches for something that
 * needs a workspace.
 *
 * The signed-out cases run in their own browser context with no stored
 * session, because every other spec in this suite reuses the one session
 * global setup seeds.
 */
const BACKEND_ORIGIN = process.env.BROWNIE_E2E_BACKEND_ORIGIN ?? 'http://localhost:8081'

async function expectNoSidewaysScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow, 'page must not be wider than the viewport').toBeLessThanOrEqual(0)
}

test('a signed-out visitor reaching for an upload is sent to sign in, and lands back on what they were doing', async ({
  browser,
}) => {
  const context = await browser.newContext({ storageState: undefined })
  const page = await context.newPage()

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Welcome to Brownie!' })).toBeVisible()

  await page.getByRole('link', { name: 'Upload your documents' }).click()
  await expect(page).toHaveURL(/\/signin\?next=(%2F|\/)documents(%2F|\/)new$/)
  await expect(page.getByText('to upload your documents')).toBeVisible()

  // A real session against the same backend the app talks to, so what follows is the
  // application's own behaviour and not a mocked identity.
  const seeded = await context.request.get(
    `${BACKEND_ORIGIN}/test-support/sessions?subject=brownie-e2e-shell-${Date.now()}`,
    { headers: { 'X-Test-Support-Token': process.env.BROWNIE_TEST_SUPPORT_TOKEN ?? '' } },
  )
  expect(seeded.ok()).toBe(true)

  // Stands in for the provider round trip, which really does end at this app's home
  // address: the destination the click parked has to survive that and be followed.
  await page.route('**/oauth2/authorization/entra', (route) =>
    route.fulfill({ status: 302, headers: { location: '/' }, body: '' }),
  )
  await page.getByRole('link', { name: 'Sign in or sign up' }).click()

  await page.waitForURL(/\/documents\/new$/)
  await expect(page.getByLabel('Template', { exact: true })).toBeVisible({ timeout: 15_000 })
  await context.close()
})

test('a signed-out visitor following Trash Bin is sent to sign in, carrying that destination', async ({ browser }) => {
  const context = await browser.newContext({ storageState: undefined })
  const page = await context.newPage()

  await page.goto('/')
  await page.getByRole('link', { name: 'Trash Bin' }).click()

  await expect(page).toHaveURL(/\/signin\?next=(%2F|\/)trash$/)
  await expect(page.getByText('to open your trash bin')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Sign in or sign up' })).toBeVisible()
  await context.close()
})

test('the sidebar collapses, stays collapsed across a reload, and reopens', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/')

  await expect(page.getByRole('link', { name: 'Home', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Hide sidebar' }).click()
  await expect(page.getByRole('link', { name: 'Home', exact: true })).toBeHidden()

  await page.reload()
  await expect(page.getByRole('button', { name: 'Show sidebar' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Home', exact: true })).toBeHidden()

  await page.getByRole('button', { name: 'Show sidebar' }).click()
  await expect(page.getByRole('link', { name: 'Home', exact: true })).toBeVisible()
  await expectNoSidewaysScroll(page)
})

test('at phone width the sidebar is a drawer that opens, closes on Escape, and never widens the page', async ({
  page,
}) => {
  await page.setViewportSize({ width: 400, height: 780 })
  await page.goto('/')

  await expect(page.getByRole('link', { name: 'Trash Bin' })).toBeHidden()
  await expectNoSidewaysScroll(page)

  await page.getByRole('button', { name: 'Open menu' }).click()
  await expect(page.getByRole('link', { name: 'Trash Bin' })).toBeVisible()
  await expectNoSidewaysScroll(page)

  await page.keyboard.press('Escape')
  await expect(page.getByRole('link', { name: 'Trash Bin' })).toBeHidden()
  await expect(page.getByRole('button', { name: 'Open menu' })).toBeFocused()
})

test('the trash bin says why it is empty instead of showing a list that failed to load', async ({ page }) => {
  await page.goto('/trash')

  await expect(page.getByRole('heading', { name: 'Your trash bin is empty' })).toBeVisible()
  await expect(page.getByText('nothing you can do in the app removes a document')).toBeVisible()
})

test('Chat sends you to the document the conversation would be about', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('link', { name: 'Chat', exact: true }).click()

  await expect(page).toHaveURL(/\/chat$/)
  await expect(page.getByRole('heading', { name: 'Chat with Brownie' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Start a new document' })).toBeVisible()
})
