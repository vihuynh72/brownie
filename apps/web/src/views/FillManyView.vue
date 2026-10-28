<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { useSessionStore } from '@/stores/session'
import { useTemplatesStore } from '@/stores/templates'
import { describeCommonFailure } from '@/api/failures'
import {
  allocateUpload,
  ApiRequestError,
  completeUpload,
  createBatch,
  fillBatchStep,
  getTemplateVersion,
  readArtifactTable,
  uploadArtifactContent,
  type CapabilitiesResponse,
  type TableReadingResponse,
  type TemplateResponse,
  type TemplateVersionResponse,
} from '@/api/client'
import {
  batchBusy,
  batchStepSentence,
  defaultChoices,
  defaultTitleColumn,
  previewDocuments,
  refusedSpreadsheetSentence,
  repeatedSpots,
  STEP_NO_PROGRESS,
  stepNext,
  tableUnreadableSentence,
  unmappedRequired,
  type FieldChoice,
} from '@/batch/fillMany'
import { refusalParts } from '@/upload/learnAndStart'
import { fieldLabel } from '@/workspace/layout'

/**
 * Fill from a spreadsheet: choose a workbook or CSV, check how its columns
 * land on this template's places to fill over the first rows, then make
 * one document per row. The heavy steps are the server's, called in
 * bounded pieces; while they run the page says where the count stands and
 * a leave that cannot wait does not lose the batch -- it keeps the rows it
 * already made and the person comes back to it.
 */
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const templates = useTemplatesStore()

const templateId = computed(() => Number(route.params.templateId))

const template = ref<TemplateResponse | null>(null)
const version = ref<TemplateVersionResponse | null>(null)
const capabilities = ref<CapabilitiesResponse | null>(null)
const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<string | null>(null)

const fileInput = ref<HTMLInputElement | null>(null)
const chooseButton = ref<HTMLButtonElement | null>(null)
const heading = ref<HTMLElement | null>(null)

/** choose → reading the file → mapping → creating+fill loop. */
const stage = ref<'choose' | 'reading' | 'mapping' | 'creating'>('choose')
const error = ref<string | null>(null)
/** A file Brownie would not take: the reason in the same two parts Home gives a refused form. */
const fileRefusal = ref<{ name: string; message: string } | null>(null)
const statusLine = ref('')
const fileName = ref('')

const artifactId = ref<number | null>(null)
const reading = ref<TableReadingResponse | null>(null)
const sheet = ref<string | null>(null)
const hasHeaderRow = ref(true)
const dateOrder = ref<'MDY' | 'DMY'>('MDY')
const readingAgain = ref(false)

const choices = ref<Map<string, FieldChoice>>(new Map())
const titleColumn = ref<number | null>(null)

const making = ref(false)
const madeCount = ref(0)
const waitingBusy = ref(false)

const fields = computed(() => version.value?.fields ?? [])
const columns = computed(() => reading.value?.columns ?? [])
const repeated = computed(() => repeatedSpots(fields.value))
const blockingRepeated = computed(() => repeated.value.some((entry) => entry.blocking))
const unmapped = computed(() => unmappedRequired(fields.value, choices.value))
const composeOffered = computed(() => capabilities.value?.batchComposeOffered === true)

const preview = computed(() =>
  reading.value !== null && template.value !== null
    ? previewDocuments(reading.value, fields.value, choices.value, titleColumn.value, template.value.displayName)
    : [],
)

/** The number the button names: data rows, blank or not -- the server skips the blank ones itself. */
const documentCount = computed(() => reading.value?.rowCount ?? 0)

const canMake = computed(
  () => reading.value !== null && !blockingRepeated.value && unmapped.value.length === 0 && !making.value,
)

const accept = computed(() => {
  const types = capabilities.value?.tableFileTypes
  if (!types || types.length === 0) return '.xlsx,.csv'
  return types.map((type) => type.extension).join(',')
})

const uploadLimit = computed(() =>
  capabilities.value && capabilities.value.maxUploadBytes > 0 ? capabilities.value.maxUploadBytes : null,
)

/**
 * The page's one polite live region: it stays in the page while empty,
 * because a region that only appears with its first message is not
 * reliably read out. What it says follows where the work stands.
 */
const announcement = computed(() => {
  if (loadState.value === 'loading') return 'Opening this template…'
  if (waitingBusy.value) {
    return 'Brownie is doing something else for you just now; it will try again in a moment.'
  }
  if (making.value) return `Making documents: ${madeCount.value} of ${documentCount.value}`
  return statusLine.value
})

/** A refused file's reason and, where the sentence carries it, the way forward. */
const refusal = computed(() => (fileRefusal.value ? refusalParts(fileRefusal.value.message) : null))

/** "Open it, save it again..." after "What you can do:" reads as the rest of that sentence. */
function afterColon(sentence: string): string {
  return sentence.charAt(0).toLowerCase() + sentence.slice(1)
}

/** Choosing again from the card is the same choose the button starts; focus goes back to it afterwards. */
async function backToChoose(): Promise<void> {
  fileRefusal.value = null
  await nextTick()
  chooseButton.value?.focus()
}

/** The notices about the read, in the person's words; each names its rows where the read gave them. */
const noticeSentences = computed(() => {
  const sentences: string[] = []
  for (const notice of reading.value?.notices ?? []) {
    const rows = notice.rows ?? []
    const named = rows.length <= 8 ? `row${rows.length === 1 ? '' : 's'} ${rows.join(', ')}` : `${rows.length} rows`
    switch (notice.code) {
      case 'BLANK_ROWS':
        sentences.push(
          `${rows.length === 1 ? `Row ${rows[0]} is` : `${named} ${rows.length <= 8 ? 'are' : 'are'}`} blank and will be skipped.`,
        )
        break
      case 'DUPLICATE_ROWS':
        sentences.push(`Rows ${rows.join(', ')} repeat an earlier row, so the same document would come out twice.`)
        break
      case 'FORMULAS_WITHOUT_RESULTS':
        sentences.push(
          `Formulas in ${named} have no saved result; those cells fill as empty. ` +
            'Open the file in Excel and save it again, then choose it again, for the answers to fill.',
        )
        break
      case 'ERROR_VALUES':
        sentences.push(`Cells in ${named} are error values such as #N/A; those cells fill as empty.`)
        break
      default:
        break
    }
  }
  return sentences
})

/**
 * For every DATE spot mapped to a column, how many of the rows the preview
 * sees are not dates. The count covers the first rows only -- what the
 * reading returned -- and the sentence says so.
 */
const dateWarnings = computed(() => {
  const warnings: string[] = []
  const rows = reading.value?.previewRows ?? []
  for (const field of fields.value) {
    if (field.cardinality !== 'SCALAR' || field.type !== 'DATE') continue
    const column = choices.value.get(field.fieldId)?.column
    if (column == null) continue
    const seen = rows.filter((row) => !row.blank)
    const notDates = seen.filter((row) => {
      const cell = row.cells[column]
      return cell != null && cell.problem == null && cell.display.trim() !== '' && cell.date == null
    }).length
    if (notDates > 0) {
      warnings.push(
        `In the rows shown, ${notDates} ${notDates === 1 ? 'cell is' : 'cells are'} in the ${fieldLabel(field)} column ` +
          `not a date; those documents will have it empty.`,
      )
    }
  }
  return warnings
})

function title(): string {
  return template.value ? `Fill ${template.value.displayName} from a spreadsheet` : 'Fill from a spreadsheet'
}

async function load(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  loadState.value = 'loading'
  loadError.value = null
  try {
    await templates.refresh(workspaceId)
    const found = templates.usable.find((entry) => entry.id === templateId.value) ?? null
    if (found === null || found.currentActiveVersionId == null) {
      loadState.value = 'error'
      loadError.value =
        'This template is not there to fill from. It may be in the Trash Bin: restore it, or choose another.'
      return
    }
    template.value = found
    const [versionAnswer, caps] = await Promise.all([
      getTemplateVersion(workspaceId, found.id, found.currentActiveVersionId),
      loadCapabilities().catch(() => null),
    ])
    version.value = versionAnswer
    capabilities.value = caps
    loadState.value = 'ready'
    await nextTick()
    heading.value?.focus()
  } catch (loadFailure) {
    loadState.value = 'error'
    loadError.value =
      describeCommonFailure(loadFailure, 'this template') ??
      'Brownie could not open this template. If this keeps happening, let whoever runs this Brownie know.'
  }
}

function chooseFile(): void {
  fileInput.value?.click()
}

/** What to say when the upload itself refused the file, before any table reading was tried. */
function uploadRefusal(uploadError: unknown, file: File): string {
  if (uploadError instanceof ApiRequestError && uploadError.status === 413) {
    if (uploadLimit.value !== null && file.size > uploadLimit.value) {
      return `This file is larger than the ${formatBytes(uploadLimit.value)} upload limit, so it was not uploaded.`
    }
    return 'This file is larger than Brownie accepts, so it was not uploaded.'
  }
  if (uploadError instanceof ApiRequestError && uploadError.status === 415) {
    const reason = uploadError.problem?.reason
    if (reason === 'SPREADSHEET') return refusedSpreadsheetSentence()
    if (reason === 'PASSWORD_PROTECTED' || reason === 'RIGHTS_PROTECTED' || reason === 'DAMAGED') {
      return 'Brownie could not read this file. It may be damaged: open it, save it again, and choose it again.'
    }
    return 'This is a document, not a spreadsheet. Choose a .xlsx or .csv file.'
  }
  return `Could not upload "${file.name}". ${describeCommonFailure(uploadError, 'a way to upload files') ?? 'Try again.'}`
}

async function onFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const workspaceId = session.personalWorkspaceId
  if (!file || workspaceId === undefined) return
  fileName.value = file.name
  error.value = null
  fileRefusal.value = null
  if (uploadLimit.value !== null && file.size > uploadLimit.value) {
    fileRefusal.value = {
      name: file.name,
      message: `This file is larger than the ${formatBytes(uploadLimit.value)} upload limit, so it was not uploaded.`,
    }
    return
  }
  stage.value = 'reading'
  statusLine.value = `Uploading "${file.name}"…`
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    const uploaded = await uploadArtifactContent(workspaceId, allocated.id, file)
    statusLine.value = `Checking "${file.name}"…`
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      fileRefusal.value = {
        name: file.name,
        message:
          completed.status === 'REJECTED' && completed.rejectionReason === 'MALWARE_DETECTED'
            ? `Brownie did not accept "${file.name}": the malware scan flagged it.`
            : `Brownie did not accept "${file.name}": it did not pass Brownie's checks.`,
      }
      stage.value = 'choose'
      return
    }
    // A document uploads fine; only the table read says so -- unless the upload already knows it is a document type,
    // which names the refusal plainly without a read that could only repeat it.
    const detected = uploaded.detectedMediaType ?? completed.detectedMediaType
    if (detected != null && detected !== 'XLSX' && detected !== 'PLAIN_TEXT') {
      fileRefusal.value = {
        name: file.name,
        message: 'This is a document, not a spreadsheet. Choose a .xlsx or .csv file.',
      }
      stage.value = 'choose'
      return
    }
    artifactId.value = allocated.id
    statusLine.value = `Reading "${file.name}"…`
    await readTable()
    if (stage.value === 'reading') stage.value = 'mapping'
  } catch (uploadError) {
    fileRefusal.value = { name: file.name, message: uploadRefusal(uploadError, file) }
    stage.value = 'choose'
  } finally {
    // Whatever the outcome, the "Checking..." line goes with it.
    statusLine.value = ''
  }
}

/**
 * Reads the chosen file as a table with the sheet, header and date choices
 * as they stand; a refusal lands in `error` and the page offers the file
 * step again.
 */
async function readTable(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || artifactId.value === null) return
  try {
    const answer = await readArtifactTable(workspaceId, artifactId.value, {
      sheet: sheet.value ?? undefined,
      hasHeaderRow: hasHeaderRow.value,
      dateOrder: dateOrder.value,
    })
    reading.value = answer
    hasHeaderRow.value = answer.hasHeaderRow
    sheet.value = answer.sheet
    choices.value = defaultChoices(fields.value, answer.columns)
    titleColumn.value = defaultTitleColumn(fields.value, choices.value)
    stage.value = 'mapping'
    await nextTick()
    heading.value?.focus()
  } catch (readError) {
    const message =
      readError instanceof ApiRequestError && readError.problem?.code === 'TABLE_UNREADABLE'
        ? tableUnreadableSentence(readError.problem, fileName.value)
        : (batchStepSentence(readError) ??
          describeCommonFailure(readError, 'this file') ??
          `Brownie could not read "${fileName.value}" as a table. Try again.`)
    if (reading.value === null) {
      // A file that was never read is a refused file: the card says why and offers choosing another.
      fileRefusal.value = { name: fileName.value, message }
      stage.value = 'choose'
    } else {
      error.value = message
    }
  }
}

/** Sheet, header and date choices re-read the same upload; nothing is chosen twice. */
async function reread(): Promise<void> {
  if (readingAgain.value) return
  readingAgain.value = true
  error.value = null
  try {
    await readTable()
  } finally {
    readingAgain.value = false
  }
}

function optionLabel(column: { index: number; letter: string; name: string }): string {
  return `${column.letter} · ${column.name}`
}

function setColumn(fieldId: string, value: string): void {
  const choice = choices.value.get(fieldId)
  if (!choice) return
  choice.column = value === '' ? null : Number(value)
  if (choice.column === null) choice.mode = 'VALUE'
  choices.value = new Map(choices.value)
}

function setMode(fieldId: string, mode: 'VALUE' | 'COMPOSE'): void {
  const choice = choices.value.get(fieldId)
  if (!choice) return
  choice.mode = mode
  choices.value = new Map(choices.value)
}

const BUSY_WAIT_MS = 2000
const BUSY_TRIES = 15

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Creates the batch, then calls fill in bounded steps until nothing is
 * left. The loop stops at the first refusal that is not BUSY; BUSY waits
 * and asks again a bounded number of times. When a step fails partway the
 * progress already made stays made, so "Try again" asks the same way and
 * the server simply does the rows still pending.
 */
async function makeDocuments(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || artifactId.value === null || reading.value === null || !canMake.value) return
  making.value = true
  waitingBusy.value = false
  error.value = null
  try {
    const mapping = fields.value
      .filter((field) => field.cardinality === 'SCALAR')
      .flatMap((field) => {
        const choice = choices.value.get(field.fieldId)
        if (choice?.column == null) return []
        return [
          {
            fieldId: field.fieldId,
            column: choice.column,
            mode: choice.mode,
          },
        ]
      })
    const batch = await createBatch(workspaceId, crypto.randomUUID(), {
      templateId: template.value!.id,
      templateVersionId: version.value!.id,
      artifactId: artifactId.value,
      sheet: sheet.value ?? undefined,
      hasHeaderRow: hasHeaderRow.value,
      dateOrder: dateOrder.value,
      mapping,
      titleColumn: titleColumn.value ?? undefined,
    })
    for (let busyTries = 0; ; ) {
      try {
        waitingBusy.value = false
        const step = await fillBatchStep(workspaceId, batch.id)
        madeCount.value = batch.rowCount - step.remaining
        busyTries = 0
        const next = stepNext(step)
        if (next.kind === 'done') {
          // The work is done, so the leave guard and the unload warning
          // must not treat the way to the batch page as leaving mid-step.
          making.value = false
          await router.push({ name: 'batch', params: { batchId: batch.id } })
          return
        }
        if (next.kind === 'stuck') {
          error.value = STEP_NO_PROGRESS
          return
        }
        if (next.kind === 'wait') {
          await pause(next.ms)
        }
      } catch (stepError) {
        if (batchBusy(stepError) && busyTries < BUSY_TRIES) {
          busyTries++
          waitingBusy.value = true
          await pause(BUSY_WAIT_MS)
          continue
        }
        error.value =
          batchBusy(stepError)
            ? 'Another step for you is still running and did not leave room in time. Try again in a moment.'
            : (batchStepSentence(stepError) ??
              describeCommonFailure(stepError, 'making the documents') ??
              'Brownie stopped making documents. Try again.')
        return
      }
    }
  } catch (createError) {
    error.value =
      batchStepSentence(createError) ??
      describeCommonFailure(createError, 'making the batch') ??
      'Brownie could not start making documents. Try again.'
  } finally {
    making.value = false
    waitingBusy.value = false
  }
}

/** The fill loop is the only stretch where leaving would interrupt work that resumes anyway. */
function confirmLeaving(): boolean {
  if (!making.value) return true
  return window.confirm('Brownie stops if you leave; it continues when you come back.')
}

onBeforeRouteLeave(() => confirmLeaving())

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (!making.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  void load()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))
</script>

<template>
  <section class="page-column fill-many" aria-labelledby="fill-many-heading">
    <div class="page-head">
      <h1 id="fill-many-heading" ref="heading" class="page-title" tabindex="-1">{{ title() }}</h1>
      <p class="page-lede">Brownie makes one document from each row of an Excel workbook or a CSV.</p>
    </div>

    <!-- The page's one polite live region: always here, its text is what changes. -->
    <p class="visually-hidden" role="status">{{ announcement }}</p>

    <p v-if="loadState === 'loading'" class="field-hint">Opening this template…</p>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">{{ loadError }}</p>

    <template v-else>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <p v-if="statusLine" class="field-hint">{{ statusLine }}</p>
      <p v-if="waitingBusy" class="field-hint">
        Brownie is doing something else for you just now; it will try again in a moment.
      </p>

      <div v-if="stage === 'choose' || stage === 'reading'" class="panel fill-many__choose">
        <p>
          Choose an Excel workbook (.xlsx) or a CSV file.
          <template v-if="capabilities?.batchMaxRows">Up to {{ capabilities.batchMaxRows }} rows make one batch.</template>
        </p>
        <p class="field-hint">
          The first row should hold column names such as “Member name” or “Date awarded”. Each row fills one document.
        </p>

        <!-- A file Brownie would not take: which file, why, and the way forward -- as Home's refused card tells it. -->
        <div v-if="fileRefusal !== null && refusal !== null" class="fill-many__refusal">
          <p class="fill-many__refusal-name" :title="fileRefusal.name">{{ fileRefusal.name }}</p>
          <p class="fill-many__refusal-reason" role="alert">{{ refusal.reason }}</p>
          <p v-if="refusal.advice" class="field-hint">What you can do: {{ afterColon(refusal.advice) }}</p>
          <div class="fill-many__refusal-actions">
            <button type="button" class="button button--primary button--small" @click="chooseFile">
              <AppIcon name="upload" :size="16" />
              <span>Choose another file</span>
            </button>
            <button type="button" class="button button--quiet button--small" @click="backToChoose">Dismiss</button>
          </div>
        </div>

        <button
          ref="chooseButton"
          type="button"
          class="button button--primary"
          :disabled="stage === 'reading'"
          @click="chooseFile"
        >
          <AppIcon name="upload" :size="16" />
          Choose a spreadsheet
        </button>
        <input
          ref="fileInput"
          type="file"
          class="visually-hidden"
          :accept="accept"
          data-test="fill-many-file"
          @change="onFileChosen"
        />
      </div>

      <template v-if="stage === 'mapping' && reading !== null">
        <div class="panel fill-many__table">
          <h2 class="fill-many__section-title">{{ fileName }}</h2>

          <div class="fill-many__read-options">
            <label v-if="reading.sheetNames.length > 1" class="field">
              <span class="field-label">Sheet</span>
              <select
                class="text-field fill-many__select"
                :value="sheet"
                :disabled="readingAgain"
                @change="sheet = ($event.target as HTMLSelectElement).value; reread()"
              >
                <option v-for="name in reading.sheetNames" :key="name" :value="name">{{ name }}</option>
              </select>
            </label>
            <label class="field field--inline fill-many__check">
              <input
                type="checkbox"
                :checked="hasHeaderRow"
                :disabled="readingAgain"
                @change="hasHeaderRow = ($event.target as HTMLInputElement).checked; reread()"
              />
              <span>The first row holds column names</span>
            </label>
            <fieldset v-if="reading.ambiguousDates" class="field fill-many__date-order">
              <legend class="field-label">Dates like 03/04/2026 mean</legend>
              <label class="field--inline fill-many__check">
                <input
                  type="radio"
                  name="date-order"
                  value="MDY"
                  :checked="dateOrder === 'MDY'"
                  :disabled="readingAgain"
                  @change="dateOrder = 'MDY'; reread()"
                />
                <span>March 4</span>
              </label>
              <label class="field--inline fill-many__check">
                <input
                  type="radio"
                  name="date-order"
                  value="DMY"
                  :checked="dateOrder === 'DMY'"
                  :disabled="readingAgain"
                  @change="dateOrder = 'DMY'; reread()"
                />
                <span>3 April</span>
              </label>
            </fieldset>
          </div>

          <ul v-if="noticeSentences.length > 0 || dateWarnings.length > 0" class="fill-many__notices">
            <li v-for="(sentence, index) in [...noticeSentences, ...dateWarnings]" :key="index">{{ sentence }}</li>
          </ul>

          <h3 class="fill-many__section-title">Match the columns to the places to fill</h3>
          <ul class="fill-many__mapping">
            <li v-for="entry in repeated" :key="`repeated-${entry.field.fieldId}`" class="fill-many__field">
              <span class="fill-many__label">
                {{ fieldLabel(entry.field)
                }}<span v-if="entry.blocking" class="fill-many__required" aria-hidden="true">*</span><span v-if="entry.blocking" class="visually-hidden">, required before export</span>
              </span>
              <span class="field-hint">This place takes a list and can't be filled from a spreadsheet row.</span>
            </li>
            <li v-for="field in fields.filter((f) => f.cardinality === 'SCALAR')" :key="field.fieldId" class="fill-many__field">
              <label class="fill-many__label" :for="`map-${field.fieldId}`">
                {{ fieldLabel(field)
                }}<span v-if="field.requiredness === 'REQUIRED'" class="fill-many__required" aria-hidden="true">*</span><span v-if="field.requiredness === 'REQUIRED'" class="visually-hidden">, required before export</span>
              </label>
              <select
                :id="`map-${field.fieldId}`"
                class="text-field fill-many__select"
                :value="choices.get(field.fieldId)?.column ?? ''"
                @change="setColumn(field.fieldId, ($event.target as HTMLSelectElement).value)"
              >
                <option value="">Don't fill</option>
                <option v-for="column in columns" :key="column.index" :value="column.index">
                  {{ optionLabel(column) }}
                </option>
              </select>
              <label
                v-if="composeOffered && field.type === 'TEXT'"
                class="field--inline fill-many__compose fill-many__check"
              >
                <input
                  type="checkbox"
                  :checked="choices.get(field.fieldId)?.mode === 'COMPOSE'"
                  :disabled="choices.get(field.fieldId)?.column == null"
                  @change="setMode(field.fieldId, ($event.target as HTMLInputElement).checked ? 'COMPOSE' : 'VALUE')"
                />
                <span>Brownie writes it from this column's notes</span>
              </label>
            </li>
          </ul>

          <label class="field fill-many__title-column">
            <span class="field-label">Name each document after</span>
            <select
              class="text-field fill-many__select"
              :value="titleColumn ?? ''"
              @change="titleColumn = ($event.target as HTMLSelectElement).value === '' ? null : Number(($event.target as HTMLSelectElement).value)"
            >
              <option value="">its row number</option>
              <option v-for="column in columns" :key="column.index" :value="column.index">
                {{ optionLabel(column) }}
              </option>
            </select>
          </label>

          <p v-if="unmapped.length > 0" class="field-error">
            These places have to be filled and no column is chosen for them:
            {{ unmapped.map((field) => fieldLabel(field)).join(', ') }}.
          </p>
          <p v-if="blockingRepeated" class="field-error">
            {{ template?.displayName }} has a place that takes a list, which a spreadsheet row cannot fill.
            This template cannot be filled from a spreadsheet.
          </p>

          <h3 class="fill-many__section-title">The first documents</h3>
          <!-- The table scrolls inside this focusable region on a narrow screen, so the page itself never scrolls sideways. -->
          <div class="fill-many__preview-scroll" role="region" aria-label="The first documents" tabindex="0">
          <table class="fill-many__preview">
            <caption class="visually-hidden">How the first rows would read as documents</caption>
            <thead>
              <tr>
                <th scope="col">Document</th>
                <th v-for="field in fields.filter((f) => f.cardinality === 'SCALAR' && choices.get(f.fieldId)?.column != null)" :key="field.fieldId" scope="col">
                  {{ fieldLabel(field) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="doc in preview" :key="doc.rowNumber">
                <td>{{ doc.title }}</td>
                <td
                  v-for="field in fields.filter((f) => f.cardinality === 'SCALAR' && choices.get(f.fieldId)?.column != null)"
                  :key="field.fieldId"
                  :class="{ 'fill-many__cell-problem': doc.cells.find((c) => c.fieldId === field.fieldId)?.problem || doc.cells.find((c) => c.fieldId === field.fieldId)?.notADate || doc.cells.find((c) => c.fieldId === field.fieldId)?.notACheckbox }"
                >
                  {{ doc.cells.find((c) => c.fieldId === field.fieldId)?.display ?? '' }}
                </td>
              </tr>
            </tbody>
          </table>
          </div>
        </div>
      </template>

      <div class="fill-many__actions">
        <button
          v-if="stage === 'mapping'"
          type="button"
          class="button button--primary"
          :disabled="!canMake"
          @click="makeDocuments"
        >
          {{ making ? 'Making documents…' : `Make ${documentCount} document${documentCount === 1 ? '' : 's'}` }}
        </button>
        <p v-if="making" class="field-hint">
          Making documents: {{ madeCount }} of {{ documentCount }}
        </p>
        <button
          v-if="stage === 'mapping' && error"
          type="button"
          class="button button--secondary"
          :disabled="making"
          @click="makeDocuments"
        >
          Try again
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.fill-many__choose {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  align-items: flex-start;
}

.fill-many__table {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.fill-many__section-title {
  font-size: var(--font-size-lg);
  margin: 0;
}

.fill-many__read-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: baseline;
}

.field {
  display: flex;
  flex-direction: column;
}

.field--inline {
  display: inline-flex;
  flex-direction: row;
  gap: var(--space-2);
  align-items: center;
}

.fill-many__date-order {
  border: none;
  padding: 0;
  margin: 0;
  display: flex;
  gap: var(--space-3);
  align-items: baseline;
}

.fill-many__notices {
  margin: 0;
  padding-left: var(--space-5);
  color: var(--cocoa-700);
}

.fill-many__mapping {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.fill-many__field {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  align-items: baseline;
}

.fill-many__label {
  min-width: 12rem;
  font-weight: 600;
}

/* The workspace's own required mark: a red asterisk, with "required" said in the label. */
.fill-many__required {
  margin-left: var(--space-2);
  color: var(--color-error);
  font-weight: 500;
}

.fill-many__select {
  inline-size: auto;
  min-inline-size: 14rem;
  max-inline-size: 100%;
}

/* Checkbox and radio, the size and colour the existing dialogs draw them. */
.fill-many__check input {
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0;
  accent-color: var(--color-cocoa);
}

.fill-many__refusal {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  align-items: flex-start;
  padding: var(--space-3);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius);
}

.fill-many__refusal-name {
  font-weight: var(--font-weight-medium);
  margin: 0;
}

.fill-many__refusal-reason {
  margin: 0;
}

.fill-many__refusal-actions {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.fill-many__compose {
  display: inline-flex;
  gap: var(--space-2);
  align-items: center;
  font-size: var(--font-size-sm);
}

.fill-many__title-column {
  display: flex;
  gap: var(--space-3);
  align-items: baseline;
}

/* A wide table scrolls inside this wrapper, never the page; the wrapper is focusable so a keyboard can scroll it. */
.fill-many__preview-scroll {
  overflow-x: auto;
  max-inline-size: 100%;
}

.fill-many__preview {
  border-collapse: collapse;
  width: 100%;
  min-width: max-content;
}

.fill-many__preview th,
.fill-many__preview td {
  text-align: left;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--cocoa-200);
  vertical-align: top;
}

.fill-many__cell-problem {
  color: var(--error-700, #a33);
}

.fill-many__actions {
  display: flex;
  gap: var(--space-3);
  align-items: baseline;
  margin-top: var(--space-4);
}
</style>
