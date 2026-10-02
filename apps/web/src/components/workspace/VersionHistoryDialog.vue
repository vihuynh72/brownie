<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import {
  ApiRequestError,
  getDocumentRevision,
  listDocumentRevisions,
  restoreRevision,
  type DocumentRevisionResponse,
} from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'

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
 * person decides; the confirmation for a restore sits inside the row it
 * belongs to rather than in a second dialog on top of this one.
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
  compareToken += 1
  versions.value = []
  listState.value = 'idle'
  listError.value = null
  compareRevisionId.value = null
  compareStage.value = 'idle'
  compareError.value = null
  compareRevision.value = null
  confirmingRevisionId.value = null
  restoreStage.value = 'idle'
  restoreError.value = null
  restoreKey = null
  status.value = ''
}

defineExpose({ open, close })

// ---- The list of versions ------------------------------------------------------------------

const versions = ref<DocumentRevisionResponse[]>([])
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
    listState.value = 'loaded'
    if (firstLoad) status.value = ''
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

function when(revision: DocumentRevisionResponse): string {
  return new Date(revision.createdAt).toLocaleString()
}

// ---- Comparing a version with now ----------------------------------------------------------

const compareRevisionId = ref<number | null>(null)
type CompareStage = 'idle' | 'loading' | 'loaded' | 'error'
const compareStage = ref<CompareStage>('idle')
const compareError = ref<string | null>(null)
const compareRevision = ref<DocumentRevisionResponse | null>(null)

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
    status.value = `Showing version ${loaded.revisionNumber} compared with now.`
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

/** A field's value as one line of text, or null when it holds nothing. */
function fieldDisplayValue(field: FieldValue | undefined): string | null {
  if (!field) return null
  const text = field.value ?? (field.values ?? []).join(', ')
  return text === '' ? null : text
}

const compareFieldIds = computed<string[]>(() => {
  if (compareRevision.value === null) return []
  return Array.from(
    new Set([...Object.keys(compareRevision.value.fields), ...Object.keys(props.currentRevision.fields)]),
  ).sort()
})

function fieldChanged(fieldId: string): boolean {
  if (compareRevision.value === null) return false
  return (
    fieldDisplayValue(compareRevision.value.fields[fieldId]) !==
    fieldDisplayValue(props.currentRevision.fields[fieldId])
  )
}

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

// ---- Restoring a version -------------------------------------------------------------------

const confirmingRevisionId = ref<number | null>(null)
type RestoreStage = 'idle' | 'restoring'
const restoreStage = ref<RestoreStage>('idle')
const restoreError = ref<string | null>(null)

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
    class="version-history"
    :class="{ 'version-history--fallback': !nativeModal }"
    aria-labelledby="version-history-heading"
    @cancel.prevent="close"
    @close="onNativeClose"
    @keydown="onFallbackKeydown"
  >
    <template v-if="isOpen">
      <header class="version-history__header">
        <h2 id="version-history-heading" class="version-history__title">Version history</h2>
        <button ref="closeButton" class="icon-button" type="button" @click="close">
          <AppIcon name="close" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <div class="version-history__body">
        <p class="field-hint">
          Every save keeps a version here. Restoring one adds it as a new current version, so nothing is lost. Locked
          fill spots keep their current values.
        </p>

        <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ status }}</p>

        <p v-if="listState === 'loading'" class="field-hint">Loading version history…</p>
        <p v-if="listError" class="field-error" role="alert">{{ listError }}</p>
        <button
          v-if="listState === 'error' && listRetryable"
          class="button button--secondary"
          type="button"
          @click="loadVersions"
        >
          Try again
        </button>

        <ul v-if="orderedVersions.length > 0" class="revision-list">
          <li v-for="revision in orderedVersions" :key="revision.id" class="revision-row">
            <div class="revision-row__main">
              <div class="revision-row__body">
                <span class="revision-row__name">
                  Version {{ revision.revisionNumber }}
                  <span v-if="isCurrent(revision)" class="revision-row__badge">Current version</span>
                </span>
                <span class="field-hint">{{ revision.editReason }}</span>
                <time class="field-hint" :datetime="revision.createdAt">{{ when(revision) }}</time>
              </div>

              <div v-if="confirmingRevisionId !== revision.id" class="revision-row__actions">
                <button
                  class="button button--secondary"
                  type="button"
                  :aria-label="`Compare version ${revision.revisionNumber} with the current version`"
                  :aria-disabled="compareStage === 'loading' && compareRevisionId === revision.id ? 'true' : undefined"
                  @click="compareWithNow(revision)"
                >
                  Compare
                </button>
                <button
                  v-if="!isCurrent(revision)"
                  :id="restoreButtonId(revision)"
                  class="button button--secondary"
                  type="button"
                  :aria-label="`Restore version ${revision.revisionNumber}`"
                  @click="askToRestore(revision)"
                >
                  <AppIcon name="restore" :size="18" />
                  Restore
                </button>
              </div>
            </div>

            <div
              v-if="confirmingRevisionId === revision.id"
              class="revision-row__confirm"
              role="group"
              :aria-labelledby="questionId(revision)"
            >
              <p :id="questionId(revision)" class="revision-row__question" tabindex="-1">
                Restore version {{ revision.revisionNumber }}? Your current version stays in the history.
              </p>
              <p v-if="restoreError" class="field-error" role="alert">{{ restoreError }}</p>
              <div class="revision-row__actions">
                <button
                  class="button button--primary"
                  type="button"
                  :aria-disabled="restoreStage === 'restoring' ? 'true' : undefined"
                  @click="confirmRestore(revision)"
                >
                  {{ restoreStage === 'restoring' ? 'Restoring…' : `Restore version ${revision.revisionNumber}` }}
                </button>
                <button
                  class="button button--secondary"
                  type="button"
                  :aria-disabled="restoreStage === 'restoring' ? 'true' : undefined"
                  @click="keepCurrentVersion(revision)"
                >
                  Keep the current version
                </button>
              </div>
            </div>

            <div v-if="compareRevisionId === revision.id && compareStage !== 'idle'" class="compare-panel">
              <p v-if="compareStage === 'loading'" class="field-hint">Loading version {{ revision.revisionNumber }}…</p>
              <p v-if="compareError" class="field-error" role="alert">{{ compareError }}</p>

              <template v-if="compareStage === 'loaded' && compareRevision">
                <h3 class="compare-panel__title">Version {{ compareRevision.revisionNumber }} compared with now</h3>
                <div class="compare-row compare-row--head" aria-hidden="true">
                  <span></span>
                  <div class="compare-row__values">
                    <span>Version {{ compareRevision.revisionNumber }}</span>
                    <span>Now (version {{ currentRevision.revisionNumber }})</span>
                  </div>
                </div>
                <div
                  v-for="fieldId in compareFieldIds"
                  :key="fieldId"
                  class="compare-row"
                  :class="{ 'compare-row--changed': fieldChanged(fieldId) }"
                >
                  <span class="compare-row__label">
                    <span class="compare-row__name">{{ fieldLabel(fieldId) }}</span>
                    <span v-if="fieldChanged(fieldId)" class="compare-row__note">Changed</span>
                    <span v-if="fieldChanged(fieldId) && keptFieldIds.has(fieldId)" class="compare-row__note">
                      Locked, so a restore keeps its current value
                    </span>
                  </span>
                  <div class="compare-row__values">
                    <span>
                      <span class="compare-row__which">Version {{ compareRevision.revisionNumber }}: </span>
                      <template v-if="fieldDisplayValue(compareRevision.fields[fieldId]) !== null">
                        {{ fieldDisplayValue(compareRevision.fields[fieldId]) }}
                      </template>
                      <span v-else class="compare-row__empty">Empty</span>
                    </span>
                    <span>
                      <span class="compare-row__which">Now: </span>
                      <template v-if="fieldDisplayValue(currentRevision.fields[fieldId]) !== null">
                        {{ fieldDisplayValue(currentRevision.fields[fieldId]) }}
                      </template>
                      <span v-else class="compare-row__empty">Empty</span>
                    </span>
                  </div>
                </div>
              </template>
            </div>
          </li>
        </ul>
        <p v-else-if="listState === 'loaded'" class="field-hint">This document has no saved versions.</p>
        <p v-if="listState === 'loaded' && versions.length === 1" class="field-hint">
          There is only one version, so there is nothing to restore.
        </p>
      </div>
    </template>
  </dialog>
</template>

<style scoped>
.version-history {
  width: min(40rem, 100% - 2rem);
  max-width: none;
  max-height: min(90dvh, 52rem);
  padding: 0;
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 1rem 3rem rgb(42 41 36 / 0.2);
}

/* Only while open: a display value on a closed dialog would override the browser's own display: none. */
.version-history[open] {
  display: flex;
  flex-direction: column;
}

.version-history::backdrop {
  background: rgb(42 41 36 / 0.45);
  background: color-mix(in srgb, var(--color-text, #2a2924) 45%, transparent);
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.version-history--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  height: fit-content;
}

.version-history__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-hairline);
}

.version-history__title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}

.version-history__body {
  container: version-history / inline-size;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-4) var(--space-5) var(--space-5);
}

.version-history__body > .field-hint:first-child {
  margin-top: 0;
}

.revision-list {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.revision-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: 0.75rem;
}

.revision-row__main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
}

.revision-row__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
  overflow-wrap: anywhere;
}

.revision-row__name {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  font-weight: 600;
}

.revision-row__badge {
  padding: 0 var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--color-honey-soft);
  color: var(--color-text);
  font-size: var(--font-size-sm);
  font-weight: 500;
}

.revision-row__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.revision-row__confirm {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.revision-row__question {
  margin: 0;
  font-weight: 600;
}

.button[aria-disabled='true'] {
  opacity: 0.6;
  cursor: progress;
}

/* The same rounded, compact buttons as the workspace behind the window. */
.version-history .button {
  min-block-size: 2.25rem;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
}

.compare-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-border);
}

.compare-panel__title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-base);
}

.compare-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  align-items: start;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-2);
  border-bottom: 1px solid var(--color-border);
  overflow-wrap: anywhere;
}

.compare-row--head {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.compare-row__label {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.compare-row__name {
  font-weight: 600;
}

.compare-row__note {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.compare-row__values {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-2);
}

.compare-row__values > span {
  position: relative;
}

/* Which version a value is from: read out beside every value, and shown only once the columns stack. */
.compare-row__which {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

.compare-row__empty {
  color: var(--color-text-secondary);
  font-style: italic;
}

.compare-row--changed {
  background: var(--color-honey-soft);
  border-radius: var(--radius);
}

.compare-row--changed .compare-row__values > span {
  font-weight: 600;
}

@container version-history (max-width: 30rem) {
  .compare-row,
  .compare-row__values {
    grid-template-columns: minmax(0, 1fr);
  }

  /* Stacked, each value says which version it is, so the column heads have nothing to head. */
  .compare-row--head {
    display: none;
  }

  .compare-row__which {
    position: static;
    width: auto;
    height: auto;
    margin: 0;
    overflow: visible;
    clip: auto;
    white-space: normal;
    color: var(--color-text-secondary);
    font-weight: 400;
  }
}

/* On a phone the dialog takes the whole width; the page behind it has nothing left to show at the sides. */
@media (max-width: 30rem) {
  .version-history {
    width: 100%;
    max-height: 100dvh;
    border-inline: 0;
    border-radius: 0;
  }

  .version-history__header,
  .version-history__body {
    padding-inline: var(--space-4);
  }
}
</style>
