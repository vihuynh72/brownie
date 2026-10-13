<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
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
 * control lives in a panel the person can close, and a copy that finishes
 * after it was closed must still reach the document's list of sources.
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
/** The row's name, spoken as the button's name and drawn as its title, so the two cannot drift apart. */
const TITLE = 'Copy a file from Google Drive'
/** What choosing files lets Brownie read, said the same way wherever it is said. */
const CHOOSE_WORDS =
  "Choose files in Google's own file picker. Brownie can then read only those files, and only Google Docs and plain-text (.txt) files, and copies one only when you ask."

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
  <div v-if="offered" class="drive-picker" :class="{ 'drive-picker--open': open }">
    <!--
      The whole row is the button. Its name is the title alone, as it is drawn; the sentence under the title is what
      the button says it does, read after the name rather than run into it.
    -->
    <button
      type="button"
      class="drive-picker__toggle"
      :aria-label="TITLE"
      aria-describedby="drive-picker-about"
      :aria-expanded="open"
      aria-controls="drive-picker-panel"
      @click="toggle"
    >
      <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="cloud" :size="18" /></span>
      <span class="drive-picker__words">
        <span class="drive-picker__title">{{ TITLE }}</span>
        <span id="drive-picker-about" class="drive-picker__about"
          >Google Docs and .txt files you choose in Google Drive, up to 10 at a time.</span
        >
      </span>
      <AppIcon class="drive-picker__chevron" :name="open ? 'chevron-down' : 'chevron-right'" :size="18" />
    </button>

    <div v-show="open" id="drive-picker-panel" class="drive-picker__panel">
      <p v-if="notice" ref="noticeElement" class="drive-picker__notice" role="status" tabindex="-1">
        <AppIcon name="check-circle" :size="18" />
        <span>{{ notice }}</span>
      </p>
      <p v-if="error" class="drive-picker__problem field-error" role="alert">
        <AppIcon name="alert-circle" :size="16" />
        <span>{{ error }}</span>
      </p>

      <p v-if="connectionState === 'loading'" class="drive-picker__progress field-hint" aria-live="polite">
        <span class="activity-indicator" aria-hidden="true"></span>Checking your Google Drive connection…
      </p>

      <template v-else-if="connectionState === 'loaded'">
        <template v-if="files.length > 0">
          <p class="field-hint">
            {{ CHOOSE_WORDS }}
            <RouterLink to="/connections">More about connections</RouterLink>
          </p>
          <ul class="drive-picker__files">
            <li v-for="file in files" :key="file.id" class="source-chip drive-picker__file">
              <span class="source-chip__text">
                <span class="source-chip__name">{{ nameOf(file) }}</span>
                <span class="source-chip__meta">Chosen {{ dayFormatter.format(new Date(file.grantedAt)) }}</span>
              </span>
              <span class="drive-picker__actions">
                <button
                  :id="`drive-copy-${file.id}`"
                  type="button"
                  class="button button--secondary button--small"
                  :disabled="busyWith !== null"
                  @click="copy(file)"
                >
                  <!-- The space lives outside the hidden span: inside it, the compiler trims it and the name runs together. -->
                  <span>{{ busyWith === file.id && !forgetting ? 'Copying…' : 'Copy' }} <span class="visually-hidden">{{ nameOf(file) }}</span></span>
                </button>
                <button
                  :id="`drive-forget-${file.id}`"
                  type="button"
                  class="button button--secondary button--small"
                  :disabled="busyWith !== null"
                  @click="forget(file)"
                >
                  <span>Stop reading <span class="visually-hidden">{{ nameOf(file) }}</span></span>
                </button>
              </span>
            </li>
          </ul>
          <button id="drive-pick" type="button" class="button button--secondary button--small" :disabled="busyWith !== null" @click="pick">
            {{ busyWith === 'pick' ? "Opening Google's file picker…" : 'Choose more files in Google Drive' }}
          </button>
        </template>

        <!-- Nothing to copy yet: not connected, waiting to be connected again, or connected with no file chosen. -->
        <div v-else class="msg-card drive-picker__card">
          <div class="msg-card__section drive-picker__choose">
            <p v-if="connection !== null && connection.state !== 'ACTIVE'">{{ stateSentence(connection) }}</p>
            <p v-else-if="connection?.state === 'ACTIVE'">No files chosen yet.</p>
            <p class="field-hint">
              {{ CHOOSE_WORDS }}
              <RouterLink to="/connections">More about connections</RouterLink>
            </p>
            <button id="drive-pick" type="button" class="button button--primary button--small" :disabled="busyWith !== null" @click="pick">
              {{ busyWith === 'pick' ? "Opening Google's file picker…" : 'Choose files in Google Drive' }}
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/*
 * One row of the Add a source panel: a tile, what the row does, and a chevron. Opening it gives the row the quiet inset
 * shade and shows its content underneath, lined up with the words and clear of the tile.
 */
.drive-picker {
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
}

/* Two open rows would run into one shade, so an open row keeps a little white between it and its neighbours. */
.drive-picker--open {
  margin-block: 0.25rem;
  background: var(--color-paper-soft);
}

/* The edge is transparent so a forced-colours display can still draw it; the padding is a pixel less to make room. */
.drive-picker__toggle {
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
.drive-picker__toggle[aria-expanded='false']:hover {
  background: var(--color-paper-soft);
}

.drive-picker__words {
  flex: 1 1 auto;
  display: grid;
  gap: 0.125rem;
  min-inline-size: 0;
}

.drive-picker__title {
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.drive-picker__about {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.35;
}

.drive-picker__chevron {
  flex: none;
  color: var(--color-text-muted);
}

.drive-picker__panel {
  display: grid;
  justify-items: start;
  gap: 0.625rem;
  padding-block: 0.125rem 0.75rem;
  padding-inline: 3.1875rem 0.75rem;
}

.drive-picker__panel > * {
  min-inline-size: 0;
}

.drive-picker__panel p {
  margin: 0;
}

/* What was just done, said where the keyboard lands. */
.drive-picker__notice {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  inline-size: 100%;
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-control);
  background: var(--color-success-wash);
  line-height: 1.4;
}

.drive-picker__notice > svg {
  margin-block-start: 0.0625rem;
  color: var(--color-success);
}

.drive-picker__problem,
.drive-picker__progress {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}

.drive-picker__problem > svg {
  margin-block-start: 0.0625rem;
}

.drive-picker__progress {
  align-items: center;
}

/* Nothing to copy yet: a quiet card with what choosing files means and the way to do it. */
.drive-picker__card {
  inline-size: 100%;
}

.drive-picker__choose {
  display: grid;
  justify-items: start;
  gap: 0.5rem;
}

.drive-picker__choose p {
  line-height: 1.45;
}

.drive-picker__choose .button {
  margin-block-start: 0.25rem;
}

.drive-picker__files {
  display: grid;
  gap: 0.5rem;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* A file's name wraps instead of being cut short, and its buttons drop below it on a narrow panel. */
.drive-picker__file {
  flex-wrap: wrap;
  justify-content: space-between;
  inline-size: 100%;
}

.drive-picker__file .source-chip__text {
  flex: 1 1 12rem;
}

.drive-picker__file .source-chip__name {
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  overflow-wrap: anywhere;
}

.drive-picker__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
</style>
