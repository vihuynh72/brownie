<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import ChatAppsPanel from '@/components/ChatAppsPanel.vue'
import type { IconName } from '@/components/icons'
import { useSessionStore } from '@/stores/session'
import { loadCapabilities } from '@/capabilities'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import { describeCommonFailure } from '@/api/failures'
import {
  ApiRequestError,
  disconnectGoogle,
  forgetDriveFile,
  listConnections,
  startDrivePick,
  startGoogleConsent,
  type ConnectionResponse,
  type ConnectorAccess,
  type ResourceGrantResponse,
} from '@/api/client'
import {
  ACCESS_NAMES,
  PERMISSION_WORDS,
  consentOutcome,
  describeConnectorFailure,
  disconnectSentence,
  driveBrownieWords,
  latestConnection,
  mentionFile,
  offeredAccess,
  stateSentence,
} from '@/connections/words'

/**
 * The accounts outside Brownie a person lets it read from, or add to on
 * their approval: for each, where it stands, the permission in Google's own
 * words beside what Brownie itself does with it, what Brownie may read
 * through it, and the way to connect, reconnect or disconnect. Google sends
 * the person back here after its consent page, and what it said is shown
 * once and then taken out of the address, so reloading or sharing the
 * address does not repeat it.
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const connections = ref<ConnectionResponse[]>([])
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const loadError = ref<string | null>(null)
/** What this deployment offers to connect; null when the server did not say. */
const offered = ref<ConnectorAccess[] | null>(null)
/** Whether this deployment serves chat apps (MCP) at all, and the address to give them when it does. */
const mcpEnabled = ref(false)
const mcpServerUrl = ref<string | null>(null)

const outcome = ref<{ tone: 'success' | 'failure'; text: string } | null>(null)
const outcomeElement = ref<HTMLElement | null>(null)
const busy = ref<'connect' | 'disconnect' | 'forget' | null>(null)
const actionError = ref<string | null>(null)
const confirmingDisconnect = ref(false)

const calendar = computed(() => latestConnection(connections.value, 'CALENDAR_EVENTS'))
const drive = computed(() => latestConnection(connections.value, 'DRIVE_FILES'))
const saving = computed(() => latestConnection(connections.value, 'DRIVE_SAVING'))
const adding = computed(() => latestConnection(connections.value, 'CALENDAR_EVENT_CREATION'))
const anyOpen = computed(() => connections.value.some((connection) => connection.state !== 'DISCONNECTED'))
/** Drive is shown only where it exists, or once this deployment offers it: until then there is nothing to use it for. */
const calendarOffered = computed(() => offered.value?.includes('CALENDAR_EVENTS') ?? false)
const driveOffered = computed(() => offered.value?.includes('DRIVE_FILES') ?? false)
const showDrive = computed(() => drive.value !== null || driveOffered.value)
const savingOffered = computed(() => offered.value?.includes('DRIVE_SAVING') ?? false)
/** Saving is its own connection, shown where it exists or is offered; its card then says Brownie may change something, on approval. */
const showSaving = computed(() => saving.value !== null || savingOffered.value)
const addingOffered = computed(() => offered.value?.includes('CALENDAR_EVENT_CREATION') ?? false)
const showAdding = computed(() => adding.value !== null || addingOffered.value)
/** Whether any connection here can change something, on approval; what disconnecting leaves behind then says so. */
const showWrites = computed(() => showSaving.value || showAdding.value)
/** The files Brownie may read, from the open Drive connection only: disconnecting forgets them all. */
const pickedFiles = computed<ResourceGrantResponse[]>(() =>
  drive.value !== null && drive.value.state !== 'DISCONNECTED' ? (drive.value.grants ?? []).filter((grant) => grant.type === 'DRIVE_FILE') : [],
)
const nothingOffered = computed(() => offered.value !== null && offered.value.length === 0 && connections.value.length === 0)

const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function day(value: string): string {
  return dayFormatter.format(new Date(value))
}

/**
 * What a card's chip says about one connection: a word and an icon as well as a colour, so the state is never
 * shown by colour alone. A connection Google stopped accepting is "Paused", waiting for the person to connect again.
 */
function standing(connection: ConnectionResponse | null): { label: string; tone: string; icon: IconName | null } {
  switch (connection?.state) {
    case 'ACTIVE':
      return { label: 'Connected', tone: 'tag--success', icon: 'check-circle' }
    case 'RECONNECT_REQUIRED':
      return { label: 'Paused', tone: 'tag--honey', icon: 'warning' }
    default:
      return { label: 'Not connected', tone: '', icon: null }
  }
}

function paused(connection: ConnectionResponse | null): boolean {
  return connection?.state === 'RECONNECT_REQUIRED'
}

function canConnect(access: ConnectorAccess, connection: ConnectionResponse | null): boolean {
  if (!(offered.value?.includes(access) ?? false)) {
    return false
  }
  return connection === null || connection.state !== 'ACTIVE'
}

async function readOutcome(): Promise<void> {
  const found = consentOutcome(route.query)
  if (found === null) {
    return
  }
  outcome.value = { tone: found.tone, text: found.text }
  // Said once: the address goes back to the plain page, so a reload or a shared link does not say it again.
  await router.replace({ path: route.path })
  await nextTick()
  outcomeElement.value?.focus()
}

async function load(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || session.status === 'anonymous') {
    return
  }
  loadState.value = 'loading'
  loadError.value = null
  try {
    connections.value = await listConnections(workspaceId)
    loadState.value = 'loaded'
  } catch (error) {
    loadState.value = 'error'
    loadError.value =
      describeConnectorFailure(error, 'connected accounts') ??
      'Brownie could not load your connected accounts. If this keeps happening, let whoever runs this Brownie know.'
  }
  // What can be connected is a nicety on top of the list; without it the page offers nothing new, and says why.
  try {
    const capabilities = await loadCapabilities()
    offered.value = offeredAccess(capabilities)
    mcpEnabled.value = capabilities.mcpEnabled === true
    mcpServerUrl.value = capabilities.mcpServerUrl ?? null
  } catch {
    offered.value = null
  }
}

onMounted(async () => {
  await readOutcome()
  await load()
})
watch(
  () => session.personalWorkspaceId,
  (workspaceId, previous) => {
    if (workspaceId !== undefined && workspaceId !== previous) {
      void load()
    }
  },
)

async function connect(access: ConnectorAccess): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busy.value !== null) {
    return
  }
  busy.value = 'connect'
  actionError.value = null
  try {
    const started = await startGoogleConsent(workspaceId, access, '/connections')
    // Google's page, then back here: the whole page leaves, so nothing else happens on this one, unless it does not.
    navigateTo(started.authorizationUrl)
    releaseIfStillHere(() => {
      busy.value = null
    })
  } catch (error) {
    actionError.value = `Could not start connecting ${ACCESS_NAMES[access]}. ${
      describeConnectorFailure(error, 'a way to connect Google accounts') ?? 'Try again.'
    }`
    busy.value = null
  }
}

/**
 * Picking files in Google's own file picker; it also connects Google Drive, or connects it again, the way connecting
 * does. Google sends the person back here, and what was added is said at the top of the page.
 */
async function pickFiles(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busy.value !== null) {
    return
  }
  busy.value = 'connect'
  actionError.value = null
  try {
    const started = await startDrivePick(workspaceId, '/connections')
    navigateTo(started.authorizationUrl)
    releaseIfStillHere(() => {
      busy.value = null
    })
  } catch (error) {
    actionError.value = `Could not open Google's file picker. ${
      describeConnectorFailure(error, "a way to open Google's file picker", 'DRIVE_FILES') ?? 'Try again.'
    }`
    busy.value = null
  }
}

async function forgetFile(file: ResourceGrantResponse): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busy.value !== null) {
    return
  }
  busy.value = 'forget'
  actionError.value = null
  const named = mentionFile(file.displayName)
  const Named = mentionFile(file.displayName, true)
  try {
    connections.value = await forgetDriveFile(workspaceId, file.id)
    outcome.value = { tone: 'success', text: `Brownie will no longer read ${named}. Copies already made from it stay where they are.` }
    busy.value = null
    await nextTick()
    outcomeElement.value?.focus()
  } catch (error) {
    busy.value = null
    if (error instanceof ApiRequestError && !error.routeMissing && error.problem?.code === 'CONNECTOR_RESOURCE_NOT_FOUND') {
      // Already off the list (taken back in another tab, or by Google): what was asked is done. Read the list again to show it.
      outcome.value = { tone: 'success', text: `${Named} was already off your list, so Brownie no longer reads it.` }
      await load()
      await nextTick()
      outcomeElement.value?.focus()
      return
    }
    actionError.value = `${Named} is still on your list. ${
      describeConnectorFailure(error, 'a way to take a file off your list', 'DRIVE_FILES') ?? 'Try again.'
    }`
    await nextTick()
    document.getElementById(`connections-forget-${file.id}`)?.focus()
  }
}

/**
 * Development builds only, and only until this Brownie offers Google Drive: a way to find out, on this Brownie's own
 * Google registration, how Google's file picker answers. It works against the earlier test build of the server, which
 * only counts the files that come back; a server that records picked files refuses picks until Drive is offered.
 */
const pickerTestVisible = computed(() => import.meta.env.DEV && (offered.value?.length ?? 0) > 0 && !driveOffered.value)

async function tryPicker(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busy.value !== null) {
    return
  }
  busy.value = 'connect'
  actionError.value = null
  try {
    const started = await startDrivePick(workspaceId, '/connections')
    navigateTo(started.authorizationUrl)
    releaseIfStillHere(() => {
      busy.value = null
    })
  } catch (error) {
    actionError.value =
      error instanceof ApiRequestError && error.routeMissing
        ? 'The Brownie server that answered does not have the file picker test.'
        : error instanceof ApiRequestError && error.problem?.code === 'CONNECTOR_NOT_CONFIGURED'
          ? 'This Brownie server records the files you pick, so it refuses this test until Google Drive is offered here.'
          : `Could not open Google's file picker. ${
              describeConnectorFailure(error, "a way to open Google's file picker", 'DRIVE_FILES') ?? 'Try again.'
            }`
    busy.value = null
  }
}

/** What a chat-app action did, said once in the page's own status line. */
async function chatAppOutcome(payload: { tone: 'success' | 'failure'; text: string }): Promise<void> {
  outcome.value = payload
  await nextTick()
  outcomeElement.value?.focus()
}

async function askToDisconnect(): Promise<void> {
  confirmingDisconnect.value = true
  actionError.value = null
  await nextTick()
  document.getElementById('connections-disconnect-confirm')?.focus()
}

async function keepConnected(): Promise<void> {
  confirmingDisconnect.value = false
  await nextTick()
  document.getElementById('connections-disconnect')?.focus()
}

async function disconnect(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || busy.value !== null) {
    return
  }
  busy.value = 'disconnect'
  actionError.value = null
  const knownBefore = new Map(connections.value.map((connection) => [connection.id, connection.state]))
  try {
    connections.value = await disconnectGoogle(workspaceId)
    confirmingDisconnect.value = false
    outcome.value = { tone: 'success', text: disconnectSentence(knownBefore, connections.value) }
    busy.value = null
    await nextTick()
    outcomeElement.value?.focus()
  } catch (error) {
    actionError.value = `Nothing was disconnected. ${
      describeCommonFailure(error, 'a way to disconnect Google accounts') ?? 'Try again.'
    }`
    busy.value = null
    await nextTick()
    document.getElementById('connections-disconnect-confirm')?.focus()
  }
}
</script>

<template>
  <!-- The router sends a signed-out visitor to the sign-in page before this view mounts; this is what shows if a session ends while it is open. -->
  <section v-if="session.status === 'anonymous'" class="page-column page-column--narrow">
    <div class="panel connections__signin">
      <p>Sign in to see the accounts you have connected to Brownie.</p>
      <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/connections' } }">Sign in</RouterLink>
    </div>
  </section>

  <section v-else class="page-column page-column--wide connections" aria-labelledby="connections-heading">
    <header class="page-head">
      <h1 id="connections-heading" class="page-title">Connections</h1>
      <p class="page-lede">
        Brownie uses your Google account only where you allow it here, and only when you ask for it inside a document.
      </p>
    </header>

    <div class="connections__stack">
      <p
        v-if="outcome"
        ref="outcomeElement"
        class="connections__banner"
        :class="outcome.tone === 'success' ? 'connections__banner--success' : 'connections__banner--error'"
        :role="outcome.tone === 'success' ? 'status' : 'alert'"
        tabindex="-1"
      >
        <AppIcon :name="outcome.tone === 'success' ? 'check-circle' : 'alert-circle'" :size="18" />
        <span>{{ outcome.text }}</span>
      </p>
      <p v-if="actionError" class="connections__banner connections__banner--error" role="alert">
        <AppIcon name="alert-circle" :size="18" />
        <span>{{ actionError }}</span>
      </p>

      <p v-if="loadState === 'loading'" class="connections__loading" aria-live="polite">
        <span class="activity-indicator" aria-hidden="true"></span>
        <span>Loading your connected accounts…</span>
      </p>
      <p v-else-if="loadState === 'error'" class="connections__banner connections__banner--error" role="alert">
        <AppIcon name="alert-circle" :size="18" />
        <span>{{ loadError }}</span>
      </p>

      <template v-if="loadState === 'loaded'">
        <p v-if="nothingOffered" class="panel connections__note">
          <AppIcon name="info" :size="16" />
          <span>Connecting other accounts is not set up on this Brownie.</span>
        </p>
        <p v-else-if="offered === null" class="panel connections__note">
          <AppIcon name="info" :size="16" />
          <span>The Brownie server that answered does not say which accounts can be connected, so none is offered here.</span>
        </p>

        <div v-if="!nothingOffered" class="connections__grid">
          <section
            class="panel account"
            :class="{ 'account--attention': paused(calendar) }"
            aria-labelledby="connections-calendar"
          >
            <div class="account__head">
              <span class="doc-tile" aria-hidden="true"><AppIcon name="calendar" :size="22" /></span>
              <h2 id="connections-calendar" class="account__title">{{ ACCESS_NAMES.CALENDAR_EVENTS }}</h2>
              <span class="tag" :class="standing(calendar).tone">
                <AppIcon v-if="standing(calendar).icon" :name="standing(calendar).icon!" :size="14" />
                {{ standing(calendar).label }}
              </span>
            </div>
            <p class="account__summary">{{ calendar ? stateSentence(calendar) : 'Not connected.' }}</p>
            <dl class="panel--quiet account__facts">
              <div class="account__fact">
                <dt>Google asks you to allow</dt>
                <dd>
                  "{{ PERMISSION_WORDS.CALENDAR_EVENTS.google }}"
                  <span class="account__also">{{ PERMISSION_WORDS.CALENDAR_EVENTS.alsoAsked }}</span>
                </dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie does</dt>
                <dd>{{ PERMISSION_WORDS.CALENDAR_EVENTS.brownie }}</dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie may read</dt>
                <dd v-if="calendar && (calendar.grants ?? []).length > 0">
                  <ul class="account__list">
                    <li v-for="grant in calendar.grants ?? []" :key="grant.id">
                      {{ grant.displayName ?? 'Your calendar' }}, since {{ day(grant.grantedAt) }}
                    </li>
                  </ul>
                </dd>
                <dd v-else>Nothing yet. Your calendar is added here the first time you list its events from a document.</dd>
              </div>
            </dl>
            <p v-if="calendar?.state === 'ACTIVE' && calendarOffered" class="field-hint">
              To copy an event, open a document, press Add a source (+) beside the message box and use "Copy an event from Google Calendar".
            </p>
            <p v-else-if="calendar?.state === 'ACTIVE'" class="field-hint">
              Copying events from Google Calendar is not offered on this Brownie at the moment. The connection stays until you
              disconnect it.
            </p>
            <div v-if="canConnect('CALENDAR_EVENTS', calendar)" class="account__actions">
              <button
                id="connections-connect-calendar"
                type="button"
                class="button button--primary button--small"
                :disabled="busy !== null"
                @click="connect('CALENDAR_EVENTS')"
              >
                <AppIcon name="calendar" :size="16" />
                {{ calendar?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Calendar again' : 'Connect Google Calendar' }}
              </button>
            </div>
          </section>

          <section
            v-if="showDrive"
            class="panel account"
            :class="{ 'account--attention': paused(drive) }"
            aria-labelledby="connections-drive"
          >
            <div class="account__head">
              <span class="doc-tile" aria-hidden="true"><AppIcon name="folder" :size="22" /></span>
              <h2 id="connections-drive" class="account__title">{{ ACCESS_NAMES.DRIVE_FILES }}</h2>
              <span class="tag" :class="standing(drive).tone">
                <AppIcon v-if="standing(drive).icon" :name="standing(drive).icon!" :size="14" />
                {{ standing(drive).label }}
              </span>
            </div>
            <p class="account__summary">{{ drive ? stateSentence(drive) : 'Not connected.' }}</p>
            <dl class="panel--quiet account__facts">
              <div class="account__fact">
                <dt>Google asks you to allow</dt>
                <dd>"{{ PERMISSION_WORDS.DRIVE_FILES.google }}"</dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie does</dt>
                <dd>{{ driveBrownieWords(driveOffered) }}</dd>
              </div>
              <div v-if="driveOffered || pickedFiles.length > 0" class="account__fact">
                <dt>What Brownie may read</dt>
                <dd v-if="pickedFiles.length > 0">
                  <ul class="account__list">
                    <li v-for="file in pickedFiles" :key="file.id" class="account__file">
                      <span class="account__file-name">{{ file.displayName ?? 'A file from Google Drive' }}, since {{ day(file.grantedAt) }}</span>
                      <button
                        :id="`connections-forget-${file.id}`"
                        type="button"
                        class="button button--secondary button--small"
                        :disabled="busy !== null"
                        @click="forgetFile(file)"
                      >
                        <span>Stop reading this file <span class="visually-hidden">{{ file.displayName ?? 'from Google Drive' }}</span></span>
                      </button>
                    </li>
                  </ul>
                  <span class="account__also">Copies already made from a file stay in your documents when you stop reading it.</span>
                </dd>
                <dd v-else>Nothing yet. Files you choose in Google's file picker are listed here.</dd>
              </div>
            </dl>
            <p v-if="drive?.state === 'ACTIVE' && driveOffered && pickedFiles.length > 0" class="field-hint">
              To copy a file, open a document, press Add a source (+) beside the message box and use "Copy a file from Google Drive".
            </p>
            <div v-if="driveOffered" class="account__actions">
              <button
                id="connections-pick-drive"
                type="button"
                class="button button--small"
                :class="drive?.state === 'ACTIVE' ? 'button--secondary' : 'button--primary'"
                :disabled="busy !== null"
                @click="pickFiles"
              >
                <AppIcon name="folder" :size="16" />
                {{
                  drive?.state === 'RECONNECT_REQUIRED'
                    ? 'Connect Google Drive again and choose files'
                    : pickedFiles.length > 0
                      ? 'Choose more files in Google Drive'
                      : 'Choose files in Google Drive'
                }}
              </button>
            </div>
          </section>

          <section
            v-if="showSaving"
            class="panel account"
            :class="{ 'account--attention': paused(saving) }"
            aria-labelledby="connections-saving"
          >
            <div class="account__head">
              <span class="doc-tile" aria-hidden="true"><AppIcon name="cloud" :size="22" /></span>
              <h2 id="connections-saving" class="account__title">{{ ACCESS_NAMES.DRIVE_SAVING }}</h2>
              <span class="tag" :class="standing(saving).tone">
                <AppIcon v-if="standing(saving).icon" :name="standing(saving).icon!" :size="14" />
                {{ standing(saving).label }}
              </span>
            </div>
            <p class="account__summary">{{ saving ? stateSentence(saving) : 'Not connected.' }}</p>
            <dl class="panel--quiet account__facts">
              <div class="account__fact">
                <dt>Google asks you to allow</dt>
                <dd>"{{ PERMISSION_WORDS.DRIVE_SAVING.google }}"</dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie does</dt>
                <dd>{{ PERMISSION_WORDS.DRIVE_SAVING.brownie }}</dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie may read</dt>
                <dd>
                  Which account this is, through Google Drive; a file it saved for you, to check it afterwards; and a Google Doc it
                  saved, in full, when you prepare adding text to it and again afterwards.
                </dd>
              </div>
            </dl>
            <p v-if="saving?.state === 'ACTIVE' && savingOffered" class="field-hint">
              To save a document, open Export on it; "Save to Google Drive" is there once it is exported.
            </p>
            <p v-else-if="saving?.state === 'ACTIVE'" class="field-hint">
              Saving to Google Drive is not offered on this Brownie at the moment. The connection stays until you disconnect it.
            </p>
            <div v-if="canConnect('DRIVE_SAVING', saving)" class="account__actions">
              <button
                id="connections-connect-saving"
                type="button"
                class="button button--primary button--small"
                :disabled="busy !== null"
                @click="connect('DRIVE_SAVING')"
              >
                <AppIcon name="cloud" :size="16" />
                {{ saving?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Drive for saving again' : 'Connect Google Drive for saving' }}
              </button>
            </div>
          </section>

          <section
            v-if="showAdding"
            class="panel account"
            :class="{ 'account--attention': paused(adding) }"
            aria-labelledby="connections-adding"
          >
            <div class="account__head">
              <span class="doc-tile" aria-hidden="true"><AppIcon name="calendar-add" :size="22" /></span>
              <h2 id="connections-adding" class="account__title">{{ ACCESS_NAMES.CALENDAR_EVENT_CREATION }}</h2>
              <span class="tag" :class="standing(adding).tone">
                <AppIcon v-if="standing(adding).icon" :name="standing(adding).icon!" :size="14" />
                {{ standing(adding).label }}
              </span>
            </div>
            <p class="account__summary">{{ adding ? stateSentence(adding) : 'Not connected.' }}</p>
            <dl class="panel--quiet account__facts">
              <div class="account__fact">
                <dt>Google asks you to allow</dt>
                <dd>
                  "{{ PERMISSION_WORDS.CALENDAR_EVENT_CREATION.google }}"
                  <span class="account__also">{{ PERMISSION_WORDS.CALENDAR_EVENT_CREATION.alsoAsked }}</span>
                </dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie does</dt>
                <dd>{{ PERMISSION_WORDS.CALENDAR_EVENT_CREATION.brownie }}</dd>
              </div>
              <div class="account__fact">
                <dt>What Brownie may read</dt>
                <dd>Which account this is, and an event it added for you, to check it after adding it.</dd>
              </div>
            </dl>
            <p v-if="adding?.state === 'ACTIVE' && addingOffered" class="field-hint">
              To add an event, open a document and use "Add an event to Google Calendar" in its Export window.
            </p>
            <p v-else-if="adding?.state === 'ACTIVE'" class="field-hint">
              Adding events to Google Calendar is not offered on this Brownie at the moment. The connection stays until you
              disconnect it.
            </p>
            <div v-if="canConnect('CALENDAR_EVENT_CREATION', adding)" class="account__actions">
              <button
                id="connections-connect-adding"
                type="button"
                class="button button--primary button--small"
                :disabled="busy !== null"
                @click="connect('CALENDAR_EVENT_CREATION')"
              >
                <AppIcon name="calendar-add" :size="16" />
                {{
                  adding?.state === 'RECONNECT_REQUIRED'
                    ? 'Connect Google Calendar for adding events again'
                    : 'Connect Google Calendar for adding events'
                }}
              </button>
            </div>
          </section>
        </div>

        <ChatAppsPanel
          v-if="mcpEnabled && session.personalWorkspaceId !== undefined"
          :workspace-id="session.personalWorkspaceId"
          :enabled="mcpEnabled"
          :server-url="mcpServerUrl"
          @outcome="chatAppOutcome"
        />

        <section v-if="pickerTestVisible" class="panel account" aria-labelledby="connections-picker-test">
          <h2 id="connections-picker-test" class="account__title">Google's file picker (test)</h2>
          <p class="field-hint">
            Only in development builds, and only with the earlier test build of the Brownie server, which counts the files
            Google's own file picker sends back and opens none of them. Google treats it as connecting Google Drive again. A
            Brownie server that records the files you pick refuses this test until Google Drive is offered here.
          </p>
          <div class="account__actions">
            <button
              id="connections-try-picker"
              type="button"
              class="button button--secondary button--small"
              :disabled="busy !== null"
              @click="tryPicker"
            >
              Try Google's file picker (test)
            </button>
          </div>
        </section>

        <div v-if="anyOpen" class="connections__disconnect">
          <template v-if="!confirmingDisconnect">
            <button
              id="connections-disconnect"
              type="button"
              class="button button--secondary button--small"
              :disabled="busy !== null"
              @click="askToDisconnect"
            >
              <AppIcon name="close-circle" :size="16" />
              Disconnect Google…
            </button>
            <p class="field-hint connections__disconnect-note">
              Disconnects every Google connection here. Brownie will need your agreement again to read anything. Copies already in
              your documents stay.
            </p>
          </template>
          <div
            v-else
            class="panel connections__confirm"
            role="group"
            aria-label="Disconnect Google"
            @keydown.esc="keepConnected"
          >
            <p id="connections-disconnect-question" class="connections__question">
              Disconnect every Google connection here? Brownie will need your agreement again to read anything.
            </p>
            <p class="connections__consequences">
              Brownie asks Google to take back the access it still holds, and deletes that access whatever Google answers.
              A connection waiting to be connected again holds none Brownie can use, so for that one Brownie cannot ask
              Google; remove Brownie in your Google account instead. Every file you chose in Google Drive is taken off your
              list too. Copies already brought into your documents stay with those documents, and each still says where it
              came from; they are never updated again. To remove a copy, delete every document it was copied into.
              <template v-if="showWrites">
                A change waiting for your approval can no longer be made, and one whose outcome is unknown can be asked about only
                once the same Google account is connected again. Files Brownie saved, text it added and events it added stay in
                your Google account.</template
              >
            </p>
            <div class="connections__actions">
              <button
                id="connections-disconnect-confirm"
                type="button"
                class="button button--danger button--small"
                aria-describedby="connections-disconnect-question"
                :disabled="busy !== null"
                @click="disconnect"
              >
                <AppIcon name="close-circle" :size="16" />
                {{ busy === 'disconnect' ? 'Disconnecting…' : 'Yes, disconnect' }}
              </button>
              <button type="button" class="button button--secondary button--small" :disabled="busy !== null" @click="keepConnected">
                Keep connected
              </button>
            </div>
          </div>
        </div>

        <p class="field-hint">
          You can also see or remove Brownie's access in your Google account, under
          <a href="https://myaccount.google.com/connections" target="_blank" rel="noopener noreferrer">third-party connections</a>.
        </p>
      </template>

      <div class="connections__back">
        <RouterLink class="button button--quiet button--small" to="/">
          <AppIcon name="arrow-left" :size="16" />
          Back to your documents
        </RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* The title's line starts 58px below the top of the page region, which the shell already pads by its own gutter: the shared column's 3.5rem on top of that sits it too low. */
.connections {
  padding-block-start: 2.125rem;
}

.connections__signin {
  display: grid;
  justify-items: start;
  gap: var(--space-4);
}

.connections__signin > p {
  margin: 0;
}

/* Everything below the title and its line sits in one column, so the page keeps the same 20px between its parts. */
.connections__stack {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 1.25rem;
  margin-block-start: 1.25rem;
}

.connections__stack > .field-hint {
  margin: 0;
}

/* What Google answered, or what failed: a tinted band whose icon and words say the same thing the colour does. */
.connections__banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin: 0;
  padding: 0.625rem 0.875rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  line-height: 1.45;
}

.connections__banner > svg {
  margin-block-start: 0.0625rem;
}

.connections__banner--success {
  background: var(--color-success-wash);
  color: var(--color-success);
}

.connections__banner--error {
  background: var(--color-error-wash);
  color: var(--color-error);
}

.connections__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.connections__note {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin: 0;
  padding: 0.75rem 1rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.45;
}

.connections__note > svg {
  flex: none;
  margin-block-start: 0.125rem;
}

/*
 * Two columns where each card keeps room for its table, one where it would not. The tracks are as wide as the
 * smallest card that still reads well, so the page decides by its own width, not by the window's.
 */
.connections__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 26rem), 1fr));
  gap: 1rem 1.125rem;
}

/*
 * One account: a chip beside its name, where it stands, the facts in a quiet table, and the one thing to do next
 * at the bottom, so the buttons in a row of cards line up even when their tables differ in length.
 */
.account {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  /* The table inside lays itself out by the card's width, which depends on the page and not on the window. */
  container-type: inline-size;
}

/* A connection waiting for the person: the same card with a honey edge, a pixel and a half thick so it stands out from the plain hairline of the others. */
.account--attention {
  border-color: var(--color-honey);
  box-shadow: inset 0 0 0 0.5px var(--color-honey);
}

/* The tile, the name and the chip on one line; a name that is long wraps inside its own column and never under the chip. */
.account__head {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2) var(--space-3);
}

.account__title {
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

/* The state chip carries its icon a little smaller, with a little more air, than a chip that only labels. */
.account__head > .tag {
  justify-self: end;
  gap: 0.3125rem;
}

.account__head > .tag > svg {
  inline-size: 0.875rem;
  block-size: 0.875rem;
}

/* So little room that the name would be squeezed to a few letters: the chip goes on a line of its own, under the name. */
@container (max-width: 15rem) {
  .account__head {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .account__head > .tag {
    grid-column: 2;
    justify-self: start;
  }
}

.account__summary {
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.account > .field-hint {
  margin: 0;
}

/*
 * The facts are label and value rows in an inset panel, separated by hairlines. Narrow, the label sits above its value;
 * wide enough, the labels share a column so the values line up.
 */
.account__facts {
  margin: 0;
  padding: 0;
}

.account__fact {
  display: grid;
  gap: var(--space-1);
  padding: 0.5625rem 0.75rem;
}

.account__fact + .account__fact {
  border-block-start: 1px solid var(--color-hairline);
}

.account__fact > dt {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  /* Tighter than the value beside it, so the label's first line sits a little above the value's, as a caption does. */
  line-height: 1.2;
}

.account__fact > dd {
  margin: 0;
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

@container (min-width: 25rem) {
  .account__fact {
    grid-template-columns: 9.375rem minmax(0, 1fr);
    column-gap: var(--space-3);
  }
}

.account__also {
  display: block;
  margin-block-start: var(--space-1);
  color: var(--color-text-muted);
}

/* More room between one item and the next than inside an item, so a file and its button read as a pair. */
.account__list {
  display: grid;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.account__list + .account__also {
  margin-block-start: var(--space-3);
}

.account__file {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.account__file-name {
  min-inline-size: 0;
  overflow-wrap: anywhere;
}

.account__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-block-start: auto;
}

/* The way out, with what it does in a line beside it; asking to disconnect swaps both for the question and what follows. */
.connections__disconnect {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.connections__disconnect-note {
  margin: 0;
}

.connections__confirm {
  display: grid;
  gap: var(--space-3);
  inline-size: 100%;
  border-color: var(--color-error);
}

.connections__question {
  margin: 0;
  color: var(--color-error);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.connections__consequences {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.5;
}

.connections__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.connections__back {
  display: flex;
}
</style>
