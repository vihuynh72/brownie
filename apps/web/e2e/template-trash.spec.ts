import { test, expect, type BrowserContext } from '@playwright/test'

const TEMPLATE = 'Table-led meeting minutes'

/**
 * Takes the template back out of the Trash Bin through the API, so a failure part-way through this
 * spec never leaves the workspace without a template the other specs start documents from.
 */
async function restoreIfTrashed(context: BrowserContext): Promise<void> {
  const workspaceId = (await (await context.request.get('/api/v1/me')).json()).memberships[0].workspaceId
  const trashed = await (await context.request.get(`/api/v1/workspaces/${workspaceId}/templates?trashed=true`)).json()
  const xsrf = (await context.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
  for (const template of trashed as { id: number; displayName: string }[]) {
    if (template.displayName !== TEMPLATE) continue
    await context.request.post(`/api/v1/workspaces/${workspaceId}/templates/${template.id}/restore`, {
      headers: { 'X-XSRF-TOKEN': xsrf },
    })
  }
}

/**
 * A template leaves My Templates for the Trash Bin from the menu beside it (the "..." button, and
 * the same menu on a right-click), is listed in the Trash Bin with the words about what that
 * means, and comes back to My Templates when it is restored there. No model call is involved.
 */
test('a template goes to the Trash Bin from its menu and comes back from there', async ({ page, context }) => {
  // A run stopped part-way may have left it in the trash; this one starts from My Templates as it should be.
  await page.goto('/')
  await restoreIfTrashed(context)
  await page.goto('/')
  const sidebar = page.locator('#app-sidebar')
  const row = sidebar.getByRole('button', { name: TEMPLATE, exact: true })
  await expect(row).toBeVisible({ timeout: 15_000 })

  try {
    // A right-click on the template opens its menu at the pointer; Escape closes it again.
    await row.click({ button: 'right' })
    await expect(page.getByRole('menuitem', { name: 'Move to Trash Bin' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('menuitem', { name: 'Move to Trash Bin' })).toBeHidden()

    // The visible button opens the same menu, and choosing its one item moves the template.
    await sidebar.getByRole('button', { name: `More for ${TEMPLATE}` }).click()
    await page.getByRole('menuitem', { name: 'Move to Trash Bin' }).click()
    await expect(row).toHaveCount(0)
    await expect(sidebar.getByText(`Moved "${TEMPLATE}" to the Trash Bin.`)).toBeVisible()

    await sidebar.getByRole('link', { name: 'Trash Bin' }).first().click()
    await expect(page).toHaveURL(/\/trash$/)
    const templates = page.getByRole('region', { name: 'Templates' })
    await expect(templates.getByText('Documents made from a template keep working while it is in the trash.')).toBeVisible()
    await templates.getByRole('button', { name: `Restore ${TEMPLATE}` }).click()
    await expect(sidebar.getByRole('button', { name: TEMPLATE, exact: true })).toBeVisible({ timeout: 15_000 })
  } finally {
    await restoreIfTrashed(context)
  }
})
