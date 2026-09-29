<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { proposeCalendarEvent, type ActionResponse } from '@/api/client'
import { stateSentence } from '@/connections/words'
import { actionLink, exactTimeOf, mayAcknowledge, timeOf, withdrawable } from '@/actions/words'
import {
  CALENDAR_EVENT_WORDS,
  calendarEventPreview,
  calendarEventSentence,
  startsAt,
  whenSentence,
  type CalendarEventPreview,
} from '@/actions/calendarWords'
import { useActionList } from '@/actions/useActionList'

/**
 * Adding one event, typed here and prefilled from the document, to the
 * person's own main calendar: not repeating, with no guests, telling nobody.
 * Preparing it shows exactly what approving would add, read from the payload
 * the approval covers, with its time in its own time zone; nothing is added
 * until the person approves that. An event whose outcome Brownie cannot know
 * is never sent again: it stays on the list, with the way to ask Google,
 * until the person says they have looked for themselves.
 */
const props = defineProps<{
  workspaceId: number
  documentId: number
  /** The document's title, offered as the event's. */
  documentTitle: string
  /** A date the document holds, offered as the event's day; null when it holds none. */
  suggestedDate: string | null
  /** Changes not saved yet: connecting leaves the page, so it waits for them rather than have the browser ask. */
  unsavedWork: boolean
}>()

const TYPES = ['CALENDAR_CREATE_EVENT'] as const

const {
  offerUnknown,
  loadState,
  connection,
  actions,
  unread,
  busy,
  error,
  typesOffered: addingOffered,
  shown,
  visible,
  reconnectOffered,
  load,
  upsert,
  touch,
  focusOn,
  connect: connectFor,
  approve,
  ask,
  settle,
  approvable,
  noLongerOffered,
  checkAgain,
  proposalFailed,
} = useActionList({
  workspaceId: () => props.workspaceId,
  documentId: () => props.documentId,
  types: TYPES,
  kind: CALENDAR_EVENT_WORDS,
  thing: 'event',
  outcome: 'the event was added',
  lookIn: 'your calendar',
  what: 'a way to add events to Google Calendar',
  heading: 'calendar-event-heading',
  itemPrefix: 'calendar-event-',
})

/** The viewer's own time zone, the one a person most likely means; any other can be chosen. */
function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function zoneNames(): string[] {
  try {
    const names = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf?.('timeZone') ?? []
    return names.includes('UTC') ? names : ['UTC', ...names]
  } catch {
    return []
  }
}

const title = ref(props.documentTitle)
const allDay = ref(false)
const timeZone = ref(browserZone())
/** Every zone the browser knows, with the one chosen always among them so the list never shows a blank for it. */
const ZONES = (() => {
  const names = zoneNames()
  return names.length === 0 || names.includes(timeZone.value) ? names : [timeZone.value, ...names]
})()
const start = ref(props.suggestedDate ? `${props.suggestedDate}T09:00` : '')
const end = ref(props.suggestedDate ? `${props.suggestedDate}T10:00` : '')
const firstDay = ref(props.suggestedDate ?? '')
const lastDay = ref(props.suggestedDate ?? '')
const location = ref('')
const description = ref('')

const previews = computed(() => new Map(actions.value.map((action) => [action.id, calendarEventPreview(action)])))

function previewOf(action: ActionResponse): CalendarEventPreview | null {
  return previews.value.get(action.id) ?? null
}

onMounted(load)

function connect(): Promise<void> {
  return connectFor(props.unsavedWork, 'Google Calendar for adding events')
}

async function prepare(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  let prepared: ActionResponse | null = null
  try {
    prepared = await proposeCalendarEvent(props.workspaceId, {
      documentId: props.documentId,
      title: title.value,
      description: description.value,
      location: location.value,
      allDay: allDay.value,
      timeZone: allDay.value ? null : timeZone.value,
      start: allDay.value ? firstDay.value : start.value,
      end: allDay.value ? lastDay.value : end.value,
    })
    touch(prepared.id)
    upsert(prepared)
  } catch (failure) {
    await proposalFailed(failure, {
      kind: CALENDAR_EVENT_WORDS,
      thing: 'event',
      outcome: 'the event was added',
      lookIn: 'your calendar',
      what: 'a way to add events to Google Calendar',
    })
  } finally {
    busy.value = false
  }
  await (prepared
    ? focusOn(`calendar-event-${prepared.id}`)
    : focusOn('calendar-event-prepare', 'calendar-event-connect', 'calendar-event-heading'))
}

/**
 * An event with the same title at the same time that was already added, or
 * may have been, so approving another would add it twice.
 */
function alreadyAdded(action: ActionResponse): ActionResponse | null {
  const preview = previewOf(action)
  if (preview === null || !withdrawable(action)) return null
  const same = JSON.stringify([preview.title, preview.when])
  return (
    actions.value.find((other) => {
      const theirs = previewOf(other)
      const added = other.state === 'SUCCEEDED' && other.verification !== 'REMOVED_AFTERWARDS'
      const madeUnlikeApproved = other.state === 'FAILED' && other.failure === 'READBACK_MISMATCH'
      return (
        other.id !== action.id &&
        (added || madeUnlikeApproved || (other.state === 'OUTCOME_UNKNOWN' && other.sent)) &&
        theirs !== null &&
        JSON.stringify([theirs.title, theirs.when]) === same
      )
    }) ?? null
  )
}

/** How an event is told apart from another of the same title: when it happens, and when it was prepared, to the second. */
function nameOf(action: ActionResponse): string {
  const preview = previewOf(action)
  const prepared = exactTimeOf(action.createdAt)
  const name = preview ? `${preview.title}, ${startsAt(preview.when)}` : 'this event'
  return `${name}${prepared ? `, prepared at ${prepared}` : ''}`
}

function whereSentence(preview: CalendarEventPreview): string {
  return `On the main calendar of ${preview.accountEmail ?? 'your Google account'}${preview.location ? `, at ${preview.location}` : ''}.`
}

const connectHint = computed(() =>
  connection.value === null ? 'Adding events to Google Calendar is not connected.' : stateSentence(connection.value),
)
</script>

<template>
  <section v-if="visible" class="calendar-event" aria-labelledby="calendar-event-heading">
    <h3 id="calendar-event-heading" class="calendar-event__heading" tabindex="-1">Add an event to Google Calendar</h3>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="offerUnknown" class="field-hint">
      Brownie could not check whether adding events to Google Calendar is offered here, so only earlier events are shown.
    </p>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Checking your Google Calendar connection…</p>
    <button v-else-if="loadState === 'error' || offerUnknown" type="button" class="button button--secondary" @click="checkAgain">
      Check again<span class="visually-hidden"> for adding events to Google Calendar</span>
    </button>

    <template v-else-if="loadState === 'loaded' && addingOffered">
      <template v-if="reconnectOffered">
        <p class="field-hint">
          {{ connectHint }}
          Adding events uses a Google connection of its own, which Brownie uses only for adding an event you approve.
          <RouterLink to="/connections">More about connections</RouterLink>
        </p>
        <button id="calendar-event-connect" type="button" class="button button--primary" :disabled="busy" @click="connect">
          {{
            connection?.state === 'RECONNECT_REQUIRED'
              ? 'Connect Google Calendar for adding events again'
              : 'Connect Google Calendar for adding events'
          }}
        </button>
      </template>

      <form v-else class="calendar-event__form" @submit.prevent="prepare">
        <label class="field-label" for="calendar-event-title">Title</label>
        <input id="calendar-event-title" v-model="title" type="text" maxlength="300" required />

        <label class="calendar-event__check">
          <input v-model="allDay" type="checkbox" />
          All day
        </label>

        <template v-if="allDay">
          <label class="field-label" for="calendar-event-first-day">First day</label>
          <input id="calendar-event-first-day" v-model="firstDay" type="date" required />
          <label class="field-label" for="calendar-event-last-day">Last day</label>
          <input id="calendar-event-last-day" v-model="lastDay" type="date" required />
        </template>
        <template v-else>
          <label class="field-label" for="calendar-event-start">Starts</label>
          <input id="calendar-event-start" v-model="start" type="datetime-local" required />
          <label class="field-label" for="calendar-event-end">Ends</label>
          <input id="calendar-event-end" v-model="end" type="datetime-local" required />
          <label class="field-label" for="calendar-event-zone">Time zone</label>
          <select v-if="ZONES.length > 0" id="calendar-event-zone" v-model="timeZone">
            <option v-for="zone in ZONES" :key="zone" :value="zone">{{ zone }}</option>
          </select>
          <input v-else id="calendar-event-zone" v-model="timeZone" type="text" />
        </template>

        <label class="field-label" for="calendar-event-location">Place (optional)</label>
        <input id="calendar-event-location" v-model="location" type="text" maxlength="300" />
        <label class="field-label" for="calendar-event-description">Description (optional)</label>
        <textarea id="calendar-event-description" v-model="description" rows="3" maxlength="4000"></textarea>

        <!-- Not disabled while preparing: that would drop the focus it holds. A second press is ignored instead. -->
        <button id="calendar-event-prepare" type="submit" class="button button--secondary" :aria-disabled="busy">
          Prepare the event
        </button>
        <p class="field-hint">
          You see exactly what would be added first. Nothing is added until you approve it, and the event has no guests, so
          nobody is told about it.
        </p>
      </form>
    </template>

    <ul v-if="shown.length > 0" class="calendar-event__list">
      <li v-for="action in shown" :key="action.id" class="calendar-event__item">
        <p :id="`calendar-event-${action.id}`" class="calendar-event__state" tabindex="-1">
          {{ calendarEventSentence(action, previewOf(action)) }}
        </p>
        <p v-if="timeOf(action.createdAt)" class="field-hint">Prepared at {{ timeOf(action.createdAt) }}.</p>

        <template v-if="action.state === 'AWAITING_APPROVAL'">
          <dl v-if="previewOf(action)" class="calendar-event__facts">
            <dt>What</dt>
            <dd>
              "{{ previewOf(action)!.title }}"
              <span v-if="previewOf(action)!.description" class="calendar-event__description">{{ previewOf(action)!.description }}</span>
            </dd>
            <dt>When</dt>
            <dd>{{ whenSentence(previewOf(action)!.when) }}</dd>
            <dt>Where</dt>
            <dd>{{ whereSentence(previewOf(action)!) }}</dd>
            <dt>Who is told</dt>
            <dd>Nobody. The event has no guests, and Google is told to send no invitation or email. It does not repeat and has no video call.</dd>
            <dt>Settings</dt>
            <dd>Shown as busy, private, with your calendar's usual reminders.</dd>
            <dt>Afterwards</dt>
            <dd>When you approve, Brownie sends it, then reads back only this event, to check it is what you approved.</dd>
          </dl>
          <p v-else class="field-hint">Brownie cannot show everything this event would be, so it is not offered for approval here.</p>
        </template>
        <p v-if="alreadyAdded(action)" class="field-hint">
          {{
            alreadyAdded(action)!.state === 'SUCCEEDED'
              ? 'You already added this event to your calendar. Approving this one adds it a second time.'
              : 'An earlier try may already have added this event. Look in your calendar before approving this one.'
          }}
        </p>

        <p v-if="actionLink(action)">
          <a :href="actionLink(action) ?? undefined" target="_blank" rel="noopener noreferrer"
            >Open in Google Calendar<span class="visually-hidden"> ({{ nameOf(action) }}, opens in a new tab)</span></a
          >
        </p>
        <p v-if="noLongerOffered(action)" class="field-hint">
          This Brownie no longer adds events to Google Calendar, so this can only be cancelled.
        </p>
        <p v-if="mayAcknowledge(action) && action.state !== 'OUTCOME_UNKNOWN'" class="field-hint">
          <template v-if="action.sent">
            That try stopped before Brownie heard how it ended. Check again to ask Google; if Brownie cannot ask, look for yourself
            and say so.
          </template>
          <template v-else>
            That try stopped before anything was sent to Google. Check again to try once more, or close it with the button below.
          </template>
        </p>
        <p v-if="unread.has(action.id)" class="field-hint">Brownie could not be asked where this event stands.</p>

        <div class="calendar-event__actions">
          <button
            v-if="approvable(action, previewOf(action) !== null)"
            type="button"
            class="button button--primary"
            :aria-disabled="busy"
            @click="approve(action)"
          >
            <span
              >{{ action.state === 'APPROVED' ? 'Try adding again' : 'Add to Google Calendar' }}
              <span class="visually-hidden">{{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="withdrawable(action)"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="settle(action, 'cancel')"
          >
            <span>Cancel <span class="visually-hidden">adding {{ nameOf(action) }}</span></span>
          </button>
          <button
            v-if="action.state === 'OUTCOME_UNKNOWN' || action.state === 'EXECUTING' || action.state === 'RECONCILING'"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="ask(action, 'reconcile')"
          >
            <span
              >{{ action.state === 'OUTCOME_UNKNOWN' ? 'Ask Google what happened' : 'Check again' }}
              <span class="visually-hidden">to {{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="mayAcknowledge(action)"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="settle(action, 'acknowledge')"
          >
            <span
              >{{ action.sent ? 'I looked in my calendar' : 'Close this try' }}
              <span class="visually-hidden">for {{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="unread.has(action.id)"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="ask(action, 'read')"
          >
            <span>Check again <span class="visually-hidden">where adding {{ nameOf(action) }} stands</span></span>
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.calendar-event {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  margin-block-start: var(--space-4);
}

.calendar-event p {
  margin: 0;
}

.calendar-event__heading {
  margin: 0;
  font-size: var(--font-size-base);
}

.calendar-event__form {
  inline-size: 100%;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: var(--space-1);
}

.calendar-event__form .button {
  align-self: flex-start;
  margin-block-start: var(--space-2);
}

.calendar-event__check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-block: var(--space-1);
}

.calendar-event__list {
  list-style: none;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.calendar-event__item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.calendar-event__state {
  overflow-wrap: anywhere;
}

.calendar-event__facts {
  margin: 0;
  display: grid;
  gap: var(--space-1);
}

.calendar-event__facts dt {
  font-weight: 500;
}

.calendar-event__facts dd {
  margin: 0 0 var(--space-1);
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.calendar-event__description {
  display: block;
  white-space: pre-wrap;
}

.calendar-event__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
</style>
