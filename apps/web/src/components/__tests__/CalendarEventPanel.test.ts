import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import CalendarEventPanel from '@/components/CalendarEventPanel.vue'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCapabilities: vi.fn(),
    listConnections: vi.fn(),
    listActions: vi.fn(),
    getAction: vi.fn(),
    proposeCalendarEvent: vi.fn(),
    approveAction: vi.fn(),
    reconcileAction: vi.fn(),
    cancelAction: vi.fn(),
    acknowledgeAction: vi.fn(),
    startGoogleConsent: vi.fn(),
  }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

import {
  ApiRequestError,
  acknowledgeAction,
  approveAction,
  getCapabilities,
  listActions,
  listConnections,
  proposeCalendarEvent,
  reconcileAction,
  startGoogleConsent,
  type ActionResponse,
  type CapabilitiesResponse,
  type ConnectionResponse,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }
const mounted: { unmount(): void }[] = []
const HASH = 'c'.repeat(64)
const AT = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(
  new Date('2026-09-28T10:00:00Z'),
)
const STARTS = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Paris' }).format(
  new Date('2026-10-05T09:00:00+02:00'),
)
const EVENT = `Budget review, ${STARTS}, prepared at ${AT}`

async function mountPanel(options: { unsavedWork?: boolean; suggestedDate?: string | null } = {}) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/connections', component: stub },
    ],
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(CalendarEventPanel, {
    props: {
      workspaceId: 7,
      documentId: 42,
      documentTitle: 'Budget review',
      suggestedDate: options.suggestedDate === undefined ? '2026-10-05' : options.suggestedDate,
      unsavedWork: options.unsavedWork ?? false,
    },
    attachTo: document.body,
    global: { plugins: [router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountPanel>>

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, code: string, reason?: string, detail = 'The server said something else.') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [], ...(reason === undefined ? {} : { reason }) }
}

function capabilities(googleActions?: ActionResponse['type'][]): CapabilitiesResponse {
  return {
    maxUploadBytes: 10485760,
    uploadMediaTypes: [],
    assistSourceMediaTypes: [],
    templateMediaTypes: [],
    trashRetentionDays: 30,
    googleConnectorAccess: googleActions && googleActions.length > 0 ? ['CALENDAR_EVENT_CREATION'] : [],
    ...(googleActions === undefined ? {} : { googleActions }),
  }
}

function adding(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id: 6,
    provider: 'GOOGLE',
    access: 'CALENDAR_EVENT_CREATION',
    state: 'ACTIVE',
    accountEmail: 'me@example.org',
    grantedScopes: ['openid', 'https://www.googleapis.com/auth/calendar.events.owned'],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [],
    ...overrides,
  }
}

function payload() {
  return {
    schema: 'brownie.action/1',
    type: 'CALENDAR_CREATE_EVENT',
    nonce: 'n',
    proposedBy: 3,
    workspace: 7,
    document: { id: 42, title: 'Budget review' },
    account: { connection: 6, email: 'me@example.org' },
    target: { calendar: 'PRIMARY', guests: 'NONE', notifications: 'NONE', repeats: 'NEVER', conference: 'NONE' },
    event: {
      title: 'Budget review',
      description: '',
      location: 'Town hall',
      when: {
        allDay: false,
        timeZone: 'Europe/Paris',
        start: '2026-10-05T09:00:00+02:00',
        end: '2026-10-05T10:00:00+02:00',
        startIsFirstOfTwo: false,
        endIsFirstOfTwo: false,
      },
      reminders: 'CALENDAR_DEFAULT',
      showAs: 'BUSY',
      visibility: 'PRIVATE',
    },
    effect: { creates: 'NEW_EVENT' },
  }
}

function action(overrides: Partial<ActionResponse> = {}): ActionResponse {
  return {
    id: 41,
    type: 'CALENDAR_CREATE_EVENT',
    documentId: 42,
    state: 'AWAITING_APPROVAL',
    payload: payload(),
    payloadHash: HASH,
    createdAt: '2026-09-28T10:00:00Z',
    expiresAt: '2026-09-28T10:30:00Z',
    approvedAt: null,
    approvalExpiresAt: null,
    sent: false,
    verification: null,
    conversionCheck: null,
    failure: null,
    outcomeAcknowledged: false,
    externalLink: null,
    finishedAt: null,
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

beforeEach(() => {
  resetCapabilitiesCache()
  vi.mocked(getCapabilities).mockResolvedValue(capabilities(['CALENDAR_CREATE_EVENT']))
  vi.mocked(listConnections).mockResolvedValue([adding()])
  vi.mocked(listActions).mockResolvedValue([])
})

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.clearAllMocks()
})

describe('where adding events is offered', () => {
  it('prepares the event as typed, shows exactly what approving adds, and adds it only with the hash of what was shown', async () => {
    vi.mocked(proposeCalendarEvent).mockResolvedValue(action())
    const wrapper = await mountPanel()
    expect((wrapper.find('#calendar-event-title').element as HTMLInputElement).value).toBe('Budget review')
    expect((wrapper.find('#calendar-event-start').element as HTMLInputElement).value).toBe('2026-10-05T09:00')
    await wrapper.find('#calendar-event-zone').setValue('Europe/Paris')
    await wrapper.find('#calendar-event-location').setValue('Town hall')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(proposeCalendarEvent).toHaveBeenCalledWith(7, {
      documentId: 42,
      title: 'Budget review',
      description: '',
      location: 'Town hall',
      allDay: false,
      timeZone: 'Europe/Paris',
      start: '2026-10-05T09:00',
      end: '2026-10-05T10:00',
    })
    expect(approveAction).not.toHaveBeenCalled()
    expect(text(wrapper)).toContain('(Europe/Paris, UTC+02:00).')
    expect(text(wrapper)).toContain('On the main calendar of me@example.org, at Town hall.')
    expect(text(wrapper)).toContain('Nobody. The event has no guests, and Google is told to send no invitation or email.')
    expect(window.document.activeElement?.id).toBe('calendar-event-41')

    vi.mocked(approveAction).mockResolvedValue(
      action({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED', externalLink: 'https://www.google.com/calendar/event?eid=abc' }),
    )
    await buttonNamed(wrapper, `Add to Google Calendar ${EVENT}`).trigger('click')
    await flushPromises()

    expect(approveAction).toHaveBeenCalledTimes(1)
    expect(approveAction).toHaveBeenCalledWith(7, 41, HASH)
    expect(text(wrapper)).toContain('Added "Budget review" to your calendar.')
    expect(wrapper.find('a[target="_blank"]').attributes('href')).toBe('https://www.google.com/calendar/event?eid=abc')
  })

  it('prepares an all-day event by its first and last day, with no time zone', async () => {
    vi.mocked(proposeCalendarEvent).mockResolvedValue(action())
    const wrapper = await mountPanel()
    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.find('#calendar-event-last-day').setValue('2026-10-06')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(proposeCalendarEvent).toHaveBeenCalledWith(7, expect.objectContaining({
      allDay: true, timeZone: null, start: '2026-10-05', end: '2026-10-06',
    }))
  })

  it('says why a time was refused in the server own words, and records nothing', async () => {
    vi.mocked(proposeCalendarEvent).mockRejectedValue(new ApiRequestError(409, problem(409, 'ACTION_NOT_PROPOSABLE', 'TIME_SKIPPED',
      'The start time does not happen on that day in Europe/Paris: the clocks go forward over it.')))
    const wrapper = await mountPanel()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(text(wrapper)).toContain(
      'Nothing was prepared. The start time does not happen on that day in Europe/Paris: the clocks go forward over it.',
    )
    expect(wrapper.findAll('li')).toHaveLength(0)
  })

  it('never sends an event again whose outcome is unknown: it asks Google, or records that the person looked', async () => {
    const unknown = action({ state: 'OUTCOME_UNKNOWN', sent: true, approvedAt: '2026-09-28T10:05:00Z' })
    vi.mocked(listActions).mockResolvedValue([unknown])
    vi.mocked(reconcileAction).mockResolvedValue(unknown)
    vi.mocked(acknowledgeAction).mockResolvedValue({ ...unknown, outcomeAcknowledged: true })
    const wrapper = await mountPanel()
    expect(text(wrapper)).toContain('Brownie did not send it again, and will not')

    await buttonNamed(wrapper, `Ask Google what happened to ${EVENT}`).trigger('click')
    await flushPromises()
    expect(reconcileAction).toHaveBeenCalledWith(7, 41)
    await buttonNamed(wrapper, `I looked in my calendar for ${EVENT}`).trigger('click')
    await flushPromises()
    expect(acknowledgeAction).toHaveBeenCalledWith(7, 41)
    expect(text(wrapper)).toContain('You said you looked in your calendar')
    expect(approveAction).not.toHaveBeenCalled()
  })

  it('says when approving would add the same event a second time', async () => {
    vi.mocked(listActions).mockResolvedValue([action({ id: 42 }), action({ id: 41, state: 'SUCCEEDED', sent: true, verification: 'MATCHED' })])
    const wrapper = await mountPanel()
    expect(text(wrapper)).toContain('You already added this event to your calendar. Approving this one adds it a second time.')
  })

  it('does not warn of an event deleted since, nor of a try that never sent it', async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({ id: 42 }),
      action({ id: 41, state: 'SUCCEEDED', sent: true, verification: 'REMOVED_AFTERWARDS' }),
      action({ id: 40, state: 'OUTCOME_UNKNOWN', sent: false, outcomeAcknowledged: true }),
    ])
    const wrapper = await mountPanel()
    const waiting = wrapper.find('#calendar-event-42').element.parentElement!.textContent ?? ''
    expect(waiting).not.toContain('already added')
    expect(waiting).not.toContain('may already have added')
  })

  it('warns before approving an event Google added although it did not read back as approved', async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({ id: 42 }),
      action({ id: 41, state: 'FAILED', failure: 'READBACK_MISMATCH', sent: true }),
    ])
    const wrapper = await mountPanel()
    expect(text(wrapper)).toContain('An earlier try may already have added this event. Look in your calendar before approving this one.')
  })

  it('warns before approving an event an earlier try may already have added', async () => {
    vi.mocked(listActions).mockResolvedValue([
      action({ id: 42 }),
      action({ id: 41, state: 'OUTCOME_UNKNOWN', sent: true, outcomeAcknowledged: true }),
    ])
    const wrapper = await mountPanel()
    expect(text(wrapper)).toContain('An earlier try may already have added this event. Look in your calendar before approving this one.')
  })

  it('tells two events of the same title apart by when they happen, and lets the person say they looked after an attempt stopped', async () => {
    const later = action({ id: 43 })
    const payload = later.payload as Record<string, Record<string, unknown>>
    const moved = { ...payload, event: { ...payload.event, when: { ...(payload.event.when as object), start: '2026-10-05T11:00:00+02:00', end: '2026-10-05T12:00:00+02:00' } } }
    vi.mocked(listActions).mockResolvedValue([
      { ...later, payload: moved },
      action({ id: 42, state: 'RECONCILING', sent: true, attemptStopped: true }),
    ])
    const wrapper = await mountPanel()

    const cancels = wrapper.findAll('button').map((button) => button.text()).filter((name) => name.startsWith('Cancel'))
    expect(cancels).toHaveLength(1)
    expect(buttonNamed(wrapper, `I looked in my calendar for ${EVENT}`).exists()).toBe(true)
    expect(text(wrapper)).toContain('That try stopped before Brownie heard how it ended.')
    const names = wrapper.findAll('button').map((button) => button.text())
    expect(new Set(names).size).toBe(names.length)
  })

  it('has no accessibility violations with an event shown', async () => {
    vi.mocked(listActions).mockResolvedValue([action(), action({ id: 40, state: 'OUTCOME_UNKNOWN', sent: true })])
    const wrapper = await mountPanel()
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

describe('connecting for adding events', () => {
  it('asks for its own connection, back to this document, and waits for unsaved changes', async () => {
    vi.mocked(listConnections).mockResolvedValue([])
    const waiting = await mountPanel({ unsavedWork: true })
    expect(text(waiting)).toContain('Adding events to Google Calendar is not connected.')
    await buttonNamed(waiting, 'Connect Google Calendar for adding events').trigger('click')
    await flushPromises()
    expect(startGoogleConsent).not.toHaveBeenCalled()

    vi.mocked(startGoogleConsent).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' })
    const wrapper = await mountPanel()
    await buttonNamed(wrapper, 'Connect Google Calendar for adding events').trigger('click')
    await flushPromises()
    expect(startGoogleConsent).toHaveBeenCalledWith(7, 'CALENDAR_EVENT_CREATION', '/documents/42')
  })
})

describe('where adding events is not offered', () => {
  it('shows nothing on a server that does not say which changes it makes', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities())
    const wrapper = await mountPanel()
    expect(wrapper.find('section').exists()).toBe(false)
    expect(listActions).not.toHaveBeenCalled()
  })

  it('still shows earlier events with the way to check them, and offers no approval, once switched off', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    vi.mocked(listActions).mockResolvedValue([action(), action({ id: 40, state: 'OUTCOME_UNKNOWN', sent: true })])
    const wrapper = await mountPanel()
    expect(wrapper.find('form').exists()).toBe(false)
    const names = wrapper.findAll('button').map((button) => button.text())
    expect(names.some((name) => name.startsWith('Add to Google Calendar'))).toBe(false)
    expect(names.some((name) => name.startsWith('Ask Google what happened'))).toBe(true)
    expect(text(wrapper)).toContain('This Brownie no longer adds events to Google Calendar, so this can only be cancelled.')
  })
})
