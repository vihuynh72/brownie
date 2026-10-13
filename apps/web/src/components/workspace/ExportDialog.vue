<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { IconName } from '@/components/icons'
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
 * exact version is already on record, and says where each step stands. What
 * the check found decides what is offered: a version with a problem that
 * blocks export lists the problems, each with a way to the fill spot it is
 * about, and shows the approval as unavailable; any other version offers a
 * choice of format and one button that approves it and exports it straight
 * away. Once exported, the files can be downloaded or shared from the
 * person's own device, saved to Google Drive, and the event the document
 * describes can be added to Google Calendar.
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
  /** The number people know the current revision by ("version 9"); the revision's id means nothing to them. */
  currentRevisionNumber?: number
  /** How many fill spots the page holds and how many of them are filled, accepted and locked, counted as the page counts them. */
  fillCounts?: { total: number; filled: number; accepted: number; locked: number } | null
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
const approveButton = ref<HTMLButtonElement | null>(null)
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

const FORMAT_CHOICES: readonly { value: ExportFormat; label: string; hint: string; icon: IconName }[] = [
  { value: 'DOCX', label: 'Word (.docx)', hint: 'Edit it again in Word', icon: 'document-text' },
  { value: 'PDF', label: 'PDF', hint: 'Looks the same everywhere', icon: 'document-blank' },
  { value: 'BOTH', label: 'Word and PDF', hint: 'Both files from this version', icon: 'copy' },
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

/** How each kind of finding is labelled: a word, a tag colour, and an icon, so the kind never rests on colour alone. */
const SEVERITY: Record<Finding['severity'], { word: string; tag: string; icon: IconName }> = {
  BLOCKING: { word: 'Blocks export', tag: 'tag--error', icon: 'close-circle' },
  WARNING: { word: 'Warning', tag: 'tag--honey', icon: 'warning' },
  INFORMATIONAL: { word: 'Note', tag: '', icon: 'info' },
}

function severityOf(finding: Finding): { word: string; tag: string; icon: IconName } {
  // A kind this page does not know is read as the mildest one: it is listed, but never stops the export.
  return SEVERITY[finding.severity] ?? SEVERITY.INFORMATIONAL
}

/** A way to the fill spot is offered for what asks something of the person, not for a note that only says what Brownie did. */
function canGoTo(finding: Finding): boolean {
  return (
    (finding.severity === 'BLOCKING' || finding.severity === 'WARNING') &&
    Boolean(finding.fieldId) &&
    props.editableFieldIds.has(finding.fieldId as string)
  )
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

/** What the page counted, as the small tags beside a check that found nothing to fix; none when the page gave no counts. */
const countTags = computed<{ key: string; text: string; icon?: IconName }[]>(() => {
  const counts = props.fillCounts
  if (!counts) return []
  const tags: { key: string; text: string; icon?: IconName }[] = [
    { key: 'filled', text: `${counts.filled} of ${counts.total} ${counts.total === 1 ? 'fill spot' : 'fill spots'} filled` },
    { key: 'accepted', text: `${counts.accepted} accepted` },
  ]
  if (counts.locked > 0) tags.push({ key: 'locked', text: `${counts.locked} locked`, icon: 'lock' })
  return tags
})

/**
 * The number a person knows a revision by, when it is the one the page is showing: the page knows the number of
 * its own revision and no other, so a result that names a different revision (one that checking has just
 * appended, before the page has loaded it) has no number to give yet.
 */
function versionOf(revisionId: number | undefined): number | null {
  return props.currentRevisionNumber !== undefined && revisionId === props.currentRevisionId ? props.currentRevisionNumber : null
}

const checkedVersion = computed(() => versionOf(manifest.value?.revisionId))

const timeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })
const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

/** "today at 3:51 PM", or the day instead of "today" for an earlier one; null when the time cannot be read. */
function whenWords(value: string): string | null {
  const when = new Date(value)
  if (Number.isNaN(when.getTime())) return null
  const now = new Date()
  const today = when.getFullYear() === now.getFullYear() && when.getMonth() === now.getMonth() && when.getDate() === now.getDate()
  return `${today ? 'today' : dayFormatter.format(when)} at ${timeFormatter.format(when)}`
}

/** The line under the title: which version this is about, and once exported, when it was. */
const subtitle = computed(() => {
  const made = receipt.value
  if (made !== null) {
    const when = whenWords(made.exportedAt)
    const done = when === null ? 'approved and exported' : `approved and exported ${when}`
    const version = versionOf(made.revisionId)
    return version === null ? `${done.charAt(0).toUpperCase()}${done.slice(1)}` : `Version ${version} • ${done}`
  }
  const version = checkedVersion.value
  return version === null ? props.documentTitle : `${props.documentTitle} • Version ${version}`
})

interface StepRow {
  key: 'save' | 'check'
  state: 'done' | 'active' | 'pending' | 'failed'
  label: string
}

/**
 * The two things opening does before anything is offered, each with where it stands. Opening a version that
 * already has a check on record goes straight to done; the moment before the first answer arrives counts as
 * checking, since that is what is under way.
 */
const stepRows = computed<StepRow[]>(() => {
  const saving = stage.value === 'saving' || (props.unsavedWork && !saveRefused.value)
  const save: StepRow = saveRefused.value
    ? { key: 'save', state: 'failed', label: 'Your changes are not saved' }
    : saving
      ? { key: 'save', state: 'active', label: 'Saving your changes' }
      : { key: 'save', state: 'done', label: 'Saved your changes' }

  const waitingForAnswer = stage.value === 'idle' && manifest.value === null && !saveRefused.value && !props.unsavedWork
  const version = checkedVersion.value
  const check: StepRow =
    checkError.value !== null
      ? { key: 'check', state: 'failed', label: 'Could not check this version' }
      : stage.value === 'checking' || waitingForAnswer
        ? { key: 'check', state: 'active', label: 'Checking this version' }
        : manifest.value !== null
          ? { key: 'check', state: 'done', label: version === null ? 'Checked this version' : `Checked version ${version}` }
          : { key: 'check', state: 'pending', label: 'Check this version' }
  return [save, check]
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

/** Whether everything asked for was made: a file that could not be made is a warning, not a success. */
const outcomeComplete = computed(() => receipt.value !== null && (receipt.value.format === 'DOCX' || receipt.value.pdfArtifactId != null))

/**
 * Where things stand, in one line: the dialog's only polite live region, since the page's own region is out
 * of reach while the dialog is modal. Once the files are made it is the visible sentence about them rather
 * than a hidden copy of it, so that sentence is read once and appears on the page once; until then it is
 * kept for assistive technology alone, because the rows and the result already say it to the eye. Problems
 * that block export and failures are alerts of their own, so the line is empty then.
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
// more below, the body fades out at its foot and a small "More below" tag floats above the foot, so nothing
// reads as cut off.

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
 * changed, and the approval goes once the files are made). Focus then goes to what comes next, or failing that
 * to the way forward, so it is never left on the page behind the dialog.
 */
async function keepFocusInside(next?: () => HTMLElement | null | undefined): Promise<void> {
  await nextTick()
  const element = dialog.value
  if (!isOpen.value || element === null || element.contains(window.document.activeElement)) return
  ;(next?.() ?? checkAgainButton.value ?? heading.value)?.focus()
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

/** Goes back from the files to the choice of format, for a different format or to make the files again. */
function exportAgain(): void {
  clearExport()
  void keepFocusInside(() => content.value?.querySelector<HTMLElement>('input[name="export-format"]:checked') ?? approveButton.value)
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
    // The button that was pressed goes with the choices; the files are what the person came for.
    void keepFocusInside(() => content.value?.querySelector<HTMLElement>('a[download]'))
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
    class="dialog dialog--medium export-dialog"
    :class="{ 'export-dialog--fallback': !nativeModal }"
    :aria-labelledby="isOpen ? 'export-dialog-heading' : undefined"
    @cancel.prevent="close"
    @close="onNativeClose"
    @keydown="onFallbackKeydown"
  >
    <template v-if="isOpen">
      <header class="dialog__head">
        <h2 id="export-dialog-heading" ref="heading" class="dialog__title export-dialog__title" tabindex="-1">Export</h2>
        <p class="dialog__subtitle">{{ subtitle }}</p>
        <button class="icon-button dialog__close" type="button" @click="close">
          <AppIcon name="close" :size="22" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <div ref="body" class="dialog__body export-dialog__body" :class="{ 'export-dialog__body--more-below': moreBelow }" @scroll.passive="measureBody">
        <div ref="content" class="export-dialog__content" :class="{ 'export-dialog__content--files': receipt !== null }">
          <div class="export-dialog__outcome" :class="{ 'visually-hidden': receipt === null }">
            <AppIcon
              v-if="receipt"
              :name="outcomeComplete ? 'check-circle' : 'warning'"
              :size="22"
              class="export-dialog__outcome-icon"
              :class="{ 'export-dialog__outcome-icon--partial': !outcomeComplete }"
            />
            <p class="export-dialog__status" aria-live="polite" aria-atomic="true">{{ status }}</p>
          </div>

          <ul v-if="receipt === null" class="step-list export-dialog__steps">
            <li v-for="row in stepRows" :key="row.key" class="step" :class="`step--${row.state}`">
              <span class="step__marker" aria-hidden="true"><AppIcon v-if="row.state === 'failed'" name="close-circle" :size="18" /></span>
              <span v-if="row.state === 'done'" class="visually-hidden">Done: </span>
              <span v-else-if="row.state === 'active'" class="visually-hidden">In progress: </span>
              <span class="step__label">{{ row.label }}</span>
            </li>
          </ul>

          <p v-if="saveRefused" class="export-dialog__banner" role="alert">
            <AppIcon name="alert-circle" :size="20" />
            <span>
              Your latest changes are not saved, so this version cannot be exported yet. Close this and check the message on the
              page.
            </span>
          </p>
          <p v-if="checkError" class="export-dialog__banner" role="alert">
            <AppIcon name="alert-circle" :size="20" />
            <span>{{ checkError }}</span>
          </p>

          <!-- What the check found matters until the files exist; once they do, the files are what the window is about. -->
          <template v-if="manifest && receipt === null">
            <p v-if="findings.length > 0" class="export-dialog__count">{{ countSentence }}</p>
            <p v-if="blocking" class="export-dialog__banner" role="alert">
              <AppIcon name="close-circle" :size="20" />
              <span>This version cannot be exported yet. Fix what is listed below, then check again.</span>
            </p>

            <ul v-if="findings.length > 0" class="export-dialog__findings">
              <!-- The spaces between the parts are written out: the layout drops them, but a screen reader reading the row needs them. -->
              <li v-for="(finding, index) in findings" :key="index" class="export-dialog__finding">
                <span class="tag" :class="severityOf(finding).tag">
                  <AppIcon :name="severityOf(finding).icon" :size="14" />{{ severityOf(finding).word }}
                </span>{{ ' ' }}
                <span class="export-dialog__finding-text">{{ finding.fieldId ? `${fieldLabel(finding.fieldId)} ` : '' }}{{ finding.message }}</span>{{ ' ' }}
                <button
                  v-if="canGoTo(finding)"
                  type="button"
                  class="button button--quiet export-dialog__go"
                  @click="emit('go-to-field', finding.fieldId as string)"
                >
                  Go to {{ fieldLabel(finding.fieldId as string) }}
                  <AppIcon name="chevron-right" :size="14" />
                </button>
              </li>
            </ul>

            <div v-else-if="!blocking" class="export-dialog__clear">
              <AppIcon name="check-circle" :size="22" />
              <div class="export-dialog__clear-body">
                <p class="export-dialog__clear-title">{{ countSentence }}</p>
                <ul v-if="countTags.length > 0" class="export-dialog__counts">
                  <li v-for="tag in countTags" :key="tag.key" class="tag">
                    <AppIcon v-if="tag.icon" :name="tag.icon" :size="14" />{{ tag.text }}
                  </li>
                </ul>
              </div>
            </div>
          </template>

          <template v-if="manifest && !blocking && receipt === null">
            <fieldset v-if="!pdfOnly" class="export-dialog__formats">
              <legend class="field-label">Format</legend>
              <div class="export-dialog__format-grid">
                <label v-for="choice in formatChoices" :key="choice.value" class="export-format">
                  <input
                    v-model="exportFormat"
                    class="export-format__input"
                    type="radio"
                    name="export-format"
                    :value="choice.value"
                    :aria-labelledby="`export-format-${choice.value}-name`"
                    :aria-describedby="`export-format-${choice.value}-hint`"
                    @change="formatChanged"
                  />
                  <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon :name="choice.icon" :size="18" /></span>
                  <span :id="`export-format-${choice.value}-name`" class="export-format__title">{{ choice.label }}</span>
                  <span :id="`export-format-${choice.value}-hint`" class="export-format__hint">{{ choice.hint }}</span>
                </label>
              </div>
            </fieldset>
            <div v-else class="export-dialog__pdf-only">
              <p class="field-label">Format: PDF</p>
              <p class="field-hint">This is a PDF form, so Brownie fills it and exports it as a PDF. It does not turn it into a Word file.</p>
            </div>

            <p class="panel panel--quiet export-dialog__note">
              <AppIcon name="shield-check" :size="18" />
              <span>
                You approve exactly {{ checkedVersion === null ? 'this version' : `version ${checkedVersion}` }} in this format. If anything
                changes after this, Brownie checks and asks again.
              </span>
            </p>
            <p v-if="exportError" class="export-dialog__banner" role="alert">
              <AppIcon name="alert-circle" :size="20" />
              <span>{{ exportError }}</span>
            </p>
          </template>

          <template v-if="receipt">
            <ul class="export-dialog__files">
              <li v-for="(file, index) in exportedFiles" :key="file.kind" class="export-file">
                <span class="doc-tile doc-tile--medium" aria-hidden="true">
                  <AppIcon :name="file.kind === 'DOCX' ? 'document-text' : 'document-blank'" :size="18" />
                </span>
                <span class="export-file__text">
                  <span class="export-file__name">{{ file.fileName }}</span>
                  <span class="export-file__meta">{{ file.kind === 'DOCX' ? 'Word' : 'PDF' }}</span>
                </span>
                <a class="button button--small" :class="index === 0 ? 'button--primary' : 'button--secondary'" :href="file.url" download>
                  <AppIcon name="download" :size="16" />
                  {{ file.linkText }}
                </a>
              </li>
            </ul>

            <div v-if="shareableFiles.length > 0" class="export-dialog__share">
              <button type="button" class="button button--secondary button--small" :aria-disabled="sharing" @click="share">
                <AppIcon name="share" :size="16" />
                Share…
              </button>
              <p class="field-hint">Your device shares the file. Brownie sends nothing anywhere.</p>
            </div>
            <p v-if="shareError" class="field-error" role="alert">{{ shareError }}</p>

            <!-- Both panels hide themselves where Google is not offered; the heading above nothing would be a stray line. -->
            <div class="export-dialog__elsewhere">
              <h3 class="export-dialog__divider">Save a copy elsewhere</h3>
              <div class="export-dialog__panels">
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
          </template>
        </div>
      </div>

      <!--
        Always in view under the body: a way out, and, while the body has more below, a sign that it scrolls. Called
        Done or Cancel, never Close, so it is not mistaken for the Close button at the top, which a test or a person
        may name exactly.
      -->
      <footer class="dialog__foot export-dialog__footer">
        <span class="export-dialog__more" :class="{ 'export-dialog__more--shown': moreBelow }" aria-hidden="true" @click="scrollBodyDown">
          More below &#8595;
        </span>

        <div class="export-dialog__foot-start">
          <!-- Not disabled while busy: that would drop the focus it holds. A second press is ignored instead. -->
          <button
            v-if="checkAgainOffered && receipt === null"
            ref="checkAgainButton"
            type="button"
            class="button"
            :class="manifest !== null && !blocking ? 'button--quiet' : 'button--secondary'"
            :aria-disabled="busy"
            @click="checkAgain"
          >
            <AppIcon name="refresh" :size="18" />
            Check again
          </button>
          <p v-if="manifest && blocking" id="export-dialog-blocked-note" class="export-dialog__foot-note">
            A check that blocks export cannot be skipped.
          </p>
          <button v-if="receipt" type="button" class="button button--quiet" @click="exportAgain">Export again</button>
        </div>

        <button v-if="receipt" type="button" class="button button--secondary" @click="close">Done</button>
        <template v-else>
          <button v-if="!(manifest && blocking)" type="button" class="button button--secondary" @click="close">Cancel</button>
          <!-- With a problem that blocks export it stays in sight but cannot be used, and says why. -->
          <button
            v-if="manifest"
            ref="approveButton"
            type="button"
            class="button button--primary"
            :aria-disabled="blocking || busy"
            :aria-describedby="blocking ? 'export-dialog-blocked-note' : undefined"
            @click="approveAndExport"
          >
            <AppIcon name="check" :size="18" />
            {{ exporting ? 'Exporting…' : 'Approve and export' }}
          </button>
        </template>
      </footer>
    </template>
  </dialog>
</template>

<style scoped>
/* Only while open: a display value on a closed dialog would override the browser's own display: none. */
.export-dialog[open] {
  animation: export-dialog-in var(--motion-base) var(--motion-ease);
  /* The browser centres a modal exactly; this one sits a little above that, to match the rest of the window. */
  translate: 0 -0.625rem;
}

@keyframes export-dialog-in {
  from {
    opacity: 0;
    transform: translateY(0.5rem);
  }
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.export-dialog--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  height: fit-content;
}

/* The title takes focus only so a screen reader starts reading at the top; it is not a control to point at. */
.export-dialog__title:focus-visible {
  box-shadow: none;
  outline: none;
}

/* A document's title can be one long word; it breaks anywhere rather than running under the Close button. */
.dialog__subtitle {
  overflow-wrap: anywhere;
}

.export-dialog__body {
  overscroll-behavior: contain;
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
  gap: 1.125rem;
}

/* The files, the way to share them and the copies elsewhere sit a little closer than the steps of a check do. */
.export-dialog__content--files {
  gap: 1rem;
}

.export-dialog__body p {
  margin: 0;
}

/* ---------- Where things stand ---------- */

.export-dialog__outcome {
  display: flex;
  align-items: center;
  gap: 0.625rem;
}

.export-dialog__status {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.375rem;
}

/* Shown, it takes the place of the two steps and sits level with them rather than a line lower. */
.export-dialog__outcome:not(.visually-hidden) {
  margin-block-start: -0.1875rem;
}

.export-dialog__outcome-icon {
  color: var(--color-success);
}

.export-dialog__outcome-icon--partial {
  color: var(--color-honey-ink);
}

/* The two steps sit side by side, as one line of progress; they wrap onto a second line only in a narrow window. */
.export-dialog__steps {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1.3125rem;
}

/* The mark is a hair larger than the shared one so it matches the check icons used elsewhere in this window. */
.export-dialog__steps .step__marker {
  inline-size: 1.125rem;
  block-size: 1.125rem;
}

.export-dialog__steps .step--failed {
  color: var(--color-error);
}

.export-dialog__count {
  font-size: var(--font-size-base);
  line-height: 1.45;
}

/* A problem said in words and an icon on a red wash, so it is never told by the colour alone. */
.export-dialog__banner {
  display: flex;
  align-items: flex-start;
  gap: 0.5625rem;
  padding: 0.75rem 0.875rem;
  border-radius: var(--radius-md);
  background: var(--color-error-wash);
  color: var(--color-error);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.export-dialog__banner > svg {
  flex: none;
}

/* ---------- What the check found ---------- */

.export-dialog__findings {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
}

.export-dialog__finding {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0.25rem 0.75rem;
  padding: 0.75rem 0.875rem;
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

.export-dialog__finding + .export-dialog__finding {
  border-block-start: 1px solid var(--color-hairline);
}

.export-dialog__finding-text {
  flex: 1 1 12rem;
  min-inline-size: 0;
  overflow-wrap: anywhere;
}

/*
 * The way to a fill spot reads as a link: cocoa text and a chevron, no pill. It keeps the 24 pixels a target
 * needs, and sits level with the first line of the sentence rather than in the middle of a tall row.
 */
.export-dialog__go {
  --button-height: 1.5rem;
  --button-block: 0.125rem;
  --button-pad: 0px;
  --button-icon: 0.875rem;
  --button-gap: 0.125rem;
  --button-ink: var(--color-cocoa);
  --button-bg-hover: transparent;
  --button-bg-active: transparent;
  flex: none;
  margin-block-start: -0.3125rem;
  margin-inline-start: auto;
  font-size: var(--font-size-xs);
}

.export-dialog__go:hover {
  text-decoration: underline;
}

/* Nothing to fix: the check's good news on a green wash, with what was counted beside it. */
.export-dialog__clear {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem 0.875rem;
  border-radius: var(--radius-md);
  background: var(--color-success-wash);
  color: var(--color-success);
}

.export-dialog__clear > svg {
  flex: none;
}

.export-dialog__clear-body {
  display: grid;
  gap: 0.3125rem;
  min-inline-size: 0;
}

.export-dialog__clear-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.export-dialog__counts {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* ---------- The format ---------- */

.export-dialog__formats {
  min-inline-size: 0;
  margin: -0.125rem 0 0;
  padding: 0;
  border: 0;
}

.export-dialog__formats legend {
  padding: 0;
  margin-block-end: 1.0625rem;
}

.export-dialog__format-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10.5rem, 1fr));
  gap: 0.625rem;
}

.export-dialog__pdf-only {
  display: grid;
  gap: 0.25rem;
}

.export-dialog__pdf-only p {
  margin: 0;
}

/*
 * One format is one card: a tile for the kind of file, its name and what it is good for, and a radio at the
 * corner. The radio itself is drawn on the real input, so the arrow keys, the label and the form all behave as
 * for any radio group; the whole card is its label, so it can be pressed anywhere. Chosen, the edge thickens to
 * two pixels with an inner shadow rather than a wider border, so choosing never moves anything.
 */
.export-format {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.875rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    border-color var(--motion-fast) var(--motion-ease),
    box-shadow var(--motion-fast) var(--motion-ease);
}

.export-format:hover {
  background: var(--color-paper-soft);
}

.export-format:has(.export-format__input:checked) {
  border-color: var(--color-cocoa);
  background: var(--color-cocoa-soft);
  box-shadow: inset 0 0 0 1px var(--color-cocoa);
}

.export-format:has(.export-format__input:focus-visible) {
  box-shadow: var(--focus-ring);
}

.export-format:has(.export-format__input:checked:focus-visible) {
  box-shadow:
    inset 0 0 0 1px var(--color-cocoa),
    var(--focus-ring);
}

.export-format__input {
  appearance: none;
  position: absolute;
  inset-block-start: 1.25rem;
  inset-inline-end: 0.875rem;
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0;
  border: 1.5px solid var(--color-field-border);
  border-radius: 50%;
  background: var(--color-surface);
  cursor: pointer;
}

.export-format__input:checked {
  border-color: var(--color-cocoa);
  background: radial-gradient(circle, var(--color-surface) 0 0.1875rem, var(--color-cocoa) 0.25rem);
}

/* The card carries the focus ring, so the small circle draws none of its own. */
.export-format__input:focus-visible {
  box-shadow: none;
  outline: none;
}

.export-format__title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.export-format__hint {
  margin-block-start: 0.125rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.35;
}

/* A display that forces its own colours drops backgrounds and shadows, which would erase which card is chosen. */
@media (forced-colors: active) {
  .export-format__input:checked {
    forced-color-adjust: none;
    border-color: CanvasText;
    background: CanvasText;
  }

  .export-format:has(.export-format__input:checked) {
    outline: 2px solid Highlight;
    outline-offset: -2px;
  }
}

.export-dialog__note {
  display: flex;
  align-items: flex-start;
  gap: 0.5625rem;
  margin-block-start: 0.125rem;
  padding-block: 0.6875rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.0625rem;
}

.export-dialog__note > svg {
  flex: none;
}

/* ---------- The files ---------- */

.export-dialog__files {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
}

/* In a narrow window the download button drops under the name instead of squeezing it into a column of single letters. */
.export-file {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
  padding: 0.75rem 0.875rem;
}

.export-file + .export-file {
  border-block-start: 1px solid var(--color-hairline);
}

.export-file__text {
  display: grid;
  flex: 1 1 10rem;
  gap: 0.125rem;
  min-inline-size: 0;
}

.export-file__name {
  font-size: var(--font-size-sm);
  line-height: 1.125rem;
  overflow-wrap: anywhere;
}

.export-file__meta {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

.export-file .button {
  flex: none;
  margin-inline-start: auto;
}

.export-dialog__share {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
}

/* ---------- A copy elsewhere ---------- */

/* One column that may shrink below what its content would like, so a panel wraps its words instead of widening the window. */
.export-dialog__elsewhere {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0.875rem;
}

.export-dialog__divider {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.35;
}

.export-dialog__divider::after {
  content: '';
  flex: 1;
  border-block-start: 1px solid var(--color-hairline);
}

.export-dialog__panels {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1rem;
}

/* Both panels hide themselves where Google is not offered; a heading above nothing would be a stray line. */
.export-dialog__panels:empty,
.export-dialog__elsewhere:has(> .export-dialog__panels:empty) {
  display: none;
}

/* ---------- The foot ---------- */

.export-dialog__footer {
  position: relative;
}

.export-dialog__footer .button {
  --button-icon: 1.125rem;
  --button-gap: 0.5rem;
}

/* The left of the foot holds what steps back from the main action; it also keeps the main action at the right. */
.export-dialog__foot-start {
  display: flex;
  flex: 1 1 12rem;
  align-items: center;
  gap: 0.625rem;
  min-inline-size: 0;
}

.export-dialog__foot-note {
  flex: 1 1 0;
  min-inline-size: 0;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

/* Kept in its place while hidden and floated over the body's faded foot, so the foot never changes size as the body scrolls. */
.export-dialog__more {
  position: absolute;
  inset-block-end: calc(100% + 0.25rem);
  inset-inline-start: 50%;
  translate: -50% 0;
  visibility: hidden;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0 var(--space-2);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.6;
  cursor: pointer;
}

.export-dialog__more--shown {
  visibility: visible;
}

@media (max-width: 30rem) {
  .export-dialog__foot-start {
    flex-basis: 100%;
  }
}
</style>
