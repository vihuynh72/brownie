import type { ActionResponse } from '@/api/client'
import { timeOf, type ActionKindWords } from '@/actions/words'

/**
 * What the pages say about adding an event to a person's calendar: what the
 * event will be, read only from the payload the person approves, where it
 * stands, and why it was not added.
 */

export const CALENDAR_EVENT_WORDS: ActionKindWords = {
  access: 'CALENDAR_EVENT_CREATION',
  notOffered: 'This Brownie does not add events to Google Calendar at the moment.',
  cannot: 'This event cannot be added to Google Calendar as it is.',
  mismatch: 'What was approved is not what adding this event would do, so it was not approved. Look at it again.',
  sibling:
    'The same event, with this title at this time, is being added from this or another document, or an earlier try may already ' +
    'have added it. Look in your calendar, or check it where it was prepared.',
  connectionUnusable:
    'Brownie can only ask about this with the Google account that added it. Connect that account for adding events again to ask.',
  accessNotOffered: 'This Brownie does not add events to Google Calendar, so there is nothing to connect for it.',
  notFound: 'This document, or this event, is no longer available.',
  hidden: "Some of the event's text holds characters that reorder it or cannot be seen, and Brownie does not send those. Remove them.",
}

/** When an event happens, exactly as its payload states it. */
export type EventWhen =
  | { allDay: false; timeZone: string; start: string; end: string; startIsFirstOfTwo: boolean; endIsFirstOfTwo: boolean }
  | { allDay: true; startDate: string; endDate: string }

/** An event exactly as its payload states it. */
export interface CalendarEventPreview {
  title: string
  description: string
  location: string
  accountEmail: string | null
  when: EventWhen
}

function objectOf(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

const MOMENT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/
const DAY = /^\d{4}-\d{2}-\d{2}$/

function whenOf(value: unknown): EventWhen | null {
  const when = objectOf(value)
  if (when === null) return null
  if (when.allDay === true) {
    const { startDate, endDate } = when
    return typeof startDate === 'string' && DAY.test(startDate) && typeof endDate === 'string' && DAY.test(endDate)
      ? { allDay: true, startDate, endDate }
      : null
  }
  if (when.allDay !== false) return null
  const { timeZone, start, end, startIsFirstOfTwo, endIsFirstOfTwo } = when
  if (typeof timeZone !== 'string' || timeZone === '' || typeof start !== 'string' || !MOMENT.test(start)) return null
  if (typeof end !== 'string' || !MOMENT.test(end) || typeof startIsFirstOfTwo !== 'boolean' || typeof endIsFirstOfTwo !== 'boolean') {
    return null
  }
  return { allDay: false, timeZone, start, end, startIsFirstOfTwo, endIsFirstOfTwo }
}

/**
 * The event a payload describes, or null when it is not one this page can
 * state in full: another calendar, guests, messages, a repeat, a call, or
 * settings Brownie does not propose. An event the page cannot state is
 * never offered for approval.
 */
export function calendarEventPreview(action: ActionResponse): CalendarEventPreview | null {
  const payload = objectOf(action.payload)
  const account = objectOf(payload?.account)
  const target = objectOf(payload?.target)
  const event = objectOf(payload?.event)
  const effect = objectOf(payload?.effect)
  if (payload === null || account === null || target === null || event === null || effect === null) return null
  if (payload.schema !== 'brownie.action/1' || payload.type !== 'CALENDAR_CREATE_EVENT' || action.type !== 'CALENDAR_CREATE_EVENT') return null
  if (
    target.calendar !== 'PRIMARY' ||
    target.guests !== 'NONE' ||
    target.notifications !== 'NONE' ||
    target.repeats !== 'NEVER' ||
    target.conference !== 'NONE' ||
    effect.creates !== 'NEW_EVENT'
  ) {
    return null
  }
  if (event.reminders !== 'CALENDAR_DEFAULT' || event.showAs !== 'BUSY' || event.visibility !== 'PRIVATE') return null
  const { title, description, location } = event
  const email = account.email ?? null
  const when = whenOf(event.when)
  if (typeof title !== 'string' || title === '' || typeof description !== 'string' || typeof location !== 'string') return null
  if ((email !== null && typeof email !== 'string') || when === null) return null
  return { title, description, location, accountEmail: email, when }
}

/** The offset a moment was written with, as a person reads it: "UTC+02:00", or "UTC" for none. */
function offsetOf(moment: string): string {
  const offset = moment.slice(-6)
  return offset === '+00:00' || offset === '-00:00' ? 'UTC' : `UTC${offset}`
}

function dayName(date: string): string {
  // A day with no time of day: shown as that day wherever the viewer is.
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`))
}

/** The day before an ISO day, as ISO: the last day of an all-day event whose end day is not included. */
function dayBefore(date: string): string {
  const day = new Date(`${date}T00:00:00Z`)
  day.setUTCDate(day.getUTCDate() - 1)
  return day.toISOString().slice(0, 10)
}

/** When an event starts, briefly and in its own time zone: what tells it apart from another of the same title. */
export function startsAt(when: EventWhen): string {
  if (when.allDay) return dayName(when.startDate)
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: when.timeZone }).format(new Date(when.start))
  } catch {
    return when.start
  }
}

/**
 * When the event happens, in its own time zone (never the viewer's), with
 * the offset each time has there, and a word when a time happens twice that
 * day and the first was taken.
 */
export function whenSentence(when: EventWhen): string {
  if (when.allDay) {
    const last = dayBefore(when.endDate)
    return last === when.startDate ? `${dayName(when.startDate)}, all day` : `${dayName(when.startDate)} to ${dayName(last)}, all day`
  }
  let dateTime: Intl.DateTimeFormat
  let time: Intl.DateTimeFormat
  try {
    dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'short', timeZone: when.timeZone })
    time = new Intl.DateTimeFormat(undefined, { timeStyle: 'short', timeZone: when.timeZone })
  } catch {
    // A zone this browser does not know: the moments as written, which are exact all the same.
    return `${when.start} to ${when.end} (${when.timeZone})`
  }
  const start = new Date(when.start)
  const end = new Date(when.end)
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: when.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
  const sameDay = day.format(start) === day.format(end)
  const offsets = offsetOf(when.start) === offsetOf(when.end) ? offsetOf(when.start) : `${offsetOf(when.start)} to ${offsetOf(when.end)}`
  const span = sameDay ? `${dateTime.format(start)} to ${time.format(end)}` : `${dateTime.format(start)} to ${dateTime.format(end)}`
  const twice = [
    when.startIsFirstOfTwo ? 'The start time happens twice that day, because the clocks go back; this is the first.' : null,
    when.endIsFirstOfTwo ? 'The end time happens twice that day, because the clocks go back; this is the first.' : null,
  ].filter((sentence): sentence is string => sentence !== null)
  return [`${span} (${when.timeZone}, ${offsets}).`, ...twice].join(' ')
}

const FAILURES: Record<NonNullable<ActionResponse['failure']>, string> = {
  CONNECTION_CHANGED: 'The Google connection changed after this event was prepared, so nothing was added. Prepare it again.',
  DOCUMENT_GONE: 'The document was moved to the trash, so nothing was added.',
  DOCUMENT_CHANGED: 'The document changed after this event was prepared, so nothing was added. Prepare it again.',
  EXPORT_CHANGED: 'The document changed after this event was prepared, so nothing was added. Prepare it again.',
  TARGET_CHANGED: 'Your calendar is no longer as it was when this event was prepared, so nothing was added.',
  CONTENT_CHANGED: 'This event is no longer exactly the one prepared, so nothing was added. Prepare it again.',
  PROVIDER_REFUSED: 'Google refused this event, so nothing was added.',
  STORAGE_FULL: 'Google refused this event, so nothing was added.',
  BLOCKED_BY_ORGANIZATION: 'The organization that manages this Google account does not allow this, so nothing was added.',
  PERMISSION_REFUSED: 'Google did not let Brownie add an event to this calendar, so nothing was added.',
  TARGET_UNAVAILABLE: 'Your main calendar could not be found, so nothing was added.',
  LIMIT_REACHED: "Google's limit on adding events was reached, so nothing was added. Prepare it again later.",
  READBACK_MISMATCH:
    'Google added an event, but when Brownie read it back it was not exactly what you approved, or it had been changed since. ' +
    'Look at it in your calendar: Brownie did not change or remove it.',
}

/**
 * Where one event stands, in one or two sentences, read only from the action
 * itself. An event whose outcome is unknown says so, and says it was not sent
 * again; Brownie never sends it again, since Google cannot prove it was not
 * added.
 */
export function calendarEventSentence(action: ActionResponse, preview: CalendarEventPreview | null): string {
  const name = preview ? `"${preview.title}"` : 'this event'
  const Name = preview ? `"${preview.title}"` : 'This event'
  switch (action.state) {
    case 'AWAITING_APPROVAL': {
      const until = timeOf(action.expiresAt)
      return `Ready to add ${name}. Nothing is added until you approve it${until ? `, which you can do until ${until}` : ''}.`
    }
    case 'APPROVED': {
      const until = timeOf(action.approvalExpiresAt)
      return `${Name} was not added: it did not reach Google, or Google turned it away for now. You can try again${
        until ? ` until ${until}` : ''
      }, or cancel.`
    }
    case 'EXECUTING':
      return `Brownie is adding ${name}, or was when this page last asked. Check again to see where it stands.`
    case 'RECONCILING':
      return `Brownie is asking Google what became of ${name}.`
    case 'SUCCEEDED':
      return action.verification === 'REMOVED_AFTERWARDS'
        ? `${Name} was added to your calendar, and has since been deleted there.`
        : `Added ${name} to your calendar.`
    case 'FAILED':
      return (action.failure ? FAILURES[action.failure] : undefined) ?? 'This event was not added.'
    case 'OUTCOME_UNKNOWN':
      if (!action.sent) {
        return `Nothing was sent for ${name}, so it was not added. The try that stopped is closed.`
      }
      if (action.outcomeAcknowledged) {
        return `You said you looked in your calendar for ${name}. Brownie still cannot tell whether it was added.`
      }
      return (
        `Brownie cannot tell yet whether ${name} was added as you approved it: Google's answer did not arrive, or did not say ` +
        'enough. Brownie did not send it again, and will not: ask Google what happened, or look for it in your calendar.'
      )
    case 'CANCELLED':
      return `Cancelled. ${Name} was not added.`
    case 'EXPIRED':
      return action.approvedAt
        ? `The approval ran out before ${name} could be added, so nothing was added.`
        : 'This was not approved in time, so nothing was added.'
    default:
      return ''
  }
}
