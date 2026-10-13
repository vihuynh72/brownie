<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
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
import { ADD_STANDING, connectionStanding, standingOf, type Standing } from '@/actions/standing'

/**
 * Adding one event, typed here and prefilled from the document, to the
 * person's own main calendar: not repeating, with no guests, telling nobody.
 * Preparing it shows exactly what approving would add, read from the payload
 * the approval covers, with its time in its own time zone; nothing is added
 * until the person approves that. An event whose outcome Brownie cannot know
 * is never sent again: it stays on the list, with the way to ask Google,
 * until the person says they have looked for themselves.
 *
 * It starts as one quiet row that opens into the form, so the export is not
 * crowded by a form most people will not need. Each event prepared is a card
 * of its own, always in view, with where it stands in a chip at the top.
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
/** Whether the row has opened into the form. What was typed is kept when it closes again. */
const expanded = ref(false)

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
    // The event is now a card of its own to decide on, so the form closes behind it.
    expanded.value = false
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

const longDay = new Intl.DateTimeFormat(undefined, { dateStyle: 'long', timeZone: 'UTC' })

/**
 * What the closed row says the form starts from: the document's title and the day it holds. The title itself is not
 * spelled out, since a long one would turn a row meant to be one line into a paragraph.
 */
const prefilled = computed(() => {
  const parsed = props.suggestedDate ? new Date(`${props.suggestedDate}T00:00:00Z`) : null
  const day = parsed !== null && !Number.isNaN(parsed.getTime()) ? longDay.format(parsed) : null
  return `Prefilled with this document’s title${day === null ? '' : ` and ${day}`}. No guests are invited.`
})

/** One card for each event on the list, with the chip that says where it stands. */
const cards = computed(() => shown.value.map((action) => ({ action, standing: standingOf(action, ADD_STANDING) })))

type Entry = 'loading' | 'check' | 'connect' | 'form'

/**
 * What the card for adding an event holds: waiting to hear what is offered, asking again after a failure,
 * connecting, or the row that opens into the form. Nothing where adding events is not offered, so it is not there.
 */
const entry = computed<Entry | null>(() => {
  if (loadState.value === 'loading') return 'loading'
  if (loadState.value === 'error' || offerUnknown.value) return 'check'
  if (loadState.value !== 'loaded' || !addingOffered.value) return null
  return reconnectOffered.value ? 'connect' : 'form'
})

const connectionChip = computed(() => connectionStanding(connection.value))

function edgeClass(standing: Standing | null): string {
  return standing?.edge === 'approval' ? 'save-card--approval' : standing?.edge === 'attention' ? 'save-card--attention' : ''
}
</script>

<template>
  <section v-if="visible" class="calendar-event" aria-labelledby="calendar-event-heading">
    <p v-if="error" class="save-alert" role="alert">
      <AppIcon name="alert-circle" :size="16" />
      <span>{{ error }}</span>
    </p>

    <ul v-if="cards.length > 0" class="calendar-event__list">
      <li v-for="({ action, standing }, index) in cards" :key="action.id" class="panel save-card" :class="edgeClass(standing)">
        <div class="save-card__head">
          <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="calendar-add" :size="18" /></span>
          <h3
            :id="index === 0 ? 'calendar-event-heading' : undefined"
            :tabindex="index === 0 ? -1 : undefined"
            class="save-card__title"
            >Add an event to Google Calendar</h3
          >
          <span v-if="standing" class="tag" :class="standing.tone">
            <AppIcon v-if="standing.icon" :name="standing.icon" :size="14" />
            {{ standing.label }}
          </span>
        </div>

        <div class="save-card__summary">
          <p :id="`calendar-event-${action.id}`" class="save-card__state" tabindex="-1">{{ calendarEventSentence(action, previewOf(action)) }}</p>
          <span v-if="timeOf(action.createdAt)" class="save-card__prepared">Prepared at {{ timeOf(action.createdAt) }}.</span>
        </div>

        <template v-if="action.state === 'AWAITING_APPROVAL'">
          <dl v-if="previewOf(action)" class="panel--quiet save-facts">
            <div class="save-facts__row">
              <dt>What</dt>
              <dd>
                "{{ previewOf(action)!.title }}"
                <span v-if="previewOf(action)!.description" class="calendar-event__description">{{ previewOf(action)!.description }}</span>
              </dd>
            </div>
            <div class="save-facts__row">
              <dt>When</dt>
              <dd>{{ whenSentence(previewOf(action)!.when) }}</dd>
            </div>
            <div class="save-facts__row">
              <dt>Where</dt>
              <dd>{{ whereSentence(previewOf(action)!) }}</dd>
            </div>
            <div class="save-facts__row">
              <dt>Who is told</dt>
              <dd>Nobody. The event has no guests, and Google is told to send no invitation or email. It does not repeat and has no video call.</dd>
            </div>
            <div class="save-facts__row">
              <dt>Settings</dt>
              <dd>Shown as busy, private, with your calendar's usual reminders.</dd>
            </div>
            <div class="save-facts__row">
              <dt>Afterwards</dt>
              <dd>When you approve, Brownie sends it, then reads back only this event, to check it is what you approved.</dd>
            </div>
          </dl>
          <p v-else class="save-note">Brownie cannot show everything this event would be, so it is not offered for approval here.</p>
        </template>
        <p v-if="alreadyAdded(action)" class="save-note save-note--warning">
          <AppIcon name="warning" :size="14" />
          <span>
            {{
              alreadyAdded(action)!.state === 'SUCCEEDED'
                ? 'You already added this event to your calendar. Approving this one adds it a second time.'
                : 'An earlier try may already have added this event. Look in your calendar before approving this one.'
            }}
          </span>
        </p>
        <p v-if="noLongerOffered(action)" class="save-note">
          This Brownie no longer adds events to Google Calendar, so this can only be cancelled.
        </p>
        <p v-if="mayAcknowledge(action) && action.state !== 'OUTCOME_UNKNOWN'" class="save-note">
          <template v-if="action.sent">
            That try stopped before Brownie heard how it ended. Check again to ask Google; if Brownie cannot ask, look for yourself
            and say so.
          </template>
          <template v-else>
            That try stopped before anything was sent to Google. Check again to try once more, or close it with the button below.
          </template>
        </p>
        <p v-if="unread.has(action.id)" class="save-note">Brownie could not be asked where this event stands.</p>

        <div class="save-card__actions">
          <a
            v-if="actionLink(action)"
            class="button button--secondary button--small"
            :href="actionLink(action) ?? undefined"
            target="_blank"
            rel="noopener noreferrer"
          >
            <AppIcon name="external" :size="16" />
            <span>Open in Google Calendar<span class="visually-hidden"> ({{ nameOf(action) }}, opens in a new tab)</span></span>
          </a>
          <button
            v-if="approvable(action, previewOf(action) !== null)"
            type="button"
            class="button button--primary button--small"
            :aria-disabled="busy"
            @click="approve(action)"
          >
            <AppIcon :name="action.state === 'APPROVED' ? 'refresh' : 'calendar-add'" :size="16" />
            <span
              >{{ action.state === 'APPROVED' ? 'Try adding again' : 'Add to Google Calendar' }}
              <span class="visually-hidden">{{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="withdrawable(action)"
            type="button"
            class="button button--quiet button--small"
            :aria-disabled="busy"
            @click="settle(action, 'cancel')"
          >
            <span>Cancel <span class="visually-hidden">adding {{ nameOf(action) }}</span></span>
          </button>
          <button
            v-if="action.state === 'OUTCOME_UNKNOWN' || action.state === 'EXECUTING' || action.state === 'RECONCILING'"
            type="button"
            class="button button--secondary button--small"
            :aria-disabled="busy"
            @click="ask(action, 'reconcile')"
          >
            <AppIcon name="refresh" :size="16" />
            <span
              >{{ action.state === 'OUTCOME_UNKNOWN' ? 'Ask Google what happened' : 'Check again' }}
              <span class="visually-hidden">to {{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="mayAcknowledge(action)"
            type="button"
            class="button button--secondary button--small"
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
            class="button button--secondary button--small"
            :aria-disabled="busy"
            @click="ask(action, 'read')"
          >
            <AppIcon name="refresh" :size="16" />
            <span>Check again <span class="visually-hidden">where adding {{ nameOf(action) }} stands</span></span>
          </button>
        </div>
      </li>
    </ul>

    <div
      v-if="entry !== null"
      class="panel save-card"
      :class="{ 'save-card--row': entry === 'form', 'save-card--attention': entry === 'connect' && connectionChip.edge === 'attention' }"
    >
      <template v-if="entry === 'form'">
        <!--
          The whole row presses: the button is the title, and its ::after stretches over the row, so the title stays the
          heading and the sentence under it stays outside the button's name, which it describes instead.
        -->
        <div class="calendar-event__row" :class="{ 'calendar-event__row--open': expanded }">
          <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="calendar-add" :size="18" /></span>
          <div class="calendar-event__text">
            <h3
              :id="cards.length === 0 ? 'calendar-event-heading' : undefined"
              :tabindex="cards.length === 0 ? -1 : undefined"
              class="calendar-event__name"
            >
              <button
                type="button"
                class="calendar-event__toggle"
                :aria-expanded="expanded"
                :aria-controls="expanded ? 'calendar-event-form' : undefined"
                aria-describedby="calendar-event-prefill"
                @click="expanded = !expanded"
              >
                Add an event to Google Calendar
              </button>
            </h3>
            <p id="calendar-event-prefill" class="calendar-event__prefill">{{ prefilled }}</p>
          </div>
          <AppIcon :name="expanded ? 'chevron-up' : 'chevron-down'" :size="18" />
        </div>

        <form v-if="expanded" id="calendar-event-form" class="calendar-event__form" @submit.prevent="prepare">
          <div class="calendar-event__field">
            <label class="field-label" for="calendar-event-title">Title</label>
            <input id="calendar-event-title" v-model="title" class="text-field" type="text" maxlength="300" required />
          </div>

          <label class="calendar-event__check">
            <input v-model="allDay" type="checkbox" />
            All day
          </label>

          <div v-if="allDay" class="calendar-event__pair">
            <div class="calendar-event__field">
              <label class="field-label" for="calendar-event-first-day">First day</label>
              <input id="calendar-event-first-day" v-model="firstDay" class="text-field" type="date" required />
            </div>
            <div class="calendar-event__field">
              <label class="field-label" for="calendar-event-last-day">Last day</label>
              <input id="calendar-event-last-day" v-model="lastDay" class="text-field" type="date" required />
            </div>
          </div>
          <template v-else>
            <div class="calendar-event__pair">
              <div class="calendar-event__field">
                <label class="field-label" for="calendar-event-start">Starts</label>
                <input id="calendar-event-start" v-model="start" class="text-field" type="datetime-local" required />
              </div>
              <div class="calendar-event__field">
                <label class="field-label" for="calendar-event-end">Ends</label>
                <input id="calendar-event-end" v-model="end" class="text-field" type="datetime-local" required />
              </div>
            </div>
            <div class="calendar-event__field">
              <label class="field-label" for="calendar-event-zone">Time zone</label>
              <select v-if="ZONES.length > 0" id="calendar-event-zone" v-model="timeZone" class="text-field">
                <option v-for="zone in ZONES" :key="zone" :value="zone">{{ zone }}</option>
              </select>
              <input v-else id="calendar-event-zone" v-model="timeZone" class="text-field" type="text" />
            </div>
          </template>

          <div class="calendar-event__field">
            <label class="field-label" for="calendar-event-location">Place (optional)</label>
            <input id="calendar-event-location" v-model="location" class="text-field" type="text" maxlength="300" />
          </div>
          <div class="calendar-event__field">
            <label class="field-label" for="calendar-event-description">Description (optional)</label>
            <textarea id="calendar-event-description" v-model="description" class="text-field" rows="3" maxlength="4000"></textarea>
          </div>

          <!-- Not disabled while preparing: that would drop the focus it holds. A second press is ignored instead. -->
          <button id="calendar-event-prepare" type="submit" class="button button--secondary button--small calendar-event__prepare" :aria-disabled="busy">
            <AppIcon name="calendar-add" :size="16" />
            Prepare the event
          </button>
          <p class="field-hint">
            You see exactly what would be added first. Nothing is added until you approve it, and the event has no guests, so
            nobody is told about it.
          </p>
        </form>
      </template>

      <template v-else>
        <div class="save-card__head">
          <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="calendar-add" :size="18" /></span>
          <h3
            :id="cards.length === 0 ? 'calendar-event-heading' : undefined"
            :tabindex="cards.length === 0 ? -1 : undefined"
            class="save-card__title"
            >Add an event to Google Calendar</h3
          >
          <span v-if="entry === 'connect'" class="tag" :class="connectionChip.tone">
            <AppIcon v-if="connectionChip.icon" :name="connectionChip.icon" :size="14" />
            {{ connectionChip.label }}
          </span>
        </div>

        <p v-if="entry === 'loading'" class="save-card__summary" aria-live="polite">Checking your Google Calendar connection…</p>

        <template v-else-if="entry === 'check'">
          <p v-if="offerUnknown" class="save-card__summary">
            Brownie could not check whether adding events to Google Calendar is offered here, so only earlier events are shown.
          </p>
          <div class="save-card__actions">
            <button type="button" class="button button--secondary button--small" @click="checkAgain">
              <AppIcon name="refresh" :size="16" />
              <span>Check again<span class="visually-hidden"> for adding events to Google Calendar</span></span>
            </button>
          </div>
        </template>

        <template v-else-if="entry === 'connect'">
          <p class="save-card__summary">{{ connectHint }}</p>
          <p class="save-note">
            <span>
              Adding events uses a Google connection of its own, which Brownie uses only for adding an event you approve.
              <RouterLink to="/connections">More about connections</RouterLink>
            </span>
          </p>
          <div class="save-card__actions">
            <button id="calendar-event-connect" type="button" class="button button--primary button--small" :disabled="busy" @click="connect">
              <AppIcon name="calendar-add" :size="16" />
              {{
                connection?.state === 'RECONNECT_REQUIRED'
                  ? 'Connect Google Calendar for adding events again'
                  : 'Connect Google Calendar for adding events'
              }}
            </button>
          </div>
        </template>
      </template>
    </div>
  </section>
</template>

<style scoped>
/* One card for each event and one for adding another, a rem apart as the other blocks of the dialog are. */
.calendar-event {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--space-4);
}

.calendar-event p {
  margin: 0;
}

.calendar-event__list {
  display: grid;
  gap: var(--space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

/*
 * An event or the way to add one, drawn as the cards of connected accounts are: a tile, what it is, a chip for where
 * it stands, a sentence, and the one thing to do. An event waiting for approval has the action colour for its edge, a
 * pixel and a half thick, and one that needs a look has honey, so either stands out from the plain hairline of the rest.
 */
.save-card {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1rem;
  /* The head, the table and the form lay themselves out by the card's width, which depends on the dialog and not on the window. */
  container-type: inline-size;
}

.save-card--approval {
  border-color: var(--color-cocoa);
  box-shadow: inset 0 0 0 0.5px var(--color-cocoa);
}

.save-card--attention {
  border-color: var(--color-honey);
  box-shadow: inset 0 0 0 0.5px var(--color-honey);
}

/* The tile, what it is and the chip on one line; a name that is long wraps inside its own column and never under the chip. */
.save-card__head {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2) 0.75rem;
}

.save-card__title {
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

/* The chip carries its icon a little smaller, with a little more air, than a chip that only labels. */
.save-card__head > .tag {
  justify-self: end;
  gap: 0.3125rem;
}

.save-card__head > .tag > svg {
  inline-size: 0.875rem;
  block-size: 0.875rem;
}

/* So little room that the name would be squeezed to a few letters: the chip goes on a line of its own, under the name. */
@container (max-width: 29rem) {
  .save-card__head {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .save-card__head > .tag {
    grid-column: 2;
    justify-self: start;
  }
}

/* The sentence and, run on after it in quieter type, when the event was prepared, so the pair costs no line of its own. */
.save-card__summary {
  font-size: var(--font-size-sm);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.save-card__state {
  display: inline;
}

.save-card__prepared {
  margin-inline-start: 0.25rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  /* All of it or none of it on the line, so a time is never split from the words before it. */
  white-space: nowrap;
}

/*
 * What approving adds, as label and value rows in an inset panel separated by hairlines. Narrow, the label sits above
 * its value; wide enough, the rows share two columns so the values line up, however long the longest label is.
 */
.save-facts {
  margin: 0;
  padding: 0;
}

.save-facts__row {
  display: grid;
  /* Only the space between a label and its value when they are stacked: side by side the table's own column gap holds them apart. */
  row-gap: var(--space-1);
  padding: 0.5625rem 0.75rem;
}

.save-facts__row + .save-facts__row {
  border-block-start: 1px solid var(--color-hairline);
}

.save-facts dt {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  /* Tighter than the value beside it, so the label's first line sits a little above the value's, as a caption does. */
  line-height: 1.2;
}

.save-facts dd {
  margin: 0;
  font-size: var(--font-size-xs);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

@container (min-width: 25rem) {
  .save-facts {
    display: grid;
    /* The first track also holds the row's own side padding, since a row takes its columns from here. */
    grid-template-columns: minmax(5.625rem, max-content) minmax(0, 1fr);
    column-gap: 0.75rem;
  }

  .save-facts__row {
    grid-template-columns: subgrid;
    grid-column: 1 / -1;
  }
}

.calendar-event__description {
  display: block;
  margin-block-start: var(--space-1);
  color: var(--color-text-muted);
  white-space: pre-wrap;
}

/* A line of small print about this event; the one that warns before a second copy is added has a honey panel and an icon. */
.save-note {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.45;
}

.save-note > svg {
  flex: none;
  margin-block-start: 0.0625rem;
}

.save-note--warning {
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-md);
  background: var(--color-honey-soft);
  color: var(--color-text);
}

.save-note--warning > svg {
  color: var(--color-honey-ink);
}

.save-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.625rem;
}

/* A card with nothing left to do has no buttons, and then no row for them either, so no gap is left under its last line. */
.save-card__actions:empty {
  display: none;
}

/* Something went wrong with a request: a wash of the error colour, an icon and the words in the main text colour, so it reads without the colour. */
.save-alert {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.625rem 0.75rem;
  border-radius: var(--radius-md);
  background: var(--color-error-wash);
  font-size: var(--font-size-sm);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.save-alert > svg {
  flex: none;
  margin-block-start: 0.125rem;
  color: var(--color-error);
}

/*
 * The closed row: the card with no padding of its own, so the row can be pressed to its edges and the form below it can
 * run edge to edge under a hairline.
 */
.save-card--row {
  gap: 0;
  padding: 0;
}

.calendar-event__row {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 0 0.75rem;
  min-block-size: 4rem;
  padding: 0.5rem 1rem;
  border-radius: calc(var(--radius-lg) - 1px);
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.calendar-event__row--open {
  border-end-start-radius: 0;
  border-end-end-radius: 0;
}

.calendar-event__row:hover {
  background: var(--color-paper-soft);
}

/* The row carries the focus ring, drawn inside it so it is not cut off by the dialog's edge. */
.calendar-event__row:has(.calendar-event__toggle:focus-visible) {
  box-shadow: inset 0 0 0 2px var(--color-focus);
}

.calendar-event__row > svg {
  color: var(--color-text-muted);
}

.calendar-event__text {
  display: grid;
  gap: 0.0625rem;
  min-inline-size: 0;
}

.calendar-event__name {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.calendar-event__toggle {
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

/* The button covers the whole row, whose own position holds it there. */
.calendar-event__toggle::after {
  content: '';
  position: absolute;
  inset: 0;
}

.calendar-event__toggle:focus-visible {
  border-radius: 0;
  box-shadow: none;
}

.calendar-event__prefill {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.45;
}

.calendar-event__form {
  display: grid;
  gap: 0.75rem;
  padding: 1rem;
  border-block-start: 1px solid var(--color-hairline);
}

.calendar-event__field {
  min-inline-size: 0;
}

/* Two fields that belong together side by side while there is room, one over the other when there is not. */
.calendar-event__pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.75rem;
}

@container (max-width: 30rem) {
  .calendar-event__pair {
    grid-template-columns: minmax(0, 1fr);
  }
}

.calendar-event__check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-block-size: 1.5rem;
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

.calendar-event__check input {
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0;
  accent-color: var(--color-cocoa);
}

.calendar-event__prepare {
  justify-self: start;
}
</style>
