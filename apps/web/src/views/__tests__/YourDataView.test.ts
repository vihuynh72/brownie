import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import YourDataView from '@/views/YourDataView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getDataPractices: vi.fn(),
    getWorkspaceUsage: vi.fn(),
    deleteWorkspace: vi.fn(),
    getCapabilities: vi.fn(),
    listConnections: vi.fn(),
  }
})

import {
  ApiRequestError,
  deleteWorkspace,
  getCapabilities,
  getDataPractices,
  getWorkspaceUsage,
  listConnections,
  type DataPracticesResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

const PRACTICES: DataPracticesResponse = {
  trashRetentionDays: 14,
  abandonedUploadHours: 24,
  refusedFileHours: 48,
  unusedFileHours: 6,
  auditRecordDays: 90,
  modelProvider: 'OpenAI',
  modelName: 'gpt-5.4-mini-2026-03-17',
  supportContact: 'data@example.org',
}

const assign = vi.fn()
const realLocation = window.location

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

function problem(status: number, detail: string, code = 'X') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

async function mountPage() {
  // Memory history: the page itself replaces window.location in these tests to catch where it navigates.
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/your-data', component: YourDataView },
      { path: '/signin', component: { template: '<div />' } },
    ],
  })
  router.push('/your-data')
  await router.isReady()
  const wrapper = mount(YourDataView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function button(wrapper: Awaited<ReturnType<typeof mountPage>>, name: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  }
  return found
}

/** Opens the question, types the phrase and submits it. */
async function confirmDeletion(wrapper: Awaited<ReturnType<typeof mountPage>>): Promise<void> {
  await button(wrapper, 'Delete my workspace…').trigger('click')
  await flushPromises()
  await wrapper.find('#privacy-confirm-phrase').setValue('delete my workspace')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('YourDataView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.mocked(getDataPractices).mockReset().mockResolvedValue(PRACTICES)
    vi.mocked(getWorkspaceUsage).mockReset().mockResolvedValue({
      monthUsedUsd: 0.0124,
      monthLimitUsd: 2,
      monthRemainingUsd: 1.9876,
      monthRequests: 8,
      sharedAllowanceExhausted: false,
    })
    vi.mocked(getCapabilities).mockReset().mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [
        { mediaType: 'application/pdf', extension: '.pdf' },
        { mediaType: 'text/plain', extension: '.txt' },
      ],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
    })
    vi.mocked(deleteWorkspace).mockReset()
    vi.mocked(listConnections).mockReset().mockResolvedValue([])
    assign.mockReset()
    Object.defineProperty(window, 'location', { configurable: true, value: { ...realLocation, assign } })
    signIn()
  })

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: realLocation })
    document.body.innerHTML = ''
  })

  it('says how long things are kept in the numbers the server gave, not in numbers of its own', async () => {
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('restored for 14 days')
    expect(wrapper.text()).toContain('never finished is removed after a day')
    expect(wrapper.text()).toContain('loses its contents after 2 days')
    expect(wrapper.text()).toContain('any more is removed after 6 hours')
    expect(wrapper.text()).toContain('kept for 90 days')
    expect(wrapper.text()).toContain('OpenAI (gpt-5.4-mini-2026-03-17)')
    expect(wrapper.text()).toContain('PDF, TXT, up to 10 MB each')
    expect(wrapper.text()).toContain('8 requests to the model')
    expect(wrapper.text()).toContain('Ask data@example.org.')
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  it('says what connecting Google keeps and shares, and that deleting everything takes that access back', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      googleConnectorAccess: ['CALENDAR_EVENTS'],
    })
    const wrapper = await mountPage()
    const text = wrapper.text().replace(/\s+/g, ' ')

    expect(text).toContain('If you connect a Google account: which account, what Google allowed and when')
    expect(text).toContain('The access Google gives Brownie is deleted as soon as you disconnect Google.')
    expect(text).toContain('never changes anything there')
    expect(text).toContain('Brownie also asks Google to take back the access it still holds to any Google account you connected.')
    expect(wrapper.find('a[href="/connections"]').text()).toBe('Connections')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('leaves Google out where this Brownie has none set up, or its server is from before connections', async () => {
    // The default capabilities here have no googleConnectorAccess at all, as an older server sends them.
    expect((await mountPage()).text()).not.toContain('Google')

    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 1,
      uploadMediaTypes: [],
      assistSourceMediaTypes: [],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      googleConnectorAccess: [],
    })
    expect((await mountPage()).text()).not.toContain('Google')
  })

  it('still says what connecting Google would mean when whether it is offered cannot be checked', async () => {
    vi.mocked(getCapabilities).mockRejectedValue(new ApiRequestError(502, undefined))
    const text = (await mountPage()).text().replace(/\s+/g, ' ')

    expect(text).toContain('If you connect a Google account')
    expect(text, 'whether this Brownie can reach Google at all is not known, so it promises nothing of it').not.toContain(
      'Brownie also asks Google',
    )
  })

  it('still says what is kept about a Google account after this Brownie stopped offering Google', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 1,
      uploadMediaTypes: [],
      assistSourceMediaTypes: [],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      googleConnectorAccess: [],
    })
    vi.mocked(listConnections).mockResolvedValue([
      {
        id: 1, provider: 'GOOGLE', access: 'CALENDAR_EVENTS', state: 'ACTIVE', accountEmail: 'me@example.org', grantedScopes: [],
        reconnectReason: null, connectedAt: '2026-09-20T10:00:00Z', tokenIssuedAt: '2026-09-20T10:00:00Z', disconnectedAt: null,
        providerRevocation: null, grants: [],
      },
    ])
    const text = (await mountPage()).text().replace(/\s+/g, ' ')

    expect(listConnections).toHaveBeenCalledWith(7)
    expect(text).toContain('If you connect a Google account: which account')
    expect(text, 'this Brownie cannot reach Google now, so deleting cannot promise to ask it').not.toContain('Brownie also asks Google')
  })

  it('does not invent someone to ask when nobody has been named', async () => {
    vi.mocked(getDataPractices).mockResolvedValue({ ...PRACTICES, supportContact: null })
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('Nobody has been named to ask yet.')
    expect(wrapper.text()).not.toContain('@')
  })

  it('still says everything that matters when usage and the accepted file types cannot be loaded', async () => {
    vi.mocked(getWorkspaceUsage).mockRejectedValue(new ApiRequestError(500, undefined))
    vi.mocked(getCapabilities).mockRejectedValue(new ApiRequestError(500, undefined))
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('restored for 14 days')
    expect(wrapper.text()).not.toContain('This month')
    expect(wrapper.text()).toContain('Delete everything')
  })

  it('deletes the workspace only after the phrase is typed, then leaves for a signed-out page', async () => {
    vi.mocked(deleteWorkspace).mockResolvedValue({ deletionId: 9 })
    const wrapper = await mountPage()

    await wrapper.findAll('button').find((b) => b.text() === 'Delete my workspace…')!.trigger('click')
    await flushPromises()
    const input = wrapper.find<HTMLInputElement>('#privacy-confirm-phrase')
    expect(document.activeElement).toBe(input.element)
    const confirm = () => wrapper.findAll('button').find((b) => b.text() === 'Delete everything for good')!
    expect(confirm().attributes('disabled')).toBeDefined()
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])

    await input.setValue('delete my workspac')
    expect(confirm().attributes('disabled')).toBeDefined()
    await wrapper.find('form').trigger('submit')
    expect(deleteWorkspace).not.toHaveBeenCalled()

    await input.setValue('  Delete My Workspace ')
    expect(confirm().attributes('disabled')).toBeUndefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(deleteWorkspace).toHaveBeenCalledWith(7)
    expect(assign).toHaveBeenCalledWith('/signin?deleted=1')
  })

  it('backs out on Escape or "Keep my workspace" and puts focus back where it was', async () => {
    const wrapper = await mountPage()
    const open = () => wrapper.findAll('button').find((b) => b.text() === 'Delete my workspace…')

    await open()!.trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(document.activeElement).toBe(open()!.element)

    await open()!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text() === 'Keep my workspace')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(deleteWorkspace).not.toHaveBeenCalled()
  })

  it('says nothing was deleted when a run is still stopping, and lets the person try again', async () => {
    vi.mocked(deleteWorkspace).mockRejectedValue(
      new ApiRequestError(409, {
        status: 409,
        title: 'Conflict',
        code: 'DELETION_WAITING_FOR_RUNNING_WORK',
        correlationId: 'c',
        fields: [],
        recoveryActions: [],
      }),
    )
    const wrapper = await mountPage()
    await wrapper.findAll('button').find((b) => b.text() === 'Delete my workspace…')!.trigger('click')
    await flushPromises()
    await wrapper.find('#privacy-confirm-phrase').setValue('delete my workspace')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('A run is still stopping. Nothing was deleted')
    expect(assign).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').find((b) => b.text() === 'Delete everything for good')!.attributes('disabled')).toBeUndefined()
  })

  /**
   * One failed descriptive request must never take away the only way to
   * delete everything. The page says what went wrong, keeps every fixed
   * section, and words each sentence that needed a number without it.
   */
  it('keeps the whole page and the way to delete everything when the description of the data cannot be loaded', async () => {
    vi.mocked(getDataPractices).mockRejectedValue(new ApiRequestError(503, problem(503, 'The database is away. Try again in a minute.')))
    vi.mocked(deleteWorkspace).mockResolvedValue({ deletionId: 9 })
    const wrapper = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toBe('The database is away. Try again in a minute.')
    const text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain('What is kept')
    expect(text).toContain(
      'Something in the trash bin can be restored at least until the date the trash bin shows beside it. Soon after that date it is deleted for good',
    )
    expect(text).toContain('An upload that was never finished is removed automatically.')
    expect(text).toContain('loses its contents automatically.')
    expect(text).toContain('A file that nothing refers to any more is removed automatically.')
    expect(text).toContain('The record of actions is kept for a set period, then removed.')
    expect(text).toContain('is sent to the AI service this Brownie is set up to use to get an answer.')
    expect(text).toContain('Whoever gave you access to Brownie is the person to ask.')
    expect(text).not.toContain('Nobody has been named')
    expect(text).not.toMatch(/undefined|null|for\s+days|after\s+\./)
    // The parts that did load still show.
    expect(text).toContain('PDF, TXT, up to 10 MB each')
    expect(text).toContain('8 requests to the model')
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])

    await confirmDeletion(wrapper)
    expect(deleteWorkspace).toHaveBeenCalledWith(7)
    expect(assign).toHaveBeenCalledWith('/signin?deleted=1')
  })

  /** What the owner saw: a server that predates this page. Reloading cannot help, so the page must not suggest it. */
  it('says the server is older than this page when it has no description of the data, and not to reload', async () => {
    vi.mocked(getDataPractices).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/data-practices.', 'NOT_FOUND')),
    )
    const wrapper = await mountPage()

    const alert = wrapper.get('[role="alert"]').text()
    expect(alert).toContain('older than this page and does not have the details of how your data is kept and shared yet')
    expect(alert).toContain('Reloading will not change that')
    expect(wrapper.text()).toContain('The rest of this page leaves out the exact periods')
    expect(button(wrapper, 'Delete my workspace…').exists()).toBe(true)
  })

  it.each([
    ['a 429 with an explanation from the server', new ApiRequestError(429, problem(429, 'Wait 12 seconds, then try again.')), 'Wait 12 seconds, then try again.'],
    ['a 401', new ApiRequestError(401, undefined), 'Your session has ended. Sign in again to carry on.'],
    ['a network failure', new TypeError('Failed to fetch'), 'Brownie could not be reached. Check your connection, then try again.'],
    [
      'a failure only Brownie can explain',
      new ApiRequestError(500, problem(500, 'Unexpected.', 'INTERNAL')),
      'Brownie could not load the details of how your data is kept and shared. If this keeps happening, let whoever runs this Brownie know.',
    ],
  ])('says what happened when the description of the data could not be loaded because of %s', async (_case, error, sentence) => {
    vi.mocked(getDataPractices).mockRejectedValue(error)
    const wrapper = await mountPage()

    expect(wrapper.get('[role="alert"]').text()).toBe(sentence)
    expect(wrapper.text()).not.toContain('Something went wrong')
    expect(wrapper.text()).not.toMatch(/reloading/i)
    expect(button(wrapper, 'Delete my workspace…').exists()).toBe(true)
  })

  /** An older server can answer with fewer fields than this page knows; each sentence then reads without the missing one. */
  it('words a period the server did not send without its number', async () => {
    const older: Partial<DataPracticesResponse> = { ...PRACTICES }
    delete older.unusedFileHours
    delete older.auditRecordDays
    vi.mocked(getDataPractices).mockResolvedValue(older as DataPracticesResponse)
    const wrapper = await mountPage()

    const text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain('restored for 14 days')
    expect(text).toContain('A file that nothing refers to any more is removed automatically.')
    expect(text).toContain('The record of actions is kept for a set period, then removed.')
    expect(text).not.toMatch(/undefined|NaN/)
  })

  /** The page can mount before the identity request has answered; usage has to follow once the workspace is known. */
  it('reads the usage for this month once the workspace becomes known after the page opened', async () => {
    const session = useSessionStore()
    session.identity = null
    session.status = 'loading'
    const wrapper = await mountPage()
    expect(getWorkspaceUsage).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('This month')

    signIn()
    await flushPromises()

    expect(getWorkspaceUsage).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('8 requests to the model')
  })

  it('says why nothing was deleted when the workspace is not known yet, instead of doing nothing', async () => {
    const session = useSessionStore()
    session.identity = null
    session.status = 'loading'
    const wrapper = await mountPage()

    await confirmDeletion(wrapper)

    expect(deleteWorkspace).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Brownie is still confirming who is signed in, so nothing was deleted. Wait a moment, then try again.',
    )
    expect(document.activeElement).toBe(wrapper.find('#privacy-confirm-phrase').element)
    expect(assign).not.toHaveBeenCalled()
  })

  /** The delete form now shows on a server that predates this page too; "try again" would be false there. */
  it('says nothing was deleted, and why, when the server has no way to delete a workspace', async () => {
    vi.mocked(deleteWorkspace).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/deletions.', 'NOT_FOUND')),
    )
    const wrapper = await mountPage()

    await confirmDeletion(wrapper)

    const alert = wrapper.get('[role="alert"]').text()
    expect(alert).toContain('Nothing was deleted.')
    expect(alert).toContain('does not have a way to delete a workspace yet')
    expect(alert).not.toContain('try again')
    expect(assign).not.toHaveBeenCalled()
  })

  it('says nothing was deleted, in words from the server, when it is briefly unavailable', async () => {
    vi.mocked(deleteWorkspace).mockRejectedValue(new ApiRequestError(503, problem(503, 'The database is away. Try again in a minute.')))
    const wrapper = await mountPage()

    await confirmDeletion(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toBe('Nothing was deleted. The database is away. Try again in a minute.')
  })

  it('asks a signed-out visitor to sign in and brings them back here afterwards', async () => {
    const session = useSessionStore()
    session.identity = null
    session.status = 'anonymous'
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('Sign in to see what Brownie keeps about you, or to delete it.')
    expect(wrapper.get('a.button--primary').attributes('href')).toBe('/signin?next=/your-data')
    expect(wrapper.text()).not.toContain('Delete my workspace')
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  /** In the app, a 401 from any request moves the session to signed out; the page then has to offer the way back in. */
  it('offers to sign in again when the session ends while the page is open', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Delete everything')

    const session = useSessionStore()
    session.identity = null
    session.status = 'anonymous'
    await flushPromises()

    expect(wrapper.text()).not.toContain('Delete everything')
    expect(wrapper.get('a.button--primary').attributes('href')).toBe('/signin?next=/your-data')
  })

  it("says why a refusal that will not change was made, in the server's words, without advice to try again", async () => {
    vi.mocked(deleteWorkspace).mockRejectedValue(
      new ApiRequestError(403, {
        status: 403, title: 'Forbidden', code: 'FORBIDDEN', detail: 'Only the owner of a workspace can delete it.',
        correlationId: 'c', fields: [], recoveryActions: [],
      }),
    )
    const wrapper = await mountPage()
    await wrapper.findAll('button').find((b) => b.text() === 'Delete my workspace…')!.trigger('click')
    await flushPromises()
    await wrapper.find('#privacy-confirm-phrase').setValue('delete my workspace')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('Nothing was deleted. Only the owner of a workspace can delete it.')
  })
})
