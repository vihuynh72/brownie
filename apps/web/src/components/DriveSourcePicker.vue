<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { loadCapabilities } from '@/capabilities'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import {
  ApiRequestError,
  forgetDriveFile,
  listConnections,
  startDrivePick,
  type ConnectionResponse,
  type DriveImportResponse,
  type ResourceGrantResponse,
} from '@/api/client'
import {
  describeConnectorFailure,
  describeDriveCopyFailure,
  latestConnection,
  mentionFile,
  offeredAccess,
  stateSentence,
} from '@/connections/words'

/**
 * Copying a file the person picked in their Google Drive into this document
 * as a source. Files are chosen in Google's own file picker, never listed
 * here: this control shows only the files already on the person's list, and
 * copies only the one they choose. Offered only where this Brownie offers
 * Google Drive at all.
 *
 * The copy itself is made by the page ({@code copyFile}), not here: this
 * control lives in a tab, and a copy that finishes after the tab was left
 * must still reach the document's list of sources.
 */
const props = defineProps<{
  workspaceId: number
  documentId: number
  /** Open from the start: Google has just sent the person back here from its picker, and the page has said what it answered. */
  startOpen: boolean
  /** Changes not saved yet: picking leaves the page, so it waits for them rather than have the browser ask. */
  unsavedWork: boolean
  copyFile: (grantId: number) => Promise<DriveImportResponse>
}>()
/** Told when the person does anything here, so the page can take down what it said about Google's answer. */
const emit = defineEmits<{ used: [] }>()

const offered = ref(false)
const open = ref(false)
const connection = ref<ConnectionResponse | null>(null)
const connectionState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const busyWith = ref<number | 'pick' | null>(null)
const forgetting = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
const noticeElement = ref<HTMLElement | null>(null)

const files = computed<ResourceGrantResponse[]>(() =>
  connection.value?.state === 'ACTIVE' ? (connection.value.grants ?? []).filter((grant) => grant.type === 'DRIVE_FILE') : [],
)

const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function nameOf(file: ResourceGrantResponse): string {
  return file.displayName ?? 'A file from Google Drive'
}

onMounted(async () => {
  try {
    offered.value = offeredAccess(await loadCapabilities())?.includes('DRIVE_FILES') ?? false
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
  // Asked again on every opening: files may have been picked or forgotten on another page since.
  if (open.value && busyWith.value === null) {
    error.value = null
    await loadConnection()
  }
}

async function loadConnection(): Promise<void> {
  connectionState.value = 'loading'
  try {
    connection.value = latestConnection(await listConnections(props.workspaceId), 'DRIVE_FILES')
    connectionState.value = 'loaded'
  } catch (failure) {
    connectionState.value = 'error'
    error.value =
      describeConnectorFailure(failure, 'connected accounts', 'DRIVE_FILES') ??
      'Brownie could not check your Google Drive connection. Try again.'
  }
}

/** A failure after which the list is not what this control last saw: asked again, so it offers only what can still be copied. */
async function recheckAfter(failure: unknown): Promise<void> {
  if (!(failure instanceof ApiRequestError) || failure.routeMissing) {
    return
  }
  const code = failure.problem?.code
  const reason = failure.problem?.reason
  if (
    code === 'CONNECTION_RECONNECT_REQUIRED' ||
    code === 'CONNECTION_NOT_FOUND' ||
    code === 'CONNECTOR_RESOURCE_NOT_FOUND' ||
    (code === 'CONNECTOR_RESOURCE_UNAVAILABLE' && (reason === 'GONE' || reason === 'ACCESS_LOST'))
  ) {
    await loadConnection()
  }
}

async function pick(): Promise<void> {
  if (busyWith.value !== null) {
    return
  }
  emit('used')
  if (props.unsavedWork) {
    error.value = "This document has changes that are not saved yet. Choosing files takes you to Google's page, so choose once they are saved."
    return
  }
  busyWith.value = 'pick'
  error.value = null
  try {
    const started = await startDrivePick(props.workspaceId, `/documents/${props.documentId}`)
    // Google's picker, then back to this document; the button works again if Back brings this page back.
    navigateTo(started.authorizationUrl)
    releaseIfStillHere(() => {
      busyWith.value = null
    })
  } catch (failure) {
    error.value = `Could not open Google's file picker. ${
      describeConnectorFailure(failure, "a way to open Google's file picker", 'DRIVE_FILES') ?? 'Try again.'
    }`
    busyWith.value = null
  }
}

async function copy(file: ResourceGrantResponse): Promise<void> {
  if (busyWith.value !== null) {
    return
  }
  emit('used')
  busyWith.value = file.id
  error.value = null
  notice.value = null
  let copied = false
  try {
    const result = await props.copyFile(file.id)
    copied = true
    const asText = result.source.origin?.conversion === 'GOOGLE_DOC_AS_TEXT'
    await say(
      result.newCopy
        ? `Copied ${mentionFile(file.displayName)} into this document's sources${asText ? ', as the text Google exports it as' : ''}.`
        : `${mentionFile(file.displayName, true)} had already been copied as it is now, so this document uses that copy.`,
    )
  } catch (failure) {
    error.value = describeDriveCopyFailure(failure, file.displayName)
    await recheckAfter(failure)
  } finally {
    busyWith.value = null
  }
  if (!copied) {
    await nextTick()
    ;(window.document.getElementById(`drive-copy-${file.id}`) ?? window.document.getElementById('drive-pick'))?.focus()
  }
}

async function forget(file: ResourceGrantResponse): Promise<void> {
  if (busyWith.value !== null) {
    return
  }
  emit('used')
  busyWith.value = file.id
  forgetting.value = true
  error.value = null
  notice.value = null
  let done = false
  try {
    connection.value = latestConnection(await forgetDriveFile(props.workspaceId, file.id), 'DRIVE_FILES')
    done = true
    await say(`Brownie will no longer read ${mentionFile(file.displayName)}. Copies already made from it stay where they are.`)
  } catch (failure) {
    if (failure instanceof ApiRequestError && !failure.routeMissing && failure.problem?.code === 'CONNECTOR_RESOURCE_NOT_FOUND') {
      // Already off the list (taken back elsewhere, or by Google): what was asked is done. Read the list again to show it.
      done = true
      await loadConnection()
      await say(`${mentionFile(file.displayName, true)} was already off your list, so Brownie no longer reads it.`)
    } else {
      error.value = `Brownie still has ${mentionFile(file.displayName)} on your list. ${
        describeConnectorFailure(failure, 'a way to take a file off your list', 'DRIVE_FILES') ?? 'Try again.'
      }`
      await recheckAfter(failure)
    }
  } finally {
    busyWith.value = null
    forgetting.value = false
  }
  if (!done) {
    await nextTick()
    ;(window.document.getElementById(`drive-forget-${file.id}`) ?? window.document.getElementById('drive-pick'))?.focus()
  }
}
</script>

<template>
  <div v-if="offered" class="drive-picker">
    <button type="button" class="button button--secondary" :aria-expanded="open" aria-controls="drive-picker-panel" @click="toggle">
      Copy a file from Google Drive
    </button>

    <div v-show="open" id="drive-picker-panel" class="drive-picker__panel">
      <p v-if="notice" ref="noticeElement" class="drive-picker__notice" role="status" tabindex="-1">{{ notice }}</p>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>

      <p v-if="connectionState === 'loading'" class="field-hint" aria-live="polite">Checking your Google Drive connection…</p>

      <template v-else-if="connectionState === 'loaded'">
        <p v-if="connection !== null && connection.state !== 'ACTIVE'" class="field-hint">{{ stateSentence(connection) }}</p>
        <p class="field-hint">
          Choose files in Google's own file picker. Brownie can then read only those files, and only Google Docs and plain-text
          (.txt) files, and copies one only when you ask.
          <RouterLink to="/connections">More about connections</RouterLink>
        </p>
        <ul v-if="files.length > 0" class="drive-picker__files">
          <li v-for="file in files" :key="file.id" class="drive-picker__file">
            <div class="drive-picker__text">
              <span class="drive-picker__name">{{ nameOf(file) }}</span>
              <span class="field-hint">Chosen {{ dayFormatter.format(new Date(file.grantedAt)) }}</span>
            </div>
            <div class="drive-picker__actions">
              <button :id="`drive-copy-${file.id}`" type="button" class="button button--secondary" :disabled="busyWith !== null" @click="copy(file)">
                <!-- The space lives outside the hidden span: inside it, the compiler trims it and the name runs together. -->
                <span>{{ busyWith === file.id && !forgetting ? 'Copying…' : 'Copy' }} <span class="visually-hidden">{{ nameOf(file) }}</span></span>
              </button>
              <button
                :id="`drive-forget-${file.id}`"
                type="button"
                class="button button--secondary"
                :disabled="busyWith !== null"
                @click="forget(file)"
              >
                <span>Stop reading <span class="visually-hidden">{{ nameOf(file) }}</span></span>
              </button>
            </div>
          </li>
        </ul>
        <p v-else-if="connection?.state === 'ACTIVE'" class="field-hint">No files chosen yet.</p>
        <button id="drive-pick" type="button" class="button button--primary" :disabled="busyWith !== null" @click="pick">
          {{ busyWith === 'pick' ? "Opening Google's file picker…" : files.length > 0 ? 'Choose more files in Google Drive' : 'Choose files in Google Drive' }}
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.drive-picker {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  margin-block: var(--space-3);
}

.drive-picker__panel {
  inline-size: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.drive-picker__panel p {
  margin: 0;
}

.drive-picker__notice {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

.drive-picker__files {
  list-style: none;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.drive-picker__file {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.drive-picker__text {
  display: flex;
  flex-direction: column;
  min-inline-size: 0;
  flex: 1 1 12rem;
}

.drive-picker__name {
  font-weight: 500;
  overflow-wrap: anywhere;
}

.drive-picker__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
</style>
