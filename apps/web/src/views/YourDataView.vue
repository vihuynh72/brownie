<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { dayCount, hourCount } from '@/periods'
import { describeCommonFailure } from '@/api/failures'
import { offeredAccess } from '@/connections/words'
import {
  ApiRequestError,
  deleteWorkspace,
  getDataPractices,
  getWorkspaceUsage,
  listConnections,
  type CapabilitiesResponse,
  type DataPracticesResponse,
  type UsageResponse,
} from '@/api/client'

/**
 * What Brownie keeps, for how long, who else sees any of it, and the one
 * irreversible thing a person can do about all of it. Every number on this
 * page comes from the server, which reads it from the same setting the
 * background worker acts on, so the page cannot promise a period the system
 * does not keep. Nothing here is a legal notice; it is a description.
 *
 * Only the sentences that carry those numbers wait for the server, and each
 * has a wording without its number. Everything else, the way to delete the
 * workspace above all, is on the page whatever the server answers: a failed
 * description must never take away the only way to remove everything.
 *
 * Deleting the workspace asks for a typed phrase, in the page and not in a
 * dialog: it removes everything and cannot be undone, and a button alone
 * is too easy to press. Escape or "Keep my workspace" puts focus back on
 * the button that opened the question.
 */
const CONFIRMATION_PHRASE = 'delete my workspace'
const PRACTICES_WHAT = 'the details of how your data is kept and shared'

const session = useSessionStore()
const practices = ref<DataPracticesResponse | null>(null)
const capabilities = ref<CapabilitiesResponse | null>(null)
const capabilitiesFailed = ref(false)
const hasConnections = ref(false)
const hasDriveConnection = ref(false)
const usage = ref<UsageResponse | null>(null)
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const loadError = ref<string | null>(null)

const confirming = ref(false)
const typedPhrase = ref('')
const deleting = ref(false)
const deleteError = ref<string | null>(null)
const phraseInput = ref<HTMLInputElement | null>(null)
const openButton = ref<HTMLButtonElement | null>(null)

const phraseMatches = computed(() => typedPhrase.value.trim().toLowerCase() === CONFIRMATION_PHRASE)

// Each period is null when the server did not send it (it failed, or is older than this page), and the sentence
// that carries it then reads without it.
const trashPeriod = computed(() => dayCount(practices.value?.trashRetentionDays))
const abandonedUploadPeriod = computed(() => hourCount(practices.value?.abandonedUploadHours))
const refusedFilePeriod = computed(() => hourCount(practices.value?.refusedFileHours))
const unusedFilePeriod = computed(() => hourCount(practices.value?.unusedFileHours))
const auditRecordPeriod = computed(() => dayCount(practices.value?.auditRecordDays))

/** "OpenAI (gpt-5.4-mini)", or null when the server has not said who the model provider is. */
const modelLabel = computed(() => {
  const provider = practices.value?.modelProvider
  if (!provider) return null
  const model = practices.value?.modelName
  return model ? `${provider} (${model})` : provider
})

const acceptedFilesSentence = computed(() => {
  const types = capabilities.value?.uploadMediaTypes
  if (!Array.isArray(types) || types.length === 0) return null
  const names = types.map((type) => type.extension.replace(/^\./, '').toUpperCase()).join(', ')
  const limit = capabilities.value?.maxUploadBytes
  return typeof limit === 'number' && limit > 0 ? `${names}, up to ${formatBytes(limit)} each.` : `${names}.`
})

const googleOffered = computed(() => (offeredAccess(capabilities.value)?.length ?? 0) > 0)
/**
 * Whether to say what connecting a Google account keeps and shares: yes where this Brownie offers it, where the
 * person has a connection (Google may have been switched off since, and what was kept is still kept), and when
 * neither could be checked, since those sentences are all worded "if you connect". Not where the server says it
 * has no Google set up and the person never connected, or is from before connections existed.
 */
const mentionsGoogle = computed(() => capabilitiesFailed.value || googleOffered.value || hasConnections.value)
/** The same, for choosing files in Google Drive: where it is offered, where the person has a Drive connection, or when unknown. */
const mentionsDrive = computed(
  () => capabilitiesFailed.value || (offeredAccess(capabilities.value)?.includes('DRIVE_FILES') ?? false) || hasDriveConnection.value,
)

const money = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4 })

/** Usage from a server that sends every number this sentence needs; anything less leaves the section out. */
const knownUsage = computed(() => {
  const value = usage.value
  if (!value) return null
  const complete =
    typeof value.monthRequests === 'number' &&
    typeof value.monthUsedUsd === 'number' &&
    typeof value.monthLimitUsd === 'number'
  return complete ? value : null
})

async function loadPractices(): Promise<void> {
  loadState.value = 'loading'
  loadError.value = null
  try {
    practices.value = await getDataPractices()
    loadState.value = 'loaded'
  } catch (error) {
    loadState.value = 'error'
    loadError.value =
      describeCommonFailure(error, PRACTICES_WHAT) ??
      `Brownie could not load ${PRACTICES_WHAT}. If this keeps happening, let whoever runs this Brownie know.`
  }
}

// Both are additions to the page, not the page: it still says everything that matters without them.
async function loadAcceptedFiles(): Promise<void> {
  try {
    capabilities.value = await loadCapabilities()
  } catch {
    capabilities.value = null
    capabilitiesFailed.value = true
  }
}

async function loadUsage(workspaceId: number | undefined): Promise<void> {
  if (workspaceId === undefined) return
  try {
    const answer = await getWorkspaceUsage(workspaceId)
    // A different workspace may have arrived while this one was being read.
    if (session.personalWorkspaceId === workspaceId) {
      usage.value = answer
    }
  } catch {
    usage.value = null
  }
}

async function loadConnections(workspaceId: number | undefined): Promise<void> {
  if (workspaceId === undefined) return
  try {
    const connections = await listConnections(workspaceId)
    if (session.personalWorkspaceId === workspaceId) {
      hasConnections.value = connections.length > 0
      hasDriveConnection.value = connections.some((connection) => connection.access === 'DRIVE_FILES')
    }
  } catch (error) {
    // A server from before connections has no such route and so nothing kept; any other failure leaves it unknown,
    // and the sentences about Google, all worded "if you connect", are said rather than left out.
    if (!(error instanceof ApiRequestError && error.routeMissing) && session.personalWorkspaceId === workspaceId) {
      hasConnections.value = true
      hasDriveConnection.value = true
    }
  }
}

async function askToDelete(): Promise<void> {
  confirming.value = true
  typedPhrase.value = ''
  deleteError.value = null
  await nextTick()
  phraseInput.value?.focus()
}

async function keepWorkspace(): Promise<void> {
  confirming.value = false
  typedPhrase.value = ''
  deleteError.value = null
  await nextTick()
  openButton.value?.focus()
}

/** Why the workspace could not be deleted, in words that say whether waiting or trying again can change that. */
function describeDeleteFailure(error: unknown): string {
  if (error instanceof ApiRequestError && error.problem?.code === 'DELETION_WAITING_FOR_RUNNING_WORK') {
    return 'A run is still stopping. Nothing was deleted; try again in a moment.'
  }
  const common = describeCommonFailure(error, 'a way to delete a workspace')
  if (common) return `Nothing was deleted. ${common}`
  // A refusal the server explains (only the owner may delete a workspace, say) gets the same answer however often it
  // is asked, so it is said as the server put it, without advice to try again.
  if (error instanceof ApiRequestError && error.status >= 400 && error.status < 500 && error.problem?.detail) {
    return `Nothing was deleted. ${error.problem.detail}`
  }
  return 'Your workspace could not be deleted. Nothing was deleted; try again.'
}

async function showDeleteError(text: string): Promise<void> {
  deleteError.value = text
  await nextTick()
  phraseInput.value?.focus()
}

async function deleteEverything(): Promise<void> {
  if (!phraseMatches.value || deleting.value) return
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) {
    // Normally an identity request still under way: the app shell shows its own card for a failed one and for an
    // account with no workspace.
    await showDeleteError(
      session.status === 'authenticated'
        ? 'Brownie found no workspace for this account, so there is nothing to delete.'
        : 'Brownie is still confirming who is signed in, so nothing was deleted. Wait a moment, then try again.',
    )
    return
  }
  deleting.value = true
  deleteError.value = null
  try {
    await deleteWorkspace(workspaceId)
  } catch (error) {
    deleting.value = false
    await showDeleteError(describeDeleteFailure(error))
    return
  }
  // The server has ended every session for this person; the page that follows must be a fresh, signed-out one.
  window.location.assign('/signin?deleted=1')
}

onMounted(() => {
  void loadPractices()
  void loadAcceptedFiles()
  void loadUsage(session.personalWorkspaceId)
  void loadConnections(session.personalWorkspaceId)
})
// The page can mount before the identity request has answered; usage is read as soon as the workspace is known.
watch(
  () => session.personalWorkspaceId,
  (workspaceId, previous) => {
    if (workspaceId !== undefined && workspaceId !== previous) {
      void loadUsage(workspaceId)
      void loadConnections(workspaceId)
    }
  },
)
</script>

<template>
  <!-- The router sends a signed-out visitor to the sign-in page before this view mounts; this is what shows if a session ends while it is open. -->
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to see what Brownie keeps about you, or to delete it.</p>
    <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/your-data' } }">Sign in</RouterLink>
  </section>

  <section v-else class="privacy" aria-labelledby="privacy-heading">
    <span class="privacy__mark" aria-hidden="true"><AppIcon name="shield" :size="28" /></span>
    <h1 id="privacy-heading" class="privacy__title">Your data</h1>
    <p>What Brownie keeps, how long it keeps it, who else sees any of it, and how to remove all of it.</p>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading…</p>
    <template v-else-if="loadState === 'error'">
      <p class="field-error" role="alert">{{ loadError }}</p>
      <p class="field-hint">
        The rest of this page leaves out the exact periods and the name of the AI service, which come from the server.
      </p>
    </template>

    <h2 class="privacy__heading">What is kept</h2>
    <ul class="privacy__list">
      <li>Your documents, every saved version of them, and the templates and rules you taught.</li>
      <li>The files you attach as sources, and the files Brownie makes from your documents (Word and PDF).</li>
      <li>Where each filled-in value came from in your sources, so you can check it.</li>
      <li>A record of each run: when it started, how it ended, and what it cost. Not what it said.</li>
      <li>
        A record of a few actions someone may need to account for afterwards (moving a document to the trash, deleting,
        exporting, starting a run again). It holds who and when, never a title or anything you wrote.
      </li>
      <li v-if="mentionsGoogle">
        If you connect a Google account: which account, what Google allowed and when, and the access Google gives
        Brownie, kept encrypted. A calendar event you copy into a document is kept as text, like a file you attach, and
        says where it came from.
      </li>
      <li v-if="mentionsDrive">
        If you choose files in Google Drive: which files they are, each one's name as Google Drive gives it, and since when
        Brownie may read it. After you take a file off your list, or disconnect, Brownie no longer reads it or shows it,
        but keeps that record with your workspace, as it does which account was connected. A file you copy into a document
        is kept as text, like a file you attach, and says where it came from.
      </li>
    </ul>

    <h2 class="privacy__heading">How long</h2>
    <ul class="privacy__list">
      <li v-if="trashPeriod">
        Something in the trash bin can be restored for {{ trashPeriod }}. After that, or when you delete it yourself, it
        is deleted for good together with its files and any source file nothing else uses.
      </li>
      <li v-else>
        Something in the trash bin can be restored at least until the date the trash bin shows beside it. Soon after that
        date it is deleted for good together with its files and any source file nothing else uses; deleting it yourself
        does that straight away.
      </li>
      <li v-if="abandonedUploadPeriod">An upload that was never finished is removed after {{ abandonedUploadPeriod }}.</li>
      <li v-else>An upload that was never finished is removed automatically.</li>
      <li v-if="refusedFilePeriod">
        A file that was refused (wrong kind, or failed the virus scan) loses its contents after {{ refusedFilePeriod }}.
      </li>
      <li v-else>A file that was refused (wrong kind, or failed the virus scan) loses its contents automatically.</li>
      <li v-if="unusedFilePeriod">A file that nothing refers to any more is removed after {{ unusedFilePeriod }}.</li>
      <li v-else>A file that nothing refers to any more is removed automatically.</li>
      <li v-if="auditRecordPeriod">The record of actions is kept for {{ auditRecordPeriod }}.</li>
      <li v-else>The record of actions is kept for a set period, then removed.</li>
      <li v-if="mentionsGoogle">
        The access Google gives Brownie is deleted as soon as you disconnect Google<template v-if="mentionsDrive">, and
        every file you chose in Google Drive is taken off your list</template>. Which account was connected, and when<template
          v-if="mentionsDrive"
        >, and which files you chose</template>, stays with your workspace until the workspace is deleted; copies already
        made stay with their documents.
      </li>
      <li>
        When something is deleted for good, what remains is a note that a deletion happened: numbers, dates and
        counts, nothing of its contents. That note is what keeps it deleted if the system is ever restored from a
        backup.
      </li>
    </ul>

    <h2 class="privacy__heading">Who else sees it</h2>
    <ul class="privacy__list">
      <li>
        When you ask Brownie to fill in a document or rewrite a value, the text it needs from your sources and your
        document is sent to
        <template v-if="modelLabel">{{ modelLabel }}</template>
        <template v-else>the AI service this Brownie is set up to use</template>
        to get an answer. Nothing is sent until you ask.
      </li>
      <li>Signing in is handled by Microsoft. Brownie receives who you are, never your password.</li>
      <li>Every file you upload is scanned for viruses on Brownie's own server before anything reads it.</li>
      <li v-if="mentionsGoogle">
        If you connect a Google account, Brownie reads from Google only what you ask for (the days of your calendar you
        list, and the event you copy<template v-if="mentionsDrive">; what each file you choose in Google Drive is, and a
        file's content only when you copy it</template>) and never changes anything there. See or remove it on the
        <RouterLink to="/connections">Connections</RouterLink> page.
      </li>
    </ul>

    <template v-if="acceptedFilesSentence">
      <h2 class="privacy__heading">Files Brownie accepts</h2>
      <p>{{ acceptedFilesSentence }} A file is judged by what it is, not by its name.</p>
    </template>

    <template v-if="knownUsage">
      <h2 class="privacy__heading">This month</h2>
      <p>
        {{ knownUsage.monthRequests }} {{ knownUsage.monthRequests === 1 ? 'request' : 'requests' }} to the model, costing
        {{ money.format(knownUsage.monthUsedUsd) }} of this workspace's {{ money.format(knownUsage.monthLimitUsd) }} for the
        month.
        <template v-if="knownUsage.sharedAllowanceExhausted">
          The allowance everyone shares is used up for this month, so new runs are paused until next month.
        </template>
      </p>
    </template>

    <h2 class="privacy__heading">Questions</h2>
    <p v-if="practices?.supportContact">Ask {{ practices.supportContact }}.</p>
    <p v-else-if="practices">Nobody has been named to ask yet. Whoever gave you access to Brownie is the person to ask.</p>
    <p v-else>Whoever gave you access to Brownie is the person to ask.</p>

    <h2 class="privacy__heading">Delete everything</h2>
    <p>
      This deletes your workspace for good: every document, template, source and file, and your sign-in record. It
      cannot be undone, and nothing goes to the trash bin first. Signing in again afterwards starts a new, empty
      workspace.
      <template v-if="googleOffered">
        Brownie also asks Google to take back the access it still holds to any Google account you connected.
      </template>
    </p>
    <button
      v-if="!confirming"
      ref="openButton"
      type="button"
      class="button button--secondary privacy__danger"
      @click="askToDelete"
    >
      Delete my workspace…
    </button>
    <form v-else class="privacy__confirm" @submit.prevent="deleteEverything" @keydown.esc="keepWorkspace">
      <label class="field-label" for="privacy-confirm-phrase">
        To delete everything, type <strong>{{ CONFIRMATION_PHRASE }}</strong>
      </label>
      <input
        id="privacy-confirm-phrase"
        ref="phraseInput"
        v-model="typedPhrase"
        type="text"
        autocomplete="off"
        autocapitalize="off"
        spellcheck="false"
        :disabled="deleting"
      />
      <p v-if="deleteError" class="field-error" role="alert">{{ deleteError }}</p>
      <div class="privacy__actions">
        <button type="submit" class="button button--secondary privacy__danger" :disabled="!phraseMatches || deleting">
          {{ deleting ? 'Deleting…' : 'Delete everything for good' }}
        </button>
        <button type="button" class="button button--secondary" :disabled="deleting" @click="keepWorkspace">Keep my workspace</button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.privacy {
  inline-size: 100%;
  max-inline-size: 42rem;
  margin-inline: auto;
  margin-block-start: var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
}

.privacy__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: 3rem;
  block-size: 3rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  color: var(--color-cocoa-strong);
}

.privacy__title {
  margin: 0;
  font-size: var(--font-size-xl);
}

.privacy__heading {
  margin: var(--space-4) 0 0;
  font-size: var(--font-size-lg);
}

.privacy__list {
  margin: 0;
  padding-inline-start: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.privacy__confirm {
  inline-size: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--color-error);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.privacy__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.privacy__danger {
  border-color: var(--color-error);
  color: var(--color-error);
}
</style>
