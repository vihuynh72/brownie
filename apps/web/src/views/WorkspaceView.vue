<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { readDocumentHandoff } from '@/router/handoff'
// PDF.js is the largest thing this page can load; it is fetched only once the preview pane is shown.
const PdfPreview = defineAsyncComponent(() => import('@/components/PdfPreview.vue'))
import {
  ApiRequestError,
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  approveExport,
  artifactDownloadUrl,
  artifactPreviewUrl,
  attachDocumentSource,
  cancelJob,
  compileRevision,
  completeUpload,
  executeAssist,
  exportDocument,
  extractArtifact,
  getDocument,
  getDocumentRevision,
  getEvidenceExcerpt,
  getExtractionResult,
  getGenerationQuestions,
  getJob,
  getLatestCompilation,
  getLatestExportApproval,
  getLatestExportReceipt,
  getLatestValidation,
  getTemplateVersion,
  importCalendarEvent,
  interpretAssist,
  listDocumentRevisions,
  listDocumentSources,
  listGenerationRuns,
  listTemplateVersionRules,
  patchDocumentContent,
  recordReviewDecision,
  resumeGeneration,
  retryJob,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
  validateDocument,
  type ArtifactResponse,
  type DocumentResponse,
  type DocumentRevisionResponse,
  type CalendarImportResponse,
  type DocumentSourceResponse,
  type EvidenceExcerptResponse,
  type ExportApprovalResponse,
  type ExportFormat,
  type ExportReceiptResponse,
  type FieldDefinitionResponse,
  type FieldEditRequest,
  type FieldLock,
  type FieldStateResponse,
  type PatchAcceptResponse,
  type PatchProposalResponse,
  type QuestionResponse,
  type AssistInterpretationResponse,
  type ReviewDecision,
  type RuleResponse,
  type ValidationManifestResponse,
} from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { describePayload, describeScope } from '@/rules/describeRule'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { consentOutcome, originLink, originSentence } from '@/connections/words'
import CalendarSourcePicker from '@/components/CalendarSourcePicker.vue'

const props = defineProps<{ documentId: number }>()

const session = useSessionStore()
const document = ref<DocumentResponse | null>(null)
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')

// One persistent polite live region for the page: assistive technology announces changes to an
// element that was already in the tree, which a message rendered by v-if at the moment it matters
// is not. Everything worth hearing (saving, saved, conflict, validation, preview) goes through it.
const liveMessage = ref('')
function announce(text: string): void {
  // Clearing first makes a repeated message (a second "Saved.") announce again.
  liveMessage.value = ''
  void nextTick(() => {
    liveMessage.value = text
  })
}

const uploadLimit = ref<string | null>(null)
/** The same limit in bytes, to tell a file refused for its size from one refused for what it unpacks to. */
const uploadLimitBytes = ref<number | null>(null)
onMounted(async () => {
  try {
    const { maxUploadBytes } = await loadCapabilities()
    // An older server may not send the limit at all; then neither the hint nor a size refusal names one.
    if (typeof maxUploadBytes === 'number' && maxUploadBytes > 0) {
      uploadLimitBytes.value = maxUploadBytes
      uploadLimit.value = formatBytes(maxUploadBytes)
    }
  } catch {
    uploadLimit.value = null
    uploadLimitBytes.value = null
  }
})

const INSPECTOR_TABS = ['assist', 'rules', 'sources', 'checks', 'history'] as const
type InspectorTab = (typeof INSPECTOR_TABS)[number]
const activeTab = ref<InspectorTab>('sources')

// Rules tab: read-only. The rules that apply to this document are the accepted ones on its own
// template version; proposed and rejected ones are counted, not listed, since deciding them is the
// template's business (the teaching screen), not the document's.
const rules = ref<RuleResponse[]>([])
const rulesLoadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const rulesInForce = computed(() =>
  rules.value.filter((rule) => rule.templateVersionId === document.value?.templateVersionId && rule.status === 'ACCEPTED'),
)
const rulesUndecided = computed(() =>
  rules.value.filter((rule) => rule.templateVersionId === document.value?.templateVersionId && rule.status === 'PROPOSED').length,
)

/** Field ids an accepted rule requires a value for, so the editor can say so before validation does. */
const ruleRequiredFieldIds = computed(() => {
  const ids = new Set<string>()
  for (const rule of rulesInForce.value) {
    if (rule.payload.kind === 'REQUIRED_FIELDS') for (const fieldId of rule.payload.fieldIds ?? []) ids.add(fieldId)
  }
  return ids
})

async function loadRules(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return
  rulesLoadState.value = 'loading'
  try {
    // The version route answers for an activated version; the template's own rules route only
    // answers for an open draft, which the version a document is created from never is.
    rules.value = (await listTemplateVersionRules(workspaceId, document.value.templateId, document.value.templateVersionId)) ?? []
    rulesLoadState.value = 'loaded'
  } catch {
    rulesLoadState.value = 'error'
  }
}

watch(activeTab, (tab) => {
  if (tab === 'rules' && (rulesLoadState.value === 'idle' || rulesLoadState.value === 'error')) void loadRules()
  if (tab !== 'sources') calendarPickerStartsOpen.value = false
})

function selectTab(tab: InspectorTab): void {
  activeTab.value = tab
  if (tab === 'history') void loadRevisionHistory()
  if (tab === 'checks') void hydrateChecksState()
}

/** The empty document's one call to action: open the Sources tab and put focus on its file picker. */
async function goToSources(): Promise<void> {
  selectTab('sources')
  await nextTick()
  window.document.getElementById('attach-source')?.focus()
}

/** Template refs for each tab button, in tab order -- Vue keeps this array in sync with the v-for automatically. */
const tabButtonEls = ref<HTMLButtonElement[]>([])

function focusTab(index: number): void {
  tabButtonEls.value[index]?.focus()
}

/**
 * Roving-tabindex arrow-key navigation for the inspector tablist (WAI-ARIA tabs pattern): the
 * arrow keys both move focus and activate the target tab, Home/End jump to the first/last tab,
 * and every other key is left alone.
 */
function onTabKeydown(event: KeyboardEvent, index: number): void {
  let nextIndex: number
  switch (event.key) {
    case 'ArrowRight':
    case 'ArrowDown':
      nextIndex = (index + 1) % INSPECTOR_TABS.length
      break
    case 'ArrowLeft':
    case 'ArrowUp':
      nextIndex = (index - 1 + INSPECTOR_TABS.length) % INSPECTOR_TABS.length
      break
    case 'Home':
      nextIndex = 0
      break
    case 'End':
      nextIndex = INSPECTOR_TABS.length - 1
      break
    default:
      return
  }
  event.preventDefault()
  selectTab(INSPECTOR_TABS[nextIndex])
  focusTab(nextIndex)
}

// Below the 720px breakpoint the inspector pane (tab-strip + tabpanel) collapses into a
// togglable drawer instead of always being visible beneath the preview pane. The collapse
// itself is CSS-only (see .inspector-drawer--collapsed, scoped inside that same media query),
// so this ref just tracks open/closed state -- it has no visible effect at or above the
// breakpoint, and none of the logic below needs to know the actual viewport width.
const drawerOpen = ref(false)
const drawerToggleRef = ref<HTMLButtonElement | null>(null)
const drawerRef = ref<HTMLDivElement | null>(null)
const drawerHeadingRef = ref<HTMLHeadingElement | null>(null)

function toggleDrawer(): void {
  drawerOpen.value = !drawerOpen.value
}

function closeDrawer(): void {
  drawerOpen.value = false
  drawerToggleRef.value?.focus()
}

watch(drawerOpen, async (open) => {
  if (!open) return
  await nextTick()
  drawerHeadingRef.value?.focus()
})

// The drawer exists only below the breakpoint. A drawer opened on a narrow viewport that then
// grows (a window resized, a phone rotated) would otherwise keep its Tab trap on what is now an
// ordinary column; closing it the moment the viewport is wide releases the trap.
const narrowViewport =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 720px)') : null
function releaseDrawerOnWideViewport(event: { matches: boolean }): void {
  if (!event.matches && drawerOpen.value) drawerOpen.value = false
}
onMounted(() => {
  if (narrowViewport && typeof narrowViewport.addEventListener === 'function') {
    narrowViewport.addEventListener('change', releaseDrawerOnWideViewport)
  }
})
onBeforeUnmount(() => {
  if (narrowViewport && typeof narrowViewport.removeEventListener === 'function') {
    narrowViewport.removeEventListener('change', releaseDrawerOnWideViewport)
  }
})

const DRAWER_FOCUSABLE_SELECTOR = 'a[href], button, input, select, textarea, [tabindex]'

/**
 * Elements that are actual tab stops inside the drawer right now -- the broad selector above,
 * narrowed to what a real Tab key press would actually land on: nothing disabled, and nothing on
 * tabindex="-1" (the roving-tabindex tab buttons this same keydown-Tab check would otherwise wrongly
 * treat as separate stops, since e.g. "button:not([disabled])" alone can't see their tabindex).
 */
function drawerFocusable(): HTMLElement[] {
  if (!drawerRef.value) return []
  return Array.from(drawerRef.value.querySelectorAll<HTMLElement>(DRAWER_FOCUSABLE_SELECTOR)).filter(
    (el) => !el.hasAttribute('disabled') && el.tabIndex >= 0,
  )
}

/**
 * Escape closes the drawer; Tab/Shift+Tab wrap at the drawer's own first/last focusable element so
 * focus can never leave it while open. Gated on drawerOpen itself, not just on the drawer being
 * visible -- above the 720px breakpoint .inspector-drawer--collapsed has no effect at all (it's
 * scoped inside that media query), so the drawer's contents stay visible and focusable regardless
 * of drawerOpen's value; without this guard, Tab would trap a desktop keyboard user inside this
 * region forever; a real WCAG 2.1.2 violation this component's own test suite never caught because
 * jsdom's default viewport already satisfies the >=720px case every test runs under.
 */
function onDrawerKeydown(event: KeyboardEvent): void {
  if (!drawerOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDrawer()
    return
  }
  if (event.key !== 'Tab' || !drawerRef.value) return

  const focusable = drawerFocusable()
  if (focusable.length === 0) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]

  // `document` above is this component's own ref (the loaded DocumentResponse), not the DOM
  // global -- window.document is needed here to read the real activeElement.
  if (event.shiftKey && window.document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && window.document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

// Whatever the new-document screen handed over when it navigated here (see router/handoff.ts): the
// source it attached during creation, shown at once while the server's own list loads, and a
// warning if that attachment failed -- shown here, on the screen the person actually lands on,
// rather than lost with the creation screen's own unmounted state. The server's document-source
// list is the truth; the handoff only bridges the first paint.
const handoff = readDocumentHandoff()
const attachedSources = ref<DocumentSourceResponse[]>(
  (handoff?.attachedSources ?? []).map((source) => ({ ...source, attachedAt: source.fetchedAt })),
)
const handoffWarning = ref<string | null>(handoff?.sourceWarning ?? null)
const sourceUploadState = ref<'idle' | 'uploading' | 'error'>('idle')
const sourceUploadError = ref<string | null>(null)
/** Which attached source Assist extracts from; defaults to the most recently attached one. */
const selectedSourceId = ref<number | null>(null)
/** Sources attached on this page, by upload or by copying: kept when a list read before them arrives after them. */
const sourcesAddedHere = new Set<number>()

async function loadDocumentSources(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  try {
    const sources = await listDocumentSources(workspaceId, props.documentId)
    // The server's list is the truth once it answers; until then, or if it answers with nothing
    // while the creation screen just handed a source over, the handoff copy stays on screen.
    if (Array.isArray(sources) && sources.length > 0) {
      const newer = attachedSources.value.filter(
        (source) => sourcesAddedHere.has(source.id) && !sources.some((listed) => listed.id === source.id),
      )
      attachedSources.value = [...newer, ...sources]
    }
  } catch {
    // The handoff copy (if any) stays on screen; attaching still works and refreshes the list.
  }
  if (selectedSourceId.value === null || !attachedSources.value.some((source) => source.id === selectedSourceId.value)) {
    selectedSourceId.value = attachedSources.value[0]?.id ?? null
  }
}

// Google sends the person back here after they connect Google Calendar from the Sources tab, with
// what happened in the address. It is read once and taken out of the address, so a reload or a
// shared link does not say it again, and it is said at the top of the page rather than in the
// tab, which a narrow screen keeps in a closed drawer.
const route = useRoute()
const router = useRouter()
const calendarConsent = ref(readCalendarConsent())
/** The picker opens by itself on this first visit to the tab only. */
const calendarPickerStartsOpen = ref(calendarConsent.value !== null)
const calendarConsentElement = ref<HTMLElement | null>(null)
watch(calendarConsentElement, (element) => element?.focus())
function readCalendarConsent(): { tone: 'success' | 'failure'; text: string } | null {
  const outcome = consentOutcome(route.query)
  if (outcome === null) return null
  const rest = { ...route.query }
  delete rest.google
  delete rest.access
  delete rest.reason
  void router.replace({ query: rest })
  return { tone: outcome.tone, text: outcome.text }
}

/**
 * Copies one calendar event into this document. Done here rather than in the picker, which lives
 * in the Sources tab: a copy that finishes after the person has moved to another tab still joins
 * the document's sources, where Assist looks for them.
 */
async function copyCalendarEvent(eventId: string): Promise<CalendarImportResponse> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) throw new Error('Brownie is still confirming who is signed in.')
  const selectedBefore = selectedSourceId.value
  const result = await importCalendarEvent(workspaceId, props.documentId, eventId)
  sourcesAddedHere.add(result.source.id)
  attachedSources.value = [result.source, ...attachedSources.value.filter((attached) => attached.id !== result.source.id)]
  // Chosen for Assist only if the person has not chosen another source meanwhile, and no run is reading one.
  const runUnderWay = ['starting', 'running', 'waiting-for-input', 'resuming'].includes(extractionStage.value)
  if (!runUnderWay && selectedSourceId.value === selectedBefore) selectedSourceId.value = result.source.id
  return result
}

/**
 * 'unconfirmed' is a run that moved on to a state whose details this page could not read (its
 * questions, or its result). Nothing is running here any more, so neither "Extracting…" nor Cancel
 * is shown; and nothing new is started until "Check again" has read the run back, since a second
 * start would be a second paid run.
 */
type ExtractionStage =
  | 'idle'
  | 'starting'
  | 'running'
  | 'waiting-for-input'
  | 'resuming'
  | 'succeeded'
  | 'failed'
  | 'cancelled'
  | 'unconfirmed'
const extractionStage = ref<ExtractionStage>('idle')
const extractionJobState = ref<string | null>(null)
const extractionError = ref<string | null>(null)
const extractionResultArtifactId = ref<number | null>(null)
const extractionJobId = ref<number | null>(null)
const cancellationRequested = ref(false)
/** Set when polling stopped without knowing where the run ended up; "Check again" re-reads the run from the server. */
const extractionStalled = ref(false)
/** Why polling stopped, shown beside "Check again". */
const stalledMessage = ref('')
/** What a run under way is doing, in words rather than the job queue's state names; null while there is nothing to say. */
const runProgress = computed(() => {
  switch (extractionJobState.value) {
    case 'QUEUED':
      return 'Waiting for a worker to pick this run up.'
    case 'LEASED':
      return 'Brownie is reading your source and filling in the fields.'
    case 'CANCEL_REQUESTED':
      return 'Stopping this run.'
    default:
      return null
  }
})
/** Set while a run has sat QUEUED with no claim attempt for longer than a worker would take to notice it. */
const noWorkerYet = ref(false)
const openQuestions = ref<QuestionResponse[]>([])
const answerDrafts = ref<Record<number, string>>({})
const answeringQuestionId = ref<number | null>(null)

/**
 * A reload, a second tab, or a network drop must never lose a paid run: the latest generation run
 * for this document is read back from the server and the Assist tab resumes from whatever state
 * its job is really in -- still running (poll), waiting for answers (show them), finished (offer to
 * apply), or ended (say so). Nothing here starts a job.
 */
async function rehydrateLatestRun(): Promise<void> {
  try {
    await followLatestRun()
  } catch {
    // The Assist tab starts as it would before any run; the runs themselves are safe on the server.
  }
}

/** What rehydrateLatestRun does, but throwing when the runs could not be listed, so "Check again" can say so. */
async function followLatestRun(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  const runs = (await listGenerationRuns(workspaceId, props.documentId)) ?? []
  const latest = runs[0]
  if (!latest) return
  extractionJobId.value = latest.jobId
  extractionJobState.value = latest.job.state
  cancellationRequested.value = latest.job.cancellationRequestedAt != null
  extractionStalled.value = false
  switch (latest.job.state) {
    case 'QUEUED':
    case 'LEASED':
    case 'CANCEL_REQUESTED':
      extractionStage.value = 'running'
      void pollJobUntilTerminal(workspaceId, latest.jobId)
      break
    case 'WAITING_FOR_INPUT':
      try {
        openQuestions.value = await getGenerationQuestions(workspaceId, props.documentId, latest.jobId)
      } catch (error) {
        // An empty question list would offer Continue with nothing answered.
        stopFollowingUnreadRun('This run is waiting for your answers, but its questions could not be loaded.', error, 'questions for a run')
        break
      }
      extractionStage.value = 'waiting-for-input'
      break
    case 'SUCCEEDED':
      extractionResultArtifactId.value = latest.resultArtifactId ?? null
      extractionStage.value = latest.resultArtifactId != null ? 'succeeded' : 'failed'
      if (latest.resultArtifactId == null) extractionError.value = 'The last run finished without a readable result.'
      break
    case 'CANCELLED':
      extractionStage.value = 'cancelled'
      break
    default:
      extractionStage.value = 'failed'
      // DEAD and FAILED are the job queue's names for a run that stopped trying, not words for a person.
      extractionError.value =
        latest.job.state === 'DEAD' || latest.job.state === 'FAILED'
          ? 'The last run gave up before it could finish.'
          : 'The last run ended without a result.'
  }
}

/**
 * Stops following a run whose next step could not be read. The run is fine on the server; this
 * page just does not know where it is, so it offers "Check again" rather than showing a run still
 * under way, with a Cancel for it, or inviting a second, paid start.
 */
function stopFollowingUnreadRun(what: string, error: unknown, feature: string): void {
  extractionStage.value = 'unconfirmed'
  extractionStalled.value = true
  const why = describeCommonFailure(error, feature)
  stalledMessage.value = why ? `${what} ${why}` : what
}

type ApplyStage = 'idle' | 'applying' | 'proposed' | 'accepting' | 'accepted' | 'failed'
const applyStage = ref<ApplyStage>('idle')
const applyError = ref<string | null>(null)
const patchProposal = ref<PatchProposalResponse | null>(null)
const acceptResult = ref<PatchAcceptResponse | null>(null)

const fieldActionError = ref<string | null>(null)
const fieldActionPending = ref<string | null>(null)

type ValidationStage = 'idle' | 'validating' | 'validated' | 'failed'
const validationStage = ref<ValidationStage>('idle')
const validationError = ref<string | null>(null)
const validationManifest = ref<ValidationManifestResponse | null>(null)
/** The revision id `hydrateChecksState` has already tried to rehydrate check state for -- never re-fetched a second time for the same revision. */
const checksHydratedForRevisionId = ref<number | null>(null)

const exportFormat = ref<ExportFormat>('BOTH')
/** As the format choice names them, not as the server spells them. */
const FORMAT_NAMES: Record<ExportFormat, string> = { DOCX: 'DOCX', PDF: 'PDF', BOTH: 'DOCX and PDF' }
/**
 * Says what was exported in terms of what was asked for. A Word-only export is complete, not "only one
 * file"; and when a PDF was asked for and could not be made, the Word file is offered with the reason.
 */
const exportOutcomeMessage = computed(() => {
  const receipt = exportReceipt.value
  if (!receipt) return ''
  const hasPdf = receipt.pdfArtifactId != null
  switch (receipt.format) {
    case 'DOCX':
      return 'DOCX exported.'
    case 'PDF':
      return hasPdf ? 'PDF exported.' : 'The PDF could not be made for this version, so the DOCX is offered instead.'
    default:
      return hasPdf ? 'Both files exported.' : 'The DOCX was exported; the PDF could not be made for this version.'
  }
})

type ApprovalStage = 'idle' | 'approving' | 'approved' | 'failed'
const approvalStage = ref<ApprovalStage>('idle')
const approvalError = ref<string | null>(null)
const exportApproval = ref<ExportApprovalResponse | null>(null)

type ExportStage = 'idle' | 'exporting' | 'exported' | 'failed'
const exportStage = ref<ExportStage>('idle')
const exportError = ref<string | null>(null)
const exportReceipt = ref<ExportReceiptResponse | null>(null)

const revisions = ref<DocumentRevisionResponse[]>([])
type RevisionsLoadState = 'idle' | 'loading' | 'loaded' | 'error'
const revisionsLoadState = ref<RevisionsLoadState>('idle')
const revisionsError = ref<string | null>(null)

const compareRevisionId = ref<number | null>(null)
type CompareStage = 'idle' | 'loading' | 'loaded' | 'error'
const compareStage = ref<CompareStage>('idle')
const compareError = ref<string | null>(null)
const compareRevision = ref<DocumentRevisionResponse | null>(null)

/** Why the first load failed, for the message that stands in for the editor; null otherwise. */
const loadError = ref<string | null>(null)
/**
 * Why a reload after the first load failed. The editor stays exactly as it was -- the document last loaded, the
 * person's drafts and their focus -- with this said above it, since what it shows may be out of date.
 */
const reloadError = ref<string | null>(null)
/** Set when a request for this document found it gone; the messages that say so link to the trash bin. */
const documentGone = ref(false)

/** Brownie's own "not there": a document answers every read and write that way once it is in the trash. */
function isDocumentGone(error: unknown): boolean {
  return brownieSaysNotThere(error)
}

/** Loads the document and answers whether it could, so a caller never acts on a copy it only assumes is current. */
async function loadDocument(): Promise<boolean> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return false
  // Only the first load shows the loading state, and only its failure replaces the editor. A reload
  // after a save, a review decision or an accepted proposal keeps the editor mounted: unmounting it
  // would drop keyboard focus and any text the person is typing at that moment.
  const reloading = document.value !== null
  if (!reloading) loadState.value = 'loading'
  try {
    const loaded = await getDocument(workspaceId, props.documentId)
    document.value = loaded
    loadState.value = 'loaded'
    loadError.value = null
    reloadError.value = null
    documentGone.value = false
    if (definitionsLoadedForVersionId.value !== loaded.templateVersionId) {
      await loadFieldDefinitions(workspaceId, loaded)
      void loadRules()
    }
    // A reload triggered by some other action (a review decision, an accepted proposal, a
    // validation run) must never wipe values the person is still typing; only a clean editor
    // follows the server. A save marks the editor clean itself before it reloads.
    if (!isDirty.value) resetDrafts()
    if (!sidePanelsHydrated) {
      sidePanelsHydrated = true
      await loadDocumentSources()
      await rehydrateLatestRun()
    }
    return true
  } catch (error) {
    const gone = isDocumentGone(error)
    if (gone) documentGone.value = true
    if (!reloading) {
      loadState.value = 'error'
      loadError.value = gone
        ? 'This document is not available. It may have been moved to the trash, where it can be restored.'
        : (describeCommonFailure(error, 'a way to open this document') ?? 'Could not load this document.')
      return false
    }
    if (gone) {
      reloadError.value =
        'This document is no longer available, for example because it was moved to the trash in another tab. ' +
        'What is shown here is the last version this page loaded.'
    } else {
      const why = describeCommonFailure(error, 'a way to open this document')
      reloadError.value = `The latest version of this document could not be loaded, so what is shown here may be out of date.${why ? ` ${why}` : ''}`
    }
    return false
  }
}

/** Sources and the latest run are read once per page load; later reloads of the document (after a save, a review) must not restart polling or replace an in-progress question list. */
let sidePanelsHydrated = false

onMounted(loadDocument)
watch(() => session.status, (status) => {
  if (status === 'authenticated') void loadDocument()
})

function fieldDisplayValue(field: DocumentRevisionResponse['fields'][string] | undefined): string {
  if (!field) return '—'
  return field.value ?? ((field.values ?? []).join(', ') || '—')
}

// ---- Editing by hand -----------------------------------------------------------------------
//
// The editor is driven by the template version's own field definitions, so every field the
// template defines gets a control even before it holds a value. Drafts live apart from the loaded
// document: what the person types is theirs until they save, and a save is one typed PATCH against
// the exact revision they were looking at -- the server refuses a stale revision (412) or a locked
// field (409) rather than letting either side's work silently vanish.

const fieldDefinitions = ref<FieldDefinitionResponse[]>([])
const definitionsLoadedForVersionId = ref<number | null>(null)

/**
 * Best effort: the template's definitions are the ideal source, but a document whose template
 * lookup fails must still be editable for the fields its revision already holds, so a failure
 * here falls back to those (see editableFields) instead of disabling editing.
 */
async function loadFieldDefinitions(workspaceId: number, loaded: DocumentResponse): Promise<void> {
  try {
    const version = await getTemplateVersion(workspaceId, loaded.templateId, loaded.templateVersionId)
    fieldDefinitions.value = version?.fields ?? []
  } catch {
    fieldDefinitions.value = []
  }
  definitionsLoadedForVersionId.value = loaded.templateVersionId
}

interface EditableField {
  fieldId: string
  type: 'TEXT' | 'DATE'
  cardinality: 'SCALAR' | 'REPEATED'
  requiredness: 'REQUIRED' | 'OPTIONAL' | null
}

const editableFields = computed<EditableField[]>(() => {
  if (fieldDefinitions.value.length > 0) {
    return fieldDefinitions.value.map((definition) => ({
      fieldId: definition.fieldId,
      type: definition.type,
      cardinality: definition.cardinality,
      requiredness: definition.requiredness,
    }))
  }
  const fields = document.value?.currentRevision.fields ?? {}
  return Object.entries(fields).map(([fieldId, field]) => ({
    fieldId,
    type: field.type,
    cardinality: field.cardinality,
    requiredness: null,
  }))
})

const scalarFields = computed(() => editableFields.value.filter((field) => field.cardinality === 'SCALAR'))
/**
 * Every repeated field of a template is one column of the same logical row (an action item's task,
 * owner, and due date, for the built-in minutes), and the filler requires them to hold the same
 * number of items -- so they are edited together as rows, never one list at a time.
 */
const repeatedFields = computed(() => editableFields.value.filter((field) => field.cardinality === 'REPEATED'))
const editableFieldIds = computed(() => new Set(editableFields.value.map((field) => field.fieldId)))

/** Moves keyboard focus to a field's control (a repeated field's first row), so a finding can be fixed without hunting for it. */
function focusField(fieldId: string): void {
  const target =
    window.document.getElementById(`edit-${fieldId}`) ?? window.document.getElementById(`edit-${fieldId}-0`)
  if (!(target instanceof HTMLElement)) return
  target.scrollIntoView?.({ block: 'center' })
  target.focus()
}

type Drafts = Record<string, string | string[]>
const drafts = ref<Drafts>({})
const cleanDrafts = ref<Drafts>({})

function draftsFromRevision(): Drafts {
  const fields = document.value?.currentRevision.fields ?? {}
  const next: Drafts = {}
  for (const field of scalarFields.value) {
    next[field.fieldId] = fields[field.fieldId]?.value ?? ''
  }
  const rowCount = Math.max(0, ...repeatedFields.value.map((field) => fields[field.fieldId]?.values?.length ?? 0))
  for (const field of repeatedFields.value) {
    const values = fields[field.fieldId]?.values ?? []
    next[field.fieldId] = Array.from({ length: rowCount }, (_, index) => values[index] ?? '')
  }
  return next
}

function resetDrafts(): void {
  const next = draftsFromRevision()
  drafts.value = next
  cleanDrafts.value = JSON.parse(JSON.stringify(next))
  saveStage.value = 'idle'
  saveError.value = null
}

const isDirty = computed(() => JSON.stringify(drafts.value) !== JSON.stringify(cleanDrafts.value))
const hasAnyValue = computed(() => Object.keys(document.value?.currentRevision.fields ?? {}).length > 0)

function scalarDraft(fieldId: string): string {
  const value = drafts.value[fieldId]
  return typeof value === 'string' ? value : ''
}

function rowDraft(fieldId: string): string[] {
  const value = drafts.value[fieldId]
  return Array.isArray(value) ? value : []
}

const rowCount = computed(() => {
  const first = repeatedFields.value[0]
  return first ? rowDraft(first.fieldId).length : 0
})

const rowIndexes = computed(() => Array.from({ length: rowCount.value }, (_, index) => index))

/** A human label from a stable field id: "action.item.due" reads as "Action item due"; the id itself stays available to assistive tech through aria-describedby. */
function labelFor(fieldId: string): string {
  const words = fieldId.replace(/[._-]+/g, ' ').trim()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const STATE_LABELS: Record<string, Record<string, string>> = {
  authorship: { IMPORTED: 'Imported', AI_COMPOSED: 'From Assist', USER_AUTHORED: 'Typed by you', MIXED: 'Assist and you' },
  evidenceSupport: {
    DIRECT: 'Source cited',
    TRANSFORMED: 'Source cited, reworded',
    AMBIGUOUS: 'Evidence unclear',
    UNSUPPORTED: 'Not in the source',
    MISSING: 'No source cited',
  },
  validation: { PASSED: 'Checks passed', WARNING: 'Check warning', BLOCKING: 'Blocks export', UNAVAILABLE: 'Not checked' },
  review: { UNREVIEWED: 'Not reviewed', ACCEPTED: 'Accepted', REJECTED: 'Rejected', NEEDS_CLARIFICATION: 'Needs clarification' },
  lock: { PRESERVE_ON_REGENERATION: 'Kept on regeneration', EXPLICITLY_LOCKED: 'Locked' },
}

/** The words a person reads for a field-state value; the raw constant only when no wording exists for it. */
function stateLabel(dimension: keyof FieldStateResponse, value: string): string {
  return STATE_LABELS[dimension]?.[value] ?? value
}

/** The chips worth showing for one field state: nothing that only restates the obvious (an unchecked field, an unlocked field, no source for a typed value). */
function stateChips(state: FieldStateResponse): string[] {
  const chips = [stateLabel('authorship', state.authorship)]
  if (!(state.evidenceSupport === 'MISSING' && state.authorship === 'USER_AUTHORED')) {
    chips.push(stateLabel('evidenceSupport', state.evidenceSupport))
  }
  if (state.validation !== 'NOT_RUN') chips.push(stateLabel('validation', state.validation))
  chips.push(stateLabel('review', state.review))
  if (state.lock !== 'EDITABLE') chips.push(stateLabel('lock', state.lock))
  return chips
}

function fieldStateOf(fieldId: string): FieldStateResponse | null {
  return document.value?.currentRevision.fields[fieldId]?.fieldState ?? null
}

function isFieldLocked(fieldId: string): boolean {
  return fieldStateOf(fieldId)?.lock === 'EXPLICITLY_LOCKED'
}

/** The state of one row, read from its first column; every column of a row is reviewed and locked together by the row controls below. */
function rowState(index: number): FieldStateResponse | null {
  const first = repeatedFields.value[0]
  if (!first) return null
  return document.value?.currentRevision.fields[first.fieldId]?.itemFieldStates?.[index] ?? null
}

/** A lock on any row of any column blocks editing every row: the server refuses a field edit while one of its items is locked, and the columns can only be saved together. */
const rowsLocked = computed(() =>
  repeatedFields.value.some((field) =>
    (document.value?.currentRevision.fields[field.fieldId]?.itemFieldStates ?? []).some((state) => state?.lock === 'EXPLICITLY_LOCKED'),
  ),
)

/** Rows the server would refuse: a date column cannot be blank, because a blank is not a date. */
const rowProblems = computed<string[]>(() => {
  const problems: string[] = []
  for (const index of rowIndexes.value) {
    for (const field of repeatedFields.value) {
      if (field.type === 'DATE' && !(rowDraft(field.fieldId)[index] ?? '').trim()) {
        problems.push(`Row ${index + 1} needs a value for ${labelFor(field.fieldId)}, or remove the row.`)
      }
    }
  }
  return problems
})

function addRow(): void {
  for (const field of repeatedFields.value) {
    drafts.value[field.fieldId] = [...rowDraft(field.fieldId), '']
  }
}

function removeRow(index: number): void {
  for (const field of repeatedFields.value) {
    drafts.value[field.fieldId] = rowDraft(field.fieldId).filter((_, position) => position !== index)
  }
}

function moveRow(index: number, delta: -1 | 1): void {
  const target = index + delta
  if (target < 0 || target >= rowCount.value) return
  for (const field of repeatedFields.value) {
    const values = [...rowDraft(field.fieldId)]
    const moved = values[index]!
    values[index] = values[target]!
    values[target] = moved
    drafts.value[field.fieldId] = values
  }
}

type SaveStage = 'idle' | 'saving' | 'saved' | 'conflict' | 'failed'
const saveStage = ref<SaveStage>('idle')
const saveError = ref<string | null>(null)
const editNote = ref('')

function buildEdits(): FieldEditRequest[] {
  const fields = document.value?.currentRevision.fields ?? {}
  const edits: FieldEditRequest[] = []
  for (const field of scalarFields.value) {
    const draft = scalarDraft(field.fieldId).trim()
    const clean = cleanDrafts.value[field.fieldId]
    if (draft === (typeof clean === 'string' ? clean : '')) continue
    if (draft === '') {
      if (fields[field.fieldId]) edits.push({ operation: 'CLEAR', fieldId: field.fieldId })
      continue
    }
    edits.push({ operation: 'SET', fieldId: field.fieldId, value: { type: field.type, cardinality: 'SCALAR', value: draft } })
  }
  const rowsChanged = repeatedFields.value.some(
    (field) => JSON.stringify(rowDraft(field.fieldId)) !== JSON.stringify(cleanDrafts.value[field.fieldId] ?? []),
  )
  if (rowsChanged) {
    for (const field of repeatedFields.value) {
      const values = rowIndexes.value.map((index) => (rowDraft(field.fieldId)[index] ?? '').trim())
      if (values.length === 0) {
        if (fields[field.fieldId]) edits.push({ operation: 'CLEAR', fieldId: field.fieldId })
        continue
      }
      edits.push({ operation: 'SET', fieldId: field.fieldId, value: { type: field.type, cardinality: 'REPEATED', values } })
    }
  }
  return edits
}

async function saveEdits(trigger: 'manual' | 'auto' = 'manual'): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value || rowProblems.value.length > 0) return
  const edits = buildEdits()
  if (edits.length === 0) return

  // What this save sends. Anything typed after this point, while the request is in flight, must
  // survive the reload: the server's copy replaces only the fields that still hold exactly what
  // was sent, and the rest stay dirty and are saved by the next pause.
  const sent: Drafts = JSON.parse(JSON.stringify(drafts.value))
  saveStage.value = 'saving'
  saveError.value = null
  try {
    await patchDocumentContent(
      workspaceId,
      props.documentId,
      {
        expectedRevisionId: document.value.currentRevision.id,
        edits,
        editReason: editNote.value.trim() || (trigger === 'auto' ? 'Autosaved.' : 'Edited in the workspace.'),
      },
      crypto.randomUUID(),
    )
    editNote.value = ''
    if (await loadDocument()) {
      reconcileDraftsAfterSave(sent)
    } else {
      // The save happened, but the copy on screen is still the one from before it, so the server's
      // values cannot be taken from it. What was sent is what the server now holds; anything typed
      // since stays unsaved, and its save, made against the revision on screen, is refused as stale
      // and reloads the document.
      cleanDrafts.value = JSON.parse(JSON.stringify(sent))
    }
    saveStage.value = 'saved'
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 412) {
      // Someone (or another action in this same browser) moved the document on since this editor
      // last loaded it. The drafts stay exactly as typed; the document reloads underneath them so a
      // second save applies onto what is really current -- the person chooses which.
      saveStage.value = 'conflict'
      await loadDocument()
      return
    }
    saveStage.value = 'failed'
    if (isDocumentGone(error)) documentGone.value = true
    saveError.value = saveFailureMessage(error)
  }
}

/** Why a save did not happen, in words that say what will help. The drafts stay as typed whatever it was. */
function saveFailureMessage(error: unknown): string {
  if (isDocumentGone(error)) {
    return (
      'Your changes were not saved: this document is no longer available, for example because it was moved to ' +
      'the trash. They are still in the fields above.'
    )
  }
  if (error instanceof ApiRequestError) {
    if (error.status === 409 && error.problem?.code === 'FIELD_LOCKED') {
      return error.problem.detail ?? 'A field you changed is locked. Unlock it first, or discard that change.'
    }
    if (error.status === 422 || error.status === 400) {
      return error.problem?.detail ?? error.message
    }
    // Only the server knows how large one save may be, and its explanation says.
    if (error.status === 413) {
      return error.problem?.detail
        ? `Your changes were not saved. ${error.problem.detail}`
        : 'Your changes were not saved: together they are larger than one save may be.'
    }
  }
  const why = describeCommonFailure(error, 'a way to save changes')
  return why ? `Your changes were not saved. ${why}` : 'Could not save your changes. Try again.'
}

/**
 * A 412 from a review, lock, or accept means the document moved on since this page loaded it. The
 * only sound recovery is to reload and let the person act on what is really current; the message
 * says so instead of a generic failure. Returns true when it handled the error that way.
 */
async function reloadedAfterStaleRevision(error: unknown): Promise<boolean> {
  if (!(error instanceof ApiRequestError && error.status === 412)) return false
  if (await loadDocument()) {
    fieldActionError.value = 'This document changed since you loaded it, so it was reloaded. Try again on the current version.'
    announce('This document changed elsewhere and was reloaded.')
  } else {
    // The reload's own message says why the current version is not on screen.
    fieldActionError.value = 'This document changed since you loaded it, so that was not done.'
  }
  return true
}

/**
 * Says why a review decision or a lock did not happen. `fallback` names which one, for a failure
 * no page words the same way.
 */
function reportFieldActionFailure(error: unknown, fallback: string): void {
  if (isDocumentGone(error)) {
    documentGone.value = true
    fieldActionError.value = 'That was not done: this document is no longer available, for example because it was moved to the trash.'
    return
  }
  fieldActionError.value = describeCommonFailure(error, 'field reviews and locks') ?? fallback
}

// ---------------------------------------------------------------------------------------------
// Autosave: a pause in typing saves what changed against the revision this editor loaded. Rows
// with a problem (a blank date) are never sent; the problem is shown instead. A conflict stops
// autosaving until the person chooses what to do with their edits; a failed save waits for the
// next edit rather than retrying on its own. "Save now" remains for people who want to be sure.
const AUTOSAVE_DELAY_MS = 2500
let autosaveTimer: ReturnType<typeof setTimeout> | null = null

function cancelAutosave(): void {
  if (autosaveTimer) clearTimeout(autosaveTimer)
  autosaveTimer = null
}

function scheduleAutosave(): void {
  cancelAutosave()
  autosaveTimer = setTimeout(() => {
    autosaveTimer = null
    void autosave()
  }, AUTOSAVE_DELAY_MS)
}

async function autosave(): Promise<void> {
  if (!isDirty.value || rowProblems.value.length > 0) return
  if (saveStage.value === 'saving' || saveStage.value === 'conflict') return
  await saveEdits('auto')
  // Edits typed while the save was in flight are picked up by the next pause.
  if (isDirty.value && saveStage.value === 'saved') scheduleAutosave()
}

watch(
  drafts,
  () => {
    if (isDirty.value && saveStage.value !== 'conflict') scheduleAutosave()
  },
  { deep: true },
)

watch(saveStage, (stage) => {
  const messages: Record<SaveStage, string> = {
    idle: '',
    saving: 'Saving…',
    saved: 'Saved.',
    conflict: 'This document changed elsewhere. Your edits are kept; choose whether to save them onto the latest version.',
    failed: 'Your changes could not be saved.',
  }
  if (messages[stage]) announce(messages[stage])
})

function hasUnsavedWork(): boolean {
  return isDirty.value || saveStage.value === 'saving'
}

onBeforeRouteLeave(() => {
  if (!hasUnsavedWork()) return true
  return window.confirm('You have unsaved changes on this document. Leave anyway?')
})

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (!hasUnsavedWork()) return
  event.preventDefault()
  // Older browsers need a value; newer ones ignore the text and show their own wording.
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload))
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', warnBeforeUnload)
  cancelAutosave()
})

/**
 * After a save, take the server's normalized values into every field the person has not touched
 * since the save began, and leave the others exactly as typed. Assigning field by field, rather
 * than replacing the drafts object, means an input whose draft did not change is not re-rendered,
 * so a caret in it stays where it was.
 */
function reconcileDraftsAfterSave(sent: Drafts): void {
  const server = draftsFromRevision()
  for (const fieldId of new Set([...Object.keys(server), ...Object.keys(drafts.value)])) {
    const typedSince = JSON.stringify(drafts.value[fieldId]) !== JSON.stringify(sent[fieldId])
    if (typedSince) continue
    const next = server[fieldId] ?? (Array.isArray(sent[fieldId]) ? [] : '')
    if (JSON.stringify(drafts.value[fieldId]) !== JSON.stringify(next)) drafts.value[fieldId] = next
  }
  cleanDrafts.value = JSON.parse(JSON.stringify(server))
}

async function recordRowReview(index: number, decision: ReviewDecision): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return
  const rowKey = `row-${index}`
  fieldActionPending.value = rowKey
  fieldActionError.value = null
  try {
    // One decision per column of the row, each against the revision the previous one produced.
    let revisionId = document.value.currentRevision.id
    for (const field of repeatedFields.value) {
      const revision = await recordReviewDecision(workspaceId, props.documentId, revisionId, field.fieldId, decision, crypto.randomUUID(), index)
      revisionId = revision.id
    }
    await loadDocument()
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not record a review decision for row ${index + 1}. Try again.`)
  } finally {
    fieldActionPending.value = null
  }
}

async function toggleRowLock(index: number): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return
  const nextLock: FieldLock = rowState(index)?.lock === 'EXPLICITLY_LOCKED' ? 'EDITABLE' : 'EXPLICITLY_LOCKED'
  const rowKey = `row-${index}`
  fieldActionPending.value = rowKey
  fieldActionError.value = null
  try {
    let revisionId = document.value.currentRevision.id
    for (const field of repeatedFields.value) {
      const revision = await setFieldLock(workspaceId, props.documentId, revisionId, field.fieldId, nextLock, crypto.randomUUID(), index)
      revisionId = revision.id
    }
    await loadDocument()
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not change the lock for row ${index + 1}. Try again.`)
  } finally {
    fieldActionPending.value = null
  }
}

function resetApprovalAndExportState(): void {
  approvalStage.value = 'idle'
  approvalError.value = null
  exportApproval.value = null
  exportStage.value = 'idle'
  exportError.value = null
  exportReceipt.value = null
}

// ---------------------------------------------------------------------------------------------
// Preview pane: the latest compiled PDF of this document, drawn beside the editor. A compilation is
// never started on its own -- it spawns the isolated renderer -- so the pane shows whatever exists
// for the current content (a compilation from an earlier "Regenerate preview" or from validation,
// which compiles too) and asks for a click to make a new one. Staleness is judged by the
// revision's content hash, not its id: a review decision or a lock makes a new revision without
// changing a single value, and a preview of identical content is not out of date.
type PreviewStage = 'idle' | 'loading' | 'generating' | 'ready' | 'failed'
const previewStage = ref<PreviewStage>('idle')
const previewError = ref<string | null>(null)
const previewArtifactId = ref<number | null>(null)
const previewContentHash = ref<string | null>(null)
const previewRevisionNumber = ref<number | null>(null)
const previewOpen = ref(typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia('(min-width: 1100px)').matches : false)

const previewUrl = computed(() => {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || previewArtifactId.value == null) return null
  return artifactPreviewUrl(workspaceId, previewArtifactId.value)
})
const previewIsStale = computed(
  () => previewContentHash.value != null && document.value != null && previewContentHash.value !== document.value.currentRevision.contentHash,
)

/** Picks up a compilation that already exists for the current content, if any; a 404 just means none has been made yet. */
async function loadExistingPreview(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const current = document.value?.currentRevision
  if (workspaceId === undefined || !current) return
  if (previewContentHash.value === current.contentHash && previewArtifactId.value != null) return
  previewStage.value = 'loading'
  previewError.value = null
  try {
    const compilation = await getLatestCompilation(workspaceId, props.documentId, current.id)
    adoptPreview(compilation.pdfArtifactId, current.contentHash, current.revisionNumber)
  } catch (error) {
    // A server without the route is not one with no preview yet: generating one would fail the same way.
    if (error instanceof ApiRequestError && error.status === 404 && !error.routeMissing) {
      previewStage.value = previewArtifactId.value != null ? 'ready' : 'idle'
      return
    }
    previewStage.value = 'failed'
    previewError.value = describeCommonFailure(error, 'document previews') ?? 'Could not check for an existing preview. Try again.'
  }
}

async function regeneratePreview(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const current = document.value?.currentRevision
  if (workspaceId === undefined || !current) return
  previewStage.value = 'generating'
  previewError.value = null
  try {
    const compilation = await compileRevision(workspaceId, props.documentId, current.id)
    adoptPreview(compilation.pdfArtifactId, current.contentHash, current.revisionNumber)
  } catch (error) {
    previewStage.value = 'failed'
    previewError.value =
      describeCommonFailure(error, 'document previews') ??
      (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'The preview could not be generated. Try again.')
  }
}

function adoptPreview(pdfArtifactId: number, contentHash: string, revisionNumber: number): void {
  previewArtifactId.value = pdfArtifactId
  previewContentHash.value = contentHash
  previewRevisionNumber.value = revisionNumber
  previewStage.value = 'ready'
}

function togglePreview(): void {
  previewOpen.value = !previewOpen.value
}

watch(() => document.value?.currentRevision.contentHash, () => void loadExistingPreview())

// ---------------------------------------------------------------------------------------------
// Evidence: a value filled by Assist carries the ids of the source spans it was taken from. The
// marker opens the cited excerpts, fetched through the document's own evidence route. Nothing
// here can point at a page or a position on the preview: the renderer emits no locator, and
// the panel says so instead of guessing.
const evidenceOpenFor = ref<string | null>(null)
const evidenceExcerpts = ref<EvidenceExcerptResponse[]>([])
const evidenceStage = ref<'idle' | 'loading' | 'ready' | 'failed'>('idle')
const evidenceError = ref<string | null>(null)
const MAX_EVIDENCE_EXCERPTS = 5

function evidenceSpanIdsOf(fieldId: string): number[] {
  return document.value?.currentRevision.fields[fieldId]?.evidenceSourceSpanIds ?? []
}

const repeatedFieldsWithEvidence = computed(() => repeatedFields.value.filter((field) => evidenceSpanIdsOf(field.fieldId).length > 0))

async function toggleEvidence(fieldId: string): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (evidenceOpenFor.value === fieldId) {
    evidenceOpenFor.value = null
    return
  }
  evidenceOpenFor.value = fieldId
  evidenceExcerpts.value = []
  evidenceError.value = null
  if (workspaceId === undefined) return
  evidenceStage.value = 'loading'
  try {
    const spanIds = evidenceSpanIdsOf(fieldId).slice(0, MAX_EVIDENCE_EXCERPTS)
    const excerpts = await Promise.all(spanIds.map((spanId) => getEvidenceExcerpt(workspaceId, props.documentId, spanId)))
    if (evidenceOpenFor.value !== fieldId) return
    evidenceExcerpts.value = excerpts
    evidenceStage.value = 'ready'
  } catch {
    if (evidenceOpenFor.value !== fieldId) return
    evidenceStage.value = 'failed'
    evidenceError.value = 'The cited excerpt could not be loaded.'
  }
}

function resetChecksState(): void {
  validationStage.value = 'idle'
  validationError.value = null
  validationManifest.value = null
  resetApprovalAndExportState()
}

// A revision changes here either because another action already reloaded the document (accepting
// a patch proposal, a field review decision, a lock toggle -- any validate/approve/export state on
// screen was scoped to the revision that just stopped being current, so it can no longer be
// trusted), or because validateCurrentRevision() itself just refreshed the document after the new
// revision its own POST /validate created -- in that one case validationManifest.value already
// names the new revision, so it (and the approval/export state validate just recomputed) must
// survive rather than be wiped a moment after being set. Either way, the revision list any earlier
// History-tab visit cached is now stale and must be re-fetched next time that tab is opened.
watch(() => document.value?.currentRevision.id, (newRevisionId) => {
  revisionsLoadState.value = 'idle'
  revisions.value = []
  if (newRevisionId !== undefined && validationManifest.value?.revisionId === newRevisionId) {
    return
  }
  resetChecksState()
})

/**
 * Shared by validate, approve, and export.
 *
 * A 412 means whatever this action was scoped to (the revision validate ran against, or the
 * manifest/approval approve or export relied on) is no longer current -- the only sound recovery
 * is a fresh validate, so every checks/approval/export ref is cleared here. The message is also
 * written directly to validationError (in addition to being returned) because approvalError and
 * exportError live inside the `validationManifest`-gated part of the template, which this same
 * reset just made disappear -- validationError is the only place left that stays visible.
 *
 * A 404 means only the specific manifest/approval/receipt this action pointed at is gone (e.g.
 * superseded by another approval); the validation manifest itself is still meaningful, so only the
 * approval/export state is reset.
 *
 * A 422 carries a single server message rather than an itemized list.
 *
 * Anything else that every page words the same way -- a busy renderer, too many requests, a
 * server without the route -- is said as such, with the server's own explanation where it gave
 * one; `feature` names the step for a server that does not have it.
 */
function checksFailureMessage(error: unknown, feature: string): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 412) {
      resetChecksState()
      const message = 'This document changed since you last validated it. Validate again.'
      validationError.value = message
      return message
    }
    if (error.status === 404 && !error.routeMissing) {
      resetApprovalAndExportState()
      return error.problem?.detail ?? 'That approval is no longer available. Validate again.'
    }
    if (error.status === 422) {
      return error.problem?.detail ?? error.message
    }
  }
  return describeCommonFailure(error, feature) ?? 'Something went wrong. Try again.'
}

async function validateCurrentRevision(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return

  validationStage.value = 'validating'
  validationError.value = null
  try {
    const manifest = await validateDocument(
      workspaceId,
      props.documentId,
      document.value.currentRevision.id,
      crypto.randomUUID(),
    )
    validationManifest.value = manifest
    resetApprovalAndExportState()
    // /validate always appends a brand-new current revision (it records per-field validation
    // results onto it) distinct from the one just validated -- resync document.value with that new
    // revision so the rest of the page (and the currentRevision.id watcher above) sees it too.
    await loadDocument()
    // Validation compiled this exact content, so its PDF is the preview -- no second render needed.
    if (manifest.pdfArtifactId != null && document.value) {
      adoptPreview(manifest.pdfArtifactId, document.value.currentRevision.contentHash, document.value.currentRevision.revisionNumber)
    }
    validationStage.value = 'validated'
  } catch (error) {
    validationStage.value = 'failed'
    validationError.value = checksFailureMessage(error, 'document checks')
  }
}

// Approving again (same format re-approved, or a new one after the watcher below cleared a prior
// approval) must never leave a stale receipt from an earlier approval visible next to it.
watch(exportFormat, () => {
  if (approvalStage.value !== 'idle') {
    resetApprovalAndExportState()
  }
})

async function approveCurrentExport(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const manifest = validationManifest.value
  if (workspaceId === undefined || !manifest) return

  exportStage.value = 'idle'
  exportError.value = null
  exportReceipt.value = null
  approvalStage.value = 'approving'
  approvalError.value = null
  try {
    exportApproval.value = await approveExport(workspaceId, props.documentId, manifest.id, exportFormat.value)
    approvalStage.value = 'approved'
  } catch (error) {
    approvalStage.value = 'failed'
    approvalError.value = checksFailureMessage(error, 'export approval')
  }
}

async function exportApprovedDocument(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !exportApproval.value) return

  exportStage.value = 'exporting'
  exportError.value = null
  try {
    exportReceipt.value = await exportDocument(workspaceId, props.documentId)
    exportStage.value = 'exported'
  } catch (error) {
    exportStage.value = 'failed'
    exportError.value = checksFailureMessage(error, 'exports')
  }
}

/**
 * Best-effort rehydration for a document that already has validate/approve/export state recorded
 * against its current revision from an earlier session (or before a page reload) -- Validate,
 * Approve for export and Export all remain available and authoritative regardless, so any failure
 * here (including a 404 for "nothing recorded yet", the common case) is silently ignored rather
 * than surfaced as an error.
 */
async function hydrateChecksState(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return
  const revisionId = document.value.currentRevision.id
  if (checksHydratedForRevisionId.value === revisionId) return
  checksHydratedForRevisionId.value = revisionId
  if (validationStage.value !== 'idle') return

  let manifest: ValidationManifestResponse
  try {
    manifest = await getLatestValidation(workspaceId, props.documentId, revisionId)
  } catch {
    return
  }
  validationManifest.value = manifest
  validationStage.value = 'validated'

  let approval: ExportApprovalResponse
  try {
    approval = await getLatestExportApproval(workspaceId, props.documentId)
  } catch {
    return
  }
  if (approval.validationManifestId !== manifest.id) return
  exportApproval.value = approval
  approvalStage.value = 'approved'

  try {
    const receipt = await getLatestExportReceipt(workspaceId, props.documentId)
    if (receipt.exportApprovalId === approval.id) {
      exportReceipt.value = receipt
      exportStage.value = 'exported'
    }
  } catch {
    // No export recorded against this approval yet -- fine, the Export button covers that.
  }
}

async function loadRevisionHistory(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || revisionsLoadState.value === 'loading' || revisionsLoadState.value === 'loaded') return

  revisionsLoadState.value = 'loading'
  revisionsError.value = null
  try {
    revisions.value = await listDocumentRevisions(workspaceId, props.documentId)
    revisionsLoadState.value = 'loaded'
  } catch (error) {
    revisionsLoadState.value = 'error'
    revisionsError.value =
      describeCommonFailure(error, 'revision history') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      "Could not load this document's revision history."
  }
}

// Stamps each call so an out-of-order response (an earlier compare that resolves after a later
// one) can never overwrite the result of a more recent request -- compareRevisionId and
// compareRevision are otherwise two independently-written refs with no other guard tying them together.
let compareRequestToken = 0

async function compareToRevision(revisionId: number): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return

  const requestToken = ++compareRequestToken
  compareRevisionId.value = revisionId
  compareStage.value = 'loading'
  compareError.value = null
  try {
    const revision = await getDocumentRevision(workspaceId, props.documentId, revisionId)
    if (requestToken !== compareRequestToken) return
    compareRevision.value = revision
    compareStage.value = 'loaded'
  } catch (error) {
    if (requestToken !== compareRequestToken) return
    compareStage.value = 'error'
    compareError.value =
      describeCommonFailure(error, 'revision history') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Could not load that revision.'
  }
}

const orderedRevisions = computed<DocumentRevisionResponse[]>(() => [...revisions.value].reverse())

const compareFieldIds = computed<string[]>(() => {
  if (!compareRevision.value || !document.value) return []
  return Array.from(
    new Set([...Object.keys(compareRevision.value.fields), ...Object.keys(document.value.currentRevision.fields)]),
  ).sort()
})

function fieldChanged(fieldId: string): boolean {
  if (!compareRevision.value || !document.value) return false
  return (
    fieldDisplayValue(compareRevision.value.fields[fieldId]) !==
    fieldDisplayValue(document.value.currentRevision.fields[fieldId])
  )
}

/**
 * Why a finished upload was not accepted, as a clause. The rejection reasons are the scanner's and
 * the content inspector's own codes, and a file still waiting for its scan has none at all.
 */
function whyFileWasRefused(artifact: ArtifactResponse): string {
  if (artifact.status !== 'REJECTED') return 'Brownie could not finish checking it'
  switch (artifact.rejectionReason) {
    case 'MALWARE_DETECTED':
      return 'the malware scan flagged it'
    case 'UNSUPPORTED_MEDIA_TYPE':
      return 'Brownie cannot use that kind of file'
    case 'DECOMPRESSION_LIMIT_EXCEEDED':
      return 'it unpacks to far more than Brownie accepts'
    case 'EXPIRED_ABANDONED_UPLOAD':
      return 'the upload took too long to finish'
    default:
      return "it did not pass Brownie's checks"
  }
}

/**
 * Why an upload the server refused was not attached. A size refusal names the limit when this page
 * knows it and the file is over it; otherwise the server's own explanation says what was too large,
 * which for a package can be what it unpacks to rather than the file itself.
 */
function uploadFailureMessage(error: unknown, file: File): string {
  if (error instanceof ApiRequestError && error.status === 413) {
    const limit = uploadLimitBytes.value
    const limitText = limit !== null && file.size > limit ? formatBytes(limit) : null
    if (limitText) {
      return `That file was not attached: it is larger than the ${limitText} upload limit.`
    }
    return error.problem?.detail
      ? `That file was not attached. ${error.problem.detail}`
      : 'That file was not attached: it is larger than Brownie accepts.'
  }
  if (error instanceof ApiRequestError && error.status === 415) {
    return 'That file was not attached: Brownie cannot use that kind of file as a source. Attach a plain-text (.txt) file.'
  }
  const why = describeCommonFailure(error, 'a way to attach sources')
  return why ? `That file was not attached. ${why}` : 'Could not attach that file. Try again.'
}

async function onSourceFileChosen(event: Event): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (workspaceId === undefined || !file) return

  sourceUploadState.value = 'uploading'
  sourceUploadError.value = null
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    await uploadArtifactContent(workspaceId, allocated.id, file)
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      sourceUploadError.value = `That file was not attached: ${whyFileWasRefused(completed)}.`
      sourceUploadState.value = 'error'
      return
    }
    await extractArtifact(workspaceId, allocated.id)
    const attached = await attachDocumentSource(workspaceId, props.documentId, allocated.id)
    sourcesAddedHere.add(attached.id)
    attachedSources.value = [attached, ...attachedSources.value.filter((source) => source.id !== attached.id)]
    selectedSourceId.value = attached.id
    sourceUploadState.value = 'idle'
  } catch (error) {
    sourceUploadError.value = uploadFailureMessage(error, file)
    sourceUploadState.value = 'error'
  }
}

/**
 * Asks the trusted worker to pull typed facts (and action-item rows) out of
 * the selected source. The job may pause to ask about anything missing or
 * conflicting; once it finishes, "Apply to document" turns the result into
 * a proposal and "Accept and update document" is the only step that
 * changes this document's own fields. A failure here happens before any
 * job exists, so trying again is safe; once a job id is known, the poll
 * loop below never starts another.
 */
async function tryGroundedExtraction(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const source = attachedSources.value.find((candidate) => candidate.id === selectedSourceId.value) ?? attachedSources.value[0]
  if (workspaceId === undefined || !source) return

  extractionStage.value = 'starting'
  extractionError.value = null
  // The last run's state must not describe this one, or offer to start it again, before its first status read.
  extractionJobState.value = null
  extractionResultArtifactId.value = null
  extractionStalled.value = false
  cancellationRequested.value = false
  openQuestions.value = []
  applyStage.value = 'idle'
  applyError.value = null
  patchProposal.value = null
  acceptResult.value = null
  try {
    const receipt = await startExtraction(workspaceId, props.documentId, source.artifactId, crypto.randomUUID())
    extractionJobId.value = receipt.jobId
    extractionStage.value = 'running'
  } catch (error) {
    extractionStage.value = 'failed'
    extractionError.value =
      describeCommonFailure(error, 'extraction from sources') ??
      (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'Could not start extraction. Try again.')
    return
  }
  await pollJobUntilTerminal(workspaceId, extractionJobId.value!)
}

/**
 * The sentence for a failed status read that waiting cannot fix, or null for one worth waiting out
 * (a lost connection, a server error, too many requests). Polling on through an ended session or a
 * missing run would say "still checking" for ten minutes about something that can only ever answer
 * the same way.
 */
function pollFailureWaitingCannotFix(error: unknown): string | null {
  if (!(error instanceof ApiRequestError)) return null
  if (brownieSaysNotThere(error)) {
    return 'This run is no longer available, for example because its document was moved to the trash.'
  }
  if (error.status === 401 || error.routeMissing) return describeCommonFailure(error, 'a way to check on a run')
  if (error.status === 403) return 'Brownie no longer lets this account see this run, so this page stopped checking on it.'
  return null
}

/**
 * Follows one job to a resting state. A failed status read is not a failed job: the loop keeps
 * going with a longer gap and tells the person it lost contact, because giving up here would
 * invite a second, paid start. It stops early only on an answer waiting cannot change (see
 * pollFailureWaitingCannotFix), or when the run's questions or result cannot be read once it gets
 * there, which "Check again" picks up. After ten minutes it stops polling and offers "Check again"
 * instead, since the job's real state is on the server whenever the person asks.
 */
async function pollJobUntilTerminal(workspaceId: number, jobId: number): Promise<void> {
  const terminalStates = new Set(['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED'])
  const startedAt = Date.now()
  const deadline = startedAt + 10 * 60 * 1000
  let delayMs = 1500
  noWorkerYet.value = false
  while (Date.now() < deadline) {
    let job
    try {
      job = await getJob(workspaceId, jobId)
      extractionError.value = null
      delayMs = 1500
    } catch (error) {
      if (extractionJobId.value !== jobId) return
      const ending = pollFailureWaitingCannotFix(error)
      if (ending !== null) {
        extractionStage.value = 'failed'
        extractionError.value = ending
        return
      }
      extractionError.value =
        error instanceof ApiRequestError && error.status === 429
          ? 'Brownie asked this page to check less often; still checking on this run.'
          : 'Lost contact with the server; still checking on this run.'
      delayMs = Math.min(delayMs * 2, 15_000)
      await new Promise((resolve) => setTimeout(resolve, delayMs))
      continue
    }
    if (extractionJobId.value !== jobId) return
    extractionJobState.value = job.state
    cancellationRequested.value = cancellationRequested.value || job.cancellationRequestedAt != null
    // A worker claims a queued job within a couple of seconds. Thirty seconds with no attempt means
    // there is no worker to claim it, which the person should be told rather than left watching.
    noWorkerYet.value = job.state === 'QUEUED' && job.attemptCount === 0 && Date.now() - startedAt > 30_000
    if (job.state === 'WAITING_FOR_INPUT') {
      try {
        openQuestions.value = await getGenerationQuestions(workspaceId, props.documentId, jobId)
      } catch (error) {
        stopFollowingUnreadRun('This run is waiting for your answers, but its questions could not be loaded.', error, 'questions for a run')
        return
      }
      extractionStage.value = 'waiting-for-input'
      return
    }
    if (terminalStates.has(job.state)) {
      if (job.state === 'SUCCEEDED') {
        let result
        try {
          result = await getExtractionResult(workspaceId, props.documentId, jobId)
        } catch (error) {
          stopFollowingUnreadRun('This run has finished, but its result could not be loaded.', error, 'run results')
          return
        }
        extractionResultArtifactId.value = result.artifactId
        extractionStage.value = 'succeeded'
      } else if (job.state === 'CANCELLED') {
        extractionStage.value = 'cancelled'
      } else {
        extractionStage.value = 'failed'
        // FAILED or DEAD: the job queue's names for a run that stopped trying, not words for a person.
        extractionError.value = 'This run gave up before it could finish.'
      }
      return
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs))
  }
  extractionStalled.value = true
  stalledMessage.value = 'Still running after ten minutes of checking. The run continues on the server.'
}

/** Re-reads the run from the server; used after polling stopped, and safe at any time. */
async function checkRunAgain(): Promise<void> {
  try {
    await followLatestRun()
  } catch (error) {
    // The run is as it was; only this check failed, so the offer to check stays.
    stalledMessage.value = describeCommonFailure(error, 'a list of runs') ?? 'Brownie could not check on this run just now.'
  }
}

const retryingRun = ref(false)
/** The job the server said can never be started again, so the offer is not repeated for it. */
const runThatCannotBeRetried = ref<number | null>(null)
/**
 * Only a run that gave up can be started again. Starting a new extraction from the same source on
 * the same version of the document would find this same run and report the same ending, so this is
 * the way forward until the document changes.
 */
const canRetryRun = computed(
  () =>
    extractionStage.value === 'failed' &&
    extractionJobId.value !== null &&
    extractionJobId.value !== runThatCannotBeRetried.value &&
    (extractionJobState.value === 'DEAD' || extractionJobState.value === 'FAILED'),
)

async function retryRun(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null) return
  retryingRun.value = true
  extractionError.value = null
  try {
    const job = await retryJob(workspaceId, jobId)
    extractionJobState.value = job.state
    extractionStalled.value = false
    cancellationRequested.value = false
    extractionStage.value = 'running'
  } catch (error) {
    if (error instanceof ApiRequestError && error.problem?.code === 'JOB_TARGET_STALE') {
      runThatCannotBeRetried.value = jobId
      extractionError.value =
        'This document has changed since that run began, so it cannot be started again. Start a new extraction instead.'
    } else if (error instanceof ApiRequestError && !error.routeMissing && (error.status === 409 || error.status === 404)) {
      // The run is not in the state this page last saw (another tab restarted it, or it has since
      // finished), so asking again cannot help; what the server says now is what should be shown.
      runThatCannotBeRetried.value = jobId
      await rehydrateLatestRun()
    } else {
      // Nothing happened to the run. A server without the retry route says nothing about the run
      // itself, so it keeps its ending and the offer stays for once the server has been updated.
      extractionError.value = describeCommonFailure(error, 'a way to start a run again') ?? 'Could not start this run again. Try again.'
    }
    return
  } finally {
    retryingRun.value = false
  }
  await pollJobUntilTerminal(workspaceId, jobId)
}

/**
 * Cooperative: the worker checks before each paid call and the job ends CANCELLED; a model call
 * already in flight may still finish and cost. The button therefore says "requested" until the job
 * really reaches a resting state, which the poll loop reports.
 */
async function cancelExtraction(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null) return
  extractionError.value = null
  try {
    await cancelJob(workspaceId, jobId, crypto.randomUUID())
    cancellationRequested.value = true
    if (extractionStage.value === 'waiting-for-input') {
      // Nothing is polling while a job waits for answers; follow it to CANCELLED ourselves.
      extractionStage.value = 'running'
      await pollJobUntilTerminal(workspaceId, jobId)
    }
  } catch (error) {
    extractionError.value = describeCommonFailure(error, 'a way to cancel a run') ?? 'Could not request cancellation. Try again.'
  }
}

async function submitAnswer(questionId: number): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const answerValue = (answerDrafts.value[questionId] ?? '').trim()
  if (workspaceId === undefined || !answerValue) return

  answeringQuestionId.value = questionId
  extractionError.value = null
  try {
    const answered = await answerQuestion(workspaceId, questionId, answerValue)
    openQuestions.value = openQuestions.value.map((question) => (question.id === answered.id ? answered : question))
  } catch (error) {
    extractionError.value = describeCommonFailure(error, "a way to answer a run's questions") ?? 'Could not save that answer. Try again.'
  } finally {
    answeringQuestionId.value = null
  }
}

async function resumeAfterAnswers(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null) return

  extractionStage.value = 'resuming'
  extractionError.value = null
  try {
    await resumeGeneration(workspaceId, props.documentId, jobId, crypto.randomUUID())
    extractionStage.value = 'running'
    await pollJobUntilTerminal(workspaceId, jobId)
  } catch (error) {
    extractionStage.value = 'waiting-for-input'
    extractionError.value = describeCommonFailure(error, 'a way to continue a run once its questions are answered') ?? 'Could not resume extraction. Try again.'
  }
}

async function applyResultToDocument(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null) return

  applyStage.value = 'applying'
  applyError.value = null
  try {
    patchProposal.value = await applyGenerationResult(workspaceId, props.documentId, jobId)
    applyStage.value = 'proposed'
  } catch (error) {
    applyStage.value = 'failed'
    applyError.value = describeCommonFailure(error, "a way to apply a run's results") ?? 'Could not turn this result into a proposal. Try again.'
  }
}

// ---------------------------------------------------------------------------------------------
// The composer: a typed request is interpreted first (what it would do, to which field or finding)
// and only an explicit second click executes it. A change or a rewrite comes back as a proposal
// that goes through the same accept step as an Assist result; an explanation is text; a draft
// request starts the existing extraction; anything else is answered with what Brownie can do.
type AssistStage = 'idle' | 'interpreting' | 'interpreted' | 'executing' | 'done' | 'failed'
const assistText = ref('')
const assistStage = ref<AssistStage>('idle')
const assistInterpretation = ref<AssistInterpretationResponse | null>(null)
const assistExplanation = ref<string | null>(null)
const assistError = ref<string | null>(null)
const assistBusy = computed(() => assistStage.value === 'interpreting' || assistStage.value === 'executing')

async function interpretAssistRequest(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const text = assistText.value.trim()
  if (workspaceId === undefined || text === '') return
  assistStage.value = 'interpreting'
  assistError.value = null
  assistExplanation.value = null
  assistInterpretation.value = null
  try {
    assistInterpretation.value = await interpretAssist(workspaceId, props.documentId, text)
    assistStage.value = 'interpreted'
  } catch (error) {
    assistStage.value = 'failed'
    assistError.value =
      describeCommonFailure(error, 'the Assist composer') ??
      (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'Assist could not read that request. Try again.')
  }
}

async function runAssistRequest(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const interpretation = assistInterpretation.value
  const text = assistText.value.trim()
  if (workspaceId === undefined || !interpretation?.executable || !document.value) return
  if (interpretation.kind === 'DRAFT') {
    assistStage.value = 'done'
    if (attachedSources.value.length === 0) {
      assistError.value = 'Attach a source on the Sources tab first; then Assist can draft from it.'
      return
    }
    await tryGroundedExtraction()
    return
  }
  assistStage.value = 'executing'
  assistError.value = null
  try {
    const outcome = await executeAssist(workspaceId, props.documentId, text, document.value.currentRevision.id)
    if (outcome.proposal) {
      patchProposal.value = outcome.proposal
      acceptResult.value = null
      applyError.value = null
      applyStage.value = 'proposed'
      announce('Assist proposed a change. Review it, then accept it to update the document.')
    }
    if (outcome.explanation) {
      assistExplanation.value = outcome.explanation
      announce('Assist explained the finding.')
    }
    assistStage.value = 'done'
  } catch (error) {
    assistStage.value = 'failed'
    if (error instanceof ApiRequestError && error.status === 412) {
      // The reload's own message says why, when the current version could not be read.
      assistError.value = (await loadDocument())
        ? 'This document changed since you loaded it, so it was reloaded. Ask again on the current version.'
        : 'This document changed since you loaded it, so that was not done.'
      return
    }
    assistError.value =
      describeCommonFailure(error, 'the Assist composer') ??
      (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'Assist could not do that. Try again.')
  }
}

function clearAssistRequest(): void {
  assistText.value = ''
  assistStage.value = 'idle'
  assistInterpretation.value = null
  assistExplanation.value = null
  assistError.value = null
}

async function acceptProposal(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const proposal = patchProposal.value
  if (workspaceId === undefined || !proposal || !document.value) return

  applyStage.value = 'accepting'
  applyError.value = null
  try {
    acceptResult.value = await acceptPatchProposal(
      workspaceId,
      props.documentId,
      proposal.id,
      document.value.currentRevision.id,
      crypto.randomUUID(),
    )
    applyStage.value = 'accepted'
    await loadDocument()
  } catch (error) {
    applyStage.value = 'proposed'
    applyError.value = describeCommonFailure(error, 'a way to accept proposed changes') ?? 'Could not apply this proposal. Try again.'
  }
}

async function recordFieldReview(fieldId: string, decision: ReviewDecision): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return

  fieldActionPending.value = fieldId
  fieldActionError.value = null
  try {
    await recordReviewDecision(workspaceId, props.documentId, document.value.currentRevision.id, fieldId, decision, crypto.randomUUID())
    await loadDocument()
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not record a review decision for ${fieldId}. Try again.`)
  } finally {
    fieldActionPending.value = null
  }
}

async function toggleFieldLock(fieldId: string, currentLock: FieldLock): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return

  const nextLock: FieldLock = currentLock === 'EXPLICITLY_LOCKED' ? 'EDITABLE' : 'EXPLICITLY_LOCKED'
  fieldActionPending.value = fieldId
  fieldActionError.value = null
  try {
    await setFieldLock(workspaceId, props.documentId, document.value.currentRevision.id, fieldId, nextLock, crypto.randomUUID())
    await loadDocument()
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not change the lock for ${fieldId}. Try again.`)
  } finally {
    fieldActionPending.value = null
  }
}
</script>

<template>
  <!-- The router sends a signed-out visitor to the sign-in page before this view mounts; this is what shows if a session ends while it is open. -->
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to view this document.</p>
    <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: `/documents/${props.documentId}` } }">
      Sign in
    </RouterLink>
  </section>

  <section v-else-if="loadState === 'loading'" aria-live="polite">
    <h1 class="visually-hidden">Loading document</h1>
    <p>Loading document…</p>
  </section>

  <section v-else-if="loadState === 'error'" class="field-error" role="alert">
    <p>{{ loadError ?? 'Could not load this document.' }}</p>
    <p v-if="calendarConsent">{{ calendarConsent.text }}</p>
    <p v-if="documentGone"><RouterLink to="/trash">Open the trash bin</RouterLink></p>
    <RouterLink to="/">Back to your documents</RouterLink>
  </section>

  <section v-else-if="document">
    <div class="workspace-topbar">
      <div>
        <RouterLink to="/" class="field-hint">&larr; Your documents</RouterLink>
        <h1>{{ document.title }}</h1>
      </div>
      <button type="button" class="button" :aria-expanded="previewOpen" aria-controls="pdf-pane" @click="togglePreview">
        {{ previewOpen ? 'Hide preview' : 'Show preview' }}
      </button>
    </div>
    <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ liveMessage }}</p>
    <p v-if="handoffWarning" class="field-error" role="alert">{{ handoffWarning }}</p>
    <p
      v-if="calendarConsent"
      ref="calendarConsentElement"
      :class="calendarConsent.tone === 'success' ? 'workspace-notice' : 'field-error'"
      :role="calendarConsent.tone === 'success' ? 'status' : 'alert'"
      tabindex="-1"
    >
      {{ calendarConsent.text }}
      <template v-if="calendarConsent.tone === 'success'">Copy an event from the Sources tab.</template>
    </p>
    <p v-if="reloadError" class="field-error" role="alert">
      {{ reloadError }}
      <RouterLink v-if="documentGone" to="/trash">Open the trash bin</RouterLink>
    </p>

    <div class="workspace-layout" :class="{ 'workspace-layout--with-preview': previewOpen }">
      <div class="card preview-pane">
        <h2>Content</h2>
        <p v-if="fieldActionError" class="field-error" role="alert">
          {{ fieldActionError }}
          <RouterLink v-if="documentGone && !reloadError" to="/trash">Open the trash bin</RouterLink>
        </p>

        <div v-if="!hasAnyValue && !isDirty" class="empty-state">
          <p class="empty-state__title">Nothing filled in yet.</p>
          <p class="field-hint">
            Type values straight into the fields below, or attach your notes or a transcript and use Assist to
            fill them from there. You review every value before it is exported.
          </p>
          <button type="button" class="button" @click="goToSources">Attach a source</button>
        </div>
        <p v-else class="field-hint">Edit any value below and save. Each save keeps the previous version in History.</p>

        <form v-if="editableFields.length > 0" class="field-list" @submit.prevent="saveEdits('manual')">
          <div v-for="field in scalarFields" :id="`field-${field.fieldId}`" :key="field.fieldId" class="field-row">
            <div class="field-row__value">
              <label class="field-row__label" :for="`edit-${field.fieldId}`">
                {{ labelFor(field.fieldId) }}
                <span v-if="field.requiredness === 'REQUIRED'" class="field-hint">(required)</span>
                <span v-else-if="ruleRequiredFieldIds.has(field.fieldId)" class="field-hint">(required by a rule)</span>
              </label>
              <input
                :id="`edit-${field.fieldId}`"
                :type="field.type === 'DATE' ? 'date' : 'text'"
                class="field-input"
                :value="scalarDraft(field.fieldId)"
                :disabled="isFieldLocked(field.fieldId)"
                :aria-describedby="`field-id-${field.fieldId}`"
                @input="drafts[field.fieldId] = ($event.target as HTMLInputElement).value"
              />
              <span :id="`field-id-${field.fieldId}`" class="visually-hidden">Field {{ field.fieldId }}</span>
              <span v-if="isFieldLocked(field.fieldId)" class="field-hint">Locked: unlock it to edit.</span>
            </div>
            <div v-if="fieldStateOf(field.fieldId)" class="field-row__state">
              <span v-for="chip in stateChips(fieldStateOf(field.fieldId)!)" :key="chip" class="badge">{{ chip }}</span>
              <button
                v-if="evidenceSpanIdsOf(field.fieldId).length > 0"
                class="button"
                type="button"
                :aria-expanded="evidenceOpenFor === field.fieldId"
                :aria-controls="`evidence-${field.fieldId}`"
                :aria-label="`Evidence for ${field.fieldId}`"
                @click="toggleEvidence(field.fieldId)"
              >
                Evidence ({{ evidenceSpanIdsOf(field.fieldId).length }})
              </button>
              <div class="field-row__actions">
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === field.fieldId"
                  :aria-label="`Accept ${field.fieldId}`"
                  @click="recordFieldReview(field.fieldId, 'ACCEPTED')"
                >
                  Accept
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === field.fieldId"
                  :aria-label="`Reject ${field.fieldId}`"
                  @click="recordFieldReview(field.fieldId, 'REJECTED')"
                >
                  Reject
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === field.fieldId"
                  :aria-label="`Mark ${field.fieldId} as needing clarification`"
                  @click="recordFieldReview(field.fieldId, 'NEEDS_CLARIFICATION')"
                >
                  Needs clarification
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === field.fieldId"
                  :aria-label="`${isFieldLocked(field.fieldId) ? 'Unlock' : 'Lock'} ${field.fieldId}`"
                  @click="toggleFieldLock(field.fieldId, fieldStateOf(field.fieldId)!.lock as FieldLock)"
                >
                  {{ isFieldLocked(field.fieldId) ? 'Unlock' : 'Lock' }}
                </button>
              </div>
            </div>
            <div v-if="evidenceOpenFor === field.fieldId" :id="`evidence-${field.fieldId}`" class="evidence-panel">
              <p v-if="evidenceStage === 'loading'" aria-live="polite">Loading the cited excerpt…</p>
              <p v-else-if="evidenceStage === 'failed'" class="field-error" role="alert">{{ evidenceError }}</p>
              <template v-else>
                <blockquote v-for="excerpt in evidenceExcerpts" :key="excerpt.spanId" class="evidence-excerpt">
                  <p>{{ excerpt.excerptText }}</p>
                  <footer class="field-hint">From {{ excerpt.displayFilename ?? `source #${excerpt.sourceSnapshotId}` }}</footer>
                </blockquote>
                <p v-if="evidenceSpanIdsOf(field.fieldId).length > MAX_EVIDENCE_EXCERPTS" class="field-hint">
                  Showing the first {{ MAX_EVIDENCE_EXCERPTS }} of {{ evidenceSpanIdsOf(field.fieldId).length }} cited passages.
                </p>
                <p class="field-hint">Brownie can show where a value came from; it cannot point to where a value lands on the preview page.</p>
              </template>
            </div>
          </div>

          <fieldset v-if="repeatedFields.length > 0" class="row-group">
            <legend class="field-row__label">Rows</legend>
            <p v-if="rowsLocked" class="field-hint">A row is locked, so the rows cannot be edited until it is unlocked.</p>
            <div v-if="rowCount > 0" class="row-table-wrap">
            <table class="row-table">
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th v-for="field in repeatedFields" :key="field.fieldId" scope="col">{{ labelFor(field.fieldId) }}</th>
                  <th scope="col">Row actions</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="index in rowIndexes" :key="index">
                  <th scope="row">{{ index + 1 }}</th>
                  <td v-for="field in repeatedFields" :key="field.fieldId">
                    <label class="visually-hidden" :for="`edit-${field.fieldId}-${index}`">
                      {{ labelFor(field.fieldId) }}, row {{ index + 1 }}
                    </label>
                    <input
                      :id="`edit-${field.fieldId}-${index}`"
                      :type="field.type === 'DATE' ? 'date' : 'text'"
                      class="field-input"
                      :value="rowDraft(field.fieldId)[index] ?? ''"
                      :disabled="rowsLocked"
                      @input="rowDraft(field.fieldId)[index] = ($event.target as HTMLInputElement).value"
                    />
                  </td>
                  <td>
                    <div class="field-row__actions">
                      <span v-if="rowState(index)" class="badge">{{ stateLabel('review', rowState(index)!.review) }}</span>
                      <span v-if="rowState(index) && rowState(index)!.lock !== 'EDITABLE'" class="badge">
                        {{ stateLabel('lock', rowState(index)!.lock) }}
                      </span>
                      <button
                        class="button"
                        type="button"
                        :disabled="rowsLocked"
                        :aria-label="`Remove row ${index + 1}`"
                        @click="removeRow(index)"
                      >
                        Remove
                      </button>
                      <button
                        class="button"
                        type="button"
                        :disabled="rowsLocked || index === 0"
                        :aria-label="`Move row ${index + 1} up`"
                        @click="moveRow(index, -1)"
                      >
                        Up
                      </button>
                      <button
                        class="button"
                        type="button"
                        :disabled="rowsLocked || index === rowCount - 1"
                        :aria-label="`Move row ${index + 1} down`"
                        @click="moveRow(index, 1)"
                      >
                        Down
                      </button>
                      <template v-if="rowState(index)">
                        <button
                          class="button"
                          type="button"
                          :disabled="fieldActionPending === `row-${index}`"
                          :aria-label="`Accept row ${index + 1}`"
                          @click="recordRowReview(index, 'ACCEPTED')"
                        >
                          Accept
                        </button>
                        <button
                          class="button"
                          type="button"
                          :disabled="fieldActionPending === `row-${index}`"
                          :aria-label="`Reject row ${index + 1}`"
                          @click="recordRowReview(index, 'REJECTED')"
                        >
                          Reject
                        </button>
                        <button
                          class="button"
                          type="button"
                          :disabled="fieldActionPending === `row-${index}`"
                          :aria-label="`${rowState(index)!.lock === 'EXPLICITLY_LOCKED' ? 'Unlock' : 'Lock'} row ${index + 1}`"
                          @click="toggleRowLock(index)"
                        >
                          {{ rowState(index)!.lock === 'EXPLICITLY_LOCKED' ? 'Unlock' : 'Lock' }}
                        </button>
                      </template>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
            </div>
            <p v-else class="field-hint">No rows yet.</p>
            <button type="button" class="button" :disabled="rowsLocked" @click="addRow">Add row</button>
            <div v-if="repeatedFieldsWithEvidence.length > 0" class="evidence-links">
              <button
                v-for="field in repeatedFieldsWithEvidence"
                :key="field.fieldId"
                class="button"
                type="button"
                :aria-expanded="evidenceOpenFor === field.fieldId"
                :aria-controls="`evidence-${field.fieldId}`"
                @click="toggleEvidence(field.fieldId)"
              >
                Evidence for {{ labelFor(field.fieldId) }} ({{ evidenceSpanIdsOf(field.fieldId).length }})
              </button>
            </div>
            <div
              v-for="field in repeatedFieldsWithEvidence"
              v-show="evidenceOpenFor === field.fieldId"
              :id="`evidence-${field.fieldId}`"
              :key="`panel-${field.fieldId}`"
              class="evidence-panel"
            >
              <p v-if="evidenceStage === 'loading'" aria-live="polite">Loading the cited excerpt…</p>
              <p v-else-if="evidenceStage === 'failed'" class="field-error" role="alert">{{ evidenceError }}</p>
              <template v-else-if="evidenceOpenFor === field.fieldId">
                <blockquote v-for="excerpt in evidenceExcerpts" :key="excerpt.spanId" class="evidence-excerpt">
                  <p>{{ excerpt.excerptText }}</p>
                  <footer class="field-hint">From {{ excerpt.displayFilename ?? `source #${excerpt.sourceSnapshotId}` }}</footer>
                </blockquote>
                <p class="field-hint">Brownie can show where a value came from; it cannot point to where a value lands on the preview page.</p>
              </template>
            </div>
          </fieldset>

          <div class="save-bar">
            <div class="field-row__value">
              <label class="field-label" for="edit-note">Note for history (optional)</label>
              <input id="edit-note" v-model="editNote" type="text" class="field-input" />
            </div>
            <div class="field-row__actions">
              <button
                type="submit"
                class="button button--primary"
                :disabled="!isDirty || saveStage === 'saving' || rowProblems.length > 0"
              >
                {{ saveStage === 'saving' ? 'Saving…' : 'Save now' }}
              </button>
              <button type="button" class="button" :disabled="!isDirty || saveStage === 'saving'" @click="resetDrafts">
                Discard changes
              </button>
            </div>
            <p v-if="saveStage === 'saved' && !isDirty">Saved.</p>
            <p v-if="isDirty && saveStage !== 'saving' && saveStage !== 'conflict'" class="field-hint">
              Unsaved changes. Brownie saves a moment after you stop typing.
            </p>
            <div v-if="rowProblems.length > 0" class="field-error" role="alert">
              <ul>
                <li v-for="problem in rowProblems" :key="problem">{{ problem }}</li>
              </ul>
            </div>
            <p v-if="saveError" class="field-error" role="alert">
              {{ saveError }}
              <RouterLink v-if="documentGone && !reloadError" to="/trash">Open the trash bin</RouterLink>
            </p>
            <div v-if="saveStage === 'conflict'" class="field-error conflict-notice" role="alert">
              <!-- The revision on screen is only the current one when the reload after the conflict worked. -->
              <p v-if="reloadError">
                This document changed since you started editing, and its latest version could not be loaded, so
                nothing was saved. Your edits are still in the fields above.
              </p>
              <p v-else>
                This document changed since you started editing (revision
                {{ document.currentRevision.revisionNumber }} is now current). Your edits are still in the fields
                above. Save them onto the latest version, or discard them to see what changed.
              </p>
              <div class="field-row__actions">
                <button type="button" class="button button--primary" @click="saveEdits('manual')">Save my edits onto the latest</button>
                <button type="button" class="button" @click="resetDrafts">Discard my edits</button>
              </div>
            </div>
          </div>
        </form>
      </div>

      <div v-if="previewOpen" id="pdf-pane" class="card pdf-pane">
        <h2>Preview</h2>
        <p v-if="previewStage === 'ready' && previewRevisionNumber != null" class="field-hint">
          Preview of version {{ previewRevisionNumber }}.
          <span v-if="previewIsStale">The document has changed since it was made.</span>
          <span v-else-if="isDirty">You have unsaved edits; save, then regenerate to see them.</span>
        </p>
        <p v-if="previewStage === 'idle'" class="field-hint">No preview yet. Generate one to see this document as it will export.</p>
        <p v-if="previewStage === 'loading'" aria-live="polite">Checking for a preview…</p>
        <p v-if="previewStage === 'generating'" aria-live="polite">Generating the preview…</p>
        <p v-if="previewError" class="field-error" role="alert">{{ previewError }}</p>
        <div class="pdf-pane__actions">
          <button
            class="button button--primary"
            type="button"
            :disabled="previewStage === 'generating' || previewStage === 'loading'"
            @click="regeneratePreview"
          >
            {{ previewStage === 'generating' ? 'Generating…' : previewArtifactId == null ? 'Generate preview' : 'Regenerate preview' }}
          </button>
          <a v-if="previewUrl" class="button" :href="previewUrl" target="_blank" rel="noopener">Open the PDF in a new tab</a>
        </div>
        <PdfPreview :src="previewUrl" :label="`Preview of version ${previewRevisionNumber ?? ''}`" />
      </div>

      <div class="card inspector-pane">
        <button
          ref="drawerToggleRef"
          type="button"
          class="button drawer-toggle"
          :aria-expanded="drawerOpen"
          aria-controls="inspector-drawer"
          @click="toggleDrawer"
        >
          {{ drawerOpen ? 'Hide details' : 'Show details' }}
        </button>

        <div
          id="inspector-drawer"
          ref="drawerRef"
          class="inspector-drawer"
          :class="{ 'inspector-drawer--collapsed': !drawerOpen }"
          role="region"
          aria-labelledby="inspector-drawer-heading"
          @keydown="onDrawerKeydown"
        >
          <h2 id="inspector-drawer-heading" ref="drawerHeadingRef" class="visually-hidden" tabindex="-1">
            Document details
          </h2>

          <div class="tab-strip" role="tablist" aria-label="Document inspector">
            <button
              v-for="(tab, index) in INSPECTOR_TABS"
              :key="tab"
              ref="tabButtonEls"
              type="button"
              role="tab"
              :aria-selected="activeTab === tab"
              :tabindex="activeTab === tab ? 0 : -1"
              class="tab-button"
              :class="{ 'tab-button--active': activeTab === tab }"
              @click="selectTab(tab)"
              @keydown="onTabKeydown($event, index)"
            >
              {{
                tab === 'assist'
                  ? 'Assist'
                  : tab === 'rules'
                    ? 'Rules'
                    : tab === 'sources'
                      ? 'Sources'
                      : tab === 'checks'
                        ? 'Checks'
                        : 'History'
              }}
            </button>
          </div>

          <div role="tabpanel">
            <div v-if="activeTab === 'sources'">
              <label class="field-label" for="attach-source">Attach a source</label>
              <input
                id="attach-source"
                type="file"
                accept=".txt,text/plain"
                :disabled="sourceUploadState === 'uploading'"
                @change="onSourceFileChosen"
              />
              <p class="field-hint">
                Plain-text notes or a transcript (.txt)<span v-if="uploadLimit">, up to {{ uploadLimit }}</span>. Assist reads
                plain-text sources.
              </p>
              <p v-if="sourceUploadState === 'uploading'" aria-live="polite">Uploading…</p>
              <p v-if="sourceUploadError" class="field-error" role="alert">{{ sourceUploadError }}</p>
              <CalendarSourcePicker
                v-if="session.personalWorkspaceId !== undefined"
                :workspace-id="session.personalWorkspaceId"
                :document-id="documentId"
                :start-open="calendarPickerStartsOpen"
                :unsaved-work="hasUnsavedWork()"
                :copy-event="copyCalendarEvent"
                @used="calendarConsent = null"
              />

              <ul v-if="attachedSources.length > 0" class="source-list">
                <li v-for="source in attachedSources" :key="source.id">
                  {{ source.displayFilename ?? `Source #${source.id}` }}
                  <span class="field-hint">(attached {{ new Date(source.attachedAt).toLocaleString() }})</span>
                  <span v-if="originSentence(source)" class="field-hint source-list__origin">
                    {{ originSentence(source) }}
                    <a v-if="originLink(source)" :href="originLink(source) ?? undefined" target="_blank" rel="noopener noreferrer"
                      >Open in Google Calendar<span class="visually-hidden"> (opens in a new tab)</span></a
                    >
                  </span>
                </li>
              </ul>
              <p v-else class="field-hint">No sources attached to this document yet.</p>
            </div>

            <div v-else-if="activeTab === 'assist'">
              <form class="assist-composer" @submit.prevent="interpretAssistRequest">
                <label class="field-label" for="assist-composer">Ask Assist</label>
                <textarea
                  id="assist-composer"
                  v-model="assistText"
                  class="field-input assist-composer__input"
                  rows="2"
                  placeholder="e.g. change meeting title to Spring Planning"
                  :disabled="assistBusy"
                ></textarea>
                <p class="field-hint">
                  Brownie does a few bounded things: draft from your sources, change a field, shorten or rewrite a
                  text field, explain a finding. It shows what it would touch before it does anything.
                </p>
                <div class="field-row__actions">
                  <button type="submit" class="button" :disabled="assistText.trim() === '' || assistBusy">
                    {{ assistStage === 'interpreting' ? 'Reading…' : 'Interpret' }}
                  </button>
                  <button v-if="assistStage !== 'idle'" type="button" class="button" :disabled="assistBusy" @click="clearAssistRequest">
                    Clear
                  </button>
                </div>
                <p v-if="assistError" class="field-error" role="alert">{{ assistError }}</p>
                <div v-if="assistInterpretation" class="assist-plan" role="group" aria-labelledby="assist-plan-heading">
                  <p id="assist-plan-heading" class="field-label">What Assist would do</p>
                  <p>{{ assistInterpretation.summary }}</p>
                  <dl v-if="assistInterpretation.scope">
                    <template v-if="assistInterpretation.scope.fieldId">
                      <dt>Field</dt>
                      <dd>{{ assistInterpretation.scope.label }} ({{ assistInterpretation.scope.fieldId }})</dd>
                    </template>
                    <template v-if="assistInterpretation.scope.findingMessage">
                      <dt>Finding</dt>
                      <dd>{{ assistInterpretation.scope.findingMessage }}</dd>
                    </template>
                    <template v-else-if="assistInterpretation.scope.fieldId">
                      <dt>Now</dt>
                      <dd>{{ assistInterpretation.scope.currentValue ?? '(empty)' }}</dd>
                    </template>
                  </dl>
                  <ul v-if="assistInterpretation.help.length > 0" class="assist-help">
                    <li v-for="item in assistInterpretation.help" :key="item">{{ item }}</li>
                  </ul>
                  <p v-if="assistInterpretation.executable && assistInterpretation.usesModel" class="field-hint">
                    This makes one model call. Nothing changes on the document until you accept the result.
                  </p>
                  <div v-if="assistInterpretation.executable" class="field-row__actions">
                    <button type="button" class="button button--primary" :disabled="assistBusy" @click="runAssistRequest">
                      {{ assistStage === 'executing' ? 'Working…' : 'Do it' }}
                    </button>
                  </div>
                </div>
                <blockquote v-if="assistExplanation" class="assist-explanation">
                  <p>{{ assistExplanation }}</p>
                </blockquote>
              </form>

              <p class="field-hint">
                Pulls this template's fields (and any repeated rows) out of an attached source. Brownie asks you
                about anything missing or conflicting, then proposes the values. Nothing changes on this
                document until you accept them.
              </p>
              <p v-if="attachedSources.length === 0" class="field-hint">Attach a source first, on the Sources tab.</p>
              <template v-else>
                <template v-if="attachedSources.length > 1">
                  <label class="field-label" for="extract-source">Source to read</label>
                  <select id="extract-source" v-model.number="selectedSourceId" :disabled="extractionStage === 'starting' || extractionStage === 'running'">
                    <option v-for="source in attachedSources" :key="source.id" :value="source.id">
                      {{ source.displayFilename ?? `Source #${source.id}` }}
                    </option>
                  </select>
                </template>
                <button
                  class="button button--primary"
                  type="button"
                  :disabled="
                    extractionStage === 'starting' ||
                    extractionStage === 'running' ||
                    extractionStage === 'waiting-for-input' ||
                    extractionStage === 'resuming' ||
                    extractionStage === 'unconfirmed'
                  "
                  @click="tryGroundedExtraction"
                >
                  {{ extractionStage === 'starting' || extractionStage === 'running' ? 'Extracting…' : 'Try grounded extraction' }}
                </button>
                <button
                  v-if="extractionStage === 'running' || extractionStage === 'waiting-for-input'"
                  class="button"
                  type="button"
                  :disabled="cancellationRequested"
                  @click="cancelExtraction"
                >
                  {{ cancellationRequested ? 'Cancellation requested…' : 'Cancel' }}
                </button>
                <p v-if="extractionStage === 'running' && runProgress" aria-live="polite">{{ runProgress }}</p>
                <p v-if="extractionStage === 'running' && noWorkerYet" class="field-error" role="status">
                  No worker has picked this run up yet. If the Brownie worker is not running, the run waits until it is;
                  you can cancel it and try again later.
                </p>
                <p v-if="extractionStage === 'cancelled'" aria-live="polite">This run was cancelled. Nothing was applied.</p>
                <div v-if="extractionStalled" class="field-hint" role="status">
                  <p>{{ stalledMessage }}</p>
                  <button class="button" type="button" @click="checkRunAgain">Check again</button>
                </div>
                <p v-if="extractionError" class="field-error" role="alert">{{ extractionError }}</p>
                <div v-if="canRetryRun" class="field-row__actions">
                  <button class="button" type="button" :disabled="retryingRun" @click="retryRun">
                    {{ retryingRun ? 'Starting again…' : 'Try this run again' }}
                  </button>
                </div>

                <div v-if="extractionStage === 'waiting-for-input' || extractionStage === 'resuming'" class="question-list" aria-live="polite">
                  <p class="field-hint">A few things need your input before this can finish.</p>
                  <div v-for="question in openQuestions" :key="question.id" class="question-item">
                    <p class="field-label">
                      {{ labelFor(question.fieldId) }}
                      <span class="field-hint">({{ question.reason === 'CONFLICT' ? 'conflicts with the current value' : 'missing' }})</span>
                    </p>
                    <template v-if="question.status === 'ANSWERED'">
                      <p>Answered: {{ question.answerValue }}</p>
                    </template>
                    <template v-else>
                      <ul v-if="question.candidates.length > 0" class="source-list">
                        <li v-for="(candidate, index) in question.candidates" :key="index">
                          <button type="button" class="button" @click="answerDrafts[question.id] = candidate.value">
                            {{ candidate.value }}
                          </button>
                        </li>
                      </ul>
                      <label class="field-label" :for="`answer-${question.id}`">Your answer</label>
                      <input
                        :id="`answer-${question.id}`"
                        type="text"
                        v-model="answerDrafts[question.id]"
                        :disabled="answeringQuestionId === question.id"
                      />
                      <button
                        class="button"
                        type="button"
                        :disabled="answeringQuestionId === question.id || !(answerDrafts[question.id] ?? '').trim()"
                        @click="submitAnswer(question.id)"
                      >
                        Save answer
                      </button>
                    </template>
                  </div>
                  <button
                    class="button button--primary"
                    type="button"
                    :disabled="extractionStage === 'resuming' || !openQuestions.every((q) => q.status === 'ANSWERED')"
                    @click="resumeAfterAnswers"
                  >
                    {{ extractionStage === 'resuming' ? 'Resuming…' : 'Continue' }}
                  </button>
                </div>

                <div v-if="extractionStage === 'succeeded' && extractionResultArtifactId !== null">
                  <p>
                    Done —
                    <a :href="`/api/v1/workspaces/${session.personalWorkspaceId}/uploads/${extractionResultArtifactId}/download`">
                      download the raw result
                    </a>.
                  </p>
                  <button
                    v-if="applyStage === 'idle' || applyStage === 'failed'"
                    class="button button--primary"
                    type="button"
                    @click="applyResultToDocument"
                  >
                    Apply to document
                  </button>
                  <p v-if="applyStage === 'applying'" aria-live="polite">Preparing proposal…</p>
                  <!-- Only a failed apply is said here; a failed accept is said beside the proposal, which may have come from the composer instead. -->
                  <p v-if="applyError && applyStage === 'failed'" class="field-error" role="alert">{{ applyError }}</p>

                </div>
              </template>

              <div v-if="patchProposal && applyStage !== 'idle' && applyStage !== 'failed'" class="question-item">
                <p class="field-label">Proposed changes</p>
                <dl>
                  <template v-for="(field, fieldId) in patchProposal.proposedValues" :key="fieldId">
                    <dt>{{ labelFor(fieldId) }}</dt>
                    <dd>
                      <ol v-if="field.cardinality === 'REPEATED'" class="proposed-items">
                        <li v-for="(item, index) in field.values ?? []" :key="index">{{ item }}</li>
                      </ol>
                      <template v-else>{{ field.value }}</template>
                    </dd>
                  </template>
                </dl>
                <p v-if="patchProposal.proposedRepeatedItemCount > 0" class="field-hint">
                  {{ patchProposal.proposedRepeatedItemCount }} action item{{ patchProposal.proposedRepeatedItemCount === 1 ? '' : 's' }}
                  proposed, listed above in matching order.
                </p>
                <div v-if="patchProposal.skippedRepeatedItems.length > 0" class="field-error" role="status">
                  <p>
                    {{ patchProposal.skippedRepeatedItems.length }} action item{{ patchProposal.skippedRepeatedItems.length === 1 ? ' was' : 's were' }}
                    found in your source but could not be proposed, because a required detail could not be determined.
                    Fill {{ patchProposal.skippedRepeatedItems.length === 1 ? 'it' : 'them' }} in yourself before exporting:
                  </p>
                  <ul>
                    <li v-for="skipped in patchProposal.skippedRepeatedItems" :key="skipped.itemIndex">
                      {{ skipped.description ?? `Item ${skipped.itemIndex + 1}` }}
                      <span class="field-hint">(missing: {{ skipped.unresolvedFieldIds.join(', ') }})</span>
                    </li>
                  </ul>
                </div>
                <button
                  v-if="applyStage !== 'accepted'"
                  class="button button--primary"
                  type="button"
                  :disabled="applyStage === 'accepting'"
                  @click="acceptProposal"
                >
                  {{ applyStage === 'accepting' ? 'Applying…' : 'Accept and update document' }}
                </button>
                <p v-if="applyError" class="field-error" role="alert">{{ applyError }}</p>
                <p v-if="applyStage === 'accepted'" aria-live="polite">Applied to the document.</p>
                <p
                  v-if="acceptResult && Object.values(acceptResult.fieldStatuses).some((s) => s !== 'CLEAN')"
                  class="field-hint"
                  aria-live="polite"
                >
                  Some fields could not be applied automatically (conflicting or locked).
                </p>
              </div>
            </div>

            <div v-else-if="activeTab === 'checks'">
              <button
                class="button button--primary"
                type="button"
                :disabled="validationStage === 'validating'"
                @click="validateCurrentRevision"
              >
                {{ validationStage === 'validating' ? 'Validating…' : 'Validate this revision' }}
              </button>
              <p v-if="validationStage === 'validating'" aria-live="polite">Validating…</p>
              <p v-if="validationError" class="field-error" role="alert">{{ validationError }}</p>

              <template v-if="validationManifest">
                <p v-if="validationManifest.hasUnresolvedBlocking" class="field-error" role="alert">
                  Cannot export: unresolved blocking findings
                </p>
                <p v-else aria-live="polite">Ready to export</p>

                <ul v-if="validationManifest.findings.length > 0" class="finding-list">
                  <li v-for="(finding, index) in validationManifest.findings" :key="index" class="finding-row">
                    <span class="badge">{{ finding.severity }}</span>
                    <span v-if="finding.fieldId" class="field-row__label">{{ labelFor(finding.fieldId) }}</span>
                    <span>{{ finding.message }}</span>
                    <button
                      v-if="finding.fieldId && editableFieldIds.has(finding.fieldId)"
                      type="button"
                      class="button"
                      :aria-label="`Go to ${finding.fieldId}`"
                      @click="focusField(finding.fieldId)"
                    >
                      Go to field
                    </button>
                  </li>
                </ul>
                <p v-else class="field-hint">No findings.</p>

                <template v-if="!validationManifest.hasUnresolvedBlocking">
                  <label class="field-label" for="export-format">Export format</label>
                  <select id="export-format" v-model="exportFormat">
                    <option value="DOCX">DOCX</option>
                    <option value="PDF">PDF</option>
                    <option value="BOTH">Both</option>
                  </select>
                  <button
                    class="button button--primary"
                    type="button"
                    :disabled="approvalStage === 'approving'"
                    @click="approveCurrentExport"
                  >
                    {{ approvalStage === 'approving' ? 'Approving…' : 'Approve for export' }}
                  </button>
                  <p v-if="approvalError" class="field-error" role="alert">{{ approvalError }}</p>
                </template>

                <div v-if="exportApproval">
                  <p class="field-hint">Approved for: {{ FORMAT_NAMES[exportApproval.format] }}</p>
                  <button
                    class="button button--primary"
                    type="button"
                    :disabled="exportStage === 'exporting'"
                    @click="exportApprovedDocument"
                  >
                    {{ exportStage === 'exporting' ? 'Exporting…' : 'Export' }}
                  </button>
                  <p v-if="exportError" class="field-error" role="alert">{{ exportError }}</p>
                </div>

                <div v-if="exportReceipt">
                  <p aria-live="polite">{{ exportOutcomeMessage }}</p>
                  <ul class="source-list">
                    <li v-if="exportReceipt.format !== 'PDF' || exportReceipt.pdfArtifactId == null">
                      <a :href="artifactDownloadUrl(session.personalWorkspaceId!, exportReceipt.docxArtifactId)">Download DOCX</a>
                    </li>
                    <li v-if="exportReceipt.pdfArtifactId != null">
                      <a :href="artifactDownloadUrl(session.personalWorkspaceId!, exportReceipt.pdfArtifactId)">Download PDF</a>
                    </li>
                  </ul>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === 'history'">
              <p v-if="revisionsLoadState === 'loading'" aria-live="polite">Loading revision history…</p>
              <p v-if="revisionsError" class="field-error" role="alert">{{ revisionsError }}</p>

              <ul v-if="orderedRevisions.length > 0" class="revision-list">
                <li v-for="revision in orderedRevisions" :key="revision.id" class="revision-row">
                  <div class="revision-row__body">
                    <span class="field-row__label">Revision {{ revision.revisionNumber }}</span>
                    <span class="field-hint">{{ revision.editReason }}</span>
                    <span class="field-hint">{{ new Date(revision.createdAt).toLocaleString() }}</span>
                  </div>
                  <button
                    class="button"
                    type="button"
                    :disabled="compareStage === 'loading' && compareRevisionId === revision.id"
                    :aria-label="`Compare revision ${revision.revisionNumber} to current`"
                    @click="compareToRevision(revision.id)"
                  >
                    Compare to current
                  </button>
                </li>
              </ul>
              <p v-else-if="revisionsLoadState === 'loaded'" class="field-hint">No earlier revisions yet.</p>

              <div v-if="compareStage !== 'idle'" class="compare-panel">
                <p v-if="compareStage === 'loading'" aria-live="polite">Loading revision {{ compareRevisionId }}…</p>
                <p v-if="compareError" class="field-error" role="alert">{{ compareError }}</p>

                <template v-if="compareStage === 'loaded' && compareRevision && document">
                  <h3>Revision {{ compareRevision.revisionNumber }} vs. current</h3>
                  <div class="compare-row">
                    <span></span>
                    <div class="compare-row__values">
                      <span class="field-hint">Revision {{ compareRevision.revisionNumber }}</span>
                      <span class="field-hint">Current (revision {{ document.currentRevision.revisionNumber }})</span>
                    </div>
                  </div>
                  <div
                    v-for="fieldId in compareFieldIds"
                    :key="fieldId"
                    class="compare-row"
                    :class="{ 'compare-row--changed': fieldChanged(fieldId) }"
                  >
                    <span class="field-row__label">{{ labelFor(fieldId) }}</span>
                    <div class="compare-row__values">
                      <span>{{ fieldDisplayValue(compareRevision.fields[fieldId]) }}</span>
                      <span>{{ fieldDisplayValue(document.currentRevision.fields[fieldId]) }}</span>
                    </div>
                  </div>
                </template>
              </div>
            </div>

            <div v-else-if="activeTab === 'rules'">
              <p class="field-hint">
                The rules this document's template version enforces when it is validated and exported. Rules are
                taught on the template, so they are read-only here.
              </p>
              <p v-if="rulesLoadState === 'loading'" aria-live="polite">Loading rules…</p>
              <p v-else-if="rulesLoadState === 'error'" class="field-error" role="alert">Could not load the rules. Try again.</p>
              <template v-else-if="rulesLoadState === 'loaded'">
                <p v-if="rulesInForce.length === 0" class="field-hint">
                  No accepted rules on this template version beyond its required fields.
                </p>
                <ul v-else class="rule-list">
                  <li v-for="rule in rulesInForce" :key="rule.id" class="rule-row">
                    <span class="badge">{{ rule.category }}</span>
                    <span class="rule-row__scope">{{ describeScope(rule.scope) }}</span>
                    <span>{{ describePayload(rule.payload) }}</span>
                    <p v-if="rule.humanExplanation" class="field-hint">{{ rule.humanExplanation }}</p>
                  </li>
                </ul>
                <p v-if="rulesUndecided > 0" class="field-hint">
                  {{ rulesUndecided }} proposed {{ rulesUndecided === 1 ? 'rule is' : 'rules are' }} waiting for a decision on the template.
                </p>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.workspace-topbar {
  margin-bottom: var(--space-5);
}

.workspace-topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.workspace-layout {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--space-5);
}

/* A grid item is never wider than its column: its own content scrolls or wraps instead of painting over the next pane. */
.workspace-layout > * {
  min-width: 0;
}

/*
 * Three columns only where all three keep a usable width; below that the
 * preview takes a full row under the editor.
 *
 * The question asked is how wide this page's own region is, not how wide
 * the window is: the sidebar beside it can be showing or collapsed, and
 * those two answers differ by its whole width. 68rem is the three
 * columns' own minimums (22 + 20 + 22) plus the two gaps between them,
 * so the threshold is the layout's real requirement rather than a guess
 * at a screen size.
 */
@container main (min-width: 68rem) {
  .workspace-layout--with-preview {
    grid-template-columns: minmax(22rem, 3fr) minmax(20rem, 3fr) minmax(22rem, 2fr);
  }
}

@container main (max-width: 67.999rem) {
  .workspace-layout--with-preview .pdf-pane {
    grid-column: 1 / -1;
  }
}

.pdf-pane__actions {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-bottom: var(--space-3);
}

.evidence-panel {
  grid-column: 1 / -1;
  margin-top: var(--space-2);
  padding: var(--space-3);
  border-left: 3px solid var(--color-honey);
  background: var(--color-honey-soft);
  border-radius: var(--radius);
}

.evidence-excerpt {
  margin: 0 0 var(--space-2);
}

.evidence-excerpt p {
  margin: 0 0 var(--space-1);
}

.evidence-links {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-3);
}

.assist-composer {
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.assist-composer__input {
  width: 100%;
  min-height: 3.5rem;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  resize: vertical;
}

.assist-plan {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border-left: 3px solid var(--color-honey);
  background: var(--color-honey-soft);
  border-radius: var(--radius);
}

.assist-help {
  margin: var(--space-2) 0;
  padding-left: var(--space-4);
}

.assist-explanation {
  margin: var(--space-3) 0 0;
  padding: var(--space-3);
  border-left: 3px solid var(--color-border);
  background: var(--color-surface);
}

/* Hidden above the 720px breakpoint -- the inspector pane is a normal, always-visible column there. */
.drawer-toggle {
  display: none;
}

@media (max-width: 720px) {
  .workspace-layout {
    grid-template-columns: 1fr;
  }

  .compare-row {
    grid-template-columns: 1fr;
  }

  .compare-row__values {
    grid-template-columns: 1fr;
  }

  .drawer-toggle {
    display: inline-flex;
    margin-bottom: var(--space-4);
  }

  .inspector-drawer--collapsed {
    display: none;
  }
}

dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-2) var(--space-4);
}

.proposed-items {
  margin: 0;
  padding-left: var(--space-4);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
}

.empty-state__title {
  margin: 0;
  font-weight: 600;
}

dt {
  font-weight: 600;
  color: var(--color-text-secondary);
}

.tab-strip {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: var(--space-4);
}

.tab-button {
  background: none;
  border: none;
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.tab-button--active {
  color: var(--color-text);
  border-bottom-color: var(--color-honey);
}

.source-list {
  padding-left: var(--space-5);
}

.source-list__origin {
  display: block;
}

.workspace-notice {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

.field-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.field-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.field-row__value {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.field-row__label {
  font-weight: 600;
  color: var(--color-text-secondary);
}

.field-row__state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.field-row__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.field-input {
  width: 100%;
  max-width: 32rem;
  box-sizing: border-box;
}

.row-group {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-width: 0;
}

/* The rows table scrolls sideways inside its own box when the columns need more room than the pane has.
   The box is also the positioning context for the visually hidden row labels inside it, which are
   absolutely positioned and would otherwise escape the box and widen the whole page. */
.row-table-wrap {
  position: relative;
  width: 100%;
  overflow-x: auto;
}

.row-table {
  width: 100%;
  border-collapse: collapse;
}

.row-table th,
.row-table td {
  text-align: left;
  vertical-align: top;
  padding: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.row-table .field-input {
  min-width: 10rem;
}

.save-bar {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
}

.conflict-notice {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.badge {
  display: inline-block;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius);
  background: var(--color-honey-soft);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.question-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.question-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.finding-list {
  list-style: none;
  margin: var(--space-3) 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.finding-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.revision-list {
  list-style: none;
  margin: var(--space-3) 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.revision-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.revision-row__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.compare-panel {
  margin-top: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.compare-row {
  display: grid;
  grid-template-columns: 1fr 2fr;
  align-items: center;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
}

.compare-row__values {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-2);
}

.compare-row--changed {
  background: var(--color-honey-soft);
  border-radius: var(--radius);
}

.compare-row--changed .compare-row__values span {
  font-weight: 600;
}
</style>
