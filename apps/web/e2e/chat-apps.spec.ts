import { test, expect } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { startDocumentFromSidebar } from './documents'

/**
 * The Chat apps panel on Connections, against the real running stack with
 * MCP on: a token made on the page really speaks JSON-RPC to /mcp, the calls
 * it makes really are written into the document's version history, and a
 * grant revoked on the page really stops answering the very next call.
 *
 * The MCP calls go through plain fetch, not the browser's request context:
 * a chat app holds a bearer token and no session cookie, so nothing it
 * sends should ride the seeded browser session.
 */
const API_ORIGIN = process.env.BROWNIE_E2E_BACKEND_ORIGIN ?? 'http://localhost:8081'

const ALL_TOOLS = [
  'add_text_source',
  'answer_question',
  'confirm_fill',
  'export_document',
  'get_document',
  'get_export_link',
  'get_fill_status',
  'list_templates',
  'propose_fill',
  'start_document',
]

/** One JSON-RPC call to /mcp, with the token a chat app would present. */
async function mcp(token: string, body: object) {
  return fetch(`${API_ORIGIN}/mcp`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  })
}

async function initialize(token: string) {
  const response = await mcp(token, {
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'playwright-e2e', version: '0' },
    },
  })
  expect(response.status).toBe(200)
}

test('a token made on the page calls the tools, the calls appear in the history, and revoking ends its access', async ({
  page,
}) => {
  const tokenName = `Playwright ${Date.now()}`

  await page.goto('/connections')
  const panel = page.locator('.chat-apps')
  await expect(panel).toBeVisible({ timeout: 15_000 })

  // The address a chat app is pointed at is what the page itself displays.
  const serverUrl = await panel.locator('.chat-apps__url .text-field').inputValue()
  expect(serverUrl).toBe(`${API_ORIGIN}/mcp`)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // Create the token on the page; the dialog is the one and only place it is shown.
  await panel.getByLabel('A name for the token').fill(tokenName)
  await panel.getByRole('button', { name: 'Create a token' }).click()
  const dialog = page.getByRole('dialog', { name: 'Your new token' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('Brownie will not show this token again.')
  const token = (await dialog.locator('.chat-apps__token-text').innerText()).trim()
  expect(token.length).toBeGreaterThan(10)
  await expect(dialog).toContainText(`claude mcp add --transport http brownie ${serverUrl}`)
  await dialog.getByRole('button', { name: 'Close' }).click()
  await expect(dialog).toBeHidden()

  // The new grant is listed as a token; the calls below give it a "last used".
  const grantRow = panel.locator('.chat-apps__grant', { hasText: tokenName })
  await expect(grantRow).toBeVisible()
  await expect(grantRow).toContainText('Token')

  // tools/list answers with every tool Brownie offers.
  await initialize(token)
  const listed = await mcp(token, { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} })
  expect(listed.status).toBe(200)
  const listedBody = await listed.json()
  const toolNames = (listedBody.result.tools as { name: string }[]).map((tool) => tool.name).sort()
  expect(toolNames).toEqual(ALL_TOOLS)

  // A document the spec itself makes, read through the token.
  const document = await startDocumentFromSidebar(page, 'Flowing meeting minutes')
  const got = await mcp(token, {
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: { name: 'get_document', arguments: { document_id: document.id } },
  })
  expect(got.status).toBe(200)
  const gotBody = await got.json()
  expect(gotBody.result.isError ?? false).toBe(false)

  // The call is in the document's version history, named by its grant.
  await page.getByRole('button', { name: 'Version history' }).click()
  const history = page.getByRole('dialog', { name: 'Version history' })
  await expect(history).toBeVisible({ timeout: 15_000 })
  await expect(history.locator('.revision-row--call')).toContainText(`${tokenName} read it`)
  await history.getByRole('button', { name: 'Close' }).click()

  // Revoking on the page ends the token's access at once.
  await page.goto('/connections')
  await expect(panel).toBeVisible({ timeout: 15_000 })
  await panel.locator('.chat-apps__grant', { hasText: tokenName }).getByRole('button', { name: 'Revoke' }).click()
  await expect(panel.getByText(`${tokenName} loses access to Brownie at once.`)).toBeVisible()
  await panel.getByRole('button', { name: 'Yes, revoke' }).click()
  await expect(page.getByRole('status').filter({ hasText: `${tokenName} no longer has access to Brownie.` })).toBeVisible()
  // A revoked grant leaves the list at once; an earlier run's token may still stand beside it.
  await expect(panel.locator('.chat-apps__grant', { hasText: tokenName })).toHaveCount(0)

  const refused = await mcp(token, {
    jsonrpc: '2.0',
    id: 4,
    method: 'tools/call',
    params: { name: 'get_document', arguments: { document_id: document.id } },
  })
  expect(refused.status).toBe(401)
  expect(refused.headers.get('www-authenticate')).toContain('resource_metadata=')
})
