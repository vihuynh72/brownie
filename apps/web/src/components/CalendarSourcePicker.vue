<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { loadCapabilities } from '@/capabilities'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import {
  ApiRequestError,
  listCalendarEvents,
  listConnections,
  startGoogleConsent,
  type CalendarEventResponse,
  type CalendarEventsResponse,
  type CalendarImportResponse,
  type ConnectionResponse,
} from '@/api/client'
import { describeConnectorFailure, latestConnection, offeredAccess, stateSentence } from '@/connections/words'
import { calendarWindow, WINDOW_CHOICES, type WindowChoice } from '@/connections/calendarWindow'

/**
 * Copying one event from the person's own Google calendar into this
 * document as a source. Nothing here reads the calendar until the person
 * asks for a stretch of days, because reading it is also what records that
 * Brownie may; and the copy is made only for the one event they choose.
 * Offered only where this Brownie can connect Google Calendar at all.
 *
 * The copy itself is made by the page ({@code copyEvent}), not here: this
 * control lives in a tab, and a copy that finishes after the tab was left
 * must still reach the document's list of sources.
 */
const props = defineProps<{
  workspaceId: number
  documentId: number
  /** Open from the start: Google has just sent the person back here, and the page has said what it answered. */
  startOpen: boolean
  /** Changes not saved yet: connecting leaves the page, so it waits for them rather than have the browser ask. */
  unsavedWork: boolean
  copyEvent: (eventId: string) => Promise<CalendarImportResponse>
}>()
/** Told when the person does anything here, so the page can take down what it said about Google's answer. */
const emit = defineEmits<{ used: [] }>()

const offered = ref(false)
const open = ref(false)
const connection = ref<ConnectionResponse | null>(null)
const connectionState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const choice = ref<WindowChoice>('last7')
const listing = ref<CalendarEventsResponse | null>(null)
const listState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const importingId = ref<string | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const noticeElement = ref<HTMLElement | null>(null)
/** Said once a listing arrives, in a region that is always there, so assistive technology hears it. */
const listAnnouncement = ref('')
/** The days the list on screen is for, which the menu may no longer show. */
const listedChoice = ref<WindowChoice>('last7')

const dateFormatter = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
const dateTimeFormatter = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})
const timeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })

function localDay(value: string): string {
  // A date with no time of day: read as the person's own midnight, not UTC's.
  return dateFormatter.format(new Date(`${value}T00:00:00`))
}

/** When an event is planned, in the person's own time zone; the copy itself names the calendar's. */
function when(event: CalendarEventResponse): string {
  if (event.allDay && event.startDate) {
    const last = event.endDate ?? event.startDate
    return last === event.startDate ? `${localDay(event.startDate)}, all day` : `${localDay(event.startDate)} to ${localDay(last)}, all day`
  }
  if (event.startsAt) {
    const start = new Date(event.startsAt)
    const end = event.endsAt ? new Date(event.endsAt) : null
    if (end === null) {
      return dateTimeFormatter.format(start)
    }
    return start.toDateString() === end.toDateString()
      ? `${dateTimeFormatter.format(start)} to ${timeFormatter.format(end)}`
      : `${dateTimeFormatter.format(start)} to ${dateTimeFormatter.format(end)}`
  }
  return ''
}

function titleOf(event: CalendarEventResponse): string {
  return event.title ?? 'Untitled event'
}

onMounted(async () => {
  try {
    offered.value = offeredAccess(await loadCapabilities())?.includes('CALENDAR_EVENTS') ?? false
  } catch {
    offered.value = false
  }
  if (props.startOpen && offered.value) {
    open.value = true
    await loadConnection()
  }
})

async function say(text: string): Promise<void> {
  notice.value = text
  await nextTick()
  noticeElement.value?.focus()
}

async function toggle(): Promise<void> {
  open.value = !open.value
  emit('used')
  // Asked again on every opening: the connection may have been changed on another page since.
  if (open.value && !busy.value) {
    error.value = null
    await loadConnection()
  }
}

/** A failure that means the connection is not what this control last saw: asked again, so it offers the right button. */
async function recheckAfter(failure: unknown): Promise<void> {
  const code = failure instanceof ApiRequestError ? failure.problem?.code : undefined
  if (code === 'CONNECTION_RECONNECT_REQUIRED' || code === 'CONNECTION_NOT_FOUND') {
    await loadConnection()
    if (connection.value?.state !== 'ACTIVE') {
      // Events this connection can no longer copy are not left on offer.
      listing.value = null
      listState.value = 'idle'
    }
  }
}

async function loadConnection(): Promise<void> {
  connectionState.value = 'loading'
  try {
    connection.value = latestConnection(await listConnections(props.workspaceId), 'CALENDAR_EVENTS')
    connectionState.value = 'loaded'
  } catch (failure) {
    connectionState.value = 'error'
    error.value =
      describeConnectorFailure(failure, 'connected accounts') ?? 'Brownie could not check your Google Calendar connection. Try again.'
  }
}

async function connect(): Promise<void> {
  if (busy.value) {
    return
  }
  emit('used')
  if (props.unsavedWork) {
    error.value = "This document has changes that are not saved yet. Connecting takes you to Google's page, so connect once they are saved."
    return
  }
  busy.value = true
  error.value = null
  try {
    const started = await startGoogleConsent(props.workspaceId, 'CALENDAR_EVENTS', `/documents/${props.documentId}`)
    // Google's page, then back to this document; the button works again if Back brings this page back.
    navigateTo(started.authorizationUrl)
    releaseIfStillHere(() => {
      busy.value = false
    })
  } catch (failure) {
    error.value = `Could not start connecting Google Calendar. ${
      describeConnectorFailure(failure, 'a way to connect Google accounts') ?? 'Try again.'
    }`
    busy.value = false
  }
}

async function showEvents(): Promise<void> {
  if (busy.value) {
    return
  }
  emit('used')
  busy.value = true
  error.value = null
  notice.value = null
  listAnnouncement.value = ''
  listState.value = 'loading'
  listedChoice.value = choice.value
  const { from, to } = calendarWindow(choice.value, new Date())
  try {
    listing.value = await listCalendarEvents(props.workspaceId, from.toISOString(), to.toISOString())
    listState.value = 'loaded'
    const count = listing.value.events.length
    listAnnouncement.value =
      count === 0 ? NO_EVENTS : `${count} ${count === 1 ? 'event' : 'events'} listed${listing.value.truncated ? ', and there are more' : ''}.`
  } catch (failure) {
    listState.value = 'error'
    listing.value = null
    error.value = describeConnectorFailure(failure, 'a way to read Google Calendar') ?? 'Brownie could not list your events. Try again.'
    await recheckAfter(failure)
  } finally {
    busy.value = false
  }
}

const NO_EVENTS = 'No ordinary events on your main calendar in those days.'


function importFailure(failure: unknown, event: CalendarEventResponse): string {
  if (failure instanceof ApiRequestError && !failure.routeMissing) {
    switch (failure.problem?.code) {
      case 'CONNECTOR_RESOURCE_UNAVAILABLE':
      case 'CONNECTOR_RESOURCE_UNSUPPORTED':
        return failure.problem.detail ?? `"${titleOf(event)}" cannot be copied.`
      case 'CONNECTOR_RESOURCE_REFUSED':
        return `"${titleOf(event)}" was not copied: Brownie's checks refused it.`
      case 'CONNECTOR_RESOURCE_TOO_LARGE':
        return `"${titleOf(event)}" is larger than Brownie copies from Google Calendar, so it was not copied.`
      case 'NOT_FOUND':
        return 'This document is no longer available, so nothing was copied.'
      default:
        break
    }
  }
  return `"${titleOf(event)}" was not copied. ${describeConnectorFailure(failure, 'a way to copy calendar events') ?? 'Try again.'}`
}

async function copy(event: CalendarEventResponse): Promise<void> {
  if (busy.value) {
    return
  }
  emit('used')
  busy.value = true
  importingId.value = event.id
  error.value = null
  notice.value = null
  let copied = false
  try {
    const result = await props.copyEvent(event.id)
    copied = true
    await say(
      result.newCopy
        ? `Copied "${titleOf(event)}" into this document's sources. Its times are as planned in the calendar.`
        : `"${titleOf(event)}" had already been copied as it is now, so this document uses that copy.`,
    )
  } catch (failure) {
    error.value = importFailure(failure, event)
    await recheckAfter(failure)
  } finally {
    busy.value = false
    importingId.value = null
  }
  if (!copied) {
    await nextTick()
    ;(window.document.getElementById(`calendar-copy-${event.id}`) ?? window.document.getElementById('calendar-connect'))?.focus()
  }
}
</script>

<template>
  <div v-if="offered" class="calendar-picker">
    <button
      type="button"
      class="button button--secondary"
      :aria-expanded="open"
      aria-controls="calendar-picker-panel"
      @click="toggle"
    >
      Copy an event from Google Calendar
    </button>

    <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ listAnnouncement }}</p>
    <div v-show="open" id="calendar-picker-panel" class="calendar-picker__panel">
      <p v-if="notice" ref="noticeElement" class="calendar-picker__notice" role="status" tabindex="-1">{{ notice }}</p>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>

      <p v-if="connectionState === 'loading'" class="field-hint" aria-live="polite">Checking your Google Calendar connection…</p>

      <template v-else-if="connectionState === 'loaded'">
        <template v-if="connection === null || connection.state !== 'ACTIVE'">
          <p class="field-hint">
            {{
              connection === null
                ? 'Google Calendar is not connected.'
                : stateSentence(connection)
            }}
            Connecting asks Google to let Brownie see the events on calendars you own; Brownie then reads only the days you
            ask for and copies only the event you choose.
            <RouterLink to="/connections">More about connections</RouterLink>
          </p>
          <button id="calendar-connect" type="button" class="button button--primary" :disabled="busy" @click="connect">
            {{ connection?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Calendar again' : 'Connect Google Calendar' }}
          </button>
        </template>

        <form v-else class="calendar-picker__window" @submit.prevent="showEvents">
          <label class="field-label" for="calendar-window">Which days</label>
          <select id="calendar-window" v-model="choice">
            <option v-for="option in WINDOW_CHOICES" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <!-- Not disabled while reading: that would drop the focus it holds. A second press is ignored instead. -->
          <button type="submit" class="button button--secondary" :aria-disabled="busy">Show events</button>
        </form>
      </template>

      <p v-if="listState === 'loading'" class="field-hint" aria-live="polite">Reading your calendar…</p>
      <template v-else-if="listState === 'loaded' && listing">
        <p v-if="listing.events.length === 0" class="field-hint">{{ NO_EVENTS }}</p>
        <template v-else>
          <p class="field-hint">
            Times as planned in your calendar, shown in your own time zone. Only ordinary events are listed, one occurrence at
            a time for repeating ones.
          </p>
          <ul class="calendar-picker__events">
            <li v-for="event in listing.events" :key="event.id" class="calendar-picker__event">
              <div class="calendar-picker__text">
                <span class="calendar-picker__title">{{ titleOf(event) }}</span>
                <span class="field-hint">
                  {{ when(event) }}<template v-if="event.status === 'TENTATIVE'"> · tentative</template
                  ><template v-if="event.recurring"> · repeats</template>
                </span>
              </div>
              <button
                :id="`calendar-copy-${event.id}`"
                type="button"
                class="button button--secondary"
                :disabled="busy"
                @click="copy(event)"
              >
                <!-- The space lives outside the hidden span: inside it, the compiler trims it and the name runs together. -->
                <span>{{ importingId === event.id ? 'Copying…' : 'Copy' }} <span class="visually-hidden">{{ titleOf(event) }}</span></span>
              </button>
            </li>
          </ul>
          <p v-if="listing.truncated" class="field-hint">
            There are more events in those days than Brownie lists at once, so only the earliest
            {{ listing.events.length }} are shown.<template v-if="listedChoice === 'last30'">
              Choose the last 7 days to see the most recent ones.</template
            >
          </p>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.calendar-picker {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  margin-block: var(--space-3);
}

.calendar-picker__panel {
  inline-size: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.calendar-picker__panel p {
  margin: 0;
}

.calendar-picker__notice {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

.calendar-picker__window {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-2);
}

.calendar-picker__window .field-label {
  flex-basis: 100%;
}

.calendar-picker__events {
  list-style: none;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.calendar-picker__event {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.calendar-picker__text {
  display: flex;
  flex-direction: column;
  min-inline-size: 0;
  flex: 1 1 12rem;
}

.calendar-picker__title {
  font-weight: 500;
  overflow-wrap: anywhere;
}
</style>
