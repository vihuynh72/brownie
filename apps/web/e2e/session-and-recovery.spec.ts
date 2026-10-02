import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

/**
 * Three journeys an independent review first wrote as deliberately red
 * acceptance tests against real defects: sign-out that did not end the
 * session, an identity failure that stranded every page, and a failed
 * upload whose explanation vanished (now a form whose upload fails, said
 * on Home where the file was chosen). They run in the default suite now
 * that the product behaviour is fixed; each still checks the server or the
 * page the person is on, never a possibly cached view.
 */

const BACKEND_ORIGIN = process.env.BROWNIE_E2E_BACKEND_ORIGIN ?? 'http://localhost:8081'
const FORM_DOCX = fileURLToPath(new URL('../../../fixtures/public/templates/table-led-meeting-minutes.docx', import.meta.url))

test('Sign out invalidates the authenticated session and finishes at the identity provider', async ({ browser }) => {
  // Sign-out really ends the server session now, so this test must not spend the one shared
  // session every other spec's context reuses from global-setup -- it seeds a throwaway session
  // of its own, exactly the way global-setup seeds the shared one.
  const context = await browser.newContext({ storageState: undefined })
  const seeded = await context.request.get(`${BACKEND_ORIGIN}/test-support/sessions?subject=brownie-e2e-signout-${Date.now()}`, {
    headers: { 'X-Test-Support-Token': process.env.BROWNIE_TEST_SUPPORT_TOKEN ?? '' },
  })
  expect(seeded.ok()).toBe(true)
  const page = await context.newPage()

  // The server's JSON logout answer names the real provider's end-session URL, which the page then
  // navigates to. Stub that one external hop so this suite stays offline-safe and never depends on
  // a real Microsoft page rendering -- the assertion that matters is against our own server.
  await page.route(/ciamlogin\.com|login\.microsoftonline\.com/, (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<p>Identity provider sign-out (stubbed in this test).</p>' }),
  )

  await page.goto('/')
  expect((await context.request.get('/api/v1/me')).status()).toBe(200)
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()

  // Check the server, not a possibly cached page or a changed URL.
  await expect.poll(async () => (await context.request.get('/api/v1/me')).status()).toBe(401)
  await context.close()
})

test('a failed identity request shows a recoverable error instead of indefinite loading', async ({ page }) => {
  let identityRequests = 0
  await page.route('**/api/v1/me', async (route) => {
    identityRequests++
    await route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"Review outage"}' })
  })
  await page.goto('/')
  await expect.poll(() => identityRequests).toBeGreaterThan(0)

  // The application must explain the failure, not silently strand the user.
  await expect(page.getByRole('alert')).toContainText(/try again|retry|reload/i)

  // And the retry must really retry: let the next attempt through and the page recovers by itself.
  await page.unroute('**/api/v1/me')
  await page.getByRole('button', { name: 'Try again', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Upload your documents', exact: true })).toBeVisible({ timeout: 15_000 })
})

test('a form whose upload fails is explained on Home, where the person chose it, and nothing is made', async ({ page }) => {
  let uploadAttempts = 0
  await page.route('**/api/v1/workspaces/*/uploads', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    uploadAttempts++
    await route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"Review upload outage"}' })
  })
  await page.goto('/')
  const upload = page.getByRole('button', { name: 'Upload your documents', exact: true })
  const name = `Review upload recovery ${Date.now()}`
  const chooser = page.waitForEvent('filechooser')
  await upload.click()
  await (await chooser).setFiles({
    name: `${name}.docx`,
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: await readFile(FORM_DOCX),
  })

  await expect(page.getByRole('alert')).toHaveText(`Could not upload "${name}.docx". Review upload outage`)
  expect(uploadAttempts).toBe(1)
  await expect(page).toHaveURL(/\/$/)
  // Ready for another try: the button is usable again and no step is still said to be under way.
  await expect(upload).not.toHaveAttribute('aria-disabled', 'true')
  await expect(page.getByRole('status').filter({ hasText: /…$/ })).toHaveCount(0)
  await expect(page.locator('#app-sidebar').getByRole('button', { name, exact: true })).toHaveCount(0)
})

test('a mistyped address gets a page that says so and a way back, not a blank main region', async ({ page }) => {
  await page.goto('/documents/12/whatever')
  await expect(page.getByRole('heading', { name: 'There is nothing at this address' })).toBeVisible()
  await page.getByRole('link', { name: 'Go to your documents' }).click()
  await expect(page).toHaveURL(/\/$/)
})
