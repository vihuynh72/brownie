import { test, expect } from '@playwright/test'

/**
 * Three journeys an independent review first wrote as deliberately red
 * acceptance tests against real defects: sign-out that did not end the
 * session, an identity failure that stranded every page, and a failed
 * optional upload whose warning vanished on navigation. They run in the
 * default suite now that the product behaviour is fixed; each still checks
 * the server or the landed-on page, never a possibly cached view.
 */

const BACKEND_ORIGIN = process.env.BROWNIE_E2E_BACKEND_ORIGIN ?? 'http://localhost:8081'

test('Sign out invalidates the authenticated session and finishes at the identity provider', async ({ browser }) => {
  // Sign-out really ends the server session now, so this test must not spend the one shared
  // session every other spec's context reuses from global-setup -- it seeds a throwaway session
  // of its own, exactly the way global-setup seeds the shared one.
  const context = await browser.newContext({ storageState: undefined })
  const seeded = await context.request.get(`${BACKEND_ORIGIN}/test-support/sessions?subject=brownie-e2e-signout-${Date.now()}`)
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
  await page.goto('/documents/new')
  await expect.poll(() => identityRequests).toBeGreaterThan(0)

  // The application must explain the failure, not silently strand the user.
  await expect(page.getByRole('alert')).toContainText(/try again|retry|reload/i)

  // And the retry must really retry: let the next attempt through and the page recovers by itself.
  await page.unroute('**/api/v1/me')
  await page.getByRole('button', { name: 'Try again', exact: true }).click()
  await expect(page.getByLabel('Template', { exact: true })).toBeEnabled({ timeout: 15_000 })
})

test('creating a document preserves the warning when its optional source upload fails', async ({ page }) => {
  let uploadAttempts = 0
  await page.route('**/api/v1/workspaces/*/uploads', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    uploadAttempts++
    await route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"Review upload outage"}' })
  })
  await page.goto('/documents/new')
  await page.getByLabel('Template', { exact: true }).selectOption({ label: 'Flowing meeting minutes' })
  const title = `Review upload recovery ${Date.now()}`
  await page.getByLabel('Title', { exact: true }).fill(title)
  await page.setInputFiles('#source', {
    name: 'review-notes.txt', mimeType: 'text/plain',
    buffer: Buffer.from('Synthetic review notes. No personal information.'),
  })
  await page.getByRole('button', { name: 'Create document', exact: true }).click()
  await page.waitForURL(/\/documents\/\d+$/)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  expect(uploadAttempts).toBe(1)

  // A real RouterView unmounts the creation page. A component-only mount
  // can leave its local warning visible and miss this regression.
  await expect(page.getByRole('alert')).toContainText(/source file could not be attached/i)
})
