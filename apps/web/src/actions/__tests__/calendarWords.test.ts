import { describe, expect, it } from 'vitest'
import { ApiRequestError, type ActionResponse } from '@/api/client'
import { actionLink, describeActionFailure } from '@/actions/words'
import {
  CALENDAR_EVENT_WORDS,
  calendarEventPreview,
  calendarEventSentence,
  whenSentence,
  type EventWhen,
} from '@/actions/calendarWords'

const TIMED: EventWhen = {
  allDay: false,
  timeZone: 'Europe/Paris',
  start: '2026-10-05T09:00:00+02:00',
  end: '2026-10-05T10:30:00+02:00',
  startIsFirstOfTwo: false,
  endIsFirstOfTwo: false,
}

function payload(overrides: { target?: Record<string, unknown>; event?: Record<string, unknown>; type?: string } = {}) {
  return {
    schema: 'brownie.action/1',
    type: overrides.type ?? 'CALENDAR_CREATE_EVENT',
    nonce: 'n',
    proposedBy: 3,
    workspace: 7,
    document: { id: 42, title: 'Minutes' },
    account: { connection: 5, email: 'me@example.org' },
    target: { calendar: 'PRIMARY', guests: 'NONE', notifications: 'NONE', repeats: 'NEVER', conference: 'NONE', ...overrides.target },
    event: {
      title: 'Budget review',
      description: 'Bring the figures.',
      location: 'Town hall',
      when: TIMED,
      reminders: 'CALENDAR_DEFAULT',
      showAs: 'BUSY',
      visibility: 'PRIVATE',
      ...overrides.event,
    },
    effect: { creates: 'NEW_EVENT' },
  }
}

function action(overrides: Partial<ActionResponse> = {}): ActionResponse {
  return {
    id: 1,
    type: 'CALENDAR_CREATE_EVENT',
    documentId: 42,
    state: 'AWAITING_APPROVAL',
    payload: payload(),
    payloadHash: 'c'.repeat(64),
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

describe('what adding an event would do, read from its payload', () => {
  it('states the event, its time and its account', () => {
    expect(calendarEventPreview(action())).toEqual({
      title: 'Budget review',
      description: 'Bring the figures.',
      location: 'Town hall',
      accountEmail: 'me@example.org',
      when: TIMED,
    })
  })

  it('refuses to state an event with guests, messages, a repeat, a call, another calendar or other settings', () => {
    for (const target of [{ guests: 'ALL' }, { notifications: 'ALL' }, { repeats: 'WEEKLY' }, { conference: 'MEET' }, { calendar: 'OTHER' }]) {
      expect(calendarEventPreview(action({ payload: payload({ target }) })), JSON.stringify(target)).toBeNull()
    }
    for (const event of [
      { visibility: 'PUBLIC' },
      { showAs: 'FREE' },
      { reminders: 'NONE' },
      { title: '' },
      { when: { ...TIMED, start: '2026-10-05T09:00+02:00' } },
      { when: { allDay: true, startDate: '2026-10-05' } },
      { when: { ...TIMED, startIsFirstOfTwo: 'no' } },
    ]) {
      expect(calendarEventPreview(action({ payload: payload({ event }) })), JSON.stringify(event)).toBeNull()
    }
    expect(calendarEventPreview(action({ payload: payload({ type: 'DRIVE_SAVE_FILE' }) }))).toBeNull()
  })
})

describe('when an event happens, said in its own time zone', () => {
  const inParis = (options: Intl.DateTimeFormatOptions, moment: string) =>
    new Intl.DateTimeFormat(undefined, { ...options, timeZone: 'Europe/Paris' }).format(new Date(moment))

  it('says the day and both times in the event zone with its offset, whatever the viewer zone', () => {
    const said = whenSentence(TIMED)
    expect(said).toBe(
      `${inParis({ dateStyle: 'full', timeStyle: 'short' }, TIMED.start)} to ${inParis({ timeStyle: 'short' }, TIMED.end)} ` +
        '(Europe/Paris, UTC+02:00).',
    )
  })

  it('says when a time happens twice and the first was taken, and names both offsets across a change', () => {
    const said = whenSentence({
      allDay: false,
      timeZone: 'Europe/Paris',
      start: '2026-10-25T02:30:00+02:00',
      end: '2026-10-25T04:00:00+01:00',
      startIsFirstOfTwo: true,
      endIsFirstOfTwo: false,
    })
    expect(said).toContain('(Europe/Paris, UTC+02:00 to UTC+01:00).')
    expect(said).toContain('The start time happens twice that day, because the clocks go back; this is the first.')
    expect(said).not.toContain('The end time happens twice')
  })

  it('says all-day events by their first and last day', () => {
    const oneDay = whenSentence({ allDay: true, startDate: '2026-10-05', endDate: '2026-10-06' })
    const day = new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeZone: 'UTC' })
    expect(oneDay).toBe(`${day.format(new Date('2026-10-05T00:00:00Z'))}, all day`)
    expect(whenSentence({ allDay: true, startDate: '2026-10-05', endDate: '2026-10-08' })).toBe(
      `${day.format(new Date('2026-10-05T00:00:00Z'))} to ${day.format(new Date('2026-10-07T00:00:00Z'))}, all day`,
    )
  })

  it('falls back to the exact moments for a zone the browser does not know', () => {
    expect(whenSentence({ ...TIMED, timeZone: 'Mars/Olympus' })).toBe(
      '2026-10-05T09:00:00+02:00 to 2026-10-05T10:30:00+02:00 (Mars/Olympus)',
    )
  })
})

describe('where an event stands', () => {
  const preview = calendarEventPreview(action())

  it('says each state, an event deleted since as added, and never that an unknown outcome did not happen', () => {
    expect(calendarEventSentence(action(), preview)).toMatch(/^Ready to add "Budget review". Nothing is added until you approve it/)
    expect(calendarEventSentence(action({ state: 'SUCCEEDED', verification: 'MATCHED' }), preview)).toBe(
      'Added "Budget review" to your calendar.',
    )
    expect(calendarEventSentence(action({ state: 'SUCCEEDED', verification: 'REMOVED_AFTERWARDS' }), preview)).toBe(
      '"Budget review" was added to your calendar, and has since been deleted there.',
    )
    const unknown = calendarEventSentence(action({ state: 'OUTCOME_UNKNOWN', sent: true }), preview)
    expect(unknown).toContain('Brownie did not send it again, and will not')
    expect(unknown).not.toContain('nothing was added')
    expect(calendarEventSentence(action({ state: 'FAILED', failure: 'READBACK_MISMATCH' }), preview)).toContain('Google added an event')
    expect(calendarEventSentence(action({ state: 'FAILED', failure: 'LIMIT_REACHED' }), preview)).toContain('nothing was added')
    expect(calendarEventSentence(action({ state: 'OUTCOME_UNKNOWN', sent: false, outcomeAcknowledged: true }), preview)).toBe(
      'Nothing was sent for "Budget review", so it was not added. The try that stopped is closed.',
    )
  })

  it('links only to Google Calendar pages over https', () => {
    expect(actionLink(action({ externalLink: 'https://www.google.com/calendar/event?eid=abc' }))).toBe(
      'https://www.google.com/calendar/event?eid=abc',
    )
    expect(actionLink(action({ externalLink: 'https://calendar.google.com/calendar/r/eventedit/abc' }))).not.toBeNull()
    expect(actionLink(action({ externalLink: 'https://www.google.com/search?q=calendar' }))).toBeNull()
    expect(actionLink(action({ externalLink: 'https://me:pw@calendar.google.com/x' }))).toBeNull()
  })

  it('words a refused proposal in the server own precise words where only it can say what', () => {
    const skipped = new ApiRequestError(409, {
      status: 409,
      title: 't',
      code: 'ACTION_NOT_PROPOSABLE',
      reason: 'TIME_SKIPPED',
      detail: 'The start time does not happen on that day in Europe/Paris: the clocks go forward over it.',
      correlationId: 'c',
      fields: [],
      recoveryActions: [],
    })
    expect(describeActionFailure(skipped, 'x', CALENDAR_EVENT_WORDS)).toBe(
      'The start time does not happen on that day in Europe/Paris: the clocks go forward over it.',
    )
    const notOffered = new ApiRequestError(409, {
      status: 409,
      title: 't',
      code: 'ACTION_NOT_OFFERED',
      correlationId: 'c',
      fields: [],
      recoveryActions: [],
    })
    expect(describeActionFailure(notOffered, 'x', CALENDAR_EVENT_WORDS)).toBe(
      'This Brownie does not add events to Google Calendar at the moment.',
    )
  })
})
