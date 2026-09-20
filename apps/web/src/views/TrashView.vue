<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { loadCapabilities } from '@/capabilities'
import { dayCount } from '@/periods'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { ApiRequestError, listDeletions, purgeDeletion, restoreDeletion, type DeletionResponse } from '@/api/client'

/**
 * The trash bin: every document that was moved here and can still come
 * back, each with the day it will be deleted for good if nobody restores
 * it first. Restoring is one click because it loses nothing. Deleting
 * forever cannot be undone, so it asks once, in the row itself, with the
 * document's name in the question; there is no dialog to get lost in, and
 * Escape or "Keep it" puts focus back on the button that asked.
 */
const session = useSessionStore()
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

async function load(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) {
    return
  }
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
  // The retention period is a nicety in the explanation; each row carries its own date either way.
  try {
    retention.value = dayCount((await loadCapabilities()).trashRetentionDays)
  } catch {
    retention.value = null
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
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to see your trash bin.</p>
    <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/trash' } }">Sign in</RouterLink>
  </section>

  <section v-else class="trash" aria-labelledby="trash-heading">
    <span class="trash__mark" aria-hidden="true"><AppIcon name="trash" :size="28" /></span>
    <h1 id="trash-heading" class="trash__title">
      {{ loadState === 'loaded' && trashed.length === 0 ? 'Your trash bin is empty' : 'Trash bin' }}
    </h1>
    <p v-if="retention">
      Documents you move here can be restored exactly as they were for {{ retention }}. After that, or when you delete
      one yourself, it is deleted for good together with its files and any source file that nothing else uses.
    </p>
    <p v-else>
      Documents you move here can be restored exactly as they were at least until the date shown beside each one. Soon
      after that date it is deleted for good together with its files and any source file that nothing else uses;
      deleting one yourself does that straight away.
    </p>

    <p v-if="notice" ref="noticeElement" class="trash__notice" tabindex="-1">
      {{ notice.text }}
      <RouterLink v-if="notice.documentId !== null" :to="`/documents/${notice.documentId}`">Open it</RouterLink>
    </p>
    <p v-if="actionError" class="field-error" role="alert">{{ actionError }}</p>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading the trash bin…</p>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">{{ loadError }}</p>

    <ul v-if="trashed.length > 0" class="trash__list">
      <li v-for="entry in trashed" :key="entry.id" class="trash__item">
        <div class="trash__text">
          <span class="trash__name" :title="titleOf(entry)">{{ titleOf(entry) }}</span>
          <span class="field-hint">{{ datesOf(entry) }}</span>
        </div>

        <div v-if="confirmingId !== entry.id" class="trash__actions">
          <button
            :id="`trash-restore-${entry.id}`"
            type="button"
            class="button button--secondary"
            :disabled="busyId !== null"
            @click="restore(entry)"
          >
            <AppIcon name="restore" :size="18" />
            <!-- The space lives outside the hidden span: inside it, the compiler trims it and the name runs together. -->
            <span>Restore <span class="visually-hidden">{{ titleOf(entry) }}</span></span>
          </button>
          <button
            :id="`trash-delete-${entry.id}`"
            type="button"
            class="button button--secondary trash__danger"
            :disabled="busyId !== null"
            @click="askToDelete(entry)"
          >
            Delete forever <span class="visually-hidden">{{ titleOf(entry) }}</span>
          </button>
        </div>

        <div v-else class="trash__confirm" role="group" :aria-label="`Delete ${titleOf(entry)} forever`" @keydown.esc="keep(entry)">
          <p class="trash__question">Delete "{{ titleOf(entry) }}" forever? This cannot be undone.</p>
          <div class="trash__actions">
            <button
              :id="`trash-confirm-${entry.id}`"
              type="button"
              class="button button--secondary trash__danger"
              :disabled="busyId !== null"
              @click="deleteForever(entry)"
            >
              {{ busyId === entry.id ? 'Deleting…' : 'Yes, delete forever' }}
            </button>
            <button type="button" class="button button--secondary" :disabled="busyId !== null" @click="keep(entry)">
              Keep it
            </button>
          </div>
        </div>
      </li>
    </ul>

    <p v-if="stillRemovingCount > 0" class="field-hint">
      {{
        stillRemovingCount === 1
          ? 'The files of 1 deleted document are still being removed in the background.'
          : `The files of ${stillRemovingCount} deleted documents are still being removed in the background.`
      }}
      Nobody can open them any more.
    </p>

    <RouterLink class="button button--secondary" to="/">Back to your documents</RouterLink>
  </section>
</template>

<style scoped>
.trash {
  inline-size: 100%;
  max-inline-size: 42rem;
  margin-inline: auto;
  margin-block-start: var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
}

.trash__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: 3rem;
  block-size: 3rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  color: var(--color-cocoa-strong);
}

.trash__title {
  margin: 0;
  font-size: var(--font-size-xl);
}

.trash__notice {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

/* The default link blue measures 4.48:1 on this wash, just under AA; the text colour is 11.96:1 and the underline still says "link". */
.trash__notice a {
  color: var(--color-text);
}

.trash__list {
  list-style: none;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.trash__item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.trash__text {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-inline-size: 0;
  flex: 1 1 14rem;
}

.trash__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.trash__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.trash__actions .button {
  gap: var(--space-2);
}

.trash__confirm {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  flex: 1 1 100%;
}

.trash__question {
  margin: 0;
  font-weight: 500;
}

/* The error red on the white surface measures about 5.9:1, so the label stays readable as text, not only as a colour. */
.trash__danger {
  border-color: var(--color-error);
  color: var(--color-error);
}
</style>
