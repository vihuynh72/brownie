<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import CalendarEventPanel from '@/components/CalendarEventPanel.vue'
import DriveSavePanel from '@/components/DriveSavePanel.vue'
import {
  ApiRequestError,
  approveExport,
  artifactDownloadUrl,
  exportDocument,
  getLatestExportApproval,
  getLatestExportReceipt,
  getLatestValidation,
  validateDocument,
  type ExportApprovalResponse,
  type ExportFormat,
  type ExportReceiptResponse,
  type ValidationManifestResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'

/**
 * Taking the version of the document on screen to files the person can keep.
 *
 * Opening it does the steps nobody should have to ask for: it saves what the
 * page still holds, then checks the current version unless a check of that
 * exact version is already on record. What the check found decides what is
 * offered: a version with a problem that blocks export lists the problems,
 * each with a way to the fill spot it is about; any other version offers one
 * button that approves the chosen format and exports it straight away. The
 * files it made can be downloaded, shared from the person's own device, or
 * saved to Google Drive, and the event the document describes can be added
 * to Google Calendar.
 *
 * Everything shown is scoped to one revision. When the document moves on,
 * what was checked, approved or exported for the old revision is cleared,
 * because none of it describes the new one.
 */
const props = defineProps<{
  workspaceId: number
  documentId: number
  documentTitle: string
  /** The document's current revision; checking the document appends a new one. */
  currentRevisionId: number
  /** The page holds edits the server does not have yet. */
  unsavedWork: boolean
  /** Asks the page to save now; true when nothing is left unsaved. */
  saveBeforeExport: () => Promise<boolean>
  /** Asks the page to load the document again; true when that worked. */
  reloadDocument: () => Promise<boolean>
  /** The words the page shows for a field. */
  fieldLabel: (fieldId: string) => string
  /** The fields the page can move focus to. */
  editableFieldIds: ReadonlySet<string>
  /** A date the document holds, offered as the day of a calendar event. */
  suggestedEventDate: string | null
  /** The document is a PDF form: it is filled and exported as a PDF, and never becomes a Word file. */
  pdfOnly?: boolean
}>()

const emit = defineEmits<{
  /** The person wants to fix this field: the page closes the dialog and focuses its fill spot. */
  'go-to-field': [fieldId: string]
  closed: []
}>()

type Finding = ValidationManifestResponse['findings'][number]

const dialog = ref<HTMLDialogElement | null>(null)
const heading = ref<HTMLElement | null>(null)
const checkAgainButton = ref<HTMLButtonElement | null>(null)
const isOpen = ref(false)
/** Whether the browser drew the dialog as a real modal; without one it is laid over the page by hand. */
const nativeModal = ref(false)
let opener: HTMLElement | null = null

type Stage = 'idle' | 'saving' | 'checking' | 'checked' | 'failed'
const stage = ref<Stage>('idle')
/** The page could not save, so the version on the server is not the one the person sees. */
const saveRefused = ref(false)
const checkError = ref<string | null>(null)
const manifest = ref<ValidationManifestResponse | null>(null)
/** Set while a check the person asked for runs, so the button they pressed stays where their focus is. */
const checkAgainPressed = ref(false)

const exportFormat = ref<ExportFormat>(props.pdfOnly ? 'PDF' : 'BOTH')
const approval = ref<ExportApprovalResponse | null>(null)
const receipt = ref<ExportReceiptResponse | null>(null)
const exporting = ref(false)
const exportError = ref<string | null>(null)

const sharing = ref(false)
const shareError = ref<string | null>(null)

/**
 * Each clearing gets a number, and a request that answers after one drops its answer: a check or an export
 * that started before the document changed, or before the format did, describes something no longer asked for.
 */
let checkEpoch = 0
let exportEpoch = 0

const FORMAT_CHOICES: readonly { value: ExportFormat; label: string }[] = [
  { value: 'DOCX', label: 'Word (.docx)' },
  { value: 'PDF', label: 'PDF' },
  { value: 'BOTH', label: 'Word and PDF' },
]

/** The formats this document can be exported in: a PDF form only as a PDF. */
const formatChoices = computed(() => (props.pdfOnly ? FORMAT_CHOICES.filter((choice) => choice.value === 'PDF') : FORMAT_CHOICES))

watch(
  () => props.pdfOnly === true,
  (pdfOnly) => {
    if (pdfOnly) exportFormat.value = 'PDF'
    else if (exportFormat.value === 'PDF' && approval.value === null) exportFormat.value = 'BOTH'
  },
)

const busy = computed(() => stage.value === 'saving' || stage.value === 'checking' || exporting.value)

/** An older server may leave out either part; a finding that blocks export is trusted over a missing flag. */
const findings = computed<Finding[]>(() => {
  const list = manifest.value?.findings
  return Array.isArray(list) ? list : []
})
const blocking = computed(
  () => manifest.value?.hasUnresolvedBlocking === true || findings.value.some((finding) => finding.severity === 'BLOCKING'),
)

const checkAgainOffered = computed(
  () => manifest.value !== null || checkError.value !== null || saveRefused.value || checkAgainPressed.value,
)

function severityWord(severity: Finding['severity']): string {
  if (severity === 'BLOCKING') return 'Blocks export'
  if (severity === 'WARNING') return 'Warning'
  return 'Note'
}

function counted(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/** "1 warning", "1 warning and 2 notes", "1 thing that blocks export, 1 warning and 2 notes". */
function joined(parts: string[]): string {
  if (parts.length <= 1) return parts.join('')
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

const countSentence = computed(() => {
  const list = findings.value
  if (list.length === 0) return 'Brownie checked this version. It found nothing to fix.'
  const blocks = list.filter((finding) => finding.severity === 'BLOCKING').length
  const warnings = list.filter((finding) => finding.severity === 'WARNING').length
  const notes = list.length - blocks - warnings
  const parts: string[] = []
  if (blocks > 0) parts.push(counted(blocks, 'thing that blocks export', 'things that block export'))
  if (warnings > 0) parts.push(counted(warnings, 'warning', 'warnings'))
  if (notes > 0) parts.push(counted(notes, 'note', 'notes'))
  return `Brownie checked this version. It found ${joined(parts)}.`
})

/**
 * Says what was exported in terms of what was asked for. A Word-only export is complete, not "only one
 * file"; and when a PDF was asked for and could not be made, the Word file is offered with the reason.
 */
const outcome = computed(() => {
  const made = receipt.value
  if (!made) return ''
  const hasPdf = made.pdfArtifactId != null
  switch (made.format) {
    case 'DOCX':
      return 'DOCX exported.'
    case 'PDF':
      return hasPdf ? 'PDF exported.' : 'The PDF could not be made for this version, so the DOCX is offered instead.'
    default:
      return hasPdf ? 'Both files exported.' : 'The DOCX was exported; the PDF could not be made for this version.'
  }
})

/**
 * Where things stand, in one line at the top of the dialog: the dialog's only polite live region, since the
 * page's own region is out of reach while the dialog is modal. It is the visible sentence itself rather than a
 * hidden copy of it, so each sentence is read once and appears on the page once. Problems that block export and
 * failures are alerts of their own, so the line is empty then.
 */
const status = computed(() => {
  if (stage.value === 'saving') return 'Saving your changes first…'
  if (stage.value === 'checking') return 'Checking this version…'
  if (exporting.value) return 'Approving and exporting this version…'
  if (manifest.value === null || blocking.value) return ''
  return receipt.value !== null ? outcome.value : 'Ready to export.'
})

interface ExportedFile {
  kind: 'DOCX' | 'PDF'
  url: string
  linkText: string
  fileName: string
  mediaType: string
}

/** A file name the person's device accepts: the document's title without the characters file systems refuse. */
function baseFileName(title: string): string {
  const cleaned = title.replace(/[\u0000-\u001f\u007f/\\:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 100).trim()
  return cleaned === '' ? 'Document' : cleaned
}

/**
 * The files the export offers, the same ones its download links name: the Word file only when no PDF stands in for it.
 * A PDF form's export has no Word file at all.
 */
const exportedFiles = computed<ExportedFile[]>(() => {
  const made = receipt.value
  if (!made) return []
  const name = baseFileName(props.documentTitle)
  const files: ExportedFile[] = []
  if (made.docxArtifactId != null && (made.format !== 'PDF' || made.pdfArtifactId == null)) {
    files.push({
      kind: 'DOCX',
      url: artifactDownloadUrl(props.workspaceId, made.docxArtifactId),
      linkText: 'Download Word file (.docx)',
      fileName: `${name}.docx`,
      mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
  }
  if (made.pdfArtifactId != null) {
    files.push({
      kind: 'PDF',
      url: artifactDownloadUrl(props.workspaceId, made.pdfArtifactId),
      linkText: 'Download PDF',
      fileName: `${name}.pdf`,
      mediaType: 'application/pdf',
    })
  }
  return files
})

/** Whether this browser can hand files to the device's own share sheet at all. */
const canShareFiles = (() => {
  try {
    return navigator.canShare?.({ files: [new File([''], 'x.pdf', { type: 'application/pdf' })] }) === true
  } catch {
    return false
  }
})()

/** The exported files this device says it can share: some share sheets take a PDF but not a Word file. */
const shareableFiles = computed<ExportedFile[]>(() => {
  if (!canShareFiles) return []
  return exportedFiles.value.filter((file) => {
    try {
      return navigator.canShare({ files: [new File([''], file.fileName, { type: file.mediaType })] })
    } catch {
      return false
    }
  })
})

// ---- Where the dialog scrolls ------------------------------------------------------------------
//
// On a short window the body scrolls between the title and the foot, which both stay in view. While there is
// more below, the body fades out at its foot and the foot says "More below", so nothing reads as cut off.

const body = ref<HTMLElement | null>(null)
const content = ref<HTMLElement | null>(null)
const moreBelow = ref(false)

function measureBody(): void {
  const element = body.value
  const more = element !== null && element.scrollHeight - element.scrollTop - element.clientHeight > 4
  // Set only when it changes; what it changes is drawn over the layout, never in it, so measuring never resizes anything.
  if (more !== moreBelow.value) moreBelow.value = more
}

function scrollBodyDown(): void {
  const element = body.value
  if (!element || typeof element.scrollBy !== 'function') return
  const still = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  element.scrollBy({ top: element.clientHeight * 0.8, behavior: still ? 'auto' : 'smooth' })
}

let bodyObserver: ResizeObserver | null = null
watch(
  [body, content],
  ([box, inside]) => {
    bodyObserver?.disconnect()
    bodyObserver = null
    moreBelow.value = false
    if (!box) return
    measureBody()
    if (typeof ResizeObserver === 'undefined') return
    // The box changes with the window; what is in it as the check, the export and the Google panels arrive.
    bodyObserver = new ResizeObserver(() => measureBody())
    bodyObserver.observe(box)
    if (inside) bodyObserver.observe(inside)
  },
  { flush: 'post' },
)
onBeforeUnmount(() => bodyObserver?.disconnect())

// ---- Opening and closing ---------------------------------------------------------------------

function open(): void {
  if (isOpen.value) {
    heading.value?.focus()
    return
  }
  opener = window.document.activeElement instanceof HTMLElement ? window.document.activeElement : null
  saveRefused.value = false
  checkError.value = null
  exportError.value = null
  shareError.value = null
  isOpen.value = true
  void nextTick(() => {
    const element = dialog.value
    if (element === null || !isOpen.value) return
    nativeModal.value = false
    if (typeof element.showModal === 'function') {
      try {
        element.showModal()
        nativeModal.value = true
      } catch {
        // A dialog the page already shows without a modal cannot become one; it is laid over the page instead.
      }
    }
    if (!nativeModal.value) element.setAttribute('open', '')
    // The title, not the first button: what the dialog says comes before what it offers, and the first button is Close.
    heading.value?.focus()
    void prepare()
  })
}

function close(): void {
  if (!isOpen.value) return
  isOpen.value = false
  const element = dialog.value
  if (element !== null) {
    if (nativeModal.value && typeof element.close === 'function' && element.open) element.close()
    element.removeAttribute('open')
  }
  nativeModal.value = false
  const returnTo = opener
  opener = null
  void nextTick(() => {
    // Only focus that went nowhere goes back: a page that closes the dialog to take the person to a field has
    // already put focus where it belongs.
    const active = window.document.activeElement
    const lost = active === null || active === window.document.body || element?.contains(active) === true
    if (lost && returnTo !== null && returnTo.isConnected) returnTo.focus()
  })
  emit('closed')
}

/**
 * The browser closed the dialog by itself: it lets a page refuse Escape only once without the person doing
 * anything else in between, and then closes the dialog regardless. Everything else closes it through close().
 */
function onNativeClose(): void {
  if (isOpen.value && dialog.value?.open !== true) close()
}

function onFallbackKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || nativeModal.value) return
  event.preventDefault()
  close()
}

defineExpose({ open, close })

// ---- Saving, checking and what the check found ------------------------------------------------

function clearExport(): void {
  exportEpoch += 1
  approval.value = null
  receipt.value = null
  exporting.value = false
  exportError.value = null
  shareError.value = null
}

function clearAll(): void {
  checkEpoch += 1
  stage.value = 'idle'
  manifest.value = null
  checkError.value = null
  clearExport()
}

/**
 * A button the person was on can disappear with what it acted on (the export controls go when the document
 * changed). Focus then goes to the way forward, so it is never left on the page behind the dialog.
 */
async function keepFocusInside(): Promise<void> {
  await nextTick()
  const element = dialog.value
  if (!isOpen.value || element === null || element.contains(window.document.activeElement)) return
  ;(checkAgainButton.value ?? heading.value)?.focus()
}

// A revision changes under the dialog either because checking just appended one (the check it holds then
// names that very revision and stays) or because something else changed the document, which makes whatever
// was checked, approved or exported for the old revision say nothing about the new one.
watch(
  () => props.currentRevisionId,
  (revisionId) => {
    const checkedRevision = manifest.value?.revisionId
    if (checkedRevision === undefined || checkedRevision === revisionId) return
    clearAll()
    checkError.value = 'This document changed. Check it again.'
    void keepFocusInside()
  },
)

/** Saves what the page still holds; false, with the reason shown, when the page could not. */
async function saveFirst(): Promise<boolean> {
  if (!props.unsavedWork) return true
  // A save makes a new revision, so anything held for the current one is about to describe an old one.
  clearAll()
  stage.value = 'saving'
  let saved = false
  try {
    saved = await props.saveBeforeExport()
  } catch {
    saved = false
  }
  // The page's new revision reaches this dialog's props on the page's next render.
  await nextTick()
  if (stage.value === 'saving') stage.value = 'idle'
  if (!saved) {
    saveRefused.value = true
    return false
  }
  return true
}

let preparing: Promise<void> | null = null

/** What opening runs: never twice at once, since each check appends a revision. */
function prepare(): Promise<void> {
  if (preparing === null) {
    preparing = runPreparation().finally(() => {
      preparing = null
    })
  }
  return preparing
}

async function runPreparation(): Promise<void> {
  if (!(await saveFirst())) return
  // A check held from an earlier opening still describes this revision: the watcher above clears it otherwise.
  if (manifest.value !== null && manifest.value.revisionId === props.currentRevisionId) return
  clearAll()
  await hydrate()
  if (manifest.value === null && isOpen.value) await check()
}

/**
 * Picks up a check, approval and export already on record for the current revision, from an earlier visit or
 * before a reload. Any failure is ignored rather than shown: checking again and exporting stay available and
 * say for themselves what goes wrong, and "nothing on record" is the common answer.
 */
async function hydrate(): Promise<void> {
  const checkedAt = checkEpoch
  const exportedAt = exportEpoch
  let found: ValidationManifestResponse
  try {
    found = await getLatestValidation(props.workspaceId, props.documentId, props.currentRevisionId)
  } catch {
    return
  }
  if (checkedAt !== checkEpoch || typeof found?.id !== 'number') return
  manifest.value = found
  stage.value = 'checked'

  let approved: ExportApprovalResponse
  try {
    approved = await getLatestExportApproval(props.workspaceId, props.documentId)
  } catch {
    return
  }
  if (checkedAt !== checkEpoch || exportedAt !== exportEpoch || approved?.validationManifestId !== found.id) return
  // The format shown is the one approved, so the choice on screen matches the files offered below it.
  if (formatChoices.value.some((choice) => choice.value === approved.format)) exportFormat.value = approved.format
  approval.value = approved

  try {
    const made = await getLatestExportReceipt(props.workspaceId, props.documentId)
    if (checkedAt === checkEpoch && exportedAt === exportEpoch && made?.exportApprovalId === approved.id) {
      receipt.value = made
    }
  } catch {
    // Approved but not exported: the export button covers that.
  }
}

async function check(): Promise<void> {
  if (stage.value === 'checking' || stage.value === 'saving') return
  const checkedAt = checkEpoch
  stage.value = 'checking'
  checkError.value = null
  try {
    const result = await validateDocument(props.workspaceId, props.documentId, props.currentRevisionId, crypto.randomUUID())
    if (checkedAt !== checkEpoch) return
    // Held before the page reloads: checking appended the revision this result names, and the watcher keeps a
    // result for the revision the page moves to.
    manifest.value = result
    clearExport()
    stage.value = 'checked'
    try {
      await props.reloadDocument()
    } catch {
      // The page says for itself when it cannot reload; the result above is the server's and still holds.
    }
  } catch (error) {
    if (checkedAt !== checkEpoch) return
    stage.value = 'failed'
    showFailure(error, 'check')
  }
}

async function checkAgain(): Promise<void> {
  if (busy.value) return
  checkAgainPressed.value = true
  saveRefused.value = false
  try {
    if (!(await saveFirst())) return
    await check()
  } finally {
    checkAgainPressed.value = false
  }
}

// ---- Approving and exporting -------------------------------------------------------------------

/** Approving another format makes the approval and files on screen describe a choice no longer made. */
function formatChanged(): void {
  if (approval.value !== null || receipt.value !== null || exporting.value) clearExport()
}

async function approveAndExport(): Promise<void> {
  const checked = manifest.value
  if (checked === null || blocking.value || busy.value) return
  clearExport()
  const exportedAt = exportEpoch
  exporting.value = true
  const format = exportFormat.value
  try {
    const approved = await approveExport(props.workspaceId, props.documentId, checked.id, format)
    if (exportedAt !== exportEpoch) return
    approval.value = approved
    const made = await exportDocument(props.workspaceId, props.documentId)
    if (exportedAt !== exportEpoch) return
    receipt.value = made
    exporting.value = false
  } catch (error) {
    if (exportedAt !== exportEpoch) return
    exporting.value = false
    showFailure(error, approval.value === null ? 'approve' : 'export')
  }
}

type Step = 'check' | 'approve' | 'export'

/** What a server without the step lacks, in words that fit after "does not have". */
const STEP_FEATURES: Record<Step, string> = { check: 'document checks', approve: 'export approval', export: 'exports' }
const STEP_FALLBACKS: Record<Step, string> = {
  check: 'Brownie could not check this version. Try again.',
  approve: 'Brownie could not approve this export. Try again.',
  export: 'Brownie could not export this version. Try again.',
}

/**
 * Shared by checking, approving and exporting.
 *
 * A 412 means whatever the step relied on is no longer current: the revision it checked, or the check an
 * approval or export was scoped to. The only sound recovery is a fresh check, so everything is cleared and
 * the page is asked to load the revision the server now has, or checking again would be refused the same way.
 *
 * A 404 from checking means the document itself is gone. From approving or exporting, it means only the
 * approval that step pointed at is gone (another approval replaced it): the check still holds, so only the
 * approval and export are cleared. The server's own words for it name records by number, so they are not shown.
 *
 * A 422 carries the server's single explanation. Anything every page words the same way (a busy server, too
 * many requests, a server without the step, a session that ended, no connection) is said as such.
 */
function showFailure(error: unknown, step: Step): void {
  if (error instanceof ApiRequestError) {
    if (error.status === 412) {
      clearAll()
      checkError.value = 'This document changed since it was checked. Check it again.'
      void props.reloadDocument().catch(() => false)
      void keepFocusInside()
      return
    }
    if (error.status === 404 && !error.routeMissing) {
      if (step === 'check') {
        checkError.value = 'This document is no longer here. It may have been moved to the trash.'
        return
      }
      clearExport()
      exportError.value = 'That approval is no longer available. Check again.'
      return
    }
    if (error.status === 422) {
      setStepError(step, error.problem?.detail ?? error.message)
      return
    }
  }
  setStepError(step, describeCommonFailure(error, STEP_FEATURES[step]) ?? STEP_FALLBACKS[step])
}

function setStepError(step: Step, message: string): void {
  if (step === 'check') checkError.value = message
  else exportError.value = message
}

// ---- Sharing --------------------------------------------------------------------------------------

/**
 * Files already fetched for sharing, by address. A browser lets a page open the share sheet only just after
 * the person pressed something, and fetching a file first can outlast that; fetching as soon as the export is
 * on screen lets the press open the sheet straight away. Nothing leaves the person's device from here.
 */
const fetchedForSharing = new Map<string, Promise<File>>()

function fetchForSharing(file: ExportedFile): Promise<File> {
  let fetched = fetchedForSharing.get(file.url)
  if (fetched === undefined) {
    fetched = (async () => {
      const response = await fetch(file.url, { credentials: 'include' })
      if (!response.ok) throw new Error(`The file could not be fetched (${response.status}).`)
      return new File([await response.blob()], file.fileName, { type: file.mediaType })
    })()
    fetchedForSharing.set(file.url, fetched)
    // A failed fetch is tried again on the next press rather than remembered.
    fetched.catch(() => fetchedForSharing.delete(file.url))
  }
  return fetched
}

watch(
  [shareableFiles, isOpen],
  ([files, shown]) => {
    if (!shown) {
      fetchedForSharing.clear()
      return
    }
    for (const file of files) void fetchForSharing(file).catch(() => undefined)
  },
  { immediate: true },
)

async function share(): Promise<void> {
  const files = shareableFiles.value
  if (sharing.value || files.length === 0) return
  sharing.value = true
  shareError.value = null
  try {
    const loaded = await Promise.all(files.map(fetchForSharing))
    await navigator.share({ files: loaded, title: props.documentTitle })
  } catch (error) {
    // The person closed the share sheet: nothing went wrong, so nothing is said.
    if ((error as { name?: unknown } | null)?.name !== 'AbortError') {
      shareError.value = 'Sharing did not work on this device. Download the file and share it from there.'
    }
  } finally {
    sharing.value = false
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="export-dialog"
    :class="{ 'export-dialog--fallback': !nativeModal }"
    :aria-labelledby="isOpen ? 'export-dialog-heading' : undefined"
    @cancel.prevent="close"
    @close="onNativeClose"
    @keydown="onFallbackKeydown"
  >
    <template v-if="isOpen">
      <header class="export-dialog__header">
        <h2 id="export-dialog-heading" ref="heading" class="export-dialog__title" tabindex="-1">Export</h2>
        <button class="icon-button" type="button" @click="close">
          <AppIcon name="close" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <div ref="body" class="export-dialog__body" :class="{ 'export-dialog__body--more-below': moreBelow }" @scroll.passive="measureBody">
        <div ref="content" class="export-dialog__content">
          <div class="export-dialog__check">
            <p class="export-dialog__status" aria-live="polite" aria-atomic="true">{{ status }}</p>
            <p v-if="saveRefused" class="field-error" role="alert">
              Your latest changes are not saved, so this version cannot be exported yet. Close this and check the message on
              the page.
            </p>

            <template v-if="manifest">
              <p v-if="blocking" class="field-error export-dialog__verdict" role="alert">
                This version cannot be exported yet. Fix what is listed below, then check again.
              </p>
              <p class="field-hint">{{ countSentence }}</p>

              <ul v-if="findings.length > 0" class="export-dialog__findings">
                <!-- The spaces between the parts are written out: the layout drops them, but a screen reader reading the row needs them. -->
                <li v-for="(finding, index) in findings" :key="index" class="export-dialog__finding">
                  <span
                    class="export-dialog__severity"
                    :class="{ 'export-dialog__severity--blocking': finding.severity === 'BLOCKING' }"
                    >{{ severityWord(finding.severity) }}</span
                  >{{ ' ' }}
                  <span class="export-dialog__finding-text">
                    <span v-if="finding.fieldId" class="export-dialog__field">{{ fieldLabel(finding.fieldId) }}</span>{{ ' ' }}
                    <span>{{ finding.message }}</span>
                  </span>{{ ' ' }}
                  <button
                    v-if="finding.fieldId && editableFieldIds.has(finding.fieldId)"
                    type="button"
                    class="button button--secondary export-dialog__go"
                    @click="emit('go-to-field', finding.fieldId)"
                  >
                    Go to {{ fieldLabel(finding.fieldId) }}
                  </button>
                </li>
              </ul>
            </template>

            <p v-if="checkError" class="field-error" role="alert">{{ checkError }}</p>
            <!-- Not disabled while busy: that would drop the focus it holds. A second press is ignored instead. -->
            <button
              v-if="checkAgainOffered"
              ref="checkAgainButton"
              type="button"
              class="button button--secondary export-dialog__check-again"
              :aria-disabled="busy"
              @click="checkAgain"
            >
              Check again
            </button>
          </div>

          <div v-if="manifest && !blocking" class="export-dialog__export">
            <fieldset v-if="!pdfOnly" class="export-dialog__formats">
              <legend class="field-label">Format</legend>
              <label v-for="choice in formatChoices" :key="choice.value" class="export-dialog__format">
                <input v-model="exportFormat" type="radio" name="export-format" :value="choice.value" @change="formatChanged" />
                {{ choice.label }}
              </label>
            </fieldset>
            <div v-else class="export-dialog__pdf-only">
              <p class="field-label">Format: PDF</p>
              <p class="field-hint">This is a PDF form, so Brownie fills it and exports it as a PDF. It does not turn it into a Word file.</p>
            </div>

            <!-- Once exported, the download is what comes next, so it becomes the main button and this one steps back. -->
            <button
              type="button"
              class="button export-dialog__approve"
              :class="receipt ? 'button--secondary' : 'button--primary'"
              :aria-disabled="busy"
              @click="approveAndExport"
            >
              {{ exporting ? 'Exporting…' : 'Approve and export' }}
            </button>
            <p v-if="exportError" class="field-error" role="alert">{{ exportError }}</p>

            <div v-if="receipt" class="export-dialog__files">
              <ul class="export-dialog__links">
                <li v-for="file in exportedFiles" :key="file.kind">
                  <a class="button button--primary" :href="file.url" download>
                    <AppIcon name="download" :size="18" />
                    {{ file.linkText }}
                  </a>
                </li>
              </ul>
              <template v-if="shareableFiles.length > 0">
                <button type="button" class="button button--secondary" :aria-disabled="sharing" @click="share">
                  <AppIcon name="share" :size="18" />
                  Share…
                </button>
                <p class="field-hint">Your device shares the file. Brownie sends nothing anywhere.</p>
              </template>
              <p v-if="shareError" class="field-error" role="alert">{{ shareError }}</p>
            </div>
          </div>

          <div class="export-dialog__elsewhere">
            <DriveSavePanel :workspace-id="workspaceId" :document-id="documentId" :receipt="receipt" :unsaved-work="unsavedWork" />
            <CalendarEventPanel
              :workspace-id="workspaceId"
              :document-id="documentId"
              :document-title="documentTitle"
              :suggested-date="suggestedEventDate"
              :unsaved-work="unsavedWork"
            />
          </div>
        </div>
      </div>

      <!--
        Always in view under the body: a way out, and, while the body has more below, a sign that it scrolls. Called
        Done, so it is never mistaken for the Close button at the top, which a test or a person may name exactly.
      -->
      <footer class="export-dialog__footer">
        <span class="export-dialog__more" :class="{ 'export-dialog__more--shown': moreBelow }" aria-hidden="true" @click="scrollBodyDown">
          More below <span class="export-dialog__more-arrow">&#8595;</span>
        </span>
        <button type="button" class="button button--secondary export-dialog__done" @click="close">Done</button>
      </footer>
    </template>
  </dialog>
</template>

<style scoped>
.export-dialog {
  width: min(40rem, 100% - 2rem);
  max-width: none;
  max-height: 90vh;
  max-height: min(90dvh, 52rem);
  padding: 0;
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 1rem 3rem rgb(42 41 36 / 0.2);
}

/* Only while open: a display value on a closed dialog would override the browser's own display: none. */
.export-dialog[open] {
  display: flex;
  flex-direction: column;
  animation: export-dialog-in var(--motion-base) var(--motion-ease);
}

@keyframes export-dialog-in {
  from {
    opacity: 0;
    transform: translateY(0.5rem);
  }
}

/* The page behind is dimmed with its own text colour, so the scrim matches the warm palette in any theme. */
.export-dialog::backdrop {
  background: rgb(42 41 36 / 0.45);
  background: color-mix(in srgb, var(--color-text, #2a2924) 45%, transparent);
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.export-dialog--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  height: fit-content;
}

.export-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-hairline);
}

.export-dialog__title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}

/* The title takes focus only so a screen reader starts reading at the top; it is not a control to point at. */
.export-dialog__title:focus-visible {
  box-shadow: none;
  outline: none;
}

.export-dialog__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: var(--space-4) var(--space-5) var(--space-5);
  scrollbar-width: thin;
  scrollbar-color: var(--color-scrollbar) transparent;
}

/* More below: the body fades out at its foot, so the last thing showing reads as going on, not cut off. */
.export-dialog__body--more-below {
  mask-image: linear-gradient(to bottom, #000 calc(100% - 2.5rem), transparent);
}

.export-dialog__content {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.export-dialog__footer {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--color-hairline);
}

/* Kept in its place while hidden, so the foot never changes size as the body scrolls. */
.export-dialog__more {
  visibility: hidden;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0 var(--space-2);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-pill);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: 600;
  line-height: 1.6;
  cursor: pointer;
}

.export-dialog__more--shown {
  visibility: visible;
}

.export-dialog__done {
  min-block-size: 2.25rem;
  margin-inline-start: auto;
  border-radius: var(--radius-pill);
}

.export-dialog__body p {
  margin: 0;
}

.export-dialog__check,
.export-dialog__export,
.export-dialog__files {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.export-dialog__status,
.export-dialog__verdict {
  font-weight: 600;
}

.export-dialog__verdict.field-error {
  font-size: var(--font-size-base);
}

.export-dialog__findings {
  list-style: none;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.export-dialog__finding {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--space-2) var(--space-3);
  padding-block-end: var(--space-2);
  border-bottom: 1px solid var(--color-hairline);
}

/* Severity is said in words on the badge; the border only repeats it for the eye. */
.export-dialog__severity {
  flex: none;
  padding: var(--space-1) var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: var(--color-honey-soft);
  font-size: var(--font-size-sm);
  font-weight: 600;
  line-height: 1.3;
}

.export-dialog__severity--blocking {
  background: var(--color-surface);
  border-color: var(--color-error);
  color: var(--color-error);
}

.export-dialog__finding-text {
  flex: 1 1 12rem;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow-wrap: anywhere;
}

.export-dialog__field {
  font-weight: 600;
}

.export-dialog__go {
  flex: none;
}

.export-dialog__check-again {
  margin-block-start: var(--space-1);
}

.export-dialog__formats {
  margin: 0;
  padding: 0;
  border: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}

.export-dialog__formats legend {
  padding: 0;
  margin-block-end: var(--space-1);
}

.export-dialog__pdf-only p {
  margin: 0;
}

.export-dialog__format {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-block-size: 1.5rem;
}

.button[aria-disabled='true'] {
  cursor: progress;
}

/*
 * The dialog's own buttons take the shared button's size and shape, as the Google panels below them do, so
 * the buttons in the one window read as one set.
 */

.export-dialog__links {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.export-dialog__elsewhere {
  border-top: 1px solid var(--color-hairline);
}

/* Both panels hide themselves where Google is not offered; a divider above nothing would be a stray line. */
.export-dialog__elsewhere:empty {
  display: none;
}

@media (max-width: 30rem) {
  .export-dialog {
    width: 100%;
    max-height: 100dvh;
    border-inline: 0;
    border-radius: 0;
  }

  .export-dialog__header,
  .export-dialog__body,
  .export-dialog__footer {
    padding-inline: var(--space-4);
  }
}
</style>
