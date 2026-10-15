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
  type UsageResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

const PRACTICES: DataPracticesResponse = {
  trashRetentionDays: 14,
  abandonedUploadHours: 24,
  refusedFileHours: 48,
  unusedFileHours: 6,
  auditRecordDays: 90,
  modelProvider: 'OpenAI',
  modelName: 'gpt-6-luna',
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

/** The field the phrase is typed into, found the way a person finds it: by the words above it. */
function phraseField(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  const label = wrapper.findAll('label').find((candidate) => candidate.text() === 'Type delete my workspace to confirm')
  if (!label) throw new Error('The question about the phrase is not showing')
  return wrapper.get<HTMLInputElement>(`#${label.attributes('for')}`)
}

/** Opens the question, types the phrase and submits it. */
async function confirmDeletion(wrapper: Awaited<ReturnType<typeof mountPage>>): Promise<void> {
  await button(wrapper, 'Delete my workspace…').trigger('click')
  await flushPromises()
  await phraseField(wrapper).setValue('delete my workspace')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

/** The headings of the page's cards, in the order they are read. */
function cardHeadings(wrapper: Awaited<ReturnType<typeof mountPage>>): string[] {
  return wrapper.findAll('h2').map((heading) => heading.text())
}

/** The card a heading names: the section that opens with it, with every word in it. */
function card(wrapper: Awaited<ReturnType<typeof mountPage>>, heading: string) {
  const found = wrapper.findAll('section').find((candidate) => candidate.element.querySelector(':scope > h2')?.textContent?.trim() === heading)
  if (!found) throw new Error(`No card named "${heading}" among: ${cardHeadings(wrapper).join(' | ')}`)
  return found
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
      mcpEnabled: false,
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
    expect(wrapper.text()).toContain('OpenAI (gpt-6-luna)')
    expect(wrapper.text()).toContain('PDF, TXT, up to 10 MB each')
    expect(wrapper.text()).toContain('8 requests to the model')
    expect(wrapper.text()).toContain('Ask data@example.org.')
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  it('says that an uploaded form is read by the model only where this Brownie does that, and that nothing else is sent', async () => {
    const promise = 'Nothing is sent until you ask.'
    const upload =
      'When you upload a form, its text is sent to the same service, so Brownie can find the places to fill in and name them. Nothing else is sent until you ask.'

    let wrapper = await mountPage()
    let text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain(`is sent to OpenAI (gpt-6-luna) to get an answer. ${promise}`)
    expect(text).not.toContain('When you upload a form')
    wrapper.unmount()

    vi.mocked(getDataPractices).mockResolvedValue({ ...PRACTICES, formTextSentToModel: false })
    wrapper = await mountPage()
    expect(wrapper.text().replace(/\s+/g, ' ')).toContain(promise)
    wrapper.unmount()

    vi.mocked(getDataPractices).mockResolvedValue({ ...PRACTICES, formTextSentToModel: true })
    wrapper = await mountPage()
    text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain(`is sent to OpenAI (gpt-6-luna) to get an answer. ${upload}`)
    expect(text).not.toContain(promise)
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  it('says what connecting Google keeps and shares, and that deleting everything takes that access back', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
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

  it('says what saving to Google Drive keeps and sends, and that a change needs approval, where saving is offered or was used', async () => {
    const offering = (googleConnectorAccess: ('CALENDAR_EVENTS' | 'DRIVE_FILES' | 'DRIVE_SAVING')[]) => ({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
      googleConnectorAccess,
    })
    vi.mocked(getCapabilities).mockResolvedValue(offering(['CALENDAR_EVENTS', 'DRIVE_SAVING']))
    let wrapper = await mountPage()
    let text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain('If you save a document to Google Drive: each save you prepare')
    expect(text).toContain('deleting anything here does not delete it there')
    expect(text).toContain('approving a save to Google Drive or an addition to a Google Doc and sending it')
    expect(text).toContain('Preparing to add text to a Google Doc Brownie saved reads that Doc in full')
    expect(text).toContain('and changes nothing there that you have not approved.')
    expect(text).toContain('is sent to Google only once you approve that save')
    expect(text).not.toContain('add an event to Google Calendar')
    expect(text).not.toContain('never changes anything there')
    expect(text).toContain('Files you saved to Google Drive stay in your Google Drive.')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    // Switched off since, but connected before: what was kept is still said.
    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue(offering(['CALENDAR_EVENTS']))
    vi.mocked(listConnections).mockResolvedValue([
      {
        id: 8,
        provider: 'GOOGLE',
        access: 'DRIVE_SAVING',
        state: 'DISCONNECTED',
        accountEmail: null,
        grantedScopes: [],
        reconnectReason: null,
        connectedAt: '2026-09-20T10:00:00Z',
        tokenIssuedAt: null,
        disconnectedAt: '2026-09-21T10:00:00Z',
        providerRevocation: 'REVOKED',
        grants: [],
      },
    ])
    wrapper = await mountPage()
    text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain('If you save a document to Google Drive')

    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(listConnections).mockResolvedValue([])
    text = (await mountPage()).text().replace(/\s+/g, ' ')
    expect(text).not.toContain('save a document to Google Drive')
    expect(text).toContain('never changes anything there')
  })

  it('says what adding calendar events keeps and sends, and that nobody is told, where adding events is offered', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
      googleConnectorAccess: ['CALENDAR_EVENTS', 'CALENDAR_EVENT_CREATION'],
    })
    const wrapper = await mountPage()
    const text = wrapper.text().replace(/\s+/g, ' ')
    expect(text).toContain('If you add an event to Google Calendar: each event you prepare')
    expect(text).toContain('approving an event for Google Calendar and sending it')
    expect(text).toContain('and changes nothing there that you have not approved.')
    expect(text).toContain('it has no guests, so Google tells nobody about it')
    expect(text).toContain('After a change you approve, it reads back only what that change made or added to, to check it.')
    expect(text).toContain('Events you added to Google Calendar stay in your calendar.')
    expect(text).not.toContain('save a document to Google Drive')
    expect(text).not.toContain('never changes anything there')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('says what choosing Drive files keeps and reads only where Drive is offered or was used', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
      googleConnectorAccess: ['CALENDAR_EVENTS'],
    })
    let text = (await mountPage()).text().replace(/\s+/g, ' ')
    expect(text).not.toContain('Google Drive')

    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
      googleConnectorAccess: ['CALENDAR_EVENTS', 'DRIVE_FILES'],
    })
    text = (await mountPage()).text().replace(/\s+/g, ' ')
    expect(text).toContain(
      "If you choose files in Google Drive: which files they are, each one's name as Google Drive gives it, and since when Brownie may read it.",
    )
    expect(text).toContain('After you take a file off your list, or disconnect, Brownie no longer reads it or shows it, but keeps that record')
    expect(text).toContain('deleted as soon as you disconnect Google, and every file you chose in Google Drive is taken off your list.')
    expect(text).toContain('and which files you chose, stays with your workspace until the workspace is deleted')
    expect(text).toContain("what each file you choose in Google Drive is, and a file's content only when you copy it")

    // A Drive connection made while Drive was offered is still described after it stops being offered.
    document.body.innerHTML = ''
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 1,
      uploadMediaTypes: [],
      assistSourceMediaTypes: [],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
      googleConnectorAccess: [],
    })
    vi.mocked(listConnections).mockResolvedValue([
      {
        id: 5, provider: 'GOOGLE', access: 'DRIVE_FILES', state: 'ACTIVE', accountEmail: 'me@example.org', grantedScopes: [],
        reconnectReason: null, connectedAt: '2026-09-20T10:00:00Z', tokenIssuedAt: '2026-09-20T10:00:00Z', disconnectedAt: null,
        providerRevocation: null, grants: [],
      },
    ])
    text = (await mountPage()).text().replace(/\s+/g, ' ')
    expect(text).toContain('If you choose files in Google Drive')
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
      mcpEnabled: false,
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
      mcpEnabled: false,
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
    expect(cardHeadings(wrapper)).toEqual(['What is kept', 'How long', 'Who else sees it', 'Questions', 'Delete everything'])
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  it('answers each question in a card of its own, under a title and a line saying what the page is for', async () => {
    const wrapper = await mountPage()

    expect(wrapper.get('h1').text()).toBe('Your data')
    expect(wrapper.text()).toContain('What Brownie keeps, for how long, who else sees it, and how to delete it all.')
    expect(cardHeadings(wrapper)).toEqual([
      'What is kept',
      'How long',
      'Who else sees it',
      'Files Brownie accepts',
      'This month',
      'Questions',
      'Delete everything',
    ])
    expect(card(wrapper, 'How long').text()).toContain('restored for 14 days')
    expect(card(wrapper, 'Who else sees it').text()).toContain('OpenAI (gpt-6-luna)')
    expect(card(wrapper, 'Files Brownie accepts').text()).toContain('PDF, TXT, up to 10 MB each.')
    expect(card(wrapper, 'Questions').text()).toBe('QuestionsAsk data@example.org.')
  })

  it('names the file kinds the server accepts, with the size limit only when it sends one, and has no such card when it names none', async () => {
    const capabilities = (extensions: string[], maxUploadBytes: number) => ({
      maxUploadBytes,
      uploadMediaTypes: extensions.map((extension) => ({ mediaType: 'application/octet-stream', extension })),
      assistSourceMediaTypes: [],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
    })

    vi.mocked(getCapabilities).mockResolvedValue(capabilities(['.docx', '.pdf'], 0))
    let wrapper = await mountPage()
    expect(card(wrapper, 'Files Brownie accepts').text()).toContain('DOCX, PDF. A file is judged by what it is, not by its name.')
    wrapper.unmount()

    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([], 1024))
    wrapper = await mountPage()
    expect(cardHeadings(wrapper)).not.toContain('Files Brownie accepts')
  })

  it('waits for the server only for the sentences that carry its numbers, and says so while it does', async () => {
    let answer: (value: DataPracticesResponse) => void = () => {}
    vi.mocked(getDataPractices).mockReturnValue(new Promise<DataPracticesResponse>((resolve) => (answer = resolve)))
    const wrapper = await mountPage()

    expect(wrapper.text()).toContain('Loading…')
    expect(card(wrapper, 'How long').text()).toContain('An upload that was never finished is removed automatically.')
    expect(wrapper.find('button').exists()).toBe(true)

    answer(PRACTICES)
    await flushPromises()

    expect(wrapper.text()).not.toContain('Loading…')
    expect(card(wrapper, 'How long').text()).toContain('An upload that was never finished is removed after a day.')
  })

  describe('This month', () => {
    const NOW = new Date('2026-10-07T22:58:00Z')

    beforeEach(() => {
      // Only the clock: the page's own requests and the test's waiting must keep running on real timers.
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(NOW)
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    const monthDay = (year: number, monthIndex: number) =>
      new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(year, monthIndex, 1)))

    function usageOf(monthUsedUsd: number, monthLimitUsd: number, monthRequests: number, sharedAllowanceExhausted = false) {
      return {
        monthUsedUsd,
        monthLimitUsd,
        monthRemainingUsd: Math.max(0, monthLimitUsd - monthUsedUsd),
        monthRequests,
        sharedAllowanceExhausted,
      }
    }

    const fillOf = (wrapper: Awaited<ReturnType<typeof mountPage>>) => card(wrapper, 'This month').get('.your-data__meter-fill').attributes('style')

    it('shows what has been spent of the allowance as figures and as a bar, and when the allowance starts again', async () => {
      vi.mocked(getWorkspaceUsage).mockResolvedValue(usageOf(0.74, 2, 31))
      const wrapper = await mountPage()
      const month = card(wrapper, 'This month')

      expect(month.text()).toContain('$0.74 of $2.00 used · 31 requests to the model')
      // The server counts its months in UTC, so the date says so rather than leave a person west of Greenwich a day out.
      expect(month.text()).toContain(`Resets ${monthDay(2026, 10)} (UTC)`)
      expect(fillOf(wrapper)).toContain('--your-data-used: 37%')
      // The figures say all that the bar shows, so a screen reader is not read it twice.
      expect(month.get('.your-data__meter').attributes('aria-hidden')).toBe('true')
      expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
    })

    it('counts the first of next month in UTC, also at the turn of the year', async () => {
      vi.setSystemTime(new Date('2026-12-31T23:30:00Z'))
      const wrapper = await mountPage()

      expect(card(wrapper, 'This month').text()).toContain(`Resets ${monthDay(2027, 0)} (UTC)`)
    })

    it.each([
      ['nothing spent yet', usageOf(0, 2, 0), '0%'],
      ['a part of it', usageOf(0.0124, 2, 8), '0.6%'],
      ['more than all of it, as a request still under way is counted at the most it could cost', usageOf(2.2, 2, 3), '100%'],
      ['an allowance of nothing, which leaves no room', usageOf(0, 0, 0), '100%'],
    ])('fills the bar for %s', async (_case, figures, fill) => {
      vi.mocked(getWorkspaceUsage).mockResolvedValue(figures)
      const wrapper = await mountPage()

      expect(fillOf(wrapper)).toContain(`--your-data-used: ${fill}`)
    })

    it('says "1 request", not "1 requests"', async () => {
      vi.mocked(getWorkspaceUsage).mockResolvedValue(usageOf(0.01, 2, 1))
      const wrapper = await mountPage()

      expect(card(wrapper, 'This month').text()).toContain('1 request to the model')
      expect(card(wrapper, 'This month').text()).not.toContain('1 requests')
    })

    it('says what happens when the allowance runs out: reading and rewriting stop, and typing, reviewing and exporting do not', async () => {
      const wrapper = await mountPage()
      const text = card(wrapper, 'This month').text().replace(/\s+/g, ' ')

      expect(text).toContain(
        'When the allowance runs out, Brownie cannot fill in a document or rewrite a value until next month. Typing, reviewing and exporting keep working.',
      )
      expect(text, 'the shared allowance is said to be used up only when the server says so').not.toContain('everyone shares')
    })

    it('says that the allowance everyone shares is used up only when the server says it is', async () => {
      vi.mocked(getWorkspaceUsage).mockResolvedValue(usageOf(0.5, 2, 4, true))
      const wrapper = await mountPage()
      const text = card(wrapper, 'This month').text().replace(/\s+/g, ' ')

      expect(text).toContain('The allowance everyone shares is used up for this month, so new runs are paused until next month.')
      expect(text).toContain('When the allowance runs out')
    })

    it('leaves the card out when the server did not send every figure it needs', async () => {
      const partial: Partial<UsageResponse> = usageOf(0.5, 2, 4)
      delete partial.monthLimitUsd
      vi.mocked(getWorkspaceUsage).mockResolvedValue(partial as UsageResponse)
      const wrapper = await mountPage()

      expect(cardHeadings(wrapper)).not.toContain('This month')
      expect(wrapper.text()).not.toMatch(/NaN|undefined/)
    })
  })

  it('deletes the workspace only after the phrase is typed, then leaves for a signed-out page', async () => {
    vi.mocked(deleteWorkspace).mockResolvedValue({ deletionId: 9 })
    const wrapper = await mountPage()

    await wrapper.findAll('button').find((b) => b.text() === 'Delete my workspace…')!.trigger('click')
    await flushPromises()
    const input = phraseField(wrapper)
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

  it('asks for the phrase under a label of its own, offers the way back before the way through, and keeps the way through shut until the phrase is right', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('form').exists()).toBe(false)

    await button(wrapper, 'Delete my workspace…').trigger('click')
    await flushPromises()

    const form = wrapper.get('form')
    expect(wrapper.findAll('button').some((candidate) => candidate.text() === 'Delete my workspace…'), 'the question takes the place of the button that asked it').toBe(false)
    expect(form.findAll('button').map((candidate) => candidate.text())).toEqual(['Keep my workspace', 'Delete everything for good'])
    expect(phraseField(wrapper).attributes('autocomplete')).toBe('off')

    const forGood = button(wrapper, 'Delete everything for good')
    expect(forGood.attributes('disabled')).toBeDefined()
    await phraseField(wrapper).setValue('delete my')
    expect(forGood.attributes('disabled')).toBeDefined()
    await phraseField(wrapper).setValue('delete my workspace')
    expect(forGood.attributes('disabled')).toBeUndefined()
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
  })

  it('stops the field and both buttons while the workspace is being deleted, and asks the server only once', async () => {
    vi.mocked(deleteWorkspace).mockReturnValue(new Promise<{ deletionId: number }>(() => {}))
    const wrapper = await mountPage()
    await confirmDeletion(wrapper)

    expect(button(wrapper, 'Deleting…').attributes('disabled')).toBeDefined()
    expect(button(wrapper, 'Keep my workspace').attributes('disabled')).toBeDefined()
    expect(phraseField(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.find('form').trigger('submit')
    expect(deleteWorkspace).toHaveBeenCalledTimes(1)
    expect(assign).not.toHaveBeenCalled()

    // Escape cannot call the request back, so it must not hide the question and what comes of it.
    await wrapper.find('form').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('says what deleting does, with one space between the sentences however many of them this Brownie needs', async () => {
    const fixed =
      'This deletes your workspace for good: every document, template, source and file, and your sign-in record. ' +
      'It cannot be undone, and nothing goes to the trash bin first. Signing in again afterwards starts a new, empty workspace.'

    // Nothing from Google offered or used: only what is always true.
    let wrapper = await mountPage()
    expect(card(wrapper, 'Delete everything').get('p').text()).toBe(fixed)
    wrapper.unmount()

    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10 * 1024 * 1024,
      uploadMediaTypes: [],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 14,
      mcpEnabled: false,
      googleConnectorAccess: ['CALENDAR_EVENTS', 'DRIVE_SAVING', 'CALENDAR_EVENT_CREATION'],
    })
    wrapper = await mountPage()
    expect(card(wrapper, 'Delete everything').get('p').text()).toBe(
      `${fixed} Brownie also asks Google to take back the access it still holds to any Google account you connected. ` +
        'Files you saved to Google Drive stay in your Google Drive. Events you added to Google Calendar stay in your calendar.',
    )
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
    await phraseField(wrapper).setValue('delete my workspace')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('A run is still stopping. Nothing was deleted')
    expect(assign).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').find((b) => b.text() === 'Delete everything for good')!.attributes('disabled')).toBeUndefined()
    expect(document.activeElement, 'focus goes back to the field, so the phrase can be fixed or the try repeated').toBe(phraseField(wrapper).element)
    expect((await axe(wrapper.element as HTMLElement)).violations).toEqual([])
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
    expect(document.activeElement).toBe(phraseField(wrapper).element)
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
    await phraseField(wrapper).setValue('delete my workspace')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('Nothing was deleted. Only the owner of a workspace can delete it.')
  })
})
