<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import SpotDialogShell from '@/components/workspace/SpotDialogShell.vue'
import { loadCapabilities } from '@/capabilities'
import { useSessionStore } from '@/stores/session'
import { describeCommonFailure } from '@/api/failures'
import {
  acceptBatchStep,
  advanceBatchExport,
  batchExportDownloadUrl,
  checkBatchStep,
  composeBatchStep,
  createBatchExport,
  deleteBatch,
  fillBatchStep,
  getBatch,
  getBatchExport,
  getTemplateVersion,
  getWorkspaceUsage,
  type BatchExportResponse,
  type BatchResponse,
  type BatchRowResponse,
} from '@/api/client'
import {
  acceptSkipSentence,
  batchBusy,
  batchStepSentence,
  exportOutcomeSentence,
  findingWords,
  rowProblemSentence,
  STEP_NO_PROGRESS,
  stepNext,
  type StepAnswer,
} from '@/batch/fillMany'
import { fieldLabel } from '@/workspace/layout'

/**
 * One batch of documents made from a spreadsheet: how far along it is,
 * each row's state, and the steps that move it -- more documents,
 * writing texts with Brownie, accepting, checking, and approving an
 * export whose archive downloads from here. Every step is bounded, so a
 * step call answers what it did and how many rows are left, and the page
 * asks the batch again after each call rather than adding states up
 * itself: a step's row list carries only what changed.
 */
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const batchId = computed(() => Number(route.params.batchId))

const batch = ref<BatchResponse | null>(null)
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<string | null>(null)
const composeOffered = ref(false)
/** The template's own format, which decides what an export may be asked for. */
const templateKind = ref<'DOCX' | 'PDF' | null>(null)
/** The pinned version's fields, so a finding can name its place by its label. */
const versionFields = ref<readonly { fieldId: string; label?: string | null }[]>([])

const heading = ref<HTMLElement | null>(null)
const actionError = ref<string | null>(null)
const actionNotice = ref<string | null>(null)
const noticeElement = ref<HTMLElement | null>(null)
const statusLine = ref('')

/** The step under way, or null -- the leave guard holds while one runs. */
const running = ref<'fill' | 'compose' | 'accept' | 'check' | 'export' | null>(null)
const waitingBusy = ref(false)

/**
 * The page's one polite live region: it stays in the page while empty,
 * because a region that only appears with its first message is not
 * reliably read out. What it says follows where the work stands; the
 * export dialog's own status region says its own.
 */
const announcement = computed(() => {
  if (loadState.value === 'loading') return 'Opening the batch…'
  if (waitingBusy.value) {
    return 'Brownie is doing something else for you just now; it will try again in a moment.'
  }
  return statusLine.value
})

const skipped = ref<{ rowNumber: number; sentence: string }[]>([])
const noAllowance = ref<string | null>(null)

const exportDialog = ref<InstanceType<typeof SpotDialogShell> | null>(null)
const exportFormat = ref<'DOCX' | 'PDF' | 'BOTH'>('DOCX')
const activeExport = ref<BatchExportResponse | null>(null)
const exportProgress = ref<{ done: number; total: number } | null>(null)
const exportError = ref<string | null>(null)

const confirmingDelete = ref(false)
const deleting = ref(false)

const rows = computed(() => batch.value?.rows ?? [])

const counts = computed(() => {
  const all = rows.value
  return {
    made: all.filter((row) => row.documentId != null && row.state === 'CREATED').length,
    pending: all.filter((row) => row.state === 'PENDING').length,
    accepted: all.filter((row) => row.accepted).length,
    checked: all.filter((row) => row.check.state === 'CHECKED').length,
    blocking: all.filter((row) => row.check.blocking > 0 || row.preCheckBlocking > 0).length,
    suggestions: all.filter((row) => row.composeState === 'PROPOSED' || row.suggestionWaiting).length,
    exported: all.filter((row) => row.lastExport?.outcome === 'EXPORTED').length,
    composePending: all.filter((row) => row.composeState === 'PENDING').length,
  }
})

/** The rows an export can take now: made and still there, accepted, nothing blocking and no suggestion waiting. */
const exportableCount = computed(
  () =>
    rows.value.filter(
      (row) =>
        row.documentId != null &&
        row.documentPresent &&
        row.accepted &&
        row.check.blocking === 0 &&
        row.preCheckBlocking === 0 &&
        !row.suggestionWaiting &&
        row.composeState !== 'PROPOSED',
    ).length,
)

const leftOutItems = computed(() => (activeExport.value?.items ?? []).filter((item) => item.outcome !== 'EXPORTED'))

const exportedCount = computed(
  () => (activeExport.value?.items ?? []).filter((item) => item.outcome === 'EXPORTED').length,
)

const downloadUrl = computed(() => {
  const workspaceId = session.personalWorkspaceId
  const exportId = activeExport.value?.id
  return workspaceId !== undefined && exportId != null
    ? batchExportDownloadUrl(workspaceId, batch.value?.id ?? batchId.value, exportId)
    : null
})

const downloadName = computed(() =>
  activeExport.value !== null ? `Download ZIP (${exportedCount.value} documents)` : '',
)

const fieldName = (fieldId: string | null | undefined): string => {
  if (fieldId == null) return 'A cell'
  const defined = versionFields.value.find((field) => field.fieldId === fieldId)
  return fieldLabel(defined ?? { fieldId, label: null })
}

/** A finding's place named by the pinned version's own label, where the version still names it. */
const findingFieldName = (fieldId: string): string | null => {
  const defined = versionFields.value.find((field) => field.fieldId === fieldId)
  return defined ? fieldLabel(defined) : null
}

/** The letters a problem's column index reads as; the batch carries the index, not the letter the person saw. */
const problemColumns = computed(() =>
  [...new Set(rows.value.flatMap((row) => row.problems.map((problem) => problem.column)).filter((column) => column != null))].map(
    (index) => ({ index: index as number, letter: letterOf(index as number) }),
  ),
)

/** 0 -> A, 1 -> B, 26 -> AA: the letters the spreadsheet itself uses. */
function letterOf(index: number): string {
  let letters = ''
  for (let n = index; n >= 0; n = Math.floor(n / 26) - 1) {
    letters = String.fromCharCode(65 + (n % 26)) + letters
  }
  return letters
}

function rowNotes(row: BatchRowResponse): string[] {
  return row.problems.map((problem) => rowProblemSentence(problem, fieldName, problemColumns.value))
}

/** A row's document id to link to, or the words for why there is nothing to open. */
function rowDocument(row: BatchRowResponse): { id: number | null; label: string } {
  if (row.documentId != null && row.documentPresent) {
    return { id: row.documentId, label: row.documentTitle ?? `Document ${row.documentId}` }
  }
  if (row.documentId != null) return { id: null, label: 'Deleted' }
  if (row.state === 'SKIPPED_BLANK') return { id: null, label: 'Blank row' }
  if (row.state === 'DOCUMENT_DELETED') return { id: null, label: 'Deleted' }
  return { id: null, label: 'Not made' }
}

/** What the row's state reads as, one phrase for the status column. */
function rowStatus(row: BatchRowResponse): string {
  if (row.documentId != null && !row.documentPresent) return 'In the Trash'
  if (row.state === 'PENDING') return 'Not made'
  if (row.state === 'SKIPPED_BLANK') return 'Skipped: the row was blank'
  if (row.state === 'FAILED') return 'Not made'
  if (row.state === 'DOCUMENT_DELETED') return 'Deleted'
  if (row.lastExport?.outcome === 'EXPORTED') return 'Exported'
  if (row.check.blocking > 0 || row.preCheckBlocking > 0) {
    const finding = row.check.firstBlocking
    const detail = finding?.code != null
      ? findingWords(`${finding.code}${finding.fieldId != null ? `:${finding.fieldId}` : ''}`, findingFieldName)
      : ''
    return `Blocks export${detail ? `: ${detail}` : ''}`
  }
  if (row.composeState === 'PROPOSED' || row.suggestionWaiting) return 'Suggestion waiting'
  if (row.check.state === 'CHECKED') return 'Checked'
  if (row.accepted) return 'Accepted'
  if (row.problems.length > 0) return 'Needs a value'
  return 'Filled'
}

async function load(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  loadState.value = batch.value === null ? 'loading' : 'ready'
  loadError.value = null
  try {
    const answer = await getBatch(workspaceId, batchId.value)
    batch.value = answer
    loadState.value = 'ready'
  } catch (error) {
    if (batch.value === null) {
      loadState.value = 'error'
      loadError.value =
        describeCommonFailure(error, 'this batch') ??
        'Brownie could not open this batch. If this keeps happening, let whoever runs this Brownie know.'
    }
    return
  }
  if (templateKind.value === null && batch.value !== null) {
    try {
      const version = await getTemplateVersion(workspaceId, batch.value.templateId, batch.value.templateVersionId)
      templateKind.value = version.kind ?? 'DOCX'
      versionFields.value = version.fields ?? []
    } catch {
      templateKind.value = 'DOCX'
    }
  }
}

/** What a failed step is told as, where the batch codes carry the answer. */
function stepFailureWords(error: unknown, what: string): string {
  return (
    batchStepSentence(error) ??
    describeCommonFailure(error, what) ??
    `Brownie stopped ${what}. Try again.`
  )
}

const BUSY_WAIT_MS = 2000
const BUSY_TRIES = 15
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Calls one bounded step again and again while the server answers that
 * rows are left. The batch is asked for again after every call: a step
 * response names only the rows it touched, so the page never infers
 * states the answer did not give. The answer's own retryAfterMs is the
 * wait honoured before the next call; an answer that moved nothing and
 * named no wait ends the loop instead of spinning. BATCH_BUSY waits and
 * asks again, bounded; anything else stops the loop and is worded for
 * the person.
 *
 * Answers false when the loop stopped early on an error.
 */
async function runSteps(
  kind: NonNullable<typeof running.value>,
  what: string,
  step: () => Promise<StepAnswer>,
): Promise<boolean> {
  running.value = kind
  actionError.value = null
  try {
    for (let busyTries = 0; ; ) {
      try {
        waitingBusy.value = false
        const answer = await step()
        await load()
        busyTries = 0
        const next = stepNext(answer)
        if (next.kind === 'done') return true
        if (next.kind === 'stuck') {
          actionError.value = STEP_NO_PROGRESS
          return false
        }
        if (next.kind === 'wait') await pause(next.ms)
      } catch (error) {
        if (batchBusy(error) && busyTries < BUSY_TRIES) {
          busyTries++
          waitingBusy.value = true
          await pause(BUSY_WAIT_MS)
          continue
        }
        actionError.value = batchBusy(error)
          ? 'Another step for you is still running and did not leave room in time. Try again in a moment.'
          : stepFailureWords(error, what)
        return false
      }
    }
  } finally {
    running.value = null
    waitingBusy.value = false
  }
}

async function continueMaking(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || batch.value === null || running.value !== null) return
  const id = batch.value.id
  statusLine.value = 'Making documents…'
  await runSteps('fill', 'making the documents', async () => fillBatchStep(workspaceId, id))
  statusLine.value = ''
}

async function writeTexts(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || batch.value === null || running.value !== null) return
  const id = batch.value.id
  noAllowance.value = null
  statusLine.value = 'Writing texts with Brownie…'
  const finished = await runSteps('compose', 'writing texts', async () => composeBatchStep(workspaceId, id))
  statusLine.value = ''
  if (finished && rows.value.some((row) => row.composeState === 'NO_ALLOWANCE')) {
    try {
      const usage = await getWorkspaceUsage(workspaceId)
      noAllowance.value =
        `This workspace's $${usage.monthLimitUsd.toFixed(2)} model allowance for this month is used up, ` +
        'so the rest could not be written. It starts again next month.'
    } catch {
      noAllowance.value = 'This month’s model allowance is used up, so the rest could not be written. It starts again next month.'
    }
  }
}

async function acceptReady(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || batch.value === null || running.value !== null) return
  const id = batch.value.id
  const skippedRows = new Map<number, string>()
  statusLine.value = 'Accepting rows that are ready…'
  const finished = await runSteps('accept', 'accepting rows', async () => {
    const step = await acceptBatchStep(workspaceId, id)
    for (const row of step.rows) {
      if (row.result === 'SKIPPED' && row.code !== 'ALREADY_ACCEPTED') {
        skippedRows.set(row.rowNumber, acceptSkipSentence(row.code))
      }
    }
    return step
  })
  statusLine.value = ''
  if (finished && skippedRows.size > 0) {
    skipped.value = [...skippedRows.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([rowNumber, sentence]) => ({ rowNumber, sentence }))
    actionNotice.value = `${skippedRows.size} row${skippedRows.size === 1 ? '' : 's'} could not be accepted yet.`
    await nextTick()
    noticeElement.value?.focus()
  }
}

async function checkAll(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || batch.value === null || running.value !== null) return
  const id = batch.value.id
  statusLine.value = 'Checking documents…'
  await runSteps('check', 'checking the documents', async () => checkBatchStep(workspaceId, id))
  statusLine.value = ''
}

// ---- Export --------------------------------------------------------------------------------------

const exportFormats = computed<{ value: 'DOCX' | 'PDF' | 'BOTH'; label: string }[]>(() =>
  templateKind.value === 'PDF'
    ? [{ value: 'PDF', label: 'PDF' }]
    : [
        { value: 'DOCX', label: 'Word' },
        { value: 'PDF', label: 'PDF' },
        { value: 'BOTH', label: 'Word and PDF' },
      ],
)

function openExport(): void {
  exportError.value = null
  exportFormat.value = exportFormats.value[0]!.value
  exportDialog.value?.open()
}

function closeExport(): void {
  exportDialog.value?.close()
}

/**
 * Approves and exports: one export run is made, then advance steps until
 * it completes. The run's items say which rows shipped and which were
 * left out, each with its reason. The archive itself only downloads from
 * a complete run, which the download route enforces as well.
 */
async function approveAndExport(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || batch.value === null || running.value !== null) return
  const id = batch.value.id
  exportError.value = null
  running.value = 'export'
  try {
    const created = await createBatchExport(workspaceId, id, crypto.randomUUID(), exportFormat.value)
    activeExport.value = created
    const total = created.items.length
    exportProgress.value = { done: total - created.items.filter((item) => item.outcome === 'PENDING').length, total }
    for (let busyTries = 0; ; ) {
      try {
        waitingBusy.value = false
        const step = await advanceBatchExport(workspaceId, id, created.id)
        exportProgress.value = { done: total - step.remaining, total }
        await load()
        busyTries = 0
        if (step.state === 'COMPLETE') {
          activeExport.value = await getBatchExport(workspaceId, id, created.id)
          exportProgress.value = null
          closeExport()
          return
        }
        const next = stepNext(step)
        if (next.kind === 'stuck') {
          exportError.value = STEP_NO_PROGRESS
          exportProgress.value = null
          return
        }
        if (next.kind === 'wait') await pause(next.ms)
      } catch (error) {
        if (batchBusy(error) && busyTries < BUSY_TRIES) {
          busyTries++
          waitingBusy.value = true
          await pause(BUSY_WAIT_MS)
          continue
        }
        exportError.value = batchBusy(error)
          ? 'Another step for you is still running and did not leave room in time. Try again in a moment.'
          : stepFailureWords(error, 'exporting the documents')
        exportProgress.value = null
        return
      }
    }
  } catch (error) {
    exportError.value = stepFailureWords(error, 'starting the export')
    exportProgress.value = null
  } finally {
    running.value = null
    waitingBusy.value = false
  }
}

// ---- Delete ----------------------------------------------------------------------------------------

async function removeBatch(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || batch.value === null || deleting.value) return
  deleting.value = true
  actionError.value = null
  const title = batch.value.title
  try {
    await deleteBatch(workspaceId, batch.value.id)
    await router.push({ name: 'home', query: { batchDeleted: title } })
  } catch (error) {
    actionError.value = batchBusy(error)
      ? 'Another step for you is still running, so the batch could not be deleted. Try again in a moment.'
      : stepFailureWords(error, 'deleting the batch')
    confirmingDelete.value = false
  } finally {
    deleting.value = false
  }
}

function confirmLeaving(): boolean {
  if (running.value === null) return true
  return window.confirm('Brownie stops if you leave; it continues when you come back.')
}

onBeforeRouteLeave(() => confirmLeaving())

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (running.value === null) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  void loadCapabilities()
    .then((caps) => {
      composeOffered.value = caps.batchComposeOffered === true
    })
    .catch(() => {})
  void load().then(() => heading.value?.focus())
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))
</script>

<template>
  <section class="page-column batch" aria-labelledby="batch-heading">
    <!-- The page's one polite live region: always here, its text is what changes. -->
    <p class="visually-hidden" role="status">{{ announcement }}</p>

    <p v-if="loadState === 'loading'" class="field-hint">Opening the batch…</p>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">{{ loadError }}</p>

    <template v-else-if="batch !== null">
      <div class="page-head">
        <h1 id="batch-heading" ref="heading" class="page-title" tabindex="-1">{{ batch.title }}</h1>
        <p class="page-lede">
          {{ counts.made }} of {{ batch.rowCount }} documents made · {{ counts.accepted }} accepted ·
          {{ counts.checked }} checked · {{ counts.blocking }} blocking ·
          {{ counts.suggestions }} suggestion{{ counts.suggestions === 1 ? '' : 's' }} waiting ·
          {{ counts.exported }} exported
        </p>
      </div>

      <p v-if="actionError" class="field-error" role="alert">{{ actionError }}</p>
      <p v-if="actionNotice" ref="noticeElement" class="batch__notice" tabindex="-1">{{ actionNotice }}</p>
      <p v-if="noAllowance" class="field-hint">{{ noAllowance }}</p>
      <p v-if="statusLine" class="field-hint">{{ statusLine }}</p>
      <p v-if="waitingBusy" class="field-hint">
        Brownie is doing something else for you just now; it will try again in a moment.
      </p>
      <p v-if="running !== null" class="field-hint">
        Brownie stops if you leave; it continues when you come back.
      </p>

      <div class="batch__actions">
        <button
          v-if="counts.pending > 0"
          type="button"
          class="button button--primary"
          :disabled="running !== null"
          @click="continueMaking"
        >
          Continue making documents ({{ counts.pending }} left)
        </button>
        <button
          v-if="composeOffered && counts.composePending > 0"
          type="button"
          class="button button--secondary"
          :disabled="running !== null"
          @click="writeTexts"
        >
          Write {{ counts.composePending }} text{{ counts.composePending === 1 ? '' : 's' }} with Brownie
        </button>
        <button
          type="button"
          class="button button--secondary"
          :disabled="running !== null || counts.made === 0"
          @click="acceptReady"
        >
          Accept rows that are ready
        </button>
        <button
          type="button"
          class="button button--secondary"
          :disabled="running !== null || counts.made === 0"
          @click="checkAll"
        >
          Check all
        </button>
        <button
          type="button"
          class="button button--secondary"
          :disabled="running !== null || counts.made === 0"
          @click="openExport"
        >
          <AppIcon name="download" :size="16" />
          Export…
        </button>
        <button
          v-if="!confirmingDelete"
          type="button"
          class="button button--quiet"
          :disabled="running !== null"
          @click="confirmingDelete = true"
        >
          <AppIcon name="trash" :size="16" />
          Delete batch
        </button>
        <span v-else class="batch__delete-confirm">
          <span>
            Brownie deletes this batch now, and the spreadsheet you uploaded for it within a day. The
            {{ counts.made }} documents stay in your documents.
          </span>
          <button type="button" class="button button--danger button--small" :disabled="deleting" @click="removeBatch">
            Delete batch
          </button>
          <button type="button" class="button button--secondary button--small" :disabled="deleting" @click="confirmingDelete = false">
            Keep it
          </button>
        </span>
      </div>

      <div v-if="activeExport !== null && activeExport.state === 'COMPLETE'" class="panel batch__export-done">
        <a v-if="downloadUrl" class="button button--primary" :href="downloadUrl" download>
          <AppIcon name="download" :size="16" />
          {{ downloadName }}
        </a>
        <ul v-if="leftOutItems.length > 0" class="batch__left-out">
          <li v-for="item in leftOutItems" :key="item.rowNumber">
            Row {{ item.rowNumber }}: {{ exportOutcomeSentence(item.outcome, item.detail, findingFieldName) }}
            <RouterLink
              v-if="rows.find((row) => row.rowNumber === item.rowNumber)?.documentId != null"
              :to="`/documents/${rows.find((row) => row.rowNumber === item.rowNumber)!.documentId}`"
            >
              {{ rows.find((row) => row.rowNumber === item.rowNumber)!.documentTitle ?? 'Open the document' }}
            </RouterLink>
          </li>
        </ul>
      </div>

      <ul v-if="skipped.length > 0" class="batch__skipped">
        <li v-for="entry in skipped" :key="entry.rowNumber">Row {{ entry.rowNumber }}: {{ entry.sentence }}</li>
      </ul>

      <!-- A wide table scrolls inside this focusable region on a narrow screen, so the page itself never scrolls sideways. -->
      <div v-if="rows.length > 0" class="batch__table-scroll" role="region" aria-label="Every row and where it stands" tabindex="0">
      <table class="batch__table">
        <caption>
          Every row of "{{ batch.title }}" and where it stands
        </caption>
        <thead>
          <tr>
            <th scope="col">Row</th>
            <th scope="col">Document</th>
            <th scope="col">Status</th>
            <th scope="col">Notes</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.rowNumber">
            <td>{{ row.rowNumber }}</td>
            <td>
              <RouterLink v-if="rowDocument(row).id !== null" :to="`/documents/${rowDocument(row).id}`">
                {{ rowDocument(row).label }}
              </RouterLink>
              <span v-else>{{ rowDocument(row).label }}</span>
            </td>
            <td>{{ rowStatus(row) }}</td>
            <td>
              <ul v-if="rowNotes(row).length > 0" class="batch__notes">
                <li v-for="(note, index) in rowNotes(row)" :key="index">{{ note }}</li>
              </ul>
            </td>
          </tr>
        </tbody>
      </table>
      </div>

      <SpotDialogShell
        ref="exportDialog"
        title="Export"
        caption="Approve these documents and export them together"
        :busy="running === 'export'"
        :status="exportProgress ? `Exporting: ${exportProgress.done} of ${exportProgress.total}` : ''"
        :error="exportError"
        @closed="closeExport"
      >
        <p>
          Brownie checks each document first. Rows with something that blocks export, a suggestion waiting,
          or a change after you approve are left out and listed.
        </p>
        <fieldset class="batch__format">
          <legend class="field-label">Export as</legend>
          <label v-for="option in exportFormats" :key="option.value" class="field--inline batch__check">
            <input v-model="exportFormat" type="radio" name="export-format" :value="option.value" />
            <span>{{ option.label }}</span>
          </label>
        </fieldset>
        <template #foot>
          <button
            type="button"
            class="button button--primary"
            :disabled="running === 'export' || exportableCount === 0"
            @click="approveAndExport"
          >
            Approve and export {{ exportableCount }} document{{ exportableCount === 1 ? '' : 's' }}
          </button>
        </template>
      </SpotDialogShell>
    </template>
  </section>
</template>

<style scoped>
.batch__notice {
  padding: var(--space-3);
  border-radius: var(--radius);
  background: var(--color-honey-soft);
}

.batch__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  align-items: center;
  margin-block: var(--space-4);
}

.batch__delete-confirm {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius);
}

.batch__export-done {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  align-items: flex-start;
  margin-block: var(--space-4);
}

.batch__left-out,
.batch__skipped {
  margin: 0;
  padding-left: var(--space-5);
}

/* A wide table scrolls inside this wrapper, never the page; the wrapper is focusable so a keyboard can scroll it. */
.batch__table-scroll {
  overflow-x: auto;
  max-inline-size: 100%;
  margin-top: var(--space-4);
}

.batch__table {
  border-collapse: collapse;
  width: 100%;
  min-width: max-content;
}

.batch__table caption {
  text-align: left;
  font-weight: var(--font-weight-medium);
  padding-block: var(--space-2);
}

.batch__table th,
.batch__table td {
  text-align: left;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--color-hairline);
  vertical-align: top;
}

.batch__notes {
  margin: 0;
  padding-left: var(--space-4);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.batch__format {
  border: none;
  padding: 0;
  margin: var(--space-3) 0 0;
  display: flex;
  gap: var(--space-4);
  align-items: baseline;
}

.field--inline {
  display: inline-flex;
  gap: var(--space-2);
  align-items: center;
}

/* Radios, the size and colour the existing dialogs draw them. */
.batch__check input {
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0;
  accent-color: var(--color-cocoa);
}
</style>
