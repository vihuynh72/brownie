<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { forgetFormNotes, readDocumentHandoff } from '@/router/handoff'
// PDF.js is the largest thing this page can load; it is fetched only once the print preview is shown.
const PdfPreview = defineAsyncComponent(() => import('@/components/PdfPreview.vue'))
// A PDF form's page view draws its pages with PDF.js too, so it is loaded only for a PDF form.
const PdfFormPage = defineAsyncComponent(() => import('@/components/workspace/PdfFormPage.vue'))
import {
  ApiRequestError,
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  artifactPreviewUrl,
  attachDocumentSource,
  cancelJob,
  changeFillSpots,
  compileRevision,
  completeUpload,
  executeAssist,
  extractArtifact,
  getDocument,
  getEvidenceExcerpt,
  getExtractionResult,
  getGenerationQuestions,
  getJob,
  getLatestCompilation,
  getLatestValidation,
  getTemplateLayout,
  getTemplateVersion,
  importCalendarEvent,
  importDriveFile,
  interpretAssist,
  listDocumentRevisions,
  listDocumentSources,
  listGenerationRuns,
  listTemplateVersionRules,
  patchDocumentContent,
  recordReviewDecision,
  restoreRevision,
  resumeGeneration,
  retryJob,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
  type ArtifactResponse,
  type AssistPageAnchor,
  type CalendarImportResponse,
  type DocumentResponse,
  type DocumentRevisionResponse,
  type DocumentSourceResponse,
  type DriveImportResponse,
  type EvidenceExcerptResponse,
  type FieldDefinitionResponse,
  type FieldEditRequest,
  type FieldLock,
  type FieldStateResponse,
  type PatchAcceptResponse,
  type PatchProposalResponse,
  type PdfPageAnchor,
  type PreparationNoticeResponse,
  type QuestionResponse,
  type ReviewDecision,
  type RuleResponse,
  type TemplateLayoutResponse,
} from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { describePayload } from '@/rules/describeRule'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { CONSENT_QUERY_KEYS, consentOutcome, originLink, originLinkLabel, originSentence } from '@/connections/words'
import {
  describeStyle,
  dominantFillSpotStyle,
  fieldLabel,
  fieldStateWords,
  fillSpotStyle,
  formatDateLikeExport,
  labelFor,
  type EditableField,
} from '@/workspace/layout'
import { useFoundSpots } from '@/workspace/foundSpots'
import { formNoteSentences } from '@/upload/fillableCopyWords'
import { anchorableLines, lineWords, placeOnLine, toDocxAnchor, type PagePoint } from '@/workspace/anchors'
import { useFillSpotChanges, type SpotUndo } from '@/workspace/fillSpotChanges'
import { addedWords, removedWords, renamedWords, spotRefusal, undoneWords } from '@/workspace/fillSpotWords'
import { spotChangeFailure, type PdfSpotRequest, type PdfSpotResult } from '@/workspace/pdfPage'
import AppIcon from '@/components/AppIcon.vue'
import CalendarSourcePicker from '@/components/CalendarSourcePicker.vue'
import DriveSourcePicker from '@/components/DriveSourcePicker.vue'
import ChoiceList from '@/components/workspace/ChoiceList.vue'
import DocumentPage from '@/components/workspace/DocumentPage.vue'
import ExportDialog from '@/components/workspace/ExportDialog.vue'
import PlaceSpotDialog, { type PlaceSpotRefusal, type PlaceSpotRequest } from '@/components/workspace/PlaceSpotDialog.vue'
import RemoveSpotDialog from '@/components/workspace/RemoveSpotDialog.vue'
import RenameSpotDialog from '@/components/workspace/RenameSpotDialog.vue'
import VersionHistoryDialog from '@/components/workspace/VersionHistoryDialog.vue'

const props = defineProps<{ documentId: number }>()

const session = useSessionStore()
const document = ref<DocumentResponse | null>(null)
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')

// One persistent polite live region for the page: assistive technology announces changes to an
// element that was already in the tree, which a message rendered by v-if at the moment it matters
// is not. Everything worth hearing (saving, saved, conflict, what Brownie did) goes through it.
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

// ---- Where things sit -------------------------------------------------------------------------
//
// On a wide page the document and Brownie's panel sit side by side. On a narrow one there is room
// for one of them at a time, so a two-button switch above them chooses which, and nothing is ever
// squeezed into a column too thin to read. Both stay mounted either way: switching never loses a
// half-typed message or a draft value.
type NarrowView = 'document' | 'assistant'
const narrowView = ref<NarrowView>('document')
const assistantHeadingRef = ref<HTMLElement | null>(null)

async function showAssistant(focusHeading = true): Promise<void> {
  narrowView.value = 'assistant'
  if (!focusHeading) return
  await nextTick()
  assistantHeadingRef.value?.focus()
}

/** The document's page as it is edited, or the rendered file as it will export. */
const docView = ref<'page' | 'print'>('page')

// ---- Rules -----------------------------------------------------------------------------------
//
// The rules that apply to this document are the accepted ones on its own template version;
// proposed and rejected ones are counted, not listed, since deciding them is the template's
// business, not the document's.
const rules = ref<RuleResponse[]>([])
const rulesLoadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const rulesInForce = computed(() =>
  rules.value.filter((rule) => rule.templateVersionId === document.value?.templateVersionId && rule.status === 'ACCEPTED'),
)
const rulesUndecided = computed(() =>
  rules.value.filter((rule) => rule.templateVersionId === document.value?.templateVersionId && rule.status === 'PROPOSED').length,
)

// A short window makes the Rules card scroll on its own. While more of it is below, a fade and a "More" hint at
// its foot say so; the card takes focus, so the arrow keys scroll it, and the hint scrolls it for a pointer.
const rulesCardRef = ref<HTMLElement | null>(null)
const rulesCardBodyRef = ref<HTMLElement | null>(null)
const rulesCardMore = ref(false)
function measureRulesCard(): void {
  const card = rulesCardRef.value
  rulesCardMore.value = card !== null && card.scrollHeight - card.scrollTop - card.clientHeight > 4
}
function scrollRulesCard(): void {
  const card = rulesCardRef.value
  if (!card || typeof card.scrollBy !== 'function') return
  const still = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  card.scrollBy({ top: card.clientHeight * 0.8, behavior: still ? 'auto' : 'smooth' })
}
let rulesCardObserver: ResizeObserver | null = null
watch(
  rulesCardRef,
  (card) => {
    rulesCardObserver?.disconnect()
    rulesCardObserver = null
    measureRulesCard()
    if (!card || typeof ResizeObserver === 'undefined') return
    // The card changes size with the window; what is in it changes with the rules, the notes and the spot chosen.
    rulesCardObserver = new ResizeObserver(() => measureRulesCard())
    rulesCardObserver.observe(card)
    if (rulesCardBodyRef.value) rulesCardObserver.observe(rulesCardBodyRef.value)
  },
  { flush: 'post' },
)
onBeforeUnmount(() => rulesCardObserver?.disconnect())

/** Field ids an accepted rule requires a value for, so the page can say so before a check does. */
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

// ---- The template's page ---------------------------------------------------------------------
//
// The template's own text, with a place for each value, so the person edits the document as it
// reads rather than a list of boxes. When the page cannot be drawn (an older server, a template
// Brownie cannot lay out) every value is still reachable: the page lists its fill spots instead.
const layout = ref<TemplateLayoutResponse | null>(null)
const layoutState = ref<'loading' | 'ready' | 'unavailable'>('loading')
const layoutProblem = ref<string | null>(null)
const layoutLoadedForVersionId = ref<number | null>(null)
/** The layout being read for a new version, so a change to the fill spots can wait for the page it makes. */
let layoutRequest: Promise<void> | null = null

/**
 * Only a drawn layout, or the server's own answer that this template cannot be drawn, is final for
 * the version; anything else (an older server, a lost connection, a busy server) is tried again the
 * next time the document loads, and the page says what actually went wrong rather than blaming the
 * template.
 */
async function loadLayout(workspaceId: number, loaded: DocumentResponse): Promise<void> {
  if (!layout.value) layoutState.value = 'loading'
  try {
    layout.value = await getTemplateLayout(workspaceId, loaded.templateId, loaded.templateVersionId)
    layoutState.value = 'ready'
    layoutProblem.value = null
    layoutLoadedForVersionId.value = loaded.templateVersionId
  } catch (error) {
    if (layout.value && layoutLoadedForVersionId.value === loaded.templateVersionId) return
    layout.value = null
    layoutState.value = 'unavailable'
    const final = error instanceof ApiRequestError && error.status === 422
    layoutProblem.value = final ? null : describeCommonFailure(error, 'a way to draw a template as a page')
    if (final) layoutLoadedForVersionId.value = loaded.templateVersionId
  }
}

// ---- Sources ---------------------------------------------------------------------------------
//
// Whatever the screen that created this document handed over when it navigated here (see
// router/handoff.ts): a source attached during creation, shown at once while the server's own list
// loads, and a warning about the creation (a source that could not be attached, fill spots a learned
// form had to leave out) -- shown here, on the screen the person actually lands on, rather than lost
// with the creating screen's own state. The server's document-source list is the truth; the handoff
// only bridges the first paint.
const handoff = readDocumentHandoff()
const attachedSources = ref<DocumentSourceResponse[]>(
  (handoff?.attachedSources ?? []).map((source) => ({ ...source, attachedAt: source.fetchedAt })),
)
const handoffWarning = ref<string | null>(handoff?.sourceWarning ?? null)

// What Brownie found in an uploaded form and changed in its copy of it: news for the person on arrival, not a
// warning. It shares one short line over the page with the places Brownie found, folded behind "Details" so
// the page itself, and its first marked place, stay in view; the Rules card says it again later.
const formNotes = ref<string[]>(handoff?.formNotes ?? [])
const formNotesOpen = ref(false)
const stripButtonsRef = ref<HTMLElement | null>(null)

// A region that appears already holding its notes is not read out, and one that is live would read out its own
// buttons as they change; so the page says once, through its live line, that the notes are there. The live line
// itself first appears with the document, and a live line that appears already holding words is not read out
// either; so the words wait a moment, until screen readers have seen the line empty.
const NOTES_ANNOUNCEMENT_DELAY_MS = 500
let formNotesAnnounced = false
let formNotesTimer: ReturnType<typeof setTimeout> | undefined
watch(
  loadState,
  (state) => {
    if (state !== 'loaded' || formNotesAnnounced) return
    formNotesAnnounced = true
    const count = formNotes.value.length
    if (count === 0) return
    formNotesTimer = setTimeout(
      () => announce(`About this document: ${count === 1 ? '1 note' : `${count} notes`} above the page.`),
      NOTES_ANNOUNCEMENT_DELAY_MS,
    )
  },
  { flush: 'post' },
)
onBeforeUnmount(() => clearTimeout(formNotesTimer))

function dismissFormNotes(): void {
  formNotes.value = []
  forgetFormNotes()
  // The region and the button in it are gone; the document itself is what comes next.
  void nextTick(() => window.document.getElementById('document-pane')?.focus({ preventScroll: true }))
}
const sourceUploadState = ref<'idle' | 'uploading' | 'error'>('idle')
const sourceUploadError = ref<string | null>(null)
/** Which attached source Brownie reads; defaults to the most recently attached one. */
const selectedSourceId = ref<number | null>(null)
/** Sources attached on this page, by upload or by copying: kept when a list read before them arrives after them. */
const sourcesAddedHere = new Set<number>()

const selectedSource = computed(
  () => attachedSources.value.find((candidate) => candidate.id === selectedSourceId.value) ?? attachedSources.value[0] ?? null,
)

function sourceName(source: DocumentSourceResponse | null | undefined): string {
  if (!source) return 'your source'
  return source.displayFilename ?? source.origin?.title ?? 'your source'
}

/** What kind of source it is; where it was copied from, and when, is the card's next line. */
function sourceKindWords(source: DocumentSourceResponse): string {
  if (source.origin?.conversion === 'CALENDAR_EVENT_AS_TEXT') return 'Calendar event'
  if (source.origin?.conversion === 'GOOGLE_DOC_AS_TEXT') return 'Google Doc'
  return 'Text file'
}

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

/** The panel under the chat where a source is added: a file, or a copy from a connected Google account. */
const sourcesOpen = ref(false)

// Google sends the person back here after they connect Google Calendar, choose files in Google
// Drive, or connect a way to save or add events, with what happened in the address. It is read
// once and taken out of the address, so a reload or a shared link does not say it again, and it is
// said at the top of the page, which every screen width shows.
const route = useRoute()
const router = useRouter()
const calendarConsent = ref(readCalendarConsent())
const consentAccess = calendarConsent.value?.access ?? null
/** The control Google's answer was for opens by itself, on this first visit only. */
const calendarPickerStartsOpen = ref(
  calendarConsent.value !== null &&
    consentAccess !== 'DRIVE_FILES' &&
    consentAccess !== 'DRIVE_SAVING' &&
    consentAccess !== 'CALENDAR_EVENT_CREATION',
)
const drivePickerStartsOpen = ref(consentAccess === 'DRIVE_FILES')
/** Connecting a way to save or to add an event was done from Export, so Export opens again once the document is here. */
const exportOpensOnArrival = calendarConsent.value?.tone === 'success' && (consentAccess === 'DRIVE_SAVING' || consentAccess === 'CALENDAR_EVENT_CREATION')
if (calendarPickerStartsOpen.value || drivePickerStartsOpen.value) {
  sourcesOpen.value = true
  narrowView.value = 'assistant'
}
const calendarConsentElement = ref<HTMLElement | null>(null)
watch(calendarConsentElement, (element) => element?.focus())
function readCalendarConsent(): { tone: 'success' | 'failure'; text: string; access: string | null; hint: string | null } | null {
  const outcome = consentOutcome(route.query)
  if (outcome === null) return null
  const rest = { ...route.query }
  for (const key of CONSENT_QUERY_KEYS) delete rest[key]
  void router.replace({ query: rest })
  // What to do next, said only when there is something to copy.
  const hint =
    outcome.tone !== 'success'
      ? null
      : outcome.access === 'DRIVE_FILES'
        ? (outcome.added ?? 0) > 0
          ? 'Choose a file to copy into this document in Brownie\'s panel.'
          : null
        : outcome.access === 'DRIVE_SAVING'
          ? 'Save the exported document from Export.'
          : outcome.access === 'CALENDAR_EVENT_CREATION'
            ? 'Add the event from Export.'
            : 'Choose an event to copy into this document in Brownie\'s panel.'
  return { tone: outcome.tone, text: outcome.text, access: outcome.access, hint }
}

/**
 * Copies one calendar event into this document. Done here rather than in the picker: a copy that
 * finishes after the person has closed the panel still joins the document's sources, where
 * Brownie looks for them.
 */
async function copyCalendarEvent(eventId: string): Promise<CalendarImportResponse> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) throw new Error('Brownie is still confirming who is signed in.')
  const selectedBefore = selectedSourceId.value
  const result = await importCalendarEvent(workspaceId, props.documentId, eventId)
  sourcesAddedHere.add(result.source.id)
  attachedSources.value = [result.source, ...attachedSources.value.filter((attached) => attached.id !== result.source.id)]
  // Chosen for Brownie only if the person has not chosen another source meanwhile, and no run is reading one.
  if (!runUnderWay.value && selectedSourceId.value === selectedBefore) selectedSourceId.value = result.source.id
  return result
}

/** Copies one picked Drive file into this document, done here for the same reason as a calendar event. */
async function copyDriveFile(grantId: number): Promise<DriveImportResponse> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) throw new Error('Brownie is still confirming who is signed in.')
  const selectedBefore = selectedSourceId.value
  const result = await importDriveFile(workspaceId, props.documentId, grantId)
  sourcesAddedHere.add(result.source.id)
  attachedSources.value = [result.source, ...attachedSources.value.filter((attached) => attached.id !== result.source.id)]
  if (!runUnderWay.value && selectedSourceId.value === selectedBefore) selectedSourceId.value = result.source.id
  return result
}

// ---- Runs: Brownie reading a source ------------------------------------------------------------

/**
 * 'unconfirmed' is a run that moved on to a state whose details this page could not read (its
 * questions, or its result). Nothing is running here any more, so neither progress nor Stop is
 * shown; and nothing new is started until "Check again" has read the run back, since a second
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
/** The name of the source the run on screen reads, for Brownie's sentences about it. */
const runSourceName = ref<string | null>(null)
/**
 * The run this visit started or continued. Its result is turned into a proposal as soon as it is
 * ready, because the person is waiting for it; a run found finished on arrival waits for a click,
 * since this page cannot tell whether its values were already looked at.
 */
const runFollowedHere = ref<number | null>(null)
const runUnderWay = computed(() => ['starting', 'running', 'waiting-for-input', 'resuming'].includes(extractionStage.value))
/** What a run under way is doing, in words rather than the job queue's state names; null while there is nothing to say. */
const runProgress = computed(() => {
  switch (extractionJobState.value) {
    case 'QUEUED':
      return 'Waiting for a worker to pick this up.'
    case 'LEASED':
      return `Reading ${runSourceName.value ?? 'your source'} and filling in the fields.`
    case 'CANCEL_REQUESTED':
      return 'Stopping.'
    default:
      return null
  }
})
/** Set while a run has sat QUEUED with no claim attempt for longer than a worker would take to notice it. */
const noWorkerYet = ref(false)
const openQuestions = ref<QuestionResponse[]>([])
const answeringQuestionId = ref<number | null>(null)

/**
 * A reload, a second tab, or a network drop must never lose a paid run: the latest run for this
 * document is read back from the server and the chat resumes from whatever state its job is really
 * in -- still running (poll), waiting for answers (show them), finished (offer its values), or
 * ended (say so). Nothing here starts a job.
 */
async function rehydrateLatestRun(): Promise<void> {
  try {
    await followLatestRun()
  } catch {
    // The chat starts as it would before any run; the runs themselves are safe on the server.
  }
}

/** What rehydrateLatestRun does, but throwing when the runs could not be listed, so "Check again" can say so. */
async function followLatestRun(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  const runs = (await listGenerationRuns(workspaceId, props.documentId)) ?? []
  const latest = runs[0]
  if (!latest) return
  if (runSlotId.value === null || extractionJobId.value !== latest.jobId) placeInChat('run')
  extractionJobId.value = latest.jobId
  extractionJobState.value = latest.job.state
  cancellationRequested.value = latest.job.cancellationRequestedAt != null
  extractionStalled.value = false
  runSourceName.value = sourceName(attachedSources.value.find((source) => source.artifactId === latest.sourceArtifactId))
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
        // An empty question list would offer to continue with nothing answered.
        stopFollowingUnreadRun('This reading is waiting for your answers, but its questions could not be loaded.', error, 'questions for a run')
        break
      }
      extractionStage.value = 'waiting-for-input'
      break
    case 'SUCCEEDED':
      extractionResultArtifactId.value = latest.resultArtifactId ?? null
      extractionStage.value = latest.resultArtifactId != null ? 'succeeded' : 'failed'
      if (latest.resultArtifactId == null) extractionError.value = 'The last reading finished without a readable result.'
      break
    case 'CANCELLED':
      extractionStage.value = 'cancelled'
      break
    default:
      extractionStage.value = 'failed'
      // DEAD and FAILED are the job queue's names for a run that stopped trying, not words for a person.
      extractionError.value =
        latest.job.state === 'DEAD' || latest.job.state === 'FAILED'
          ? 'The last reading gave up before it could finish.'
          : 'The last reading ended without a result.'
  }
}

/**
 * Stops following a run whose next step could not be read. The run is fine on the server; this
 * page just does not know where it is, so it offers "Check again" rather than showing a run still
 * under way, with a Stop for it, or inviting a second, paid start.
 */
function stopFollowingUnreadRun(what: string, error: unknown, feature: string): void {
  extractionStage.value = 'unconfirmed'
  extractionStalled.value = true
  const why = describeCommonFailure(error, feature)
  stalledMessage.value = why ? `${what} ${why}` : what
}

// ---- Proposals: what Brownie would change ------------------------------------------------------

type ApplyStage = 'idle' | 'applying' | 'proposed' | 'accepting' | 'accepted' | 'failed'
const applyStage = ref<ApplyStage>('idle')
const applyError = ref<string | null>(null)
const patchProposal = ref<PatchProposalResponse | null>(null)
const acceptResult = ref<PatchAcceptResponse | null>(null)
/** The first line Brownie says about a proposal: where its values came from, or the change asked for. */
const proposalIntro = ref('')
/** The reading whose values the proposal on screen holds; null for a proposal from a request. */
const proposalFromJobId = ref<number | null>(null)
/**
 * A reading whose values the person already approved or set aside on this visit. Its values are
 * still offered, since the person may want another look, but as a second look: approving them
 * again would put them over anything changed since.
 */
const decidedRunJobId = ref<number | null>(null)

const fieldActionError = ref<string | null>(null)
const fieldActionPending = ref<string | null>(null)

/** Why the first load failed, for the message that stands in for the page; null otherwise. */
const loadError = ref<string | null>(null)
/**
 * Why a reload after the first load failed. The page stays exactly as it was -- the document last loaded, the
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
  // Only the first load shows the loading state, and only its failure replaces the page. A reload
  // after a save, a review decision or an accepted proposal keeps the page mounted: unmounting it
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
    // The layout is asked for alongside the definitions rather than after them; the page does not wait for it.
    if (layoutLoadedForVersionId.value !== loaded.templateVersionId) layoutRequest = loadLayout(workspaceId, loaded)
    if (definitionsLoadedForVersionId.value !== loaded.templateVersionId) {
      await loadFieldDefinitions(workspaceId, loaded)
      void loadRules()
    }
    // A reload triggered by some other action (a review decision, an accepted proposal, a check)
    // must never wipe values the person is still typing; only a clean page follows the server. A
    // save marks the page clean itself before it reloads.
    if (!isDirty.value) resetDrafts()
    if (!sidePanelsHydrated) {
      sidePanelsHydrated = true
      await loadDocumentSources()
      await rehydrateLatestRun()
      if (exportOpensOnArrival) {
        await nextTick()
        exportDialogRef.value?.open()
      }
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
watch(
  () => session.status,
  (status) => {
    if (status === 'authenticated') void loadDocument()
  },
)

// ---- Editing by hand -------------------------------------------------------------------------
//
// The page is driven by the template version's own field definitions, so every field the
// template defines gets a fill spot even before it holds a value. Drafts live apart from the loaded
// document: what the person types is theirs until it is saved, and a save is one typed PATCH
// against the exact revision they were looking at -- the server refuses a stale revision (412) or
// a locked field (409) rather than letting either side's work silently vanish.

const fieldDefinitions = ref<FieldDefinitionResponse[]>([])
/**
 * What the server noticed in making the uploaded file ready to fill, kept with the template version the
 * document is on; null where the version was made before it was kept, or could not be read.
 */
const preparationNotices = ref<readonly PreparationNoticeResponse[] | null>(null)
/** The first date the document holds, offered as the day of an event added to the calendar from it. */
const suggestedEventDate = computed(() => {
  const fields = document.value?.currentRevision.fields ?? {}
  for (const definition of fieldDefinitions.value) {
    const value = definition.type === 'DATE' && definition.cardinality === 'SCALAR' ? fields[definition.fieldId]?.value : null
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  }
  return null
})
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
    preparationNotices.value = Array.isArray(version?.preparationNotices) ? version.preparationNotices : null
    foundSpots.setAccepted(loaded.templateId, version?.acceptedFieldIds)
  } catch {
    fieldDefinitions.value = []
    preparationNotices.value = null
    foundSpots.setAccepted(loaded.templateId, [])
  }
  definitionsLoadedForVersionId.value = loaded.templateVersionId
}

const editableFields = computed<EditableField[]>(() => {
  if (fieldDefinitions.value.length > 0) {
    return fieldDefinitions.value.map((definition) => ({
      fieldId: definition.fieldId,
      type: definition.type,
      cardinality: definition.cardinality,
      requiredness: definition.requiredness,
      label: definition.label ?? null,
      origin: definition.origin ?? null,
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

/**
 * The name each field goes by everywhere on this page -- announcements, the chat, Export's "Go to" list,
 * the Rules card: the form's own label when its definition has one, else the one worked out from the id.
 */
const fieldLabelsById = computed(() => new Map(editableFields.value.map((field) => [field.fieldId, fieldLabel(field)])))
function labelOf(fieldId: string): string {
  return fieldLabelsById.value.get(fieldId) ?? labelFor(fieldId)
}

/** The places Brownie found itself: marked on the page until the person keeps them. */
const foundSpots = useFoundSpots({
  fields: editableFields,
  workspaceId: () => session.personalWorkspaceId,
  templateId: () => document.value?.templateId ?? null,
  labelOf,
  announce,
  kind: () => (isPdfForm.value ? 'PDF' : 'DOCX'),
})
const { uncheckedFieldIds: uncheckedFoundIds, uncheckedSet: uncheckedFoundSet, keepPending, keepError, bannerText: foundBannerText } = foundSpots

/** "Keep": the place stays as it is and loses its mark; focus stays in the bar, whose button has gone. */
async function keepSelectedPlace(): Promise<void> {
  const current = selected.value
  if (!current || !(await foundSpots.keep(current.fieldId))) return
  await nextTick()
  selectionBarRef.value?.focus({ preventScroll: true })
}

/**
 * "Keep all": its button goes. Where the line stays for the notes about the form, focus moves to the line's
 * next button; otherwise the line goes too, and focus moves on to the document.
 */
async function keepAllFoundPlaces(): Promise<void> {
  if (!(await foundSpots.keepAll())) return
  await nextTick()
  const next = stripButtonsRef.value?.querySelector<HTMLElement>('button')
  if (next) next.focus()
  else window.document.getElementById('document-pane')?.focus({ preventScroll: true })
}

/** The line over the page: the places Brownie found while any is unchecked; else the note, or how many notes, about the form. */
const stripText = computed(() => {
  if (uncheckedFoundIds.value.length > 0) return foundBannerText.value
  const count = formNotes.value.length
  if (count === 1) return formNotes.value[0]!
  return count > 1 ? `About this document: ${count} notes.` : ''
})
/** "Details" opens the notes under the line, unless the line already is the one note. */
const stripOffersDetails = computed(() => formNotes.value.length > 1 || (formNotes.value.length === 1 && uncheckedFoundIds.value.length > 0))

/**
 * The Rules card's "About this form": the notes about the upload the form came from, kept with its template
 * version, for whenever the document is opened. How many places Brownie found is said by the line over the
 * page while any is unchecked; once all are kept, none is said to be marked.
 */
const aboutThisForm = computed(() => {
  const notices = preparationNotices.value
  if (!notices || notices.length === 0) return []
  const unchecked = uncheckedFoundSet.value
  return formNoteSentences(
    {
      kind: isPdfForm.value ? 'PDF' : 'DOCX',
      notices,
      spots: fieldDefinitions.value.map((definition) => ({
        origin: unchecked.has(definition.fieldId) ? 'FOUND_BY_BROWNIE' : 'FORM',
        binding: { kind: definition.bindingKind },
      })),
    },
    { foundShownOnPage: unchecked.size > 0 },
  )
})
const aboutThisFormOpen = ref(false)
/**
 * The notes are shown in one place at a time: while "About this form" is open, the line over the page folds
 * its own "Details" away rather than saying the same things a second time beside it.
 */
const stripDetailsShown = computed(() => stripOffersDetails.value && !aboutThisFormOpen.value)
watch(aboutThisFormOpen, (open) => {
  if (open) formNotesOpen.value = false
})

// ---- Changing the form's fill spots ------------------------------------------------------------------
//
// A place chosen on the page (a selection, a click, the menu on its text) or a line and a place chosen
// from the list becomes a new fill spot; the bar about a spot renames or removes it. Each change makes a
// new version of the form, which new documents start from too, and the document moves to it with its
// values: whatever was typed is saved first, and the page is loaded again with the new version's layout.
// The chat says what was done, with Undo beside it.

/** The lines of the form's body a new fill spot can go in. */
const anchorLines = computed(() => (layout.value && layout.value.kind !== 'PDF' ? anchorableLines(layout.value) : []))
const placeDialogRef = ref<InstanceType<typeof PlaceSpotDialog> | null>(null)
const renameDialogRef = ref<InstanceType<typeof RenameSpotDialog> | null>(null)
const removeDialogRef = ref<InstanceType<typeof RemoveSpotDialog> | null>(null)
const moveButtonRef = ref<HTMLElement | null>(null)
/** The spot the rename or remove dialog is about. */
let spotChangeTarget: string | null = null
/** The spot just added, which takes focus once the dialog has closed. */
let addedSpotToFocus: string | null = null

/** Saves what the person typed before a change to the form, the way Undo does; null when nothing is left unsaved. */
async function saveTypingFirst(): Promise<string | null> {
  cancelAutosave()
  await settleSaving()
  if (!isDirty.value) return null
  if (saveStage.value === 'conflict' || rowProblems.value.length > 0) {
    return 'What you typed on this page cannot be saved as it is, so nothing else was changed. Save it or discard it first.'
  }
  await saveEdits('manual')
  return hasUnsavedWork() ? 'What you typed on this page could not be saved, so nothing else was changed.' : null
}

/** Loads the document again and waits for the layout of the version it is on, so the page shows what changed. */
async function reloadWithLayout(): Promise<boolean> {
  const loaded = await loadDocument()
  await layoutRequest
  return loaded
}

const spotChanges = useFillSpotChanges({
  workspaceId: () => session.personalWorkspaceId,
  documentId: () => props.documentId,
  document,
  definitions: fieldDefinitions,
  labelOf,
  saveTypingFirst,
  reload: reloadWithLayout,
})
const { behindLatest, newerVersionWords, moving: movingToLatest, moveError } = spotChanges

/** A document on an older version of its form is moved to the newest first; the page says so and offers the move. */
function spotChangesWait(): boolean {
  if (!behindLatest.value) return false
  fieldActionError.value = 'This document is on an older version of its form. Move it to the newest version first, then change its fill spots.'
  // The offer may have been set aside with "Not now"; the move is needed now, so it comes back.
  spotChanges.offerNewerVersionAgain()
  void nextTick(() => moveButtonRef.value?.focus())
  return true
}

/** "Not now": the banner goes with the button that had focus, so focus moves on to the document. */
function setNewerVersionAside(): void {
  spotChanges.setNewerVersionAside()
  void nextTick(() => window.document.getElementById('document-pane')?.focus({ preventScroll: true }))
}

function openPlaceDialog(): void {
  if (spotChangesWait()) return
  fieldActionError.value = null
  placeDialogRef.value?.openAtLines()
}

/** A place chosen on the page: the dialog opens at naming it. */
function onFillHere(point: PagePoint, returnTo: HTMLElement | null): void {
  if (spotChangesWait()) return
  const line = anchorLines.value.find((candidate) => candidate.nodeId === point.nodeId && candidate.anchorTextHash === point.anchorTextHash)
  const place = line ? placeOnLine(line, point) : null
  if (!line || !place) {
    fieldActionError.value = 'The page changed; select the place again.'
    return
  }
  fieldActionError.value = null
  placeDialogRef.value?.openAtPlace(line, place, returnTo)
}

async function sendNewSpot(request: PlaceSpotRequest): Promise<PlaceSpotRefusal | null> {
  const parserVersion = layout.value?.parserVersion
  if (!parserVersion) return { message: 'The page changed; select the place again.', choosePlaceAgain: true }
  const anchor = toDocxAnchor(request.line, request.place, parserVersion)
  const outcome = await spotChanges.change([{ kind: 'ADD', label: request.label, type: request.type, anchor }], 'add')
  if ('refusal' in outcome) return { message: outcome.refusal.message, choosePlaceAgain: outcome.refusal.reload }
  const { response } = outcome
  const fieldId = response.fieldIds[0] ?? ''
  const definition = response.templateVersion.fields.find((field) => field.fieldId === fieldId)
  const label = definition ? fieldLabel(definition) : request.label
  addedSpotToFocus = fieldId
  say({
    from: 'brownie',
    text: addedWords(label, request.place.where, response.otherDocumentsOnPreviousVersion),
    undo: { action: 'add', label, previousRevisionId: response.previousRevisionId, revisionId: response.revision.id },
  })
  return null
}

async function onSpotAdded(): Promise<void> {
  const fieldId = addedSpotToFocus
  addedSpotToFocus = null
  if (fieldId) await focusField(fieldId)
}

/**
 * Whether the bar offers changes to the selected spot itself. On a PDF form, only for a spot with a place
 * on the pages (its page view asks about it); on a Word form, for any spot once its definitions are read,
 * from a server that can change fill spots: only such a server says which version of the form is newest
 * (null when it does not know), so an older one's documents offer no change that could only be refused.
 */
const spotActionsShown = computed(() => {
  const current = selected.value
  if (!current) return false
  if (isPdfForm.value) return selectedPdfSpot.value !== null && current.rowIndex === null
  return fieldDefinitions.value.length > 0 && selectedField.value !== null && document.value?.templateLatestVersionId !== undefined
})

/** The row about the fill spot itself: a place to keep, or changes that can be made to it. */
const spotRowShown = computed(() => selected.value !== null && (uncheckedFoundSet.value.has(selected.value.fieldId) || spotActionsShown.value))
/** The row about the value in it: its review and lock once it has a state, and where it came from. */
const valueRowShown = computed(
  () => selected.value !== null && (!!selectedState.value || selectedRowHasState.value || evidenceSpanIdsOf(selected.value.fieldId).length > 0),
)

function openRename(event: MouseEvent): void {
  const current = selected.value
  if (!current || spotChangesWait()) return
  if (isPdfForm.value) {
    pdfPageRef.value?.openRename(current.fieldId)
    return
  }
  spotChangeTarget = current.fieldId
  renameDialogRef.value?.open(labelOf(current.fieldId), event.currentTarget as HTMLElement)
}

async function sendRename(label: string): Promise<string | null> {
  const fieldId = spotChangeTarget
  if (!fieldId) return null
  const before = labelOf(fieldId)
  const outcome = await spotChanges.change([{ kind: 'RENAME', fieldId, label }], 'rename')
  if ('refusal' in outcome) return outcome.refusal.message
  const { response } = outcome
  const after = labelOf(fieldId)
  say({
    from: 'brownie',
    text: renamedWords(before, after, response.otherDocumentsOnPreviousVersion),
    undo: { action: 'rename', label: after, previousRevisionId: response.previousRevisionId, revisionId: response.revision.id },
  })
  await foundSpots.keepRenamed(fieldId)
  return null
}

/** How the text fits in a box Brownie drew on a PDF page; the PDF's own fields keep the form's look. */
function openRestyle(): void {
  const current = selected.value
  if (!current || spotChangesWait()) return
  pdfPageRef.value?.openRestyle(current.fieldId)
}

function openRemove(event: MouseEvent): void {
  const current = selected.value
  if (!current || spotChangesWait()) return
  if (isPdfForm.value) {
    pdfPageRef.value?.openRemove(current.fieldId)
    return
  }
  spotChangeTarget = current.fieldId
  const definition = fieldDefinitions.value.find((field) => field.fieldId === current.fieldId)
  const hasValue = scalarDraft(current.fieldId).trim() !== '' || document.value?.currentRevision.fields[current.fieldId] !== undefined
  const fromTheForm = (definition?.origin ?? 'FORM') === 'FORM'
  removeDialogRef.value?.open(labelOf(current.fieldId), hasValue, fromTheForm, event.currentTarget as HTMLElement)
}

async function sendRemove(): Promise<string | null> {
  const fieldId = spotChangeTarget
  if (!fieldId) return null
  const label = labelOf(fieldId)
  const outcome = await spotChanges.change([{ kind: 'REMOVE', fieldId }], 'remove')
  if ('refusal' in outcome) return outcome.refusal.message
  const { response } = outcome
  say({
    from: 'brownie',
    text: removedWords(label, response.otherDocumentsOnPreviousVersion),
    undo: { action: 'remove', label, previousRevisionId: response.previousRevisionId, revisionId: response.revision.id },
  })
  return null
}

/** The spot and the bar about it are gone; focus goes to the document they were in. */
function onSpotRemoved(): void {
  window.document.getElementById('document-pane')?.focus({ preventScroll: true })
}

const undoingSpotChange = ref(false)

/** Undo beside a change in the chat: back to the version before it, form and values. */
async function undoSpotChange(line: ChatLine): Promise<void> {
  const change = line.undo
  if (!change || line.undone || undoingSpotChange.value) return
  const since = document.value !== null && (document.value.currentRevision.id !== change.revisionId || isDirty.value)
  if (since && !window.confirm('This document changed after that. Undo takes those changes back too; they stay in the version history. Undo anyway?')) return
  undoingSpotChange.value = true
  try {
    const problem = await spotChanges.undo(change)
    if (problem) {
      say({ from: 'brownie', tone: 'error', text: problem })
      return
    }
    // The toolbar's Undo goes on from here, further back, rather than bringing back the change just undone.
    if (document.value) undoLandedAt = { undoKey: undoKey(document.value.currentRevision), restoredRevisionId: change.previousRevisionId }
    line.undone = true
    say({ from: 'brownie', text: undoneWords(change.action, change.label) })
  } finally {
    undoingSpotChange.value = false
  }
}

const UNDO_WHAT: Record<SpotUndo['action'], string> = { add: 'adding', rename: 'renaming', remove: 'removing', change: 'the change to' }

/** What was moved to the newest version, said where the offer was until the document changes again. */
const movedNotice = ref<{ text: string; revisionId: number } | null>(null)
const shownMovedNotice = computed(() =>
  movedNotice.value && document.value?.currentRevision.id === movedNotice.value.revisionId ? movedNotice.value.text : null,
)

async function moveToNewestVersion(): Promise<void> {
  const words = await spotChanges.moveToLatest()
  if (!words || !document.value) return
  movedNotice.value = { text: words, revisionId: document.value.currentRevision.id }
  fieldActionError.value = null
  say({ from: 'brownie', text: words })
  await nextTick()
  window.document.getElementById('document-pane')?.focus({ preventScroll: true })
}

/** Until the template's definitions arrive, the page cannot know its fill spots, so it says it is loading. */
const pageLoading = computed(() => document.value !== null && definitionsLoadedForVersionId.value !== document.value.templateVersionId)
const scalarFields = computed(() => editableFields.value.filter((field) => field.cardinality === 'SCALAR'))
/**
 * Every repeated field of a template is one column of the same logical row (an action item's task,
 * owner, and due date, for the built-in minutes), and the filler requires them to hold the same
 * number of items -- so they are edited together as rows, never one list at a time.
 */
const repeatedFields = computed(() => editableFields.value.filter((field) => field.cardinality === 'REPEATED'))
const editableFieldIds = computed(() => new Set(editableFields.value.map((field) => field.fieldId)))
const requiredFieldIds = computed(() => {
  const ids = new Set(ruleRequiredFieldIds.value)
  for (const field of editableFields.value) if (field.requiredness === 'REQUIRED') ids.add(field.fieldId)
  return ids
})

/**
 * Moves keyboard focus to a field's fill spot (a repeated field's first row), so a finding can be
 * fixed without hunting for it. The page is brought into view first: on a narrow screen it may be
 * behind Brownie's panel, and in print preview it is not drawn at all.
 */
async function focusField(fieldId: string): Promise<void> {
  narrowView.value = 'document'
  docView.value = 'page'
  await nextTick()
  const target =
    window.document.getElementById(`edit-${fieldId}`) ??
    window.document.getElementById(`edit-${fieldId}-0`) ??
    // A repeated field with no rows yet is filled by adding one.
    window.document.querySelector<HTMLElement>('#document-pane [data-add-row]')
  if (!(target instanceof HTMLElement)) return
  target.scrollIntoView?.({ block: 'center' })
  target.focus()
  if (target.hasAttribute('data-add-row')) announce(`${labelOf(fieldId)} has no rows yet. Add a row to fill it in.`)
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

/**
 * The drafts as a save would send them. A save trims every value, so a draft that differs only by
 * surrounding spaces is not a change: counting it as one would leave the page "unsaved" for good,
 * since no save could ever make the two agree.
 */
function comparableDrafts(source: Drafts): string {
  const comparable: Record<string, string | string[]> = {}
  for (const fieldId of Object.keys(source).sort()) {
    const value = source[fieldId]!
    comparable[fieldId] = Array.isArray(value) ? value.map((item) => item.trim()) : value.trim()
  }
  return JSON.stringify(comparable)
}

const isDirty = computed(() => comparableDrafts(drafts.value) !== comparableDrafts(cleanDrafts.value))
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

function updateScalar(fieldId: string, value: string): void {
  drafts.value[fieldId] = value
}

function updateRow(fieldId: string, index: number, value: string): void {
  // Assigned in place, so the other rows' drafts (and the inputs showing them) are untouched.
  const values = rowDraft(fieldId)
  if (index < 0 || index >= values.length) return
  values[index] = value
}

/** What a review decision is called when the page says it was recorded. */
const DECISION_WORDS: Record<ReviewDecision, string> = {
  UNREVIEWED: 'not reviewed',
  ACCEPTED: 'accepted',
  REJECTED: 'rejected',
  NEEDS_CLARIFICATION: 'needs clarification',
}

function fieldStateOf(fieldId: string): FieldStateResponse | null {
  return document.value?.currentRevision.fields[fieldId]?.fieldState ?? null
}

function isFieldLocked(fieldId: string): boolean {
  return fieldStateOf(fieldId)?.lock === 'EXPLICITLY_LOCKED'
}

const lockedFieldIds = computed(() => new Set(scalarFields.value.filter((field) => isFieldLocked(field.fieldId)).map((field) => field.fieldId)))

/** Whether any column of a row is locked: a row locked partway (a lock that stopped after its first column) still reads as locked. */
function rowLocked(index: number): boolean {
  return repeatedFields.value.some(
    (field) => document.value?.currentRevision.fields[field.fieldId]?.itemFieldStates?.[index]?.lock === 'EXPLICITLY_LOCKED',
  )
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
        problems.push(`Row ${index + 1} needs a value for ${fieldLabel(field)}, or remove the row.`)
      }
    }
  }
  return problems
})

/** The page moves focus into the new row itself, since it knows where the row is drawn. */
function addRow(): void {
  for (const field of repeatedFields.value) {
    drafts.value[field.fieldId] = [...rowDraft(field.fieldId), '']
  }
}

async function removeRow(index: number): Promise<void> {
  for (const field of repeatedFields.value) {
    drafts.value[field.fieldId] = rowDraft(field.fieldId).filter((_, position) => position !== index)
  }
  if (selected.value?.rowIndex != null) selected.value = null
  announce(`Row ${index + 1} removed.`)
  // The button pressed went with the bar; focus goes to the row that took its place, or the one
  // before it, or "Add row" when none is left, rather than to the top of the page.
  await nextTick()
  const first = repeatedFields.value[0]
  const next = first && rowCount.value > 0 ? window.document.getElementById(`edit-${first.fieldId}-${Math.min(index, rowCount.value - 1)}`) : null
  const addRowButton = window.document.querySelector<HTMLElement>('#document-pane [data-add-row]')
  ;(next ?? addRowButton)?.focus()
}

async function moveRow(index: number, delta: -1 | 1): Promise<void> {
  const target = index + delta
  if (target < 0 || target >= rowCount.value) return
  for (const field of repeatedFields.value) {
    const values = [...rowDraft(field.fieldId)]
    const moved = values[index]!
    values[index] = values[target]!
    values[target] = moved
    drafts.value[field.fieldId] = values
  }
  if (selected.value && selected.value.rowIndex === index) selected.value = { ...selected.value, rowIndex: target }
  announce(`Row ${index + 1} moved ${delta < 0 ? 'up' : 'down'}.`)
}

type SaveStage = 'idle' | 'saving' | 'saved' | 'conflict' | 'failed'
const saveStage = ref<SaveStage>('idle')
const saveError = ref<string | null>(null)

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

/** The save under way, if any, so that Export and Undo can wait for it rather than act beside it. */
let currentSave: Promise<void> | null = null

async function saveEdits(trigger: 'manual' | 'auto' = 'manual'): Promise<void> {
  const save = saveEditsOnce(trigger)
  currentSave = save
  try {
    await save
  } finally {
    if (currentSave === save) currentSave = null
  }
}

/** Waits until no save is under way; a save never throws, it records how it ended. */
async function settleSaving(): Promise<void> {
  while (currentSave) await currentSave
}

async function saveEditsOnce(trigger: 'manual' | 'auto'): Promise<void> {
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
        editReason: trigger === 'auto' ? 'Autosaved.' : 'Edited in the workspace.',
      },
      crypto.randomUUID(),
    )
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
      // Someone (or another action in this same browser) moved the document on since this page
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
      'the trash. They are still on the page.'
    )
  }
  if (error instanceof ApiRequestError) {
    if (error.status === 409 && error.problem?.code === 'FIELD_LOCKED') {
      return error.problem.detail ?? 'A fill spot you changed is locked. Unlock it first, or undo that change.'
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
// Autosave: a pause in typing saves what changed against the revision this page loaded. Rows
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

/**
 * While the chat changes the form's fill spots, autosave waits: the change makes a new version of the document,
 * and a save made meanwhile against the one on screen would be refused, or would refuse the change. What was
 * typed meanwhile is saved once the page shows the new version.
 */
let autosaveHeld = false

function holdAutosave(): void {
  autosaveHeld = true
  cancelAutosave()
}

function releaseAutosave(): void {
  autosaveHeld = false
  if (isDirty.value && saveStage.value !== 'conflict') scheduleAutosave()
}

async function autosave(): Promise<void> {
  if (autosaveHeld || !isDirty.value || rowProblems.value.length > 0) return
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

/** What the status beside Export says, in the fewest words that are true. */
const saveStatus = computed<{ tone: 'saved' | 'pending' | 'problem'; text: string }>(() => {
  if (saveStage.value === 'conflict') return { tone: 'problem', text: 'Not saved' }
  if (saveStage.value === 'saving') return { tone: 'pending', text: 'Saving…' }
  if (isDirty.value) return saveStage.value === 'failed' || rowProblems.value.length > 0 ? { tone: 'problem', text: 'Not saved' } : { tone: 'pending', text: 'Unsaved changes' }
  if (saveStage.value === 'failed') return { tone: 'problem', text: 'Not saved' }
  return { tone: 'saved', text: 'Saved' }
})

function confirmLeavingUnsavedWork(): boolean {
  if (!hasUnsavedWork()) return true
  return window.confirm('You have unsaved changes on this document. Leave anyway?')
}

onBeforeRouteLeave(confirmLeavingUnsavedWork)
// Another document opened from here (a template started from the sidebar) is the same route with
// another id, which the leave guard does not see.
onBeforeRouteUpdate((to, from) => to.params.documentId === from.params.documentId || confirmLeavingUnsavedWork())

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
 * than replacing the drafts object, means a fill spot whose draft did not change is not
 * re-rendered, so a caret in it stays where it was.
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

/**
 * Asked by Export before it checks the document: nothing may be exported that the server does not
 * hold. A save already under way is waited for. A conflict is the person's to settle, so Export
 * never saves over the other version for them; it says the changes are not saved instead.
 */
async function saveBeforeExport(): Promise<boolean> {
  cancelAutosave()
  await settleSaving()
  if (saveStage.value === 'conflict' || rowProblems.value.length > 0) return false
  if (isDirty.value) await saveEdits('manual')
  // A save whose reload failed leaves this page on the version before it, which Export would then check.
  return !hasUnsavedWork() && reloadError.value === null
}

const saveStatusRef = ref<HTMLElement | null>(null)

/** "Save now" goes away once nothing is left to save; focus then moves to the status that says so. */
async function saveNow(): Promise<void> {
  if (saveStage.value === 'saving' || rowProblems.value.length > 0) return
  await saveEdits('manual')
  await nextTick()
  const active = window.document.activeElement
  if (active === null || active === window.document.body) saveStatusRef.value?.focus()
}

// ---- Undo --------------------------------------------------------------------------------------
//
// Undo goes back to how the document read before its latest change, whoever made it -- a pause in
// typing, Brownie's filled-in values, a restore -- by restoring the most recent earlier version
// whose values differ from the current ones. Versions that only recorded a check, a review or a
// lock are skipped, since undoing one would appear to do nothing. Pressing it again goes further
// back: the page remembers where the last undo landed for as long as the document still reads the
// way that undo left it. Typing not saved yet is saved first, so undoing it leaves it in the
// version history rather than losing it; typing that cannot be saved as it is (a conflict, a row
// with a blank date) is discarded only after the person says so. A locked fill spot keeps its
// value: the server leaves it as it is and says so.
const undoing = ref(false)
/** Where the last undo landed: the version it restored, while the document still reads the way it left it. */
let undoLandedAt: { undoKey: string; restoredRevisionId: number } | null = null

/**
 * What one step of Undo compares: the values, and the version of the form they were written against,
 * so a version that only changed the form's fill spots (values carried over as they were) is a step too.
 */
function undoKey(revision: DocumentRevisionResponse): string {
  return `${revision.contentHash}\n${revision.templateVersionId ?? ''}`
}

async function undoLastChange(): Promise<void> {
  if (undoing.value || !document.value || session.personalWorkspaceId === undefined) return
  undoing.value = true
  fieldActionError.value = null
  try {
    cancelAutosave()
    await settleSaving()
    if (isDirty.value) {
      if (saveStage.value === 'conflict' || rowProblems.value.length > 0) {
        if (!window.confirm('Undo discards the changes on this page that are not saved. Discard them?')) return
        resetDrafts()
        announce('Your unsaved changes were undone.')
        return
      }
      await saveEdits('manual')
      // The save's own message says why it did not happen; nothing is undone meanwhile.
      if (hasUnsavedWork()) return
    }
    await restorePreviousVersion()
  } finally {
    undoing.value = false
  }
}

async function restorePreviousVersion(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const current = document.value?.currentRevision
  if (workspaceId === undefined || !current) return
  try {
    const revisions = (await listDocumentRevisions(workspaceId, props.documentId)) ?? []
    const ordered = [...revisions].sort((left, right) => left.revisionNumber - right.revisionNumber)
    const currentIndex = ordered.findIndex((revision) => revision.id === current.id)
    const landedIndex = undoLandedAt && undoLandedAt.undoKey === undoKey(current) ? ordered.findIndex((revision) => revision.id === undoLandedAt?.restoredRevisionId) : -1
    const startIndex = landedIndex >= 0 ? landedIndex : currentIndex
    let targetIndex = -1
    for (let index = Math.min(startIndex, ordered.length) - 1; index >= 0; index--) {
      if (undoKey(ordered[index]!) !== undoKey(current)) {
        targetIndex = index
        break
      }
    }
    if (currentIndex < 0 || targetIndex < 0) {
      announce('There is nothing earlier to go back to.')
      fieldActionError.value = 'There is nothing earlier to go back to.'
      return
    }
    const target = ordered[targetIndex]!
    const restored = await restoreRevision(
      workspaceId,
      props.documentId,
      target.id,
      current.id,
      crypto.randomUUID(),
      `Undid a change: back to version ${target.revisionNumber}.`,
    )
    undoLandedAt = { undoKey: undoKey(restored.revision), restoredRevisionId: target.id }
    await reloadWithLayout()
    const kept = restored.keptLockedFieldIds ?? []
    const keptWords = kept.length > 0 ? ` ${kept.map(labelOf).join(', ')} kept ${kept.length === 1 ? 'its' : 'their'} value because ${kept.length === 1 ? 'it is' : 'they are'} locked.` : ''
    say({ from: 'brownie', text: `Undone: the document is back to how it read in version ${target.revisionNumber}.${keptWords}` })
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, 'Could not undo the last change. Try again.')
    if (error instanceof ApiRequestError && error.routeMissing) {
      fieldActionError.value = describeCommonFailure(error, 'a way to undo a change')
    }
  }
}

/**
 * A change to what the document says starts the undo trail again from the newest version. A
 * version that only recorded a check, a review or a lock leaves the values as Undo left them, so
 * the trail carries on through it.
 */
watch(
  () => (document.value ? undoKey(document.value.currentRevision) : null),
  (key) => {
    if (undoLandedAt && key !== undoLandedAt.undoKey) undoLandedAt = null
  },
)

// ---- The selected fill spot ----------------------------------------------------------------------
//
// Whichever fill spot the person last moved into: its state, review and lock controls sit in the
// bar at the foot of the page, and the Rules card shows its text style and the rules about it.
const selected = ref<{ fieldId: string; rowIndex: number | null } | null>(null)
const selectionBarRef = ref<HTMLElement | null>(null)
/** Set while the bar hands focus back to its spot on closing, so that focus does not open the bar again. */
let closingBar = false

function select(selection: { fieldId: string; rowIndex: number | null }): void {
  if (closingBar) return
  if (selected.value?.fieldId === selection.fieldId && selected.value?.rowIndex === selection.rowIndex) return
  selected.value = selection
  if (evidenceOpenFor.value !== null && evidenceOpenFor.value !== selection.fieldId) evidenceOpenFor.value = null
}

function spotElementId(target: { fieldId: string; rowIndex: number | null }): string {
  return target.rowIndex === null ? `edit-${target.fieldId}` : `edit-${target.fieldId}-${target.rowIndex}`
}

/** The fill spot last focused; a field the template draws in two places has two, and focus goes back to the one the person was in. */
let lastSpotElement: HTMLElement | null = null

function spotElementFor(target: { fieldId: string; rowIndex: number | null }): HTMLElement | null {
  const id = spotElementId(target)
  const last = lastSpotElement
  if (last && last.isConnected && (last.id === id || last.id.startsWith(`${id}--`))) return last
  return window.document.getElementById(id)
}

function clearSelection(): void {
  const previous = selected.value
  selected.value = null
  evidenceOpenFor.value = null
  if (!previous) return
  // Back to the fill spot the bar was about, so a keyboard user carries on where they were.
  closingBar = true
  try {
    spotElementFor(previous)?.focus()
  } finally {
    closingBar = false
  }
}

/**
 * Words selected on the page bring up "Fill in here" beside them; the bar about the spot last in use gives way,
 * so the two never show at once. Focus stays where the selection put it.
 */
function closeBarForSelectedWords(): void {
  selected.value = null
  evidenceOpenFor.value = null
}

/**
 * The bar follows the spot that has focus and is drawn after the whole page, so Tab alone would only
 * reach it for the last spot. Alt+Enter (Option+Return) in a spot comes here instead, and Escape in
 * the bar goes back to the spot, leaving the bar open.
 */
async function openActions(target: { fieldId: string; rowIndex: number | null }): Promise<void> {
  select(target)
  await nextTick()
  // The bar is always in view at the foot of the screen, so the page stays where it is.
  selectionBarRef.value?.focus({ preventScroll: true })
}

function returnToSelectedSpot(): void {
  const current = selected.value
  if (current) spotElementFor(current)?.focus()
}

/** How to reach the bar from a spot, in the words of the keyboard in use. */
const actionsKey = /Mac|iPhone|iPad/.test(window.navigator.platform ?? '') ? 'Option+Return' : 'Alt+Enter'

/**
 * The bar sits over the foot of the page, so a spot that gets focus there, or that the bar grows
 * over, would be typed into unseen. The page scrolls by just enough to show it above the bar; a value
 * taller than the room there keeps its first line, where the caret is, in view.
 */
function keepFocusClearOfBar(): void {
  const bar = selectionBarRef.value
  const focused = window.document.activeElement
  // A Word page or a PDF form's pages: either way the spot being typed in stays in view above the bar.
  if (!bar || !(focused instanceof HTMLElement) || !focused.closest('.document-page, .pdf-form-page')) return
  // A menu the page raised into the top layer (the one on its text) is drawn over the bar, which hides none of it.
  if (focused.closest('[popover]')) return
  // A button pressed with a pointer is not chased: moving it between the press and the release would
  // lose the click. A fill spot always counts as focus to show, however it was reached.
  if (!focused.classList.contains('fill-spot__control') && !focusIsShown(focused)) return
  const barBox = bar.getBoundingClientRect()
  // A bar that takes no room (the document pane hidden behind Brownie's panel) covers nothing.
  if (barBox.height === 0) return
  const box = focused.getBoundingClientRect()
  // A spot the person has scrolled away from, below the screen, is not chased.
  if (box.top >= barBox.bottom) return
  const covered = box.bottom - barBox.top + 12
  if (covered <= 0) return
  // Where the page fills the window, the document pane scrolls on its own and the window does not.
  const pane = bar.closest('.document-pane')
  const paneScrolls =
    pane instanceof HTMLElement &&
    typeof pane.scrollBy === 'function' &&
    window.getComputedStyle(pane).overflowY !== 'visible' &&
    pane.scrollHeight > pane.clientHeight
  const top = paneScrolls ? pane.getBoundingClientRect().top : 0
  const scroll = Math.min(covered, Math.max(0, box.top - top - 12))
  if (scroll <= 0) return
  if (paneScrolls) pane.scrollBy({ top: scroll })
  else if (typeof window.scrollBy === 'function') window.scrollBy({ top: scroll })
}

/** Whether the browser shows this element's focus; an engine that cannot say is taken to show it. */
function focusIsShown(element: HTMLElement): boolean {
  try {
    return element.matches(':focus-visible')
  } catch {
    return true
  }
}

let barObserver: ResizeObserver | null = null
watch(selectionBarRef, (bar) => {
  barObserver?.disconnect()
  barObserver = null
  // The bar grows when the spot it is about gets a state (after a save), and can then cover it.
  if (bar && typeof ResizeObserver !== 'undefined') {
    barObserver = new ResizeObserver(() => keepFocusClearOfBar())
    barObserver.observe(bar)
  }
})
watch(selected, () => void nextTick(keepFocusClearOfBar), { flush: 'post' })

function onDocumentFocusIn(event: FocusEvent): void {
  if (event.target instanceof HTMLElement && event.target.classList.contains('fill-spot__control')) lastSpotElement = event.target
  void nextTick(keepFocusClearOfBar)
}

/** A text spot grows as a value wraps; the line being typed stays above the bar. */
function onDocumentInput(): void {
  void nextTick(keepFocusClearOfBar)
}

onBeforeUnmount(() => barObserver?.disconnect())

const selectedField = computed(() => (selected.value ? (editableFields.value.find((field) => field.fieldId === selected.value!.fieldId) ?? null) : null))
const selectedLabel = computed(() => {
  if (!selected.value) return ''
  const label = labelOf(selected.value.fieldId)
  return selected.value.rowIndex === null ? label : `${label}, row ${selected.value.rowIndex + 1}`
})
/** The selected spot's own state: for a row, its own column's item, since each column is filled and checked on its own. */
const selectedState = computed<FieldStateResponse | null>(() => {
  const current = selected.value
  if (!current) return null
  if (current.rowIndex === null) return fieldStateOf(current.fieldId)
  return document.value?.currentRevision.fields[current.fieldId]?.itemFieldStates?.[current.rowIndex] ?? null
})
/** A row's review and lock act on the whole row, so they are offered whenever any of its columns holds a value. */
const selectedRowHasState = computed(() => {
  const current = selected.value
  if (!current || current.rowIndex === null) return false
  const index = current.rowIndex
  return repeatedFields.value.some((field) => (document.value?.currentRevision.fields[field.fieldId]?.itemFieldStates?.[index] ?? null) !== null)
})
const selectedRequired = computed(() => (selected.value ? requiredFieldIds.value.has(selected.value.fieldId) : false))
/** What the bar's Lock or Unlock does: a field's own lock, or the whole row's, the same test the row action makes. */
const selectedLocked = computed(() => {
  const current = selected.value
  if (!current) return false
  return current.rowIndex === null ? selectedState.value?.lock === 'EXPLICITLY_LOCKED' : rowLocked(current.rowIndex)
})
/** What the bar says about a spot with no state yet: empty, or typed and not saved; nothing when it is saved as it reads. */
const selectedHint = computed<string | null>(() => {
  const current = selected.value
  if (!current) return null
  const draft = (current.rowIndex === null ? scalarDraft(current.fieldId) : (rowDraft(current.fieldId)[current.rowIndex] ?? '')).trim()
  if (draft === '') return 'Nothing filled in here yet.'
  const clean = cleanDrafts.value[current.fieldId]
  const saved = current.rowIndex === null ? (typeof clean === 'string' ? clean : '') : (Array.isArray(clean) ? (clean[current.rowIndex] ?? '') : '')
  return draft === saved.trim() ? null : 'Not saved yet.'
})

// A reload can take away what was selected: a row removed elsewhere, or a field the version no longer has.
watch([editableFieldIds, rowCount], () => {
  const current = selected.value
  if (!current) return
  if (!editableFieldIds.value.has(current.fieldId) || (current.rowIndex !== null && current.rowIndex >= rowCount.value)) {
    selected.value = null
  }
})

// ---- Rules card ------------------------------------------------------------------------------

/** The text style a value in the selected fill spot takes when exported; the most common one when nothing is selected. */
const shownStyle = computed(() => {
  const style = selected.value && layout.value ? fillSpotStyle(layout.value, selected.value.fieldId) : null
  return describeStyle(style ?? (layout.value ? dominantFillSpotStyle(layout.value) : null))
})
/** The style's parts as chips; none until the template's layout is read, since the style comes from it. */
const styleChips = computed(() => {
  if (!layout.value) return []
  // One of a PDF's own fields is written the way the form says, which the page does not know.
  if (selectedPdfSpot.value?.bindingKind === 'ACROFORM_FIELD') return []
  const style = shownStyle.value
  const chips: string[] = []
  if (style.font) chips.push(style.font)
  if (style.size) chips.push(style.size)
  chips.push(style.weight)
  if (style.italic) chips.push('Italic')
  if (style.underline) chips.push('Underlined')
  return chips
})
/** How a date reads once exported, shown with today's date, where the selected spot (or any spot) is a date. */
const dateExample = computed(() => {
  const dateField =
    selectedField.value?.type === 'DATE' || (!selectedField.value && editableFields.value.some((field) => field.type === 'DATE'))
  return dateField ? formatDateLikeExport(todayIso()) : null
})
/** The fill spots the template itself requires, in page order; a rule that requires more lists them in its own words. */
const requiredLabels = computed(() => editableFields.value.filter((field) => field.requiredness === 'REQUIRED').map((field) => fieldLabel(field)))

function todayIso(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** Rules about the selected fill spot and rules about the whole document; every rule when nothing is selected. */
const shownRules = computed(() => {
  const fieldId = selected.value?.fieldId ?? null
  if (!fieldId) return rulesInForce.value
  return rulesInForce.value.filter((rule) => {
    const payload = rule.payload
    if (payload.kind === 'REQUIRED_FIELDS') return (payload.fieldIds ?? []).includes(fieldId)
    if (payload.fieldId) return payload.fieldId === fieldId
    return rule.scope.kind === 'WHOLE_TEMPLATE'
  })
})

function ruleWords(rule: RuleResponse): string {
  return describePayload(rule.payload, labelOf)
}

// ---- Print preview -----------------------------------------------------------------------------
//
// The latest compiled PDF of this document: the file as it exports. A compilation is never started
// on its own -- it spawns the isolated renderer -- so the view shows whatever exists for the
// current content (from an earlier "Generate preview" or from a check, which compiles too) and asks
// for a click to make a new one. Staleness is judged by the revision's content hash, not its id: a
// review decision or a lock makes a new revision without changing a single value, and a preview of
// identical content is not out of date.
type PreviewStage = 'idle' | 'loading' | 'generating' | 'ready' | 'failed'
const previewStage = ref<PreviewStage>('idle')
const previewError = ref<string | null>(null)
const previewArtifactId = ref<number | null>(null)
const previewContentHash = ref<string | null>(null)
const previewRevisionNumber = ref<number | null>(null)

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
      // A check compiles this exact content too, and its PDF is the same file an export offers.
      try {
        const checked = await getLatestValidation(workspaceId, props.documentId, current.id)
        if (checked.pdfArtifactId != null) {
          adoptPreview(checked.pdfArtifactId, current.contentHash, current.revisionNumber)
          return
        }
      } catch {
        // No check either: the preview has simply not been made yet.
      }
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

// Only read while the print preview is what the person is looking at.
watch(
  [() => document.value?.currentRevision.contentHash, docView],
  ([, view]) => {
    if (view === 'print') void loadExistingPreview()
  },
)

// ---------------------------------------------------------------------------------------------
// Evidence: a value filled by Brownie carries the ids of the source spans it was taken from. The
// bar at the foot of the page opens the cited excerpts, fetched through the document's own
// evidence route. Nothing here can point at a position on the rendered file: the renderer emits no
// locator, and the panel says so instead of guessing.
const evidenceOpenFor = ref<string | null>(null)
const evidenceExcerpts = ref<EvidenceExcerptResponse[]>([])
const evidenceStage = ref<'idle' | 'loading' | 'ready' | 'failed'>('idle')
const evidenceError = ref<string | null>(null)
const MAX_EVIDENCE_EXCERPTS = 5

function evidenceSpanIdsOf(fieldId: string): number[] {
  return document.value?.currentRevision.fields[fieldId]?.evidenceSourceSpanIds ?? []
}

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

// ---- Adding a source ---------------------------------------------------------------------------

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
    // The new source is the one read next, but a reading under way keeps the source it started with.
    if (!runUnderWay.value) selectedSourceId.value = attached.id
    sourceUploadState.value = 'idle'
    announce(`${sourceName(attached)} attached.`)
  } catch (error) {
    sourceUploadError.value = uploadFailureMessage(error, file)
    sourceUploadState.value = 'error'
  }
}

// Google's answer opened a picker by itself on this first visit only; after the panel closes, it
// opens with every picker closed like any other time.
watch(sourcesOpen, (open) => {
  if (open) return
  calendarPickerStartsOpen.value = false
  drivePickerStartsOpen.value = false
})

async function toggleSources(): Promise<void> {
  sourcesOpen.value = !sourcesOpen.value
  if (!sourcesOpen.value) return
  await nextTick()
  window.document.getElementById('attach-source')?.focus()
}

const addSourceButtonRef = ref<HTMLElement | null>(null)

/** The panel's own close button goes with it, so focus returns to the + that opened it. */
async function closeSources(): Promise<void> {
  sourcesOpen.value = false
  await nextTick()
  addSourceButtonRef.value?.focus()
}

/** The empty page's one call to action: Brownie's panel, with the file picker open and focused. */
async function goToSources(): Promise<void> {
  await showAssistant(false)
  sourcesOpen.value = true
  await nextTick()
  window.document.getElementById('attach-source')?.focus()
}

// ---- Reading a source ----------------------------------------------------------------------------

/**
 * Asks the trusted worker to pull typed facts (and repeated rows) out of the selected source. The
 * job may pause to ask about anything missing or conflicting; once it finishes, its values become
 * a proposal, and approving the proposal is the only step that changes this document. A failure
 * here happens before any job exists, so trying again is safe; once a job id is known, the poll
 * loop below never starts another.
 */
async function tryGroundedExtraction(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const source = selectedSource.value
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
  runSourceName.value = sourceName(source)
  placeInChat('run')
  try {
    const receipt = await startExtraction(workspaceId, props.documentId, source.artifactId, crypto.randomUUID())
    extractionJobId.value = receipt.jobId
    runFollowedHere.value = receipt.jobId
    extractionStage.value = 'running'
  } catch (error) {
    extractionStage.value = 'failed'
    extractionError.value =
      describeCommonFailure(error, 'reading sources') ??
      (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'Could not start reading. Try again.')
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
    return 'This reading is no longer available, for example because its document was moved to the trash.'
  }
  if (error.status === 401 || error.routeMissing) return describeCommonFailure(error, 'a way to check on a run')
  if (error.status === 403) return 'Brownie no longer lets this account see this reading, so this page stopped checking on it.'
  return null
}

/** Set when this page goes away, so a run it was following is no longer asked about every few seconds. */
let pageLeft = false
onBeforeUnmount(() => {
  pageLeft = true
})

/**
 * Follows one job to a resting state. A failed status read is not a failed job: the loop keeps
 * going with a longer gap and tells the person it lost contact, because giving up here would
 * invite a second, paid start. It stops early only on an answer waiting cannot change (see
 * pollFailureWaitingCannotFix), or when the run's questions or result cannot be read once it gets
 * there, which "Check again" picks up. After ten minutes it stops polling and offers "Check again"
 * instead, since the job's real state is on the server whenever the person asks. It also stops as
 * soon as the person leaves this page: nothing is left to show the answer to.
 */
async function pollJobUntilTerminal(workspaceId: number, jobId: number): Promise<void> {
  const terminalStates = new Set(['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED'])
  const startedAt = Date.now()
  const deadline = startedAt + 10 * 60 * 1000
  let delayMs = 1500
  noWorkerYet.value = false
  while (Date.now() < deadline && !pageLeft) {
    let job
    try {
      job = await getJob(workspaceId, jobId)
      extractionError.value = null
      delayMs = 1500
    } catch (error) {
      if (pageLeft || extractionJobId.value !== jobId) return
      const ending = pollFailureWaitingCannotFix(error)
      if (ending !== null) {
        extractionStage.value = 'failed'
        extractionError.value = ending
        return
      }
      extractionError.value =
        error instanceof ApiRequestError && error.status === 429
          ? 'Brownie asked this page to check less often; still checking.'
          : 'Lost contact with the server; still checking.'
      delayMs = Math.min(delayMs * 2, 15_000)
      await new Promise((resolve) => setTimeout(resolve, delayMs))
      continue
    }
    if (pageLeft || extractionJobId.value !== jobId) return
    extractionJobState.value = job.state
    cancellationRequested.value = cancellationRequested.value || job.cancellationRequestedAt != null
    // A worker claims a queued job within a couple of seconds. Thirty seconds with no attempt means
    // there is no worker to claim it, which the person should be told rather than left watching.
    noWorkerYet.value = job.state === 'QUEUED' && job.attemptCount === 0 && Date.now() - startedAt > 30_000
    if (job.state === 'WAITING_FOR_INPUT') {
      try {
        openQuestions.value = await getGenerationQuestions(workspaceId, props.documentId, jobId)
      } catch (error) {
        stopFollowingUnreadRun('This reading is waiting for your answers, but its questions could not be loaded.', error, 'questions for a run')
        return
      }
      extractionStage.value = 'waiting-for-input'
      announce('Brownie has a question for you.')
      return
    }
    if (terminalStates.has(job.state)) {
      if (job.state === 'SUCCEEDED') {
        let result
        try {
          result = await getExtractionResult(workspaceId, props.documentId, jobId)
        } catch (error) {
          stopFollowingUnreadRun('This reading has finished, but its result could not be loaded.', error, 'run results')
          return
        }
        extractionResultArtifactId.value = result.artifactId
        extractionStage.value = 'succeeded'
        if (runFollowedHere.value === jobId) void applyResultToDocument()
      } else if (job.state === 'CANCELLED') {
        extractionStage.value = 'cancelled'
        announce('Brownie stopped reading. Nothing on the document changed.')
      } else {
        extractionStage.value = 'failed'
        // FAILED or DEAD: the job queue's names for a run that stopped trying, not words for a person.
        extractionError.value = 'This reading gave up before it could finish.'
      }
      return
    }
    await new Promise((resolve) => setTimeout(resolve, delayMs))
  }
  if (pageLeft) return
  extractionStalled.value = true
  stalledMessage.value = 'Still reading after ten minutes of checking. It carries on on the server.'
}

/** Re-reads the run from the server; used after polling stopped, and safe at any time. */
async function checkRunAgain(): Promise<void> {
  try {
    await followLatestRun()
  } catch (error) {
    // The run is as it was; only this check failed, so the offer to check stays.
    stalledMessage.value = describeCommonFailure(error, 'a list of runs') ?? 'Brownie could not check on this reading just now.'
  }
}

const retryingRun = ref(false)
/** The job the server said can never be started again, so the offer is not repeated for it. */
const runThatCannotBeRetried = ref<number | null>(null)
/**
 * Only a run that gave up can be started again. Starting a new reading from the same source on the
 * same version of the document would find this same run and report the same ending, so this is the
 * way forward until the document changes.
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
    runFollowedHere.value = jobId
    extractionStage.value = 'running'
  } catch (error) {
    if (error instanceof ApiRequestError && error.problem?.code === 'JOB_TARGET_STALE') {
      runThatCannotBeRetried.value = jobId
      extractionError.value = 'This document has changed since that reading began, so it cannot be started again. Ask me to fill it again instead.'
    } else if (error instanceof ApiRequestError && !error.routeMissing && (error.status === 409 || error.status === 404)) {
      // The run is not in the state this page last saw (another tab restarted it, or it has since
      // finished), so asking again cannot help; what the server says now is what should be shown.
      runThatCannotBeRetried.value = jobId
      await rehydrateLatestRun()
    } else {
      // Nothing happened to the run. A server without the retry route says nothing about the run
      // itself, so it keeps its ending and the offer stays for once the server has been updated.
      extractionError.value = describeCommonFailure(error, 'a way to start a run again') ?? 'Could not start this reading again. Try again.'
    }
    return
  } finally {
    retryingRun.value = false
  }
  await pollJobUntilTerminal(workspaceId, jobId)
}

/**
 * Cooperative: the worker checks before each paid call and the job ends CANCELLED; a model call
 * already in flight may still finish and cost. The button therefore says "Stopping…" until the job
 * really reaches a resting state, which the poll loop reports.
 */
async function cancelExtraction(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null || cancellationRequested.value) return
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
    extractionError.value = describeCommonFailure(error, 'a way to cancel a run') ?? 'Could not stop reading. Try again.'
  }
}

/** Saves one answer; once every question has one, the reading carries on by itself, since that is what answering was for. */
async function answerWith(questionId: number, value: string): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const answerValue = value.trim()
  if (workspaceId === undefined || !answerValue || answeringQuestionId.value !== null) return

  answeringQuestionId.value = questionId
  extractionError.value = null
  try {
    const answered = await answerQuestion(workspaceId, questionId, answerValue)
    openQuestions.value = openQuestions.value.map((question) => (question.id === answered.id ? answered : question))
  } catch (error) {
    extractionError.value = describeCommonFailure(error, "a way to answer a run's questions") ?? 'Could not save that answer. Try again.'
    return
  } finally {
    answeringQuestionId.value = null
  }
  await keepFocusInChat('question')
  if (openQuestions.value.length > 0 && openQuestions.value.every((question) => question.status === 'ANSWERED')) {
    await resumeAfterAnswers()
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
    runFollowedHere.value = jobId
    extractionStage.value = 'running'
    await pollJobUntilTerminal(workspaceId, jobId)
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 409 && !error.routeMissing) {
      // The run is no longer waiting (another tab carried on, or it was stopped): what the server says now is what to show.
      await rehydrateLatestRun()
      return
    }
    extractionStage.value = 'waiting-for-input'
    extractionError.value = describeCommonFailure(error, 'a way to continue a run once its questions are answered') ?? 'Could not carry on reading. Try again.'
  }
}

async function applyResultToDocument(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null || applyStage.value === 'applying') return

  applyStage.value = 'applying'
  applyError.value = null
  try {
    const proposal = await applyGenerationResult(workspaceId, props.documentId, jobId)
    placeInChat('proposal')
    proposalFromJobId.value = jobId
    patchProposal.value = proposal
    acceptResult.value = null
    proposalIntro.value = `Here is what I found in ${runSourceName.value ?? 'your source'}:`
    applyStage.value = 'proposed'
    announce('Brownie found values for this document. Check them, then approve them to fill them in.')
    // "Show what I found" went away with the reading's status; the proposal it showed takes focus.
    await keepFocusInChat()
  } catch (error) {
    applyStage.value = 'failed'
    applyError.value =
      error instanceof ApiRequestError && error.problem?.code === 'GENERATION_RESULT_EMPTY'
        ? `I could not find any of this document's values in ${runSourceName.value ?? 'that source'}.`
        : (describeCommonFailure(error, "a way to apply a run's results") ?? 'Could not get the values ready. Try again.')
  }
}

// ---- The chat -----------------------------------------------------------------------------------
//
// What was said on this visit, in order. Brownie's composer is not a free conversation: each line is
// matched to one of a few bounded things Brownie can do (fill from a source, change a field, shorten
// or rewrite a text field, explain a finding), and nothing changes on the document until the person
// approves the proposal it makes. What is still under way (reading a source, questions, a proposal)
// is drawn after these lines from the page's own state, so a reload shows it again.
type ChatLine = {
  id: number
  from: 'person' | 'brownie'
  text: string
  tone?: 'error'
  help?: string[]
  quote?: boolean
  /** A place in the conversation where the reading under way, or the proposal waiting for a decision, is drawn. */
  slot?: 'run' | 'proposal'
  /** A change to the form's fill spots this line reports, with Undo beside it. */
  undo?: SpotUndo
  undone?: boolean
  /** The lines a new fill spot could go on, to choose from; picking one sends `choiceFor` again with it. */
  choices?: { lineText: string; anchor: AssistPageAnchor }[]
  choiceFor?: string
  chosen?: boolean
}
const chat = ref<ChatLine[]>([])
let chatSequence = 0
const chatLogRef = ref<HTMLElement | null>(null)
/**
 * Where the reading and the proposal sit in the conversation: after whatever was said before they
 * began, and before whatever is said while they wait. Only the latest place of each is drawn, so an
 * earlier reading or proposal leaves behind only the lines said about it.
 */
const runSlotId = ref<number | null>(null)
const proposalSlotId = ref<number | null>(null)

/**
 * Adds a line to the conversation. What Brownie says is also announced, through the page's one live
 * region: the conversation itself is not a live region, so nothing is heard twice, and a reply is
 * heard even while the document is shown instead of the chat.
 */
function say(line: Omit<ChatLine, 'id'>): void {
  chat.value.push({ id: ++chatSequence, ...line })
  if (line.from === 'brownie' && line.text && !line.slot) {
    const help = (line.help ?? []).map((item) => (item.split(':')[0] ?? '').replace(/[<>]/g, '').trim()).filter(Boolean)
    announce(help.length > 0 ? `${line.text} ${help.join('; ')}.` : line.text)
  }
  void scrollChatToEnd()
}

function placeInChat(slot: 'run' | 'proposal'): void {
  say({ from: 'brownie', text: '', slot })
  if (slot === 'run') runSlotId.value = chatSequence
  else proposalSlotId.value = chatSequence
}

async function scrollChatToEnd(): Promise<void> {
  await nextTick()
  const log = chatLogRef.value
  if (log && typeof log.scrollTo === 'function') log.scrollTo({ top: log.scrollHeight })
  // Where the panel above the message box scrolls too (a short window), it moves just enough to show the
  // conversation's end, and no further.
  const outer = log?.parentElement
  if (log && outer && outer.scrollHeight > outer.clientHeight) {
    const hidden = log.getBoundingClientRect().bottom - outer.getBoundingClientRect().bottom
    if (hidden > 0) outer.scrollTop += hidden
  }
}

watch([extractionStage, applyStage, () => openQuestions.value.length], () => void scrollChatToEnd())

watch(extractionError, (text) => {
  // A reading that failed says so in an alert of its own.
  if (text && extractionStage.value !== 'failed') announce(text)
})
watch(
  () => (extractionStalled.value ? stalledMessage.value : ''),
  (text) => {
    if (text) announce(text)
  },
)
watch(noWorkerYet, (none) => {
  if (none) announce('No worker has picked this up yet. The reading waits until the Brownie worker is running.')
})
watch(extractionStage, (stage) => {
  // The failure's alert sits in Brownie's panel; where the panel is hidden, it is said instead.
  if (stage === 'failed' && !isShown(chatLogRef.value)) announce(extractionError.value ?? 'The reading stopped before it could finish.')
})

function isShown(element: HTMLElement | null): boolean {
  return element !== null && element.getClientRects().length > 0
}

// ---- "Here" on the page ----------------------------------------------------------------------------
//
// The last place selected or clicked on the page goes with a request to Brownie, so "add a fill spot for
// Company here" means that place. It is forgotten once the page shows another version of the form.
const pagePlace = ref<PagePoint | null>(null)

function onPagePlace(point: PagePoint): void {
  pagePlace.value = point
}

watch(
  () => layout.value?.versionId,
  () => {
    pagePlace.value = null
  },
)

/** The place on the page as a request to Brownie sends it; null when none is chosen or the page moved on. */
const pagePlaceAnchor = computed<AssistPageAnchor | null>(() => {
  const point = pagePlace.value
  const parserVersion = layout.value?.parserVersion
  if (!point || !parserVersion) return null
  const line = anchorLines.value.find((candidate) => candidate.nodeId === point.nodeId && candidate.anchorTextHash === point.anchorTextHash)
  const place = line ? placeOnLine(line, point) : null
  return line && place ? toDocxAnchor(line, place, parserVersion) : null
})

/**
 * The place a message's "here" means, as it is sent: on a PDF form, the point picked on a page for this
 * message only; on a Word page, the last place chosen there.
 */
function takePageAnchor(text: string): AssistPageAnchor | null {
  if (!isPdfForm.value) return pagePlaceAnchor.value
  const picked = pdfPageAnchor.value && /\bhere\b/i.test(text) ? pdfPageAnchor.value : null
  pdfPageAnchor.value = null
  return picked
}

/** Under the message box while it says "here": which place that is. */
const hereHint = computed(() => {
  if (isPdfForm.value) {
    if (!composerSaysHere.value) return null
    const picked = pdfPageAnchor.value
    return picked ? `"Here" is the place you picked on page ${picked.pageNumber}.` : 'To say where "here" is, click the place on the page first.'
  }
  if (!/\b(here|this line)\b/i.test(composerText.value)) return null
  const point = pagePlace.value
  const line = point && pagePlaceAnchor.value ? anchorLines.value.find((candidate) => candidate.nodeId === point.nodeId) : null
  if (!line) return 'To say where "here" is, select the place on the page first.'
  const words = lineWords(line)
  return `"Here" is the place you chose on the page, in the paragraph "${words.length > 60 ? `${words.slice(0, 59)}\u2026` : words}".`
})

/** The codes a refused fill spot change comes back with from the chat, which the page words itself. */
const SPOT_REFUSAL_CODES = new Set([
  'FILL_SPOT_ANCHOR_STALE',
  'FILL_SPOT_PLACE_NOT_ALLOWED',
  'FILL_SPOT_LOCKED',
  'FILL_SPOT_WOULD_NOT_PRINT',
  'TEMPLATE_VERSION_MOVED_ON',
  'DOCUMENT_TEMPLATE_VERSION_MOVED',
])
const SPOT_ACTIONS = { ADD_FILL_SPOT: 'add', RENAME_FILL_SPOT: 'rename', REMOVE_FILL_SPOT: 'remove' } as const

type AssistStage = 'idle' | 'interpreting' | 'executing' | 'done' | 'failed'
const assistStage = ref<AssistStage>('idle')
const assistBusy = computed(() => assistStage.value === 'interpreting' || assistStage.value === 'executing')
const composerText = ref('')

function onComposerKeydown(event: KeyboardEvent): void {
  // Enter sends and Shift+Enter starts a new line, as in most chat boxes; a key that finishes an
  // input-method composition (Japanese, Chinese, Korean) is left to finish it.
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void sendMessage()
}

/**
 * Sends one line to Brownie. The line is read first (what it would do, to which field), then done
 * straight away: filling from a source starts a reading, and a change or a rewrite comes back as a
 * proposal the person approves or not. An explanation is text. A line Brownie cannot act on is
 * answered with what it can do.
 */
async function sendMessage(given?: string, options: { anchor?: AssistPageAnchor | null; echo?: boolean } = {}): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const text = (given ?? composerText.value).trim()
  if (workspaceId === undefined || text === '' || assistBusy.value || !document.value) return
  if (given === undefined) composerText.value = ''
  if (options.echo !== false) say({ from: 'person', text })
  // The place chosen on the page goes along, so "here" means it; a line picked from Brownie's choices goes instead.
  const anchor = options.anchor !== undefined ? options.anchor : takePageAnchor(text)

  assistStage.value = 'interpreting'
  let interpretation
  try {
    interpretation = anchor ? await interpretAssist(workspaceId, props.documentId, text, anchor) : await interpretAssist(workspaceId, props.documentId, text)
  } catch (error) {
    assistStage.value = 'failed'
    say({
      from: 'brownie',
      tone: 'error',
      text:
        (brownieSaysNotThere(error) ? 'This document is no longer available, for example because it was moved to the trash.' : null) ??
        describeCommonFailure(error, 'a way to ask Brownie for changes') ??
        (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'I could not read that request. Try again.'),
    })
    // Nothing was done, so the words go back into the box to send again, unless something new was typed meanwhile.
    if (given === undefined && composerText.value === '') composerText.value = text
    return
  }

  if (!interpretation.executable) {
    assistStage.value = 'done'
    // A spot change refused because the document is on an older version of its form: the answer says to move it
    // first, so the offer to move comes back even if it was set aside with "Not now".
    if (interpretation.kind in SPOT_ACTIONS && behindLatest.value) spotChanges.offerNewerVersionAgain()
    const choices = interpretation.choices ?? []
    say({ from: 'brownie', text: interpretation.summary, help: interpretation.help, ...(choices.length > 0 ? { choices, choiceFor: text } : {}) })
    return
  }

  if (interpretation.kind === 'DRAFT') {
    assistStage.value = 'done'
    if (attachedSources.value.length === 0) {
      say({ from: 'brownie', text: 'Add your notes or a transcript with Add a source (+) first, then ask me again.' })
      return
    }
    if (extractionStage.value === 'unconfirmed') {
      say({ from: 'brownie', text: 'I lost track of the last reading. Press "Check again" above so I can see where it is before starting another.' })
      return
    }
    if (runUnderWay.value) {
      say({ from: 'brownie', text: 'I am already reading a source for this document. Wait for it to finish, or stop it first.' })
      return
    }
    await tryGroundedExtraction()
    return
  }

  assistStage.value = 'executing'
  // A change to the form's fill spots makes a new version of the document, so what was typed is saved first, as
  // a change made from the page is, and autosave waits until the page shows the new version.
  const changesSpots = interpretation.kind in SPOT_ACTIONS
  if (changesSpots) {
    holdAutosave()
    const unsaved = await saveTypingFirst()
    if (unsaved !== null || !document.value) {
      releaseAutosave()
      assistStage.value = 'failed'
      say({ from: 'brownie', tone: 'error', text: unsaved ?? 'This document is not loaded yet. Try again in a moment.' })
      // Nothing was done, so the words go back into the box to send again, unless something new was typed meanwhile.
      if (given === undefined && composerText.value === '') composerText.value = text
      return
    }
  }
  try {
    const expectedRevisionId = document.value.currentRevision.id
    const outcome = anchor
      ? await executeAssist(workspaceId, props.documentId, text, expectedRevisionId, anchor)
      : await executeAssist(workspaceId, props.documentId, text, expectedRevisionId)
    if (outcome.spotChange) {
      // Done at once, like a change made from the page: the page shows the form's new version, and Undo sits beside the answer.
      const change = outcome.spotChange
      await reloadWithLayout()
      const action = outcome.kind in SPOT_ACTIONS ? SPOT_ACTIONS[outcome.kind as keyof typeof SPOT_ACTIONS] : 'add'
      say({
        from: 'brownie',
        text: outcome.summary,
        undo: { action, label: change.label, previousRevisionId: change.previousRevisionId, revisionId: document.value?.currentRevision.id ?? -1 },
      })
      if (action === 'rename') await foundSpots.keepRenamed(change.fieldId)
      assistStage.value = 'done'
      return
    }
    if (outcome.proposal) {
      placeInChat('proposal')
      proposalFromJobId.value = null
      patchProposal.value = outcome.proposal
      acceptResult.value = null
      applyError.value = null
      proposalIntro.value = interpretation.kind === 'CHANGE_FIELD' ? 'Here is the change you asked for:' : 'Here is my suggestion:'
      applyStage.value = 'proposed'
      announce('Brownie proposed a change. Check it, then approve it to update the document.')
    }
    if (outcome.explanation) {
      say({ from: 'brownie', text: outcome.explanation, quote: true })
    }
    assistStage.value = 'done'
  } catch (error) {
    assistStage.value = 'failed'
    if (error instanceof ApiRequestError && error.status === 412) {
      // The reload's own message says why, when the current version could not be read.
      say({
        from: 'brownie',
        tone: 'error',
        text: (await loadDocument())
          ? 'This document changed since you loaded it, so it was reloaded. Ask again on the current version.'
          : 'This document changed since you loaded it, so that was not done.',
      })
      return
    }
    if (error instanceof ApiRequestError && SPOT_REFUSAL_CODES.has(error.problem?.code ?? '')) {
      const kind = interpretation.kind in SPOT_ACTIONS ? SPOT_ACTIONS[interpretation.kind as keyof typeof SPOT_ACTIONS] : 'add'
      const refusal = spotRefusal(error, kind, labelOf)
      if (refusal.reload) await reloadWithLayout()
      if (error.problem?.code === 'DOCUMENT_TEMPLATE_VERSION_MOVED') spotChanges.offerNewerVersionAgain()
      // A box on a PDF page is refused for reasons of its own (off the page, too small), which its words say.
      const message = isPdfForm.value ? spotChangeFailure(error, labelOf, kind === 'add' ? 'add' : 'change') : refusal.message
      say({ from: 'brownie', tone: 'error', text: message })
      return
    }
    say({
      from: 'brownie',
      tone: 'error',
      text:
        (brownieSaysNotThere(error) ? 'This document is no longer available, for example because it was moved to the trash.' : null) ??
        describeCommonFailure(error, 'a way to ask Brownie for changes') ??
        (error instanceof ApiRequestError && error.problem?.detail ? error.problem.detail : 'I could not do that. Try again.'),
    })
  } finally {
    if (changesSpots) releaseAutosave()
  }
}

/** A line picked from Brownie's choices: the same request again, with that line as the place. */
async function chooseLineFor(line: ChatLine, index: number): Promise<void> {
  const choice = line.choices?.[index]
  if (!choice || !line.choiceFor || line.chosen || assistBusy.value) return
  line.chosen = true
  say({ from: 'person', text: choice.lineText })
  await sendMessage(line.choiceFor, { anchor: choice.anchor, echo: false })
  await keepFocusInChat()
}

/** One of Brownie's suggestions, put in the message box for the person to finish rather than sent for them. */
async function useSuggestion(item: string): Promise<void> {
  const example = /for example "([^"]+)"/.exec(item)?.[1]
  // Without an example, the request's own words up to its first blank ("Shorten "), for the person to finish.
  const words = item.split(':')[0] ?? ''
  const blank = words.indexOf('<')
  composerText.value = example ?? (blank >= 0 ? words.slice(0, blank) : words.trim())
  await nextTick()
  const box = window.document.getElementById('assist-composer')
  if (box instanceof HTMLTextAreaElement) {
    box.focus()
    box.setSelectionRange(box.value.length, box.value.length)
  }
}

/** A proposal's values as lines a person reads: one per field, and one per row for repeated fields. */
const proposalLines = computed<{ key: string; label: string; value: string }[]>(() => {
  const proposal = patchProposal.value
  if (!proposal) return []
  const lines: { key: string; label: string; value: string }[] = []
  const repeated: [string, string[]][] = []
  for (const [fieldId, field] of Object.entries(proposal.proposedValues)) {
    if (field.cardinality === 'REPEATED') {
      repeated.push([fieldId, (field.values ?? []).map((value) => (field.type === 'DATE' ? formatDateLikeExport(value) : value))])
      continue
    }
    const value = field.value ?? ''
    lines.push({ key: fieldId, label: labelOf(fieldId), value: field.type === 'DATE' ? formatDateLikeExport(value) : value })
  }
  const rows = Math.max(0, ...repeated.map(([, values]) => values.length))
  for (let index = 0; index < rows; index++) {
    lines.push({
      key: `row-${index}`,
      label: `Row ${index + 1}`,
      value: repeated
        .map(([fieldId, values]) => `${labelOf(fieldId)}: ${values[index] ?? ''}`)
        .join('; '),
    })
  }
  return lines
})

async function acceptProposal(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const proposal = patchProposal.value
  if (workspaceId === undefined || !proposal || !document.value || applyStage.value === 'accepting') return

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
    const statuses = Object.entries(acceptResult.value.fieldStatuses)
    const applied = statuses.filter(([, status]) => status === 'CLEAN').length
    const held = statuses.filter(([, status]) => status !== 'CLEAN').map(([fieldId]) => labelOf(fieldId))
    const heldWords = held.length > 0 ? ` I left ${held.join(', ')} as ${held.length === 1 ? 'it was' : 'they were'}: locked, or changed since I read the document.` : ''
    if (applied === 0) {
      say({ from: 'brownie', text: `Nothing was filled in.${heldWords}` })
    } else {
      say({
        from: 'brownie',
        text: `Filled in ${applied} ${applied === 1 ? 'value' : 'values'}.${heldWords} Check each one before you export.`,
      })
    }
    if (proposalFromJobId.value !== null) decidedRunJobId.value = proposalFromJobId.value
    patchProposal.value = null
  } catch (error) {
    applyStage.value = 'proposed'
    if (error instanceof ApiRequestError && error.status === 412) {
      applyError.value = (await loadDocument())
        ? 'This document changed since I made this proposal, so it was reloaded. Ask me again on the current version.'
        : 'This document changed since I made this proposal, so nothing was filled in.'
      return
    }
    applyError.value = describeCommonFailure(error, 'a way to accept proposed changes') ?? 'Could not fill these in. Try again.'
  }
}

/** Not now: the proposal is set aside on this page; nothing on the document changed, and nothing is sent. */
function setProposalAside(): void {
  if (proposalFromJobId.value !== null) decidedRunJobId.value = proposalFromJobId.value
  patchProposal.value = null
  applyStage.value = 'idle'
  applyError.value = null
  say({ from: 'brownie', text: 'All right, I left the document as it is.' })
}

async function onProposalChoice(index: number): Promise<void> {
  if (index === 0) await acceptProposal()
  else setProposalAside()
  await keepFocusInChat()
}

/**
 * A choice pressed in the chat goes away with what it answered (a proposal, a question), which
 * would drop focus to the top of the page. It goes to the next question still open instead, or to
 * the message box.
 */
async function keepFocusInChat(prefer: 'proposal' | 'question' = 'proposal'): Promise<void> {
  await nextTick()
  // A result being turned into a proposal is about to take the place of what went; focus waits for it.
  if (applyStage.value === 'applying') return
  const active = window.document.activeElement
  if (active instanceof HTMLElement && active !== window.document.body && active.isConnected) return
  // Only when the control that held focus in the conversation went away, never when the person left it for the page.
  if (!lastChatFocus || lastChatFocus.isConnected) return
  const log = chatLogRef.value
  const offered = log?.querySelector<HTMLElement>('.proposal') ?? null
  const question = log?.querySelector<HTMLElement>('.question .choices__option, .question .choices__other-input') ?? null
  const status = log?.querySelector<HTMLElement>('.run-status') ?? null
  const failure = status?.querySelector('[role="alert"]') ? status : null
  const next =
    (prefer === 'question' ? (question ?? offered) : (offered ?? question)) ?? failure ?? window.document.getElementById('assist-composer')
  lastChatFocus = null
  next?.focus()
}

/** The control in the conversation that last had focus; null once focus moves anywhere else. */
let lastChatFocus: Element | null = null
function onPageFocusIn(event: FocusEvent): void {
  lastChatFocus = event.target instanceof Element && (chatLogRef.value?.contains(event.target) ?? false) ? event.target : null
}
onMounted(() => window.document.addEventListener('focusin', onPageFocusIn))
onBeforeUnmount(() => window.document.removeEventListener('focusin', onPageFocusIn))
// Whatever replaces a proposal, a question list or a button of the reading (Carry on, Stop, Try again, Check again) takes the focus it held.
watch(
  [proposalSlotId, patchProposal, extractionStage, applyStage, extractionStalled, canRetryRun, () => openQuestions.value.length],
  () => void keepFocusInChat(),
  { flush: 'post' },
)

function questionPrompt(question: QuestionResponse): string {
  const label = labelOf(question.fieldId)
  return question.reason === 'CONFLICT'
    ? `${label}: the source says something different from what the document holds. Which should it be?`
    : `${label}: I could not find this in the source. What should it be?`
}

// ---- Review and lock ---------------------------------------------------------------------------

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
    announce(`Row ${index + 1}: ${DECISION_WORDS[decision]}.`)
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
  const nextLock: FieldLock = rowLocked(index) ? 'EDITABLE' : 'EXPLICITLY_LOCKED'
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
    announce(nextLock === 'EXPLICITLY_LOCKED' ? `Row ${index + 1} locked.` : `Row ${index + 1} unlocked.`)
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not change the lock for row ${index + 1}. Try again.`)
  } finally {
    fieldActionPending.value = null
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
    announce(`${labelOf(fieldId)}: ${DECISION_WORDS[decision]}.`)
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not record a review decision for ${labelOf(fieldId)}. Try again.`)
  } finally {
    fieldActionPending.value = null
  }
}

async function toggleFieldLock(fieldId: string): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const currentLock = fieldStateOf(fieldId)?.lock
  if (workspaceId === undefined || !document.value || !currentLock) return

  const nextLock: FieldLock = currentLock === 'EXPLICITLY_LOCKED' ? 'EDITABLE' : 'EXPLICITLY_LOCKED'
  fieldActionPending.value = fieldId
  fieldActionError.value = null
  try {
    await setFieldLock(workspaceId, props.documentId, document.value.currentRevision.id, fieldId, nextLock, crypto.randomUUID())
    await loadDocument()
    announce(nextLock === 'EXPLICITLY_LOCKED' ? `${labelOf(fieldId)} locked.` : `${labelOf(fieldId)} unlocked.`)
  } catch (error) {
    if (await reloadedAfterStaleRevision(error)) return
    reportFieldActionFailure(error, `Could not change the lock for ${labelOf(fieldId)}. Try again.`)
  } finally {
    fieldActionPending.value = null
  }
}

/** The review buttons of the bar, for the selected fill spot or row. */
function reviewSelected(decision: ReviewDecision): void {
  const current = selected.value
  if (!current) return
  if (current.rowIndex === null) void recordFieldReview(current.fieldId, decision)
  else void recordRowReview(current.rowIndex, decision)
}

function toggleSelectedLock(): void {
  const current = selected.value
  if (!current) return
  if (current.rowIndex === null) void toggleFieldLock(current.fieldId)
  else void toggleRowLock(current.rowIndex)
}

/** What the bar's decisions are about, in the words of their accessible names: a field, or a whole row. */
const selectedTarget = computed(() => {
  const current = selected.value
  if (!current) return ''
  return current.rowIndex === null ? labelOf(current.fieldId) : `row ${current.rowIndex + 1}`
})

const selectedPendingKey = computed(() => {
  const current = selected.value
  if (!current) return null
  return current.rowIndex === null ? current.fieldId : `row-${current.rowIndex}`
})

// ---- Dialogs -----------------------------------------------------------------------------------

const exportDialogRef = ref<InstanceType<typeof ExportDialog> | null>(null)
const historyDialogRef = ref<InstanceType<typeof VersionHistoryDialog> | null>(null)

function openExport(): void {
  exportDialogRef.value?.open()
}

function openHistory(): void {
  historyDialogRef.value?.open()
}

async function goToFieldFromExport(fieldId: string): Promise<void> {
  exportDialogRef.value?.close()
  await focusField(fieldId)
}

// ---- Changing a PDF form's fill spots --------------------------------------------------------------
//
// On a PDF form the page view asks about each change (a box drawn, moved or restyled, a spot added,
// renamed or taken away) and sends it through here. Like a change on a Word page it makes a new version
// of the form and moves this document onto it with every value kept: typing not saved yet is saved
// first, and the chat says what was done with the same Undo.

const isPdfForm = computed(() => layout.value?.kind === 'PDF')
const pdfPageRef = ref<{
  openRename: (fieldId: string) => void
  openRestyle: (fieldId: string) => void
  openRemove: (fieldId: string) => void
} | null>(null)

/** The selected spot's first place on a PDF form's pages; null on a Word form, or for a field the pages have no place for. */
const selectedPdfSpot = computed(() => {
  const current = selected.value
  return current ? (layout.value?.pdf?.spots?.find((spot) => spot.fieldId === current.fieldId) ?? null) : null
})

/** A point picked on a PDF page while the message being written says "here". */
const pdfPageAnchor = ref<PdfPageAnchor | null>(null)
const composerSaysHere = computed(() => isPdfForm.value && /\bhere\b/i.test(composerText.value))
watch(composerSaysHere, (here) => {
  if (!here) pdfPageAnchor.value = null
})

const SPOT_CHANGE_DONE: Record<PdfSpotRequest['kind'], (label: string, count: number) => string> = {
  add: (label) => `Added a fill spot for ${label}. New documents from this form will have it too.`,
  rename: (label) => `Renamed the fill spot to ${label}. New documents from this form will have the new name too.`,
  restyle: (label) => `Changed how the text fits in ${label}. New documents from this form will have the change too.`,
  remove: (label) => `Removed the fill spot ${label}. Its value stays in the version history.`,
  move: (label, count) =>
    `${count === 1 ? `Moved the box for ${label}.` : `Moved ${count} boxes.`} New documents from this form will have the change too.`,
}

/** How Undo names a PDF change: moving or restyling a box is undone as a change to the spot. */
const PDF_UNDO_ACTION: Record<PdfSpotRequest['kind'], SpotUndo['action']> = {
  add: 'add',
  rename: 'rename',
  restyle: 'change',
  remove: 'remove',
  move: 'change',
}

async function changePdfSpots(request: PdfSpotRequest): Promise<PdfSpotResult> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return { ok: false, message: 'This document is not loaded yet. Try again in a moment.' }
  if (behindLatest.value) {
    spotChanges.offerNewerVersionAgain()
    return { ok: false, message: 'This document is on an older version of its form. Move it to the newest version first, then change its fill spots.' }
  }
  const unsaved = await saveTypingFirst()
  if (unsaved !== null) return { ok: false, message: unsaved }
  const current = document.value
  try {
    const result = await changeFillSpots(
      workspaceId,
      props.documentId,
      current.currentRevision.id,
      current.templateVersionId,
      request.changes,
      crypto.randomUUID(),
    )
    await reloadWithLayout()
    await nextTick()
    const fieldId = result.fieldIds?.[0] ?? null
    say({
      from: 'brownie',
      text: SPOT_CHANGE_DONE[request.kind](request.label, request.changes.length),
      undo: { action: PDF_UNDO_ACTION[request.kind], label: request.label, previousRevisionId: result.previousRevisionId, revisionId: result.revision.id },
    })
    if (request.kind === 'remove') {
      selected.value = null
      return { ok: true, focusId: 'document-pane' }
    }
    for (const change of request.changes) if (change.kind === 'RENAME' && change.fieldId) await foundSpots.keepRenamed(change.fieldId)
    return { ok: true, focusId: fieldId ? `edit-${fieldId}` : null }
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 412) {
      return {
        ok: false,
        message: (await loadDocument())
          ? 'This document changed since you loaded it, so it was reloaded. Try again on the current version.'
          : 'This document changed since you loaded it, so that was not done.',
      }
    }
    if (error instanceof ApiRequestError && error.problem?.code === 'TEMPLATE_VERSION_MOVED_ON') await loadDocument()
    if (error instanceof ApiRequestError && error.problem?.code === 'DOCUMENT_TEMPLATE_VERSION_MOVED') {
      // The form moved on since the page loaded: loading the document again learns of the newer version, which is offered.
      await loadDocument()
      spotChanges.offerNewerVersionAgain()
    }
    return { ok: false, message: spotChangeFailure(error, labelOf, request.kind === 'add' ? 'add' : 'change') }
  }
}

async function onVersionRestored(payload: { revision: DocumentRevisionResponse; keptLockedFieldIds: string[] }): Promise<void> {
  await loadDocument()
  const kept = payload.keptLockedFieldIds ?? []
  announce(
    `Version restored.${kept.length > 0 ? ` ${kept.map(labelOf).join(', ')} kept ${kept.length === 1 ? 'its' : 'their'} value because ${kept.length === 1 ? 'it is' : 'they are'} locked.` : ''}`,
  )
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

  <div v-else-if="document" class="workspace" :class="`workspace--showing-${narrowView}`">
    <header class="workspace-bar">
      <div class="workspace-bar__start">
        <!-- Not disabled while busy: that would drop the focus it holds. A second press is ignored instead. -->
        <button type="button" class="icon-button" :aria-disabled="undoing" @click="undoLastChange">
          <AppIcon name="undo" />
          <span class="visually-hidden">Undo the last change</span>
        </button>
        <button type="button" class="icon-button" @click="openHistory">
          <AppIcon name="history" />
          <span class="visually-hidden">Version history</span>
        </button>
      </div>
      <!-- Which face of the document the page shows; kept in the bar so the page itself starts at once. -->
      <div class="view-toggle" role="group" aria-label="Document view">
        <button type="button" class="view-toggle__button" :aria-pressed="docView === 'page'" @click="docView = 'page'">
          Page
        </button>
        <button type="button" class="view-toggle__button" :aria-pressed="docView === 'print'" @click="docView = 'print'">
          Print preview
        </button>
      </div>
      <div class="workspace-bar__end">
        <p ref="saveStatusRef" class="save-status" :class="`save-status--${saveStatus.tone}`" tabindex="-1">
          <span class="save-status__dot" aria-hidden="true"></span>
          <span>{{ saveStatus.text }}</span>
        </p>
        <button
          v-if="isDirty && saveStage !== 'conflict'"
          type="button"
          class="button button--secondary workspace-bar__save"
          :aria-disabled="saveStage === 'saving' || rowProblems.length > 0"
          @click="saveNow"
        >
          Save now
        </button>
        <button type="button" class="button button--primary workspace-bar__export" @click="openExport">Export</button>
      </div>
    </header>

    <h1 class="workspace-title">{{ document.title }}</h1>

    <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ liveMessage }}</p>

    <div class="workspace-notices">
      <p v-if="handoffWarning" class="field-error" role="alert">{{ handoffWarning }}</p>
      <p v-if="keepError" class="field-error" role="alert">{{ keepError }}</p>
      <p
        v-if="calendarConsent"
        ref="calendarConsentElement"
        :class="calendarConsent.tone === 'success' ? 'workspace-notice' : 'field-error'"
        :role="calendarConsent.tone === 'success' ? 'status' : 'alert'"
        tabindex="-1"
      >
        {{ calendarConsent.text }}
        <template v-if="calendarConsent.hint">{{ calendarConsent.hint }}</template>
      </p>
      <p v-if="reloadError" class="field-error" role="alert">
        {{ reloadError }}
        <RouterLink v-if="documentGone" to="/trash">Open the trash bin</RouterLink>
      </p>
      <p v-if="saveError" class="field-error" role="alert">
        {{ saveError }}
        <RouterLink v-if="documentGone && !reloadError" to="/trash">Open the trash bin</RouterLink>
      </p>
      <div v-if="saveStage === 'conflict'" class="field-error conflict-notice" role="alert">
        <!-- The revision on screen is only the current one when the reload after the conflict worked. -->
        <p v-if="reloadError">
          This document changed since you started editing, and its latest version could not be loaded, so nothing was
          saved. Your edits are still on the page.
        </p>
        <p v-else>
          This document changed since you started editing (version {{ document.currentRevision.revisionNumber }} is now
          current). Your edits are still on the page. Save them onto the latest version, or discard them to see what
          changed.
        </p>
        <div class="button-row">
          <button type="button" class="button button--primary" @click="saveEdits('manual')">Save my edits onto the latest</button>
          <button type="button" class="button button--secondary" @click="resetDrafts">Discard my edits</button>
        </div>
      </div>
      <div v-if="rowProblems.length > 0" class="field-error" role="alert">
        <ul class="plain-list">
          <li v-for="problem in rowProblems" :key="problem">{{ problem }}</li>
        </ul>
      </div>
      <p v-if="fieldActionError" class="field-error" role="alert">
        {{ fieldActionError }}
        <RouterLink v-if="documentGone && !reloadError" to="/trash">Open the trash bin</RouterLink>
      </p>
    </div>

    <!-- Only shown where the two panes cannot sit side by side. -->
    <div class="workspace-switch" role="group" aria-label="Show">
      <button
        type="button"
        class="workspace-switch__button"
        :aria-pressed="narrowView === 'document'"
        aria-controls="document-pane"
        @click="narrowView = 'document'"
      >
        Document
      </button>
      <button
        type="button"
        class="workspace-switch__button"
        :aria-pressed="narrowView === 'assistant'"
        aria-controls="assistant-pane"
        @click="showAssistant(false)"
      >
        Brownie
      </button>
    </div>

    <div class="workspace-panes">
      <!-- Focusable, so the scroll keys move the document where it scrolls on its own. -->
      <div
        id="document-pane"
        class="document-pane"
        role="region"
        aria-label="Document area"
        tabindex="0"
        @focusin="onDocumentFocusIn"
        @input="onDocumentInput"
      >
        <!--
          One short line over the page about the form: the places Brownie found while any is unchecked, with
          "Keep all", and the notes about the upload behind "Details". Not a live region: it holds its buttons,
          and the page's live line says once that the notes are there.
        -->
        <section v-if="docView === 'page' && stripText" class="form-strip" :class="{ 'form-strip--found': uncheckedFoundIds.length > 0 }" aria-label="About this document">
          <div class="form-strip__line">
            <p class="form-strip__text">{{ stripText }}</p>
            <div ref="stripButtonsRef" class="form-strip__buttons">
              <button
                v-if="uncheckedFoundIds.length > 0"
                type="button"
                class="button button--secondary form-strip__button"
                :aria-disabled="keepPending !== null"
                @click="keepAllFoundPlaces"
              >
                Keep all<span class="visually-hidden"> the places Brownie found</span>
              </button>
              <button
                v-if="stripDetailsShown"
                type="button"
                class="button button--secondary form-strip__button"
                :aria-expanded="formNotesOpen ? 'true' : 'false'"
                aria-controls="form-notes-list"
                @click="formNotesOpen = !formNotesOpen"
              >
                Details<span class="visually-hidden"> about this document</span>
              </button>
              <button
                v-if="formNotes.length > 0 && uncheckedFoundIds.length === 0"
                type="button"
                class="button button--secondary form-strip__button"
                @click="dismissFormNotes"
              >
                Dismiss<span class="visually-hidden"> the notes about this document</span>
              </button>
            </div>
          </div>
          <ul v-if="stripDetailsShown" v-show="formNotesOpen" id="form-notes-list" class="form-strip__notes">
            <li v-for="note in formNotes" :key="note">{{ note }}</li>
          </ul>
        </section>

        <!--
          Before anything is filled in, how the page gets filled: one quiet line once the page has fill spots, so
          the first of them stays in view, and a note of its own over a page that has none yet.
        -->
        <p v-if="docView === 'page' && !hasAnyValue && !isDirty && !pageLoading && editableFields.length > 0" class="empty-hint">
          <strong>Nothing filled in yet.</strong> Type into the highlighted spots, or
          <button type="button" class="link-button" @click="goToSources">add notes or a transcript</button> for Brownie to
          fill them in.
        </p>
        <div v-else-if="docView === 'page' && !hasAnyValue && !isDirty && !pageLoading" class="empty-state">
          <p class="empty-state__title">Nothing filled in yet.</p>
          <p class="field-hint">
            Type straight into the highlighted spots, or give Brownie your notes or a transcript and ask it to fill them
            in. You check every value before it is exported.
          </p>
          <button type="button" class="button button--secondary" @click="goToSources">Add notes or a transcript</button>
        </div>

        <!-- A newer version of this document's form (a fill spot added from another document): what it changes, and the move. -->
        <div v-if="docView === 'page' && newerVersionWords" class="version-banner">
          <p class="version-banner__text" role="status">{{ newerVersionWords }}</p>
          <p v-if="moveError" class="field-error version-banner__error" role="alert">{{ moveError }}</p>
          <div class="version-banner__buttons">
            <button
              ref="moveButtonRef"
              type="button"
              class="button button--primary"
              :aria-disabled="movingToLatest ? 'true' : undefined"
              @click="!movingToLatest && moveToNewestVersion()"
            >
              {{ movingToLatest ? 'Moving…' : 'Move this document to it' }}
            </button>
            <button type="button" class="button button--secondary" :aria-disabled="movingToLatest ? 'true' : undefined" @click="!movingToLatest && setNewerVersionAside()">
              Not now
            </button>
          </div>
        </div>
        <p v-if="docView === 'page' && shownMovedNotice" class="workspace-notice version-banner__moved">{{ shownMovedNotice }}</p>

        <!-- A PDF form is its own pages with the spots laid over them; a Word form is drawn from its text. -->
        <PdfFormPage
          v-if="isPdfForm && session.personalWorkspaceId !== undefined"
          v-show="docView === 'page'"
          ref="pdfPageRef"
          :layout="layout"
          :layout-state="pageLoading ? 'loading' : layoutState"
          :layout-problem="layoutProblem"
          :fields="editableFields"
          :drafts="drafts"
          :revision-fields="document.currentRevision.fields"
          :required-field-ids="requiredFieldIds"
          :locked-field-ids="lockedFieldIds"
          :rows-locked="rowsLocked"
          :selected="selected"
          :found-field-ids="uncheckedFoundSet"
          :workspace-id="session.personalWorkspaceId"
          :send-spot-changes="changePdfSpots"
          :picking-point="composerSaysHere"
          @update-scalar="updateScalar"
          @update-row="updateRow"
          @select="select"
          @open-actions="openActions"
          @add-row="addRow"
          @pick-point="pdfPageAnchor = $event"
        />
        <DocumentPage
          v-else
          v-show="docView === 'page'"
          :layout="layout"
          :layout-state="pageLoading ? 'loading' : layoutState"
          :layout-problem="layoutProblem"
          :fields="editableFields"
          :drafts="drafts"
          :revision-fields="document.currentRevision.fields"
          :required-field-ids="requiredFieldIds"
          :locked-field-ids="lockedFieldIds"
          :rows-locked="rowsLocked"
          :selected="selected"
          :found-field-ids="uncheckedFoundSet"
          :can-add-spots="docView === 'page' && !pageLoading && layoutState === 'ready' && anchorLines.length > 0"
          :fill-here-hidden="placeDialogRef?.isOpen === true"
          @update-scalar="updateScalar"
          @update-row="updateRow"
          @select="select"
          @open-actions="openActions"
          @add-row="addRow"
          @add-spot="openPlaceDialog"
          @fill-here="onFillHere"
          @place="onPagePlace"
          @words-selected="closeBarForSelectedWords"
        />

        <section v-if="docView === 'print'" class="print-preview" aria-labelledby="print-preview-heading">
          <h2 id="print-preview-heading" class="visually-hidden">Print preview</h2>
          <p v-if="previewStage === 'ready' && previewRevisionNumber != null" class="field-hint">
            The file as it exports, from version {{ previewRevisionNumber }}.
            <span v-if="previewIsStale">The document has changed since it was made.</span>
            <span v-else-if="isDirty">You have unsaved changes; they appear once saved and generated again.</span>
          </p>
          <p v-if="previewStage === 'idle'" class="field-hint">Generate a preview to see this document exactly as it exports.</p>
          <p v-if="previewStage === 'loading'" aria-live="polite">Checking for a preview…</p>
          <p v-if="previewStage === 'generating'" aria-live="polite">Generating the preview…</p>
          <p v-if="previewError" class="field-error" role="alert">{{ previewError }}</p>
          <div class="button-row">
            <button
              class="button button--secondary"
              type="button"
              :aria-disabled="previewStage === 'generating' || previewStage === 'loading'"
              @click="previewStage !== 'generating' && previewStage !== 'loading' && regeneratePreview()"
            >
              {{ previewStage === 'generating' ? 'Generating…' : previewArtifactId == null ? 'Generate preview' : 'Generate again' }}
            </button>
          </div>
          <PdfPreview :src="previewUrl" :label="`Preview of version ${previewRevisionNumber ?? ''}`" />
        </section>

        <!-- The selected fill spot's state and the decisions about it. At the foot of the page, where a finger or a pointer reaches it without covering the text above. -->
        <section
          v-if="selected && docView === 'page'"
          ref="selectionBarRef"
          class="selection-bar"
          data-covers-page
          :aria-label="`About ${selectedLabel}`"
          tabindex="-1"
          @keydown.esc.stop="returnToSelectedSpot"
        >
          <div class="selection-bar__head">
            <p class="selection-bar__name">{{ selectedLabel }}</p>
            <button type="button" class="icon-button" @click="clearSelection">
              <AppIcon name="close" :size="18" />
              <span class="visually-hidden">Close the bar about {{ selectedLabel }}</span>
            </button>
          </div>
          <p v-if="selectedState || selectedRequired" class="selection-bar__chips">
            <span v-if="selectedRequired" class="badge">Required</span>
            <span v-for="chip in selectedState ? fieldStateWords(selectedState) : []" :key="chip" class="badge">{{ chip }}</span>
          </p>
          <p v-if="!selectedState && selectedHint" class="field-hint">{{ selectedHint }}</p>
          <!-- The place itself, as Brownie found it: "Keep" in the row about the fill spot says it is right. -->
          <p v-if="uncheckedFoundSet.has(selected.fieldId)" class="selection-bar__found">
            <span class="badge">Found by Brownie</span> Check that this is the right place.
          </p>
          <div
            v-if="evidenceOpenFor === selected.fieldId"
            :id="`evidence-${selected.fieldId}`"
            class="evidence-panel"
            role="region"
            :aria-label="`Where ${selectedLabel} came from`"
            tabindex="0"
          >
            <p v-if="evidenceStage === 'loading'" aria-live="polite">Loading the cited excerpt…</p>
            <p v-else-if="evidenceStage === 'failed'" class="field-error" role="alert">{{ evidenceError }}</p>
            <template v-else>
              <blockquote v-for="excerpt in evidenceExcerpts" :key="excerpt.spanId" class="evidence-excerpt">
                <p>{{ excerpt.excerptText }}</p>
                <footer class="field-hint">From {{ excerpt.displayFilename ?? 'an attached source' }}</footer>
              </blockquote>
              <p v-if="selected.rowIndex !== null" class="field-hint">
                These are the passages cited for {{ labelOf(selected.fieldId) }} in every row, not only row {{ selected.rowIndex + 1 }}.
              </p>
              <p v-if="evidenceSpanIdsOf(selected.fieldId).length > MAX_EVIDENCE_EXCERPTS" class="field-hint">
                Showing the first {{ MAX_EVIDENCE_EXCERPTS }} of {{ evidenceSpanIdsOf(selected.fieldId).length }} cited passages.
              </p>
              <p class="field-hint">Brownie shows where a value came from; it cannot point to where it lands in the exported file.</p>
            </template>
          </div>
          <!--
            The buttons in rows, each named for what its buttons change, so "Keep" (this place is right) is never
            read as "Accept" (this value is right). The fill spot: keeping a place Brownie found, its name, on a box
            Brownie drew how its text fits, or the spot taken away, each asked about in a dialog; kept while the page
            loads a new version, so focus can come back to them after a change. The value: its review and its lock,
            and where it came from. A row of a table: moving or removing it.
          -->
          <div v-if="spotRowShown" class="selection-bar__group" role="group" aria-labelledby="selection-bar-spot-label">
            <span id="selection-bar-spot-label" class="selection-bar__group-label">This fill spot:</span>
            <span class="selection-bar__group-buttons">
              <button
                v-if="uncheckedFoundSet.has(selected.fieldId)"
                type="button"
                class="button button--secondary"
                :aria-disabled="keepPending !== null"
                @click="keepSelectedPlace"
              >
                Keep<span class="visually-hidden"> this fill spot</span>
              </button>
              <template v-if="spotActionsShown">
                <button type="button" class="button button--secondary" @click="openRename">
                  Rename…<span class="visually-hidden">{{ ` ${labelOf(selected.fieldId)}` }}</span>
                </button>
                <button v-if="selectedPdfSpot?.bindingKind === 'PAGE_BOX'" type="button" class="button button--secondary" @click="openRestyle">
                  Text size…<span class="visually-hidden">{{ ` for ${labelOf(selected.fieldId)}` }}</span>
                </button>
                <button v-if="isPdfForm || selectedField?.cardinality === 'SCALAR'" type="button" class="button button--secondary" @click="openRemove">
                  Remove…<span class="visually-hidden">{{ ` ${labelOf(selected.fieldId)}` }}</span>
                </button>
              </template>
            </span>
          </div>
          <p v-if="isPdfForm && selectedPdfSpot?.bindingKind === 'ACROFORM_FIELD'" class="field-hint">
            This box belongs to the PDF’s own form, so the form sets where it is and how its text looks.
          </p>
          <div v-if="valueRowShown" class="selection-bar__group" role="group" aria-labelledby="selection-bar-value-label">
            <span id="selection-bar-value-label" class="selection-bar__group-label">This value:</span>
            <span class="selection-bar__group-buttons">
              <template v-if="selectedState || selectedRowHasState">
                <button class="button button--secondary" type="button" :aria-disabled="fieldActionPending === selectedPendingKey" @click="fieldActionPending !== selectedPendingKey && reviewSelected('ACCEPTED')">
                  Accept<span class="visually-hidden">{{ ` ${selectedTarget}` }}</span>
                </button>
                <button class="button button--secondary" type="button" :aria-disabled="fieldActionPending === selectedPendingKey" @click="fieldActionPending !== selectedPendingKey && reviewSelected('REJECTED')">
                  Reject<span class="visually-hidden">{{ ` ${selectedTarget}` }}</span>
                </button>
                <button
                  v-if="selected.rowIndex === null"
                  class="button button--secondary"
                  type="button"
                  :aria-disabled="fieldActionPending === selectedPendingKey"
                  @click="fieldActionPending !== selectedPendingKey && reviewSelected('NEEDS_CLARIFICATION')"
                >
                  Needs clarification<span class="visually-hidden"> for {{ selectedTarget }}</span>
                </button>
                <button class="button button--secondary" type="button" :aria-disabled="fieldActionPending === selectedPendingKey" @click="fieldActionPending !== selectedPendingKey && toggleSelectedLock()">
                  {{ selectedLocked ? 'Unlock' : 'Lock' }}<span class="visually-hidden">{{ ` ${selectedTarget}` }}</span>
                </button>
              </template>
              <button
                v-if="evidenceSpanIdsOf(selected.fieldId).length > 0"
                class="button button--secondary"
                type="button"
                :aria-expanded="evidenceOpenFor === selected.fieldId"
                :aria-controls="`evidence-${selected.fieldId}`"
                @click="toggleEvidence(selected.fieldId)"
              >
                Where it came from ({{ evidenceSpanIdsOf(selected.fieldId).length }})
              </button>
            </span>
          </div>
          <div v-if="selected.rowIndex !== null" class="selection-bar__group" role="group" aria-labelledby="selection-bar-row-label">
            <span id="selection-bar-row-label" class="selection-bar__group-label">This row:</span>
            <span class="selection-bar__group-buttons">
              <button class="button button--secondary" type="button" :aria-disabled="rowsLocked || selected.rowIndex === 0" @click="!rowsLocked && selected.rowIndex > 0 && moveRow(selected.rowIndex, -1)">
                Move up<span class="visually-hidden"> row {{ selected.rowIndex + 1 }}</span>
              </button>
              <button
                class="button button--secondary"
                type="button"
                :aria-disabled="rowsLocked || selected.rowIndex === rowCount - 1"
                @click="!rowsLocked && selected.rowIndex < rowCount - 1 && moveRow(selected.rowIndex, 1)"
              >
                Move down<span class="visually-hidden"> row {{ selected.rowIndex + 1 }}</span>
              </button>
              <button class="button button--secondary" type="button" :aria-disabled="rowsLocked" @click="!rowsLocked && selected && removeRow(selected.rowIndex!)">
                Remove row {{ selected.rowIndex + 1 }}
              </button>
            </span>
          </div>
          <div class="button-row selection-bar__rules">
            <button type="button" class="button button--secondary" @click="showAssistant()">Text style and rules</button>
          </div>
          <p v-if="selected.rowIndex !== null && rowsLocked" class="field-hint">A row is locked, so the rows cannot be changed until it is unlocked.</p>
          <p class="field-hint selection-bar__keys">{{ actionsKey }} in a fill spot comes to this bar; Escape goes back to the spot.</p>
        </section>
      </div>

      <aside id="assistant-pane" class="assistant-pane" aria-labelledby="assistant-heading">
        <h2 id="assistant-heading" ref="assistantHeadingRef" class="visually-hidden" tabindex="-1">Chat with Brownie</h2>

        <!-- Everything above the message box; on a wide page it scrolls on its own when it cannot all fit. -->
        <div class="assistant-pane__scroll">
          <!-- Focusable, so the scroll keys move the card where a short window makes it scroll on its own. -->
          <section ref="rulesCardRef" class="rules-card" aria-labelledby="rules-heading" tabindex="0" @scroll.passive="measureRulesCard">
            <div ref="rulesCardBodyRef" class="rules-card__body">
              <div class="rules-card__head">
                <h3 id="rules-heading" class="rules-card__heading">Rules</h3>
                <button
                  v-if="aboutThisForm.length > 0"
                  type="button"
                  class="button button--secondary rules-card__about"
                  :aria-expanded="aboutThisFormOpen ? 'true' : 'false'"
                  aria-controls="about-this-form"
                  @click="aboutThisFormOpen = !aboutThisFormOpen"
                >
                  About this form
                </button>
              </div>
              <ul v-if="aboutThisForm.length > 0" v-show="aboutThisFormOpen" id="about-this-form" class="rules-card__about-notes">
                <li v-for="note in aboutThisForm" :key="note">{{ note }}</li>
              </ul>
              <div class="rules-card__style">
                <span class="rules-card__glyph" aria-hidden="true">Aa</span>
                <div>
                  <p class="rules-card__caption">
                    Text style <span aria-hidden="true">·</span> {{ selected ? selectedLabel : 'Fill spots' }}
                  </p>
                  <ul v-if="styleChips.length > 0" class="chip-list" aria-label="Text style">
                    <li v-for="chip in styleChips" :key="chip" class="chip">{{ chip }}</li>
                  </ul>
                  <p v-else class="field-hint">
                    {{
                      selectedPdfSpot?.bindingKind === 'ACROFORM_FIELD'
                        ? 'The PDF’s own form sets how this box’s text looks.'
                        : layoutState === 'loading' && !layoutProblem
                          ? 'Reading the template’s style…'
                          : 'The template’s text style could not be read.'
                    }}
                  </p>
                  <p v-if="dateExample" class="rules-card__date">
                    Dates read like <span class="chip">{{ dateExample }}</span>
                  </p>
                </div>
              </div>
              <p v-if="styleChips.length > 0" class="field-hint rules-card__source">How values look when exported, taken from the template.</p>
              <p v-if="!selected && requiredLabels.length > 0" class="rules-card__required">
                <span class="rules-card__required-mark" aria-hidden="true">*</span> Required before export: {{ requiredLabels.join(', ') }}.
              </p>
              <p v-if="rulesLoadState === 'loading'" class="field-hint" aria-live="polite">Loading rules…</p>
              <p v-else-if="rulesLoadState === 'error'" class="field-hint">
                The template's rules could not be loaded.
                <button type="button" class="link-button" @click="loadRules">Try again</button>
              </p>
              <template v-else-if="rulesLoadState === 'loaded'">
                <ul v-if="shownRules.length > 0" class="rules-card__rules">
                  <li v-for="rule in shownRules" :key="rule.id">
                    {{ ruleWords(rule) }}
                    <span v-if="rule.humanExplanation" class="field-hint"> {{ rule.humanExplanation }}</span>
                  </li>
                </ul>
                <p v-else class="field-hint">
                  {{
                    selected
                      ? `No rule of this template is about ${selectedLabel}.`
                      : requiredLabels.length > 0
                        ? 'The template has no other rules.'
                        : 'The template has no rules.'
                  }}
                </p>
                <!-- Nothing in the app decides a suggested rule any more: say it does not apply, and promise no step to take. -->
                <p v-if="rulesUndecided > 0" class="field-hint">
                  {{
                    rulesUndecided === 1
                      ? '1 suggested rule was never turned on, so it does not apply.'
                      : `${rulesUndecided} suggested rules were never turned on, so they do not apply.`
                  }}
                </p>
              </template>
            </div>
            <!-- Seen, not heard: a screen reader reads the whole card whether or not it is scrolled. -->
            <div v-if="rulesCardMore" class="rules-card__more" aria-hidden="true">
              <span class="rules-card__more-label" @click="scrollRulesCard">More <span class="rules-card__more-arrow">&#8595;</span></span>
            </div>
          </section>

          <!-- Focusable so a keyboard can scroll the conversation even when it holds no button to land on. -->
          <div ref="chatLogRef" class="chat" role="region" aria-label="Conversation with Brownie" tabindex="0">
            <ul v-if="attachedSources.length > 0" class="source-cards" aria-label="Sources">
              <li v-for="source in attachedSources" :key="source.id" class="source-card">
                <span class="source-card__tile" aria-hidden="true"><AppIcon name="document" :size="18" /></span>
                <span class="source-card__body">
                  <span class="source-card__name">{{ sourceName(source) }}</span>
                  <span class="source-card__meta">
                    {{ sourceKindWords(source) }}
                    <span v-if="attachedSources.length > 1 && source.id === selectedSource?.id"> · Brownie reads this one</span>
                  </span>
                  <span v-if="originSentence(source)" class="source-card__meta">
                    {{ originSentence(source) }}
                    <a v-if="originLink(source)" :href="originLink(source) ?? undefined" target="_blank" rel="noopener noreferrer"
                      >{{ originLinkLabel(source) }}<span class="visually-hidden"> (opens in a new tab)</span></a
                    >
                  </span>
                </span>
              </li>
            </ul>

            <div class="chat-line chat-line--brownie">
              <p>
                I can fill this document from your notes, a transcript or a conversation. Add one with Add a source (+),
                then ask me to fill it in. You can also type straight into the highlighted spots, and change anything I fill in.
              </p>
            </div>

            <template v-for="line in chat" :key="line.id">
              <div v-if="!line.slot" class="chat-line" :class="`chat-line--${line.from}`">
                <blockquote v-if="line.quote" class="chat-line__quote"><p>{{ line.text }}</p></blockquote>
                <p v-else :class="{ 'field-error': line.tone === 'error' }">{{ line.text }}</p>
                <ul v-if="line.help && line.help.length > 0" class="chat-suggestions" aria-label="What you can ask">
                  <li v-for="item in line.help" :key="item">
                    <button type="button" class="chat-suggestion" @click="useSuggestion(item)">{{ item }}</button>
                  </li>
                </ul>
                <ChoiceList
                  v-if="line.choices && line.choices.length > 0 && !line.chosen"
                  :options="line.choices.map((choice) => choice.lineText)"
                  name="The paragraph the fill spot goes in"
                  :id-prefix="`place-choice-${line.id}`"
                  :allow-other="false"
                  :busy="assistBusy"
                  @choose="(index) => chooseLineFor(line, index)"
                />
                <div v-if="line.undo" class="chat-line__actions">
                  <button
                    type="button"
                    class="button button--secondary"
                    :aria-disabled="line.undone || undoingSpotChange ? 'true' : undefined"
                    @click="undoSpotChange(line)"
                  >
                    {{ line.undone ? 'Undone' : 'Undo' }}<span class="visually-hidden">{{ ` ${UNDO_WHAT[line.undo.action]} ${line.undo.label}` }}</span>
                  </button>
                </div>
              </div>
              <!-- A reading of a source, from start to proposal. -->
              <div
                v-else-if="line.slot === 'run' && line.id === runSlotId && extractionStage !== 'idle'"
                class="chat-line chat-line--brownie run-status"
                tabindex="-1"
              >
                <template v-if="extractionStage === 'starting'">
                  <p>Starting to read {{ runSourceName }}…</p>
                </template>
                <template v-else-if="extractionStage === 'running'">
                  <p aria-live="polite">{{ runProgress ?? `Reading ${runSourceName ?? 'your source'}…` }}</p>
                  <p v-if="noWorkerYet" class="field-error">
                    No worker has picked this up yet. If the Brownie worker is not running, the reading waits until it is; you
                    can stop it and start it again once the worker is running.
                  </p>
                  <button class="button button--secondary" type="button" :aria-disabled="cancellationRequested" @click="cancelExtraction">
                    {{ cancellationRequested ? 'Stopping…' : 'Stop reading' }}
                  </button>
                </template>
                <template v-else-if="extractionStage === 'waiting-for-input' || extractionStage === 'resuming'">
                  <p>A few things need your answer before I can finish.</p>
                  <div v-for="question in openQuestions" :key="question.id" class="question">
                    <p class="question__prompt">{{ questionPrompt(question) }}</p>
                    <p v-if="question.status === 'ANSWERED'" class="field-hint">You answered: {{ question.answerValue }}</p>
                    <ChoiceList
                      v-else
                      :options="question.candidates.map((candidate) => candidate.value)"
                      :name="`Your answer for ${labelOf(question.fieldId)}`"
                      :id-prefix="`question-${question.id}`"
                      :other-label="question.candidates.length > 0 ? 'Other' : 'Your answer'"
                      other-placeholder="Type the answer…"
                      :clear-on-send="false"
                      :busy="answeringQuestionId !== null || extractionStage === 'resuming'"
                      @choose="(index) => answerWith(question.id, question.candidates[index]!.value)"
                      @other="(text) => answerWith(question.id, text)"
                    />
                  </div>
                  <p v-if="extractionStage === 'resuming'" aria-live="polite">Carrying on with your answers…</p>
                  <button
                    v-else-if="openQuestions.length > 0 && openQuestions.every((question) => question.status === 'ANSWERED')"
                    class="button button--primary"
                    type="button"
                    @click="resumeAfterAnswers"
                  >
                    Carry on reading
                  </button>
                  <button class="button button--secondary" type="button" :aria-disabled="cancellationRequested" @click="cancelExtraction">
                    {{ cancellationRequested ? 'Stopping…' : 'Stop reading' }}
                  </button>
                </template>
                <template v-else-if="extractionStage === 'succeeded' && !patchProposal">
                  <p>I finished reading {{ runSourceName ?? 'your source' }}.</p>
                  <p v-if="applyError" class="field-error" role="alert">{{ applyError }}</p>
                  <button
                    class="button button--secondary"
                    type="button"
                    :aria-disabled="applyStage === 'applying'"
                    @click="applyStage !== 'applying' && applyResultToDocument()"
                  >
                    {{
                      applyStage === 'applying'
                        ? 'Getting the values ready…'
                        : decidedRunJobId === extractionJobId
                          ? 'Show what I found again'
                          : 'Show what I found'
                    }}
                  </button>
                </template>
                <template v-else-if="extractionStage === 'cancelled'">
                  <p>I stopped reading. Nothing on the document changed.</p>
                </template>
                <template v-else-if="extractionStage === 'failed'">
                  <p class="field-error" role="alert">{{ extractionError ?? 'The reading stopped before it could finish.' }}</p>
                  <button v-if="canRetryRun" class="button button--secondary" type="button" :aria-disabled="retryingRun" @click="!retryingRun && retryRun()">
                    {{ retryingRun ? 'Starting again…' : 'Try this reading again' }}
                  </button>
                </template>
                <p v-if="extractionError && extractionStage !== 'failed'" class="field-error">{{ extractionError }}</p>
                <div v-if="extractionStalled">
                  <p>{{ stalledMessage }}</p>
                  <button class="button button--secondary" type="button" @click="checkRunAgain">Check again</button>
                </div>
              </div>

              <!-- A proposal, from a reading or from a request: nothing changes until it is approved. -->
              <div
                v-else-if="line.slot === 'proposal' && line.id === proposalSlotId && patchProposal && applyStage !== 'idle'"
                class="chat-line chat-line--brownie proposal"
                tabindex="-1"
              >
                <p>{{ proposalIntro }}</p>
                <ul class="proposal__values">
                  <li v-for="value in proposalLines" :key="value.key">
                    <span class="proposal__label">{{ value.label }}:</span> {{ value.value }}
                  </li>
                </ul>
                <div v-if="patchProposal.skippedRepeatedItems.length > 0" class="proposal__skipped" role="status">
                  <p>
                    I also found {{ patchProposal.skippedRepeatedItems.length }}
                    {{ patchProposal.skippedRepeatedItems.length === 1 ? 'row' : 'rows' }} I could not complete, because a detail was
                    missing. Fill {{ patchProposal.skippedRepeatedItems.length === 1 ? 'it' : 'them' }} in yourself before exporting:
                  </p>
                  <ul>
                    <li v-for="skipped in patchProposal.skippedRepeatedItems" :key="skipped.itemIndex">
                      {{ skipped.description ?? `Row ${skipped.itemIndex + 1}` }}
                      <span class="field-hint">(missing: {{ skipped.unresolvedFieldIds.map(labelOf).join(', ') }})</span>
                    </li>
                  </ul>
                </div>
                <p>Can you confirm? I will fill in the document once you approve it.</p>
                <ChoiceList
                  :options="['I approve, fill it in', 'Not now']"
                  name="Your decision about these values"
                  id-prefix="proposal"
                  other-label="Other"
                  other-placeholder="Type what you would prefer…"
                  :busy="applyStage === 'accepting'"
                  @choose="onProposalChoice"
                  @other="(text) => sendMessage(text)"
                />
                <p v-if="applyStage === 'accepting'" aria-live="polite">Filling in…</p>
                <p v-if="applyError" class="field-error" role="alert">{{ applyError }}</p>
              </div>
            </template>
            <p v-if="assistStage === 'interpreting' || assistStage === 'executing'" class="chat-line chat-line--brownie chat-typing" aria-live="polite">
              Working on it…
            </p>
          </div>

          <div v-if="sourcesOpen" id="add-source-panel" class="add-source">
            <div class="add-source__head">
              <p class="add-source__title">Add a source</p>
              <button type="button" class="icon-button" @click="closeSources">
                <AppIcon name="close" :size="18" />
                <span class="visually-hidden">Close adding a source</span>
              </button>
            </div>
            <label class="field-label" for="attach-source">Upload notes or a transcript</label>
            <input id="attach-source" type="file" accept=".txt,text/plain" :disabled="sourceUploadState === 'uploading'" @change="onSourceFileChosen" />
            <p class="field-hint">
              Plain text (.txt)<span v-if="uploadLimit">, up to {{ uploadLimit }}</span>. Brownie reads plain-text sources.
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
            <DriveSourcePicker
              v-if="session.personalWorkspaceId !== undefined"
              :workspace-id="session.personalWorkspaceId"
              :document-id="documentId"
              :start-open="drivePickerStartsOpen"
              :unsaved-work="hasUnsavedWork()"
              :copy-file="copyDriveFile"
              @used="calendarConsent = null"
            />
          </div>
        </div>

        <form class="composer" @submit.prevent="sendMessage()">
          <!-- The question the box shows is also its name, so speech input can reach it by the words on screen. -->
          <label class="visually-hidden" for="assist-composer">How may I help you?</label>
          <textarea
            id="assist-composer"
            v-model="composerText"
            class="composer__input"
            rows="2"
            maxlength="1000"
            placeholder="How may I help you?"
            :aria-describedby="hereHint ? 'assist-composer-here' : undefined"
            @keydown="onComposerKeydown"
          ></textarea>
          <p v-if="hereHint" id="assist-composer-here" class="composer__here">{{ hereHint }}</p>
          <div class="composer__tools">
            <button
              ref="addSourceButtonRef"
              type="button"
              class="icon-button composer__add"
              :aria-expanded="sourcesOpen"
              aria-controls="add-source-panel"
              @click="toggleSources"
            >
              <AppIcon name="plus" />
              <span class="visually-hidden">Add a source</span>
            </button>
            <label v-if="attachedSources.length > 1" class="composer__source">
              <span class="composer__source-label">Read</span>
              <select id="extract-source" v-model.number="selectedSourceId" :disabled="runUnderWay">
                <option v-for="source in attachedSources" :key="source.id" :value="source.id">{{ sourceName(source) }}</option>
              </select>
            </label>
            <button type="submit" class="composer__send" :aria-disabled="composerText.trim() === '' || assistBusy">
              <AppIcon name="send" :size="18" />
              <span class="visually-hidden">Send</span>
            </button>
          </div>
        </form>
        <p class="assistant-disclaimer">Brownie can make mistakes. Check each filled value before you export.</p>
      </aside>
    </div>

    <ExportDialog
      v-if="session.personalWorkspaceId !== undefined"
      ref="exportDialogRef"
      :workspace-id="session.personalWorkspaceId"
      :document-id="documentId"
      :document-title="document.title"
      :current-revision-id="document.currentRevision.id"
      :unsaved-work="hasUnsavedWork()"
      :save-before-export="saveBeforeExport"
      :reload-document="loadDocument"
      :field-label="labelOf"
      :editable-field-ids="editableFieldIds"
      :suggested-event-date="suggestedEventDate"
      :pdf-only="isPdfForm"
      @go-to-field="goToFieldFromExport"
    />
    <VersionHistoryDialog
      v-if="session.personalWorkspaceId !== undefined"
      ref="historyDialogRef"
      :workspace-id="session.personalWorkspaceId"
      :document-id="documentId"
      :current-revision="document.currentRevision"
      :field-label="labelOf"
      :unsaved-work="hasUnsavedWork()"
      @restored="onVersionRestored"
      @document-changed="loadDocument"
    />
    <PlaceSpotDialog ref="placeDialogRef" :lines="anchorLines" :send="sendNewSpot" @added="onSpotAdded" />
    <RenameSpotDialog ref="renameDialogRef" :send="sendRename" />
    <RemoveSpotDialog ref="removeDialogRef" :send="sendRemove" @removed="onSpotRemoved" />
  </div>
</template>

<style scoped>
/*
 * The page is two panes: the document, and Brownie's panel. They sit side by side only where both
 * keep a readable width -- the question is how wide this page's own region is, not the window,
 * since the app's sidebar beside it can be showing or collapsed. Below that, a switch shows one
 * at a time. 50rem is a narrow but readable page (about 30rem) plus the panel's minimum (18rem)
 * and the gap between them, which keeps a laptop with the sidebar open at two panes.
 *
 * The shell pads its pages for reading; this one reaches nearer the window's edges, as the design
 * draws it, and shares its first row with the shell's floating sidebar button when there is one.
 */
.workspace {
  display: flex;
  flex-direction: column;
  margin-block-start: calc(var(--space-3) - var(--shell-gutter-block-start, var(--space-5)) - var(--shell-bar-block, 0px));
  margin-inline: calc(var(--space-4) - var(--shell-gutter-inline, var(--space-5)));
}

/* The top bar: Undo and version history at the start; how the document is shown, the save status and Export at the end. */
.workspace-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  min-block-size: var(--icon-button-size);
  /* Leaves the corner the shell's floating sidebar button covers, so the two read as one row. */
  padding-inline-start: max(0px, calc(var(--shell-bar-inline, 0px) - var(--space-4)));
}

.workspace-bar__start,
.workspace-bar__end {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.workspace-bar__start {
  gap: var(--space-1);
  margin-inline-end: auto;
}

.workspace-bar__end {
  gap: var(--space-3);
}

.workspace-bar__export,
.workspace-bar__save {
  min-block-size: 2rem;
  padding-inline: var(--space-4);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
}

.save-status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: 0.8125rem;
  color: var(--color-text-muted);
}

.save-status__dot {
  inline-size: 0.375rem;
  block-size: 0.375rem;
  border-radius: 50%;
  background: var(--color-success);
}

.save-status--pending .save-status__dot {
  background: var(--color-honey);
}

.save-status--problem {
  color: var(--color-error);
}

.save-status--problem .save-status__dot {
  background: var(--color-error);
}

/* The document's name, large and light, as the design sets it; it scales with the room the page has. */
.workspace-title {
  margin: var(--space-1) 0 var(--space-4);
  text-align: center;
  font-weight: 400;
  font-size: clamp(1.75rem, 1rem + 2.2cqi, 3rem);
  line-height: 1.2;
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.workspace-notices {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-block-end: var(--space-3);
}

.workspace-notices:empty {
  display: none;
}

.workspace-notices p {
  margin: 0;
}

/* News about an uploaded form: quiet, and out of the way of the page it sits over. */
/*
 * One short line over the page about the form. While it holds places Brownie found that are still to check it
 * takes their marks' honey colours; once only notes are left it is a plain card. Its words carry it either way.
 */
.form-strip {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius);
  background: var(--color-surface);
  font-size: 0.8125rem;
}

.form-strip--found {
  border-color: var(--color-honey-line);
  background: var(--color-honey-faint);
}

.form-strip__line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-2);
}

.form-strip__text {
  flex: 1 1 14rem;
  margin: 0;
}

.form-strip__buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.form-strip__button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  font-size: 0.8125rem;
}

.form-strip__notes {
  margin: var(--space-2) 0 0;
  padding-inline-start: 1.25rem;
}

.form-strip__notes li + li {
  margin-block-start: var(--space-1);
}

/* A newer version of the form: what it changes, and the move to it. */
.version-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-block-end: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  font-size: var(--font-size-sm);
}

.version-banner__text {
  flex: 1 1 14rem;
  margin: 0;
}

.version-banner__error {
  flex: 1 1 100%;
  margin: 0;
}

.version-banner__buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.version-banner__buttons .button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  font-size: 0.8125rem;
}

.version-banner__moved {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-sm);
}

.chat-line__actions {
  display: flex;
  gap: var(--space-2);
  margin-block-start: var(--space-2);
}

.chat-line__actions .button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  font-size: 0.8125rem;
}

.composer__here {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.selection-bar__found {
  margin: 0;
  font-size: var(--font-size-sm);
}

.workspace-notice {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

.conflict-notice {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.button-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.plain-list {
  margin: 0;
  padding-inline-start: var(--space-5);
}

.link-button {
  border: 0;
  padding: 0;
  background: none;
  color: var(--color-focus);
  text-decoration: underline;
  cursor: pointer;
  text-align: start;
}

/*
 * Two small two-way switches: which face of the document shows (in the bar), and, where the panes
 * cannot sit side by side, which pane shows.
 */
.workspace-switch,
.view-toggle {
  display: inline-flex;
  padding: 2px;
  border-radius: var(--radius-pill);
  background: var(--color-paper-deep);
}

/* The switch between the panes: only where they cannot sit side by side. */
.workspace-switch {
  display: none;
  align-self: center;
  margin-block-end: var(--space-3);
}

.workspace-switch__button,
.view-toggle__button {
  min-block-size: 1.75rem;
  padding: 0 var(--space-3);
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--color-text-muted);
  cursor: pointer;
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    color var(--motion-fast) var(--motion-ease);
}

.workspace-switch__button:hover,
.view-toggle__button:hover {
  color: var(--color-text);
}

/* The chosen one is outlined as well as lifted, so it stands out from the track at 3:1 or more. */
.workspace-switch__button[aria-pressed='true'],
.view-toggle__button[aria-pressed='true'] {
  border-color: var(--color-cocoa);
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 1px 2px rgb(42 41 36 / 0.08);
}

/* Forced colours draw every edge alike, so the chosen one takes the system's own selected colours instead. */
@media (forced-colors: active) {
  .workspace-switch__button[aria-pressed='true'],
  .view-toggle__button[aria-pressed='true'] {
    forced-color-adjust: none;
    background: SelectedItem;
    color: SelectedItemText;
  }
}

.workspace-panes {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--space-5);
  align-items: start;
}

.workspace-panes > * {
  min-inline-size: 0;
}

@container main (min-width: 50rem) {
  .workspace-title {
    margin-block-end: var(--space-6);
  }

  /* The page and Brownie's panel share the width about evenly, as the design has them, set in a little from the window's edges. */
  .workspace-panes {
    grid-template-columns: minmax(0, 1fr) minmax(18rem, 1fr);
    column-gap: var(--space-6);
    padding-inline: clamp(0rem, 4cqi - 1.5rem, 2.5rem);
  }

  /*
   * Never taller than the screen. If its parts cannot all fit, what is above the message box scrolls
   * and the message box stays below it, outside the scrolling part, so nothing focused can sit under
   * it. The padding keeps focus rings (a forced-colours outline reaches 5px) inside the scrolling edge.
   */
  .assistant-pane {
    position: sticky;
    inset-block-start: var(--space-3);
    max-block-size: calc(100dvh - var(--space-5));
  }

  .assistant-pane__scroll {
    overflow-y: auto;
    padding: 6px;
    margin: -6px;
    scrollbar-width: thin;
    scrollbar-color: var(--color-scrollbar) transparent;
  }

  .add-source {
    max-block-size: 40dvh;
  }

  .selection-bar__rules {
    display: none;
  }

  /*
   * Where the window is tall enough, the page fills it exactly, as the design draws it: the bar and the
   * title stay put, the document scrolls in its own pane, and Brownie's panel keeps the message box at
   * the foot of the screen from the moment the page opens. The shell's own bar, where it is a row above
   * this page, is left its height. Shorter windows scroll as one page.
   */
  @media (min-height: 36rem) {
    .workspace {
      block-size: calc(100dvh - var(--shell-bar-row, 0px) - 2 * var(--space-3));
      margin-block-end: calc(var(--space-3) - var(--shell-gutter-block-end, var(--space-8)));
    }

    .workspace-panes {
      flex: 1 1 auto;
      min-block-size: 0;
      grid-template-rows: minmax(0, 1fr);
      align-items: stretch;
    }

    .assistant-pane {
      position: static;
      max-block-size: none;
      min-block-size: 0;
    }

    /*
     * The Rules card gives way before the conversation does, scrolling on its own in a short window; the
     * conversation's own length never decides how much of the room it gets.
     */
    .assistant-pane__scroll > .rules-card {
      flex: 0 1 auto;
      min-block-size: 0;
      overflow-y: auto;
    }

    .assistant-pane__scroll > .chat {
      flex: 1 1 0;
    }

    /* The padding keeps focus rings, and the outline of a selected fill spot, inside the scrolling edge. */
    .document-pane {
      min-block-size: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
      padding: 6px 6px var(--space-4);
      margin: -6px -6px 0;
      scrollbar-width: thin;
      scrollbar-color: var(--color-scrollbar) transparent;
    }
  }
}

@container main (max-width: 49.999rem) {
  .workspace-switch {
    display: inline-flex;
  }

  .workspace--showing-document .assistant-pane,
  .workspace--showing-assistant .document-pane {
    display: none;
  }

  /*
   * The bar keeps Undo and the save status on its first line, and puts the switch between the page and
   * the print preview on a line of its own under them, rather than letting the three wrap wherever
   * they happen to break. The empty last item forces the break.
   */
  .workspace-bar {
    row-gap: 0;
  }

  /* Pushed to the end of its line, which is the first line or, where it wraps, a line of its own. */
  .workspace-bar__end {
    margin-inline-start: auto;
  }

  .workspace-bar::after {
    content: '';
    order: 1;
    flex-basis: 100%;
  }

  .view-toggle {
    order: 2;
    margin-block-start: var(--space-2);
  }

  /* The face of the document means nothing while Brownie's panel is the one showing. */
  .workspace--showing-assistant .view-toggle {
    display: none;
  }

  .assistant-pane {
    min-block-size: min(40rem, calc(100dvh - 12rem));
  }
}

/*
 * Positioned, like the conversation and the part of Brownie's panel that scrolls, so the text kept for
 * assistive technology inside it (placed out of sight, but still placed) is clipped where the pane
 * scrolls instead of stretching the window below it.
 */
.document-pane {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* Before anything is filled in on a page with fill spots: one quiet line saying how it gets filled. */
.empty-hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 0.8125rem;
}

.empty-hint strong {
  color: var(--color-text);
  font-weight: 600;
}

/* Before anything is filled in on a page with no fill spots yet: a quiet note over it, saying how it gets filled. */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: 0.75rem;
  background: var(--color-surface);
  font-size: var(--font-size-sm);
}

.empty-state p {
  margin: 0;
}

.empty-state__title {
  font-weight: 600;
}

.empty-state .field-hint {
  color: var(--color-text-muted);
  font-size: 0.8125rem;
}

.empty-state .button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-pill);
  font-size: 0.8125rem;
}

.print-preview {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.print-preview p {
  margin: 0;
}

.print-preview .button {
  min-block-size: 2rem;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
}

/* The bar about the selected fill spot stays in reach at the foot of the screen while the page scrolls under it. */
.selection-bar {
  container: selection-bar / inline-size;
  position: sticky;
  inset-block-end: var(--space-3);
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  box-shadow: 0 8px 28px rgb(42 41 36 / 0.1);
}

/* An outline, not a shadow, so forced colours keep it. */
.selection-bar:focus-visible {
  outline: 2px solid var(--color-cocoa);
  outline-offset: 2px;
}

.selection-bar .button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
  font-weight: 500;
}

.selection-bar .field-hint {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--color-text-muted);
}

.selection-bar .selection-bar__keys {
  font-size: var(--font-size-xs);
}

/* On a touch screen there is no key to press. */
@media (pointer: coarse) {
  .selection-bar__keys {
    display: none;
  }
}

/*
 * A row of the bar's buttons with the name of what they change before them. The names share a width, so
 * the rows' buttons start in line, and buttons that wrap stay under the first; in a narrow bar the name
 * sits over its buttons instead. The buttons are a little narrower here, so a row fits on one line.
 */
.selection-bar__group {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  align-items: center;
  gap: var(--space-1) var(--space-2);
}

.selection-bar__group-label {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: 600;
}

.selection-bar__group-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.selection-bar__group .button {
  padding-inline: 0.625rem;
}

@container selection-bar (max-width: 24rem) {
  .selection-bar__group {
    grid-template-columns: minmax(0, 1fr);
  }
}

.selection-bar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.selection-bar__name {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.selection-bar__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  margin: 0;
}

.badge {
  display: inline-block;
  padding: 1px var(--space-2);
  border-radius: var(--radius-pill);
  background: var(--color-honey-soft);
  font-size: var(--font-size-xs);
  font-weight: 600;
}

.evidence-panel {
  padding: var(--space-3);
  border-inline-start: 3px solid var(--color-honey);
  background: var(--color-honey-soft);
  border-radius: var(--radius);
  max-block-size: 14rem;
  overflow-y: auto;
  font-size: var(--font-size-sm);
}

.evidence-panel p {
  margin: 0 0 var(--space-1);
}

.evidence-excerpt {
  margin: 0 0 var(--space-2);
}

/* Brownie's panel: the Rules card, the conversation, and the message box. */
.assistant-pane {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-block-size: 0;
}

.assistant-pane__scroll {
  position: relative;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-block-size: 0;
}

.rules-card {
  flex: none;
  padding: var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  font-size: 0.8125rem;
}

.rules-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  margin-block-end: var(--space-3);
}

.rules-card__heading {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.rules-card__about {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
}

/* The notes about the upload the form came from, between the heading and the text style. */
.rules-card__about-notes {
  margin: 0 0 var(--space-3);
  padding-inline-start: var(--space-5);
}

.rules-card__about-notes li + li {
  margin-block-start: var(--space-1);
}

/* The text style, on a tinted panel inside the card: the "Aa" tile, a caption, and the style as chips. */
.rules-card__style {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border-radius: 0.75rem;
  background: var(--color-paper);
}

.rules-card__glyph {
  flex: none;
  display: inline-grid;
  place-items: center;
  inline-size: 1.75rem;
  block-size: 1.75rem;
  border-radius: 0.375rem;
  background: var(--color-surface);
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--color-cocoa);
}

.rules-card__style p {
  margin: 0;
}

.rules-card__style .rules-card__caption {
  margin-block-end: var(--space-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

/* Read-only: white with a thin edge, as the design draws them, and no hover, since pressing one does nothing. */
.chip {
  display: inline-block;
  padding: 3px var(--space-2);
  border: 1px solid var(--color-hairline);
  border-radius: 0.375rem;
  background: var(--color-surface);
  font-size: var(--font-size-xs);
  font-weight: 500;
  line-height: 1.5;
}

.rules-card__style .rules-card__date {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-2);
  margin-block-start: var(--space-2);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.rules-card__required {
  color: var(--color-text);
}

.rules-card__required-mark {
  font-weight: 700;
  color: var(--color-error);
}

.rules-card__rules {
  margin: var(--space-2) 0 0;
  padding-inline-start: var(--space-5);
}

.rules-card__body > p {
  margin: var(--space-2) 0 0;
}

/*
 * While more of the card is below its foot: a fade over the last words and a "More" hint, so a card cut off by
 * a short window says so. It sits at the foot of the card as it scrolls, takes no room of its own, and lets
 * every press through but the hint's own.
 */
.rules-card__more {
  position: sticky;
  inset-block-end: calc(-1 * var(--space-4));
  display: flex;
  align-items: flex-end;
  justify-content: center;
  block-size: 2.75rem;
  margin: -2.75rem calc(-1 * var(--space-4)) calc(-1 * var(--space-4));
  padding-block-end: var(--space-1);
  border-radius: 0 0 1rem 1rem;
  background: linear-gradient(to bottom, transparent, var(--color-surface) 70%);
  pointer-events: none;
}

.rules-card__more-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0 var(--space-2);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: 600;
  line-height: 1.6;
  cursor: pointer;
  pointer-events: auto;
}

.rules-card .field-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

/* The conversation: what the person said on the right in light bubbles, Brownie's replies as plain text on the left. */
.chat {
  position: relative;
  flex: 1 1 auto;
  min-block-size: 8rem;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: 0 var(--space-3) var(--space-2) var(--space-1);
  scrollbar-width: thin;
  scrollbar-color: var(--color-scrollbar) transparent;
}

/*
 * Earlier lines fade out at the top of the conversation instead of being cut through. The fade stays
 * at the top while the lines scroll under it, takes no room of its own (the negative margin cancels the
 * gap after it), and lets every press through.
 */
.chat::before {
  content: '';
  position: sticky;
  inset-block-start: 0;
  z-index: 1;
  flex: none;
  block-size: var(--space-3);
  margin-block-end: calc(-1 * var(--space-4));
  background: linear-gradient(to bottom, var(--color-paper), transparent);
  pointer-events: none;
}

.chat-line p {
  margin: 0;
}

.chat-line--person {
  align-self: flex-end;
  max-inline-size: 85%;
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-bubble-border);
  border-radius: 0.375rem;
  background: var(--color-bubble);
  overflow-wrap: anywhere;
}

.chat-line--brownie {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  align-items: flex-start;
  overflow-wrap: anywhere;
}

.chat-line--brownie .button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-sm);
}

.chat-line__quote {
  margin: 0;
  padding-inline-start: var(--space-3);
  border-inline-start: 3px solid var(--color-hairline);
}

/* What Brownie can do, as one card of rows, like the numbered choices. */
.chat-suggestions {
  list-style: none;
  margin: 0;
  padding: var(--space-1);
  display: flex;
  flex-direction: column;
  gap: 2px;
  inline-size: 100%;
  max-inline-size: 35rem;
  border: 1px solid var(--color-hairline);
  border-radius: 0.75rem;
  background: var(--color-surface);
}

.chat-suggestion {
  inline-size: 100%;
  min-block-size: 2.5rem;
  padding: var(--space-2) var(--space-3);
  border: 0;
  border-radius: 0.5rem;
  background: transparent;
  text-align: start;
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.chat-suggestion:hover,
.chat-suggestion:focus-visible {
  background: var(--color-paper);
}

.chat-typing {
  color: var(--color-text-muted);
}

.run-status,
.proposal {
  inline-size: 100%;
}

.question {
  inline-size: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.question__prompt {
  font-weight: 500;
}

.proposal__values {
  margin: 0;
  padding-inline-start: var(--space-5);
}

.proposal__label {
  font-weight: 500;
}

.proposal__skipped {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-honey-soft);
}

.proposal__skipped ul {
  margin: var(--space-1) 0 0;
  padding-inline-start: var(--space-5);
}

.proposal :deep(.choices),
.question :deep(.choices) {
  inline-size: 100%;
  max-inline-size: 35rem;
}

/* The sources attached to the document, on the person's side, each as a file card. */
.source-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-2);
}

.source-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  max-inline-size: 90%;
  padding: var(--space-2) var(--space-3) var(--space-2) var(--space-2);
  border: 1px solid var(--color-hairline);
  border-radius: 0.625rem;
  background: var(--color-surface);
}

.source-card__tile {
  flex: none;
  display: inline-grid;
  place-items: center;
  inline-size: 1.875rem;
  block-size: 1.875rem;
  border-radius: 0.375rem;
  background: var(--color-cocoa-tile);
  color: var(--color-surface);
}

.source-card__body {
  display: flex;
  flex-direction: column;
  min-inline-size: 0;
  line-height: 1.35;
}

.source-card__name {
  font-size: 0.8125rem;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.source-card__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

/* Opened to be used, so it keeps its height (up to a cap on a wide page) and the conversation gives way to it. */
.add-source {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  overflow-y: auto;
  font-size: var(--font-size-sm);
}

.add-source p {
  margin: 0;
}

.add-source__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.add-source__title {
  font-weight: 600;
}

/* The message box: a rounded card with the question as its placeholder, + at its start and Send at its end. */
.composer {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-3) var(--space-3) var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: 1.125rem;
  background: var(--color-surface);
  transition: border-color var(--motion-fast) var(--motion-ease);
}

.composer:focus-within {
  border-color: var(--color-cocoa);
}

/* Forced colours make every border the same system colour, so focus is outlined in the system's own focus colour. */
@media (forced-colors: active) {
  .composer:focus-within {
    outline: 2px solid Highlight;
    outline-offset: 2px;
  }
}

.composer__input {
  inline-size: 100%;
  min-block-size: 3rem;
  max-block-size: 10rem;
  border: 0;
  padding: 0;
  background: transparent;
  resize: none;
  field-sizing: content;
}

.composer__input::placeholder {
  color: var(--color-text-muted);
}

.composer__input:focus-visible {
  box-shadow: none;
  outline: none;
}

.composer__tools {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-inline-start: calc(-1 * var(--space-2));
}

/* The design's + is drawn heavier than the other icons, so the one way to add a source stands out. */
.composer__add {
  color: var(--color-text);
}

.composer__add :deep(.app-icon) {
  stroke-width: 2.4;
}

.composer__source {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  min-inline-size: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

.composer__source select {
  min-inline-size: 0;
  max-inline-size: 12rem;
}

.composer__send {
  margin-inline-start: auto;
  display: inline-grid;
  place-items: center;
  inline-size: 2.25rem;
  block-size: 2.25rem;
  border: 1px solid var(--color-cocoa);
  border-radius: 50%;
  background: var(--color-cocoa);
  color: var(--color-surface);
  cursor: pointer;
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    border-color var(--motion-fast) var(--motion-ease);
}

.composer__send:hover:not([aria-disabled='true']) {
  border-color: var(--color-cocoa-strong);
  background: var(--color-cocoa-strong);
}

.composer__send[aria-disabled='true'] {
  border-color: var(--color-bubble-border);
  background: var(--color-bubble-border);
  color: var(--color-text-muted);
  cursor: default;
}

.assistant-disclaimer {
  flex: none;
  margin: 0;
  text-align: center;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

@media (pointer: coarse) {
  .composer__send {
    inline-size: 2.75rem;
    block-size: 2.75rem;
  }
}
</style>
