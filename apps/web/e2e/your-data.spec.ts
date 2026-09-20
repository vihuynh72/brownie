import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

/**
 * The "Your data" page against the real server: that it shows the numbers
 * the server is configured with, and that deleting a workspace from it
 * really deletes it. The deletion journey signs in as somebody of its own,
 * because it ends every session of the person it runs as, and the session
 * every other spec shares must survive it.
 */

const BACKEND_ORIGIN = process.env.BROWNIE_E2E_BACKEND_ORIGIN ?? 'http://localhost:8081'

test('the page says how long things are kept, in the numbers the server is configured with', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('link', { name: 'Your data', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your data', level: 1 })).toBeVisible()

  const practices = await (await page.request.get('/api/v1/data-practices')).json()
  await expect(page.getByText(`restored for ${practices.trashRetentionDays} days`)).toBeVisible()
  await expect(page.getByText(`kept for ${practices.auditRecordDays} days`)).toBeVisible()
  await expect(page.getByText(`${practices.modelProvider} (${practices.modelName})`)).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Delete everything' })).toBeVisible()

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

test('deleting the workspace needs the typed phrase, really deletes it, and ends the session', async ({ browser }) => {
  const context = await browser.newContext({ storageState: undefined })
  const seeded = await context.request.get(`${BACKEND_ORIGIN}/test-support/sessions?subject=brownie-e2e-delete-me-${Date.now()}`, {
    headers: { 'X-Test-Support-Token': process.env.BROWNIE_TEST_SUPPORT_TOKEN ?? '' },
  })
  expect(seeded.ok()).toBe(true)
  const page = await context.newPage()
  const me = await (await context.request.get('/api/v1/me')).json()
  const workspaceId = me.memberships[0].workspaceId

  await page.goto('/your-data')
  await page.getByRole('button', { name: 'Delete my workspace…' }).click()
  const phrase = page.getByLabel(/To delete everything, type/)
  await expect(phrase).toBeFocused()
  const confirm = page.getByRole('button', { name: 'Delete everything for good' })
  await expect(confirm).toBeDisabled()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // Backing out changes nothing and hands focus back to where it came from.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Delete my workspace…' })).toBeFocused()
  expect((await context.request.get(`/api/v1/workspaces/${workspaceId}/documents`)).status()).toBe(200)

  await page.getByRole('button', { name: 'Delete my workspace…' }).click()
  await phrase.fill('delete my workspace')
  await expect(confirm).toBeEnabled()
  await confirm.click()

  // The page that follows is the signed-out one, and it says what just happened.
  await expect(page.getByRole('status')).toContainText('Your workspace and everything in it was deleted.')
  // Check the server, not the page: the session is over, and it was this person's only way in.
  await expect.poll(async () => (await context.request.get('/api/v1/me')).status()).toBe(401)
  await context.close()
})

/**
 * What the owner saw: this app in front of a server built before these pages existed. Such a server has no
 * data-practices or trash routes and sends no trash period. Neither page may print a sentence with a hole in it, tell
 * anyone to reload, or hide the way to delete everything.
 */
test('in front of an older server, both pages say what is wrong and keep what does not depend on it', async ({ page }) => {
  const noSuchRoute = (path: string) => ({
    status: 404,
    contentType: 'application/problem+json',
    body: JSON.stringify({
      type: 'about:blank', title: 'Not Found', status: 404, detail: `No static resource ${path}.`,
      code: 'NOT_FOUND', correlationId: 'e2e', fields: [], recoveryActions: [],
    }),
  })
  await page.route('**/api/v1/data-practices', (route) => route.fulfill(noSuchRoute('api/v1/data-practices')))
  await page.route('**/api/v1/workspaces/*/deletions', (route) =>
    route.request().method() === 'GET' ? route.fulfill(noSuchRoute('api/v1/workspaces/1/deletions')) : route.continue(),
  )
  await page.route('**/api/v1/capabilities', async (route) => {
    const response = await route.fetch()
    const body = await response.json()
    delete body.trashRetentionDays
    await route.fulfill({ response, json: body })
  })

  await page.goto('/your-data')
  await expect(page.getByRole('alert')).toContainText('older than this page')
  await expect(page.getByRole('heading', { name: 'Delete everything' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Delete my workspace…' })).toBeVisible()
  // Saying that reloading will not help is fine; advising it is what the old page did wrong.
  expect(await page.locator('main').innerText()).not.toMatch(/try reloading|reload the page|reloading it/i)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await page.goto('/trash')
  await expect(page.getByRole('alert')).toContainText('older than this page')
  const explanation = await page.locator('main').innerText()
  expect(explanation).not.toMatch(/for\s+days/)
  expect(explanation).not.toContain('undefined')
  expect(explanation).not.toMatch(/try reloading|reload the page|reloading it/i)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})
