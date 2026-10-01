import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { RouterView, createRouter, createWebHistory, type Router } from 'vue-router'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    acceptPatchProposal: vi.fn(),
    allocateUpload: vi.fn(),
    answerQuestion: vi.fn(),
    applyGenerationResult: vi.fn(),
    approveExport: vi.fn(),
    attachDocumentSource: vi.fn(),
    cancelJob: vi.fn(),
    compileRevision: vi.fn(),
    completeUpload: vi.fn(),
    executeAssist: vi.fn(),
    exportDocument: vi.fn(),
    extractArtifact: vi.fn(),
    getCapabilities: vi.fn(),
    getDocument: vi.fn(),
    getDocumentRevision: vi.fn(),
    getEvidenceExcerpt: vi.fn(),
    getExtractionResult: vi.fn(),
    getGenerationQuestions: vi.fn(),
    getJob: vi.fn(),
    getLatestCompilation: vi.fn(),
    getLatestExportApproval: vi.fn(),
    getLatestExportReceipt: vi.fn(),
    getLatestValidation: vi.fn(),
    getTemplateLayout: vi.fn(),
    getTemplateVersion: vi.fn(),
    importCalendarEvent: vi.fn(),
    importDriveFile: vi.fn(),
    interpretAssist: vi.fn(),
    listActions: vi.fn(),
    listCalendarEvents: vi.fn(),
    listConnections: vi.fn(),
    listDocumentRevisions: vi.fn(),
    listDocumentSources: vi.fn(),
    listGenerationRuns: vi.fn(),
    listTemplateVersionRules: vi.fn(),
    patchDocumentContent: vi.fn(),
    recordReviewDecision: vi.fn(),
    restoreRevision: vi.fn(),
    resumeGeneration: vi.fn(),
    retryJob: vi.fn(),
    setFieldLock: vi.fn(),
    startExtraction: vi.fn(),
    startGoogleConsent: vi.fn(),
    uploadArtifactContent: vi.fn(),
    validateDocument: vi.fn(),
  }
})

// Leaving for Google's consent page is never followed in these tests.
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

// PDF.js needs a canvas and a worker jsdom does not have; the preview component has its own tests.
vi.mock('@/components/PdfPreview.vue', () => ({
  // The workspace loads this component on demand, so the mock must look like a real ES module to Vue's async loader.
  __esModule: true,
  default: { name: 'PdfPreview', props: ['src', 'label'], template: '<div data-testid="pdf-preview">{{ src ?? "" }}</div>' },
}))

import {
  ApiRequestError,
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  approveExport,
  attachDocumentSource,
  cancelJob,
  compileRevision,
  completeUpload,
  executeAssist,
  exportDocument,
  extractArtifact,
  getCapabilities,
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
  getTemplateLayout,
  getTemplateVersion,
  importCalendarEvent,
  importDriveFile,
  interpretAssist,
  listActions,
  listCalendarEvents,
  listConnections,
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
  startGoogleConsent,
  uploadArtifactContent,
  validateDocument,
} from '@/api/client'
import type {
  AssistExecutionResponse,
  AssistInterpretationResponse,
  CalendarEventsResponse,
  CommandReceiptResponse,
  CompilationManifestResponse,
  ConnectionResponse,
  DocumentResponse,
  DocumentRevisionResponse,
  DocumentSourceResponse,
  FieldStateResponse,
  GenerationRunResponse,
  JobResponse,
  PatchAcceptResponse,
  PatchProposalResponse,
  QuestionResponse,
  TemplateLayoutBlockResponse,
  TemplateLayoutInlineResponse,
  TemplateLayoutResponse,
  TemplateLayoutStyleResponse,
  TemplateVersionResponse,
  ValidationManifestResponse,
} from '@/api/client'
import { axe } from '@/test/axe'
import { documentHandoffState, type DocumentHandoff } from '@/router/handoff'
import { resetCapabilitiesCache } from '@/capabilities'
import { formatDateLikeExport } from '@/workspace/layout'

const API = [
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  approveExport,
  attachDocumentSource,
  cancelJob,
  compileRevision,
  completeUpload,
  executeAssist,
  exportDocument,
  extractArtifact,
  getCapabilities,
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
  getTemplateLayout,
  getTemplateVersion,
  importCalendarEvent,
  importDriveFile,
  interpretAssist,
  listActions,
  listCalendarEvents,
  listConnections,
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
  startGoogleConsent,
  uploadArtifactContent,
  validateDocument,
] as unknown as Mock[]

// ---- The template: a layout shaped like the built-in "Flowing meeting minutes" --------------------
//
// Liberation Sans throughout, bold 11 pt labels and regular placeholders, a 16 pt title, 13 pt
// section headings, a logo, a header line and a "Page 1" footer, and the action items as one
// numbered paragraph that the filler repeats once per item.

const BODY: TemplateLayoutStyleResponse = { fontFamily: 'Liberation Sans', fontSizeHalfPoints: 22, colorHex: '000000' }
const LABEL: TemplateLayoutStyleResponse = { ...BODY, bold: true }
const TITLE: TemplateLayoutStyleResponse = { ...LABEL, fontSizeHalfPoints: 32 }
const SECTION: TemplateLayoutStyleResponse = { ...LABEL, fontSizeHalfPoints: 26 }

function text(value: string, style: TemplateLayoutStyleResponse = BODY): TemplateLayoutInlineResponse {
  return { kind: 'TEXT', text: value, style }
}

function spot(fieldId: string, placeholder: string, style: TemplateLayoutStyleResponse = BODY): TemplateLayoutInlineResponse {
  return { kind: 'FILL_SPOT', fieldId, placeholder, style }
}

function paragraph(inlines: TemplateLayoutInlineResponse[], extra: Partial<TemplateLayoutBlockResponse> = {}): TemplateLayoutBlockResponse {
  return { kind: 'PARAGRAPH', alignment: null, listLevel: null, repeating: false, inlines, ...extra }
}

function flowingMinutes(titleStyle: TemplateLayoutStyleResponse = BODY): TemplateLayoutResponse {
  return {
    templateId: 1,
    versionId: 1,
    parserVersion: 'brownie-docx-graph-v2+poi-5.5.1',
    parts: [
      {
        kind: 'MAIN_DOCUMENT',
        blocks: [
          paragraph([{ kind: 'IMAGE' }]),
          paragraph([text('Meeting Minutes', TITLE)], { alignment: 'START' }),
          paragraph([text('Title: ', LABEL), spot('meeting.title', '[meeting title]', titleStyle)]),
          paragraph([text('Date: ', LABEL), spot('meeting.date', '[date]')]),
          paragraph([text('Location: ', LABEL), spot('meeting.location', '[location]')]),
          paragraph([text('Attendees: ', LABEL), spot('meeting.attendees', '[attendees]')]),
          paragraph([text('Decisions', SECTION)], { alignment: 'START' }),
          paragraph([spot('meeting.decisions', '[decisions]')]),
          paragraph([text('Action Items', SECTION)], { alignment: 'START' }),
          paragraph(
            [
              text('Task: '),
              spot('action.item.task', '[task]'),
              text('   Owner: '),
              spot('action.item.owner', '[owner]'),
              text('   Due: '),
              spot('action.item.due', '[due date]'),
            ],
            { listLevel: 0, repeating: true },
          ),
        ],
      },
      { kind: 'HEADER', blocks: [paragraph([text('Brownie Meeting Minutes Template', LABEL)])] },
      { kind: 'FOOTER', blocks: [paragraph([text('Page 1')])] },
    ],
    unplacedFieldIds: [],
  }
}

const FLOWING_MINUTES = flowingMinutes()

const MINUTES_TEMPLATE_VERSION: TemplateVersionResponse = {
  id: 1,
  templateId: 1,
  versionNumber: 1,
  sourceArtifactId: 10,
  extractionVersionId: 11,
  status: 'ACTIVATED',
  fields: [
    { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'REQUIRED', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'meeting.title' },
    { fieldId: 'meeting.date', type: 'DATE', cardinality: 'SCALAR', requiredness: 'REQUIRED', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'meeting.date' },
    { fieldId: 'meeting.location', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'meeting.location' },
    { fieldId: 'meeting.attendees', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'meeting.attendees' },
    { fieldId: 'meeting.decisions', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'meeting.decisions' },
    { fieldId: 'action.item.task', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'action.item.task' },
    { fieldId: 'action.item.owner', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'action.item.owner' },
    { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'action.item.due' },
  ],
  createdAt: '2026-03-01T00:00:00Z',
  activatedAt: '2026-03-01T00:00:00Z',
}

// ---- Documents ---------------------------------------------------------------------------------

const CREATED_AT = '2026-03-01T00:00:00Z'
const HASH_A = 'a'.repeat(64)
const HASH_B = 'b'.repeat(64)
const HASH_C = 'c'.repeat(64)

const TYPED_STATE: FieldStateResponse = { authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'NOT_RUN', review: 'UNREVIEWED', lock: 'EDITABLE' }
const IMPORTED_STATE: FieldStateResponse = { authorship: 'IMPORTED', evidenceSupport: 'DIRECT', validation: 'NOT_RUN', review: 'UNREVIEWED', lock: 'EDITABLE' }

function revision(overrides: Partial<DocumentRevisionResponse> = {}): DocumentRevisionResponse {
  return { id: 1, revisionNumber: 1, fields: {}, contentHash: HASH_A, editReason: 'created', createdAt: CREATED_AT, ...overrides }
}

function documentAt(current: DocumentRevisionResponse): DocumentResponse {
  return { id: 1, title: 'Weekly Sync', templateId: 1, templateVersionId: 1, currentRevisionId: current.id, createdAt: CREATED_AT, currentRevision: current }
}

type Fields = DocumentRevisionResponse['fields']

function scalarField(value: string, fieldState: FieldStateResponse | null = TYPED_STATE, type: 'TEXT' | 'DATE' = 'TEXT'): Fields[string] {
  return { type, cardinality: 'SCALAR', value, evidenceSourceSpanIds: [], fieldState }
}

function rowField(values: string[], type: 'TEXT' | 'DATE' = 'TEXT', states: (FieldStateResponse | null)[] = values.map(() => ({ ...TYPED_STATE }))): Fields[string] {
  return { type, cardinality: 'REPEATED', values, evidenceSourceSpanIds: [], itemFieldStates: states }
}

const DOCUMENT = documentAt(revision())

const DOCUMENT_WITH_A_SCALAR_FIELD = documentAt(revision({ fields: { 'meeting.title': scalarField('Weekly Sync', IMPORTED_STATE) } }))

const ROW_FIELDS: Fields = {
  'meeting.title': scalarField('Garden Club Planning'),
  'action.item.task': rowField(['Order seedlings']),
  'action.item.owner': rowField(['Maria Lopez']),
  'action.item.due': rowField(['2026-04-20'], 'DATE'),
}

const DOCUMENT_WITH_A_ROW = documentAt(revision({ id: 3, revisionNumber: 3, fields: ROW_FIELDS }))

const DOCUMENT_WITH_TWO_ROWS = documentAt(
  revision({
    id: 3,
    revisionNumber: 3,
    fields: {
      'meeting.title': scalarField('Garden Club Planning'),
      'action.item.task': rowField(['Order seedlings', 'Book the hall']),
      'action.item.owner': rowField(['Maria Lopez', 'Sam Ortiz']),
      'action.item.due': rowField(['2026-04-20', '2026-04-25'], 'DATE'),
    },
  }),
)

// ---- Runs, proposals and the other answers the page reads --------------------------------------

function jobResponse(state: string, overrides: Partial<JobResponse> = {}): JobResponse {
  return {
    id: 42,
    type: 'generation.extract-facts',
    resourceType: 'document',
    resourceId: 1,
    resourceVersion: 1,
    stage: 'extracting',
    state,
    attemptCount: 1,
    availableAt: CREATED_AT,
    deadlineAt: '2026-03-01T01:00:00Z',
    createdAt: CREATED_AT,
    updatedAt: CREATED_AT,
    ...overrides,
  }
}

function generationRun(jobState: string, overrides: Partial<GenerationRunResponse> = {}): GenerationRunResponse {
  return {
    id: 77,
    jobId: 42,
    documentId: 1,
    baseRevisionId: 1,
    sourceSnapshotId: 3,
    sourceArtifactId: 5,
    modelName: 'gpt-6-luna',
    promptVersion: 'extraction-v1',
    createdAt: CREATED_AT,
    job: jobResponse(jobState),
    resultArtifactId: null,
    ...overrides,
  }
}

function accepted(jobId = 42): CommandReceiptResponse {
  return { commandId: 'c1', jobId, operation: 'generation.start-extraction', status: 'ACCEPTED', acceptedAt: CREATED_AT }
}

const CONFLICT_QUESTION: QuestionResponse = {
  id: 9,
  fieldId: 'meeting.title',
  reason: 'CONFLICT',
  candidates: [
    { value: 'Old Title', evidenceSpanIds: [] },
    { value: 'Weekly Robotics Club Sync', evidenceSpanIds: [1] },
  ],
  status: 'OPEN',
  answerValue: null,
  createdAt: CREATED_AT,
}

const MISSING_DATE_QUESTION: QuestionResponse = {
  id: 10,
  fieldId: 'meeting.date',
  reason: 'MISSING_REQUIRED',
  candidates: [{ value: '2026-03-12', evidenceSpanIds: [] }],
  status: 'OPEN',
  answerValue: null,
  createdAt: CREATED_AT,
}

type Proposed = PatchProposalResponse['proposedValues'][string]

function proposedScalar(value: string, type: 'TEXT' | 'DATE' = 'TEXT'): Proposed {
  return { type, cardinality: 'SCALAR', value, values: null, evidenceSpanIds: [] }
}

function proposedRows(values: string[], type: 'TEXT' | 'DATE' = 'TEXT'): Proposed {
  return { type, cardinality: 'REPEATED', value: null, values, evidenceSpanIds: [] }
}

function proposalOf(proposedValues: PatchProposalResponse['proposedValues'], overrides: Partial<PatchProposalResponse> = {}): PatchProposalResponse {
  return {
    id: 501,
    documentId: 1,
    baseRevisionId: 1,
    proposedValues,
    status: 'PROPOSED',
    createdAt: CREATED_AT,
    proposedRepeatedItemCount: 0,
    skippedRepeatedItems: [],
    ...overrides,
  }
}

function acceptedProposal(fieldStatuses: PatchAcceptResponse['fieldStatuses']): PatchAcceptResponse {
  return { applied: true, fieldStatuses, revision: revision({ id: 2, revisionNumber: 2, contentHash: HASH_B }) }
}

const DRAFT: AssistInterpretationResponse = {
  kind: 'DRAFT',
  summary: 'Fill this document from an attached source.',
  scope: null,
  executable: true,
  usesModel: true,
  help: [],
}

const CHANGE_TITLE: AssistInterpretationResponse = {
  kind: 'CHANGE_FIELD',
  summary: 'Change Meeting title to "Spring Planning".',
  scope: { fieldId: 'meeting.title', label: 'Meeting title', currentValue: 'Weekly Sync', findingMessage: null },
  executable: true,
  usesModel: false,
  help: [],
}

const TITLE_CHANGED: AssistExecutionResponse = {
  kind: 'CHANGE_FIELD',
  summary: 'Change Meeting title to "Spring Planning".',
  explanation: null,
  help: [],
  proposal: proposalOf({ 'meeting.title': proposedScalar('Spring Planning') }, { id: 33 }),
}

// What the server says it can do, word for word.
const HELP = [
  'Fill this in from my notes: I read the source you attached and propose a value for each field.',
  'Change <field> to <value>: I propose that value for one field, for example "change meeting title to Spring Planning".',
  'Shorten <field> or rewrite <field> to <how>: I propose a shorter or reworded version of a text field that already has a value.',
  "Explain this finding: I explain, in plain words, a problem that Export's check found.",
]

const NOT_RECOGNISED: AssistInterpretationResponse = {
  kind: 'NONE',
  summary: 'I did not understand that, or it names a fill spot this document does not have. Here is what I can do:',
  scope: null,
  executable: false,
  usesModel: false,
  help: HELP,
}

const COMPILATION: CompilationManifestResponse = {
  id: 11,
  documentId: 1,
  revisionId: 1,
  templateId: 1,
  templateVersionId: 1,
  docxArtifactId: 20,
  docxSha256: HASH_B,
  pdfArtifactId: 21,
  pdfSha256: HASH_C,
  rendererVersion: 'test-renderer',
  integrityFindings: [],
  allIntegrityChecksPassed: true,
  compiledAt: CREATED_AT,
}

function manifest(overrides: Partial<ValidationManifestResponse> = {}): ValidationManifestResponse {
  return {
    id: 501,
    documentId: 1,
    revisionId: 1,
    templateId: 1,
    templateVersionId: 1,
    docxArtifactId: 900,
    docxSha256: HASH_C,
    pdfArtifactId: 901,
    pdfSha256: 'd'.repeat(64),
    findings: [],
    hasUnresolvedBlocking: false,
    createdAt: '2026-03-02T00:00:00Z',
    ...overrides,
  }
}

const ONE_SOURCE: DocumentSourceResponse[] = [
  { id: 3, artifactId: 5, displayFilename: 'notes.txt', kind: 'ARTIFACT', fetchedAt: CREATED_AT, attachedAt: CREATED_AT },
]

const TEN_MEGABYTE_LIMIT = {
  maxUploadBytes: 10485760,
  uploadMediaTypes: [{ mediaType: 'text/plain', extension: 'txt' }],
  assistSourceMediaTypes: ['text/plain'],
  templateMediaTypes: [],
  trashRetentionDays: 30,
}

/** A refusal from a Brownie server, carrying its own explanation. */
function refusal(status: number, code: string, detail?: string): ApiRequestError {
  return new ApiRequestError(status, { status, title: 'Refused', code, detail, correlationId: 'c', fields: [], recoveryActions: [] })
}

/** How a server that predates a route answers for it. */
function noSuchRoute(path: string): ApiRequestError {
  return refusal(404, 'NOT_FOUND', `No static resource ${path}.`)
}

/** What fetch throws when no answer came back at all. */
function networkFailure(): TypeError {
  return new TypeError('Failed to fetch')
}

/** "Nothing on record yet": a plain 404 with no explanation. */
function nothingOnRecord(): ApiRequestError {
  return new ApiRequestError(404, undefined)
}

const DOCUMENT_GONE = refusal(404, 'NOT_FOUND', 'No document 1 in this workspace.')
const RENDERER_BUSY = refusal(503, 'RENDERER_BUSY', 'Too many documents are being prepared right now. Nothing was changed; try again shortly.')
const RATE_LIMITED = refusal(429, 'RATE_LIMITED', 'Too many requests in a short time. Wait 12 seconds and try again.')
const STALE = refusal(412, 'STALE_REVISION', 'Revision moved on.')

// ---- Mounting ----------------------------------------------------------------------------------
//
// The page is mounted the way the app shows it, through the router's view inside the app's <main>,
// so that the guard it sets on leaving the route is live and its landmarks sit where they do at
// runtime. Every page is attached to the document: the page finds fill spots and the composer by
// element id and moves keyboard focus, and jsdom only does both for connected nodes.

const Host = defineComponent({ name: 'Host', render: () => h('main', { id: 'main-content' }, [h(RouterView)]) })
const Blank = defineComponent({ render: () => h('div') })

/** Stand-ins for the Google panels inside Export, which have tests of their own. */
const DriveStub = defineComponent({
  name: 'DriveSavePanel',
  props: ['workspaceId', 'documentId', 'receipt', 'unsavedWork'],
  render: () => h('div', { class: 'drive-stub' }),
})
const CalendarStub = defineComponent({
  name: 'CalendarEventPanel',
  props: ['workspaceId', 'documentId', 'documentTitle', 'suggestedDate', 'unsavedWork'],
  render: () => h('div', { class: 'calendar-stub' }),
})

type Page = VueWrapper

let router: Router
const mounted: Page[] = []

async function mountPage(path = '/documents/1', handoff?: DocumentHandoff): Promise<Page> {
  router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: Blank },
      { path: '/trash', component: Blank },
      { path: '/connections', component: Blank },
      { path: '/signin', component: Blank },
      { path: '/documents/:id', component: WorkspaceView, props: (route) => ({ documentId: Number(route.params.id) }) },
    ],
  })
  await router.push(handoff ? { path, state: documentHandoffState(handoff) } : path)
  await router.isReady()
  const page = mount(Host, {
    attachTo: document.body,
    global: { plugins: [router], stubs: { DriveSavePanel: DriveStub, CalendarEventPanel: CalendarStub } },
  })
  mounted.push(page)
  await flushPromises()
  await flushPromises()
  return page
}

/** Lets every pending answer and re-render finish; under fake timers it moves the clock by nothing. */
async function flushPromises(): Promise<void> {
  if (vi.isFakeTimers()) {
    await vi.advanceTimersByTimeAsync(0)
    return
  }
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function authenticate(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  setActivePinia(createPinia())
  resetCapabilitiesCache()
  for (const fn of API) fn.mockReset()
  vi.mocked(getDocument).mockResolvedValue(DOCUMENT)
  vi.mocked(getTemplateVersion).mockResolvedValue(MINUTES_TEMPLATE_VERSION)
  vi.mocked(getTemplateLayout).mockResolvedValue(FLOWING_MINUTES)
  vi.mocked(listTemplateVersionRules).mockResolvedValue([])
  vi.mocked(listDocumentSources).mockResolvedValue([])
  vi.mocked(listGenerationRuns).mockResolvedValue([])
  vi.mocked(getCapabilities).mockResolvedValue(TEN_MEGABYTE_LIMIT)
  vi.mocked(getLatestCompilation).mockRejectedValue(nothingOnRecord())
  vi.mocked(getLatestValidation).mockRejectedValue(nothingOnRecord())
  vi.mocked(getLatestExportApproval).mockRejectedValue(nothingOnRecord())
  vi.mocked(getLatestExportReceipt).mockRejectedValue(nothingOnRecord())
  vi.mocked(listConnections).mockResolvedValue([])
  vi.mocked(listActions).mockResolvedValue([])
  // The page scrolls a focused spot clear of the bar about it; jsdom lays nothing out and has no scrolling.
  vi.spyOn(window, 'scrollBy').mockImplementation(() => undefined)
  authenticate()
})

afterEach(() => {
  vi.useRealTimers()
  for (const page of mounted.splice(0)) {
    if (!page.vm.$.isUnmounted) page.unmount()
  }
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  window.history.replaceState(null, '', '/')
})

// ---- Finding things on the page ----------------------------------------------------------------

/** What assistive technology calls an element: its aria-label, or its text without the parts hidden from it. */
function accessibleName(element: Element): string {
  const label = element.getAttribute('aria-label')
  if (label !== null) return label.replace(/\s+/g, ' ').trim()
  let words = ''
  const walk = (node: Node): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      words += node.textContent ?? ''
      return
    }
    if (node instanceof Element && node.getAttribute('aria-hidden') === 'true') return
    node.childNodes.forEach(walk)
  }
  walk(element)
  return words.replace(/\s+/g, ' ').trim()
}

function norm(value: string): string {
  return value.replace(/\s+/g, ' ').trim()
}

/**
 * Buttons by accessible name. The words are compared without their spaces: the bar about a fill spot
 * currently runs "Accept" into the hidden name of what it accepts (see the test of its names below),
 * and every other test here is about what the button does, not how its name is spaced.
 */
function buttonsNamed(page: Page, name: string, within = ''): DOMWrapper<HTMLButtonElement>[] {
  const squash = (words: string) => words.replace(/\s+/g, '')
  return page.findAll<HTMLButtonElement>(`${within} button`.trim()).filter((button) => squash(accessibleName(button.element)) === squash(name))
}

function buttonNamed(page: Page, name: string, within = ''): DOMWrapper<HTMLButtonElement> | undefined {
  return buttonsNamed(page, name, within)[0]
}

function hasButton(page: Page, name: string, within = ''): boolean {
  return buttonsNamed(page, name, within).length > 0
}

async function press(page: Page, name: string, within = ''): Promise<void> {
  const target = buttonNamed(page, name, within)
  if (!target) {
    const names = page.findAll(`${within} button`.trim()).map((button) => accessibleName(button.element))
    throw new Error(`No button named "${name}"${within ? ` in ${within}` : ''}. There are: ${names.join(' | ')}`)
  }
  // A real click moves the focus to the button first; the page decides where it goes from there.
  target.element.focus()
  await target.trigger('click')
  await flushPromises()
}

/** A control by element id; field ids hold dots, which a plain #id selector would read as classes. */
function byId(page: Page, id: string) {
  return page.find(`[id="${id}"]`)
}

function valueOf(page: Page, id: string): string {
  return (byId(page, id).element as HTMLInputElement | HTMLTextAreaElement).value
}

async function type(page: Page, id: string, value: string): Promise<void> {
  await byId(page, id).setValue(value)
}

/** Moves into a fill spot the way a person does, which is what opens the bar about it. */
async function selectSpot(page: Page, id: string): Promise<void> {
  const control = byId(page, id)
  if (window.document.activeElement === control.element) await control.trigger('focus')
  else (control.element as HTMLElement).focus()
  await flushPromises()
}

const saveStatus = (page: Page) => page.get('.save-status').text()
const liveRegion = (page: Page) => page.get('.workspace > [aria-live="polite"][aria-atomic="true"]')
const notices = (page: Page) => page.get('.workspace-notices')
const chatLog = (page: Page) => page.get('[aria-label="Conversation with Brownie"]')
const selectionBar = (page: Page) => page.find('section.selection-bar')
const rulesCard = (page: Page) => page.get('section[aria-labelledby="rules-heading"]')
const runStatus = (page: Page) => page.get('.run-status')
const proposal = (page: Page) => page.find('.proposal')
const sourceCards = (page: Page) => page.findAll('ul[aria-label="Sources"] > li')
const exportDialog = (page: Page) => page.get('dialog.export-dialog')
const historyDialog = (page: Page) => page.get('dialog.version-history')

/** The words of Brownie's last error in the conversation, outside a reading or a proposal. */
function lastChatError(page: Page): string {
  const errors = chatLog(page).findAll('.chat-line--brownie:not(.run-status):not(.proposal) .field-error')
  return errors.length > 0 ? norm(errors[errors.length - 1]!.text()) : ''
}

function pressedOf(page: Page, name: string): string | undefined {
  return buttonNamed(page, name)?.attributes('aria-pressed')
}

/** Asks Brownie something through the composer, sending it with Enter as a person would. */
async function send(page: Page, words: string, how: 'enter' | 'button' = 'enter'): Promise<void> {
  const box = page.get('#assist-composer')
  await box.setValue(words)
  if (how === 'enter') await box.trigger('keydown', { key: 'Enter' })
  else await buttonNamed(page, 'Send')!.trigger('click')
  await flushPromises()
}

async function openAddSource(page: Page): Promise<void> {
  if (!page.find('#add-source-panel').exists()) await press(page, 'Add a source')
}

async function chooseSourceFile(page: Page, name = 'notes.txt', size?: number): Promise<void> {
  await openAddSource(page)
  const input = page.get('#attach-source')
  const file = new File(['notes'], name, { type: 'text/plain' })
  if (size !== undefined) Object.defineProperty(file, 'size', { value: size })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

/** Uploads minutes.txt through the add-a-source panel, the way a person attaches notes on this page. */
async function attachUpload(page: Page): Promise<void> {
  vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'minutes.txt' })
  vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'minutes.txt' })
  vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'minutes.txt' })
  vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
  vi.mocked(attachDocumentSource).mockResolvedValue({
    id: 3,
    artifactId: 5,
    displayFilename: 'minutes.txt',
    kind: 'ARTIFACT',
    fetchedAt: CREATED_AT,
    attachedAt: CREATED_AT,
  })
  await chooseSourceFile(page, 'minutes.txt')
}

/** Starts a reading of the selected source by asking for it in the chat. */
async function askToFill(page: Page): Promise<void> {
  vi.mocked(interpretAssist).mockResolvedValue(DRAFT)
  await send(page, 'Fill this in from my notes')
}

/** Answers one of Brownie's questions by typing, on the question's own last line. */
async function answerOther(page: Page, questionId: number, answer: string): Promise<void> {
  await page.get(`[id="question-${questionId}-other"]`).setValue(answer)
  await page.get(`[id="question-${questionId}-other"]`).element.closest('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flushPromises()
}

function chipsOf(page: Page): string[] {
  return selectionBar(page).findAll('.selection-bar__chips .badge').map((chip) => chip.text())
}

function styleChips(page: Page): string[] {
  return rulesCard(page).findAll('ul[aria-label="Text style"] li').map((chip) => chip.text())
}

function todayLikeExport(): string {
  const now = new Date()
  return formatDateLikeExport(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`)
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

// =================================================================================================

describe('WorkspaceView: the page', () => {
  it('draws the top bar, the title, the template as a page and Brownie beside it', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const page = await mountPage()

    expect(getTemplateVersion).toHaveBeenCalledWith(7, 1, 1)
    expect(getTemplateLayout).toHaveBeenCalledWith(7, 1, 1)
    expect(listTemplateVersionRules).toHaveBeenCalledWith(7, 1, 1)

    expect(hasButton(page, 'Undo the last change', 'header.workspace-bar')).toBe(true)
    expect(hasButton(page, 'Version history', 'header.workspace-bar')).toBe(true)
    expect(hasButton(page, 'Export', 'header.workspace-bar')).toBe(true)
    // Only while there is something to save.
    expect(hasButton(page, 'Save now')).toBe(false)
    expect(saveStatus(page)).toBe('Saved')
    expect(page.get('h1').text()).toBe('Weekly Sync')

    expect(page.find('[role="group"][aria-label="Show"]').exists()).toBe(true)
    expect(pressedOf(page, 'Document')).toBe('true')
    expect(pressedOf(page, 'Brownie')).toBe('false')
    expect(pressedOf(page, 'Page')).toBe('true')
    expect(pressedOf(page, 'Print preview')).toBe('false')

    // The template's own text, with the value in its place in the line.
    expect(page.get('.document-page__header-part').text()).toBe('Brownie Meeting Minutes Template')
    const title = byId(page, 'edit-meeting.title')
    expect(title.element.tagName).toBe('TEXTAREA')
    expect(title.attributes('aria-label')).toBe('Meeting title, required')
    expect(valueOf(page, 'edit-meeting.title')).toBe('Weekly Sync')
    expect(title.element.closest('p')!.textContent).toContain('Title: ')
    expect(page.find('.document-page__notice').exists()).toBe(false)

    expect(page.get('#assistant-heading').text()).toBe('Chat with Brownie')
    expect(rulesCard(page).get('h3').text()).toBe('Rules')
    expect(norm(chatLog(page).text())).toContain(
      'I can fill this document from your notes, a transcript or a conversation. Add one with Add a source (+), then ask ' +
        'me to fill it in. You can also type straight into the highlighted spots, and change anything I fill in.',
    )
    expect(page.get('label[for="assist-composer"]').text()).toBe('How may I help you?')
    expect(page.get('.assistant-disclaimer').text()).toBe('Brownie can make mistakes. Check each filled value before you export.')
    expect(page.findAll('[role="alert"]')).toHaveLength(0)
  })

  it('lists the fill spots instead of the page when the template cannot be drawn, and editing still works', async () => {
    vi.mocked(getTemplateLayout).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/templates/1/versions/1/layout'))
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    const page = await mountPage()

    expect(norm(page.get('.document-page__notice').text())).toBe(
      'The Brownie server that answered is older than this page and does not have a way to draw a template as a page yet. ' +
        'Reloading will not change that: the server needs to be updated and restarted. Its fill spots are listed instead.',
    )
    expect(page.text()).not.toContain('No static resource')
    expect(page.find('.document-page__header-part').exists()).toBe(false)
    expect(byId(page, 'edit-meeting.title').element.closest('p')!.textContent).toContain('Meeting title:')
    expect(page.findAll('th').map((cell) => cell.text())).toEqual(['Action item task', 'Action item owner', 'Action item due'])
    expect(byId(page, 'edit-meeting.date').attributes('type')).toBe('date')

    await type(page, 'edit-meeting.title', 'Garden Club Planning')
    await press(page, 'Save now')
    expect(vi.mocked(patchDocumentContent).mock.calls[0]![2].edits).toEqual([
      { operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Garden Club Planning' } },
    ])
  })

  it("says it is the template when the server cannot draw it, and does not ask again for the same version", async () => {
    vi.mocked(getTemplateLayout).mockRejectedValue(refusal(422, 'TEMPLATE_LAYOUT_UNAVAILABLE', 'The template file could not be read.'))
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(recordReviewDecision).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
    const page = await mountPage()

    expect(norm(page.get('.document-page__notice').text())).toBe(
      "Brownie could not draw this template's layout, so its fill spots are listed instead.",
    )
    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Accept Meeting title', 'section.selection-bar')
    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(getTemplateLayout).toHaveBeenCalledTimes(1)
  })

  it('draws the page on the next load when the first try failed for a reason that was not the template', async () => {
    vi.mocked(getTemplateLayout).mockRejectedValueOnce(networkFailure()).mockResolvedValue(FLOWING_MINUTES)
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(recordReviewDecision).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
    const page = await mountPage()

    expect(norm(page.get('.document-page__notice').text())).toBe(
      'Brownie could not be reached. Check your connection, then try again. Its fill spots are listed instead.',
    )
    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Accept Meeting title', 'section.selection-bar')

    expect(getTemplateLayout).toHaveBeenCalledTimes(2)
    expect(page.find('.document-page__notice').exists()).toBe(false)
    expect(page.get('.document-page__header-part').text()).toBe('Brownie Meeting Minutes Template')
  })

  it('shows the field list while the template is still being drawn, without saying it failed', async () => {
    vi.mocked(getTemplateLayout).mockReturnValue(new Promise(() => {}))
    const page = await mountPage()

    expect(byId(page, 'edit-meeting.title').exists()).toBe(true)
    expect(page.find('.document-page__notice').exists()).toBe(false)
  })

  it('says the page is loading, and offers nothing to fill yet, while the template is being read', async () => {
    vi.mocked(getTemplateVersion).mockReturnValue(new Promise(() => {}))
    vi.mocked(getTemplateLayout).mockReturnValue(new Promise(() => {}))
    const page = await mountPage()

    expect(page.find('.empty-state').exists()).toBe(false)
    expect(page.get('.document-page [role="status"]').text()).toBe('Loading the page…')
  })

  it("keeps every value the revision holds editable when the template's definitions cannot be read", async () => {
    vi.mocked(getTemplateVersion).mockRejectedValue(networkFailure())
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const page = await mountPage()

    expect(valueOf(page, 'edit-meeting.title')).toBe('Garden Club Planning')
    expect(valueOf(page, 'edit-action.item.task-0')).toBe('Order seedlings')
    expect(byId(page, 'edit-action.item.due-0').attributes('type')).toBe('date')
  })

  it('switches between the document and Brownie on a narrow page, keeping both, and a half-typed message, in place', async () => {
    const page = await mountPage()
    await page.get('#assist-composer').setValue('Half a thought')

    await press(page, 'Brownie')
    expect(pressedOf(page, 'Brownie')).toBe('true')
    expect(pressedOf(page, 'Document')).toBe('false')
    expect(page.get('.workspace').classes()).toContain('workspace--showing-assistant')
    expect(byId(page, 'edit-meeting.title').exists()).toBe(true)

    await press(page, 'Document')
    expect(page.get('.workspace').classes()).toContain('workspace--showing-document')
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('Half a thought')
  })

  it('offers to sign in again when the session ends while the page is open', async () => {
    const page = await mountPage()
    useSessionStore().status = 'anonymous'
    await flushPromises()

    expect(page.text()).toContain('Sign in to view this document.')
    const link = page.findAll('a').find((anchor) => anchor.text() === 'Sign in')!
    expect(decodeURIComponent(link.attributes('href')!)).toBe('/signin?next=/documents/1')
  })

  it('leads an empty page to adding notes: the panel opens beside the chat with the file picker focused', async () => {
    const page = await mountPage()

    expect(page.get('.empty-state').text()).toContain('Nothing filled in yet.')
    await press(page, 'Add notes or a transcript')

    expect(pressedOf(page, 'Brownie')).toBe('true')
    expect(page.find('#add-source-panel').exists()).toBe(true)
    expect(document.activeElement?.id).toBe('attach-source')
  })

  it('opens and closes the add-a-source panel from the + button, focusing the file picker', async () => {
    const page = await mountPage()
    const plus = () => buttonNamed(page, 'Add a source')!

    expect(plus().attributes('aria-expanded')).toBe('false')
    expect(plus().attributes('aria-controls')).toBe('add-source-panel')
    await press(page, 'Add a source')
    expect(plus().attributes('aria-expanded')).toBe('true')
    expect(document.activeElement?.id).toBe('attach-source')

    await press(page, 'Close adding a source')
    expect(page.find('#add-source-panel').exists()).toBe(false)
    expect(plus().attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(plus().element)
  })
})

// =================================================================================================

describe('WorkspaceView: editing by hand', () => {
  it('draws a fill spot for every template field, saves typed values as one typed edit against the current version, then reloads', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const page = await mountPage()

    for (const fieldId of ['meeting.title', 'meeting.location', 'meeting.attendees', 'meeting.decisions']) {
      expect(byId(page, `edit-${fieldId}`).element.tagName).toBe('TEXTAREA')
    }
    expect(byId(page, 'edit-meeting.date').attributes('type')).toBe('date')
    expect(page.text()).toContain('No rows yet.')
    expect(page.text()).toContain('Nothing filled in yet.')
    expect(hasButton(page, 'Save now')).toBe(false)

    await type(page, 'edit-meeting.title', 'Garden Club Planning')
    await type(page, 'edit-meeting.date', '2026-04-09')
    expect(saveStatus(page)).toBe('Unsaved changes')
    expect(buttonNamed(page, 'Save now')!.attributes('aria-disabled')).toBe('false')

    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(
        revision({
          id: 2,
          revisionNumber: 2,
          contentHash: HASH_B,
          fields: {
            'meeting.title': scalarField('Garden Club Planning'),
            'meeting.date': scalarField('2026-04-09', TYPED_STATE, 'DATE'),
          },
        }),
      ),
    )
    await press(page, 'Save now')

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      {
        expectedRevisionId: 1,
        edits: [
          { operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Garden Club Planning' } },
          { operation: 'SET', fieldId: 'meeting.date', value: { type: 'DATE', cardinality: 'SCALAR', value: '2026-04-09' } },
        ],
        editReason: 'Edited in the workspace.',
      },
      expect.any(String),
    )
    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(saveStatus(page)).toBe('Saved')
    expect(liveRegion(page).text()).toBe('Saved.')
    expect(hasButton(page, 'Save now')).toBe(false)
    expect(valueOf(page, 'edit-meeting.title')).toBe('Garden Club Planning')
    expect(page.find('.empty-state').exists()).toBe(false)

    await selectSpot(page, 'edit-meeting.title')
    expect(chipsOf(page)).toContain('Typed by you')
  })

  it('says Saving while a save is on its way, and ignores a second press of Save now', async () => {
    const save = deferred<DocumentRevisionResponse>()
    vi.mocked(patchDocumentContent).mockReturnValue(save.promise)
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'Garden Club Planning')

    await press(page, 'Save now')
    expect(saveStatus(page)).toBe('Saving…')
    expect(liveRegion(page).text()).toBe('Saving…')
    expect(buttonNamed(page, 'Save now')!.attributes('aria-disabled')).toBe('true')
    await press(page, 'Save now')
    expect(patchDocumentContent).toHaveBeenCalledTimes(1)

    vi.mocked(getDocument).mockResolvedValue(documentAt(revision({ id: 2, revisionNumber: 2, fields: { 'meeting.title': scalarField('Garden Club Planning') } })))
    save.resolve(revision({ id: 2, revisionNumber: 2 }))
    await flushPromises()
    expect(saveStatus(page)).toBe('Saved')
  })

  it('puts the focus on the save status once Save now goes away', async () => {
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    vi.mocked(getDocument)
      .mockResolvedValueOnce(DOCUMENT)
      .mockResolvedValue(documentAt(revision({ id: 2, revisionNumber: 2, fields: { 'meeting.title': scalarField('Garden Club Planning') } })))
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'Garden Club Planning')

    buttonNamed(page, 'Save now')!.element.focus()
    await press(page, 'Save now')

    expect(hasButton(page, 'Save now')).toBe(false)
    expect(document.activeElement).toBe(page.get('.save-status').element)
    expect(saveStatus(page)).toBe('Saved')
  })

  it('does not count spaces around a value as a change', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const page = await mountPage()

    await type(page, 'edit-meeting.title', 'Weekly Sync  ')

    expect(saveStatus(page)).toBe('Saved')
    expect(hasButton(page, 'Save now')).toBe(false)
  })

  it('adds a row, refuses to save it while its date is blank, then saves every column of the rows together', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const page = await mountPage()

    expect(page.text()).toContain('No rows yet.')
    await press(page, 'Add row')
    // The new row's first spot takes the focus, so typing carries on where the row appeared.
    expect(document.activeElement?.id).toBe('edit-action.item.task-0')
    await type(page, 'edit-action.item.task-0', 'Order seedlings')
    await type(page, 'edit-action.item.owner-0', 'Maria Lopez')

    expect(norm(notices(page).get('[role="alert"]').text())).toBe('Row 1 needs a value for Action item due, or remove the row.')
    expect(saveStatus(page)).toBe('Not saved')
    expect(buttonNamed(page, 'Save now')!.attributes('aria-disabled')).toBe('true')
    await press(page, 'Save now')
    expect(patchDocumentContent).not.toHaveBeenCalled()

    await type(page, 'edit-action.item.due-0', '2026-04-20')
    expect(page.text()).not.toContain('Row 1 needs a value')
    expect(buttonNamed(page, 'Save now')!.attributes('aria-disabled')).toBe('false')

    vi.mocked(patchDocumentContent).mockResolvedValue(DOCUMENT_WITH_A_ROW.currentRevision)
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    await press(page, 'Save now')

    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      {
        expectedRevisionId: 1,
        edits: [
          { operation: 'SET', fieldId: 'action.item.task', value: { type: 'TEXT', cardinality: 'REPEATED', values: ['Order seedlings'] } },
          { operation: 'SET', fieldId: 'action.item.owner', value: { type: 'TEXT', cardinality: 'REPEATED', values: ['Maria Lopez'] } },
          { operation: 'SET', fieldId: 'action.item.due', value: { type: 'DATE', cardinality: 'REPEATED', values: ['2026-04-20'] } },
        ],
        editReason: 'Edited in the workspace.',
      },
      expect.any(String),
    )
    expect(liveRegion(page).text()).toBe('Saved.')

    await selectSpot(page, 'edit-action.item.task-0')
    expect(hasButton(page, 'Accept row 1', 'section.selection-bar')).toBe(true)
  })

  it('clears a value the person emptied and leaves every untouched field out of the edit', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    vi.mocked(patchDocumentContent).mockResolvedValue(DOCUMENT_WITH_A_ROW.currentRevision)
    const page = await mountPage()

    await type(page, 'edit-meeting.title', '')
    await press(page, 'Save now')

    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      { expectedRevisionId: 3, edits: [{ operation: 'CLEAR', fieldId: 'meeting.title' }], editReason: 'Edited in the workspace.' },
      expect.any(String),
    )
  })

  it('moves and removes rows from the bar about the selected row, and saves them in their new order', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_TWO_ROWS)
    vi.mocked(patchDocumentContent).mockResolvedValue(DOCUMENT_WITH_TWO_ROWS.currentRevision)
    const page = await mountPage()

    await selectSpot(page, 'edit-action.item.task-1')
    expect(selectionBar(page).attributes('aria-label')).toBe('About Action item task, row 2')
    expect(buttonNamed(page, 'Move down row 2')!.attributes('aria-disabled')).toBe('true')
    await press(page, 'Move up row 2')

    expect(valueOf(page, 'edit-action.item.task-0')).toBe('Book the hall')
    expect(valueOf(page, 'edit-action.item.owner-0')).toBe('Sam Ortiz')
    expect(valueOf(page, 'edit-action.item.due-0')).toBe('2026-04-25')
    expect(valueOf(page, 'edit-action.item.task-1')).toBe('Order seedlings')
    expect(liveRegion(page).text()).toBe('Row 2 moved up.')
    // The bar follows the row it was about.
    expect(selectionBar(page).attributes('aria-label')).toBe('About Action item task, row 1')

    await press(page, 'Remove row 1')
    expect(byId(page, 'edit-action.item.task-1').exists()).toBe(false)
    expect(valueOf(page, 'edit-action.item.task-0')).toBe('Order seedlings')
    expect(liveRegion(page).text()).toBe('Row 1 removed.')
    // The button pressed went with its row; the focus goes to the row that took its place, which the bar is now about.
    expect(document.activeElement?.id).toBe('edit-action.item.task-0')
    expect(selectionBar(page).attributes('aria-label')).toBe('About Action item task, row 1')

    await press(page, 'Remove row 1')
    expect(page.text()).toContain('No rows yet.')
    expect(accessibleName(document.activeElement!)).toBe('Add row')
    expect(selectionBar(page).exists()).toBe(false)
    await press(page, 'Add row')
    await type(page, 'edit-action.item.task-0', 'Order seedlings')
    await type(page, 'edit-action.item.owner-0', 'Maria Lopez')
    await type(page, 'edit-action.item.due-0', '2026-04-20')

    await press(page, 'Save now')
    expect(vi.mocked(patchDocumentContent).mock.calls[0]![2].edits).toEqual([
      { operation: 'SET', fieldId: 'action.item.task', value: { type: 'TEXT', cardinality: 'REPEATED', values: ['Order seedlings'] } },
      { operation: 'SET', fieldId: 'action.item.owner', value: { type: 'TEXT', cardinality: 'REPEATED', values: ['Maria Lopez'] } },
      { operation: 'SET', fieldId: 'action.item.due', value: { type: 'DATE', cardinality: 'REPEATED', values: ['2026-04-20'] } },
    ])
  })

  it('leaves the rows as they are while one of them is locked, and says why', async () => {
    const locked = { ...TYPED_STATE, lock: 'EXPLICITLY_LOCKED' as const }
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(
        revision({
          id: 3,
          revisionNumber: 3,
          fields: {
            ...DOCUMENT_WITH_TWO_ROWS.currentRevision.fields,
            'action.item.task': rowField(['Order seedlings', 'Book the hall'], 'TEXT', [locked, { ...TYPED_STATE }]),
          },
        }),
      ),
    )
    const page = await mountPage()

    expect(buttonNamed(page, 'Add row')!.attributes('disabled')).toBeDefined()
    expect(page.text()).toContain('A row is locked, so rows cannot be added until it is unlocked.')
    expect(byId(page, 'edit-action.item.owner-1').attributes('readonly')).toBeDefined()

    await selectSpot(page, 'edit-action.item.task-1')
    expect(buttonNamed(page, 'Remove row 2')!.attributes('aria-disabled')).toBe('true')
    expect(buttonNamed(page, 'Move up row 2')!.attributes('aria-disabled')).toBe('true')
    expect(selectionBar(page).text()).toContain('A row is locked, so the rows cannot be changed until it is unlocked.')
    await press(page, 'Remove row 2')
    await press(page, 'Move up row 2')
    expect(valueOf(page, 'edit-action.item.task-1')).toBe('Book the hall')
    expect(saveStatus(page)).toBe('Saved')

    await selectSpot(page, 'edit-action.item.task-0')
    expect(hasButton(page, 'Unlock row 1', 'section.selection-bar')).toBe(true)
  })

  it('keeps the typed draft after a 412 and offers to save it onto the version that is now current', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'My title')

    vi.mocked(patchDocumentContent).mockRejectedValueOnce(STALE)
    const movedOn = documentAt(revision({ id: 9, revisionNumber: 9, contentHash: HASH_B }))
    vi.mocked(getDocument).mockResolvedValueOnce(movedOn)
    await press(page, 'Save now')

    const conflict = notices(page).get('.conflict-notice')
    expect(conflict.attributes('role')).toBe('alert')
    expect(norm(conflict.text())).toContain(
      'This document changed since you started editing (version 9 is now current). Your edits are still on the page. ' +
        'Save them onto the latest version, or discard them to see what changed.',
    )
    expect(valueOf(page, 'edit-meeting.title')).toBe('My title')
    expect(saveStatus(page)).toBe('Not saved')
    expect(hasButton(page, 'Save now')).toBe(false)
    expect(liveRegion(page).text()).toBe(
      'This document changed elsewhere. Your edits are kept; choose whether to save them onto the latest version.',
    )

    vi.mocked(patchDocumentContent).mockResolvedValueOnce(revision({ id: 10, revisionNumber: 10 }))
    vi.mocked(getDocument).mockResolvedValueOnce(
      documentAt(revision({ id: 10, revisionNumber: 10, contentHash: HASH_C, fields: { 'meeting.title': scalarField('My title') } })),
    )
    await press(page, 'Save my edits onto the latest')

    expect(vi.mocked(patchDocumentContent).mock.calls[1]![2]).toMatchObject({ expectedRevisionId: 9, editReason: 'Edited in the workspace.' })
    expect(page.text()).not.toContain('is now current')
    expect(saveStatus(page)).toBe('Saved')
  })

  it('discards the typed draft on request after a 412, showing the latest version', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'My title')
    vi.mocked(patchDocumentContent).mockRejectedValueOnce(STALE)
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 9, revisionNumber: 9, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Their title') } })),
    )
    await press(page, 'Save now')

    await press(page, 'Discard my edits')

    expect(valueOf(page, 'edit-meeting.title')).toBe('Their title')
    expect(page.find('.conflict-notice').exists()).toBe(false)
    expect(saveStatus(page)).toBe('Saved')
    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
  })

  it("makes a locked fill spot read-only and says why, and reports the server's own locked-field refusal", async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(
        revision({
          id: 3,
          revisionNumber: 3,
          fields: { ...ROW_FIELDS, 'meeting.title': scalarField('Garden Club Planning', { ...TYPED_STATE, lock: 'EXPLICITLY_LOCKED' }) },
        }),
      ),
    )
    const page = await mountPage()

    const title = byId(page, 'edit-meeting.title')
    // Read-only rather than disabled, so it stays in the tab order and its state can still be read.
    expect(title.attributes('readonly')).toBeDefined()
    expect(title.attributes('disabled')).toBeUndefined()
    expect(title.attributes('aria-readonly')).toBe('true')
    const description = title
      .attributes('aria-describedby')!
      .split(' ')
      .map((id) => document.getElementById(id)?.textContent ?? '')
      .join(' ')
    expect(description).toContain('Locked: unlock it to edit.')

    await type(page, 'edit-action.item.owner-0', 'Someone Else')
    vi.mocked(patchDocumentContent).mockRejectedValueOnce(
      refusal(409, 'FIELD_LOCKED', 'Field action.item.owner is explicitly locked.'),
    )
    await press(page, 'Save now')

    expect(norm(notices(page).get('[role="alert"]').text())).toBe('Field action.item.owner is explicitly locked.')
    expect(valueOf(page, 'edit-action.item.owner-0')).toBe('Someone Else')
    expect(saveStatus(page)).toBe('Not saved')
    expect(liveRegion(page).text()).toBe('Your changes could not be saved.')
  })

  it('does not lose or replace text typed while a save is on its way, and keeps the same fill spot and the focus', async () => {
    const page = await mountPage()
    const titleBefore = byId(page, 'edit-meeting.title').element

    const save = deferred<DocumentRevisionResponse>()
    vi.mocked(patchDocumentContent).mockReturnValue(save.promise)
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Priya Rao, Alex') } })),
    )

    ;(titleBefore as HTMLTextAreaElement).focus()
    vi.useFakeTimers()
    await type(page, 'edit-meeting.title', 'Priya Rao, Alex')
    await vi.advanceTimersByTimeAsync(2_600)
    vi.useRealTimers()
    expect(patchDocumentContent).toHaveBeenCalledTimes(1)

    // The save is still on its way; the person keeps typing.
    await type(page, 'edit-meeting.title', 'Priya Rao, Alex Chen, Jose Nunez')
    save.resolve(revision({ id: 2, revisionNumber: 2 }))
    await flushPromises()

    expect(valueOf(page, 'edit-meeting.title')).toBe('Priya Rao, Alex Chen, Jose Nunez')
    expect(byId(page, 'edit-meeting.title').element).toBe(titleBefore)
    expect(document.activeElement).toBe(titleBefore)
    expect(saveStatus(page)).toBe('Unsaved changes')
    expect(page.text()).not.toContain('Loading document')
  })

  it("takes the server's normalised value into a field that was not touched during the save", async () => {
    const page = await mountPage()
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Trimmed by the server') } })),
    )

    await type(page, 'edit-meeting.title', '  Trimmed by the server  ')
    await press(page, 'Save now')

    expect(vi.mocked(patchDocumentContent).mock.calls[0]![2]).toEqual({
      expectedRevisionId: 1,
      edits: [{ operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Trimmed by the server' } }],
      editReason: 'Edited in the workspace.',
    })
    expect(valueOf(page, 'edit-meeting.title')).toBe('Trimmed by the server')
    expect(saveStatus(page)).toBe('Saved')
  })

  it('saves by itself two and a half seconds after typing stops, not sooner, and says so', async () => {
    const page = await mountPage()
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Autosaved title') } })),
    )

    vi.useFakeTimers()
    await type(page, 'edit-meeting.title', 'Autosaved title')
    expect(saveStatus(page)).toBe('Unsaved changes')
    await vi.advanceTimersByTimeAsync(2_000)
    expect(patchDocumentContent).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(600)
    vi.useRealTimers()
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      {
        expectedRevisionId: 1,
        edits: [{ operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Autosaved title' } }],
        editReason: 'Autosaved.',
      },
      expect.any(String),
    )
    expect(saveStatus(page)).toBe('Saved')
    expect(liveRegion(page).text()).toBe('Saved.')
  })

  it('does not save a row that still has a problem by itself, and says why', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const page = await mountPage()

    vi.useFakeTimers()
    await type(page, 'edit-action.item.due-0', '')
    await vi.advanceTimersByTimeAsync(3_000)
    vi.useRealTimers()
    await flushPromises()

    expect(patchDocumentContent).not.toHaveBeenCalled()
    expect(norm(notices(page).text())).toContain('Row 1 needs a value for Action item due, or remove the row.')
    expect(saveStatus(page)).toBe('Not saved')
  })

  it('asks before leaving with unsaved changes, and stays when the person says no', async () => {
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'Not saved yet')

    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await router.push('/')
    expect(confirm).toHaveBeenCalledWith('You have unsaved changes on this document. Leave anyway?')
    expect(router.currentRoute.value.path).toBe('/documents/1')
    expect(valueOf(page, 'edit-meeting.title')).toBe('Not saved yet')

    confirm.mockReturnValue(true)
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('leaves without asking when everything is saved', async () => {
    await mountPage()
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(false)

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await router.push('/')
    expect(confirm).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/')
  })
})

// =================================================================================================

describe('WorkspaceView: the bar about the selected fill spot', () => {
  it('records a review decision and toggles a lock, reloading the document after each', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD)
    const page = await mountPage()

    await selectSpot(page, 'edit-meeting.title')
    expect(selectionBar(page).attributes('aria-label')).toBe('About Meeting title')
    expect(chipsOf(page)).toEqual(['Required', 'Imported', 'Source cited', 'Not reviewed'])
    expect(selectionBar(page).text()).not.toContain('EDITABLE')

    const reviewed = documentAt(
      revision({ id: 2, revisionNumber: 2, fields: { 'meeting.title': scalarField('Weekly Sync', { ...IMPORTED_STATE, review: 'ACCEPTED' }) } }),
    )
    vi.mocked(recordReviewDecision).mockResolvedValue(reviewed.currentRevision)
    vi.mocked(getDocument).mockResolvedValueOnce(reviewed)
    await press(page, 'Accept Meeting title', 'section.selection-bar')

    expect(recordReviewDecision).toHaveBeenCalledWith(7, 1, 1, 'meeting.title', 'ACCEPTED', expect.any(String))
    expect(chipsOf(page)).toContain('Accepted')
    expect(liveRegion(page).text()).toBe('Meeting title: accepted.')

    const locked = documentAt(
      revision({
        id: 3,
        revisionNumber: 3,
        fields: { 'meeting.title': scalarField('Weekly Sync', { ...IMPORTED_STATE, review: 'ACCEPTED', lock: 'EXPLICITLY_LOCKED' }) },
      }),
    )
    vi.mocked(setFieldLock).mockResolvedValue(locked.currentRevision)
    vi.mocked(getDocument).mockResolvedValueOnce(locked)
    await press(page, 'Lock Meeting title', 'section.selection-bar')

    expect(setFieldLock).toHaveBeenCalledWith(7, 1, 2, 'meeting.title', 'EXPLICITLY_LOCKED', expect.any(String))
    expect(chipsOf(page)).toContain('Locked')
    expect(hasButton(page, 'Unlock Meeting title', 'section.selection-bar')).toBe(true)
    expect(byId(page, 'edit-meeting.title').attributes('readonly')).toBeDefined()
    expect(liveRegion(page).text()).toBe('Meeting title locked.')
  })

  it('records Reject and Needs clarification for a field with the decision named', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(recordReviewDecision).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')

    await press(page, 'Reject Meeting title', 'section.selection-bar')
    expect(liveRegion(page).text()).toBe('Meeting title: rejected.')
    await press(page, 'Needs clarification for Meeting title', 'section.selection-bar')
    expect(liveRegion(page).text()).toBe('Meeting title: needs clarification.')

    expect(vi.mocked(recordReviewDecision).mock.calls.map((call) => [call[3], call[4], call.length])).toEqual([
      ['meeting.title', 'REJECTED', 6],
      ['meeting.title', 'NEEDS_CLARIFICATION', 6],
    ])
  })

  // The hidden half of each name begins with a space the template compiler would drop, so the page writes it out.
  it('names each decision with what it is about, as separate words', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const page = await mountPage()

    await selectSpot(page, 'edit-meeting.title')
    const fieldNames = selectionBar(page).findAll('button').map((button) => accessibleName(button.element))
    expect(fieldNames).toEqual(expect.arrayContaining(['Accept Meeting title', 'Reject Meeting title', 'Lock Meeting title']))

    await selectSpot(page, 'edit-action.item.task-0')
    const rowNames = selectionBar(page).findAll('button').map((button) => accessibleName(button.element))
    expect(rowNames).toEqual(expect.arrayContaining(['Accept row 1', 'Reject row 1', 'Lock row 1', 'Move up row 1', 'Remove row 1']))
  })

  it('reviews and locks a whole row by addressing every column with the row index, each against the version the last one made', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const page = await mountPage()
    await selectSpot(page, 'edit-action.item.owner-0')

    expect(selectionBar(page).attributes('aria-label')).toBe('About Action item owner, row 1')
    // A row is accepted or rejected as a whole; asking about one of its values is done per field.
    expect(hasButton(page, 'Needs clarification for row 1')).toBe(false)

    vi.mocked(recordReviewDecision)
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 4 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 5 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 6 })
    await press(page, 'Accept row 1', 'section.selection-bar')

    expect(vi.mocked(recordReviewDecision).mock.calls).toEqual([
      [7, 1, 3, 'action.item.task', 'ACCEPTED', expect.any(String), 0],
      [7, 1, 4, 'action.item.owner', 'ACCEPTED', expect.any(String), 0],
      [7, 1, 5, 'action.item.due', 'ACCEPTED', expect.any(String), 0],
    ])
    expect(liveRegion(page).text()).toBe('Row 1: accepted.')

    vi.mocked(setFieldLock)
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 7 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 8 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 9 })
    await press(page, 'Lock row 1', 'section.selection-bar')

    expect(vi.mocked(setFieldLock).mock.calls).toEqual([
      [7, 1, 3, 'action.item.task', 'EXPLICITLY_LOCKED', expect.any(String), 0],
      [7, 1, 7, 'action.item.owner', 'EXPLICITLY_LOCKED', expect.any(String), 0],
      [7, 1, 8, 'action.item.due', 'EXPLICITLY_LOCKED', expect.any(String), 0],
    ])
    expect(liveRegion(page).text()).toBe('Row 1 locked.')
  })

  // Columns disagree after a row lock that stopped partway (the first column locked, a later call
  // refused); the row then reads as locked, and its button and its action agree.
  it('does what its Lock or Unlock button says for a row whose columns are locked differently', async () => {
    const locked = { ...TYPED_STATE, lock: 'EXPLICITLY_LOCKED' as const }
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 3, revisionNumber: 3, fields: { ...ROW_FIELDS, 'action.item.task': rowField(['Order seedlings'], 'TEXT', [locked]) } })),
    )
    vi.mocked(setFieldLock).mockResolvedValue(DOCUMENT_WITH_A_ROW.currentRevision)
    const page = await mountPage()
    await selectSpot(page, 'edit-action.item.owner-0')

    const toggle = selectionBar(page)
      .findAll('button')
      .find((button) => /^(Lock|Unlock)/.test(accessibleName(button.element)))!
    const says = accessibleName(toggle.element).startsWith('Unlock') ? 'EDITABLE' : 'EXPLICITLY_LOCKED'
    toggle.element.focus()
    await toggle.trigger('click')
    await flushPromises()

    expect(vi.mocked(setFieldLock).mock.calls.map((call) => call[4])).toEqual([says, says, says])
  })

  it("says so when a spot holds nothing yet, closes back onto the spot, and takes 'Text style and rules' to Brownie's panel", async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const page = await mountPage()

    await selectSpot(page, 'edit-meeting.location')
    expect(selectionBar(page).text()).toContain('Nothing filled in here yet.')
    expect(chipsOf(page)).toEqual([])
    expect(hasButton(page, 'Accept Meeting location')).toBe(false)
    await type(page, 'edit-meeting.location', 'Room 4')
    expect(selectionBar(page).text()).toContain('Not saved yet.')

    await press(page, 'Close the bar about Meeting location')
    // The focus goes back to the spot without opening the bar about it again.
    expect(selectionBar(page).exists()).toBe(false)
    expect(document.activeElement?.id).toBe('edit-meeting.location')

    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Text style and rules', 'section.selection-bar')
    expect(pressedOf(page, 'Brownie')).toBe('true')
    expect(document.activeElement?.id).toBe('assistant-heading')
  })

  it('comes to the bar from a fill spot with Alt+Enter, and goes back to the spot with Escape, leaving the bar open', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const page = await mountPage()
    const title = byId(page, 'edit-meeting.title')
    expect(title.attributes('aria-keyshortcuts')).toBe('Alt+Enter')

    await title.trigger('keydown', { key: 'Enter', altKey: true })
    await flushPromises()

    expect(selectionBar(page).attributes('aria-label')).toBe('About Meeting title')
    expect(document.activeElement).toBe(selectionBar(page).element)
    expect(valueOf(page, 'edit-meeting.title')).toBe('Weekly Sync')

    await selectionBar(page).trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(document.activeElement).toBe(title.element)
    expect(selectionBar(page).exists()).toBe(true)
  })

  it('goes back to the very place the person came from when a field is drawn in two', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const layout = flowingMinutes()
    vi.mocked(getTemplateLayout).mockResolvedValue({
      ...layout,
      parts: layout.parts.map((part) =>
        part.kind === 'FOOTER' ? { ...part, blocks: [paragraph([text('Minutes of '), spot('meeting.title', '[meeting title]')])] } : part,
      ),
    })
    const page = await mountPage()
    const inFooter = byId(page, 'edit-meeting.title--2')
    expect(inFooter.exists()).toBe(true)

    ;(inFooter.element as HTMLElement).focus()
    await inFooter.trigger('keydown', { key: 'Enter', altKey: true })
    await flushPromises()
    expect(document.activeElement).toBe(selectionBar(page).element)

    await selectionBar(page).trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(document.activeElement).toBe(inFooter.element)

    await press(page, 'Close the bar about Meeting title')
    expect(selectionBar(page).exists()).toBe(false)
    expect(document.activeElement).toBe(inFooter.element)
  })

  it("offers a row's review and lock from a column with no state of its own, without that column borrowing another's", async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(
        revision({
          id: 3,
          revisionNumber: 3,
          fields: { ...ROW_FIELDS, 'action.item.owner': rowField(['Maria'], 'TEXT', [null]) },
        }),
      ),
    )
    const page = await mountPage()
    await selectSpot(page, 'edit-action.item.owner-0')

    expect(chipsOf(page)).toEqual([])
    expect(hasButton(page, 'Accept row 1', 'section.selection-bar')).toBe(true)
    expect(hasButton(page, 'Lock row 1', 'section.selection-bar')).toBe(true)
  })

  it('says a value Brownie filled in came from Brownie', async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(
        revision({
          fields: {
            'meeting.title': scalarField('Weekly Robotics Club Sync', {
              authorship: 'AI_COMPOSED',
              evidenceSupport: 'DIRECT',
              validation: 'NOT_RUN',
              review: 'UNREVIEWED',
              lock: 'EDITABLE',
            }),
          },
        }),
      ),
    )
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')

    expect(chipsOf(page)).toEqual(['Required', 'From Brownie', 'Source cited', 'Not reviewed'])
    expect(byId(page, 'edit-meeting.title').element.closest('.fill-spot')!.classList).toContain('fill-spot--assist')
  })

  it('opens the cited excerpt behind a value that carries evidence, and says what it cannot show', async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ fields: { 'meeting.title': { ...scalarField('Weekly Sync', IMPORTED_STATE), evidenceSourceSpanIds: [12] } } })),
    )
    vi.mocked(getEvidenceExcerpt).mockResolvedValue({
      spanId: 12,
      sourceSnapshotId: 3,
      sourceArtifactId: 5,
      displayFilename: 'minutes.txt',
      locatorType: 'PLAIN_TEXT',
      excerptText: 'The meeting title is "Weekly Sync".',
    })
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')

    const where = () => buttonNamed(page, 'Where it came from (1)', 'section.selection-bar')!
    expect(where().attributes('aria-expanded')).toBe('false')
    expect(where().attributes('aria-controls')).toBe('evidence-meeting.title')
    await where().trigger('click')
    await flushPromises()

    expect(getEvidenceExcerpt).toHaveBeenCalledWith(7, 1, 12)
    const panel = page.get('#evidence-meeting\\.title')
    expect(panel.attributes('aria-label')).toBe('Where Meeting title came from')
    expect(panel.text()).toContain('The meeting title is "Weekly Sync".')
    expect(panel.text()).toContain('From minutes.txt')
    expect(panel.text()).toContain('Brownie shows where a value came from; it cannot point to where it lands in the exported file.')
    expect(where().attributes('aria-expanded')).toBe('true')

    await where().trigger('click')
    await flushPromises()
    expect(page.find('#evidence-meeting\\.title').exists()).toBe(false)
  })

  it('says the passages cited for a row are those of its whole column', async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(
        revision({
          id: 3,
          revisionNumber: 3,
          fields: { ...ROW_FIELDS, 'action.item.owner': { ...rowField(['Maria Lopez']), evidenceSourceSpanIds: [12] } },
        }),
      ),
    )
    vi.mocked(getEvidenceExcerpt).mockResolvedValue({
      spanId: 12,
      sourceSnapshotId: 3,
      sourceArtifactId: 5,
      displayFilename: 'minutes.txt',
      locatorType: 'PLAIN_TEXT',
      excerptText: 'Maria will order the seedlings.',
    })
    const page = await mountPage()
    await selectSpot(page, 'edit-action.item.owner-0')
    await press(page, 'Where it came from (1)', 'section.selection-bar')

    const panel = page.get('#evidence-action\\.item\\.owner')
    expect(panel.text()).toContain('Maria will order the seedlings.')
    expect(norm(panel.text())).toContain('These are the passages cited for Action item owner in every row, not only row 1.')
  })

  it('fetches at most five cited passages and says how many there are', async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ fields: { 'meeting.title': { ...scalarField('Weekly Sync', IMPORTED_STATE), evidenceSourceSpanIds: [1, 2, 3, 4, 5, 6, 7] } } })),
    )
    vi.mocked(getEvidenceExcerpt).mockImplementation(async (_workspace, _document, spanId) => ({
      spanId,
      sourceSnapshotId: 3,
      sourceArtifactId: 5,
      displayFilename: null,
      locatorType: 'PLAIN_TEXT' as const,
      excerptText: `Passage ${spanId}.`,
    }))
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Where it came from (7)', 'section.selection-bar')

    expect(getEvidenceExcerpt).toHaveBeenCalledTimes(5)
    const panel = page.get('#evidence-meeting\\.title')
    expect(panel.text()).toContain('Showing the first 5 of 7 cited passages.')
    expect(panel.text()).toContain('From an attached source')
    expect(panel.text()).not.toContain('Passage 6.')
  })

  it('says so when a cited excerpt cannot be loaded', async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ fields: { 'meeting.title': { ...scalarField('Weekly Sync', IMPORTED_STATE), evidenceSourceSpanIds: [12] } } })),
    )
    vi.mocked(getEvidenceExcerpt).mockRejectedValue(networkFailure())
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Where it came from (1)', 'section.selection-bar')

    expect(page.get('#evidence-meeting\\.title [role="alert"]').text()).toBe('The cited excerpt could not be loaded.')
  })

  it('offers no evidence for a value typed by hand', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')

    expect(selectionBar(page).findAll('button').some((button) => accessibleName(button.element).startsWith('Where it came from'))).toBe(false)
  })

  it('reloads and explains when a review decision hits a version that moved on', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(recordReviewDecision).mockRejectedValue(STALE)
    const page = await mountPage()
    const loadsBefore = vi.mocked(getDocument).mock.calls.length
    await selectSpot(page, 'edit-meeting.title')

    await press(page, 'Accept Meeting title', 'section.selection-bar')

    expect(vi.mocked(getDocument).mock.calls.length).toBe(loadsBefore + 1)
    expect(norm(notices(page).get('[role="alert"]').text())).toBe(
      'This document changed since you loaded it, so it was reloaded. Try again on the current version.',
    )
    expect(liveRegion(page).text()).toBe('This document changed elsewhere and was reloaded.')
  })
})

// =================================================================================================

describe('WorkspaceView: undo', () => {
  const R1 = revision({ id: 1, revisionNumber: 1, contentHash: HASH_A, fields: { 'meeting.title': scalarField('Weekly Sync') } })
  const R2 = revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Spring Planning') } })
  const R3 = revision({ id: 3, revisionNumber: 3, contentHash: HASH_C, fields: { 'meeting.title': scalarField('Summer Planning') } })
  const R4 = revision({ id: 4, revisionNumber: 4, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Spring Planning') } })
  const R5 = revision({ id: 5, revisionNumber: 5, contentHash: HASH_A, fields: { 'meeting.title': scalarField('Weekly Sync') } })

  it('saves unsaved typing first, then undoes it, so the typing stays in the version history', async () => {
    vi.mocked(getDocument)
      .mockResolvedValueOnce(documentAt(R1))
      .mockResolvedValueOnce(documentAt(R2))
      .mockResolvedValue(documentAt(R5))
    vi.mocked(patchDocumentContent).mockResolvedValue(R2)
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2])
    vi.mocked(restoreRevision).mockResolvedValue({ revision: R5, keptLockedFieldIds: [] })
    const page = await mountPage()

    await type(page, 'edit-meeting.title', 'Spring Planning')
    await press(page, 'Undo the last change')

    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      {
        expectedRevisionId: 1,
        edits: [{ operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Spring Planning' } }],
        editReason: 'Edited in the workspace.',
      },
      expect.any(String),
    )
    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 1, 2, expect.any(String), 'Undid a change: back to version 1.')
    expect(vi.mocked(patchDocumentContent).mock.invocationCallOrder[0]!).toBeLessThan(vi.mocked(restoreRevision).mock.invocationCallOrder[0]!)
    expect(valueOf(page, 'edit-meeting.title')).toBe('Weekly Sync')
    expect(saveStatus(page)).toBe('Saved')
    expect(liveRegion(page).text()).toBe('Undone: the document is back to how it read in version 1.')
  })

  it('asks before discarding typing that cannot be saved as it is, and discards it only on yes', async () => {
    vi.mocked(getDocument).mockResolvedValue(documentAt(R1))
    const page = await mountPage()
    await press(page, 'Add row')
    await type(page, 'edit-action.item.task-0', 'Order seedlings')

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await press(page, 'Undo the last change')
    expect(confirm).toHaveBeenCalledWith('Undo discards the changes on this page that are not saved. Discard them?')
    expect(valueOf(page, 'edit-action.item.task-0')).toBe('Order seedlings')

    confirm.mockReturnValue(true)
    await press(page, 'Undo the last change')
    expect(byId(page, 'edit-action.item.task-0').exists()).toBe(false)
    expect(saveStatus(page)).toBe('Saved')
    expect(liveRegion(page).text()).toBe('Your unsaved changes were undone.')
    expect(patchDocumentContent).not.toHaveBeenCalled()
    expect(listDocumentRevisions).not.toHaveBeenCalled()
    expect(restoreRevision).not.toHaveBeenCalled()
  })

  it('goes back to the nearest earlier version whose values differ, and further back on a second press, through a review between', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(R3))
    const page = await mountPage()

    vi.mocked(listDocumentRevisions).mockResolvedValueOnce([R1, R2, R3])
    vi.mocked(restoreRevision).mockResolvedValueOnce({ revision: R4, keptLockedFieldIds: [] })
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(R4))
    await press(page, 'Undo the last change')

    expect(listDocumentRevisions).toHaveBeenCalledWith(7, 1)
    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 2, 3, expect.any(String), 'Undid a change: back to version 2.')
    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(valueOf(page, 'edit-meeting.title')).toBe('Spring Planning')
    expect(norm(chatLog(page).text())).toContain('Undone: the document is back to how it read in version 2.')
    // What Brownie says in the conversation is heard through the page's one live region too.
    expect(liveRegion(page).text()).toBe('Undone: the document is back to how it read in version 2.')

    // A review records a version without changing a value, so the trail carries on through it.
    const reviewed = revision({
      id: 5,
      revisionNumber: 5,
      contentHash: HASH_B,
      fields: { 'meeting.title': scalarField('Spring Planning', { ...TYPED_STATE, review: 'ACCEPTED' }) },
    })
    vi.mocked(recordReviewDecision).mockResolvedValue(reviewed)
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(reviewed))
    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Accept Meeting title', 'section.selection-bar')

    // Version 4 holds what version 2 held, so going on from it would undo nothing: the next press goes on from version 2.
    vi.mocked(listDocumentRevisions).mockResolvedValueOnce([R1, R2, R3, R4, reviewed])
    vi.mocked(restoreRevision).mockResolvedValueOnce({ revision: { ...R5, id: 6, revisionNumber: 6 }, keptLockedFieldIds: [] })
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt({ ...R5, id: 6, revisionNumber: 6 }))
    await press(page, 'Undo the last change')

    expect(vi.mocked(restoreRevision).mock.calls[1]).toEqual([7, 1, 1, 5, expect.any(String), 'Undid a change: back to version 1.'])
    expect(valueOf(page, 'edit-meeting.title')).toBe('Weekly Sync')
  })

  it('starts the trail again from the newest version once a value changes after an undo', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(R3))
    const page = await mountPage()
    vi.mocked(listDocumentRevisions).mockResolvedValueOnce([R1, R2, R3])
    vi.mocked(restoreRevision).mockResolvedValueOnce({ revision: R4, keptLockedFieldIds: [] })
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(R4))
    await press(page, 'Undo the last change')

    const typed = revision({ id: 5, revisionNumber: 5, contentHash: 'e'.repeat(64), fields: { 'meeting.title': scalarField('Autumn Planning') } })
    vi.mocked(patchDocumentContent).mockResolvedValue(typed)
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(typed))
    await type(page, 'edit-meeting.title', 'Autumn Planning')
    await press(page, 'Save now')

    vi.mocked(listDocumentRevisions).mockResolvedValueOnce([R1, R2, R3, R4, typed])
    vi.mocked(restoreRevision).mockResolvedValueOnce({ revision: { ...R4, id: 6, revisionNumber: 6 }, keptLockedFieldIds: [] })
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt({ ...R4, id: 6, revisionNumber: 6 }))
    await press(page, 'Undo the last change')

    expect(vi.mocked(restoreRevision).mock.calls[1]).toEqual([7, 1, 4, 5, expect.any(String), 'Undid a change: back to version 4.'])
  })

  it('passes over versions that changed no value, and names the locked fill spots that kept theirs', async () => {
    const reviewed = revision({ id: 3, revisionNumber: 3, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Spring Planning') } })
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(reviewed))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2, reviewed])
    vi.mocked(restoreRevision).mockResolvedValue({ revision: revision({ id: 4, revisionNumber: 4 }), keptLockedFieldIds: ['meeting.title'] })
    const page = await mountPage()

    await press(page, 'Undo the last change')

    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 1, 3, expect.any(String), 'Undid a change: back to version 1.')
    expect(norm(chatLog(page).text())).toContain(
      'Undone: the document is back to how it read in version 1. Meeting title kept its value because it is locked.',
    )
    expect(liveRegion(page).text()).toBe(
      'Undone: the document is back to how it read in version 1. Meeting title kept its value because it is locked.',
    )
  })

  it('says there is nothing earlier to go back to', async () => {
    vi.mocked(getDocument).mockResolvedValue(documentAt(R1))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1])
    const page = await mountPage()

    await press(page, 'Undo the last change')

    expect(restoreRevision).not.toHaveBeenCalled()
    expect(norm(notices(page).get('[role="alert"]').text())).toBe('There is nothing earlier to go back to.')
    expect(liveRegion(page).text()).toBe('There is nothing earlier to go back to.')
  })

  it('reloads and says so when the document moved on before the undo', async () => {
    vi.mocked(getDocument).mockResolvedValue(documentAt(R2))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2])
    vi.mocked(restoreRevision).mockRejectedValue(STALE)
    const page = await mountPage()

    await press(page, 'Undo the last change')

    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(norm(notices(page).get('[role="alert"]').text())).toBe(
      'This document changed since you loaded it, so it was reloaded. Try again on the current version.',
    )
  })

  it('says an older server cannot undo, in the common words for it', async () => {
    vi.mocked(getDocument).mockResolvedValue(documentAt(R2))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2])
    vi.mocked(restoreRevision).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/documents/1/revisions/1/restore'))
    const page = await mountPage()

    await press(page, 'Undo the last change')

    const said = norm(notices(page).get('[role="alert"]').text())
    expect(said).toContain('older than this page and does not have a way to undo a change yet')
    expect(said).not.toContain('No static resource')
  })
})

// =================================================================================================

describe('WorkspaceView: Export and Version history', () => {
  it('opens Export, which checks the version on screen and has the page load the version checking made', async () => {
    vi.mocked(getDocument)
      .mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD)
      .mockResolvedValue(documentAt(revision({ id: 2, revisionNumber: 2, fields: DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields })))
    vi.mocked(validateDocument).mockResolvedValue(manifest({ revisionId: 2 }))
    const page = await mountPage()
    expect(exportDialog(page).find('h2').exists()).toBe(false)

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(exportDialog(page).attributes('open')).toBeDefined()
    expect(exportDialog(page).get('h2').text()).toBe('Export')
    expect(getLatestValidation).toHaveBeenCalledWith(7, 1, 1)
    expect(validateDocument).toHaveBeenCalledWith(7, 1, 1, expect.any(String))
    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(exportDialog(page).text()).toContain('Ready to export.')
  })

  it('saves unsaved edits before Export checks, and checks the version the save made', async () => {
    vi.mocked(getDocument)
      .mockResolvedValueOnce(DOCUMENT)
      .mockResolvedValueOnce(documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Garden Club Planning') } })))
      .mockResolvedValue(documentAt(revision({ id: 3, revisionNumber: 3, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Garden Club Planning') } })))
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    vi.mocked(validateDocument).mockResolvedValue(manifest({ revisionId: 3 }))
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'Garden Club Planning')

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      {
        expectedRevisionId: 1,
        edits: [{ operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Garden Club Planning' } }],
        editReason: 'Edited in the workspace.',
      },
      expect.any(String),
    )
    expect(getLatestValidation).toHaveBeenCalledWith(7, 1, 2)
    expect(validateDocument).toHaveBeenCalledWith(7, 1, 2, expect.any(String))
    expect(vi.mocked(patchDocumentContent).mock.invocationCallOrder[0]!).toBeLessThan(vi.mocked(validateDocument).mock.invocationCallOrder[0]!)
    expect(saveStatus(page)).toBe('Saved')
  })

  it('does not check or export while a row cannot be saved, and says why in the dialog', async () => {
    const page = await mountPage()
    await press(page, 'Add row')
    await type(page, 'edit-action.item.task-0', 'Order seedlings')

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(norm(exportDialog(page).get('[role="alert"]').text())).toBe(
      'Your latest changes are not saved, so this version cannot be exported yet. Close this and check the message on the page.',
    )
    expect(patchDocumentContent).not.toHaveBeenCalled()
    expect(getLatestValidation).not.toHaveBeenCalled()
    expect(validateDocument).not.toHaveBeenCalled()
  })

  it('does not check a version the page could not load after saving it for Export', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT).mockRejectedValue(networkFailure())
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'Garden Club Planning')

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(validateDocument).not.toHaveBeenCalled()
    expect(norm(exportDialog(page).get('[role="alert"]').text())).toContain(
      'Your latest changes are not saved, so this version cannot be exported yet.',
    )
    expect(norm(notices(page).text())).toContain('The latest version of this document could not be loaded')
  })

  it('waits for a save already on its way before Export checks, and saves nothing twice', async () => {
    const save = deferred<DocumentRevisionResponse>()
    vi.mocked(patchDocumentContent).mockReturnValue(save.promise)
    vi.mocked(getDocument)
      .mockResolvedValueOnce(DOCUMENT)
      .mockResolvedValueOnce(documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Garden Club Planning') } })))
      .mockResolvedValue(documentAt(revision({ id: 3, revisionNumber: 3, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Garden Club Planning') } })))
    vi.mocked(validateDocument).mockResolvedValue(manifest({ revisionId: 3 }))
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'Garden Club Planning')
    await press(page, 'Save now')

    await press(page, 'Export', 'header.workspace-bar')
    expect(exportDialog(page).text()).toContain('Saving your changes first…')
    expect(validateDocument).not.toHaveBeenCalled()

    save.resolve(revision({ id: 2, revisionNumber: 2 }))
    await flushPromises()
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(validateDocument).toHaveBeenCalledWith(7, 1, 2, expect.any(String))
    expect(exportDialog(page).text()).toContain('Ready to export.')
  })

  // The conflict notice asks the person to choose between saving their edits onto the latest version and
  // discarding them, and the page's own saving waits for that choice. Export may not make it for them.
  it('does not save edits onto a version that moved on when Export is opened during a conflict', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const page = await mountPage()
    await type(page, 'edit-meeting.title', 'My title')
    vi.mocked(patchDocumentContent).mockRejectedValueOnce(STALE)
    vi.mocked(getDocument).mockResolvedValue(documentAt(revision({ id: 9, revisionNumber: 9, contentHash: HASH_B })))
    await press(page, 'Save now')
    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 10, revisionNumber: 10 }))

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(validateDocument).not.toHaveBeenCalled()
    expect(norm(exportDialog(page).get('[role="alert"]').text())).toContain(
      'Your latest changes are not saved, so this version cannot be exported yet.',
    )
  })

  it("closes Export from a finding's Go to button and puts the focus in that fill spot, on the page view", async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(validateDocument).mockResolvedValue(
      manifest({
        hasUnresolvedBlocking: true,
        findings: [{ code: 'REQUIRED_FIELD_MISSING', severity: 'BLOCKING', fieldId: 'meeting.date', message: 'Meeting date is required.' }],
      }),
    )
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()
    await press(page, 'Go to Meeting date', 'dialog.export-dialog')
    await flushPromises()

    expect(exportDialog(page).find('h2').exists()).toBe(false)
    expect(exportDialog(page).attributes('open')).toBeUndefined()
    expect(pressedOf(page, 'Page')).toBe('true')
    expect(document.activeElement?.id).toBe('edit-meeting.date')
  })

  it('takes a finding about a column with no rows to Add row, and says why', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(validateDocument).mockResolvedValue(
      manifest({
        hasUnresolvedBlocking: true,
        findings: [{ code: 'REQUIRED_FIELD_MISSING', severity: 'BLOCKING', fieldId: 'action.item.due', message: 'Add at least one action item.' }],
      }),
    )
    const page = await mountPage()
    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    await press(page, 'Go to Action item due', 'dialog.export-dialog')
    await flushPromises()

    expect(exportDialog(page).find('h2').exists()).toBe(false)
    expect(accessibleName(document.activeElement!)).toBe('Add row')
    expect(liveRegion(page).text()).toBe('Action item due has no rows yet. Add a row to fill it in.')
  })

  it('opens Version history, and a restore made there reloads the document and says so', async () => {
    const R1 = revision({ id: 1, revisionNumber: 1, fields: { 'meeting.title': scalarField('Weekly Sync') } })
    const R2 = revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, editReason: 'Autosaved.', fields: { 'meeting.title': scalarField('Spring Planning') } })
    const R3 = revision({ id: 3, revisionNumber: 3, fields: { 'meeting.title': scalarField('Weekly Sync') } })
    vi.mocked(getDocument).mockResolvedValueOnce(documentAt(R2)).mockResolvedValue(documentAt(R3))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2])
    vi.mocked(restoreRevision).mockResolvedValue({ revision: R3, keptLockedFieldIds: ['meeting.title'] })
    const page = await mountPage()

    await press(page, 'Version history', 'header.workspace-bar')
    expect(historyDialog(page).get('h2').text()).toBe('Version history')
    expect(listDocumentRevisions).toHaveBeenCalledWith(7, 1)
    expect(historyDialog(page).findAll('.revision-row')).toHaveLength(2)

    await press(page, 'Restore version 1', 'dialog.version-history')
    await press(page, 'Restore version 1', 'dialog.version-history')

    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 1, 2, expect.any(String))
    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(valueOf(page, 'edit-meeting.title')).toBe('Weekly Sync')
    expect(liveRegion(page).text()).toBe('Version restored. Meeting title kept its value because it is locked.')
    expect(historyDialog(page).find('h2').exists()).toBe(false)
  })

  it('reloads the document when Version history finds it moved on', async () => {
    const R1 = revision({ id: 1, revisionNumber: 1 })
    const R2 = revision({ id: 2, revisionNumber: 2, contentHash: HASH_B })
    vi.mocked(getDocument).mockResolvedValue(documentAt(R2))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2])
    vi.mocked(restoreRevision).mockRejectedValue(STALE)
    const page = await mountPage()

    await press(page, 'Version history', 'header.workspace-bar')
    await press(page, 'Restore version 1', 'dialog.version-history')
    await press(page, 'Restore version 1', 'dialog.version-history')

    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(historyDialog(page).get('[role="alert"]').text()).toBe(
      'This document changed since this list was loaded, so nothing was restored.',
    )
  })
})

// =================================================================================================

describe('WorkspaceView: print preview', () => {
  it('looks for a preview only once Print preview is chosen, and shows the one made for the current content', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(getLatestCompilation).mockResolvedValue(COMPILATION)
    const page = await mountPage()
    expect(getLatestCompilation).not.toHaveBeenCalled()

    await press(page, 'Print preview')
    // The preview component is loaded on demand; one more tick lets its (stubbed) chunk resolve.
    await flushPromises()

    expect(pressedOf(page, 'Print preview')).toBe('true')
    expect(page.get('section.document-page').isVisible()).toBe(false)
    expect(getLatestCompilation).toHaveBeenCalledWith(7, 1, 1)
    const preview = page.get('section.print-preview')
    expect(norm(preview.text())).toContain('The file as it exports, from version 1.')
    expect(preview.text()).not.toContain('has changed since')
    expect(page.get('[data-testid="pdf-preview"]').text()).toBe('/api/v1/workspaces/7/uploads/21/preview')
    expect(hasButton(page, 'Generate again')).toBe(true)
    expect((await axe(page.element)).violations).toEqual([])

    await press(page, 'Page')
    expect(page.find('section.print-preview').exists()).toBe(false)
    expect(page.get('section.document-page').isVisible()).toBe(true)
    expect(getLatestCompilation).toHaveBeenCalledTimes(1)
  })

  it('offers to generate a preview when none exists yet, and draws the one it made', async () => {
    vi.mocked(compileRevision).mockResolvedValue({ ...COMPILATION, pdfArtifactId: 31 })
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    expect(page.get('section.print-preview').text()).toContain('Generate a preview to see this document exactly as it exports.')
    expect(page.get('[data-testid="pdf-preview"]').text()).toBe('')
    await press(page, 'Generate preview')

    expect(compileRevision).toHaveBeenCalledWith(7, 1, 1)
    expect(page.get('[data-testid="pdf-preview"]').text()).toBe('/api/v1/workspaces/7/uploads/31/preview')
    expect(norm(page.get('section.print-preview').text())).toContain('The file as it exports, from version 1.')
  })

  it('says the preview is out of date once the values change, judged by content, and mentions unsaved changes before that', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(getLatestCompilation).mockResolvedValueOnce(COMPILATION).mockRejectedValue(nothingOnRecord())
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    await press(page, 'Page')
    await type(page, 'edit-meeting.title', 'Spring Planning')
    await press(page, 'Print preview')
    await flushPromises()
    expect(norm(page.get('section.print-preview').text())).toContain(
      'The file as it exports, from version 1. You have unsaved changes; they appear once saved and generated again.',
    )

    vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Spring Planning') } })),
    )
    await press(page, 'Save now')
    await flushPromises()

    expect(getLatestCompilation).toHaveBeenLastCalledWith(7, 1, 2)
    const preview = norm(page.get('section.print-preview').text())
    expect(preview).toContain('The file as it exports, from version 1. The document has changed since it was made.')
    expect(page.get('[data-testid="pdf-preview"]').text()).toBe('/api/v1/workspaces/7/uploads/21/preview')
  })

  it('keeps a preview current across a new version that changed no value, such as a check', async () => {
    vi.mocked(getDocument)
      .mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD)
      .mockResolvedValue(documentAt(revision({ id: 2, revisionNumber: 2, fields: DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields })))
    vi.mocked(getLatestCompilation).mockResolvedValue(COMPILATION)
    vi.mocked(validateDocument).mockResolvedValue(manifest({ revisionId: 2 }))
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(getLatestCompilation).toHaveBeenCalledTimes(1)
    const preview = norm(page.get('section.print-preview').text())
    expect(preview).toContain('The file as it exports, from version 1.')
    expect(preview).not.toContain('has changed since')
  })

  it("passes on the server's explanation when it cannot check for a preview", async () => {
    vi.mocked(getLatestCompilation).mockRejectedValue(RENDERER_BUSY)
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    const said = page.get('section.print-preview [role="alert"]').text()
    expect(said).toBe('Too many documents are being prepared right now. Nothing was changed; try again shortly.')
    expect(page.text()).not.toContain('Could not check for an existing preview. Try again.')
  })

  it('does not take a server without the preview route for a document with no preview yet', async () => {
    vi.mocked(getLatestCompilation).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/documents/1/revisions/1/compilation'))
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    expect(page.get('section.print-preview [role="alert"]').text()).toContain('older than this page and does not have document previews yet')
    expect(page.text()).not.toContain('Generate a preview to see this document exactly as it exports.')
  })

  it('says a server without the route, rather than its raw answer, when a preview cannot be made', async () => {
    vi.mocked(compileRevision).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/documents/1/revisions/1/compile'))
    const page = await mountPage()
    await press(page, 'Print preview')
    await flushPromises()

    await press(page, 'Generate preview')

    expect(page.get('section.print-preview [role="alert"]').text()).toContain('older than this page and does not have document previews yet')
    expect(page.text()).not.toContain('No static resource')
  })
})

// =================================================================================================

describe('WorkspaceView: the Rules card', () => {
  const RULES = [
    {
      id: 1,
      templateId: 1,
      templateVersionId: 1,
      category: 'VALIDATION',
      scope: { kind: 'WHOLE_TEMPLATE' },
      payload: { kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.title', 'meeting.date'] },
      status: 'ACCEPTED',
      humanExplanation: 'Every set of minutes names its meeting.',
      authorUserId: 1,
      createdAt: CREATED_AT,
    },
    {
      id: 2,
      templateId: 1,
      templateVersionId: 1,
      category: 'CONTENT',
      scope: { kind: 'FIELD', fieldId: 'meeting.title' },
      payload: { kind: 'MAX_TEXT_LENGTH', fieldId: 'meeting.title', maxCharacters: 80 },
      status: 'PROPOSED',
      humanExplanation: null,
      authorUserId: 1,
      createdAt: CREATED_AT,
    },
    {
      id: 3,
      templateId: 1,
      templateVersionId: 9,
      category: 'VALIDATION',
      scope: { kind: 'WHOLE_TEMPLATE' },
      payload: { kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.location'] },
      status: 'ACCEPTED',
      humanExplanation: null,
      authorUserId: 1,
      createdAt: CREATED_AT,
    },
  ] as never

  it("lists the accepted rules of the document's own template version, read-only, and counts the proposed ones", async () => {
    vi.mocked(listTemplateVersionRules).mockResolvedValue(RULES)
    const page = await mountPage()

    const card = norm(rulesCard(page).text())
    expect(card).toContain('Required before export: Meeting title, Meeting date.')
    expect(card).toContain('Require: Meeting title, Meeting date')
    expect(card).toContain('Every set of minutes names its meeting.')
    expect(card).not.toContain('Meeting location')
    expect(card).not.toContain('at most 80 characters')
    expect(card).toContain('1 proposed rule is waiting for a decision on the template.')
    expect(rulesCard(page).findAll('button')).toHaveLength(0)
    expect((await axe(page.element)).violations).toEqual([])
  })

  it('shows only the rules about the selected fill spot', async () => {
    vi.mocked(listTemplateVersionRules).mockResolvedValue(RULES)
    const page = await mountPage()

    await selectSpot(page, 'edit-meeting.date')
    expect(norm(rulesCard(page).text())).toContain('Require: Meeting title, Meeting date')

    await selectSpot(page, 'edit-meeting.location')
    const card = norm(rulesCard(page).text())
    expect(card).toContain('Text style · Meeting location')
    expect(card).toContain('No rule of this template is about Meeting location.')
    expect(card).not.toContain('Require:')
  })

  it("shows the text style a value takes when exported: the usual one, then the selected fill spot's own", async () => {
    vi.mocked(getTemplateLayout).mockResolvedValue(flowingMinutes({ ...BODY, bold: true, italic: true, fontSizeHalfPoints: 26 }))
    const page = await mountPage()
    const dateExample = () => rulesCard(page).find('.rules-card__date')

    expect(norm(rulesCard(page).text())).toContain('Text style · Fill spots')
    expect(styleChips(page)).toEqual(['Liberation Sans', '11 pt', 'Regular'])
    // How a filled date reads in the exported file, shown with today's date.
    expect(norm(dateExample().text())).toBe(`Dates read like ${todayLikeExport()}`)
    expect(norm(rulesCard(page).text())).toContain('Required before export: Meeting title, Meeting date.')

    await selectSpot(page, 'edit-meeting.title')
    expect(norm(rulesCard(page).text())).toContain('Text style · Meeting title')
    expect(styleChips(page)).toEqual(['Liberation Sans', '13 pt', 'Bold', 'Italic'])
    expect(dateExample().exists()).toBe(false)
    expect(rulesCard(page).text()).not.toContain('Required before export')

    await selectSpot(page, 'edit-meeting.date')
    expect(styleChips(page)).toEqual(['Liberation Sans', '11 pt', 'Regular'])
    expect(norm(dateExample().text())).toBe(`Dates read like ${todayLikeExport()}`)
  })

  it('says it is still reading the text style, and when it could not', async () => {
    const drawing = deferred<TemplateLayoutResponse>()
    vi.mocked(getTemplateLayout).mockReturnValueOnce(drawing.promise)
    const page = await mountPage()
    expect(rulesCard(page).find('ul[aria-label="Text style"]').exists()).toBe(false)
    expect(rulesCard(page).text()).toContain('Reading the template’s style…')
    page.unmount()

    vi.mocked(getTemplateLayout).mockRejectedValue(refusal(422, 'TEMPLATE_LAYOUT_UNAVAILABLE', 'Unreadable.'))
    const failed = await mountPage()
    expect(rulesCard(failed).text()).toContain('The template’s text style could not be read.')
    expect(rulesCard(failed).text()).not.toContain('How values look when exported')
  })

  it('says so when the rules cannot be loaded, and loads them again on request', async () => {
    vi.mocked(listTemplateVersionRules).mockRejectedValueOnce(networkFailure()).mockResolvedValue([])
    const page = await mountPage()

    expect(norm(rulesCard(page).text())).toContain("The template's rules could not be loaded.")
    await press(page, 'Try again', 'section[aria-labelledby="rules-heading"]')

    expect(listTemplateVersionRules).toHaveBeenCalledTimes(2)
    expect(norm(rulesCard(page).text())).toContain('Required before export: Meeting title, Meeting date. The template has no other rules.')
  })
})

// =================================================================================================

describe('WorkspaceView: the chat', () => {
  it('answers a line it cannot act on with what it can do, and puts a suggestion in the message box rather than sending it', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(NOT_RECOGNISED)
    const page = await mountPage()

    await send(page, 'write me a poem')

    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'write me a poem')
    expect(executeAssist).not.toHaveBeenCalled()
    expect(chatLog(page).get('.chat-line--person').text()).toBe('write me a poem')
    expect(chatLog(page).text()).toContain('I did not understand that, or it names a fill spot this document does not have. Here is what I can do:')
    const suggestions = chatLog(page).findAll('ul[aria-label="What you can ask"] button')
    expect(suggestions.map((button) => button.text())).toEqual(HELP)
    // The list is heard with the reply that introduces it, one request after another.
    expect(liveRegion(page).text()).toBe(
      'I did not understand that, or it names a fill spot this document does not have. Here is what I can do: ' +
        'Fill this in from my notes; Change field to value; Shorten field or rewrite field to how; Explain this finding.',
    )
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('')

    await suggestions[1]!.trigger('click')
    await flushPromises()
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('change meeting title to Spring Planning')
    expect(document.activeElement?.id).toBe('assist-composer')

    await suggestions[0]!.trigger('click')
    await flushPromises()
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('Fill this in from my notes')

    // Without an example, the words up to the first blank, for the person to finish.
    await suggestions[2]!.trigger('click')
    await flushPromises()
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('Shorten ')
    expect(interpretAssist).toHaveBeenCalledTimes(1)
    expect((await axe(page.element)).violations).toEqual([])
  })

  it("moves the focus to the new proposal when the proposal's own line asks for something else", async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    const page = await mountPage()
    await send(page, 'change meeting title to Spring Planning')

    const other = proposal(page).get('#proposal-other')
    ;(other.element as HTMLElement).focus()
    await other.setValue('change meeting title to Autumn Planning')
    vi.mocked(executeAssist).mockResolvedValue({
      ...TITLE_CHANGED,
      proposal: proposalOf({ 'meeting.title': proposedScalar('Autumn Planning') }, { id: 34 }),
    })
    await proposal(page).get('form').trigger('submit')
    await flushPromises()

    expect(norm(proposal(page).text())).toContain('Meeting title: Autumn Planning')
    expect(document.activeElement).toBe(proposal(page).element)
  })

  it('does a change straight away and fills it in once approved', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    vi.mocked(acceptPatchProposal).mockResolvedValue(acceptedProposal({ 'meeting.title': 'CLEAN' }))
    const page = await mountPage()

    await send(page, 'change meeting title to Spring Planning', 'button')

    expect(executeAssist).toHaveBeenCalledWith(7, 1, 'change meeting title to Spring Planning', 1)
    const offer = proposal(page)
    expect(offer.text()).toContain('Here is the change you asked for:')
    expect(norm(offer.text())).toContain('Meeting title: Spring Planning')
    expect(offer.text()).toContain('Can you confirm? I will fill in the document once you approve it.')
    expect(liveRegion(page).text()).toBe('Brownie proposed a change. Check it, then approve it to update the document.')
    expect(acceptPatchProposal).not.toHaveBeenCalled()

    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Spring Planning') } })),
    )
    await press(page, 'I approve, fill it in', '.proposal')

    expect(acceptPatchProposal).toHaveBeenCalledWith(7, 1, 33, 1, expect.any(String))
    expect(norm(chatLog(page).text())).toContain('Filled in 1 value. Check each one before you export.')
    expect(proposal(page).exists()).toBe(false)
    expect(valueOf(page, 'edit-meeting.title')).toBe('Spring Planning')
    expect(liveRegion(page).text()).toBe('Filled in 1 value. Check each one before you export.')
  })

  it('sends on Enter but not on Shift+Enter, and clears the message box once sent', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(NOT_RECOGNISED)
    const page = await mountPage()
    const box = page.get('#assist-composer')

    await box.setValue('first line')
    await box.trigger('keydown', { key: 'Enter', shiftKey: true })
    await flushPromises()
    expect(interpretAssist).not.toHaveBeenCalled()

    await box.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'first line')
    expect((box.element as HTMLTextAreaElement).value).toBe('')
  })

  it('says it is working while it reads a line, and does not send a second one meanwhile', async () => {
    const reading = deferred<AssistInterpretationResponse>()
    vi.mocked(interpretAssist).mockReturnValue(reading.promise)
    const page = await mountPage()

    await send(page, 'write me a poem')
    expect(chatLog(page).get('.chat-typing').text()).toBe('Working on it…')
    expect(buttonNamed(page, 'Send')!.attributes('aria-disabled')).toBe('true')
    await send(page, 'and another')
    expect(interpretAssist).toHaveBeenCalledTimes(1)

    reading.resolve(NOT_RECOGNISED)
    await flushPromises()
    expect(chatLog(page).find('.chat-typing').exists()).toBe(false)
  })

  it('offers a rewrite as a suggestion, and leaves the document alone on Not now', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(interpretAssist).mockResolvedValue({ ...CHANGE_TITLE, kind: 'REWRITE_FIELD', summary: 'Shorten Meeting title.', usesModel: true })
    vi.mocked(executeAssist).mockResolvedValue({ ...TITLE_CHANGED, kind: 'REWRITE_FIELD', proposal: proposalOf({ 'meeting.title': proposedScalar('Sync') }) })
    const page = await mountPage()

    await send(page, 'shorten meeting title')
    expect(proposal(page).text()).toContain('Here is my suggestion:')
    expect(norm(proposal(page).text())).toContain('Meeting title: Sync')

    await press(page, 'Not now', '.proposal')
    expect(proposal(page).exists()).toBe(false)
    expect(chatLog(page).text()).toContain('All right, I left the document as it is.')
    expect(acceptPatchProposal).not.toHaveBeenCalled()
    expect(valueOf(page, 'edit-meeting.title')).toBe('Weekly Sync')
  })

  it('sends what the person types on the last line of a proposal as a new request', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    const page = await mountPage()
    await send(page, 'change meeting title to Spring Planning')

    vi.mocked(interpretAssist).mockResolvedValue(NOT_RECOGNISED)
    await page.get('#proposal-other').setValue('make it Summer Planning instead')
    page.get('#proposal-other').element.closest('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await flushPromises()

    expect(interpretAssist).toHaveBeenLastCalledWith(7, 1, 'make it Summer Planning instead')
    expect(acceptPatchProposal).not.toHaveBeenCalled()
  })

  it('shows an explanation as a quoted line, with no proposal', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'EXPLAIN_FINDING',
      summary: 'Explain the finding on Meeting date: Meeting date is required.',
      scope: { fieldId: 'meeting.date', label: 'Meeting date', currentValue: null, findingMessage: 'Meeting date is required.' },
      executable: true,
      usesModel: true,
      help: [],
    })
    vi.mocked(executeAssist).mockResolvedValue({
      kind: 'EXPLAIN_FINDING',
      summary: 'Explain the finding on Meeting date.',
      proposal: null,
      help: [],
      explanation: 'The meeting date is empty; type it in the Meeting date field.',
    })
    const page = await mountPage()

    await send(page, 'explain this finding')

    expect(chatLog(page).get('blockquote.chat-line__quote').text()).toBe('The meeting date is empty; type it in the Meeting date field.')
    expect(proposal(page).exists()).toBe(false)
    expect(liveRegion(page).text()).toBe('The meeting date is empty; type it in the Meeting date field.')
  })

  it('cannot fill the document without a source, and starts reading one straight away when there is', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(DRAFT)
    const page = await mountPage()
    await send(page, 'fill it in')
    expect(chatLog(page).text()).toContain('Add your notes or a transcript with Add a source (+) first, then ask me again.')
    expect(liveRegion(page).text()).toBe('Add your notes or a transcript with Add a source (+) first, then ask me again.')
    expect(startExtraction).not.toHaveBeenCalled()
    expect(executeAssist).not.toHaveBeenCalled()

    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
    const withSource = await mountPage()
    await send(withSource, 'fill it in')
    expect(startExtraction).toHaveBeenCalledWith(7, 1, 5, expect.any(String))
    expect(executeAssist).not.toHaveBeenCalled()
  })

  it('does not start a second reading while one is under way', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('LEASED')])
    vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
    const page = await mountPage()

    await askToFill(page)

    expect(chatLog(page).text()).toContain('I am already reading a source for this document. Wait for it to finish, or stop it first.')
    expect(startExtraction).not.toHaveBeenCalled()
  })

  it('reloads and explains when the document moved on before the request ran', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockRejectedValue(STALE)
    const page = await mountPage()
    const loadsBefore = vi.mocked(getDocument).mock.calls.length

    await send(page, 'change meeting title to X')

    expect(vi.mocked(getDocument).mock.calls.length).toBe(loadsBefore + 1)
    expect(lastChatError(page)).toBe('This document changed since you loaded it, so it was reloaded. Ask again on the current version.')
  })

  it('does not claim a reload that failed after the document moved on', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD).mockRejectedValue(networkFailure())
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockRejectedValue(STALE)
    const page = await mountPage()

    await send(page, 'change meeting title to Spring Planning')

    expect(lastChatError(page)).toBe('This document changed since you loaded it, so that was not done.')
    expect(page.text()).not.toContain('so it was reloaded')
  })

  it('reloads and says so when a proposal is approved after the document moved on', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    vi.mocked(acceptPatchProposal).mockRejectedValue(STALE)
    const page = await mountPage()
    await send(page, 'change meeting title to Spring Planning')

    await press(page, 'I approve, fill it in', '.proposal')

    expect(getDocument).toHaveBeenCalledTimes(2)
    expect(proposal(page).get('[role="alert"]').text()).toBe(
      'This document changed since I made this proposal, so it was reloaded. Ask me again on the current version.',
    )
  })

  it('names the fields it left as they were when approving filled in only some', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(DRAFT)
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
    vi.mocked(applyGenerationResult).mockResolvedValue(
      proposalOf({ 'meeting.title': proposedScalar('Spring Planning'), 'meeting.date': proposedScalar('2026-03-12', 'DATE') }),
    )
    vi.mocked(acceptPatchProposal).mockResolvedValue(acceptedProposal({ 'meeting.title': 'CLEAN', 'meeting.date': 'LOCKED' }))
    const page = await mountPage()
    await press(page, 'Show what I found', '.run-status')
    expect(norm(proposal(page).text())).toContain('Meeting date: March 12, 2026')

    await press(page, 'I approve, fill it in', '.proposal')

    expect(norm(chatLog(page).text())).toContain(
      'Filled in 1 value. I left Meeting date as it was: locked, or changed since I read the document. Check each one before you export.',
    )
    // What the reading found stays on offer, as a second look.
    expect(hasButton(page, 'Show what I found again', '.run-status')).toBe(true)
  })

  it('says nothing was filled in when approving filled in none of the values', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    vi.mocked(acceptPatchProposal).mockResolvedValue({ ...acceptedProposal({ 'meeting.title': 'CONFLICT' }), applied: false })
    const page = await mountPage()
    await send(page, 'change meeting title to Spring Planning')

    await press(page, 'I approve, fill it in', '.proposal')

    expect(norm(chatLog(page).text())).toContain(
      'Nothing was filled in. I left Meeting title as it was: locked, or changed since I read the document.',
    )
    expect(chatLog(page).text()).not.toContain('Filled in 0 values')
    expect(liveRegion(page).text()).toBe('Nothing was filled in. I left Meeting title as it was: locked, or changed since I read the document.')
  })

  it('says Brownie could not be reached when a line cannot be read, and puts the words back to send again', async () => {
    vi.mocked(interpretAssist).mockRejectedValue(networkFailure())
    const page = await mountPage()

    await send(page, 'change meeting title to Spring Planning')

    expect(lastChatError(page)).toBe('Brownie could not be reached. Check your connection, then try again.')
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('change meeting title to Spring Planning')
  })

  it('says the document is gone when a request finds it in the trash', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockRejectedValue(DOCUMENT_GONE)
    const page = await mountPage()

    await send(page, 'change meeting title to Spring Planning')

    expect(lastChatError(page)).toBe('This document is no longer available, for example because it was moved to the trash.')
    expect(page.text()).not.toContain('No document 1 in this workspace.')
  })

  it('says a server without the route, rather than its raw answer, when a request cannot run', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/documents/1/assist/execute'))
    const page = await mountPage()

    await send(page, 'change meeting title to Spring Planning')

    expect(lastChatError(page)).toContain('older than this page')
    expect(page.text()).not.toContain('No static resource')
  })

  it("passes on the server's explanation when a proposal cannot be approved", async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    vi.mocked(acceptPatchProposal).mockRejectedValue(RATE_LIMITED)
    const page = await mountPage()
    await send(page, 'change meeting title to Spring Planning')

    await press(page, 'I approve, fill it in', '.proposal')

    expect(proposal(page).get('[role="alert"]').text()).toBe('Too many requests in a short time. Wait 12 seconds and try again.')
    expect(page.text()).not.toContain('Could not fill these in. Try again.')
  })
})

// =================================================================================================

describe('WorkspaceView: Brownie reading a source', () => {
  it('leaves the focus alone when the person moved it out of the conversation before the reading changed', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    let answer: (job: ReturnType<typeof jobResponse>) => void = () => {}
    vi.mocked(getJob).mockReturnValueOnce(new Promise((resolve) => (answer = resolve)))
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
    const page = await mountPage()
    await askToFill(page)

    // The person was in the conversation, then clicked the page's plain text: the focus is on nothing.
    ;(chatLog(page).element as HTMLElement).focus()
    ;(document.activeElement as HTMLElement).blur()
    answer(jobResponse('WAITING_FOR_INPUT'))
    await flushPromises()

    expect(runStatus(page).text()).toContain('A few things need your answer before I can finish.')
    expect(document.activeElement).toBe(document.body)
  })

  it('reads an uploaded source as soon as it is asked, asks its question, carries on once answered, and proposes what it found', async () => {
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('WAITING_FOR_INPUT'))
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
    const page = await mountPage()
    await attachUpload(page)
    expect(sourceCards(page).map((card) => norm(card.get('.source-card__name').text()))).toEqual(['minutes.txt'])
    expect(liveRegion(page).text()).toBe('minutes.txt attached.')

    await askToFill(page)

    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'Fill this in from my notes')
    expect(startExtraction).toHaveBeenCalledWith(7, 1, 5, expect.any(String))
    expect(getGenerationQuestions).toHaveBeenCalledWith(7, 1, 42)
    const asked = norm(runStatus(page).text())
    expect(asked).toContain('A few things need your answer before I can finish.')
    expect(asked).toContain('Meeting title: the source says something different from what the document holds. Which should it be?')
    const choices = runStatus(page).get('[role="group"][aria-label="Your answer for Meeting title"]')
    expect(choices.findAll('button.choices__option').map((button) => accessibleName(button.element))).toEqual([
      'Old Title',
      'Weekly Robotics Club Sync',
    ])
    expect(liveRegion(page).text()).toBe('Brownie has a question for you.')

    vi.mocked(answerQuestion).mockResolvedValue({ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Executive Committee Sync' })
    vi.mocked(resumeGeneration).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockResolvedValue({ artifactId: 77 })
    vi.mocked(applyGenerationResult).mockResolvedValue(proposalOf({ 'meeting.title': proposedScalar('Executive Committee Sync') }))
    await answerOther(page, 9, 'Executive Committee Sync')

    expect(answerQuestion).toHaveBeenCalledWith(7, 9, 'Executive Committee Sync')
    expect(resumeGeneration).toHaveBeenCalledWith(7, 1, 42, expect.any(String))
    // Started on this visit, so what it found is turned into a proposal without a click, once.
    expect(applyGenerationResult).toHaveBeenCalledTimes(1)
    expect(applyGenerationResult).toHaveBeenCalledWith(7, 1, 42)
    const offer = proposal(page)
    expect(offer.text()).toContain('Here is what I found in minutes.txt:')
    expect(norm(offer.text())).toContain('Meeting title: Executive Committee Sync')
    expect(liveRegion(page).text()).toBe('Brownie found values for this document. Check them, then approve them to fill them in.')

    vi.mocked(acceptPatchProposal).mockResolvedValue(acceptedProposal({ 'meeting.title': 'CLEAN' }))
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, fields: { 'meeting.title': scalarField('Executive Committee Sync') } })),
    )
    await press(page, 'I approve, fill it in', '.proposal')

    expect(acceptPatchProposal).toHaveBeenCalledWith(7, 1, 501, 1, expect.any(String))
    expect(norm(chatLog(page).text())).toContain('Filled in 1 value. Check each one before you export.')
    expect(valueOf(page, 'edit-meeting.title')).toBe('Executive Committee Sync')
  })

  it('carries on only once the last open question has an answer, taken from a numbered choice', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION, MISSING_DATE_QUESTION])
    vi.mocked(resumeGeneration).mockResolvedValue(accepted())
    vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
    const page = await mountPage()

    expect(norm(runStatus(page).text())).toContain('Meeting date: I could not find this in the source. What should it be?')
    vi.mocked(answerQuestion).mockResolvedValueOnce({ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Weekly Robotics Club Sync' })
    await press(page, 'Weekly Robotics Club Sync', '.run-status')

    expect(answerQuestion).toHaveBeenCalledWith(7, 9, 'Weekly Robotics Club Sync')
    expect(runStatus(page).text()).toContain('You answered: Weekly Robotics Club Sync')
    expect(resumeGeneration).not.toHaveBeenCalled()
    // The answered choices went away; the focus goes on to the question still open.
    expect(accessibleName(document.activeElement!)).toBe('2026-03-12')

    vi.mocked(answerQuestion).mockResolvedValueOnce({ ...MISSING_DATE_QUESTION, status: 'ANSWERED', answerValue: '2026-03-12' })
    await press(page, '2026-03-12', '.run-status')

    expect(answerQuestion).toHaveBeenLastCalledWith(7, 10, '2026-03-12')
    expect(resumeGeneration).toHaveBeenCalledTimes(1)
    expect(resumeGeneration).toHaveBeenCalledWith(7, 1, 42, expect.any(String))
    expect(startExtraction).not.toHaveBeenCalled()
  })

  it('lists every proposed row and names each row it could not complete', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockResolvedValue({ artifactId: 77 })
    vi.mocked(applyGenerationResult).mockResolvedValue(
      proposalOf(
        {
          'meeting.title': proposedScalar('Weekly Robotics Club Sync'),
          'action.item.task': proposedRows(['finish wiring the practice robot', 'confirm the van reservation']),
          'action.item.owner': proposedRows(['Alex Chen', 'Jose Nunez']),
          'action.item.due': proposedRows(['2026-03-12', '2026-03-10'], 'DATE'),
        },
        {
          proposedRepeatedItemCount: 2,
          skippedRepeatedItems: [{ itemIndex: 2, unresolvedFieldIds: ['action.item.due'], description: 'order the new batteries' }],
        },
      ),
    )
    const page = await mountPage()

    await askToFill(page)

    const lines = proposal(page).findAll('.proposal__values li').map((line) => norm(line.text()))
    expect(lines).toEqual([
      'Meeting title: Weekly Robotics Club Sync',
      'Row 1: Action item task: finish wiring the practice robot; Action item owner: Alex Chen; Action item due: March 12, 2026',
      'Row 2: Action item task: confirm the van reservation; Action item owner: Jose Nunez; Action item due: March 10, 2026',
    ])
    const skipped = proposal(page).get('.proposal__skipped')
    expect(skipped.attributes('role')).toBe('status')
    expect(norm(skipped.text())).toContain(
      'I also found 1 row I could not complete, because a detail was missing. Fill it in yourself before exporting:',
    )
    expect(norm(skipped.text())).toContain('order the new batteries (missing: Action item due)')
    expect((await axe(page.element)).violations).toEqual([])
  })

  it('reads the source chosen in the message box when there is more than one, and says which one it reads', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([
      { id: 3, artifactId: 5, displayFilename: 'march-notes.txt', kind: 'ARTIFACT', fetchedAt: CREATED_AT, attachedAt: CREATED_AT },
      { id: 2, artifactId: 4, displayFilename: 'february-notes.txt', kind: 'ARTIFACT', fetchedAt: '2026-02-01T00:00:00Z', attachedAt: '2026-02-01T00:00:00Z' },
    ])
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
    const page = await mountPage()

    expect(listDocumentSources).toHaveBeenCalledWith(7, 1)
    const cards = sourceCards(page).map((card) => norm(card.text()))
    expect(cards[0]).toContain('march-notes.txt')
    expect(cards[0]).toContain('Brownie reads this one')
    expect(cards[1]).toContain('february-notes.txt')
    expect(cards[1]).not.toContain('Brownie reads this one')

    await page.get('#extract-source').setValue('2')
    expect(norm(sourceCards(page)[1]!.text())).toContain('Brownie reads this one')
    await askToFill(page)

    expect(startExtraction).toHaveBeenCalledWith(7, 1, 4, expect.any(String))
    expect(runStatus(page).text()).toContain('Reading february-notes.txt…')
    expect(page.get('#extract-source').attributes('disabled')).toBeDefined()
  })

  it('stops asking about a run once the person has left the page', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('LEASED')])
    const first = deferred<JobResponse>()
    vi.mocked(getJob).mockReturnValueOnce(first.promise).mockResolvedValue(jobResponse('LEASED'))
    const page = await mountPage()
    expect(getJob).toHaveBeenCalledTimes(1)

    const waits = vi.spyOn(window, 'setTimeout')
    page.unmount()
    first.resolve(jobResponse('LEASED'))
    await flushPromises()
    // Had it kept following the run, it would now be waiting a moment to ask again.
    expect(waits.mock.calls.filter(([, delay]) => (delay ?? 0) >= 1000)).toHaveLength(0)
    waits.mockRestore()
    expect(getJob).toHaveBeenCalledTimes(1)
  })

  it('picks up a run waiting for answers straight from the server, starting nothing', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
    const page = await mountPage()

    expect(listGenerationRuns).toHaveBeenCalledWith(7, 1)
    expect(getGenerationQuestions).toHaveBeenCalledWith(7, 1, 42)
    expect(runStatus(page).text()).toContain('A few things need your answer before I can finish.')
    expect(hasButton(page, 'Weekly Robotics Club Sync', '.run-status')).toBe(true)
    expect(startExtraction).not.toHaveBeenCalled()
  })

  it('offers what a run found before this visit only on request, even when its source is no longer attached', async () => {
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
    vi.mocked(applyGenerationResult).mockResolvedValue(proposalOf({ 'meeting.title': proposedScalar('Spring Planning') }))
    const page = await mountPage()

    expect(runStatus(page).text()).toContain('I finished reading your source.')
    expect(applyGenerationResult).not.toHaveBeenCalled()
    await press(page, 'Show what I found', '.run-status')

    expect(applyGenerationResult).toHaveBeenCalledTimes(1)
    expect(applyGenerationResult).toHaveBeenCalledWith(7, 1, 42)
    expect(proposal(page).text()).toContain('Here is what I found in your source:')
    expect(norm(proposal(page).text())).toContain('Meeting title: Spring Planning')
  })

  it('moves the focus to the proposal Show what I found brings up, and reports no failure', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
    vi.mocked(applyGenerationResult).mockResolvedValue(proposalOf({ 'meeting.title': proposedScalar('Spring Planning') }))
    const page = await mountPage()

    await press(page, 'Show what I found', '.run-status')

    expect(proposal(page).find('[role="alert"]').exists()).toBe(false)
    expect(proposal(page).text()).not.toContain('Brownie could not be reached')
    // The button went away with the reading's status; the proposal it showed takes the focus.
    expect(document.activeElement).toBe(proposal(page).element)
  })

  it('says a run found stopped before this visit changed nothing, and offers no way to start it again', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('CANCELLED')])
    const page = await mountPage()

    expect(runStatus(page).text()).toContain('I stopped reading. Nothing on the document changed.')
    expect(hasButton(page, 'Try this reading again')).toBe(false)
  })

  it('starts a run that gave up again as the same run, and turns what it found into a proposal by itself', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('DEAD')])
    vi.mocked(retryJob).mockResolvedValue(jobResponse('QUEUED'))
    vi.mocked(getJob).mockResolvedValue(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockResolvedValue({ artifactId: 900 })
    vi.mocked(applyGenerationResult).mockResolvedValue(proposalOf({ 'meeting.title': proposedScalar('Spring Planning') }))
    const page = await mountPage()

    expect(runStatus(page).get('[role="alert"]').text()).toBe('The last reading gave up before it could finish.')
    // The job queue's own name for that ending is not a word for a person.
    expect(page.text()).not.toContain('DEAD')

    await press(page, 'Try this reading again', '.run-status')

    expect(retryJob).toHaveBeenCalledWith(7, 42)
    // Nothing new was started: the same job is what gets followed.
    expect(startExtraction).not.toHaveBeenCalled()
    expect(getJob).toHaveBeenCalledWith(7, 42)
    expect(applyGenerationResult).toHaveBeenCalledTimes(1)
    expect(page.text()).not.toContain('gave up')
    expect(hasButton(page, 'Try this reading again')).toBe(false)
  })

  it('says to ask again when the document has changed since the run that gave up', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('FAILED')])
    vi.mocked(retryJob).mockRejectedValue(refusal(409, 'JOB_TARGET_STALE'))
    const page = await mountPage()

    await press(page, 'Try this reading again', '.run-status')

    expect(runStatus(page).get('[role="alert"]').text()).toBe(
      'This document has changed since that reading began, so it cannot be started again. Ask me to fill it again instead.',
    )
    expect(getJob).not.toHaveBeenCalled()
    // An offer that can never succeed is not made a second time.
    expect(hasButton(page, 'Try this reading again')).toBe(false)
    expect((await axe(page.element)).violations).toEqual([])
  })

  it('shows what the run really became when another tab already restarted it and it has since finished', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns)
      .mockResolvedValueOnce([generationRun('DEAD')])
      .mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
    vi.mocked(retryJob).mockRejectedValue(refusal(409, 'CONFLICT'))
    const page = await mountPage()

    await press(page, 'Try this reading again', '.run-status')

    expect(listGenerationRuns).toHaveBeenCalledTimes(2)
    expect(runStatus(page).text()).not.toContain('gave up')
    expect(runStatus(page).text()).not.toContain('Could not start this reading again')
    expect(hasButton(page, 'Try this reading again')).toBe(false)
    expect(hasButton(page, 'Show what I found', '.run-status')).toBe(true)
    expect(applyGenerationResult).not.toHaveBeenCalled()
  })

  it('asks to stop a reading, says Stopping until the job really ends, then says it stopped', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('LEASED')])
    vi.mocked(getJob)
      .mockResolvedValueOnce(jobResponse('LEASED'))
      .mockResolvedValueOnce(jobResponse('CANCEL_REQUESTED', { cancellationRequestedAt: '2026-03-01T00:00:10Z' }))
      .mockResolvedValue(jobResponse('CANCELLED'))
    vi.mocked(cancelJob).mockResolvedValue({ ...accepted(), operation: 'job.request-cancellation' })
    vi.useFakeTimers()
    const page = await mountPage()

    expect(runStatus(page).text()).toContain('Reading notes.txt and filling in the fields.')
    await press(page, 'Stop reading', '.run-status')
    expect(cancelJob).toHaveBeenCalledWith(7, 42, expect.any(String))
    const stopping = buttonNamed(page, 'Stopping…', '.run-status')!
    expect(stopping.attributes('aria-disabled')).toBe('true')
    await stopping.trigger('click')
    expect(cancelJob).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1_500)
    expect(runStatus(page).text()).toContain('Stopping.')
    await vi.advanceTimersByTimeAsync(1_500)

    expect(runStatus(page).text()).toContain('I stopped reading. Nothing on the document changed.')
    expect(liveRegion(page).text()).toBe('Brownie stopped reading. Nothing on the document changed.')
  })

  it('follows a reading stopped while it waits for answers until it has really stopped', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
    vi.mocked(cancelJob).mockResolvedValue({ ...accepted(), operation: 'job.request-cancellation' })
    vi.mocked(getJob).mockResolvedValue(jobResponse('CANCELLED'))
    const page = await mountPage()

    await press(page, 'Stop reading', '.run-status')

    expect(getJob).toHaveBeenCalledWith(7, 42)
    expect(runStatus(page).text()).toContain('I stopped reading. Nothing on the document changed.')
  })

  it('says when a queued reading has had no worker pick it up for half a minute', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValue(jobResponse('QUEUED', { attemptCount: 0 }))
    const page = await mountPage()

    vi.useFakeTimers()
    await askToFill(page)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(runStatus(page).text()).toContain('Waiting for a worker to pick this up.')
    expect(runStatus(page).text()).not.toContain('No worker has picked this up yet')
    await vi.advanceTimersByTimeAsync(30_000)

    expect(norm(runStatus(page).text())).toContain(
      'No worker has picked this up yet. If the Brownie worker is not running, the reading waits until it is; you can stop it ' +
        'and start it again once the worker is running.',
    )
    expect(page.text()).not.toContain('QUEUED')
  })

  it('stops checking after ten minutes and offers to check again, which reads the run back', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValue(jobResponse('LEASED'))
    const page = await mountPage()

    vi.useFakeTimers()
    await askToFill(page)
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 3_000)
    const calls = vi.mocked(getJob).mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(vi.mocked(getJob).mock.calls.length).toBe(calls)

    expect(runStatus(page).text()).toContain('Still reading after ten minutes of checking. It carries on on the server.')
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
    // Still on the test clock: the button was drawn ten minutes into it, and Vue ignores a click stamped before that.
    await press(page, 'Check again', '.run-status')

    expect(listGenerationRuns).toHaveBeenCalledTimes(2)
    expect(hasButton(page, 'Check again')).toBe(false)
    expect(hasButton(page, 'Show what I found', '.run-status')).toBe(true)
  })

  it("describes a run under way in words, not by the job queue's state name", async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('LEASED')])
    vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
    const page = await mountPage()

    expect(runStatus(page).text()).toContain('Reading notes.txt and filling in the fields.')
    expect(page.text()).not.toContain('LEASED')
    expect(hasButton(page, 'Stop reading', '.run-status')).toBe(true)
  })

  it("says a run that failed while being followed gave up, without the job queue's state name, and offers to start it again", async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValue(jobResponse('FAILED'))
    const page = await mountPage()

    await askToFill(page)

    expect(runStatus(page).get('[role="alert"]').text()).toBe('This reading gave up before it could finish.')
    expect(page.text()).not.toContain('FAILED')
    expect(hasButton(page, 'Try this reading again', '.run-status')).toBe(true)
  })

  it('says so when a finished reading found none of the values', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockResolvedValue(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockResolvedValue({ artifactId: 900 })
    vi.mocked(applyGenerationResult).mockRejectedValue(refusal(422, 'GENERATION_RESULT_EMPTY', 'The result holds no values.'))
    const page = await mountPage()

    await askToFill(page)

    expect(runStatus(page).get('[role="alert"]').text()).toBe("I could not find any of this document's values in notes.txt.")
    expect(proposal(page).exists()).toBe(false)
  })
})

// =================================================================================================

describe('WorkspaceView says what went wrong, and keeps what the person has', () => {
  describe('when the document is loaded again after an action', () => {
    it('keeps the page, the typed values and keyboard focus when the reload fails, and says the page may be out of date', async () => {
      vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD).mockRejectedValueOnce(networkFailure())
      vi.mocked(recordReviewDecision).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
      const page = await mountPage()
      const date = byId(page, 'edit-meeting.date').element
      await type(page, 'edit-meeting.date', '2026-04-09')
      await selectSpot(page, 'edit-meeting.title')
      const accept = buttonNamed(page, 'Accept Meeting title', 'section.selection-bar')!.element

      await press(page, 'Accept Meeting title', 'section.selection-bar')

      expect(page.text()).not.toContain('Could not load this document')
      expect(byId(page, 'edit-meeting.date').element).toBe(date)
      expect(valueOf(page, 'edit-meeting.date')).toBe('2026-04-09')
      // The button the person pressed is still there, with the focus on it.
      expect(accept.isConnected).toBe(true)
      expect(document.activeElement).toBe(accept)
      expect(norm(notices(page).text())).toContain(
        'The latest version of this document could not be loaded, so what is shown here may be out of date. ' +
          'Brownie could not be reached. Check your connection, then try again.',
      )
      expect((await axe(page.element)).violations).toEqual([])
    })

    it('says the document went to the trash, and links there, when a reload finds it gone', async () => {
      vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD).mockRejectedValueOnce(DOCUMENT_GONE)
      vi.mocked(setFieldLock).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
      const page = await mountPage()
      await selectSpot(page, 'edit-meeting.title')

      await press(page, 'Lock Meeting title', 'section.selection-bar')

      expect(norm(notices(page).text())).toContain(
        'This document is no longer available, for example because it was moved to the trash in another tab. ' +
          'What is shown here is the last version this page loaded.',
      )
      expect(notices(page).find('a[href="/trash"]').exists()).toBe(true)
      expect(byId(page, 'edit-meeting.title').exists()).toBe(true)
    })

    it('keeps what was just saved on screen when the reload after the save fails', async () => {
      vi.mocked(getDocument)
        .mockResolvedValueOnce(DOCUMENT)
        .mockRejectedValueOnce(refusal(503, 'DATABASE_UNAVAILABLE', 'The service cannot reach its database right now. Nothing was changed; try again shortly.'))
      vi.mocked(patchDocumentContent).mockResolvedValue(revision({ id: 2, revisionNumber: 2 }))
      const page = await mountPage()

      await type(page, 'edit-meeting.title', 'Garden Club Planning')
      await press(page, 'Save now')

      expect(valueOf(page, 'edit-meeting.title')).toBe('Garden Club Planning')
      expect(saveStatus(page)).toBe('Saved')
      expect(liveRegion(page).text()).toBe('Saved.')
      expect(norm(notices(page).text())).toContain('The service cannot reach its database right now.')
    })

    it('does not name a current version it could not load after a conflicting save', async () => {
      vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT).mockRejectedValueOnce(networkFailure())
      vi.mocked(patchDocumentContent).mockRejectedValue(STALE)
      const page = await mountPage()

      await type(page, 'edit-meeting.title', 'My title')
      await press(page, 'Save now')

      expect(norm(notices(page).get('.conflict-notice').text())).toContain(
        'This document changed since you started editing, and its latest version could not be loaded, so nothing was saved. ' +
          'Your edits are still on the page.',
      )
      expect(page.text()).not.toContain('is now current')
      expect(valueOf(page, 'edit-meeting.title')).toBe('My title')
    })

    it('does not claim a reload that failed after a review decision hit a version that moved on', async () => {
      vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD).mockRejectedValueOnce(networkFailure())
      vi.mocked(recordReviewDecision).mockRejectedValue(STALE)
      const page = await mountPage()
      await selectSpot(page, 'edit-meeting.title')

      await press(page, 'Accept Meeting title', 'section.selection-bar')

      expect(norm(notices(page).text())).toContain('This document changed since you loaded it, so that was not done.')
      expect(page.text()).not.toContain('so it was reloaded')
    })

    it('says a document that cannot be found on the first load may be in the trash, and links there', async () => {
      vi.mocked(getDocument).mockRejectedValue(DOCUMENT_GONE)
      const page = await mountPage()

      const said = page.get('[role="alert"]')
      expect(said.text()).toContain('This document is not available. It may have been moved to the trash, where it can be restored.')
      expect(said.find('a[href="/trash"]').exists()).toBe(true)
      expect(said.text()).toContain('Back to your documents')
      expect(page.find('.workspace').exists()).toBe(false)
    })

    it('says Brownie could not be reached when the first load gets no answer', async () => {
      vi.mocked(getDocument).mockRejectedValue(networkFailure())
      const page = await mountPage()

      expect(page.get('[role="alert"]').text()).toContain('Brownie could not be reached.')
      expect(page.find('a[href="/trash"]').exists()).toBe(false)
    })
  })

  describe('when a save is refused', () => {
    it.each([
      ['the document was moved to the trash', DOCUMENT_GONE, 'this document is no longer available, for example because it was moved to the trash', true],
      ['the change is larger than one save may be', refusal(413, 'CONTENT_TOO_LARGE', 'A request body may be at most 1048576 bytes.'), 'A request body may be at most 1048576 bytes.', false],
      ['the database is out of reach', refusal(503, 'DATABASE_UNAVAILABLE', 'The service cannot reach its database right now.'), 'The service cannot reach its database right now.', false],
      ['the session ended', refusal(401, 'UNAUTHORIZED', 'Full authentication is required.'), 'Your session has ended.', false],
      ['no answer came back', networkFailure(), 'Brownie could not be reached.', false],
    ])('says so when %s, and keeps the typed value', async (_case, error, expected, linksToTrash) => {
      vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
      vi.mocked(patchDocumentContent).mockRejectedValue(error)
      const page = await mountPage()

      await type(page, 'edit-meeting.title', 'Typed title')
      await press(page, 'Save now')

      const said = norm(notices(page).get('[role="alert"]').text())
      expect(said).toContain(expected)
      expect(said).not.toContain('Could not save your changes. Try again.')
      expect(page.find('a[href="/trash"]').exists()).toBe(linksToTrash)
      expect(valueOf(page, 'edit-meeting.title')).toBe('Typed title')
      expect(saveStatus(page)).toBe('Not saved')
    })
  })

  describe('with a reading of a source', () => {
    it('keeps a run that gave up as it was when the server has no way to start it again, and says so', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('DEAD')])
      vi.mocked(retryJob).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/jobs/42/retry'))
      const page = await mountPage()

      await press(page, 'Try this reading again', '.run-status')

      // Nothing about the run changed, so it is not read back or marked as one that can never be retried.
      expect(listGenerationRuns).toHaveBeenCalledTimes(1)
      expect(getJob).not.toHaveBeenCalled()
      expect(runStatus(page).get('[role="alert"]').text()).toContain('older than this page and does not have a way to start a run again yet')
      expect(page.text()).not.toContain('No static resource')
      expect(hasButton(page, 'Try this reading again', '.run-status')).toBe(true)
    })

    it('says Brownie could not be reached when starting a run again gets no answer, and keeps the offer', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('DEAD')])
      vi.mocked(retryJob).mockRejectedValue(networkFailure())
      const page = await mountPage()

      await press(page, 'Try this reading again', '.run-status')

      expect(runStatus(page).text()).toContain('Brownie could not be reached.')
      expect(page.text()).not.toContain('Could not start this reading again. Try again.')
      expect(hasButton(page, 'Try this reading again', '.run-status')).toBe(true)
    })

    it("offers Check again, not a reading still under way, when a finished run's result cannot be read", async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(startExtraction).mockResolvedValue(accepted())
      vi.mocked(getJob).mockResolvedValue(jobResponse('SUCCEEDED'))
      vi.mocked(getExtractionResult).mockRejectedValue(RENDERER_BUSY)
      const page = await mountPage()

      await askToFill(page)

      expect(norm(runStatus(page).text())).toContain(
        'This reading has finished, but its result could not be loaded. Too many documents are being prepared right now.',
      )
      expect(runStatus(page).text()).not.toContain('Reading notes.txt')
      expect(hasButton(page, 'Stop reading')).toBe(false)
      // A second start would be a second paid run; the finished one is still there to pick up.
      await askToFill(page)
      expect(chatLog(page).text()).toContain(
        'I lost track of the last reading. Press "Check again" above so I can see where it is before starting another.',
      )
      expect(startExtraction).toHaveBeenCalledTimes(1)
      expect((await axe(page.element)).violations).toEqual([])

      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
      await press(page, 'Check again', '.run-status')

      expect(hasButton(page, 'Show what I found', '.run-status')).toBe(true)
      expect(hasButton(page, 'Check again')).toBe(false)
    })

    it('offers Check again, not an empty list to answer, when the questions of a waiting run cannot be read', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(startExtraction).mockResolvedValue(accepted())
      vi.mocked(getJob).mockResolvedValue(jobResponse('WAITING_FOR_INPUT'))
      vi.mocked(getGenerationQuestions).mockRejectedValue(networkFailure())
      const page = await mountPage()

      await askToFill(page)

      expect(runStatus(page).text()).toContain('This reading is waiting for your answers, but its questions could not be loaded.')
      expect(runStatus(page).text()).not.toContain('A few things need your answer')
      expect(hasButton(page, 'Stop reading')).toBe(false)
      expect(hasButton(page, 'Check again', '.run-status')).toBe(true)
    })

    it('does the same when the questions of a waiting run cannot be read as the page opens', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
      vi.mocked(getGenerationQuestions).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/documents/1/generations/42/questions'))
      const page = await mountPage()

      expect(runStatus(page).text()).toContain('This reading is waiting for your answers, but its questions could not be loaded.')
      expect(runStatus(page).text()).toContain('older than this page')
      expect(hasButton(page, 'Stop reading')).toBe(false)
      expect(hasButton(page, 'Check again', '.run-status')).toBe(true)
    })

    it('says so when Check again itself cannot reach the server, and keeps the offer', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
      vi.mocked(getGenerationQuestions).mockRejectedValue(networkFailure())
      const page = await mountPage()

      vi.mocked(listGenerationRuns).mockRejectedValue(networkFailure())
      await press(page, 'Check again', '.run-status')

      expect(runStatus(page).text()).not.toContain('its questions could not be loaded')
      expect(runStatus(page).text()).toContain('Brownie could not be reached.')
      expect(hasButton(page, 'Check again', '.run-status')).toBe(true)
    })

    it.each([
      [401, refusal(401, 'UNAUTHORIZED', 'Full authentication is required.'), 'Your session has ended.'],
      [403, refusal(403, 'FORBIDDEN', 'Access denied.'), 'Brownie no longer lets this account see this reading, so this page stopped checking on it.'],
      [404, refusal(404, 'NOT_FOUND', 'No job 42 in this workspace.'), 'This reading is no longer available, for example because its document was moved to the trash.'],
    ])('stops checking on a run and says why when the server answers %i', async (_status, error, expected) => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(startExtraction).mockResolvedValue(accepted())
      vi.mocked(getJob).mockRejectedValue(error)
      const page = await mountPage()

      vi.useFakeTimers()
      await askToFill(page)
      await vi.advanceTimersByTimeAsync(30_000)

      expect(getJob).toHaveBeenCalledTimes(1)
      expect(runStatus(page).get('[role="alert"]').text()).toContain(expected)
      expect(page.text()).not.toContain('Lost contact')
      expect(hasButton(page, 'Stop reading')).toBe(false)
    })

    /**
     * A 404 with no explanation of Brownie's own came from something in front of it, a proxy during a deploy, say.
     * It says nothing about the run, which may well still be going: stopping here would offer a second paid start.
     */
    it('keeps checking on a run through a 404 that Brownie did not send', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(startExtraction).mockResolvedValue(accepted())
      vi.mocked(getJob).mockRejectedValue(nothingOnRecord())
      const page = await mountPage()

      vi.useFakeTimers()
      await askToFill(page)
      await vi.advanceTimersByTimeAsync(30_000)

      expect(vi.mocked(getJob).mock.calls.length).toBeGreaterThan(1)
      expect(page.text()).not.toContain('This reading is no longer available')
      expect(runStatus(page).get('.field-error').text()).toBe('Lost contact with the server; still checking.')
      expect(liveRegion(page).text()).toBe('Lost contact with the server; still checking.')
      // Still following the run, so it can still be stopped, and nothing offers to start another one.
      expect(hasButton(page, 'Stop reading', '.run-status')).toBe(true)
      expect(hasButton(page, 'Try this reading again')).toBe(false)
    })

    it('does not offer to start a gone run again because the run before it gave up', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('DEAD')])
      vi.mocked(startExtraction).mockResolvedValue(accepted(43))
      vi.mocked(getJob).mockRejectedValue(refusal(404, 'NOT_FOUND', 'No job 43 in this workspace.'))
      const page = await mountPage()

      await askToFill(page)

      expect(startExtraction).toHaveBeenCalledTimes(1)
      expect(runStatus(page).text()).toContain('This reading is no longer available')
      expect(hasButton(page, 'Try this reading again')).toBe(false)
    })

    it('keeps checking through a lost connection, and says Brownie asked it to slow down on a 429', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(startExtraction).mockResolvedValue(accepted())
      vi.mocked(getJob).mockRejectedValueOnce(networkFailure()).mockRejectedValueOnce(RATE_LIMITED).mockReturnValue(new Promise(() => {}))
      const page = await mountPage()

      vi.useFakeTimers()
      await askToFill(page)
      expect(runStatus(page).get('.field-error').text()).toBe('Lost contact with the server; still checking.')

      await vi.advanceTimersByTimeAsync(3_100)
      expect(getJob).toHaveBeenCalledTimes(2)
      expect(runStatus(page).get('.field-error').text()).toBe('Brownie asked this page to check less often; still checking.')
      // Said through the page's one live region, not by the conversation.
      expect(liveRegion(page).text()).toBe('Brownie asked this page to check less often; still checking.')
      expect(page.text()).not.toContain('Lost contact')
    })

    it('says a server without the route, rather than its raw answer, when a reading cannot start', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(startExtraction).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/documents/1/generations'))
      const page = await mountPage()

      await askToFill(page)

      expect(runStatus(page).get('[role="alert"]').text()).toContain('older than this page and does not have reading sources yet')
      expect(page.text()).not.toContain('No static resource')
    })

    it("passes on the server's explanation when saving an answer or carrying on is refused for load", async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
      vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
      vi.mocked(answerQuestion).mockRejectedValueOnce(RENDERER_BUSY)
      const page = await mountPage()

      await press(page, 'Weekly Robotics Club Sync', '.run-status')
      expect(runStatus(page).text()).toContain('Too many documents are being prepared right now.')
      expect(page.text()).not.toContain('Could not save that answer. Try again.')

      vi.mocked(answerQuestion).mockResolvedValue({ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Weekly Robotics Club Sync' })
      vi.mocked(resumeGeneration).mockRejectedValue(RATE_LIMITED)
      await press(page, 'Weekly Robotics Club Sync', '.run-status')

      expect(resumeGeneration).toHaveBeenCalledTimes(1)
      expect(runStatus(page).text()).toContain('Wait 12 seconds and try again.')
      expect(page.text()).not.toContain('Could not carry on reading. Try again.')
    })

    // Once every question is answered, the answers are no longer offered, so carrying on has a button of its own.
    it('offers to carry on again when carrying on with the answers failed, and when a page opens on answered questions', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
      vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
      vi.mocked(answerQuestion).mockResolvedValue({ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Weekly Robotics Club Sync' })
      vi.mocked(resumeGeneration).mockRejectedValueOnce(RATE_LIMITED).mockResolvedValue(accepted())
      vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
      const page = await mountPage()
      expect(hasButton(page, 'Carry on reading')).toBe(false)
      await press(page, 'Weekly Robotics Club Sync', '.run-status')
      expect(runStatus(page).text()).toContain('Wait 12 seconds and try again.')

      await press(page, 'Carry on reading', '.run-status')
      expect(resumeGeneration).toHaveBeenCalledTimes(2)
      expect(runStatus(page).text()).toContain('Reading notes.txt…')

      vi.mocked(getGenerationQuestions).mockResolvedValue([{ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Old Title' }])
      page.unmount()
      const reopened = await mountPage()
      expect(runStatus(reopened).text()).toContain('You answered: Old Title')
      expect(hasButton(reopened, 'Carry on reading', '.run-status')).toBe(true)
    })

    it('shows what the run became when carrying on finds it no longer waiting', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns)
        .mockResolvedValueOnce([generationRun('WAITING_FOR_INPUT')])
        .mockResolvedValue([generationRun('CANCELLED')])
      vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
      vi.mocked(answerQuestion).mockResolvedValue({ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Old Title' })
      vi.mocked(resumeGeneration).mockRejectedValue(refusal(409, 'CONFLICT', 'The run is not waiting for input.'))
      const page = await mountPage()

      await press(page, 'Old Title', '.run-status')

      expect(listGenerationRuns).toHaveBeenCalledTimes(2)
      expect(runStatus(page).text()).toContain('I stopped reading. Nothing on the document changed.')
      expect(page.text()).not.toContain('The run is not waiting for input.')
    })

    it('says a server without the route when stopping a reading cannot be asked for', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('QUEUED')])
      vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
      vi.mocked(cancelJob).mockRejectedValue(noSuchRoute('api/v1/workspaces/7/jobs/42/cancel'))
      const page = await mountPage()

      await press(page, 'Stop reading', '.run-status')

      expect(runStatus(page).text()).toContain('does not have a way to cancel a run yet')
      expect(page.text()).not.toContain('Could not stop reading. Try again.')
      expect(hasButton(page, 'Stop reading', '.run-status')).toBe(true)
    })

    it("passes on the server's explanation when a finished run cannot be turned into a proposal", async () => {
      vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
      vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
      vi.mocked(applyGenerationResult).mockRejectedValue(RENDERER_BUSY)
      const page = await mountPage()

      await press(page, 'Show what I found', '.run-status')

      expect(runStatus(page).get('[role="alert"]').text()).toContain('Too many documents are being prepared right now.')
      expect(page.text()).not.toContain('Could not get the values ready. Try again.')
    })
  })

  describe('when a source is attached', () => {
    const panelAlert = (page: Page) => page.get('#add-source-panel [role="alert"]')

    it('names the upload limit when a file over it is refused, and does not say to try again', async () => {
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockRejectedValue(refusal(413, 'CONTENT_TOO_LARGE', 'Upload exceeds the maximum allowed size of 10485760 bytes.'))
      const page = await mountPage()

      await chooseSourceFile(page, 'notes.txt', 11 * 1024 * 1024)

      expect(getCapabilities).toHaveBeenCalledTimes(1)
      expect(panelAlert(page).text()).toBe('That file was not attached: it is larger than the 10 MB upload limit.')
      expect(page.get('#add-source-panel').text()).not.toContain('Try again')
    })

    it("passes on the server's explanation of a size refusal when the limit is not known here", async () => {
      vi.mocked(getCapabilities).mockRejectedValue(networkFailure())
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockRejectedValue(refusal(413, 'CONTENT_TOO_LARGE', 'Upload exceeds the maximum allowed size of 10485760 bytes.'))
      const page = await mountPage()

      await chooseSourceFile(page, 'notes.txt', 11 * 1024 * 1024)

      expect(panelAlert(page).text()).toBe('That file was not attached. Upload exceeds the maximum allowed size of 10485760 bytes.')
    })

    it("passes on the server's explanation of a size refusal for a file under the limit, which is about what it unpacks to", async () => {
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockRejectedValue(refusal(413, 'CONTENT_TOO_LARGE', 'Package expands beyond 52428800 uncompressed bytes.'))
      const page = await mountPage()

      await chooseSourceFile(page, 'notes.txt', 40 * 1024)

      expect(panelAlert(page).text()).toBe('That file was not attached. Package expands beyond 52428800 uncompressed bytes.')
    })

    it('says a file of a kind Brownie cannot use was not attached, and what to attach instead', async () => {
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockRejectedValue(refusal(415, 'UNSUPPORTED_MEDIA_TYPE', 'Package is not a valid ZIP archive.'))
      const page = await mountPage()

      await chooseSourceFile(page)

      expect(panelAlert(page).text()).toBe(
        'That file was not attached: Brownie cannot use that kind of file as a source. Attach a plain-text (.txt) file.',
      )
    })

    it('says Brownie could not be reached when an upload gets no answer', async () => {
      vi.mocked(allocateUpload).mockRejectedValue(networkFailure())
      const page = await mountPage()

      await chooseSourceFile(page)

      expect(panelAlert(page).text()).toBe('That file was not attached. Brownie could not be reached. Check your connection, then try again.')
    })

    it.each([
      [{ id: 5, status: 'QUARANTINED' as const, rejectionReason: null }, 'That file was not attached: Brownie could not finish checking it.', 'QUARANTINED'],
      [{ id: 5, status: 'REJECTED' as const, rejectionReason: 'MALWARE_DETECTED' }, 'That file was not attached: the malware scan flagged it.', 'MALWARE_DETECTED'],
    ])('says why a finished upload was not accepted without its internal state (%o)', async (artifact, expected, internal) => {
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'notes.txt' })
      vi.mocked(completeUpload).mockResolvedValue(artifact)
      const page = await mountPage()

      await chooseSourceFile(page)

      expect(panelAlert(page).text()).toBe(expected)
      expect(page.text()).not.toContain(internal)
      expect(attachDocumentSource).not.toHaveBeenCalled()
      expect(sourceCards(page)).toHaveLength(0)
    })

    it("falls back to the server's explanation of a size refusal when an older server does not send its limit", async () => {
      vi.mocked(getCapabilities).mockResolvedValue({
        uploadMediaTypes: [{ mediaType: 'text/plain', extension: 'txt' }],
        assistSourceMediaTypes: ['text/plain'],
        templateMediaTypes: [],
      } as never)
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockRejectedValue(refusal(413, 'CONTENT_TOO_LARGE', 'Upload exceeds the maximum allowed size of 10485760 bytes.'))
      const page = await mountPage()
      await openAddSource(page)
      expect(norm(page.get('#add-source-panel .field-hint').text())).toBe('Plain text (.txt). Brownie reads plain-text sources.')

      await chooseSourceFile(page, 'notes.txt', 11 * 1024 * 1024)

      expect(panelAlert(page).text()).toBe('That file was not attached. Upload exceeds the maximum allowed size of 10485760 bytes.')
      expect(page.get('#add-source-panel').text()).not.toContain('undefined')
      expect(page.get('#add-source-panel').text()).not.toContain('null')
    })

    it('shows the upload limit before a file is chosen', async () => {
      const page = await mountPage()
      await openAddSource(page)

      expect(getCapabilities).toHaveBeenCalledTimes(1)
      expect(norm(page.get('#add-source-panel .field-hint').text())).toBe('Plain text (.txt), up to 10 MB. Brownie reads plain-text sources.')
    })
  })

  describe('on the other steps', () => {
    it("passes on the server's explanation when a review decision is refused for load", async () => {
      vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
      vi.mocked(recordReviewDecision).mockRejectedValue(RATE_LIMITED)
      const page = await mountPage()
      await selectSpot(page, 'edit-meeting.title')

      await press(page, 'Accept Meeting title', 'section.selection-bar')

      expect(norm(notices(page).get('[role="alert"]').text())).toBe('Too many requests in a short time. Wait 12 seconds and try again.')
      expect(page.text()).not.toContain('Could not record a review decision')
    })

    it('says the document went to the trash, and links there, when a lock finds it gone', async () => {
      vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
      vi.mocked(setFieldLock).mockRejectedValue(DOCUMENT_GONE)
      const page = await mountPage()
      await selectSpot(page, 'edit-meeting.title')

      await press(page, 'Lock Meeting title', 'section.selection-bar')

      const said = notices(page).get('[role="alert"]')
      expect(norm(said.text())).toContain('That was not done: this document is no longer available, for example because it was moved to the trash.')
      expect(said.find('a[href="/trash"]').exists()).toBe(true)
    })
  })
})

// =================================================================================================

describe('WorkspaceView: a document handed over by the screen that created it', () => {
  const HANDED: DocumentSourceResponse = {
    id: 3,
    artifactId: 5,
    displayFilename: 'minutes.txt',
    kind: 'ARTIFACT',
    fetchedAt: CREATED_AT,
    attachedAt: CREATED_AT,
  }

  /**
   * A real browser finding: a source attached during document creation was invisible here, so the
   * page told the person to attach a source they had attached seconds earlier.
   */
  it('shows a source attached during creation at once, and reads it when asked', async () => {
    vi.mocked(listDocumentSources).mockReturnValue(new Promise(() => {}))
    vi.mocked(startExtraction).mockResolvedValue(accepted())
    vi.mocked(getJob).mockReturnValue(new Promise(() => {}))
    const page = await mountPage('/documents/1', { attachedSources: [HANDED], sourceWarning: null })

    expect(sourceCards(page).map((card) => card.get('.source-card__name').text())).toEqual(['minutes.txt'])
    expect(page.findAll('[role="alert"]')).toHaveLength(0)

    await askToFill(page)
    expect(chatLog(page).text()).not.toContain('Add your notes or a transcript with Add a source (+) first')
    expect(startExtraction).toHaveBeenCalledWith(7, 1, 5, expect.any(String))
  })

  it("says the creation screen's failed attachment here, where the person lands, before anything else", async () => {
    const warning = 'Your source file was not attached: the malware scan flagged it. The document was still created.'
    const page = await mountPage('/documents/1', { attachedSources: [], sourceWarning: warning })

    const alerts = page.findAll('[role="alert"]')
    expect(alerts.length).toBeGreaterThan(0)
    expect(alerts[0]!.text()).toBe(warning)
    expect(sourceCards(page)).toHaveLength(0)
  })

  it('behaves exactly as before when nothing was handed over', async () => {
    const page = await mountPage()

    expect(page.findAll('[role="alert"]')).toHaveLength(0)
    expect(sourceCards(page)).toHaveLength(0)
    expect(page.find('ul[aria-label="Sources"]').exists()).toBe(false)
  })
})

// =================================================================================================

describe('WorkspaceView: sources copied from Google', () => {
  const CALENDAR_CAPABILITIES = { ...TEN_MEGABYTE_LIMIT, googleConnectorAccess: ['CALENDAR_EVENTS' as const] }

  const COPIED: DocumentSourceResponse = {
    id: 12,
    artifactId: 13,
    kind: 'GOOGLE_CALENDAR',
    displayFilename: 'Budget review.txt',
    fetchedAt: '2026-09-23T08:00:00Z',
    attachedAt: '2026-09-23T08:00:00Z',
    origin: {
      provider: 'GOOGLE',
      title: 'Budget review',
      link: 'https://www.google.com/calendar/event?eid=abc',
      modifiedAt: '2026-09-21T08:00:00Z',
      conversion: 'CALENDAR_EVENT_AS_TEXT',
    },
  }

  const UPLOADED: DocumentSourceResponse = {
    id: 3,
    artifactId: 5,
    displayFilename: 'minutes.txt',
    kind: 'ARTIFACT',
    fetchedAt: CREATED_AT,
    attachedAt: CREATED_AT,
    origin: null,
  }

  const CALENDAR_CONNECTION: ConnectionResponse = {
    id: 1,
    provider: 'GOOGLE',
    access: 'CALENDAR_EVENTS',
    state: 'ACTIVE',
    accountEmail: 'me@example.org',
    grantedScopes: [],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [],
  }

  const ONE_EVENT: CalendarEventsResponse = {
    timeZone: null,
    truncated: false,
    events: [
      {
        id: 'evt1',
        title: 'Budget review',
        status: 'CONFIRMED',
        allDay: false,
        startDate: null,
        endDate: null,
        startsAt: '2026-09-22T09:00:00-07:00',
        endsAt: '2026-09-22T10:00:00-07:00',
        timeZone: null,
        recurring: false,
      },
    ],
  }

  beforeEach(() => {
    vi.mocked(getCapabilities).mockResolvedValue(CALENDAR_CAPABILITIES)
  })

  /** Opens the calendar control, lists the default days and presses Copy on the one event. */
  async function copyTheEvent(page: Page): Promise<void> {
    await openAddSource(page)
    await press(page, 'Copy an event from Google Calendar')
    page.get('form.calendar-picker__window').element.dispatchEvent(new Event('submit', { cancelable: true }))
    await flushPromises()
    await page.get('#calendar-copy-evt1').trigger('click')
    await flushPromises()
  }

  it('labels a copied source with where it came from and links to it at Google, and an upload with neither', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([COPIED, UPLOADED])
    const page = await mountPage()

    const [copied, uploaded] = sourceCards(page)
    expect(norm(copied!.get('.source-card__meta').text())).toBe('Calendar event · Brownie reads this one')
    expect(norm(copied!.text())).toContain('Copied from Google Calendar as text on')
    expect(norm(copied!.text())).toContain(', when it had last been changed there on')
    const link = copied!.get('a')
    expect(link.attributes('href')).toBe('https://www.google.com/calendar/event?eid=abc')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(link.text()).toBe('Open in Google Calendar (opens in a new tab)')
    expect(norm(uploaded!.get('.source-card__meta').text())).toBe('Text file')
    expect(uploaded!.text()).not.toContain('Copied from')
    expect(uploaded!.find('a').exists()).toBe(false)
  })

  it('never makes a link of an origin address that is not https, whatever the server sent', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([{ ...COPIED, origin: { ...COPIED.origin!, link: 'javascript:alert(1)' } }])
    const page = await mountPage()

    expect(sourceCards(page)[0]!.text()).toContain('Copied from Google Calendar')
    expect(sourceCards(page)[0]!.find('a').exists()).toBe(false)
  })

  it('does not offer Google Calendar when this Brownie has no Google set up', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({ ...CALENDAR_CAPABILITIES, googleConnectorAccess: [] })
    const page = await mountPage()
    await openAddSource(page)

    expect(page.get('#add-source-panel').text()).not.toContain('Copy an event from Google Calendar')
    expect(page.find('#attach-source').exists()).toBe(true)
  })

  it('adds a copied event to the sources, first, and has Brownie read it', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([UPLOADED])
    vi.mocked(listConnections).mockResolvedValue([CALENDAR_CONNECTION])
    vi.mocked(listCalendarEvents).mockResolvedValue(ONE_EVENT)
    vi.mocked(importCalendarEvent).mockResolvedValue({ source: COPIED, newCopy: true })
    const page = await mountPage()

    await copyTheEvent(page)

    expect(importCalendarEvent).toHaveBeenCalledWith(7, 1, 'evt1')
    const cards = sourceCards(page)
    expect(cards).toHaveLength(2)
    expect(cards[0]!.text()).toContain('Budget review.txt')
    expect(cards[0]!.text()).toContain('Copied from Google Calendar')
    expect(norm(cards[0]!.text())).toContain('Brownie reads this one')
    expect((page.get('#extract-source').element as HTMLSelectElement).value).toBe('12')
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('comes back from connecting with what Google said, takes it out of the address, and opens the Calendar control', async () => {
    const page = await mountPage('/documents/1?google=failed&access=calendar_events&reason=access_denied')

    expect(router.currentRoute.value.query).toEqual({})
    expect(router.currentRoute.value.path).toBe('/documents/1')
    // Said at the top of the page, which every screen width shows, and focused so it is heard.
    const said = notices(page).get('[role="alert"]')
    expect(said.text()).toBe("You chose not to allow access on Google's page, so nothing was connected.")
    expect(document.activeElement).toBe(said.element)
    expect(pressedOf(page, 'Brownie')).toBe('true')
    expect(page.find('#add-source-panel').exists()).toBe(true)
    expect(page.get('.calendar-picker > button').attributes('aria-expanded')).toBe('true')
    expect(page.get('.calendar-picker').text()).toContain('Connect Google Calendar')
  })

  // The control Google's answer was for opens by itself on arriving; once the person has closed the panel
  // it is in, it is theirs to open.
  it('leaves the Calendar control closed when the person closes the panel and opens it again', async () => {
    const page = await mountPage('/documents/1?google=failed&access=calendar_events&reason=access_denied')
    expect(page.get('.calendar-picker > button').attributes('aria-expanded')).toBe('true')

    await press(page, 'Close adding a source')
    await press(page, 'Add a source')

    expect(page.get('.calendar-picker > button').attributes('aria-expanded')).toBe('false')
  })

  it('says Google Calendar is connected and where to copy an event from', async () => {
    const page = await mountPage('/documents/1?google=connected&access=calendar_events')

    expect(norm(notices(page).get('[role="status"]').text())).toBe(
      'Google Calendar is connected. Brownie lists the days you ask for and copies only the event you choose. ' +
        'Choose an event to copy into this document in Brownie\'s panel.',
    )
    expect(page.get('.calendar-picker > button').attributes('aria-expanded')).toBe('true')
    expect(exportDialog(page).find('h2').exists()).toBe(false)
  })

  it('leaves the source the person chose for Brownie alone when a copy finishes afterwards', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([UPLOADED, { ...UPLOADED, id: 4, artifactId: 6, displayFilename: 'agenda.txt' }])
    vi.mocked(listConnections).mockResolvedValue([CALENDAR_CONNECTION])
    vi.mocked(listCalendarEvents).mockResolvedValue(ONE_EVENT)
    const copy = deferred<{ source: DocumentSourceResponse; newCopy: boolean }>()
    vi.mocked(importCalendarEvent).mockReturnValue(copy.promise)
    const page = await mountPage()
    await copyTheEvent(page)

    await page.get('select#extract-source').setValue('4')
    copy.resolve({ source: COPIED, newCopy: true })
    await flushPromises()

    expect((page.get('select#extract-source').element as HTMLSelectElement).value).toBe('4')
    expect(sourceCards(page)).toHaveLength(3)
    expect(norm(sourceCards(page).find((card) => card.text().includes('agenda.txt'))!.text())).toContain('Brownie reads this one')
  })

  it('takes down what Google said once the person uses the Calendar control', async () => {
    const page = await mountPage('/documents/1?google=failed&access=calendar_events&reason=access_denied')
    expect(notices(page).find('[role="alert"]').exists()).toBe(true)

    await press(page, 'Copy an event from Google Calendar')

    expect(notices(page).find('[role="alert"]').exists()).toBe(false)
  })

  it('keeps a copy that finishes after the person closed the panel', async () => {
    vi.mocked(listConnections).mockResolvedValue([CALENDAR_CONNECTION])
    vi.mocked(listCalendarEvents).mockResolvedValue(ONE_EVENT)
    const copy = deferred<{ source: DocumentSourceResponse; newCopy: boolean }>()
    vi.mocked(importCalendarEvent).mockReturnValue(copy.promise)
    const page = await mountPage()
    await copyTheEvent(page)

    await press(page, 'Close adding a source')
    copy.resolve({ source: COPIED, newCopy: true })
    await flushPromises()

    expect(page.find('#add-source-panel').exists()).toBe(false)
    expect(sourceCards(page)[0]!.text()).toContain('Budget review.txt')
  })

  it('leaves the address alone when it is not a return from Google', async () => {
    const page = await mountPage('/documents/1?google=maybe')

    expect(router.currentRoute.value.query).toEqual({ google: 'maybe' })
    expect(notices(page).findAll('[role="alert"], [role="status"]')).toHaveLength(0)
    expect(page.find('#add-source-panel').exists()).toBe(false)
  })

  describe('with Google Drive offered', () => {
    const DRIVE_CAPABILITIES = { ...TEN_MEGABYTE_LIMIT, googleConnectorAccess: ['CALENDAR_EVENTS' as const, 'DRIVE_FILES' as const] }
    const DRIVE_CONNECTION: ConnectionResponse = {
      ...CALENDAR_CONNECTION,
      id: 5,
      access: 'DRIVE_FILES',
      grants: [{ id: 11, type: 'DRIVE_FILE', displayName: 'Minutes', grantedAt: '2026-09-22T10:00:00Z' }],
    }
    const DRIVE_COPIED: DocumentSourceResponse = {
      id: 22,
      artifactId: 23,
      kind: 'GOOGLE_DRIVE',
      displayFilename: 'Minutes.txt',
      fetchedAt: '2026-09-23T08:00:00Z',
      attachedAt: '2026-09-23T08:00:00Z',
      origin: {
        provider: 'GOOGLE',
        title: 'Minutes',
        link: 'https://docs.google.com/document/d/abc/edit',
        modifiedAt: '2026-09-21T08:00:00Z',
        conversion: 'GOOGLE_DOC_AS_TEXT',
      },
    }

    beforeEach(() => {
      vi.mocked(getCapabilities).mockResolvedValue(DRIVE_CAPABILITIES)
      vi.mocked(listConnections).mockResolvedValue([DRIVE_CONNECTION])
    })

    it('comes back from choosing files with what was added, opens the Drive control, and takes every count out of the address', async () => {
      const page = await mountPage('/documents/1?google=picked&access=drive_files&added=1&unsupported=0&unavailable=0&unchecked=0&over_limit=0')

      expect(router.currentRoute.value.query).toEqual({})
      const said = notices(page).get('[role="status"]')
      expect(norm(said.text())).toBe(
        "1 file you chose is now on your list of files Brownie may read. Brownie reads a file's content only when you copy it into a " +
          'document. Choose a file to copy into this document in Brownie\'s panel.',
      )
      expect(document.activeElement).toBe(said.element)
      expect(page.get('.drive-picker > button').attributes('aria-expanded')).toBe('true')
      expect(page.get('.calendar-picker > button').attributes('aria-expanded'), 'the calendar control stays closed').toBe('false')
    })

    it('does not point to copying a file when a pick added nothing', async () => {
      const page = await mountPage('/documents/1?google=picked&access=drive_files&added=0')

      expect(norm(notices(page).get('[role="status"]').text())).toBe(
        "Google Drive is connected. Nothing was chosen in Google's file picker, so no file was added.",
      )
    })

    it('adds a copied Drive file to the sources, first, and labels where it came from', async () => {
      vi.mocked(listDocumentSources).mockResolvedValue([UPLOADED])
      vi.mocked(importDriveFile).mockResolvedValue({ source: DRIVE_COPIED, newCopy: true })
      const page = await mountPage()
      await openAddSource(page)

      await press(page, 'Copy a file from Google Drive')
      await page.get('#drive-copy-11').trigger('click')
      await flushPromises()

      expect(importDriveFile).toHaveBeenCalledWith(7, 1, 11)
      const cards = sourceCards(page)
      expect(cards).toHaveLength(2)
      expect(cards[0]!.text()).toContain('Minutes.txt')
      expect(norm(cards[0]!.get('.source-card__meta').text())).toBe('Google Doc · Brownie reads this one')
      expect(cards[0]!.text()).toContain('Copied from Google Drive as text')
      expect(cards[0]!.get('a').text()).toBe('Open in Google Drive (opens in a new tab)')
      expect(await axe(page.element)).toHaveNoViolations()
    })
  })

  describe('with saving and adding events offered from Export', () => {
    beforeEach(() => {
      vi.mocked(getCapabilities).mockResolvedValue({
        ...CALENDAR_CAPABILITIES,
        googleConnectorAccess: ['CALENDAR_EVENTS', 'DRIVE_SAVING', 'CALENDAR_EVENT_CREATION'],
        googleActions: ['DRIVE_SAVE_FILE', 'DRIVE_SAVE_AS_GOOGLE_DOC', 'CALENDAR_CREATE_EVENT'],
      })
      vi.mocked(getLatestValidation).mockResolvedValue(manifest())
    })

    it('comes back from connecting for saving, says to save from Export, and opens Export once the document is here', async () => {
      const page = await mountPage('/documents/1?google=connected&access=drive_saving')

      expect(router.currentRoute.value.query).toEqual({})
      expect(norm(notices(page).get('[role="status"]').text())).toBe(
        'Google Drive for saving is connected. Brownie saves a file, or adds text to a Google Doc it saved, only once you approve it. ' +
          'Save the exported document from Export.',
      )
      expect(page.find('#add-source-panel').exists()).toBe(false)
      expect(exportDialog(page).attributes('open')).toBeDefined()
      expect(exportDialog(page).get('h2').text()).toBe('Export')
      expect(getLatestValidation).toHaveBeenCalledWith(7, 1, 1)
      expect(validateDocument).not.toHaveBeenCalled()
    })

    it('comes back from connecting for adding events, says to add it from Export, and opens Export', async () => {
      const page = await mountPage('/documents/1?google=connected&access=calendar_event_creation')

      expect(norm(notices(page).get('[role="status"]').text())).toBe(
        'Google Calendar for adding events is connected. Brownie adds only an event you approve, once you approve it. ' +
          'Add the event from Export.',
      )
      expect(page.find('#add-source-panel').exists()).toBe(false)
      expect(exportDialog(page).get('h2').text()).toBe('Export')
    })

    it('does not open Export by itself when connecting for saving did not work', async () => {
      const page = await mountPage('/documents/1?google=failed&access=drive_saving&reason=access_denied')

      expect(notices(page).get('[role="alert"]').text()).toBe("You chose not to allow access on Google's page, so nothing was connected.")
      expect(exportDialog(page).find('h2').exists()).toBe(false)
      expect(page.find('#add-source-panel').exists()).toBe(false)
    })
  })
})

// =================================================================================================

describe('WorkspaceView accessibility', () => {
  it('has no detectable violations for a loaded document with a reviewed field', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const page = await mountPage()

    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with a question open', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue(ONE_SOURCE)
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION, MISSING_DATE_QUESTION])
    const page = await mountPage()

    expect(runStatus(page).text()).toContain('Old Title')
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with a proposal open', async () => {
    vi.mocked(interpretAssist).mockResolvedValue(CHANGE_TITLE)
    vi.mocked(executeAssist).mockResolvedValue(TITLE_CHANGED)
    const page = await mountPage()
    await send(page, 'change meeting title to Spring Planning')

    expect(proposal(page).exists()).toBe(true)
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with the bar about a fill spot open and its evidence shown', async () => {
    vi.mocked(getDocument).mockResolvedValue(
      documentAt(revision({ fields: { 'meeting.title': { ...scalarField('Weekly Sync', IMPORTED_STATE), evidenceSourceSpanIds: [12] } } })),
    )
    vi.mocked(getEvidenceExcerpt).mockResolvedValue({
      spanId: 12,
      sourceSnapshotId: 3,
      sourceArtifactId: 5,
      displayFilename: 'minutes.txt',
      locatorType: 'PLAIN_TEXT',
      excerptText: 'The meeting title is "Weekly Sync".',
    })
    const page = await mountPage()
    await selectSpot(page, 'edit-meeting.title')
    await press(page, 'Where it came from (1)', 'section.selection-bar')

    expect(page.find('#evidence-meeting\\.title').exists()).toBe(true)
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with rows showing and a row selected', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const page = await mountPage()
    await press(page, 'Add row')
    await selectSpot(page, 'edit-action.item.task-0')

    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with the add-a-source panel open and Google offered', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({ ...TEN_MEGABYTE_LIMIT, googleConnectorAccess: ['CALENDAR_EVENTS', 'DRIVE_FILES'] })
    const page = await mountPage()
    await openAddSource(page)

    expect(page.find('.calendar-picker').exists()).toBe(true)
    expect(page.find('.drive-picker').exists()).toBe(true)
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with Export open and its findings listed', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(validateDocument).mockResolvedValue(
      manifest({
        hasUnresolvedBlocking: true,
        findings: [
          { code: 'REQUIRED_FIELD_MISSING', severity: 'BLOCKING', fieldId: 'meeting.date', message: 'Meeting date is required.' },
          { code: 'WEAK_EVIDENCE', severity: 'WARNING', fieldId: 'meeting.title', message: 'Low confidence value.' },
        ],
      }),
    )
    const page = await mountPage()
    await press(page, 'Export', 'header.workspace-bar')
    await flushPromises()

    expect(exportDialog(page).text()).toContain('Low confidence value.')
    expect(await axe(page.element)).toHaveNoViolations()
  })

  it('has no violations with Version history open', async () => {
    const R1 = revision({ id: 1, revisionNumber: 1 })
    const R2 = revision({ id: 2, revisionNumber: 2, contentHash: HASH_B, editReason: 'Autosaved.' })
    vi.mocked(getDocument).mockResolvedValue(documentAt(R2))
    vi.mocked(listDocumentRevisions).mockResolvedValue([R1, R2])
    const page = await mountPage()
    await press(page, 'Version history', 'header.workspace-bar')

    expect(historyDialog(page).findAll('.revision-row')).toHaveLength(2)
    expect(await axe(page.element)).toHaveNoViolations()
  })
})
