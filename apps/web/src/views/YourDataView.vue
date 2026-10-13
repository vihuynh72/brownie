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
 * does not keep. Nothing here is a legal notice; it is a description. The one
 * date, when the monthly allowance starts again, follows from the server's own
 * rule that its months are calendar months in UTC.
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
const hasSavingConnection = ref(false)
const hasAddingConnection = ref(false)
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

/** "OpenAI (gpt-6-luna)", or null when the server has not said who the model provider is. */
const modelLabel = computed(() => {
  const provider = practices.value?.modelProvider
  if (!provider) return null
  const model = practices.value?.modelName
  return model ? `${provider} (${model})` : provider
})

/**
 * Whether this Brownie sends an uploaded form's text to the model by itself, to find the places to fill in and name
 * them. Nothing asks first, so the page says so rather than promise that nothing is sent until the person asks. A
 * server from before that sends nothing on upload, and leaves the answer out.
 */
const formTextSentToModel = computed(() => practices.value?.formTextSentToModel === true)

const acceptedFilesSentence = computed(() => {
  const types = capabilities.value?.uploadMediaTypes
  if (!Array.isArray(types) || types.length === 0) return null
  const names = types.map((type) => type.extension.replace(/^\./, '').toUpperCase()).join(', ')
  const limit = capabilities.value?.maxUploadBytes
  let sentence = typeof limit === 'number' && limit > 0 ? `${names}, up to ${formatBytes(limit)} each.` : `${names}.`
  // A spreadsheet to fill one document per row from is a separate accept list the server only names where batches exist.
  const tableTypes = capabilities.value?.tableFileTypes
  if (Array.isArray(tableTypes) && tableTypes.length > 0) {
    const rows = capabilities.value?.batchMaxRows
    const limitWords = typeof rows === 'number' && rows > 0 ? `, up to ${rows} rows` : ''
    sentence += ` To fill one document per row, an Excel .xlsx workbook or a CSV file${limitWords}.`
  }
  return sentence
})

/** Where writing a field's text from a row's notes is offered, that writing sends the row's values to the model. */
const composeOffered = computed(() => capabilities.value?.batchComposeOffered === true)

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
/** The same, for saving to Google Drive on approval. */
const mentionsSaving = computed(
  () => capabilitiesFailed.value || (offeredAccess(capabilities.value)?.includes('DRIVE_SAVING') ?? false) || hasSavingConnection.value,
)
/** The same, for adding events to Google Calendar on approval. */
const mentionsAdding = computed(
  () =>
    capabilitiesFailed.value ||
    (offeredAccess(capabilities.value)?.includes('CALENDAR_EVENT_CREATION') ?? false) ||
    hasAddingConnection.value,
)
/** Whether Brownie may change anything in a Google account here, each change only on approval. */
const mentionsChanges = computed(() => mentionsSaving.value || mentionsAdding.value)

/**
 * What deleting the workspace does, in one paragraph. The sentences about Google are joined with a space here, since
 * each is present only in some deployments and a template cannot keep a gap between two that may both be missing.
 */
const deletionExplanation = computed(() => {
  const sentences = [
    'This deletes your workspace for good: every document, template, source and file, and your sign-in record. It cannot be undone, and nothing goes to the trash bin first. Signing in again afterwards starts a new, empty workspace.',
  ]
  if (googleOffered.value) {
    sentences.push('Brownie also asks Google to take back the access it still holds to any Google account you connected.')
  }
  if (mentionsSaving.value) sentences.push('Files you saved to Google Drive stay in your Google Drive.')
  if (mentionsAdding.value) sentences.push('Events you added to Google Calendar stay in your calendar.')
  return sentences.join(' ')
})

const money = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4 })
const wholeNumber = new Intl.NumberFormat()

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

/** The figures the server counted, in one line: "$0.74 of $2.00 used · 31 requests to the model". */
const usageLine = computed(() => {
  const value = knownUsage.value
  if (!value) return ''
  const requests = `${wholeNumber.format(value.monthRequests)} ${value.monthRequests === 1 ? 'request' : 'requests'}`
  return `${money.format(value.monthUsedUsd)} of ${money.format(value.monthLimitUsd)} used · ${requests} to the model`
})

/**
 * How full the bar is, in percent. A request still in flight counts at the most it could cost, so the figure can pass
 * the limit; the bar stops at full. A limit of nothing leaves no room at all.
 */
const usagePercent = computed(() => {
  const value = knownUsage.value
  if (!value) return 0
  if (value.monthLimitUsd <= 0) return 100
  return Math.round(Math.min(1, Math.max(0, value.monthUsedUsd / value.monthLimitUsd)) * 1000) / 10
})

/**
 * When the allowance starts again. The server counts its months in UTC so that the boundary does not depend on who
 * is asking, so this is the first of next month in UTC and says so: for anyone west of Greenwich the new month begins
 * the evening before.
 */
const resetDate = computed(() => {
  const now = new Date()
  const first = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1))
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(first)
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
      hasSavingConnection.value = connections.some((connection) => connection.access === 'DRIVE_SAVING')
      hasAddingConnection.value = connections.some((connection) => connection.access === 'CALENDAR_EVENT_CREATION')
    }
  } catch (error) {
    // A server from before connections has no such route and so nothing kept; any other failure leaves it unknown,
    // and the sentences about Google, all worded "if you connect", are said rather than left out.
    if (!(error instanceof ApiRequestError && error.routeMissing) && session.personalWorkspaceId === workspaceId) {
      hasConnections.value = true
      hasDriveConnection.value = true
      hasSavingConnection.value = true
      hasAddingConnection.value = true
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
  // Once the request is on its way it cannot be called back, so backing out would only hide what happens next.
  if (deleting.value) return
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
  <section v-if="session.status === 'anonymous'" class="page-column page-column--narrow">
    <div class="panel your-data__signin">
      <p>Sign in to see what Brownie keeps about you, or to delete it.</p>
      <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/your-data' } }">Sign in</RouterLink>
    </div>
  </section>

  <section v-else class="page-column page-column--wide your-data" aria-labelledby="your-data-title">
    <header class="page-head">
      <h1 id="your-data-title" class="page-title">Your data</h1>
      <p class="page-lede">What Brownie keeps, for how long, who else sees it, and how to delete it all.</p>
    </header>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading…</p>
    <div v-else-if="loadState === 'error'" class="panel panel--quiet your-data__notice">
      <AppIcon name="alert-circle" :size="20" />
      <div class="your-data__notice-text">
        <p class="field-error" role="alert">{{ loadError }}</p>
        <p class="field-hint">
          The rest of this page leaves out the exact periods and the name of the AI service, which come from the server.
        </p>
      </div>
    </div>

    <!-- Two stacks rather than rows: the sentences are long and differ in length, and a row would leave a hole under its shorter card. -->
    <div class="your-data__cards">
      <div class="your-data__stack">
        <section class="panel your-data__card">
          <h2 class="your-data__card-title"><AppIcon name="folder" :size="20" />What is kept</h2>
          <ul class="your-data__list">
            <li>Your documents, every saved version of them, and the templates and rules you taught.</li>
            <li>The files you attach as sources, and the files Brownie makes from your documents (Word and PDF).</li>
            <li>Spreadsheets you fill documents from in a batch, kept until you delete the batch, then removed within a day.</li>
            <li>Where each filled-in value came from in your sources, so you can check it.</li>
            <li>A record of each run: when it started, how it ended, and what it cost. Not what it said.</li>
            <li>
              A record of a few actions someone may need to account for afterwards (moving a document to the trash,
              deleting, exporting, starting a run again<template v-if="mentionsSaving"
                >, approving a save to Google Drive or an addition to a Google Doc and sending it</template
              ><template v-if="mentionsAdding">, approving an event for Google Calendar and sending it</template>).
              It holds who and when, never a title or anything you wrote.
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
            <li v-if="mentionsSaving">
              If you save a document to Google Drive: each save you prepare, with the file's name, the Google account, the size
              and fingerprints the saved file is checked against, and for a Google Doc the filled-in values Brownie looks for in
              it; then what Google answered, and a link to the saved file. Adding a version's text to a Google Doc it saved keeps
              that text, the Doc's title, and a fingerprint of what the Doc held before. All of it is deleted together with its
              document. The file itself is yours, in your Google Drive: deleting anything here does not delete it there.
            </li>
            <li v-if="mentionsAdding">
              If you add an event to Google Calendar: each event you prepare, with its title, description, place and time and the
              Google account; then what Google answered, and a link to the event. It is deleted together with its document. The
              event itself is yours, in your calendar: deleting anything here does not delete it there.
            </li>
          </ul>
        </section>

        <section class="panel your-data__card">
          <h2 class="your-data__card-title"><AppIcon name="clock" :size="20" />How long</h2>
          <ul class="your-data__list">
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
        </section>
      </div>

      <div class="your-data__stack">
        <section class="panel your-data__card">
          <h2 class="your-data__card-title"><AppIcon name="people" :size="20" />Who else sees it</h2>
          <ul class="your-data__list">
            <li>
              When you ask Brownie to fill in a document or rewrite a value, the text it needs from your sources and your
              document is sent to
              <template v-if="modelLabel">{{ modelLabel }}</template>
              <template v-else>the AI service this Brownie is set up to use</template>
              to get an answer.
              <template v-if="composeOffered"
                >When you ask Brownie to write a field's text from a spreadsheet row's notes, that row's values are sent to
                the same service for the writing.</template
              >
              <template v-if="formTextSentToModel"
                >When you upload a form, its text is sent to the same service, so Brownie can find the places to fill in and
                name them. Nothing else is sent until you ask.</template
              >
              <template v-else>Nothing is sent until you ask.</template>
            </li>
            <li>Signing in is handled by Microsoft. Brownie receives who you are, never your password.</li>
            <li>Every file you upload is scanned for viruses on Brownie's own server before anything reads it.</li>
            <li v-if="mentionsGoogle">
              If you connect a Google account, Brownie reads from Google only what you ask for (the days of your calendar you
              list, and the event you copy<template v-if="mentionsDrive">; what each file you choose in Google Drive is, and a
              file's content only when you copy it</template>)<template v-if="mentionsChanges"
                >, and changes nothing there that you have not approved</template
              ><template v-else> and never changes anything there</template>.<template v-if="mentionsChanges">
                After a change you approve, it reads back only what that change made or added to, to check it.</template
              ><template v-if="mentionsSaving">
                Preparing to add text to a Google Doc Brownie saved reads that Doc in full, and whether it is shared or in the
                trash.</template
              ><template v-if="mentionsSaving">
                A document you save to Google Drive is sent to Google only once you approve that save, and Google converts it if
                you asked for a Google Doc; text you add to a Google Doc is sent only once you approve that addition.</template
              ><template v-if="mentionsAdding">
                An event you add to Google Calendar is sent to Google only once you approve it, and it has no guests, so Google
                tells nobody about it.</template
              >
              See or remove it on the
              <RouterLink to="/connections">Connections</RouterLink> page.
            </li>
          </ul>
        </section>

        <section v-if="acceptedFilesSentence" class="panel your-data__card">
          <h2 class="your-data__card-title"><AppIcon name="document-check" :size="20" />Files Brownie accepts</h2>
          <p class="your-data__text">{{ acceptedFilesSentence }} A file is judged by what it is, not by its name.</p>
        </section>

        <section v-if="knownUsage" class="panel your-data__card">
          <h2 class="your-data__card-title"><AppIcon name="history" :size="20" />This month</h2>
          <div class="your-data__meter-block">
            <!-- The figures beside it say everything the bar shows, so it is left out of what a screen reader reads. -->
            <div class="your-data__meter" aria-hidden="true">
              <span class="your-data__meter-fill" :style="{ '--your-data-used': `${usagePercent}%` }" />
            </div>
            <div class="your-data__usage">
              <p class="your-data__usage-line">{{ usageLine }}</p>
              <p class="your-data__reset">Resets {{ resetDate }} (UTC)</p>
            </div>
          </div>
          <div class="your-data__fineprint">
            <p v-if="knownUsage.sharedAllowanceExhausted">
              The allowance everyone shares is used up for this month, so new runs are paused until next month.
            </p>
            <p>
              When the allowance runs out, Brownie cannot fill in a document or rewrite a value until next month. Typing,
              reviewing and exporting keep working.
            </p>
          </div>
        </section>

        <section class="panel your-data__card">
          <h2 class="your-data__card-title"><AppIcon name="help-circle" :size="20" />Questions</h2>
          <p v-if="practices?.supportContact" class="your-data__text">Ask {{ practices.supportContact }}.</p>
          <p v-else-if="practices" class="your-data__text">
            Nobody has been named to ask yet. Whoever gave you access to Brownie is the person to ask.
          </p>
          <p v-else class="your-data__text">Whoever gave you access to Brownie is the person to ask.</p>
        </section>
      </div>
    </div>

    <section class="panel your-data__card your-data__danger" aria-labelledby="your-data-delete-title">
      <h2 id="your-data-delete-title" class="your-data__card-title your-data__card-title--danger">
        <AppIcon name="trash" :size="20" />Delete everything
      </h2>
      <p class="your-data__text">{{ deletionExplanation }}</p>
      <button
        v-if="!confirming"
        ref="openButton"
        type="button"
        class="button button--secondary your-data__open"
        @click="askToDelete"
      >
        Delete my workspace…
      </button>
      <form v-else class="your-data__confirm" @submit.prevent="deleteEverything" @keydown.esc="keepWorkspace">
        <label class="field-label" for="your-data-confirm-phrase">Type {{ CONFIRMATION_PHRASE }} to confirm</label>
        <div class="your-data__confirm-row">
          <input
            id="your-data-confirm-phrase"
            ref="phraseInput"
            v-model="typedPhrase"
            class="text-field"
            type="text"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
            :disabled="deleting"
          />
          <button type="button" class="button button--secondary" :disabled="deleting" @click="keepWorkspace">
            Keep my workspace
          </button>
          <button type="submit" class="button button--danger your-data__delete" :disabled="!phraseMatches || deleting">
            <AppIcon name="trash" :size="18" />
            {{ deleting ? 'Deleting…' : 'Delete everything for good' }}
          </button>
        </div>
        <p v-if="deleteError" class="field-error" role="alert">{{ deleteError }}</p>
      </form>
    </section>
  </section>
</template>

<style scoped>
/*
 * The title's line starts 50px below the top of the page region, which the shell already pads by its own gutter. The
 * space under the lede is the lede's own, so each block below carries the 18px that keeps it from the next one.
 */
.your-data {
  padding-block-start: calc(3.125rem - var(--shell-gutter-block-start, 1.5rem));
}

.your-data__signin {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
}

.your-data__signin > p {
  margin: 0;
}

.your-data > .field-hint {
  margin: 0 0 1.125rem;
}

/* Something the page could not load, said above the cards that carry on without it. */
.your-data__notice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  margin-block-end: 1.125rem;
}

.your-data__notice > svg {
  margin-block-start: 0.125rem;
  color: var(--color-error);
}

.your-data__notice-text {
  display: grid;
  gap: var(--space-1);
  min-inline-size: 0;
}

.your-data__notice-text > p {
  margin: 0;
}

.your-data__notice-text > .field-error {
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

/* As many stacks as fit side by side, but never so narrow that a line of text is cramped; one stack on a phone. */
.your-data__cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 21.5rem), 1fr));
  gap: 1rem 1.125rem;
  align-items: start;
  margin-block-end: 1.125rem;
}

.your-data__stack {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-inline-size: 0;
}

/* A heading row, then what it says, ten pixels apart. */
.your-data__card {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}

.your-data__card-title {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.25rem;
}

.your-data__card-title > svg {
  color: var(--color-cocoa);
}

.your-data__text,
.your-data__list {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.25rem;
  overflow-wrap: anywhere;
}

.your-data__list {
  padding-inline-start: 1.125rem;
}

.your-data__list > li + li {
  margin-block-start: 0.5rem;
}

/* The bar sits just above its figures; the card's own gap is for the lines that follow. */
.your-data__meter-block {
  display: grid;
  gap: 0.25rem;
}

.your-data__meter {
  block-size: 0.5rem;
  border-radius: var(--radius-pill);
  background: var(--color-track);
  overflow: hidden;
}

.your-data__meter-fill {
  display: block;
  inline-size: var(--your-data-used, 0%);
  block-size: 100%;
  border-radius: var(--radius-pill);
  background: var(--color-cocoa);
}

.your-data__usage {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 0 var(--space-3);
}

.your-data__usage-line {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.your-data__reset {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
  white-space: nowrap;
}

.your-data__fineprint {
  display: grid;
  gap: var(--space-2);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.0625rem;
}

.your-data__fineprint > p {
  margin: 0;
}

/* The card that cannot be undone: a red edge, with a half pixel more so it shows on paper, and a red heading. */
.your-data__danger {
  border-color: var(--color-error);
  box-shadow: inset 0 0 0 0.5px var(--color-error);
}

.your-data__card-title--danger {
  color: var(--color-error);
}

.your-data__card-title--danger > svg {
  color: var(--color-error);
  opacity: 0.7;
}

.your-data__danger .your-data__card-title + .your-data__text {
  margin-block-start: 0.125rem;
}

/* The way in is an outline in the same red as the card, so the filled red button is only ever the last step. */
.your-data__open {
  --button-ink: var(--color-error);
  --button-border: var(--color-error);
  --button-bg-hover: var(--color-error-wash);
  --button-border-hover: var(--color-error);
  --button-bg-active: color-mix(in srgb, var(--color-error-wash) 90%, black);
  align-self: flex-start;
}

.your-data__confirm-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

/* The field takes what the two buttons leave and drops them onto a line of their own when it would get too narrow. */
.your-data__confirm-row > .text-field {
  flex: 1 1 16rem;
  inline-size: auto;
  min-inline-size: 0;
}

.your-data__delete {
  --button-icon: 1.125rem;
  --button-gap: 0.5rem;
}

.your-data__confirm > .field-error {
  margin: var(--space-2) 0 0;
}
</style>
