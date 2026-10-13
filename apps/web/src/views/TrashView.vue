<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { useTemplatesStore } from '@/stores/templates'
import { loadCapabilities } from '@/capabilities'
import { dayCount } from '@/periods'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import {
  ApiRequestError,
  listDeletions,
  listTrashedTemplates,
  purgeDeletion,
  restoreDeletion,
  restoreTemplate,
  type DeletionResponse,
  type TemplateResponse,
} from '@/api/client'

/**
 * The trash bin: every document that was moved here and can still come
 * back, each with the day it will be deleted for good if nobody restores
 * it first. Restoring is one click because it loses nothing. Deleting
 * forever cannot be undone, so it asks once, in the row itself, with the
 * document's name in the question; there is no dialog to get lost in, and
 * Escape or "Keep it" puts focus back on the button that asked.
 *
 * Templates moved here have a section of their own. They are never
 * deleted for good, by time or by hand: every document made from one
 * still reads it, so it stays until it is restored.
 */
const session = useSessionStore()
const templatesStore = useTemplatesStore()
const entries = ref<DeletionResponse[]>([])
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const loadError = ref<string | null>(null)
/** "30 days", or null when the server did not say, which an older one does not. */
const retention = ref<string | null>(null)

const busyId = ref<number | null>(null)
const confirmingId = ref<number | null>(null)
const notice = ref<{ text: string; documentId: number | null } | null>(null)
const actionError = ref<string | null>(null)
const noticeElement = ref<HTMLElement | null>(null)

const trashed = computed(() => entries.value.filter((entry) => entry.scope === 'DOCUMENT' && entry.state === 'TRASHED'))

const trashedTemplates = ref<TemplateResponse[]>([])
/** "older" is a server that keeps no templates in the trash at all. */
const templatesState = ref<'idle' | 'loading' | 'loaded' | 'error' | 'older'>('idle')
const templatesError = ref<string | null>(null)
const restoringTemplateId = ref<number | null>(null)

/** Nothing in the trash at all, and both lists have said so. */
const empty = computed(
  () =>
    loadState.value === 'loaded' &&
    trashed.value.length === 0 &&
    templatesState.value === 'loaded' &&
    trashedTemplates.value.length === 0,
)

/**
 * What the page says about how long documents stay. The number is the one the
 * server enforces, so the page never promises a different period. When the
 * server does not send it (an older one), the sentence points at the date
 * beside each row instead, which is always the real one.
 */
const lede = computed(
  () =>
    `${retention.value ? `Documents stay here for ${retention.value}` : 'Documents stay here until the date shown beside each one'}, ` +
    'then Brownie deletes them for good. Templates stay until you restore them.',
)

/**
 * The same words describeCommonFailure uses for a server older than this
 * page, for the one way such a server shows it here without a missing
 * route: see loadTemplates.
 */
const OLDER_SERVER_TEMPLATES =
  'The Brownie server that answered is older than this page and does not have a Trash Bin for templates yet. ' +
  'Reloading will not change that: the server needs to be updated and restarted.'

/** Deleted for good, with stored files the background worker has not removed yet. */
const stillRemovingCount = computed(
  () => entries.value.filter((entry) => entry.state === 'PURGED' && entry.pendingObjectCount > 0).length,
)

const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function formatDay(value: string | null | undefined): string {
  return value ? dateFormatter.format(new Date(value)) : ''
}

function titleOf(entry: DeletionResponse): string {
  return entry.title ?? 'Untitled document'
}

/**
 * What a row says about its dates. The background worker deletes an entry
 * some time after its day has passed, not at that moment, and until it does
 * the entry can still be restored; "deleted for good on" a day already gone,
 * next to a Restore button that still works, would be wrong both ways.
 */
function datesOf(entry: DeletionResponse): string {
  const sentences: string[] = []
  if (entry.requestedAt) {
    sentences.push(`Moved here ${formatDay(entry.requestedAt)}.`)
  }
  const due = entry.purgeAfter ? Date.parse(entry.purgeAfter) : Number.NaN
  if (!Number.isNaN(due)) {
    sentences.push(
      due <= Date.now()
        ? `Its time in the trash ran out on ${formatDay(entry.purgeAfter)}, so it is due to be deleted for good; until that happens, it can still be restored.`
        : `Deleted for good on ${formatDay(entry.purgeAfter)}.`,
    )
  }
  return sentences.join(' ')
}

/** Counts every request for the templates in the trash, so an answer that arrives after a newer one was asked for is dropped. */
let templatesRequests = 0

/**
 * A server that predates templates in the trash has the list route but
 * not the question: it ignores "trashed=true" and answers with every
 * template it has, none of which says when it was trashed. Offering
 * those for restoring would present the whole live list as trash, so an
 * answer like that is read as the older server it came from.
 *
 * Only the first load says "loading": a list already on screen stays
 * there while it is fetched again, so a template just moved here from the
 * sidebar appears in place instead of the whole list blinking out and back.
 */
async function loadTemplates(workspaceId: number): Promise<void> {
  const request = ++templatesRequests
  if (templatesState.value !== 'loaded') {
    templatesState.value = 'loading'
  }
  templatesError.value = null
  try {
    const answer = await listTrashedTemplates(workspaceId)
    if (request !== templatesRequests) {
      return
    }
    if (answer.some((template) => typeof template.trashedAt !== 'string')) {
      trashedTemplates.value = []
      templatesState.value = 'older'
      return
    }
    trashedTemplates.value = answer
    templatesState.value = 'loaded'
  } catch (error) {
    if (request !== templatesRequests) {
      return
    }
    templatesState.value = 'error'
    templatesError.value =
      describeCommonFailure(error, 'a Trash Bin for templates') ??
      'Brownie could not load the templates in the trash. If this keeps happening, let whoever runs this Brownie know.'
  }
}

/**
 * The retention period is a nicety in the explanation; each row carries its own date either way. It is asked for
 * before the list is, not after, so a period the app already knows is in the sentence from the first moment and the
 * sentence does not change under the reader when the list arrives.
 */
async function loadRetention(): Promise<void> {
  try {
    retention.value = dayCount((await loadCapabilities()).trashRetentionDays)
  } catch {
    retention.value = null
  }
}

async function load(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) {
    return
  }
  void loadRetention()
  void loadTemplates(workspaceId)
  loadState.value = 'loading'
  loadError.value = null
  try {
    entries.value = await listDeletions(workspaceId)
    loadState.value = 'loaded'
  } catch (error) {
    loadState.value = 'error'
    loadError.value =
      describeCommonFailure(error, 'the trash bin') ??
      'Brownie could not load the trash bin. If this keeps happening, let whoever runs this Brownie know.'
  }
}

onMounted(load)
watch(
  () => session.personalWorkspaceId,
  (workspaceId, previous) => {
    if (workspaceId !== undefined && workspaceId !== previous) {
      void load()
    }
  },
)
// The sidebar stays beside this page, and a template moved to the trash from there belongs in the list here.
watch(
  () => templatesStore.trashedSerial,
  () => {
    const workspaceId = session.personalWorkspaceId
    if (workspaceId !== undefined) {
      void loadTemplates(workspaceId)
    }
  },
)

async function showNotice(text: string, documentId: number | null): Promise<void> {
  notice.value = { text, documentId }
  await nextTick()
  noticeElement.value?.focus()
}

/** True when the server says this entry is no longer in the trash at all: someone restored it, or it was deleted for good. */
function isNoLongerInTrash(error: unknown): boolean {
  return (
    // Only Brownie's own "not there" says anything about the entry; see brownieSaysNotThere.
    brownieSaysNotThere(error) ||
    (error instanceof ApiRequestError &&
      error.status === 409 &&
      error.problem?.code !== 'DELETION_WAITING_FOR_RUNNING_WORK')
  )
}

/**
 * The pressed button is disabled while its request runs, which drops
 * keyboard focus. When trying again makes sense, focus goes back to that
 * button once it is enabled again; every other outcome removes the row and
 * moves focus to the sentence that says what happened.
 */
async function refocus(buttonId: string): Promise<void> {
  await nextTick()
  document.getElementById(buttonId)?.focus()
}

async function restore(entry: DeletionResponse): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busyId.value !== null) {
    return
  }
  busyId.value = entry.id
  actionError.value = null
  let retryable = false
  try {
    await restoreDeletion(workspaceId, entry.id)
    entries.value = entries.value.filter((candidate) => candidate.id !== entry.id)
    await showNotice(`Restored "${titleOf(entry)}".`, entry.targetId)
  } catch (error) {
    if (isNoLongerInTrash(error)) {
      // Deleted for good elsewhere (another tab, or its time ran out while this page was open): the row is history now.
      entries.value = entries.value.filter((candidate) => candidate.id !== entry.id)
      await showNotice(`"${titleOf(entry)}" was already deleted for good, so it cannot be restored.`, null)
    } else {
      retryable = true
      actionError.value = `Could not restore "${titleOf(entry)}". ${describeCommonFailure(error, 'a way to restore documents') ?? 'Try again.'}`
    }
  } finally {
    busyId.value = null
  }
  if (retryable) {
    await refocus(`trash-restore-${entry.id}`)
  }
}

/**
 * Puts a template back in My Templates, where the sidebar shows it again at
 * once. Restoring one that is already back (from another tab, say) is the
 * same success: the server answers with the template either way.
 */
async function restoreTrashedTemplate(template: TemplateResponse): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || restoringTemplateId.value !== null) {
    return
  }
  restoringTemplateId.value = template.id
  actionError.value = null
  let retryable = false
  try {
    await restoreTemplate(workspaceId, template.id)
    trashedTemplates.value = trashedTemplates.value.filter((candidate) => candidate.id !== template.id)
    void templatesStore.refresh(workspaceId)
    await showNotice(`Restored "${template.displayName}" to My Templates.`, null)
  } catch (error) {
    retryable = true
    actionError.value = `Could not restore "${template.displayName}". ${
      describeCommonFailure(error, 'a way to restore templates') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Try again.'
    }`
  } finally {
    restoringTemplateId.value = null
  }
  if (retryable) {
    await refocus(`trash-template-restore-${template.id}`)
  }
}

async function askToDelete(entry: DeletionResponse): Promise<void> {
  confirmingId.value = entry.id
  actionError.value = null
  await nextTick()
  document.getElementById(`trash-confirm-${entry.id}`)?.focus()
}

async function keep(entry: DeletionResponse): Promise<void> {
  confirmingId.value = null
  await nextTick()
  document.getElementById(`trash-delete-${entry.id}`)?.focus()
}

async function deleteForever(entry: DeletionResponse): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busyId.value !== null) {
    return
  }
  busyId.value = entry.id
  actionError.value = null
  let retryable = false
  try {
    const purged = await purgeDeletion(workspaceId, entry.id)
    entries.value = entries.value.map((candidate) => (candidate.id === entry.id ? purged : candidate))
    confirmingId.value = null
    await showNotice(`Deleted "${titleOf(entry)}" for good.`, null)
  } catch (error) {
    if (isNoLongerInTrash(error)) {
      // Restored elsewhere while this page still listed it: nothing was deleted, and the document is live again.
      entries.value = entries.value.filter((candidate) => candidate.id !== entry.id)
      confirmingId.value = null
      await showNotice(`"${titleOf(entry)}" was restored from the trash, so nothing was deleted.`, entry.targetId)
    } else {
      retryable = true
      const waiting =
        error instanceof ApiRequestError && error.problem?.code === 'DELETION_WAITING_FOR_RUNNING_WORK'
      actionError.value = waiting
        ? `A run for "${titleOf(entry)}" is still stopping, so nothing was deleted. Try again in a moment.`
        : `Could not delete "${titleOf(entry)}". ${describeCommonFailure(error, 'a way to delete documents for good') ?? 'Try again.'}`
    }
  } finally {
    busyId.value = null
  }
  if (retryable) {
    await refocus(`trash-confirm-${entry.id}`)
  }
}
</script>

<template>
  <!-- The router sends a signed-out visitor to the sign-in page before this view mounts; this is what shows if a session ends while it is open. -->
  <section v-if="session.status === 'anonymous'" class="page-column trash">
    <div class="panel trash__signin">
      <p>Sign in to see your trash bin.</p>
      <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/trash' } }">Sign in</RouterLink>
    </div>
  </section>

  <section v-else class="page-column trash" aria-labelledby="trash-heading">
    <div class="page-head">
      <h1 id="trash-heading" class="page-title">
        {{ empty ? 'Your trash bin is empty' : 'Trash bin' }}
      </h1>
      <p class="page-lede">{{ lede }}</p>
    </div>

    <p v-if="notice" ref="noticeElement" class="trash__notice" tabindex="-1">
      {{ notice.text }}
      <RouterLink v-if="notice.documentId !== null" :to="`/documents/${notice.documentId}`">Open it</RouterLink>
    </p>
    <p v-if="actionError" class="field-error trash__status" role="alert">{{ actionError }}</p>

    <p v-if="loadState === 'loading'" class="field-hint trash__status" aria-live="polite">Loading the trash bin…</p>
    <p v-else-if="loadState === 'error'" class="field-error trash__status" role="alert">{{ loadError }}</p>
    <!-- With nothing in the trash at all, the heading already says so. -->
    <p v-else-if="loadState === 'loaded' && trashed.length === 0 && !empty" class="trash__empty">
      <AppIcon name="document-blank" :size="24" />
      No documents are in the trash.
    </p>

    <ul v-if="trashed.length > 0" class="panel panel--flush trash__list" aria-label="Documents in the trash">
      <li
        v-for="entry in trashed"
        :key="entry.id"
        class="trash__item"
        :class="{ 'trash__item--confirming': confirmingId === entry.id }"
      >
        <span class="doc-tile" aria-hidden="true"><AppIcon name="document-text" :size="22" /></span>
        <div class="trash__text">
          <span class="trash__name" :title="titleOf(entry)">{{ titleOf(entry) }}</span>
          <span class="field-hint">{{ datesOf(entry) }}</span>
        </div>

        <div v-if="confirmingId !== entry.id" class="trash__actions">
          <button
            :id="`trash-restore-${entry.id}`"
            type="button"
            class="button button--secondary button--small"
            :disabled="busyId !== null"
            @click="restore(entry)"
          >
            <AppIcon name="restore" :size="16" />
            <!-- The space lives outside the hidden span: inside it, the compiler trims it and the name runs together. -->
            <span>Restore <span class="visually-hidden">{{ titleOf(entry) }}</span></span>
          </button>
          <button
            :id="`trash-delete-${entry.id}`"
            type="button"
            class="button button--quiet button--small"
            :disabled="busyId !== null"
            @click="askToDelete(entry)"
          >
            <AppIcon class="trash__icon-soft" name="trash" :size="16" />
            <span>Delete forever <span class="visually-hidden">{{ titleOf(entry) }}</span></span>
          </button>
        </div>

        <div v-else class="trash__confirm" role="group" :aria-label="`Delete ${titleOf(entry)} forever`" @keydown.esc="keep(entry)">
          <p class="trash__question">Delete "{{ titleOf(entry) }}" forever? This cannot be undone.</p>
          <div class="trash__actions">
            <button
              :id="`trash-confirm-${entry.id}`"
              type="button"
              class="button button--danger button--small"
              :disabled="busyId !== null"
              @click="deleteForever(entry)"
            >
              <AppIcon class="trash__icon-soft" name="trash" :size="16" />
              {{ busyId === entry.id ? 'Deleting…' : 'Yes, delete forever' }}
            </button>
            <button type="button" class="button button--secondary button--small" :disabled="busyId !== null" @click="keep(entry)">
              Keep it
            </button>
          </div>
        </div>
      </li>
    </ul>

    <p v-if="stillRemovingCount > 0" class="field-hint trash__removing">
      {{
        stillRemovingCount === 1
          ? 'The files of 1 deleted document are still being removed in the background.'
          : `The files of ${stillRemovingCount} deleted documents are still being removed in the background.`
      }}
      Nobody can open them any more.
    </p>

    <section class="trash__templates" aria-labelledby="trash-templates-heading">
      <div class="trash__section-head">
        <h2 id="trash-templates-heading" class="trash__subtitle">Templates</h2>
        <p class="field-hint">
          Documents made from a template keep working while it is in the trash. A template stays here until you restore
          it.
        </p>
      </div>

      <p v-if="templatesState === 'loading'" class="field-hint trash__status">Loading the templates in the trash…</p>
      <p v-else-if="templatesState === 'older'" class="field-error trash__status" role="alert">{{ OLDER_SERVER_TEMPLATES }}</p>
      <p v-else-if="templatesState === 'error'" class="field-error trash__status" role="alert">{{ templatesError }}</p>
      <p v-else-if="templatesState === 'loaded' && trashedTemplates.length === 0 && !empty" class="trash__empty">
        <AppIcon name="layout" :size="24" />
        No templates are in the trash.
      </p>

      <ul v-if="trashedTemplates.length > 0" class="panel panel--flush trash__list" aria-label="Templates in the trash">
        <li v-for="template in trashedTemplates" :key="template.id" class="trash__item">
          <span class="doc-tile" aria-hidden="true"><AppIcon name="layout" :size="22" /></span>
          <div class="trash__text">
            <span class="trash__name" :title="template.displayName">{{ template.displayName }}</span>
            <span class="field-hint">Moved here {{ formatDay(template.trashedAt) }}. Stays until you restore it.</span>
          </div>
          <div class="trash__actions">
            <button
              :id="`trash-template-restore-${template.id}`"
              type="button"
              class="button button--secondary button--small"
              :disabled="restoringTemplateId !== null"
              @click="restoreTrashedTemplate(template)"
            >
              <AppIcon name="restore" :size="16" />
              <!-- The space lives outside the hidden span: inside it, the compiler trims it and the name runs together. -->
              <span>Restore <span class="visually-hidden">{{ template.displayName }}</span></span>
            </button>
          </div>
        </li>
      </ul>
    </section>

    <RouterLink class="button button--quiet button--small trash__back" to="/">
      <AppIcon name="arrow-left" :size="16" />
      Back to your documents
    </RouterLink>
  </section>
</template>

<style scoped>
/*
 * Everything on the page is one column of parts (the title block, a notice, each list, the note under a list, the
 * templates, the way back). One grid gap sets the distance between them, and the few parts that adjust it say why
 * where they are written; the single track is minmax(0, 1fr) so a long unbroken name can never push the page wider
 * than the window.
 */
.trash {
  /* The shell's own side margins already keep the column off the window edge. */
  --page-gutter: 0px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-content: start;
  gap: 1.25rem;
  /* The shell already leaves 1.5rem above a page, and the shared column's 3.5rem on top of that sits the title too low; this puts its first line 67px below the top of the window. */
  padding-block-start: 2.6875rem;
}

/*
 * The grid's gap owns the space after the title block, so the lede carries none of its own below it. The block ends
 * in a line of type, which carries a little leading under its last line, so it hands back a pixel of the gap: the
 * visible space then matches the one between two cards.
 */
.trash .page-head {
  margin-block-end: -0.0625rem;
}

.trash .page-lede {
  margin-block: 0.625rem 0;
}

.trash__signin {
  display: grid;
  justify-items: start;
  gap: var(--space-3);
}

.trash__signin p {
  margin: 0;
}

/* A line of state (loading, a failure) in the place the list will be. It is the message itself, not small print, so it takes the page's 14px body size rather than a hint's 12px. */
.trash__status {
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

/* Said in the place a list would be, drawn as an empty slot rather than as a card, so it is never mistaken for a row. */
.trash__empty {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  margin: 0;
  padding: 1.125rem 1.25rem;
  border: 1px dashed var(--color-outline);
  border-radius: var(--radius-lg);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.45;
}

.trash__empty > svg {
  flex: none;
}

/* What just happened, and where to go from it. The radius is the one a focused element is given, so focus does not change the corners. */
.trash__notice {
  margin: 0;
  padding: 0.75rem 1rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  font-size: var(--font-size-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

/* The default link blue measures 4.48:1 on this wash, just under AA; the text colour is 10.17:1 and the underline still says "link". */
.trash__notice a {
  color: var(--color-text);
  font-weight: var(--font-weight-medium);
}

.trash__list {
  margin: 0;
  list-style: none;
}

/* The note about files still being removed belongs to the list above it, so it sits closer to it than the parts of the page do to each other. */
.trash__removing {
  margin: -0.5rem 0 0;
}

/*
 * A row is the tile, the words and the buttons. While a delete is being confirmed the buttons give way to the
 * question, which sits under the words and not under the tile, so the name stays exactly where the eye left it.
 */
.trash__item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 0.875rem;
  padding: 0.875rem 1rem;
}

.trash__item + .trash__item {
  border-block-start: 1px solid var(--color-hairline);
}

/* The red wash runs the full width of the row, edge to edge of the list, so the question cannot be taken for part of another row. */
.trash__item--confirming {
  align-items: start;
  background: var(--color-error-wash);
}

.trash__text {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  min-inline-size: 0;
}

/* A row is as tall as its two lines together, so the small line's leading is fixed here instead of inherited. */
.trash__text .field-hint,
.trash__section-head .field-hint {
  line-height: 1.35;
}

.trash__item--confirming .trash__text {
  grid-column: 2 / -1;
}

.trash__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.25;
}

.trash__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.trash__confirm {
  grid-column: 2 / -1;
  display: grid;
  gap: 0.6875rem;
  margin-block-start: 0.625rem;
}

.trash__confirm .trash__actions {
  gap: var(--space-2);
}

/*
 * The can is a decoration beside words that already say "delete": drawn at 60% so the label, not the picture,
 * carries the weight. Under the pointer it comes to full strength, because the hover wash would otherwise leave it
 * below the 3:1 a graphic needs (2.84:1 at 60%).
 */
.trash__icon-soft {
  opacity: 0.6;
  transition: opacity var(--motion-fast) var(--motion-ease);
}

.button:hover > .trash__icon-soft {
  opacity: 1;
}

/* Error red on the red wash measures 4.83:1, so the question stays readable as text, not only as a colour. */
.trash__question {
  margin: 0;
  color: var(--color-error);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
  /* A file name with no spaces in it must wrap inside the row, not run out of it. */
  overflow-wrap: anywhere;
}

/* A line of small type carries its own leading below it, so a gap of 1.125rem reads as the same rhythm as the 1.25rem between cards. */
.trash__templates {
  display: grid;
  gap: 1.125rem;
}

.trash__section-head {
  display: grid;
  gap: 0.375rem;
}

.trash__section-head p {
  margin: 0;
}

.trash__subtitle {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  line-height: 1.2;
}

.trash__back {
  justify-self: start;
}

/* With little room the buttons would squeeze the name to a few letters, so they drop under it, in line with it. */
@container main (max-width: 38rem) {
  .trash__item {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .trash__item > .trash__actions {
    grid-column: 2;
    margin-block-start: 0.625rem;
  }
}
</style>
