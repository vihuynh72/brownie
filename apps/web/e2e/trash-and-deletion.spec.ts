import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'

/**
 * The whole life of a document someone no longer wants, in a real browser
 * against the real API and database: it leaves the home list for the trash
 * bin, its own address stops answering, it comes back exactly as it was,
 * and the second time it is deleted for good, after which the server itself
 * (not only the page) says it does not exist. No model call is involved.
 */
test('a document goes to the trash, comes back unchanged, and is then deleted for good', async ({ page, context }) => {
  await page.goto('/documents/new')
  await expect(page.getByLabel('Template', { exact: true })).toBeEnabled({ timeout: 15_000 })
  const title = `E2E trash journey ${Date.now()}`
  await page.getByLabel('Title', { exact: true }).fill(title)
  await page.getByRole('button', { name: 'Create document' }).click()
  await page.waitForURL(/\/documents\/\d+$/)
  const documentUrl = new URL(page.url())
  const documentId = Number(documentUrl.pathname.split('/').pop())
  const workspaceId = (await (await context.request.get('/api/v1/me')).json()).memberships[0].workspaceId

  // Home: one click, no question, because nothing is lost yet.
  await page.goto('/')
  await page.getByRole('button', { name: `Move ${title} to the trash` }).click()
  await expect(page.getByText(`Moved "${title}" to the trash.`)).toBeVisible()
  await expect(page.getByRole('link', { name: title })).toHaveCount(0)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // Its own address no longer answers, in the page or on the server.
  expect((await context.request.get(`/api/v1/workspaces/${workspaceId}/documents/${documentId}`)).status()).toBe(404)
  await page.goto(documentUrl.pathname)
  await expect(page.getByText('It may have been moved to the trash, where it can be restored.')).toBeVisible()

  // The trash bin has it, one click away, with the day it would be deleted for good.
  await page.getByRole('link', { name: 'Open the trash bin' }).click()
  await expect(page).toHaveURL(/\/trash$/)
  const row = page.getByRole('listitem').filter({ hasText: title })
  await expect(row).toBeVisible()
  await expect(row.getByText(/Deleted for good on/)).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await row.getByRole('button', { name: `Restore ${title}` }).click()
  await expect(page.getByText(`Restored "${title}".`)).toBeVisible()
  await expect(row).toHaveCount(0)

  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // Back at the same address with the same title.
  await page.getByRole('link', { name: 'Open it' }).click()
  await page.waitForURL(documentUrl.pathname)
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()

  // Second time round: delete forever asks once, by name.
  await page.goto('/')
  await page.getByRole('button', { name: `Move ${title} to the trash` }).click()
  await page.getByRole('link', { name: 'Restore it from the trash bin' }).click()
  await page.waitForURL(/\/trash$/)
  const again = page.getByRole('listitem').filter({ hasText: title })
  await again.getByRole('button', { name: `Delete forever ${title}` }).click()
  await expect(page.getByText(`Delete "${title}" forever? This cannot be undone.`)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Yes, delete forever' })).toBeFocused()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // Backing out with the keyboard changes nothing and returns focus to where it was.
  await page.keyboard.press('Escape')
  await expect(again.getByRole('button', { name: `Delete forever ${title}` })).toBeFocused()

  // A run that is still stopping is a "wait a moment", not a failure: nothing is deleted, the row
  // stays, and a keyboard user is put back on the button they pressed instead of losing their place.
  await page.route('**/deletions/*/purge', (route) =>
    route.fulfill({
      status: 409,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        status: 409,
        title: 'Conflict',
        detail: 'A run for this document is still stopping. Try again in a moment.',
        code: 'DELETION_WAITING_FOR_RUNNING_WORK',
        correlationId: 'e2e',
        fields: [],
        recoveryActions: [],
      }),
    }),
  )
  await again.getByRole('button', { name: `Delete forever ${title}` }).click()
  await page.getByRole('button', { name: 'Yes, delete forever' }).click()
  await expect(page.getByRole('alert')).toContainText('is still stopping, so nothing was deleted')
  await expect(page.getByRole('button', { name: 'Yes, delete forever' })).toBeFocused()
  await page.unroute('**/deletions/*/purge')

  await page.getByRole('button', { name: 'Yes, delete forever' }).click()
  await expect(page.getByText(`Deleted "${title}" for good.`)).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await expect(again).toHaveCount(0)

  // The server agrees: the document is gone and the ledger says it was deleted, without keeping its title.
  expect((await context.request.get(`/api/v1/workspaces/${workspaceId}/documents/${documentId}`)).status()).toBe(404)
  const ledger = await (await context.request.get(`/api/v1/workspaces/${workspaceId}/deletions`)).json()
  const entry = ledger.find(
    (candidate: { targetId: number; state: string }) =>
      candidate.targetId === documentId && ['PURGED', 'VERIFIED'].includes(candidate.state),
  )
  expect(entry).toBeDefined()
  expect(entry.title ?? null).toBeNull()
})
