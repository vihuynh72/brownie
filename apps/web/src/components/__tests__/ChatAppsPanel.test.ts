import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatAppsPanel from '@/components/ChatAppsPanel.vue'
import { axe } from '@/test/axe'
import type { ApiError, McpGrantResponse } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listMcpGrants: vi.fn(),
    issueMcpPersonalToken: vi.fn(),
    revokeMcpGrant: vi.fn(),
  }
})

import { ApiRequestError, issueMcpPersonalToken, listMcpGrants, revokeMcpGrant } from '@/api/client'

const SERVER_URL = 'http://brownie.example/mcp'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, code: string, detail: string): ApiError {
  return { status, title: code, detail, code, correlationId: 'c', fields: [], recoveryActions: [] }
}

function grant(overrides: Partial<McpGrantResponse> = {}): McpGrantResponse {
  return {
    id: 5,
    kind: 'PERSONAL_TOKEN',
    name: 'Claude Code on my laptop',
    scopes: ['read', 'fill', 'export'],
    tokenHint: 'ab12',
    createdAt: '2026-03-01T08:00:00Z',
    lastUsedAt: null,
    revokedAt: null,
    ...overrides,
  }
}

async function mountPanel(props: { enabled?: boolean; serverUrl?: string | null } = {}) {
  const wrapper = mount(ChatAppsPanel, {
    attachTo: document.body,
    props: { workspaceId: 7, enabled: props.enabled ?? true, serverUrl: props.serverUrl === undefined ? SERVER_URL : props.serverUrl },
  })
  await flushPromises()
  return wrapper
}

function buttonNamed(wrapper: Awaited<ReturnType<typeof mountPanel>>, name: string) {
  const found = wrapper.findAll('button').find((candidate) => (candidate.attributes('aria-label') ?? candidate.text().replace(/\s+/g, ' ').trim()) === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  }
  return found
}

describe('ChatAppsPanel', () => {
  beforeEach(() => {
    vi.mocked(listMcpGrants).mockReset().mockResolvedValue([])
    vi.mocked(issueMcpPersonalToken).mockReset()
    vi.mocked(revokeMcpGrant).mockReset()
  })

  it('is nothing at all on a deployment where chat apps are off, and asks the server nothing', async () => {
    const wrapper = await mountPanel({ enabled: false })

    expect(wrapper.find('.chat-apps').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
    expect(listMcpGrants).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it("shows Brownie's address for chat apps in a read-only field with a Copy, and says how each kind of app is set up", async () => {
    const wrapper = await mountPanel()

    const field = wrapper.get('.chat-apps__url .text-field')
    expect(field.attributes('readonly')).toBeDefined()
    expect((field.element as HTMLInputElement).value).toBe(SERVER_URL)
    expect(buttonNamed(wrapper, 'Copy').exists()).toBe(true)
    expect(wrapper.text()).toContain('Developer tools (Claude Code, VS Code, Cursor): create a token below and paste it with the URL.')
    expect(wrapper.text()).toContain('custom connector')
    expect(wrapper.text()).toContain('sign in to Brownie and choose what it may do')
    wrapper.unmount()
  })

  it('offers the three scopes in words, all on by default', async () => {
    const wrapper = await mountPanel()

    const labels = wrapper.findAll('.chat-apps__check').map((label) => label.text())
    expect(labels).toEqual(['See your templates and documents', 'Fill documents, with your confirmation', 'Export documents'])
    const boxes = wrapper.findAll('.chat-apps__check input[type="checkbox"]')
    expect(boxes.map((box) => (box.element as HTMLInputElement).checked)).toEqual([true, true, true])
    wrapper.unmount()
  })

  it('names the token, makes it and shows it once -- with ready lines that hold the real address and token', async () => {
    vi.mocked(issueMcpPersonalToken).mockResolvedValue({ grantId: 9, token: 'brn_tok_secret-xyz' })
    const wrapper = await mountPanel()

    await wrapper.get('.chat-apps__create input.text-field').setValue('Claude Code on my laptop')
    await wrapper.get('.chat-apps__create').trigger('submit')
    await flushPromises()

    expect(issueMcpPersonalToken).toHaveBeenCalledWith(7, 'Claude Code on my laptop', ['read', 'fill', 'export'])
    const dialog = wrapper.get('dialog')
    expect(dialog.text()).toContain('brn_tok_secret-xyz')
    expect(dialog.text()).toContain('Brownie will not show this token again.')
    expect(dialog.text()).toContain(`claude mcp add --transport http brownie ${SERVER_URL} --header "Authorization: Bearer brn_tok_secret-xyz"`)
    expect(dialog.text()).toContain(`"url":"${SERVER_URL}"`)
    expect(dialog.text()).toContain('"Bearer brn_tok_secret-xyz"')
    expect(dialog.text()).toContain('.vscode/mcp.json')
    expect(dialog.text()).toContain('~/.cursor/mcp.json')

    // Closing is the token's end: it leaves the page's memory with the dialog.
    await dialog.get('.dialog__close').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('brn_tok_secret-xyz')
    wrapper.unmount()
  })

  it('refuses to create without a name, and without any scope', async () => {
    const wrapper = await mountPanel()

    await wrapper.get('.chat-apps__create').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('Name the token')
    expect(issueMcpPersonalToken).not.toHaveBeenCalled()

    for (const box of wrapper.findAll('.chat-apps__check input[type="checkbox"]')) {
      await box.setValue(false)
    }
    expect(wrapper.text()).toContain('Choose at least one thing the token may do.')
    expect(buttonNamed(wrapper, 'Create a token').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('says what went wrong when making a token is refused, in the server\'s own words where it gave them', async () => {
    vi.mocked(issueMcpPersonalToken).mockRejectedValue(
      new ApiRequestError(422, problem(422, 'VALIDATION_FAILED', 'A token name is 80 letters at most.')),
    )
    const wrapper = await mountPanel()

    await wrapper.get('.chat-apps__create input.text-field').setValue('x')
    await wrapper.get('.chat-apps__create').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('A token name is 80 letters at most.')
    wrapper.unmount()
  })

  it('lists every standing grant -- token and connected app -- with its kind, dates and scopes in words, and leaves revoked ones out', async () => {
    vi.mocked(listMcpGrants).mockResolvedValue([
      grant({ id: 5, name: 'Claude Code', kind: 'PERSONAL_TOKEN', scopes: ['read'], lastUsedAt: '2026-03-05T10:00:00Z' }),
      grant({ id: 6, name: 'Claude.ai', kind: 'OAUTH_CLIENT', scopes: ['read', 'export'], tokenHint: '' }),
      grant({ id: 7, name: 'Old token', revokedAt: '2026-03-04T10:00:00Z' }),
    ])
    const wrapper = await mountPanel()

    const items = wrapper.findAll('.chat-apps__grant')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('Claude Code')
    expect(items[0].text()).toContain('Token')
    expect(items[0].text()).toContain('See your templates and documents')
    expect(items[1].text()).toContain('Claude.ai')
    expect(items[1].text()).toContain('Connected app')
    expect(items[1].text()).toContain('See your templates and documents, Export documents')
    expect(items[1].text()).toContain('Last used Never')
    expect(wrapper.text()).not.toContain('Old token')
    wrapper.unmount()
  })

  it('revokes only after asking, saying the grant loses access at once, and says so in the outcome it emits', async () => {
    vi.mocked(listMcpGrants).mockResolvedValue([grant({ id: 6, name: 'Claude.ai', kind: 'OAUTH_CLIENT' })])
    vi.mocked(revokeMcpGrant).mockResolvedValue(undefined)
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, 'Revoke').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Claude.ai loses access to Brownie at once.')
    expect(revokeMcpGrant).not.toHaveBeenCalled()

    await buttonNamed(wrapper, 'Yes, revoke').trigger('click')
    await flushPromises()

    expect(revokeMcpGrant).toHaveBeenCalledWith(7, 6)
    expect(wrapper.emitted('outcome')).toEqual([[{ tone: 'success', text: 'Claude.ai no longer has access to Brownie.' }]])
    wrapper.unmount()
  })

  it('keeps the grant when the person answers Keep it', async () => {
    vi.mocked(listMcpGrants).mockResolvedValue([grant()])
    const wrapper = await mountPanel()

    await buttonNamed(wrapper, 'Revoke').trigger('click')
    await flushPromises()
    await buttonNamed(wrapper, 'Keep it').trigger('click')
    await flushPromises()

    expect(revokeMcpGrant).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('loses access to Brownie at once')
    wrapper.unmount()
  })

  it('tells a server without chat apps apart from a failure, in the same words other pages use', async () => {
    vi.mocked(listMcpGrants).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/mcp/grants.')),
    )
    const wrapper = await mountPanel()

    expect(wrapper.find('[role="alert"]').text()).toContain('older than this page and does not have chat apps yet')
    expect(wrapper.find('[role="alert"]').text()).toContain('server needs to be updated')
    wrapper.unmount()
  })

  it('says plainly when the list cannot be loaded for another reason', async () => {
    vi.mocked(listMcpGrants).mockRejectedValue(new TypeError('Failed to fetch'))
    const wrapper = await mountPanel()

    expect(wrapper.find('[role="alert"]').text()).toContain('Brownie could not be reached')
    wrapper.unmount()
  })

  it('has no automatically detectable accessibility problems, empty and full', async () => {
    const empty = await mountPanel()
    expect(await axe(empty.element)).toHaveNoViolations()
    empty.unmount()

    vi.mocked(listMcpGrants).mockResolvedValue([grant(), grant({ id: 8, name: 'Cursor', kind: 'OAUTH_CLIENT' })])
    const full = await mountPanel()
    await buttonNamed(full, 'Revoke').trigger('click')
    await flushPromises()
    expect(await axe(full.element)).toHaveNoViolations()
    full.unmount()
  })
})
