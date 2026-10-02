import { expect, type Page } from '@playwright/test'

/**
 * Starts a document the way a person does: from Home, by pressing a
 * template under My Templates in the sidebar, which creates the document
 * and opens it. Below the width where the sidebar sits beside the page it
 * is a drawer behind the menu button, so that is opened first.
 *
 * Answers with the document's number and its title (the template's name
 * and the day), read from the page it opened on.
 */
export async function startDocumentFromSidebar(page: Page, templateName: string): Promise<{ id: number; title: string }> {
  await page.goto('/')
  const openMenu = page.getByRole('button', { name: 'Open menu', exact: true })
  if (await openMenu.isVisible()) await openMenu.click()
  await page.locator('#app-sidebar').getByRole('button', { name: templateName, exact: true }).click()
  await page.waitForURL(/\/documents\/\d+$/)
  const title = page.locator('.workspace-title')
  await expect(title).toContainText(templateName, { timeout: 15_000 })
  return { id: Number(new URL(page.url()).pathname.split('/').pop()), title: (await title.innerText()).trim() }
}
