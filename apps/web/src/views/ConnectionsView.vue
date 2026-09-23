<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { loadCapabilities } from '@/capabilities'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import { describeCommonFailure } from '@/api/failures'
import {
  disconnectGoogle,
  listConnections,
  startGoogleConsent,
  type ConnectionResponse,
  type ConnectorAccess,
} from '@/api/client'
import {
  ACCESS_NAMES,
  PERMISSION_WORDS,
  consentOutcome,
  describeConnectorFailure,
  disconnectSentence,
  latestConnection,
  offeredAccess,
  stateSentence,
} from '@/connections/words'

/**
 * The accounts outside Brownie a person lets it read from: for each, where
 * it stands, the permission in Google's own words beside what Brownie itself
 * does with it, what Brownie may read through it, and the way to connect,
 * reconnect or disconnect. Google sends the person back here after its
 * consent page, and what it said is shown once and then taken out of the
 * address, so reloading or sharing the address does not repeat it.
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const connections = ref<ConnectionResponse[]>([])
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const loadError = ref<string | null>(null)
/** What this deployment offers to connect; null when the server did not say. */
const offered = ref<ConnectorAccess[] | null>(null)

const outcome = ref<{ tone: 'success' | 'failure'; text: string } | null>(null)
const outcomeElement = ref<HTMLElement | null>(null)
const busy = ref<'connect' | 'disconnect' | null>(null)
const actionError = ref<string | null>(null)
const confirmingDisconnect = ref(false)

const calendar = computed(() => latestConnection(connections.value, 'CALENDAR_EVENTS'))
const drive = computed(() => latestConnection(connections.value, 'DRIVE_FILES'))
const anyOpen = computed(() => connections.value.some((connection) => connection.state !== 'DISCONNECTED'))
/** Drive is shown only where it exists, or once this deployment offers it: until then there is nothing to use it for. */
const calendarOffered = computed(() => offered.value?.includes('CALENDAR_EVENTS') ?? false)
const showDrive = computed(() => drive.value !== null || (offered.value?.includes('DRIVE_FILES') ?? false))
const nothingOffered = computed(() => offered.value !== null && offered.value.length === 0 && connections.value.length === 0)

const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function day(value: string): string {
  return dayFormatter.format(new Date(value))
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
    offered.value = offeredAccess(await loadCapabilities())
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
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to see the accounts you have connected to Brownie.</p>
    <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/connections' } }">Sign in</RouterLink>
  </section>

  <section v-else class="connections" aria-labelledby="connections-heading">
    <span class="connections__mark" aria-hidden="true"><AppIcon name="link" :size="28" /></span>
    <h1 id="connections-heading" class="connections__title">Connections</h1>
    <p>
      Accounts outside Brownie that you let it read from. Brownie reads only what you ask for, keeps a copy of only what
      you choose to bring into a document, and never changes anything in them.
    </p>

    <p
      v-if="outcome"
      ref="outcomeElement"
      :class="outcome.tone === 'success' ? 'connections__notice' : 'field-error'"
      :role="outcome.tone === 'success' ? 'status' : 'alert'"
      tabindex="-1"
    >
      {{ outcome.text }}
    </p>
    <p v-if="actionError" class="field-error" role="alert">{{ actionError }}</p>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading your connected accounts…</p>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">{{ loadError }}</p>

    <template v-if="loadState === 'loaded'">
      <p v-if="nothingOffered" class="field-hint">Connecting other accounts is not set up on this Brownie.</p>
      <p v-else-if="offered === null" class="field-hint">
        The Brownie server that answered does not say which accounts can be connected, so none is offered here.
      </p>

      <section v-if="!nothingOffered" class="connections__account" aria-labelledby="connections-calendar">
        <h2 id="connections-calendar" class="connections__heading">{{ ACCESS_NAMES.CALENDAR_EVENTS }}</h2>
        <p>{{ calendar ? stateSentence(calendar) : 'Not connected.' }}</p>
        <dl class="connections__facts">
          <dt>What Google asks you to allow</dt>
          <dd>
            "{{ PERMISSION_WORDS.CALENDAR_EVENTS.google }}"
            <span class="connections__also">{{ PERMISSION_WORDS.CALENDAR_EVENTS.alsoAsked }}</span>
          </dd>
          <dt>What Brownie does with it</dt>
          <dd>{{ PERMISSION_WORDS.CALENDAR_EVENTS.brownie }}</dd>
          <dt>What Brownie may read</dt>
          <dd v-if="calendar && (calendar.grants ?? []).length > 0">
            <ul class="connections__grants">
              <li v-for="grant in calendar.grants ?? []" :key="grant.id">
                {{ grant.displayName ?? 'Your calendar' }}, since {{ day(grant.grantedAt) }}
              </li>
            </ul>
          </dd>
          <dd v-else>Nothing yet. Your calendar is added here the first time you list its events from a document.</dd>
        </dl>
        <p v-if="calendar?.state === 'ACTIVE' && calendarOffered" class="field-hint">
          To copy an event, open a document and use "Copy an event from Google Calendar" on its Sources tab.
        </p>
        <p v-else-if="calendar?.state === 'ACTIVE'" class="field-hint">
          Copying events from Google Calendar is not offered on this Brownie at the moment. The connection stays until you
          disconnect it.
        </p>
        <button
          v-if="canConnect('CALENDAR_EVENTS', calendar)"
          id="connections-connect-calendar"
          type="button"
          class="button button--primary"
          :disabled="busy !== null"
          @click="connect('CALENDAR_EVENTS')"
        >
          {{ calendar?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Calendar again' : 'Connect Google Calendar' }}
        </button>
      </section>

      <section v-if="showDrive" class="connections__account" aria-labelledby="connections-drive">
        <h2 id="connections-drive" class="connections__heading">{{ ACCESS_NAMES.DRIVE_FILES }}</h2>
        <p>{{ drive ? stateSentence(drive) : 'Not connected.' }}</p>
        <dl class="connections__facts">
          <dt>What Google asks you to allow</dt>
          <dd>"{{ PERMISSION_WORDS.DRIVE_FILES.google }}"</dd>
          <dt>What Brownie does with it</dt>
          <dd>{{ PERMISSION_WORDS.DRIVE_FILES.brownie }}</dd>
        </dl>
        <button
          v-if="canConnect('DRIVE_FILES', drive)"
          type="button"
          class="button button--primary"
          :disabled="busy !== null"
          @click="connect('DRIVE_FILES')"
        >
          {{ drive?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Drive again' : 'Connect Google Drive' }}
        </button>
      </section>

      <section v-if="anyOpen" class="connections__account" aria-labelledby="connections-disconnect-heading">
        <h2 id="connections-disconnect-heading" class="connections__heading">Disconnect Google</h2>
        <p>
          Brownie asks Google to take back the access it still holds, and deletes that access whatever Google answers.
          A connection waiting to be connected again holds none Brownie can use, so for that one Brownie cannot ask
          Google; remove Brownie in your Google account instead. Copies already brought into your documents stay with
          those documents, and each still says where it came from; they are never updated again. To remove a copy,
          delete every document it was copied into.
        </p>
        <button
          v-if="!confirmingDisconnect"
          id="connections-disconnect"
          type="button"
          class="button button--secondary connections__danger"
          :disabled="busy !== null"
          @click="askToDisconnect"
        >
          Disconnect Google…
        </button>
        <div v-else class="connections__confirm" role="group" aria-label="Disconnect Google" @keydown.esc="keepConnected">
          <p class="connections__question">Disconnect every Google connection here? Brownie will need your agreement again to read anything.</p>
          <div class="connections__actions">
            <button
              id="connections-disconnect-confirm"
              type="button"
              class="button button--secondary connections__danger"
              :disabled="busy !== null"
              @click="disconnect"
            >
              {{ busy === 'disconnect' ? 'Disconnecting…' : 'Yes, disconnect' }}
            </button>
            <button type="button" class="button button--secondary" :disabled="busy !== null" @click="keepConnected">
              Keep connected
            </button>
          </div>
        </div>
      </section>

      <p class="field-hint">
        You can also see or remove Brownie's access in your Google account, under
        <a href="https://myaccount.google.com/connections" target="_blank" rel="noopener noreferrer">third-party connections</a>.
      </p>
    </template>

    <RouterLink class="button button--secondary" to="/">Back to your documents</RouterLink>
  </section>
</template>

<style scoped>
.connections {
  inline-size: 100%;
  max-inline-size: 42rem;
  margin-inline: auto;
  margin-block-start: var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
}

.connections__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: 3rem;
  block-size: 3rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  color: var(--color-cocoa-strong);
}

.connections__title {
  margin: 0;
  font-size: var(--font-size-xl);
}

.connections__heading {
  margin: 0;
  font-size: var(--font-size-lg);
}

.connections__notice {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

.connections__account {
  inline-size: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.connections__account p {
  margin: 0;
}

.connections__facts {
  margin: 0;
  display: grid;
  gap: var(--space-1);
}

.connections__facts dt {
  font-weight: 500;
}

.connections__facts dd {
  margin: 0 0 var(--space-2);
  color: var(--color-text-secondary);
}

.connections__also {
  display: block;
  margin-block-start: var(--space-1);
  color: var(--color-text-secondary);
}

.connections__grants {
  margin: 0;
  padding-inline-start: var(--space-5);
}

.connections__confirm {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.connections__question {
  font-weight: 500;
}

.connections__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* The error red on the white surface measures about 5.9:1, so the label stays readable as text, not only as a colour. */
.connections__danger {
  border-color: var(--color-error);
  color: var(--color-error);
}
</style>
