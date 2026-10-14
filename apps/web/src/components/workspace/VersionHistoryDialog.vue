<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import {
  ApiRequestError,
  getDocumentRevision,
  listDocumentRevisions,
  listMcpGrants,
  listMcpToolCalls,
  restoreRevision,
  type DocumentRevisionResponse,
  type McpToolCallResponse,
} from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { mcpCallSentence } from '@/mcp/words'
import { formatDateLikeExport } from '@/workspace/layout'

/**
 * Every saved version of the document, newest first, with a way to compare
 * any of them with what the document holds now and to restore one. A
 * restore never rewrites history: the server copies the chosen version into
 * a new current version, so the version it replaces stays in this list and
 * can itself be restored. Locked fill spots keep their current values
 * through a restore, because a lock is the person's promise to themselves
 * that nothing changes that value without them unlocking it first.
 *
 * It is a modal dialog so the document behind it cannot change while the
 * person decides. The versions sit on the left and one is chosen at a time;
 * only the chosen one offers Compare and Restore, and everything on the right
 * (the comparison, and the question that guards a restore) is about that
 * version. The question sits there rather than in a second dialog on top of
 * this one.
 */
const props = defineProps<{
  workspaceId: number
  documentId: number
  /** What the document holds now: the version a restore replaces and every comparison is made against. */
  currentRevision: DocumentRevisionResponse
  /** The words the page shows for a field. */
  fieldLabel: (fieldId: string) => string
  /** The editor holds changes the server does not have yet; a restore would silently drop them, so it waits. */
  unsavedWork: boolean
}>()

const emit = defineEmits<{
  /** A restore made a new current version; the ids are the locked fields that kept their current values. */
  restored: [payload: { revision: DocumentRevisionResponse; keptLockedFieldIds: string[] }]
  closed: []
  /**
   * The server refused a restore because the document moved on since the page last loaded it. The page has to
   * reload the document: until it does, every restore is made against a version that is no longer current and
   * is refused the same way.
   */
  'document-changed': []
}>()

const dialog = ref<HTMLDialogElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const isOpen = ref(false)
/** Whether the browser drew the dialog as a real modal; without one it is laid over the page by hand. */
const nativeModal = ref(false)
/** Each opening gets a number, so a request that finishes after the dialog closed cannot act on the next opening. */
let openingNumber = 0
let opener: HTMLElement | null = null

/** The one polite live region inside the dialog; the page's own region is out of reach while the dialog is modal. */
const status = ref('')

function open(): void {
  if (isOpen.value) return
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  openingNumber += 1
  isOpen.value = true
  void nextTick(() => {
    const element = dialog.value
    if (element === null) return
    if (typeof element.showModal === 'function') {
      nativeModal.value = true
      element.showModal()
    } else {
      nativeModal.value = false
      element.setAttribute('open', '')
      closeButton.value?.focus()
    }
  })
  void loadVersions()
}

function close(): void {
  if (!isOpen.value) return
  isOpen.value = false
  const element = dialog.value
  if (element !== null) {
    if (nativeModal.value && typeof element.close === 'function' && element.open) element.close()
    element.removeAttribute('open')
  }
  resetState()
  const returnTo = opener
  opener = null
  void nextTick(() => {
    if (returnTo !== null && returnTo.isConnected) returnTo.focus()
  })
  emit('closed')
}

/**
 * The browser closed the dialog by itself: it lets a page refuse Escape only once without the person doing
 * anything else in between, and then closes the dialog regardless. Everything else closes it through close().
 */
function onNativeClose(): void {
  // A dialog opened again straight after close() is open once more by the time that close's event arrives.
  if (isOpen.value && dialog.value?.open !== true) close()
}

function onFallbackKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || nativeModal.value) return
  event.preventDefault()
  close()
}

function resetState(): void {
  listToken += 1
  versions.value = []
  mcpCalls.value = []
  grantNames.value = new Map()
  listState.value = 'idle'
  listError.value = null
  selectedRevisionId.value = null
  showSame.value = false
  forgetDetail()
  restoreStage.value = 'idle'
}

defineExpose({ open, close })

// ---- The list of versions ------------------------------------------------------------------

const versions = ref<DocumentRevisionResponse[]>([])
/** The calls chat apps made against this document, and their grants' names; empty on a deployment without them. */
const mcpCalls = ref<McpToolCallResponse[]>([])
const grantNames = ref<ReadonlyMap<number, string>>(new Map())
type ListState = 'idle' | 'loading' | 'loaded' | 'error'
const listState = ref<ListState>('idle')
const listError = ref<string | null>(null)
/** Whether asking again could help: not when the server is older than this page or the session has ended. */
const listRetryable = ref(false)
let listToken = 0

/** Newest first. The server lists them oldest first, and the version number is what orders them for the person. */
const orderedVersions = computed<DocumentRevisionResponse[]>(() =>
  [...versions.value].sort((a, b) => b.revisionNumber - a.revisionNumber),
)

/**
 * One line in the list: a version, or a call a chat app made against the
 * document. Both are ordered by when they happened, newest first, so the
 * calls sit between the versions the way they actually occurred.
 */
type ListItem =
  | { kind: 'version'; at: string; revision: DocumentRevisionResponse }
  | { kind: 'call'; at: string; call: McpToolCallResponse }

const timelineItems = computed<ListItem[]>(() =>
  [
    ...orderedVersions.value.map((revision) => ({ kind: 'version' as const, at: revision.createdAt, revision })),
    ...mcpCalls.value.map((call) => ({ kind: 'call' as const, at: call.at, call })),
  ].sort((a, b) => Date.parse(b.at) - Date.parse(a.at)),
)

function grantNameFor(call: McpToolCallResponse): string | null {
  return grantNames.value.get(call.grantId) ?? null
}

async function loadVersions(): Promise<void> {
  const token = ++listToken
  // A reload keeps the list it already shows, so the rows (and the focus inside one) do not vanish and return.
  const firstLoad = versions.value.length === 0
  if (firstLoad) {
    listState.value = 'loading'
    status.value = 'Loading version history…'
  }
  listError.value = null
  try {
    const loaded = await listDocumentRevisions(props.workspaceId, props.documentId)
    if (token !== listToken) return
    versions.value = loaded
    // The calls chat apps made sit in this list beside the versions; the grants' names are what the lines read
    // with. A deployment without MCP, an older server without the routes, or a failure answering either must not
    // take the versions with it -- the calls simply do not appear.
    try {
      const [calls, grants] = await Promise.all([
        listMcpToolCalls(props.workspaceId, props.documentId),
        listMcpGrants(props.workspaceId),
      ])
      if (token !== listToken) return
      mcpCalls.value = calls
      grantNames.value = new Map(grants.map((grant) => [grant.id, grant.name]))
    } catch {
      if (token !== listToken) return
      mcpCalls.value = []
      grantNames.value = new Map()
    }
    listState.value = 'loaded'
    if (firstLoad) status.value = ''
    // It opens on the version just before this one, the likeliest to be wanted back, rather than on nothing.
    if (!loaded.some((revision) => revision.id === selectedRevisionId.value)) {
      forgetDetail()
      selectedRevisionId.value = startingVersion()?.id ?? null
    }
  } catch (error) {
    if (token !== listToken) return
    listState.value = 'error'
    const gone = brownieSaysNotThere(error)
    listRetryable.value = !gone && !(error instanceof ApiRequestError && (error.routeMissing || error.status === 401))
    listError.value =
      (gone ? 'This document is no longer available, for example because it was moved to the trash.' : null) ??
      describeCommonFailure(error, 'version history') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      "Could not load this document's versions."
    if (firstLoad) status.value = ''
  }
}

// A save the page finished while the dialog was open made a newer version than the list shows.
watch(
  () => props.currentRevision.id,
  () => {
    if (isOpen.value) void loadVersions()
  },
)

function isCurrent(revision: DocumentRevisionResponse): boolean {
  return revision.id === props.currentRevision.id
}

const timeFormatter = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' })
const monthDayFormatter = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' })
const fullDayFormatter = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' })

/** "Today, 3:42 PM", or the day instead of "Today" for an older version, with the year only when it is not this one. */
function whenAt(iso: string): string {
  const made = new Date(iso)
  if (Number.isNaN(made.getTime())) return ''
  const now = new Date()
  const sameDay =
    made.getFullYear() === now.getFullYear() && made.getMonth() === now.getMonth() && made.getDate() === now.getDate()
  const day = sameDay
    ? 'Today'
    : made.getFullYear() === now.getFullYear()
      ? monthDayFormatter.format(made)
      : fullDayFormatter.format(made)
  return `${day}, ${timeFormatter.format(made)}`
}

// ---- Choosing a version --------------------------------------------------------------------

/** One name for every radio button of the list, so the browser treats the versions as a single choice. */
const choiceName = useId()
const selectedRevisionId = ref<number | null>(null)

const selectedRevision = computed<DocumentRevisionResponse | null>(
  () => orderedVersions.value.find((revision) => revision.id === selectedRevisionId.value) ?? null,
)

/** The newest version that is not the current one; the current one itself when it is the only one. */
function startingVersion(): DocumentRevisionResponse | undefined {
  return orderedVersions.value.find((revision) => !isCurrent(revision)) ?? orderedVersions.value[0]
}

function choose(revision: DocumentRevisionResponse): void {
  // While a restore is under way the version it was asked for stays the chosen one.
  if (restoreStage.value === 'restoring' || selectedRevisionId.value === revision.id) return
  forgetDetail()
  selectedRevisionId.value = revision.id
}

/** Lets go of the comparison and the restore question: they were about a version that is no longer the chosen one. */
function forgetDetail(): void {
  compareToken += 1
  compareRevisionId.value = null
  compareStage.value = 'idle'
  compareError.value = null
  compareRevision.value = null
  confirmingRevisionId.value = null
  restoreError.value = null
  restoreKey = null
  status.value = ''
}

// ---- Comparing a version with now ----------------------------------------------------------

const compareRevisionId = ref<number | null>(null)
type CompareStage = 'idle' | 'loading' | 'loaded' | 'error'
const compareStage = ref<CompareStage>('idle')
const compareError = ref<string | null>(null)
const compareRevision = ref<DocumentRevisionResponse | null>(null)
/** The unchanged fill spots are listed too, once the person asks for them. */
const showSame = ref(false)
const compareHeadingId = useId()

// Stamps each call so an out-of-order response (an earlier compare that resolves after a newer one) can never
// overwrite the result of a more recent request: compareRevisionId and compareRevision are otherwise two
// independently written refs with no other guard tying them together.
let compareToken = 0

async function compareWithNow(revision: DocumentRevisionResponse): Promise<void> {
  if (compareStage.value === 'loading' && compareRevisionId.value === revision.id) return
  const token = ++compareToken
  compareRevisionId.value = revision.id
  compareStage.value = 'loading'
  compareError.value = null
  compareRevision.value = null
  status.value = `Loading version ${revision.revisionNumber}…`
  try {
    const loaded = await getDocumentRevision(props.workspaceId, props.documentId, revision.id)
    if (token !== compareToken) return
    compareRevision.value = loaded
    compareStage.value = 'loaded'
    status.value = `Showing version ${loaded.revisionNumber} compared with now. ${changedRows.value.length} changed, ${sameRows.value.length} the same.`
  } catch (error) {
    if (token !== compareToken) return
    compareStage.value = 'error'
    status.value = ''
    compareError.value =
      (brownieSaysNotThere(error) ? 'This document is no longer available, for example because it was moved to the trash.' : null) ??
      describeCommonFailure(error, 'version history') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Could not load that version.'
  }
}

type FieldValue = DocumentRevisionResponse['fields'][string]

/** A field's value as one line of text, or null when it holds nothing. Two versions are compared by this text. */
function fieldText(field: FieldValue | undefined): string | null {
  if (!field) return null
  const text = field.value ?? (field.values ?? []).join(', ')
  return text === '' ? null : text
}

/**
 * The same value the way the person reads it: dates as the export prints them, and each item of a repeated field
 * on a line of its own, since a date already holds a comma and a run of them would not read as a list.
 */
function shownText(field: FieldValue | undefined): string | null {
  if (fieldText(field) === null || field === undefined) return null
  const items = field.value != null ? [field.value] : (field.values ?? [])
  return (field.type === 'DATE' ? items.map(formatDateLikeExport) : items).join('\n')
}

interface CompareRow {
  fieldId: string
  label: string
  /** What the compared version held; null when it held nothing. */
  then: string | null
  now: string | null
  changed: boolean
}

const compareRows = computed<CompareRow[]>(() => {
  const older = compareRevision.value
  if (older === null) return []
  const ids = Array.from(new Set([...Object.keys(older.fields), ...Object.keys(props.currentRevision.fields)])).sort()
  return ids.map((fieldId) => ({
    fieldId,
    label: props.fieldLabel(fieldId),
    then: shownText(older.fields[fieldId]),
    now: shownText(props.currentRevision.fields[fieldId]),
    changed: fieldText(older.fields[fieldId]) !== fieldText(props.currentRevision.fields[fieldId]),
  }))
})
const changedRows = computed(() => compareRows.value.filter((row) => row.changed))
const sameRows = computed(() => compareRows.value.filter((row) => !row.changed))
/** What changed comes first, so asking for the rest only adds rows below and never moves the ones already read. */
const shownRows = computed(() => (showSame.value ? [...changedRows.value, ...sameRows.value] : changedRows.value))

const compareTitle = computed(() =>
  compareRevision.value === null
    ? ''
    : `Version ${compareRevision.value.revisionNumber} compared with now (version ${props.currentRevision.revisionNumber})`,
)

/**
 * Whether a restore would leave this field as it is now, which the server does for a locked field: a scalar
 * whose own state is locked, and every repeated field once any item of any of them is locked, because the rows
 * of a repeated region only make sense together.
 */
const keptFieldIds = computed<ReadonlySet<string>>(() => {
  const kept = new Set<string>()
  const repeatedIds: string[] = []
  let anyItemLocked = false
  for (const [fieldId, field] of Object.entries(props.currentRevision.fields)) {
    if (field.cardinality === 'REPEATED') {
      repeatedIds.push(fieldId)
      if ((field.itemFieldStates ?? []).some((state) => state?.lock === 'EXPLICITLY_LOCKED')) anyItemLocked = true
    } else if (field.fieldState?.lock === 'EXPLICITLY_LOCKED') {
      kept.add(fieldId)
    }
  }
  if (anyItemLocked) repeatedIds.forEach((fieldId) => kept.add(fieldId))
  return kept
})

/** "a", "a and b", "a, b and c". */
function listWords(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

/** Which of the changed fill spots a restore would leave as they are, said once under the table; null when none. */
const lockedNote = computed<string | null>(() => {
  const names = changedRows.value.filter((row) => keptFieldIds.value.has(row.fieldId)).map((row) => row.label)
  if (names.length === 0) return null
  if (names.length === 1) return `${names[0]} is locked, so a restore keeps its current value.`
  return `${listWords(names)} are locked, so a restore keeps their current values.`
})

// ---- Restoring a version -------------------------------------------------------------------

const confirmingRevisionId = ref<number | null>(null)
type RestoreStage = 'idle' | 'restoring'
const restoreStage = ref<RestoreStage>('idle')
const restoreError = ref<string | null>(null)

const confirmingRevision = computed<DocumentRevisionResponse | null>(
  () => orderedVersions.value.find((revision) => revision.id === confirmingRevisionId.value) ?? null,
)

/**
 * With no comparison on show to name them, the question says that locked fill spots are not touched, since the
 * person is about to decide without having seen which ones that would be.
 */
const showLockedHint = computed(() => compareStage.value !== 'loaded' && keptFieldIds.value.size > 0)

/**
 * The idempotency key of the restore being confirmed. Asking again for the same version against the same
 * current version reuses it, so a restore the server made but whose answer never arrived is answered with that
 * same new version rather than refused as out of date.
 */
let restoreKey: { revisionId: number; expectedRevisionId: number; key: string } | null = null

function restoreButtonId(revision: DocumentRevisionResponse): string {
  return `version-history-restore-${revision.id}`
}

function questionId(revision: DocumentRevisionResponse): string {
  return `version-history-restore-question-${revision.id}`
}

function askToRestore(revision: DocumentRevisionResponse): void {
  confirmingRevisionId.value = revision.id
  restoreStage.value = 'idle'
  restoreError.value = null
  // The Restore button this came from is replaced by the question, so the focus moves to the question itself.
  void nextTick(() => document.getElementById(questionId(revision))?.focus())
}

function keepCurrentVersion(revision: DocumentRevisionResponse): void {
  if (restoreStage.value === 'restoring') return
  confirmingRevisionId.value = null
  restoreError.value = null
  restoreKey = null
  void nextTick(() => document.getElementById(restoreButtonId(revision))?.focus())
}

const UNSAVED_WORK = 'Save or discard your changes on the page first, then restore.'

// The refusal only described the moment the person asked; once the page has saved, restoring can go ahead.
watch(
  () => props.unsavedWork,
  (unsaved) => {
    if (!unsaved && restoreError.value === UNSAVED_WORK) restoreError.value = null
  },
)

async function confirmRestore(revision: DocumentRevisionResponse): Promise<void> {
  if (restoreStage.value === 'restoring') return
  if (props.unsavedWork) {
    restoreError.value = UNSAVED_WORK
    return
  }
  const expectedRevisionId = props.currentRevision.id
  if (restoreKey === null || restoreKey.revisionId !== revision.id || restoreKey.expectedRevisionId !== expectedRevisionId) {
    restoreKey = { revisionId: revision.id, expectedRevisionId, key: crypto.randomUUID() }
  }
  const opening = openingNumber
  restoreStage.value = 'restoring'
  restoreError.value = null
  status.value = `Restoring version ${revision.revisionNumber}…`
  try {
    const response = await restoreRevision(
      props.workspaceId,
      props.documentId,
      revision.id,
      expectedRevisionId,
      restoreKey.key,
    )
    // The document changed on the server whether or not this dialog is still open, so the page always hears of it.
    emit('restored', { revision: response.revision, keptLockedFieldIds: response.keptLockedFieldIds ?? [] })
    if (opening === openingNumber && isOpen.value) close()
  } catch (error) {
    if (opening !== openingNumber || !isOpen.value) return
    restoreStage.value = 'idle'
    status.value = ''
    if (error instanceof ApiRequestError && error.status === 412) {
      restoreError.value = 'This document changed since this list was loaded, so nothing was restored.'
      emit('document-changed')
      void loadVersions()
      return
    }
    if (brownieSaysNotThere(error)) {
      restoreError.value = 'This document or that version is no longer available, so nothing was restored.'
      return
    }
    restoreError.value =
      describeCommonFailure(error, 'a way to restore a version') ?? 'Could not restore that version. Try again.'
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="dialog dialog--wide version-history"
    :class="{ 'version-history--fallback': !nativeModal }"
    aria-labelledby="version-history-heading"
    @cancel.prevent="close"
    @close="onNativeClose"
    @keydown="onFallbackKeydown"
  >
    <template v-if="isOpen">
      <header class="dialog__head dialog__head--ruled">
        <h2 id="version-history-heading" class="dialog__title">Version history</h2>
        <p class="dialog__subtitle">Every change makes a version. Restoring one keeps your current version in the history.</p>
        <button ref="closeButton" class="icon-button dialog__close" type="button" @click="close">
          <AppIcon name="close" :size="22" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <div class="dialog__body dialog__body--flush version-history__body">
        <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ status }}</p>

        <div class="version-history__versions">
          <p v-if="listState === 'loading'" class="field-hint version-history__note">Loading version history…</p>
          <p v-if="listError" class="field-error version-history__note" role="alert">{{ listError }}</p>
          <button
            v-if="listState === 'error' && listRetryable"
            class="button button--secondary button--small version-history__retry"
            type="button"
            @click="loadVersions"
          >
            Try again
          </button>

          <div v-if="timelineItems.length > 0" class="revision-list" role="radiogroup" aria-label="Versions">
            <template
              v-for="item in timelineItems"
              :key="item.kind === 'version' ? `version-${item.revision.id}` : `call-${item.call.id}`"
            >
              <div
                v-if="item.kind === 'version'"
                class="revision-row"
                :class="{ 'revision-row--selected': item.revision.id === selectedRevisionId }"
              >
                <label class="revision-row__choice">
                  <input
                    class="revision-row__radio visually-hidden"
                    type="radio"
                    :name="choiceName"
                    :checked="item.revision.id === selectedRevisionId"
                    :disabled="restoreStage === 'restoring' && item.revision.id !== selectedRevisionId"
                    @change="choose(item.revision)"
                  />
                  <span class="revision-row__line">
                    <span class="revision-row__name">Version {{ item.revision.revisionNumber }}</span>
                    <span v-if="isCurrent(item.revision)" class="tag tag--success">
                      <AppIcon name="check-circle" :size="16" />
                      Current version
                    </span>
                  </span>
                  <span class="revision-row__summary">{{ item.revision.editReason }}</span>
                  <time class="revision-row__time" :datetime="item.revision.createdAt">{{ whenAt(item.at) }}</time>
                </label>

                <!-- Gone while this row's own restore question is open or its restore is running, so Compare cannot overwrite the single status announcement meant for the restore. -->
                <div
                  v-if="item.revision.id === selectedRevisionId && confirmingRevisionId !== item.revision.id && restoreStage !== 'restoring'"
                  class="revision-row__actions"
                >
                  <button
                    class="button button--secondary button--small"
                    type="button"
                    :aria-label="`Compare version ${item.revision.revisionNumber} with the current version`"
                    :aria-disabled="compareStage === 'loading' && compareRevisionId === item.revision.id ? 'true' : undefined"
                    @click="compareWithNow(item.revision)"
                  >
                    <AppIcon name="compare" :size="16" />
                    Compare
                  </button>
                  <button
                    v-if="!isCurrent(item.revision) && confirmingRevisionId !== item.revision.id"
                    :id="restoreButtonId(item.revision)"
                    class="button button--secondary button--small"
                    type="button"
                    :aria-label="`Restore version ${item.revision.revisionNumber}`"
                    @click="askToRestore(item.revision)"
                  >
                    <AppIcon name="restore" :size="16" />
                    Restore
                  </button>
                </div>
              </div>

              <!-- A call a chat app made: a line in the same list, not a version -- nothing to choose, compare or restore. -->
              <div v-else class="revision-row revision-row--call">
                <span class="revision-row__call">
                  <AppIcon name="chat" :size="14" />
                  <span class="revision-row__call-text">{{ mcpCallSentence(item.call, grantNameFor(item.call)) }}</span>
                  <time class="revision-row__time" :datetime="item.call.at">{{ whenAt(item.at) }}</time>
                </span>
              </div>
            </template>
          </div>
          <p v-else-if="listState === 'loaded'" class="field-hint version-history__note">This document has no saved versions.</p>
          <p v-if="listState === 'loaded' && versions.length === 1" class="field-hint version-history__note">
            There is only one version, so there is nothing to restore.
          </p>
        </div>

        <div class="version-history__detail">
          <template v-if="selectedRevision">
            <p v-if="compareStage === 'idle'" class="version-history__hint">
              Choose Compare to see how version {{ selectedRevision.revisionNumber }} differs from now.
            </p>
            <p v-if="compareStage === 'loading'" class="field-hint">Loading version {{ selectedRevision.revisionNumber }}…</p>
            <p v-if="compareError" class="field-error" role="alert">{{ compareError }}</p>

            <template v-if="compareStage === 'loaded' && compareRevision">
              <div class="compare__head">
                <div>
                  <h3 :id="compareHeadingId" class="compare__title">{{ compareTitle }}</h3>
                  <p class="compare__counts">{{ changedRows.length }} changed · {{ sameRows.length }} the same</p>
                </div>
                <button
                  v-if="sameRows.length > 0"
                  class="button button--quiet button--small compare__toggle"
                  type="button"
                  :aria-pressed="showSame"
                  @click="showSame = !showSame"
                >
                  <AppIcon name="eye" :size="16" />
                  Show the same too
                </button>
              </div>

              <div v-if="shownRows.length > 0" class="compare-table" role="table" :aria-labelledby="compareHeadingId">
                <div class="compare-table__head" role="rowgroup">
                  <div class="compare-row compare-row--head" role="row">
                    <div class="compare-cell" role="columnheader">Fill spot</div>
                    <div class="compare-cell" role="columnheader">Version {{ compareRevision.revisionNumber }}</div>
                    <div class="compare-cell" role="columnheader">Now (version {{ currentRevision.revisionNumber }})</div>
                    <div class="compare-cell" role="columnheader"><span class="visually-hidden">Status</span></div>
                  </div>
                </div>
                <div role="rowgroup">
                  <div v-for="row in shownRows" :key="row.fieldId" class="compare-row" role="row">
                    <div class="compare-cell compare-cell--label" role="rowheader">{{ row.label }}</div>
                    <div
                      class="compare-cell compare-cell--then"
                      role="cell"
                      :data-label="`Version ${compareRevision.revisionNumber}`"
                    >
                      <template v-if="row.then !== null">{{ row.then }}</template>
                      <span v-else class="compare-cell__empty">Empty</span>
                    </div>
                    <div
                      class="compare-cell compare-cell--now"
                      role="cell"
                      :data-label="`Now (version ${currentRevision.revisionNumber})`"
                    >
                      <template v-if="row.now !== null">{{ row.now }}</template>
                      <span v-else class="compare-cell__empty">Empty</span>
                    </div>
                    <div class="compare-cell compare-cell--status" role="cell">
                      <span class="tag" :class="{ 'tag--honey': row.changed }">{{ row.changed ? 'Changed' : 'Same' }}</span>
                    </div>
                  </div>
                </div>
              </div>
              <p v-else class="version-history__hint">
                {{
                  compareRows.length === 0
                    ? 'Neither version has any values filled in.'
                    : 'Nothing is different: every fill spot is the same as now.'
                }}
              </p>

              <p v-if="lockedNote" class="compare__note">
                <AppIcon name="lock" :size="16" />
                {{ lockedNote }}
              </p>
            </template>

            <div
              v-if="confirmingRevision"
              class="restore-card"
              role="group"
              :aria-labelledby="questionId(confirmingRevision)"
            >
              <p :id="questionId(confirmingRevision)" class="restore-card__question" tabindex="-1">
                Restore version {{ confirmingRevision.revisionNumber }}? Your current version stays in the history.
              </p>
              <p v-if="showLockedHint" class="field-hint">Locked fill spots keep their current values.</p>
              <p v-if="restoreError" class="field-error" role="alert">{{ restoreError }}</p>
              <div class="restore-card__actions">
                <button
                  class="button button--primary button--small"
                  type="button"
                  :aria-disabled="restoreStage === 'restoring' ? 'true' : undefined"
                  @click="confirmRestore(confirmingRevision)"
                >
                  <AppIcon name="restore" :size="16" />
                  {{ restoreStage === 'restoring' ? 'Restoring…' : `Restore version ${confirmingRevision.revisionNumber}` }}
                </button>
                <button
                  class="button button--secondary button--small"
                  type="button"
                  :aria-disabled="restoreStage === 'restoring' ? 'true' : undefined"
                  @click="keepCurrentVersion(confirmingRevision)"
                >
                  Keep the current version
                </button>
              </div>
            </div>
          </template>
        </div>
      </div>
    </template>
  </dialog>
</template>

<style scoped>
.version-history {
  /* A fixed height, so the two sides do not jump about as a comparison loads or the list changes; a short window takes what it has. */
  block-size: 45rem;
  /* How the two sides share the width is decided by the dialog's own width, whatever size the window is. */
  container: version-history / inline-size;
}

.version-history[open] {
  animation: version-history-in var(--motion-base) var(--motion-ease);
}

@keyframes version-history-in {
  from {
    opacity: 0;
    transform: translateY(0.5rem);
  }
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.version-history--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
}

/* The title sits a little closer to the top edge than in the shared header, which gives the lists below it the room. */
.version-history .dialog__head {
  padding-block-start: 1.25rem;
}

.version-history .dialog__close {
  inset-block-start: 0.9375rem;
}

/* The versions on the left, what the chosen one differs on and the question about restoring it on the right. */
.version-history__body {
  display: grid;
  grid-template-columns: 22.5rem minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  overflow: hidden;
}

.version-history__versions,
.version-history__detail {
  min-inline-size: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: var(--color-scrollbar) transparent;
}

/* ---------- The versions ---------- */

.version-history__versions {
  padding: 0.9375rem 0.75rem 0.75rem;
  border-inline-end: 1px solid var(--color-hairline);
}

.version-history__note {
  margin: 0;
  padding-inline: 0.875rem;
}

.version-history__retry {
  margin: 0.5rem 0.875rem 0;
}

.revision-list {
  display: grid;
  gap: 0.25rem;
}

/* The chosen version sits on a cocoa wash; the rest are bare until the pointer is over one. */
.revision-row {
  position: relative;
  border-radius: var(--radius-md);
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.revision-row:hover {
  background: var(--color-paper-soft);
}

.revision-row--selected,
.revision-row--selected:hover {
  background: var(--color-cocoa-soft);
}

/* The radio button is the keyboard's way in; the card around it shows where the focus is. */
.revision-row:has(.revision-row__radio:focus-visible) {
  box-shadow: var(--focus-ring);
}

/* A browser that cannot ask whether a card holds the focus rings the words instead, which also shows after a click. */
@supports not selector(:has(*)) {
  .revision-row__choice:focus-within {
    box-shadow: var(--focus-ring);
  }
}

@media (forced-colors: active) {
  .revision-row:has(.revision-row__radio:focus-visible) {
    outline: 3px solid CanvasText;
    outline-offset: 2px;
  }
}

.revision-row__choice {
  display: grid;
  gap: 0.25rem;
  padding: 0.625rem 0.875rem 0.625rem;
  border-radius: var(--radius-md);
  cursor: pointer;
}

.revision-row__line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.revision-row__name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.43;
}

.revision-row__summary {
  font-size: var(--font-size-sm);
  line-height: 1.43;
  overflow-wrap: anywhere;
}

.revision-row__time {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.5;
}

.revision-row__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0 0.875rem 0.75rem;
}

/* A chat app's call sits between the versions it happened among: the same row shape, quieter, with nothing to press. */
.revision-row--call:hover {
  background: none;
}

.revision-row__call {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 0.125rem 0.5rem;
  align-items: center;
  padding: 0.5rem 0.875rem;
}

.revision-row__call > svg {
  color: var(--color-text-muted);
}

.revision-row__call-text {
  font-size: var(--font-size-sm);
  line-height: 1.43;
  overflow-wrap: anywhere;
}

.revision-row__call > .revision-row__time {
  grid-column: 2;
}

/* The buttons come straight after the words, so the chosen card takes up the space between them. */
.revision-row--selected .revision-row__choice {
  padding-block-end: 0.5rem;
}

/* ---------- The comparison ---------- */

.version-history__detail {
  container: version-detail / inline-size;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1.25rem 1.5rem 1.25rem;
}

/* The pane scrolls when its contents are taller than it; nothing in it gives way to fit. */
.version-history__detail > * {
  flex: none;
}

.version-history__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.5;
}

.compare__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem 0.75rem;
}

/* The words take the room the button leaves; where that is too little, the button goes under them. */
.compare__head > div {
  flex: 1 1 16rem;
  min-inline-size: 0;
}

.compare__title {
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.compare__counts {
  margin: 0.125rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.33;
}

.compare__toggle {
  flex: none;
}

.compare__toggle[aria-pressed='true'] {
  --button-bg: var(--color-cocoa-wash);
}

@media (forced-colors: active) {
  .compare__toggle[aria-pressed='true'] {
    outline: 2px solid ButtonText;
    outline-offset: -2px;
  }
}

/* The table is drawn from plain boxes so it can stack on a narrow window; the roles keep it a table to a screen reader. */
.compare-table {
  overflow: hidden;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.compare-row {
  display: grid;
  grid-template-columns: minmax(0, 126fr) minmax(0, 184fr) minmax(0, 184fr) 6rem;
  align-items: start;
}

.compare-row + .compare-row {
  border-block-start: 1px solid var(--color-hairline);
}

.compare-row--head {
  align-items: center;
  min-block-size: 2.4375rem;
  border-block-end: 1px solid var(--color-hairline);
  background: var(--color-paper-soft);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.2;
}

.compare-row--head .compare-cell {
  padding-block: 0;
}

.compare-cell {
  padding: 0.8125rem 0 0.6875rem 0.875rem;
  font-size: var(--font-size-sm);
  line-height: 1.43;
  overflow-wrap: anywhere;
  /* A line break the person typed stays one. */
  white-space: pre-line;
}

.compare-cell--label {
  padding-block-start: 0.8125rem;
  font-weight: var(--font-weight-medium);
  line-height: 1.15;
}

/* The older value is read against the current one, so it is the quieter of the two. */
.compare-cell--then {
  color: var(--color-text-muted);
}

.compare-cell__empty {
  color: var(--color-text-faint);
  font-style: italic;
}

.compare-cell--status {
  display: flex;
  padding-block: 0.75rem;
  padding-inline-end: 0.875rem;
}

.compare__note {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0;
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-control);
  background: var(--color-cocoa-soft);
  color: var(--color-cocoa-strong);
  font-size: var(--font-size-xs);
  line-height: 1.45;
}

.compare__note > svg {
  flex: none;
}

/* ---------- The question about restoring ---------- */

.restore-card {
  display: grid;
  gap: 0.625rem;
  margin-block-start: auto;
  padding: 0.8125rem 1rem 0.875rem;
  border-radius: var(--radius-md);
  background: var(--color-paper-soft);
}

.restore-card > p {
  margin: 0;
}

.restore-card__question {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.43;
}

/* The question takes focus only so a screen reader starts at it; it is not a control to point at. */
.restore-card__question:focus-visible {
  box-shadow: none;
  outline: none;
}

.restore-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

/* ---------- Narrower windows ---------- */

/* Too narrow for two sides: the versions sit above what the chosen one differs on, and each scrolls on its own. */
@container version-history (max-width: 46rem) {
  .version-history__body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(0, 2fr) minmax(0, 3fr);
  }

  .version-history__versions {
    border-inline-end: 0;
    border-block-end: 1px solid var(--color-hairline);
  }
}

/* Too narrow for four columns: each fill spot becomes a small card, and each value says which version it is from. */
@container version-detail (max-width: 30rem) {
  .compare-table__head {
    display: none;
  }

  .compare-row {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'label status'
      'then then'
      'now now';
    padding-block: 0.25rem 0.5rem;
  }

  .compare-cell {
    padding: 0.25rem 0.875rem 0;
  }

  .compare-cell--label {
    grid-area: label;
    padding-block-start: 0.5rem;
  }

  .compare-cell--then {
    grid-area: then;
  }

  .compare-cell--now {
    grid-area: now;
  }

  .compare-cell--status {
    grid-area: status;
    padding-block-start: 0.5rem;
  }

  .compare-cell[data-label]::before {
    content: attr(data-label);
    display: block;
    color: var(--color-text-muted);
    font-size: var(--font-size-xs);
    font-weight: var(--font-weight-medium);
    line-height: 1.5;
  }
}
</style>
