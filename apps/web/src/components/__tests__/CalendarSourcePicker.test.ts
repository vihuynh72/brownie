import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import CalendarSourcePicker from '@/components/CalendarSourcePicker.vue'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCapabilities: vi.fn(),
    listConnections: vi.fn(),
    startGoogleConsent: vi.fn(),
    listCalendarEvents: vi.fn(),
  }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

import {
  ApiRequestError,
  getCapabilities,
  listCalendarEvents,
  listConnections,
  startGoogleConsent,
  type CalendarEventResponse,
  type CalendarImportResponse,
  type CapabilitiesResponse,
  type ConnectionResponse,
  type DocumentSourceResponse,
} from '@/api/client'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }
const mounted: { unmount(): void }[] = []
const copyEvent = vi.fn<(eventId: string) => Promise<CalendarImportResponse>>()

async function mountPicker(startOpen = false, unsavedWork = false) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/connections', component: stub },
    ],
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(CalendarSourcePicker, {
    props: { workspaceId: 7, documentId: 42, startOpen, unsavedWork, copyEvent },
    attachTo: document.body,
    global: { plugins: [router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountPicker>>

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, detail: string, code = 'X', extra: Record<string, string> = {}) {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [], ...extra }
}

function capabilities(googleConnectorAccess?: ('CALENDAR_EVENTS' | 'DRIVE_FILES')[]): CapabilitiesResponse {
  return {
    maxUploadBytes: 10485760,
    uploadMediaTypes: [],
    assistSourceMediaTypes: [],
    templateMediaTypes: [],
    trashRetentionDays: 30,
    ...(googleConnectorAccess === undefined ? {} : { googleConnectorAccess }),
  }
}

function connection(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id: 1,
    provider: 'GOOGLE',
    access: 'CALENDAR_EVENTS',
    state: 'ACTIVE',
    accountEmail: 'me@example.org',
    grantedScopes: [],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [],
    ...overrides,
  }
}

function event(overrides: Partial<CalendarEventResponse> = {}): CalendarEventResponse {
  return {
    id: 'evt1',
    title: 'Budget review',
    status: 'CONFIRMED',
    allDay: false,
    startDate: null,
    endDate: null,
    startsAt: '2026-09-22T09:00:00-07:00',
    endsAt: '2026-09-22T10:00:00-07:00',
    timeZone: null,
    recurring: false,
    ...overrides,
  }
}

function copiedSource(overrides: Partial<DocumentSourceResponse> = {}): DocumentSourceResponse {
  return {
    id: 90,
    artifactId: 91,
    kind: 'GOOGLE_CALENDAR',
    displayFilename: 'Budget review.txt',
    fetchedAt: '2026-09-23T08:00:00Z',
    attachedAt: '2026-09-23T08:00:00Z',
    origin: {
      provider: 'GOOGLE',
      title: 'Budget review',
      link: 'https://www.google.com/calendar/event?eid=abc',
      modifiedAt: '2026-09-21T08:00:00Z',
      conversion: 'CALENDAR_EVENT_AS_TEXT',
    },
    ...overrides,
  }
}

function text(wrapper: Wrapper): string {
  return wrapper.text().replace(/\s+/g, ' ')
}

function buttonNamed(wrapper: Wrapper, name: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text().replace(/\s+/g, ' ').trim() === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  }
  return found
}

async function openWithActiveConnection(): Promise<Wrapper> {
  vi.mocked(listConnections).mockResolvedValue([connection()])
  const wrapper = await mountPicker()
  await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
  await flushPromises()
  return wrapper
}

async function showEvents(wrapper: Wrapper): Promise<void> {
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('CalendarSourcePicker', () => {
  beforeEach(() => {
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue(capabilities(['CALENDAR_EVENTS']))
    vi.mocked(listConnections).mockReset()
    vi.mocked(startGoogleConsent).mockReset()
    vi.mocked(listCalendarEvents).mockReset()
    copyEvent.mockReset()
    vi.mocked(navigateTo).mockReset()
    vi.mocked(releaseIfStillHere).mockReset()
  })

  afterEach(() => {
    while (mounted.length > 0) mounted.pop()?.unmount()
  })

  it('is not offered by a server that has no Google set up, or one from before connections existed', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    expect((await mountPicker()).find('button').exists()).toBe(false)

    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockResolvedValue(capabilities())
    expect((await mountPicker()).find('button').exists()).toBe(false)

    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockRejectedValue(new Error('network'))
    expect((await mountPicker()).find('button').exists()).toBe(false)
  })

  it('asks nothing of the server, and nothing of Google, until the person opens it', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    const wrapper = await mountPicker()
    const toggle = buttonNamed(wrapper, 'Copy an event from Google Calendar')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(listConnections).not.toHaveBeenCalled()

    await toggle.trigger('click')
    await flushPromises()
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(listConnections).toHaveBeenCalledWith(7)
    // Opening only checks the connection; the calendar is read when the person asks for a stretch of days.
    expect(listCalendarEvents).not.toHaveBeenCalled()
  })

  it('offers to connect when Google Calendar is not connected, and sends the person back to this document', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection({ access: 'DRIVE_FILES' })])
    vi.mocked(startGoogleConsent).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' })
    const wrapper = await mountPicker()
    await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()

    expect(text(wrapper)).toContain('Google Calendar is not connected.')
    expect(wrapper.find('a[href="/connections"]').exists()).toBe(true)
    await buttonNamed(wrapper, 'Connect Google Calendar').trigger('click')
    await flushPromises()

    expect(startGoogleConsent).toHaveBeenCalledWith(7, 'CALENDAR_EVENTS', '/documents/42')
    expect(navigateTo).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?x=1')

    // The person chose to stay (unsaved changes), or came Back from Google's page: the button works again.
    expect(buttonNamed(wrapper, 'Connect Google Calendar').attributes('disabled')).toBeDefined()
    vi.mocked(releaseIfStillHere).mock.calls[0]![0]()
    await flushPromises()
    expect(buttonNamed(wrapper, 'Connect Google Calendar').attributes('disabled')).toBeUndefined()
  })

  it('says why a connection needs connecting again, including the seven-day limit while in testing', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])
    const wrapper = await mountPicker()
    await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()

    expect(text(wrapper)).toContain('Needs connecting again.')
    expect(text(wrapper)).toContain('seven days after you connected')
    expect(buttonNamed(wrapper, 'Connect Google Calendar again').exists()).toBe(true)
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('says what went wrong when starting to connect fails, and lets the person try again', async () => {
    vi.mocked(listConnections).mockResolvedValue([])
    vi.mocked(startGoogleConsent).mockRejectedValue(new ApiRequestError(409, problem(409, 'd', 'CONNECTOR_NOT_CONFIGURED')))
    const wrapper = await mountPicker()
    await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()
    await buttonNamed(wrapper, 'Connect Google Calendar').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe(
      'Could not start connecting Google Calendar. Connecting a Google account is not set up on this Brownie.',
    )
    expect(navigateTo).not.toHaveBeenCalled()
    expect(buttonNamed(wrapper, 'Connect Google Calendar').attributes('disabled')).toBeUndefined()
  })

  it('reads the chosen days, from the start of a local day, and lists the events as planned', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 23, 15, 30))
    try {
      vi.mocked(listCalendarEvents).mockResolvedValue({
        timeZone: 'America/Los_Angeles',
        truncated: false,
        events: [
          event(),
          event({ id: 'evt2', title: null, status: 'TENTATIVE', recurring: true }),
          event({ id: 'evt3', title: 'Offsite', allDay: true, startsAt: null, endsAt: null, startDate: '2026-09-24', endDate: '2026-09-25' }),
        ],
      })
      const wrapper = await openWithActiveConnection()
      await wrapper.find('select').setValue('next7')
      await showEvents(wrapper)

      expect(listCalendarEvents).toHaveBeenCalledWith(
        7,
        new Date(2026, 8, 23).toISOString(),
        new Date(2026, 8, 30).toISOString(),
      )
      const items = wrapper.findAll('li').map((item) => item.text().replace(/\s+/g, ' '))
      expect(items).toHaveLength(3)
      expect(items[0]).toContain('Budget review')
      expect(items[1]).toContain('Untitled event')
      expect(items[1]).toContain('tentative')
      expect(items[1]).toContain('repeats')
      expect(items[2]).toContain('Offsite')
      expect(items[2]).toContain('all day')
      // Each button says which event it copies, for someone who hears the buttons out of the list.
      expect(wrapper.find('#calendar-copy-evt1').text().replace(/\s+/g, ' ')).toBe('Copy Budget review')
    } finally {
      vi.useRealTimers()
    }
  })

  it('says when there is nothing in those days, and when there is more than it lists', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValueOnce({ timeZone: null, truncated: false, events: [] })
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    expect(text(wrapper)).toContain('No ordinary events on your main calendar in those days.')
    expect(wrapper.find('[aria-live="polite"][aria-atomic="true"]').text()).toBe('No ordinary events on your main calendar in those days.')

    vi.mocked(listCalendarEvents).mockResolvedValueOnce({ timeZone: null, truncated: true, events: [event()] })
    await showEvents(wrapper)
    expect(text(wrapper)).toContain('only the earliest 1 are shown.')
    expect(text(wrapper)).not.toContain('Choose 7 days')
    expect(wrapper.find('[aria-live="polite"][aria-atomic="true"]').text()).toBe('1 event listed, and there are more.')

    vi.mocked(listCalendarEvents).mockResolvedValueOnce({ timeZone: null, truncated: true, events: [event()] })
    await wrapper.find('select').setValue('last30')
    await showEvents(wrapper)
    await wrapper.find('select').setValue('next7')
    expect(text(wrapper)).toContain('Choose the last 7 days to see the most recent ones.')

    // The next 7 days start where the next 30 do, so they would show the same earliest events: no advice that cannot work.
    vi.mocked(listCalendarEvents).mockResolvedValueOnce({ timeZone: null, truncated: true, events: [event()] })
    await wrapper.find('select').setValue('next30')
    await showEvents(wrapper)
    expect(text(wrapper)).toContain('only the earliest 1 are shown.')
    expect(text(wrapper)).not.toContain('Choose')
  })

  it('has the page copy the chosen event, and says what happened', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    copyEvent.mockResolvedValue({ source: copiedSource(), newCopy: true })
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    await wrapper.find('#calendar-copy-evt1').trigger('click')
    await flushPromises()

    expect(copyEvent).toHaveBeenCalledWith('evt1')
    const notice = wrapper.find('[role="status"]')
    expect(notice.text()).toBe('Copied "Budget review" into this document\'s sources. Its times are as planned in the calendar.')
    expect(document.activeElement).toBe(notice.element)
  })

  it('says so when the event had already been copied as it is now', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    copyEvent.mockResolvedValue({ source: copiedSource(), newCopy: false })
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    await wrapper.find('#calendar-copy-evt1').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="status"]').text()).toBe(
      '"Budget review" had already been copied as it is now, so this document uses that copy.',
    )
  })

  it('copies once however quickly the person clicks', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    let finish: (value: { source: DocumentSourceResponse; newCopy: boolean }) => void = () => {}
    copyEvent.mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    const copy = wrapper.find('#calendar-copy-evt1')
    await copy.trigger('click')
    await copy.trigger('click')
    expect(copy.text()).toContain('Copying…')
    finish({ source: copiedSource(), newCopy: true })
    await flushPromises()

    expect(copyEvent).toHaveBeenCalledTimes(1)
  })

  it.each([
    [
      'the event is gone',
      new ApiRequestError(409, problem(409, 'That event is no longer in your calendar.', 'CONNECTOR_RESOURCE_UNAVAILABLE', { reason: 'GONE' })),
      'That event is no longer in your calendar.',
    ],
    [
      "Brownie's own checks refuse it",
      new ApiRequestError(422, problem(422, 'd', 'CONNECTOR_RESOURCE_REFUSED', { reason: 'MALWARE_DETECTED' })),
      '"Budget review" was not copied: Brownie\'s checks refused it.',
    ],
    [
      'Google is unreachable',
      new ApiRequestError(503, problem(503, 'd', 'CONNECTOR_PROVIDER_UNAVAILABLE')),
      '"Budget review" was not copied. Google could not be reached. Nothing was changed; try again in a minute.',
    ],
    [
      'the document went away',
      new ApiRequestError(404, problem(404, 'Document not found.', 'NOT_FOUND')),
      'This document is no longer available, so nothing was copied.',
    ],
  ])('says so when copying fails because %s, and puts focus back on that event', async (_why, failure, message) => {
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    copyEvent.mockRejectedValue(failure)
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    await wrapper.find('#calendar-copy-evt1').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe(message)
    expect(document.activeElement).toBe(wrapper.find('#calendar-copy-evt1').element)
  })

  it('turns into a connect-again offer when Google stops accepting the access while listing', async () => {
    vi.mocked(listConnections)
      .mockResolvedValueOnce([connection()])
      .mockResolvedValueOnce([connection({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])
    vi.mocked(listCalendarEvents).mockRejectedValue(
      new ApiRequestError(409, problem(409, 'd', 'CONNECTION_RECONNECT_REQUIRED', { reason: 'TOKEN_REJECTED' })),
    )
    const wrapper = await mountPicker()
    await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()
    await showEvents(wrapper)

    expect(wrapper.find('[role="alert"]').text()).toContain("Google stopped accepting Brownie's access.")
    expect(listConnections).toHaveBeenCalledTimes(2)
    expect(buttonNamed(wrapper, 'Connect Google Calendar again').exists()).toBe(true)
  })

  it('offers to connect again when a copy finds the connection gone or refused, instead of only saying so', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    copyEvent.mockRejectedValue(new ApiRequestError(404, problem(404, 'd', 'CONNECTION_NOT_FOUND')))
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    vi.mocked(listConnections).mockResolvedValue([connection({ state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z' })])
    await wrapper.find('#calendar-copy-evt1').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('"Budget review" was not copied. Google Calendar is not connected. Connect it first.')
    expect(buttonNamed(wrapper, 'Connect Google Calendar').exists()).toBe(true)
    expect(wrapper.find('#calendar-copy-evt1').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.find('#calendar-connect').element)
  })

  it('does not leave for Google while the document has changes not saved yet', async () => {
    vi.mocked(listConnections).mockResolvedValue([])
    const wrapper = await mountPicker(false, true)
    await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()
    await buttonNamed(wrapper, 'Connect Google Calendar').trigger('click')
    await flushPromises()

    expect(startGoogleConsent).not.toHaveBeenCalled()
    expect(navigateTo).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('changes that are not saved yet')
  })

  it('tells the page each time the person uses it', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [] })
    const wrapper = await mountPicker()
    await buttonNamed(wrapper, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()
    await showEvents(wrapper)

    expect(wrapper.emitted('used')).toHaveLength(2)
  })

  it('takes down a failed check once opening again finds the connection', async () => {
    vi.mocked(listConnections).mockRejectedValueOnce(new ApiRequestError(503, problem(503, 'Brownie is briefly unavailable.')))
    const wrapper = await mountPicker()
    const toggle = buttonNamed(wrapper, 'Copy an event from Google Calendar')
    await toggle.trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)

    vi.mocked(listConnections).mockResolvedValue([connection()])
    await toggle.trigger('click')
    await toggle.trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('asks again what the connection is each time it is opened', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    const wrapper = await mountPicker()
    const toggle = buttonNamed(wrapper, 'Copy an event from Google Calendar')
    await toggle.trigger('click')
    await flushPromises()
    await toggle.trigger('click')
    vi.mocked(listConnections).mockResolvedValue([])
    await toggle.trigger('click')
    await flushPromises()

    expect(listConnections).toHaveBeenCalledTimes(2)
    expect(buttonNamed(wrapper, 'Connect Google Calendar').exists()).toBe(true)
  })

  it('says one event is too large to copy, rather than to choose fewer days', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    copyEvent.mockRejectedValue(new ApiRequestError(413, problem(413, 'd', 'CONNECTOR_RESOURCE_TOO_LARGE')))
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    await wrapper.find('#calendar-copy-evt1').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('"Budget review" is larger than Brownie copies from Google Calendar, so it was not copied.')
  })

  it('keeps the focus on "Show events" while it reads, and ignores a second press', async () => {
    let finish: (value: { timeZone: null; truncated: boolean; events: CalendarEventResponse[] }) => void = () => {}
    vi.mocked(listCalendarEvents).mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const wrapper = await openWithActiveConnection()
    const show = buttonNamed(wrapper, 'Show events')
    ;(show.element as HTMLButtonElement).focus()
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')

    expect(show.attributes('disabled')).toBeUndefined()
    expect(show.attributes('aria-disabled')).toBe('true')
    expect(document.activeElement).toBe(show.element)
    finish({ timeZone: null, truncated: false, events: [event()] })
    await flushPromises()
    expect(listCalendarEvents).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[aria-live="polite"][aria-atomic="true"]').text()).toBe('1 event listed.')
  })

  it("says Google's answer was too large rather than retrying blindly", async () => {
    vi.mocked(listCalendarEvents).mockRejectedValue(new ApiRequestError(413, problem(413, 'd', 'CONNECTOR_RESOURCE_TOO_LARGE')))
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    expect(wrapper.find('[role="alert"]').text()).toBe("Google's answer was larger than Brownie reads. Choose fewer days.")
  })

  it('opens with the connection checked when the person has just come back from Google', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    const wrapper = await mountPicker(true)

    expect(buttonNamed(wrapper, 'Copy an event from Google Calendar').attributes('aria-expanded')).toBe('true')
    expect(listConnections).toHaveBeenCalledTimes(1)
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('opens with the offer to connect when connecting did not work', async () => {
    vi.mocked(listConnections).mockResolvedValue([])
    const wrapper = await mountPicker(true)

    expect(buttonNamed(wrapper, 'Connect Google Calendar').exists()).toBe(true)
  })

  it('keeps nothing in the browser: no token, no event, not even the connection', async () => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    vi.mocked(listConnections).mockResolvedValue([connection({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])
    vi.mocked(startGoogleConsent).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' })
    const wrapper = await mountPicker(true)
    await buttonNamed(wrapper, 'Connect Google Calendar again').trigger('click')
    await flushPromises()

    vi.mocked(listConnections).mockResolvedValue([connection()])
    vi.mocked(listCalendarEvents).mockResolvedValue({ timeZone: null, truncated: false, events: [event()] })
    copyEvent.mockResolvedValue({ source: copiedSource(), newCopy: true })
    const again = await mountPicker()
    await buttonNamed(again, 'Copy an event from Google Calendar').trigger('click')
    await flushPromises()
    await showEvents(again)
    await again.find('#calendar-copy-evt1').trigger('click')
    await flushPromises()

    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(document.cookie).toBe('')
  })

  it('is built so that nothing about a connection can be kept in the browser', () => {
    // Everything that handles a connection, an event or a copy in the browser; the sign-in code that keeps where to go
    // after signing in is the only user of browser storage in this app, and handles none of these.
    const sources = import.meta.glob(
      [
        '../CalendarSourcePicker.vue',
        '../DriveSourcePicker.vue',
        '../../views/ConnectionsView.vue',
        '../../views/WorkspaceView.vue',
        '../../views/YourDataView.vue',
        '../../connections/*.ts',
        '../../api/client.ts',
        '../../navigation.ts',
      ],
      {
      query: '?raw',
      import: 'default',
      eager: true,
      },
    ) as Record<string, string>
    expect(Object.keys(sources).length).toBeGreaterThanOrEqual(9)
    for (const [file, text] of Object.entries(sources)) {
      // Reading the request-forgery cookie the server sets is not keeping anything; writing a cookie would be.
      expect(text, file).not.toMatch(/localStorage|sessionStorage|indexedDB|document\.cookie\s*=(?!=)|caches\./)
    }
  })

  it('has no accessibility violations with events listed', async () => {
    vi.mocked(listCalendarEvents).mockResolvedValue({
      timeZone: null,
      truncated: true,
      events: [event(), event({ id: 'evt2', status: 'TENTATIVE', recurring: true })],
    })
    const wrapper = await openWithActiveConnection()
    await showEvents(wrapper)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
