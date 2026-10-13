<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
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
 * control lives in a panel the person can close, and a copy that finishes
 * after it was closed must still reach the document's list of sources.
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
/** The row's name, spoken as the button's name and drawn as its title, so the two cannot drift apart. */
const TITLE = 'Copy an event from Google Calendar'

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
  <div v-if="offered" class="calendar-picker" :class="{ 'calendar-picker--open': open }">
    <!--
      The whole row is the button. Its name is the title alone, as it is drawn; the sentence under the title is what
      the button says it does, read after the name rather than run into it.
    -->
    <button
      type="button"
      class="calendar-picker__toggle"
      :aria-label="TITLE"
      aria-describedby="calendar-picker-about"
      :aria-expanded="open"
      aria-controls="calendar-picker-panel"
      @click="toggle"
    >
      <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="calendar" :size="18" /></span>
      <span class="calendar-picker__words">
        <span class="calendar-picker__title">{{ TITLE }}</span>
        <span id="calendar-picker-about" class="calendar-picker__about"
          >Brownie lists the days you ask for and copies only the event you choose.</span
        >
      </span>
      <AppIcon class="calendar-picker__chevron" :name="open ? 'chevron-down' : 'chevron-right'" :size="18" />
    </button>

    <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ listAnnouncement }}</p>
    <div v-show="open" id="calendar-picker-panel" class="calendar-picker__panel">
      <p v-if="notice" ref="noticeElement" class="calendar-picker__notice" role="status" tabindex="-1">
        <AppIcon name="check-circle" :size="18" />
        <span>{{ notice }}</span>
      </p>
      <p v-if="error" class="calendar-picker__problem field-error" role="alert">
        <AppIcon name="alert-circle" :size="16" />
        <span>{{ error }}</span>
      </p>

      <p v-if="connectionState === 'loading'" class="calendar-picker__progress field-hint" aria-live="polite">
        <span class="activity-indicator" aria-hidden="true"></span>Checking your Google Calendar connection…
      </p>

      <template v-else-if="connectionState === 'loaded'">
        <div v-if="connection === null || connection.state !== 'ACTIVE'" class="msg-card calendar-picker__card">
          <div class="msg-card__section calendar-picker__connect">
            <p>{{ connection === null ? 'Google Calendar is not connected.' : stateSentence(connection) }}</p>
            <p class="field-hint">
              Connecting asks Google to let Brownie see the events on calendars you own; Brownie then reads only the days you
              ask for and copies only the event you choose.
              <RouterLink to="/connections">More about connections</RouterLink>
            </p>
            <button id="calendar-connect" type="button" class="button button--primary button--small" :disabled="busy" @click="connect">
              {{ connection?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Calendar again' : 'Connect Google Calendar' }}
            </button>
          </div>
        </div>

        <form v-else class="calendar-picker__window" @submit.prevent="showEvents">
          <label class="field-label" for="calendar-window">Which days</label>
          <select id="calendar-window" v-model="choice" class="text-field">
            <option v-for="option in WINDOW_CHOICES" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <!-- Not disabled while reading: that would drop the focus it holds. A second press is ignored instead. -->
          <button type="submit" class="button button--secondary" :aria-disabled="busy">Show events</button>
        </form>
      </template>

      <p v-if="listState === 'loading'" class="calendar-picker__progress field-hint" aria-live="polite">
        <span class="activity-indicator" aria-hidden="true"></span>Reading your calendar…
      </p>
      <template v-else-if="listState === 'loaded' && listing">
        <p v-if="listing.events.length === 0" class="field-hint">{{ NO_EVENTS }}</p>
        <template v-else>
          <p class="field-hint">
            Times as planned in your calendar, shown in your own time zone. Only ordinary events are listed, one occurrence at
            a time for repeating ones.
          </p>
          <ul class="calendar-picker__events">
            <li v-for="event in listing.events" :key="event.id" class="source-chip calendar-picker__event">
              <span class="source-chip__text">
                <span class="source-chip__name">{{ titleOf(event) }}</span>
                <span class="source-chip__meta">
                  {{ when(event) }}<template v-if="event.status === 'TENTATIVE'"> · tentative</template
                  ><template v-if="event.recurring"> · repeats</template>
                </span>
              </span>
              <button
                :id="`calendar-copy-${event.id}`"
                type="button"
                class="button button--secondary button--small"
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
/*
 * One row of the Add a source panel: a tile, what the row does, and a chevron. Opening it gives the row the quiet inset
 * shade and shows its content underneath, lined up with the words and clear of the tile.
 */
.calendar-picker {
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
}

/* Two open rows would run into one shade, so an open row keeps a little white between it and its neighbours. */
.calendar-picker--open {
  margin-block: 0.25rem;
  background: var(--color-paper-soft);
}

/* The edge is transparent so a forced-colours display can still draw it; the padding is a pixel less to make room. */
.calendar-picker__toggle {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  inline-size: 100%;
  padding-block: calc(0.75rem - 1px) calc(0.625rem - 1px);
  padding-inline: calc(0.5625rem - 1px) calc(0.75rem - 1px);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  text-align: start;
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

/* An open row already wears the shade, so only a closed one answers the pointer with it. */
.calendar-picker__toggle[aria-expanded='false']:hover {
  background: var(--color-paper-soft);
}

.calendar-picker__words {
  flex: 1 1 auto;
  display: grid;
  gap: 0.125rem;
  min-inline-size: 0;
}

.calendar-picker__title {
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.calendar-picker__about {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.35;
}

.calendar-picker__chevron {
  flex: none;
  color: var(--color-text-muted);
}

.calendar-picker__panel {
  display: grid;
  justify-items: start;
  gap: 0.625rem;
  padding-block: 0.125rem 0.75rem;
  padding-inline: 3.1875rem 0.75rem;
}

.calendar-picker__panel > * {
  min-inline-size: 0;
}

.calendar-picker__panel p {
  margin: 0;
}

/* What was just done, said where the keyboard lands. */
.calendar-picker__notice {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  inline-size: 100%;
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-control);
  background: var(--color-success-wash);
  line-height: 1.4;
}

.calendar-picker__notice > svg {
  margin-block-start: 0.0625rem;
  color: var(--color-success);
}

.calendar-picker__problem,
.calendar-picker__progress {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}

.calendar-picker__problem > svg {
  margin-block-start: 0.0625rem;
}

.calendar-picker__progress {
  align-items: center;
}

/* Not connected, or waiting to be connected again: a quiet card with what connecting means and the way to do it. */
.calendar-picker__card {
  inline-size: 100%;
}

.calendar-picker__connect {
  display: grid;
  justify-items: start;
  gap: 0.5rem;
}

.calendar-picker__connect p {
  line-height: 1.45;
}

.calendar-picker__connect .button {
  margin-block-start: 0.25rem;
}

/* The days and the button share a line; on a panel too narrow for both, the button drops below the field. */
.calendar-picker__window {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  inline-size: 100%;
}

.calendar-picker__window .field-label {
  flex-basis: 100%;
  margin: 0;
}

.calendar-picker__window .text-field {
  flex: 1 1 14rem;
  min-inline-size: 0;
}

.calendar-picker__events {
  display: grid;
  gap: 0.5rem;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* An event's title and times wrap instead of being cut short, and the button drops below them on a narrow panel. */
.calendar-picker__event {
  flex-wrap: wrap;
  justify-content: space-between;
  inline-size: 100%;
}

.calendar-picker__event .source-chip__text {
  flex: 1 1 12rem;
}

.calendar-picker__event .source-chip__name,
.calendar-picker__event .source-chip__meta {
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
