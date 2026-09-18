import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { useSessionStore } from '@/stores/session'
import type { DocumentResponse, DocumentRevisionResponse, JobResponse, QuestionResponse } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getDocument: vi.fn(),
    getTemplateVersion: vi.fn(),
    patchDocumentContent: vi.fn(),
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    extractArtifact: vi.fn(),
    attachDocumentSource: vi.fn(),
    listDocumentSources: vi.fn(),
    listGenerationRuns: vi.fn(),
    cancelJob: vi.fn(),
    compileRevision: vi.fn(),
    getLatestCompilation: vi.fn(),
    getEvidenceExcerpt: vi.fn(),
    getCapabilities: vi.fn(),
    listTemplateVersionRules: vi.fn(),
    interpretAssist: vi.fn(),
    executeAssist: vi.fn(),
    startExtraction: vi.fn(),
    getJob: vi.fn(),
    getGenerationQuestions: vi.fn(),
    answerQuestion: vi.fn(),
    resumeGeneration: vi.fn(),
    getExtractionResult: vi.fn(),
    applyGenerationResult: vi.fn(),
    acceptPatchProposal: vi.fn(),
    recordReviewDecision: vi.fn(),
    setFieldLock: vi.fn(),
    validateDocument: vi.fn(),
    getLatestValidation: vi.fn(),
    approveExport: vi.fn(),
    getLatestExportApproval: vi.fn(),
    exportDocument: vi.fn(),
    getLatestExportReceipt: vi.fn(),
    listDocumentRevisions: vi.fn(),
    getDocumentRevision: vi.fn(),
  }
})

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
  artifactDownloadUrl,
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
  getCapabilities,
  listTemplateVersionRules,
  getTemplateVersion,
  interpretAssist,
  listDocumentRevisions,
  listDocumentSources,
  listGenerationRuns,
  patchDocumentContent,
  recordReviewDecision,
  resumeGeneration,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
  validateDocument,
} from '@/api/client'
import type {
  CompilationManifestResponse,
  ExportApprovalResponse,
  ExportReceiptResponse,
  TemplateVersionResponse,
  ValidationManifestResponse,
} from '@/api/client'
import { axe } from '@/test/axe'
import { documentHandoffState, type DocumentHandoff } from '@/router/handoff'
import { resetCapabilitiesCache } from '@/capabilities'

const DOCUMENT: DocumentResponse = {
  id: 1,
  title: 'Weekly Sync',
  templateId: 1,
  templateVersionId: 1,
  currentRevisionId: 1,
  createdAt: '2026-03-01T00:00:00Z',
  currentRevision: {
    id: 1,
    revisionNumber: 1,
    fields: {},
    contentHash: 'a'.repeat(64),
    editReason: 'created',
    createdAt: '2026-03-01T00:00:00Z',
  },
}

function jobResponse(state: string): JobResponse {
  return {
    id: 42,
    type: 'generation.extract-facts',
    resourceType: 'document',
    resourceId: 1,
    resourceVersion: 1,
    stage: 'extracting',
    state,
    attemptCount: 1,
    availableAt: '2026-03-01T00:00:00Z',
    deadlineAt: '2026-03-01T01:00:00Z',
    createdAt: '2026-03-01T00:00:00Z',
    updatedAt: '2026-03-01T00:00:00Z',
  }
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
  createdAt: '2026-03-01T00:00:00Z',
}

const DOCUMENT_WITH_A_SCALAR_FIELD: DocumentResponse = {
  ...DOCUMENT,
  currentRevision: {
    ...DOCUMENT.currentRevision,
    fields: {
      'meeting.title': {
        type: 'TEXT',
        cardinality: 'SCALAR',
        value: 'Weekly Sync',
        evidenceSourceSpanIds: [],
        fieldState: { authorship: 'IMPORTED', evidenceSupport: 'DIRECT', validation: 'NOT_RUN', review: 'UNREVIEWED', lock: 'EDITABLE' },
      },
    },
  },
}

async function mountWorkspaceView() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/documents/:id', component: WorkspaceView },
    ],
  })
  router.push('/documents/1')
  await router.isReady()
  const wrapper = mount(WorkspaceView, { props: { documentId: 1 }, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/**
 * Same as mountWorkspaceView, but attached to document.body -- jsdom only tracks
 * document.activeElement for elements connected to the live document, so the drawer/tablist focus
 * tests need this instead of the plain (detached) mount every other test in this file uses.
 * Callers must wrapper.unmount() when done, so the attached nodes don't leak into later tests.
 */
async function mountWorkspaceViewAttached() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/documents/:id', component: WorkspaceView },
    ],
  })
  router.push('/documents/1')
  await router.isReady()
  const wrapper = mount(WorkspaceView, { props: { documentId: 1 }, global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

async function attachAFakeSource(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>) {
  vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'minutes.txt' })
  vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'minutes.txt' })
  vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'minutes.txt' })
  vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
  vi.mocked(attachDocumentSource).mockResolvedValue({ id: 3, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' })

  const input = wrapper.find('#attach-source')
  const file = new File(['minutes'], 'minutes.txt', { type: 'text/plain' })
  Object.defineProperty(input.element, 'files', { value: [file] })
  await input.trigger('change')
  await flushPromises()
}

describe('WorkspaceView grounded extraction with questions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(startExtraction).mockReset()
    vi.mocked(getJob).mockReset()
    vi.mocked(getGenerationQuestions).mockReset()
    vi.mocked(answerQuestion).mockReset()
    vi.mocked(resumeGeneration).mockReset()
    vi.mocked(getExtractionResult).mockReset()
    vi.mocked(applyGenerationResult).mockReset()
    vi.mocked(acceptPatchProposal).mockReset()
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  it('waits for a person to answer a detected question, then resumes and finishes', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()
    await attachAFakeSource(wrapper)

    vi.mocked(startExtraction).mockResolvedValue({
      commandId: 'c1', jobId: 42, operation: 'generation.start-extraction', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z',
    })
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('WAITING_FOR_INPUT'))
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])

    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    await wrapper.findAll('button').find((b) => b.text() === 'Try grounded extraction')?.trigger('click')
    await flushPromises()

    expect(getGenerationQuestions).toHaveBeenCalledWith(7, 1, 42)
    expect(wrapper.text()).toContain('Meeting title')
    expect(wrapper.text()).toContain('Old Title')

    const continueButton = wrapper.findAll('button').find((b) => b.text() === 'Continue')
    expect(continueButton?.attributes('disabled')).toBeDefined()

    vi.mocked(answerQuestion).mockResolvedValue({ ...CONFLICT_QUESTION, status: 'ANSWERED', answerValue: 'Executive Committee Sync' })
    const answerInput = wrapper.find(`#answer-${CONFLICT_QUESTION.id}`)
    await answerInput.setValue('Executive Committee Sync')
    const saveButton = wrapper.findAll('button').find((b) => b.text() === 'Save answer')
    await saveButton?.trigger('click')
    await flushPromises()

    expect(answerQuestion).toHaveBeenCalledWith(7, 9, 'Executive Committee Sync')
    expect(wrapper.text()).toContain('Answered: Executive Committee Sync')

    vi.mocked(resumeGeneration).mockResolvedValue({
      commandId: 'c2', jobId: 42, operation: 'generation.resume', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z',
    })
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockResolvedValue({ artifactId: 77 })

    const resumeButton = wrapper.findAll('button').find((b) => b.text() === 'Continue')
    await resumeButton?.trigger('click')
    await flushPromises()

    expect(resumeGeneration).toHaveBeenCalledWith(7, 1, 42, expect.any(String))
    expect(wrapper.text()).toContain('download the raw result')

    vi.mocked(applyGenerationResult).mockResolvedValue({
      id: 501,
      documentId: 1,
      baseRevisionId: 1,
      proposedValues: {
        'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Executive Committee Sync', values: null, evidenceSpanIds: [] },
      },
      status: 'PROPOSED',
      createdAt: '2026-03-01T00:00:00Z',
      proposedRepeatedItemCount: 0,
      skippedRepeatedItems: [],
    })
    const applyButton = wrapper.findAll('button').find((b) => b.text() === 'Apply to document')
    await applyButton?.trigger('click')
    await flushPromises()

    expect(applyGenerationResult).toHaveBeenCalledWith(7, 1, 42)
    expect(wrapper.text()).toContain('Proposed changes')
    expect(wrapper.text()).toContain('Executive Committee Sync')

    vi.mocked(acceptPatchProposal).mockResolvedValue({
      applied: true,
      fieldStatuses: { 'meeting.title': 'CLEAN' },
      revision: {
        id: 2,
        revisionNumber: 2,
        fields: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Executive Committee Sync', evidenceSourceSpanIds: [] } },
        contentHash: 'b'.repeat(64),
        editReason: 'Accepted generated draft',
        createdAt: '2026-03-01T00:01:00Z',
      },
    })
    vi.mocked(getDocument).mockResolvedValue({
      ...DOCUMENT,
      currentRevisionId: 2,
      currentRevision: {
        id: 2,
        revisionNumber: 2,
        fields: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Executive Committee Sync', evidenceSourceSpanIds: [] } },
        contentHash: 'b'.repeat(64),
        editReason: 'Accepted generated draft',
        createdAt: '2026-03-01T00:01:00Z',
      },
    })

    const acceptButton = wrapper.findAll('button').find((b) => b.text() === 'Accept and update document')
    await acceptButton?.trigger('click')
    await flushPromises()

    expect(acceptPatchProposal).toHaveBeenCalledWith(7, 1, 501, 1, expect.any(String))
    expect(wrapper.text()).toContain('Applied to the document.')
  })
})

describe('WorkspaceView document handoff from the new-document screen', () => {
  // jsdom keeps one window.history for the whole file, and a router push to the URL a previous
  // test already left current is a no-op that leaves that test's handoff state in place -- so start
  // and end every test here on a clean history entry, and never leak a handoff into later describes.
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
  })
  afterEach(() => {
    window.history.replaceState(null, '', '/')
  })

  async function mountWithHandoff(handoff: DocumentHandoff) {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/documents/:id', component: WorkspaceView },
      ],
    })
    await router.push({ path: '/documents/1', state: documentHandoffState(handoff) })
    await router.isReady()
    const wrapper = mount(WorkspaceView, { props: { documentId: 1 }, global: { plugins: [router] } })
    await flushPromises()
    return wrapper
  }

  /**
   * A real browser finding: a source attached during document creation was invisible here, so the
   * Assist tab told the person to attach a source they had attached seconds earlier.
   */
  it('shows a source attached during creation and lets Assist use it immediately', async () => {
    const wrapper = await mountWithHandoff({
      attachedSources: [{ id: 3, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z' }],
      sourceWarning: null,
    })

    expect(wrapper.text()).toContain('Source #3')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    expect(wrapper.text()).not.toContain('Attach a source first')
    expect(wrapper.findAll('button').some((b) => b.text() === 'Try grounded extraction')).toBe(true)
  })

  it('shows the creation screen\'s failed-attachment warning here, where the person actually lands', async () => {
    const wrapper = await mountWithHandoff({
      attachedSources: [],
      sourceWarning: 'Your source file could not be attached. The document was still created; attach it again from the Sources tab.',
    })

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toMatch(/source file could not be attached/i)
    expect(wrapper.text()).toContain('No sources attached to this document yet.')
  })

  it('behaves exactly as before when nothing was handed over', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/documents/:id', component: WorkspaceView },
      ],
    })
    await router.push('/documents/1')
    await router.isReady()
    const wrapper = mount(WorkspaceView, { props: { documentId: 1 }, global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('No sources attached to this document yet.')
  })
})

describe('WorkspaceView proposal with action-item rows', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(startExtraction).mockReset()
    vi.mocked(getJob).mockReset()
    vi.mocked(getExtractionResult).mockReset()
    vi.mocked(applyGenerationResult).mockReset()
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
  })

  it('lists every proposed row per repeated field and names each row it could not propose', async () => {
    const wrapper = await mountWorkspaceView()
    await attachAFakeSource(wrapper)
    vi.mocked(startExtraction).mockResolvedValue({
      commandId: 'c1', jobId: 42, operation: 'generation.start-extraction', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z',
    })
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockResolvedValue({ artifactId: 77 })
    vi.mocked(applyGenerationResult).mockResolvedValue({
      id: 501,
      documentId: 1,
      baseRevisionId: 1,
      proposedValues: {
        'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Weekly Robotics Club Sync', values: null, evidenceSpanIds: [] },
        'action.item.task': {
          type: 'TEXT', cardinality: 'REPEATED', value: null,
          values: ['finish wiring the practice robot', 'confirm the van reservation'], evidenceSpanIds: [],
        },
        'action.item.owner': { type: 'TEXT', cardinality: 'REPEATED', value: null, values: ['Alex Chen', 'Jose Nunez'], evidenceSpanIds: [] },
        'action.item.due': { type: 'DATE', cardinality: 'REPEATED', value: null, values: ['2026-03-12', '2026-03-10'], evidenceSpanIds: [] },
      },
      status: 'PROPOSED',
      createdAt: '2026-03-01T00:00:00Z',
      proposedRepeatedItemCount: 2,
      skippedRepeatedItems: [{ itemIndex: 2, unresolvedFieldIds: ['action.item.due'], description: 'order the new batteries' }],
    })

    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    await wrapper.findAll('button').find((b) => b.text() === 'Try grounded extraction')?.trigger('click')
    await flushPromises()
    const applyButton = wrapper.findAll('button').find((b) => b.text() === 'Apply to document')
    await applyButton?.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('finish wiring the practice robot')
    expect(text).toContain('confirm the van reservation')
    expect(text).toContain('Alex Chen')
    expect(text).toContain('2026-03-10')
    expect(text).toContain('2 action items proposed')
    const status = wrapper.find('[role="status"]')
    expect(status.exists()).toBe(true)
    expect(status.text()).toContain('1 action item was found in your source but could not be proposed')
    expect(status.text()).toContain('order the new batteries')
    expect(status.text()).toContain('missing: action.item.due')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

describe('WorkspaceView field review and lock', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset()
    vi.mocked(recordReviewDecision).mockReset()
    vi.mocked(setFieldLock).mockReset()
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  it('records a review decision and toggles a lock, refreshing the document afterward', async () => {
    authenticate()
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_SCALAR_FIELD)
    const wrapper = await mountWorkspaceView()

    expect(wrapper.text()).toContain('Not reviewed')
    expect(wrapper.text()).not.toContain('EDITABLE')

    vi.mocked(recordReviewDecision).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
    vi.mocked(getDocument).mockResolvedValueOnce({
      ...DOCUMENT_WITH_A_SCALAR_FIELD,
      currentRevision: {
        ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision,
        fields: {
          'meeting.title': {
            ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields['meeting.title'],
            fieldState: { ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields['meeting.title'].fieldState!, review: 'ACCEPTED' },
          },
        },
      },
    })

    const acceptButton = wrapper.findAll('button').find((b) => b.text() === 'Accept')
    // A field list can hold many identically-labelled "Accept" buttons -- the accessible name has
    // to disambiguate which field this one acts on for a screen-reader user tabbing through them.
    expect(acceptButton?.attributes('aria-label')).toBe('Accept meeting.title')
    await acceptButton?.trigger('click')
    await flushPromises()

    expect(recordReviewDecision).toHaveBeenCalledWith(7, 1, 1, 'meeting.title', 'ACCEPTED', expect.any(String))
    expect(wrapper.text()).toContain('Accepted')

    vi.mocked(setFieldLock).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision)
    vi.mocked(getDocument).mockResolvedValueOnce({
      ...DOCUMENT_WITH_A_SCALAR_FIELD,
      currentRevision: {
        ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision,
        fields: {
          'meeting.title': {
            ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields['meeting.title'],
            fieldState: { ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields['meeting.title'].fieldState!, lock: 'EXPLICITLY_LOCKED' },
          },
        },
      },
    })

    const lockButton = wrapper.findAll('button').find((b) => b.text() === 'Lock')
    await lockButton?.trigger('click')
    await flushPromises()

    expect(setFieldLock).toHaveBeenCalledWith(7, 1, 1, 'meeting.title', 'EXPLICITLY_LOCKED', expect.any(String))
    expect(wrapper.text()).toContain('Locked')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Unlock')).toBeTruthy()
  })
})

function validationManifestResponse(overrides: Partial<ValidationManifestResponse> = {}): ValidationManifestResponse {
  return {
    id: 501,
    documentId: 1,
    revisionId: 1,
    templateId: 1,
    templateVersionId: 1,
    docxArtifactId: 900,
    docxSha256: 'c'.repeat(64),
    pdfArtifactId: 901,
    pdfSha256: 'd'.repeat(64),
    findings: [],
    hasUnresolvedBlocking: false,
    createdAt: '2026-03-02T00:00:00Z',
    ...overrides,
  }
}

function exportApprovalResponse(overrides: Partial<ExportApprovalResponse> = {}): ExportApprovalResponse {
  return {
    id: 601,
    documentId: 1,
    revisionId: 1,
    templateVersionId: 1,
    validationManifestId: 501,
    format: 'BOTH',
    approvedAt: '2026-03-02T00:01:00Z',
    ...overrides,
  }
}

function exportReceiptResponse(overrides: Partial<ExportReceiptResponse> = {}): ExportReceiptResponse {
  return {
    id: 701,
    documentId: 1,
    revisionId: 1,
    templateVersionId: 1,
    exportApprovalId: 601,
    validationManifestId: 501,
    docxArtifactId: 900,
    docxSha256: 'c'.repeat(64),
    pdfArtifactId: 901,
    pdfSha256: 'd'.repeat(64),
    format: 'BOTH',
    isCompletePair: true,
    exportedAt: '2026-03-02T00:02:00Z',
    ...overrides,
  }
}

describe('WorkspaceView checks tab', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(validateDocument).mockReset()
    // No prior validation/approval/receipt recorded for this revision -- the common case, and what
    // hydrateChecksState() sees every time the Checks tab is opened in these tests.
    vi.mocked(getLatestValidation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(approveExport).mockReset()
    vi.mocked(getLatestExportApproval).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(exportDocument).mockReset()
    vi.mocked(getLatestExportReceipt).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  async function openChecksTab(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>) {
    const checksTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Checks')
    await checksTab?.trigger('click')
  }

  it('validates, approves, and exports the current revision, then shows download links', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()
    await openChecksTab(wrapper)

    const manifest = validationManifestResponse({
      findings: [{ code: 'WEAK_EVIDENCE', severity: 'WARNING', fieldId: 'meeting.title', message: 'Low confidence value.' }],
    })
    vi.mocked(validateDocument).mockResolvedValue(manifest)

    const validateButton = wrapper.findAll('button').find((b) => b.text() === 'Validate this revision')
    await validateButton?.trigger('click')
    await flushPromises()

    expect(validateDocument).toHaveBeenCalledWith(7, 1, 1, expect.any(String))
    expect(wrapper.text()).toContain('WARNING')
    expect(wrapper.text()).toContain('Meeting title')
    expect(wrapper.text()).toContain('Low confidence value.')
    expect(wrapper.text()).toContain('Ready to export')

    vi.mocked(approveExport).mockResolvedValue(exportApprovalResponse({ validationManifestId: manifest.id, format: 'BOTH' }))

    const approveButton = wrapper.findAll('button').find((b) => b.text() === 'Approve for export')
    expect(approveButton).toBeTruthy()
    await approveButton?.trigger('click')
    await flushPromises()

    expect(approveExport).toHaveBeenCalledWith(7, 1, manifest.id, 'BOTH')

    vi.mocked(exportDocument).mockResolvedValue(exportReceiptResponse({ docxArtifactId: 900, pdfArtifactId: 901, isCompletePair: true }))

    const exportButton = wrapper.findAll('button').find((b) => b.text() === 'Export')
    expect(exportButton).toBeTruthy()
    await exportButton?.trigger('click')
    await flushPromises()

    expect(exportDocument).toHaveBeenCalledWith(7, 1)

    const docxLink = wrapper.findAll('a').find((a) => a.text() === 'Download DOCX')
    const pdfLink = wrapper.findAll('a').find((a) => a.text() === 'Download PDF')
    expect(docxLink?.attributes('href')).toBe(artifactDownloadUrl(7, 900))
    expect(pdfLink?.attributes('href')).toBe(artifactDownloadUrl(7, 901))
  })

  it('rehydrates an already-validated, approved, and exported revision when the Checks tab opens', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    const manifest = validationManifestResponse()
    const approval = exportApprovalResponse({ validationManifestId: manifest.id, format: 'BOTH' })
    const receipt = exportReceiptResponse({ exportApprovalId: approval.id })
    vi.mocked(getLatestValidation).mockReset().mockResolvedValueOnce(manifest)
    vi.mocked(getLatestExportApproval).mockReset().mockResolvedValueOnce(approval)
    vi.mocked(getLatestExportReceipt).mockReset().mockResolvedValueOnce(receipt)

    await openChecksTab(wrapper)
    await flushPromises()

    expect(getLatestValidation).toHaveBeenCalledWith(7, 1, 1)
    expect(wrapper.text()).toContain('Ready to export')
    expect(wrapper.text()).toContain('Approved for: BOTH')
    expect(wrapper.findAll('a').find((a) => a.text() === 'Download DOCX')).toBeTruthy()
    // Nothing was actually clicked -- this state came entirely from rehydration.
    expect(validateDocument).not.toHaveBeenCalled()
    expect(approveExport).not.toHaveBeenCalled()
  })

  it('does not offer to approve export while blocking findings are unresolved', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()
    await openChecksTab(wrapper)

    vi.mocked(validateDocument).mockResolvedValue(
      validationManifestResponse({
        hasUnresolvedBlocking: true,
        findings: [{ code: 'REQUIRED_FIELD_EMPTY', severity: 'BLOCKING', fieldId: 'meeting.title', message: 'This field is required.' }],
      }),
    )

    const validateButton = wrapper.findAll('button').find((b) => b.text() === 'Validate this revision')
    await validateButton?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('BLOCKING')
    expect(wrapper.text()).toContain('This field is required.')
    expect(wrapper.text()).toContain('Cannot export: unresolved blocking findings')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Approve for export')).toBeUndefined()
  })

  it('clears validation, approval, and export state on a 412 conflict, forcing the user to validate again', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()
    await openChecksTab(wrapper)

    const manifest = validationManifestResponse()
    vi.mocked(validateDocument).mockResolvedValue(manifest)
    const validateButton = wrapper.findAll('button').find((b) => b.text() === 'Validate this revision')
    await validateButton?.trigger('click')
    await flushPromises()

    // Get a real approval (and its Export button) on screen first, so the assertions below prove
    // the 412 actually tore that state down rather than merely never having built it up.
    vi.mocked(approveExport).mockResolvedValue(exportApprovalResponse({ validationManifestId: manifest.id, format: 'BOTH' }))
    const approveButton = wrapper.findAll('button').find((b) => b.text() === 'Approve for export')
    await approveButton?.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('button').find((b) => b.text() === 'Export')).toBeTruthy()

    vi.mocked(exportDocument).mockRejectedValue(
      new ApiRequestError(412, {
        status: 412,
        title: 'Stale export approval',
        code: 'STALE_EXPORT_APPROVAL',
        correlationId: 'c1',
        fields: [],
        recoveryActions: [],
      }),
    )

    const exportButton = wrapper.findAll('button').find((b) => b.text() === 'Export')
    await exportButton?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('This document changed since you last validated it. Validate again.')
    // The stale manifest/approval/receipt are gone, not just the button that was clicked --
    // re-validating is genuinely required, matching what the message says.
    expect(wrapper.text()).not.toContain('Ready to export')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Export')).toBeUndefined()
    expect(wrapper.findAll('button').find((b) => b.text() === 'Approve for export')).toBeUndefined()
    expect(wrapper.findAll('button').find((b) => b.text() === 'Validate this revision')).toBeTruthy()
  })
})

const HISTORY_REVISION_1: DocumentRevisionResponse = {
  id: 1,
  revisionNumber: 1,
  fields: {
    'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Weekly Sync', evidenceSourceSpanIds: [] },
    'meeting.location': { type: 'TEXT', cardinality: 'SCALAR', value: 'Room A', evidenceSourceSpanIds: [] },
  },
  contentHash: 'a'.repeat(64),
  editReason: 'created',
  createdAt: '2026-03-01T00:00:00Z',
}

const HISTORY_REVISION_2: DocumentRevisionResponse = {
  id: 2,
  revisionNumber: 2,
  parentRevisionId: 1,
  fields: {
    'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Weekly Sync', evidenceSourceSpanIds: [] },
    'meeting.location': { type: 'TEXT', cardinality: 'SCALAR', value: 'Room B', evidenceSourceSpanIds: [] },
  },
  contentHash: 'b'.repeat(64),
  editReason: 'Edited location',
  createdAt: '2026-03-02T00:00:00Z',
}

const DOCUMENT_WITH_HISTORY: DocumentResponse = {
  ...DOCUMENT,
  currentRevisionId: 2,
  currentRevision: HISTORY_REVISION_2,
}

describe('WorkspaceView revision history', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_HISTORY)
    vi.mocked(listDocumentRevisions).mockReset()
    vi.mocked(getDocumentRevision).mockReset()
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  it('lists revisions most-recent-first and compares a past revision against the current one', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    vi.mocked(listDocumentRevisions).mockResolvedValue([HISTORY_REVISION_1, HISTORY_REVISION_2])

    const historyTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'History')
    await historyTab?.trigger('click')
    await flushPromises()

    expect(listDocumentRevisions).toHaveBeenCalledWith(7, 1)
    const rows = wrapper.findAll('.revision-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Revision 2')
    expect(rows[1].text()).toContain('Revision 1')

    vi.mocked(getDocumentRevision).mockResolvedValue(HISTORY_REVISION_1)
    const pastRow = rows.find((row) => row.text().includes('Revision 1'))
    // Every row's "Compare to current" button reads identically out of context -- the accessible
    // name has to say which revision it compares.
    expect(pastRow?.find('button').attributes('aria-label')).toBe('Compare revision 1 to current')
    await pastRow?.find('button').trigger('click')
    await flushPromises()

    expect(getDocumentRevision).toHaveBeenCalledWith(7, 1, 1)

    const unchangedRow = wrapper.findAll('.compare-row').find((row) => row.text().includes('Meeting title'))
    const changedRow = wrapper.findAll('.compare-row').find((row) => row.text().includes('Meeting location'))
    expect(unchangedRow?.classes()).not.toContain('compare-row--changed')
    expect(changedRow?.classes()).toContain('compare-row--changed')
    expect(changedRow?.text()).toContain('Room A')
    expect(changedRow?.text()).toContain('Room B')
  })

  it('ignores a slower compare response after a faster, more recent one has already resolved', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()
    vi.mocked(listDocumentRevisions).mockResolvedValue([HISTORY_REVISION_1, HISTORY_REVISION_2])

    const historyTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'History')
    await historyTab?.trigger('click')
    await flushPromises()

    let resolveFirstRequest!: (revision: DocumentRevisionResponse) => void
    const firstRequest = new Promise<DocumentRevisionResponse>((resolve) => {
      resolveFirstRequest = resolve
    })
    vi.mocked(getDocumentRevision).mockReturnValueOnce(firstRequest)
    vi.mocked(getDocumentRevision).mockResolvedValueOnce(HISTORY_REVISION_2)

    const rows = wrapper.findAll('.revision-row')
    const revision1Row = rows.find((row) => row.text().includes('Revision 1'))
    const revision2Row = rows.find((row) => row.text().includes('Revision 2'))

    // Compare revision 1 first (its request will hang), then revision 2 (resolves right away) before
    // the first ever finishes -- the same drift a user causes by clicking two "Compare" buttons in a row.
    await revision1Row?.find('button').trigger('click')
    await revision2Row?.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Revision 2 vs. current')

    resolveFirstRequest(HISTORY_REVISION_1)
    await flushPromises()

    // The stale revision-1 response resolving late must not clobber the already-displayed revision 2.
    expect(wrapper.text()).toContain('Revision 2 vs. current')
  })
})

describe('WorkspaceView inspector tablist keyboard navigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([])
    vi.mocked(getLatestValidation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getLatestExportApproval).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getLatestExportReceipt).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  function tabs(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>) {
    return wrapper.findAll('button[role="tab"]')
  }

  it('gives only the active tab a tab stop, per the roving-tabindex pattern', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    expect(tabs(wrapper).find((tab) => tab.text() === 'Sources')?.attributes('tabindex')).toBe('0')
    for (const tab of tabs(wrapper).filter((tab) => tab.text() !== 'Sources')) {
      expect(tab.attributes('tabindex')).toBe('-1')
    }
  })

  it('ArrowRight moves to and activates the next tab, wrapping from the last tab to the first', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    await tabs(wrapper).find((tab) => tab.text() === 'Sources')?.trigger('keydown', { key: 'ArrowRight' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'Checks')?.attributes('aria-selected')).toBe('true')

    await tabs(wrapper).find((tab) => tab.text() === 'Checks')?.trigger('keydown', { key: 'ArrowRight' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'History')?.attributes('aria-selected')).toBe('true')

    // Wraps from the last tab back to the first.
    await tabs(wrapper).find((tab) => tab.text() === 'History')?.trigger('keydown', { key: 'ArrowRight' })
    const assistTab = tabs(wrapper).find((tab) => tab.text() === 'Assist')
    expect(assistTab?.attributes('aria-selected')).toBe('true')
    expect(assistTab?.attributes('tabindex')).toBe('0')
  })

  it('ArrowLeft moves to and activates the previous tab, wrapping from the first tab to the last', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    await tabs(wrapper).find((tab) => tab.text() === 'Assist')?.trigger('keydown', { key: 'ArrowLeft' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'History')?.attributes('aria-selected')).toBe('true')
  })

  it('treats ArrowDown/ArrowUp the same as ArrowRight/ArrowLeft', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    await tabs(wrapper).find((tab) => tab.text() === 'Sources')?.trigger('keydown', { key: 'ArrowDown' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'Checks')?.attributes('aria-selected')).toBe('true')

    await tabs(wrapper).find((tab) => tab.text() === 'Checks')?.trigger('keydown', { key: 'ArrowUp' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'Sources')?.attributes('aria-selected')).toBe('true')
  })

  it('Home and End jump straight to the first and last tab', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    await tabs(wrapper).find((tab) => tab.text() === 'Checks')?.trigger('keydown', { key: 'End' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'History')?.attributes('aria-selected')).toBe('true')

    await tabs(wrapper).find((tab) => tab.text() === 'History')?.trigger('keydown', { key: 'Home' })
    expect(tabs(wrapper).find((tab) => tab.text() === 'Assist')?.attributes('aria-selected')).toBe('true')
  })

  it('moves real DOM focus onto the newly active tab, not just the roving tabindex attribute', async () => {
    authenticate()
    const wrapper = await mountWorkspaceViewAttached()

    await tabs(wrapper).find((tab) => tab.text() === 'Sources')?.trigger('keydown', { key: 'ArrowRight' })

    expect(document.activeElement).toBe(tabs(wrapper).find((tab) => tab.text() === 'Checks')?.element)

    wrapper.unmount()
  })
})

describe('WorkspaceView inspector drawer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([])
    vi.mocked(getLatestValidation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getLatestExportApproval).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getLatestExportReceipt).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  function drawerToggle(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>) {
    return wrapper.find('button[aria-controls="inspector-drawer"]')
  }

  function drawerFocusable(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>): HTMLElement[] {
    return Array.from(
      wrapper.find('#inspector-drawer').element.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, [tabindex]'),
    ).filter((el) => !el.hasAttribute('disabled') && el.tabIndex >= 0)
  }

  it('starts collapsed, with a real "Show details" toggle wired to the drawer via aria-expanded/aria-controls', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    const toggle = drawerToggle(wrapper)
    expect(toggle.text()).toBe('Show details')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-controls')).toBe('inspector-drawer')
    expect(wrapper.find('#inspector-drawer').exists()).toBe(true)
  })

  it('opens on click, flips its label and aria-expanded, and moves focus into the drawer', async () => {
    authenticate()
    const wrapper = await mountWorkspaceViewAttached()

    await drawerToggle(wrapper).trigger('click')

    expect(drawerToggle(wrapper).text()).toBe('Hide details')
    expect(drawerToggle(wrapper).attributes('aria-expanded')).toBe('true')
    expect(document.activeElement?.id).toBe('inspector-drawer-heading')

    wrapper.unmount()
  })

  it('never traps Tab when the drawer was never opened -- desktop width has no collapse, so the drawer must not intercept focus there', async () => {
    authenticate()
    const wrapper = await mountWorkspaceViewAttached()

    expect(drawerToggle(wrapper).attributes('aria-expanded')).toBe('false')
    const focusable = drawerFocusable(wrapper)
    expect(focusable.length).toBeGreaterThan(1)
    const last = focusable[focusable.length - 1]!
    last.focus()

    await wrapper.find('#inspector-drawer').trigger('keydown', { key: 'Tab' })

    expect(document.activeElement).toBe(last)

    wrapper.unmount()
  })

  it('closes on Escape and returns focus to the toggle button', async () => {
    authenticate()
    const wrapper = await mountWorkspaceViewAttached()

    await drawerToggle(wrapper).trigger('click')
    expect(document.activeElement?.id).toBe('inspector-drawer-heading')

    await wrapper.find('#inspector-drawer').trigger('keydown', { key: 'Escape' })

    expect(drawerToggle(wrapper).attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(drawerToggle(wrapper).element)

    wrapper.unmount()
  })

  it('traps Tab inside the open drawer, wrapping from the last focusable element to the first', async () => {
    authenticate()
    const wrapper = await mountWorkspaceViewAttached()
    await drawerToggle(wrapper).trigger('click')

    const focusable = drawerFocusable(wrapper)
    expect(focusable.length).toBeGreaterThan(1)
    const last = focusable[focusable.length - 1]!
    last.focus()
    expect(document.activeElement).toBe(last)

    await wrapper.find('#inspector-drawer').trigger('keydown', { key: 'Tab' })

    expect(document.activeElement).toBe(focusable[0])

    wrapper.unmount()
  })

  it('traps Shift+Tab inside the open drawer, wrapping from the first focusable element to the last', async () => {
    authenticate()
    const wrapper = await mountWorkspaceViewAttached()
    await drawerToggle(wrapper).trigger('click')

    const focusable = drawerFocusable(wrapper)
    expect(focusable.length).toBeGreaterThan(1)
    const first = focusable[0]!
    first.focus()
    expect(document.activeElement).toBe(first)

    await wrapper.find('#inspector-drawer').trigger('keydown', { key: 'Tab', shiftKey: true })

    expect(document.activeElement).toBe(focusable[focusable.length - 1])

    wrapper.unmount()
  })
})

describe('WorkspaceView accessibility', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([])
    vi.mocked(getLatestValidation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getLatestExportApproval).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getLatestExportReceipt).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  it('has no automatically-detectable accessibility violations for a loaded document with a reviewed field', async () => {
    authenticate()
    const wrapper = await mountWorkspaceView()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations on the Assist tab with an open, unanswered question', async () => {
    authenticate()
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(allocateUpload).mockReset().mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'minutes.txt' })
    vi.mocked(uploadArtifactContent).mockReset().mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'minutes.txt' })
    vi.mocked(completeUpload).mockReset().mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'minutes.txt' })
    vi.mocked(extractArtifact).mockReset().mockResolvedValue({ status: 'COMPLETE' })
    vi.mocked(attachDocumentSource).mockReset().mockResolvedValue({ id: 3, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' })
    vi.mocked(startExtraction)
      .mockReset()
      .mockResolvedValue({ commandId: 'c1', jobId: 42, operation: 'generation.start-extraction', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z' })
    vi.mocked(getJob).mockReset().mockResolvedValueOnce(jobResponse('WAITING_FOR_INPUT'))
    vi.mocked(getGenerationQuestions).mockReset().mockResolvedValue([CONFLICT_QUESTION])
    const wrapper = await mountWorkspaceView()
    await attachAFakeSource(wrapper)

    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    await wrapper.findAll('button').find((b) => b.text() === 'Try grounded extraction')?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Old Title')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations on the Checks tab with populated findings and an approved, exported receipt', async () => {
    authenticate()
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(getLatestValidation)
      .mockReset()
      .mockResolvedValue(
        validationManifestResponse({
          findings: [{ code: 'WEAK_EVIDENCE', severity: 'WARNING', fieldId: 'meeting.title', message: 'Low confidence value.' }],
        }),
      )
    vi.mocked(getLatestExportApproval).mockReset().mockResolvedValue(exportApprovalResponse())
    vi.mocked(getLatestExportReceipt).mockReset().mockResolvedValue(exportReceiptResponse())
    const wrapper = await mountWorkspaceView()

    const checksTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Checks')
    await checksTab?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Low confidence value.')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations on the History tab with a revision list and an open comparison', async () => {
    authenticate()
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_HISTORY)
    vi.mocked(listDocumentRevisions).mockReset().mockResolvedValue([HISTORY_REVISION_1, HISTORY_REVISION_2])
    vi.mocked(getDocumentRevision).mockReset().mockResolvedValue(HISTORY_REVISION_1)
    const wrapper = await mountWorkspaceView()

    const historyTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'History')
    await historyTab?.trigger('click')
    await flushPromises()
    const pastRow = wrapper.findAll('.revision-row').find((row) => row.text().includes('Revision 1'))
    await pastRow?.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.compare-row').length).toBeGreaterThan(0)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no violations with the inspector drawer open', async () => {
    authenticate()
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    const wrapper = await mountWorkspaceViewAttached()

    const toggle = wrapper.find('button[aria-controls="inspector-drawer"]')
    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    wrapper.unmount()
  })
})

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
    { fieldId: 'action.item.task', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'action.item.task' },
    { fieldId: 'action.item.owner', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'action.item.owner' },
    { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'action.item.due' },
  ],
  createdAt: '2026-03-01T00:00:00Z',
  activatedAt: '2026-03-01T00:00:00Z',
}

const ITEM_STATE = { authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'NOT_RUN', review: 'UNREVIEWED', lock: 'EDITABLE' } as const

const DOCUMENT_WITH_A_ROW: DocumentResponse = {
  ...DOCUMENT,
  currentRevisionId: 3,
  currentRevision: {
    ...DOCUMENT.currentRevision,
    id: 3,
    revisionNumber: 3,
    fields: {
      'meeting.title': {
        type: 'TEXT',
        cardinality: 'SCALAR',
        value: 'Garden Club Planning',
        evidenceSourceSpanIds: [],
        fieldState: { ...ITEM_STATE },
      },
      'action.item.task': { type: 'TEXT', cardinality: 'REPEATED', values: ['Order seedlings'], evidenceSourceSpanIds: [], itemFieldStates: [{ ...ITEM_STATE }] },
      'action.item.owner': { type: 'TEXT', cardinality: 'REPEATED', values: ['Maria Lopez'], evidenceSourceSpanIds: [], itemFieldStates: [{ ...ITEM_STATE }] },
      'action.item.due': { type: 'DATE', cardinality: 'REPEATED', values: ['2026-04-20'], evidenceSourceSpanIds: [], itemFieldStates: [{ ...ITEM_STATE }] },
    },
  },
}

function input(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>, id: string) {
  return wrapper.find(`[id="${id}"]`)
}

describe('WorkspaceView editing by hand', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset()
    vi.mocked(getTemplateVersion).mockReset().mockResolvedValue(MINUTES_TEMPLATE_VERSION)
    vi.mocked(patchDocumentContent).mockReset()
    vi.mocked(recordReviewDecision).mockReset()
    vi.mocked(setFieldLock).mockReset()
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
  })

  it('renders a control for every template field, saves typed values as one typed edit against the current revision, then reloads', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const wrapper = await mountWorkspaceView()

    expect(getTemplateVersion).toHaveBeenCalledWith(7, 1, 1)
    expect(input(wrapper, 'edit-meeting.title').exists()).toBe(true)
    expect(input(wrapper, 'edit-meeting.date').attributes('type')).toBe('date')
    expect(wrapper.text()).toContain('Nothing filled in yet.')
    const saveButton = wrapper.findAll('button').find((b) => b.text() === 'Save now')
    expect(saveButton?.attributes('disabled')).toBeDefined()

    await input(wrapper, 'edit-meeting.title').setValue('Garden Club Planning')
    await input(wrapper, 'edit-meeting.date').setValue('2026-04-09')
    expect(wrapper.text()).toContain('Unsaved changes.')
    expect(saveButton?.attributes('disabled')).toBeUndefined()

    vi.mocked(patchDocumentContent).mockResolvedValue({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 2, revisionNumber: 2 })
    vi.mocked(getDocument).mockResolvedValueOnce({
      ...DOCUMENT,
      currentRevisionId: 2,
      currentRevision: {
        ...DOCUMENT.currentRevision,
        id: 2,
        revisionNumber: 2,
        fields: {
          'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Garden Club Planning', evidenceSourceSpanIds: [], fieldState: { ...ITEM_STATE } },
          'meeting.date': { type: 'DATE', cardinality: 'SCALAR', value: '2026-04-09', evidenceSourceSpanIds: [], fieldState: { ...ITEM_STATE } },
        },
      },
    })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

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
    expect(wrapper.text()).toContain('Saved.')
    expect(wrapper.text()).not.toContain('Unsaved changes.')
    expect((input(wrapper, 'edit-meeting.title').element as HTMLInputElement).value).toBe('Garden Club Planning')
    expect(wrapper.text()).toContain('Typed by you')
  })

  it('adds a row, refuses to save it until its date column is filled, then saves every column of the rows together', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const wrapper = await mountWorkspaceView()

    expect(wrapper.text()).toContain('No rows yet.')
    await wrapper.findAll('button').find((b) => b.text() === 'Add row')!.trigger('click')
    await input(wrapper, 'edit-action.item.task-0').setValue('Order seedlings')
    await input(wrapper, 'edit-action.item.owner-0').setValue('Maria Lopez')

    expect(wrapper.text()).toContain('Row 1 needs a value for Action item due')
    const saveButton = wrapper.findAll('button').find((b) => b.text() === 'Save now')
    expect(saveButton?.attributes('disabled')).toBeDefined()

    await input(wrapper, 'edit-action.item.due-0').setValue('2026-04-20')
    expect(wrapper.text()).not.toContain('Row 1 needs a value')
    expect(saveButton?.attributes('disabled')).toBeUndefined()

    vi.mocked(patchDocumentContent).mockResolvedValue(DOCUMENT_WITH_A_ROW.currentRevision)
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_ROW)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      expect.objectContaining({
        expectedRevisionId: 1,
        edits: [
          { operation: 'SET', fieldId: 'action.item.task', value: { type: 'TEXT', cardinality: 'REPEATED', values: ['Order seedlings'] } },
          { operation: 'SET', fieldId: 'action.item.owner', value: { type: 'TEXT', cardinality: 'REPEATED', values: ['Maria Lopez'] } },
          { operation: 'SET', fieldId: 'action.item.due', value: { type: 'DATE', cardinality: 'REPEATED', values: ['2026-04-20'] } },
        ],
      }),
      expect.any(String),
    )
    expect(wrapper.text()).toContain('Saved.')
    expect(wrapper.findAll('button').find((b) => b.attributes('aria-label') === 'Accept row 1')).toBeTruthy()
  })

  it('clears a scalar the person emptied and leaves untouched fields out of the edit', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_ROW)
    const wrapper = await mountWorkspaceView()

    await input(wrapper, 'edit-meeting.title').setValue('')
    vi.mocked(patchDocumentContent).mockResolvedValue(DOCUMENT_WITH_A_ROW.currentRevision)
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_ROW)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledWith(
      7,
      1,
      expect.objectContaining({ expectedRevisionId: 3, edits: [{ operation: 'CLEAR', fieldId: 'meeting.title' }] }),
      expect.any(String),
    )
  })

  it('keeps the typed draft after a 412 and offers to save it onto the revision that is now current', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT)
    const wrapper = await mountWorkspaceView()
    await input(wrapper, 'edit-meeting.title').setValue('My title')

    vi.mocked(patchDocumentContent).mockRejectedValueOnce(
      new ApiRequestError(412, { status: 412, title: 'Stale revision', code: 'STALE_REVISION', correlationId: 'c2', fields: [], recoveryActions: [] }),
    )
    const movedOn: DocumentResponse = {
      ...DOCUMENT,
      currentRevisionId: 9,
      currentRevision: { ...DOCUMENT.currentRevision, id: 9, revisionNumber: 9, fields: {} },
    }
    vi.mocked(getDocument).mockResolvedValueOnce(movedOn)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('This document changed since you started editing (revision 9 is now current)')
    expect((input(wrapper, 'edit-meeting.title').element as HTMLInputElement).value).toBe('My title')

    vi.mocked(patchDocumentContent).mockResolvedValueOnce({ ...movedOn.currentRevision, id: 10, revisionNumber: 10 })
    vi.mocked(getDocument).mockResolvedValueOnce({ ...movedOn, currentRevisionId: 10, currentRevision: { ...movedOn.currentRevision, id: 10, revisionNumber: 10 } })
    await wrapper.findAll('button').find((b) => b.text() === 'Save my edits onto the latest')!.trigger('click')
    await flushPromises()

    expect(vi.mocked(patchDocumentContent).mock.calls[1]![2]).toMatchObject({ expectedRevisionId: 9 })
    expect(wrapper.text()).not.toContain('is now current')
  })

  it('disables a locked field and reports the server\'s own locked-field refusal', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce({
      ...DOCUMENT_WITH_A_ROW,
      currentRevision: {
        ...DOCUMENT_WITH_A_ROW.currentRevision,
        fields: {
          ...DOCUMENT_WITH_A_ROW.currentRevision.fields,
          'meeting.title': { ...DOCUMENT_WITH_A_ROW.currentRevision.fields['meeting.title']!, fieldState: { ...ITEM_STATE, lock: 'EXPLICITLY_LOCKED' } },
        },
      },
    })
    const wrapper = await mountWorkspaceView()

    expect(input(wrapper, 'edit-meeting.title').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Locked: unlock it to edit.')

    await input(wrapper, 'edit-action.item.owner-0').setValue('Someone Else')
    vi.mocked(patchDocumentContent).mockRejectedValueOnce(
      new ApiRequestError(409, {
        status: 409,
        title: 'Field locked',
        code: 'FIELD_LOCKED',
        detail: 'Field action.item.owner is explicitly locked.',
        correlationId: 'c3',
        fields: [],
        recoveryActions: [],
      }),
    )
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Field action.item.owner is explicitly locked.')
    expect((input(wrapper, 'edit-action.item.owner-0').element as HTMLInputElement).value).toBe('Someone Else')
  })

  it('reviews and locks a whole row by addressing every column with the row index, each against the revision the last one produced', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const wrapper = await mountWorkspaceView()

    vi.mocked(recordReviewDecision)
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 4 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 5 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 6 })
    await wrapper.findAll('button').find((b) => b.attributes('aria-label') === 'Accept row 1')!.trigger('click')
    await flushPromises()

    expect(vi.mocked(recordReviewDecision).mock.calls).toEqual([
      [7, 1, 3, 'action.item.task', 'ACCEPTED', expect.any(String), 0],
      [7, 1, 4, 'action.item.owner', 'ACCEPTED', expect.any(String), 0],
      [7, 1, 5, 'action.item.due', 'ACCEPTED', expect.any(String), 0],
    ])

    vi.mocked(setFieldLock)
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 7 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 8 })
      .mockResolvedValueOnce({ ...DOCUMENT_WITH_A_ROW.currentRevision, id: 9 })
    await wrapper.findAll('button').find((b) => b.attributes('aria-label') === 'Lock row 1')!.trigger('click')
    await flushPromises()

    expect(vi.mocked(setFieldLock).mock.calls.map((call) => [call[2], call[3], call[4], call[6]])).toEqual([
      [3, 'action.item.task', 'EXPLICITLY_LOCKED', 0],
      [7, 'action.item.owner', 'EXPLICITLY_LOCKED', 0],
      [8, 'action.item.due', 'EXPLICITLY_LOCKED', 0],
    ])
  })

  it('has no automatically-detectable accessibility violations with the editor showing rows', async () => {
    vi.mocked(getDocument).mockResolvedValueOnce(DOCUMENT_WITH_A_ROW)
    const wrapper = await mountWorkspaceView()
    await wrapper.findAll('button').find((b) => b.text() === 'Add row')!.trigger('click')
    expect(await axe(wrapper.element as HTMLElement)).toHaveNoViolations()
  })
})

function generationRun(jobState: string, overrides: Partial<import('@/api/client').GenerationRunResponse> = {}): import('@/api/client').GenerationRunResponse {
  return {
    id: 77,
    jobId: 42,
    documentId: 1,
    baseRevisionId: 1,
    sourceSnapshotId: 3,
    sourceArtifactId: 5,
    modelName: 'gpt-5.4-mini-2026-03-17',
    promptVersion: 'extraction-v1',
    createdAt: '2026-03-01T00:00:00Z',
    job: jobResponse(jobState),
    resultArtifactId: null,
    ...overrides,
  }
}

describe('WorkspaceView generation runs survive a reload', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(getTemplateVersion).mockReset().mockResolvedValue(MINUTES_TEMPLATE_VERSION)
    vi.mocked(listDocumentSources).mockReset()
    vi.mocked(listGenerationRuns).mockReset()
    vi.mocked(getGenerationQuestions).mockReset()
    vi.mocked(getJob).mockReset()
    vi.mocked(cancelJob).mockReset()
    vi.mocked(startExtraction).mockReset()
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
  })

  it('lists the sources the server links to this document, not only what a previous screen handed over', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([
      { id: 3, artifactId: 5, displayFilename: 'march-notes.txt', kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' },
      { id: 2, artifactId: 4, displayFilename: 'february-notes.txt', kind: 'ARTIFACT', fetchedAt: '2026-02-01T00:00:00Z', attachedAt: '2026-02-01T00:00:00Z' },
    ])
    vi.mocked(listGenerationRuns).mockResolvedValue([])
    const wrapper = await mountWorkspaceView()

    expect(listDocumentSources).toHaveBeenCalledWith(7, 1)
    expect(wrapper.text()).toContain('march-notes.txt')
    expect(wrapper.text()).toContain('february-notes.txt')

    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    const picker = wrapper.find('#extract-source')
    expect(picker.exists()).toBe(true)
    await picker.setValue('2')
    vi.mocked(startExtraction).mockResolvedValue({ commandId: 'c1', jobId: 42, operation: 'generation.start-extraction', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z' })
    vi.mocked(getJob).mockResolvedValue(jobResponse('SUCCEEDED'))
    vi.mocked(getExtractionResult).mockReset().mockResolvedValue({ artifactId: 900 })
    await wrapper.findAll('button').find((b) => b.text() === 'Try grounded extraction')!.trigger('click')
    await flushPromises()

    expect(startExtraction).toHaveBeenCalledWith(7, 1, 4, expect.any(String))
  })

  it('resumes a run that is waiting for answers straight from the server, without anyone clicking Extract', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([
      { id: 3, artifactId: 5, displayFilename: 'notes.txt', kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' },
    ])
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('WAITING_FOR_INPUT')])
    vi.mocked(getGenerationQuestions).mockResolvedValue([CONFLICT_QUESTION])
    const wrapper = await mountWorkspaceView()

    expect(getGenerationQuestions).toHaveBeenCalledWith(7, 1, 42)
    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    expect(wrapper.text()).toContain('A few things need your input')
    expect(wrapper.text()).toContain('Weekly Robotics Club Sync')
    expect(startExtraction).not.toHaveBeenCalled()
  })

  it('offers to apply the result of a run that already finished, and reports a cancelled one', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([])
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('SUCCEEDED', { resultArtifactId: 900 })])
    const wrapper = await mountWorkspaceView()
    const assistTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')
    await assistTab?.trigger('click')
    // No source attached any more, but the finished run is still the document's to apply.
    expect(wrapper.text()).toContain('Attach a source first')

    vi.mocked(listDocumentSources).mockResolvedValue([
      { id: 3, artifactId: 5, displayFilename: 'notes.txt', kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' },
    ])
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('CANCELLED')])
    const second = await mountWorkspaceView()
    await second.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')?.trigger('click')
    expect(second.text()).toContain('This run was cancelled.')
  })

  it('requests cancellation of a running job and shows it as requested until the job really ends', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([
      { id: 3, artifactId: 5, displayFilename: 'notes.txt', kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' },
    ])
    vi.mocked(listGenerationRuns).mockResolvedValue([generationRun('LEASED')])
    vi.mocked(getJob).mockResolvedValueOnce(jobResponse('LEASED')).mockResolvedValueOnce({ ...jobResponse('CANCEL_REQUESTED'), cancellationRequestedAt: '2026-03-01T00:00:10Z' }).mockResolvedValue(jobResponse('CANCELLED'))
    vi.mocked(cancelJob).mockResolvedValue({ commandId: 'c9', jobId: 42, operation: 'job.request-cancellation', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z' })
    const wrapper = await mountWorkspaceView()
    await wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === 'Assist')?.trigger('click')

    const cancelButton = wrapper.findAll('button').find((b) => b.text() === 'Cancel')
    expect(cancelButton).toBeTruthy()
    await cancelButton!.trigger('click')
    await flushPromises()
    expect(cancelJob).toHaveBeenCalledWith(7, 42, expect.any(String))
    expect(wrapper.text()).toContain('Cancellation requested')

    await new Promise((resolve) => setTimeout(resolve, 1700))
    await new Promise((resolve) => setTimeout(resolve, 1700))
    await flushPromises()
    expect(wrapper.text()).toContain('This run was cancelled.')
  }, 10_000)
})

const COMPILATION: CompilationManifestResponse = {
  id: 11,
  documentId: 1,
  revisionId: 1,
  templateId: 1,
  templateVersionId: 1,
  docxArtifactId: 20,
  docxSha256: 'b'.repeat(64),
  pdfArtifactId: 21,
  pdfSha256: 'c'.repeat(64),
  rendererVersion: 'test-renderer',
  integrityFindings: [],
  allIntegrityChecksPassed: true,
  compiledAt: '2026-03-01T00:00:00Z',
}

describe('WorkspaceView preview pane and evidence', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(getTemplateVersion).mockReset().mockResolvedValue(MINUTES_TEMPLATE_VERSION)
    vi.mocked(listDocumentSources).mockReset().mockResolvedValue([])
    vi.mocked(listGenerationRuns).mockReset().mockResolvedValue([])
    vi.mocked(getLatestCompilation).mockReset()
    vi.mocked(compileRevision).mockReset()
    vi.mocked(getEvidenceExcerpt).mockReset()
    window.matchMedia = vi.fn().mockReturnValue({ matches: true }) as unknown as typeof window.matchMedia
  })

  it('shows an existing compilation as the preview of the current content', async () => {
    vi.mocked(getLatestCompilation).mockResolvedValue(COMPILATION)
    const wrapper = await mountWorkspaceView()
    // The preview component is loaded on demand; one more tick lets its (stubbed) chunk resolve.
    await flushPromises()

    expect(getLatestCompilation).toHaveBeenCalledWith(7, 1, 1)
    expect(wrapper.text()).toContain('Preview of version 1.')
    expect(wrapper.text()).not.toContain('has changed since')
    expect(wrapper.find('[data-testid="pdf-preview"]').text()).toBe('/api/v1/workspaces/7/uploads/21/preview')
    expect(wrapper.find('a[target="_blank"]').attributes('href')).toBe('/api/v1/workspaces/7/uploads/21/preview')
    expect(wrapper.text()).toContain('Regenerate preview')
    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('offers to generate a preview when none exists yet, and draws the one it made', async () => {
    vi.mocked(getLatestCompilation).mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(compileRevision).mockResolvedValue({ ...COMPILATION, pdfArtifactId: 31 })
    const wrapper = await mountWorkspaceView()
    await flushPromises()

    expect(wrapper.text()).toContain('No preview yet.')
    expect(wrapper.find('[data-testid="pdf-preview"]').text()).toBe('')
    const generate = wrapper.findAll('button').find((button) => button.text() === 'Generate preview')!
    await generate.trigger('click')
    await flushPromises()

    expect(compileRevision).toHaveBeenCalledWith(7, 1, 1)
    expect(wrapper.find('[data-testid="pdf-preview"]').text()).toBe('/api/v1/workspaces/7/uploads/31/preview')
    expect(wrapper.text()).toContain('Preview of version 1.')
  })

  it('hides the pane on request and does not fetch a preview while hidden', async () => {
    vi.mocked(getLatestCompilation).mockResolvedValue(COMPILATION)
    const wrapper = await mountWorkspaceView()
    const toggle = wrapper.findAll('button').find((button) => button.text() === 'Hide preview')!
    expect(toggle.attributes('aria-expanded')).toBe('true')

    await toggle.trigger('click')
    expect(wrapper.find('[data-testid="pdf-preview"]').exists()).toBe(false)
    expect(wrapper.findAll('button').find((button) => button.text() === 'Show preview')!.attributes('aria-expanded')).toBe('false')
  })

  it('opens the cited excerpt behind a value that carries evidence, and says what it cannot show', async () => {
    vi.mocked(getLatestCompilation).mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getDocument).mockResolvedValue({
      ...DOCUMENT_WITH_A_SCALAR_FIELD,
      currentRevision: {
        ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision,
        fields: {
          'meeting.title': { ...DOCUMENT_WITH_A_SCALAR_FIELD.currentRevision.fields['meeting.title']!, evidenceSourceSpanIds: [12] },
        },
      },
    })
    vi.mocked(getEvidenceExcerpt).mockResolvedValue({
      spanId: 12,
      sourceSnapshotId: 3,
      sourceArtifactId: 5,
      displayFilename: 'minutes.txt',
      locatorType: 'PLAIN_TEXT',
      excerptText: 'The meeting title is "Weekly Sync".',
    })
    const wrapper = await mountWorkspaceView()

    const marker = wrapper.find('button[aria-label="Evidence for meeting.title"]')
    expect(marker.text()).toBe('Evidence (1)')
    expect(marker.attributes('aria-expanded')).toBe('false')
    await marker.trigger('click')
    await flushPromises()

    expect(getEvidenceExcerpt).toHaveBeenCalledWith(7, 1, 12)
    const panel = wrapper.find('#evidence-meeting\\.title')
    expect(panel.text()).toContain('The meeting title is "Weekly Sync".')
    expect(panel.text()).toContain('From minutes.txt')
    expect(panel.text()).toContain('cannot point to where a value lands on the preview page')
    expect(marker.attributes('aria-expanded')).toBe('true')
    expect((await axe(wrapper.element)).violations).toEqual([])

    await marker.trigger('click')
    expect(wrapper.find('#evidence-meeting\\.title').exists()).toBe(false)
  })

  it('shows no evidence marker for a value typed by hand', async () => {
    vi.mocked(getLatestCompilation).mockRejectedValue(new ApiRequestError(404, undefined))
    const wrapper = await mountWorkspaceView()
    expect(wrapper.find('button[aria-label="Evidence for meeting.title"]').exists()).toBe(false)
  })
})

describe('WorkspaceView saved, conflict, limits and rules', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(getTemplateVersion).mockReset().mockResolvedValue(MINUTES_TEMPLATE_VERSION)
    vi.mocked(listDocumentSources).mockReset().mockResolvedValue([])
    vi.mocked(listGenerationRuns).mockReset().mockResolvedValue([])
    vi.mocked(getLatestCompilation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(patchDocumentContent).mockReset()
    vi.mocked(recordReviewDecision).mockReset()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue({
      maxUploadBytes: 10485760,
      uploadMediaTypes: [{ mediaType: 'text/plain', extension: 'txt' }],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
    })
    vi.mocked(listTemplateVersionRules).mockReset().mockResolvedValue([])
    window.matchMedia = vi.fn().mockReturnValue({ matches: false }) as unknown as typeof window.matchMedia
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('saves on its own a moment after typing stops, and announces it', async () => {
    const wrapper = await mountWorkspaceView()
    vi.mocked(patchDocumentContent).mockResolvedValue({ ...DOCUMENT.currentRevision, id: 2, revisionNumber: 2 })
    vi.mocked(getDocument).mockResolvedValue({
      ...DOCUMENT,
      currentRevisionId: 2,
      currentRevision: {
        ...DOCUMENT.currentRevision,
        id: 2,
        revisionNumber: 2,
        fields: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Autosaved title', evidenceSourceSpanIds: [], fieldState: { ...ITEM_STATE } } },
      },
    })

    vi.useFakeTimers()
    await wrapper.find('[id="edit-meeting.title"]').setValue('Autosaved title')
    expect(wrapper.text()).toContain('Unsaved changes. Brownie saves a moment after you stop typing.')
    await vi.advanceTimersByTimeAsync(2_000)
    expect(patchDocumentContent).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(600)
    vi.useRealTimers()
    await flushPromises()

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(vi.mocked(patchDocumentContent).mock.calls[0]![2].edits).toEqual([
      { operation: 'SET', fieldId: 'meeting.title', value: { type: 'TEXT', cardinality: 'SCALAR', value: 'Autosaved title' } },
    ])
    expect(wrapper.text()).toContain('Saved.')
    expect(wrapper.find('[aria-live="polite"][aria-atomic="true"]').text()).toBe('Saved.')
  })

  it('does not autosave a row that still has a problem, and says why', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_ROW)
    const wrapper = await mountWorkspaceView()

    vi.useFakeTimers()
    await wrapper.find('[id="edit-action.item.due-0"]').setValue('')
    await vi.advanceTimersByTimeAsync(3_000)
    vi.useRealTimers()
    await flushPromises()

    expect(patchDocumentContent).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Row 1 needs a value for')
  })

  it('shows the upload limit on the Sources tab before a file is chosen', async () => {
    const wrapper = await mountWorkspaceView()
    expect(getCapabilities).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('up to 10 MB')
  })

  it('lists the accepted rules of the document\'s own template version on the Rules tab, read-only', async () => {
    vi.mocked(listTemplateVersionRules).mockResolvedValue([
      {
        id: 1, templateId: 1, templateVersionId: 1, category: 'VALIDATION',
        scope: { kind: 'WHOLE_TEMPLATE' }, payload: { kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.title', 'meeting.date'] },
        status: 'ACCEPTED', humanExplanation: 'Every set of minutes names its meeting.', authorUserId: 1, createdAt: '2026-03-01T00:00:00Z',
      },
      {
        id: 2, templateId: 1, templateVersionId: 1, category: 'CONTENT',
        scope: { kind: 'FIELD', fieldId: 'meeting.title' }, payload: { kind: 'MAX_TEXT_LENGTH', fieldId: 'meeting.title', maxCharacters: 80 },
        status: 'PROPOSED', humanExplanation: null, authorUserId: 1, createdAt: '2026-03-01T00:00:00Z',
      },
      {
        id: 3, templateId: 1, templateVersionId: 9, category: 'VALIDATION',
        scope: { kind: 'WHOLE_TEMPLATE' }, payload: { kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.location'] },
        status: 'ACCEPTED', humanExplanation: null, authorUserId: 1, createdAt: '2026-03-01T00:00:00Z',
      },
    ] as never)
    const wrapper = await mountWorkspaceView()

    await wrapper.findAll('[role="tab"]').find((tab) => tab.text() === 'Rules')!.trigger('click')
    await flushPromises()

    expect(listTemplateVersionRules).toHaveBeenCalledWith(7, 1, 1)
    const text = wrapper.text()
    expect(text).toContain('Require: meeting.title, meeting.date')
    expect(text).toContain('Every set of minutes names its meeting.')
    expect(text).not.toContain('meeting.location')
    expect(text).not.toContain('at most 80 characters')
    expect(text).toContain('1 proposed rule is waiting for a decision on the template.')
    expect(wrapper.findAll('button').some((button) => button.text() === 'Accept' || button.text() === 'Reject')).toBe(false)
    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('reloads and explains when a review decision hits a revision that moved on', async () => {
    vi.mocked(getDocument).mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(recordReviewDecision).mockRejectedValue(new ApiRequestError(412, { status: 412, title: 'Stale', detail: 'Revision moved on.', code: 'STALE_REVISION', correlationId: 'c-1', fields: [], recoveryActions: [] }))
    const wrapper = await mountWorkspaceView()
    const loadsBefore = vi.mocked(getDocument).mock.calls.length

    await wrapper.find('button[aria-label="Accept meeting.title"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(getDocument).mock.calls.length).toBe(loadsBefore + 1)
    expect(wrapper.find('[role="alert"]').text()).toContain('This document changed since you loaded it, so it was reloaded.')
  })

  it('takes keyboard focus to the field a validation finding names', async () => {
    vi.mocked(validateDocument).mockResolvedValue({
      id: 5, documentId: 1, revisionId: 1, templateId: 1, templateVersionId: 1,
      docxArtifactId: 20, docxSha256: 'b'.repeat(64), pdfArtifactId: 21, pdfSha256: 'c'.repeat(64),
      findings: [{ code: 'REQUIRED_FIELD_MISSING', severity: 'BLOCKING', fieldId: 'meeting.title', message: 'Meeting title is required.' }],
      hasUnresolvedBlocking: true, createdAt: '2026-03-01T00:00:00Z',
    } as never)
    const wrapper = await mountWorkspaceViewAttached()
    try {
      const checksTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text() === 'Checks')!
      await checksTab.trigger('click')
      const validate = wrapper.findAll('button').find((button) => button.text() === 'Validate this revision')!
      await validate.trigger('click')
      await flushPromises()

      const goTo = wrapper.find('button[aria-label="Go to meeting.title"]')
      expect(goTo.exists()).toBe(true)
      await goTo.trigger('click')
      expect(document.activeElement?.id).toBe('edit-meeting.title')
    } finally {
      wrapper.unmount()
    }
  })
})

describe('WorkspaceView Assist composer', () => {
  const HELP = ['Draft from these sources.', 'Change <field> to <value>.', 'Shorten <field>.', 'Explain this finding.']

  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT_WITH_A_SCALAR_FIELD)
    vi.mocked(getTemplateVersion).mockReset().mockResolvedValue(MINUTES_TEMPLATE_VERSION)
    vi.mocked(listDocumentSources).mockReset().mockResolvedValue([])
    vi.mocked(listGenerationRuns).mockReset().mockResolvedValue([])
    vi.mocked(getLatestCompilation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getCapabilities).mockReset().mockRejectedValue(new Error('none'))
    vi.mocked(interpretAssist).mockReset()
    vi.mocked(executeAssist).mockReset()
    vi.mocked(acceptPatchProposal).mockReset()
    vi.mocked(startExtraction).mockReset()
    window.matchMedia = vi.fn().mockReturnValue({ matches: false }) as unknown as typeof window.matchMedia
  })

  async function openAssist() {
    const wrapper = await mountWorkspaceView()
    await wrapper.findAll('[role="tab"]').find((tab) => tab.text() === 'Assist')!.trigger('click')
    return wrapper
  }

  async function ask(wrapper: Awaited<ReturnType<typeof mountWorkspaceView>>, text: string) {
    await wrapper.find('#assist-composer').setValue(text)
    await wrapper.find('form.assist-composer').trigger('submit')
    await flushPromises()
  }

  it('answers free text with what Brownie can do, and offers nothing to run', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'NONE', summary: 'Brownie did not recognise that. Here is what it can do:', scope: null, executable: false, usesModel: false, help: HELP,
    })
    const wrapper = await openAssist()
    await ask(wrapper, 'write me a poem')

    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'write me a poem')
    expect(wrapper.text()).toContain('Here is what it can do:')
    expect(wrapper.findAll('.assist-help li')).toHaveLength(4)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Do it')).toBe(false)
    expect((await axe(wrapper.element)).violations).toEqual([])
  })

  it('shows the scope of a change, runs it only on request, and hands the proposal to the accept step', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'CHANGE_FIELD', summary: 'Change Meeting title to "Spring Planning".',
      scope: { fieldId: 'meeting.title', label: 'Meeting title', currentValue: 'Weekly Sync', findingMessage: null },
      executable: true, usesModel: false, help: [],
    })
    vi.mocked(executeAssist).mockResolvedValue({
      kind: 'CHANGE_FIELD', summary: 'Change Meeting title to "Spring Planning".', explanation: null, help: [],
      proposal: {
        id: 33, documentId: 1, baseRevisionId: 1, status: 'PROPOSED', createdAt: '2026-03-01T00:00:00Z',
        proposedValues: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Spring Planning', values: null, evidenceSpanIds: [] } },
        proposedRepeatedItemCount: 0, skippedRepeatedItems: [],
      },
    })
    vi.mocked(acceptPatchProposal).mockResolvedValue({ applied: true, fieldStatuses: { 'meeting.title': 'CLEAN' }, revision: { ...DOCUMENT.currentRevision, id: 2, revisionNumber: 2 } })
    const wrapper = await openAssist()
    await ask(wrapper, 'change meeting title to Spring Planning')

    expect(wrapper.text()).toContain('What Assist would do')
    expect(wrapper.text()).toContain('Meeting title (meeting.title)')
    expect(wrapper.text()).toContain('Weekly Sync')
    expect(executeAssist).not.toHaveBeenCalled()

    await wrapper.findAll('button').find((button) => button.text() === 'Do it')!.trigger('click')
    await flushPromises()

    expect(executeAssist).toHaveBeenCalledWith(7, 1, 'change meeting title to Spring Planning', 1)
    expect(wrapper.text()).toContain('Proposed changes')
    expect(wrapper.text()).toContain('Spring Planning')
    await wrapper.findAll('button').find((button) => button.text() === 'Accept and update document')!.trigger('click')
    await flushPromises()
    expect(acceptPatchProposal).toHaveBeenCalledWith(7, 1, 33, 1, expect.any(String))
    expect(wrapper.text()).toContain('Applied to the document.')
  })

  it('shows an explanation as text and says a model call is involved beforehand', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'EXPLAIN_FINDING', summary: 'Explain the finding on Meeting date: Meeting date is required.',
      scope: { fieldId: 'meeting.date', label: 'Meeting date', currentValue: null, findingMessage: 'Meeting date is required.' },
      executable: true, usesModel: true, help: [],
    })
    vi.mocked(executeAssist).mockResolvedValue({
      kind: 'EXPLAIN_FINDING', summary: 'Explain the finding on Meeting date.', proposal: null, help: [],
      explanation: 'The meeting date is empty; type it in the Meeting date field.',
    })
    const wrapper = await openAssist()
    await ask(wrapper, 'explain this finding')
    expect(wrapper.text()).toContain('This makes one model call.')
    expect(wrapper.text()).toContain('Meeting date is required.')

    await wrapper.findAll('button').find((button) => button.text() === 'Do it')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.assist-explanation').text()).toContain('type it in the Meeting date field')
    expect(wrapper.text()).not.toContain('Proposed changes')
  })

  it('cannot draft without a source, and starts extraction from one when there is', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'DRAFT', summary: 'Fill this document from an attached source.', scope: null, executable: true, usesModel: true, help: [],
    })
    const wrapper = await openAssist()
    await ask(wrapper, 'draft from these sources')
    await wrapper.findAll('button').find((button) => button.text() === 'Do it')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('Attach a source on the Sources tab first')
    expect(startExtraction).not.toHaveBeenCalled()

    vi.mocked(listDocumentSources).mockResolvedValue([{ id: 3, artifactId: 5, displayFilename: 'minutes.txt', kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' }])
    vi.mocked(startExtraction).mockResolvedValue({ commandId: 'c', jobId: 42, operation: 'start', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z' })
    vi.mocked(getJob).mockResolvedValue(jobResponse('QUEUED'))
    const withSource = await openAssist()
    await ask(withSource, 'draft from these sources')
    await withSource.findAll('button').find((button) => button.text() === 'Do it')!.trigger('click')
    await flushPromises()
    expect(startExtraction).toHaveBeenCalledWith(7, 1, 5, expect.any(String))
  })

  it('reloads and explains when the revision moved on before the request ran', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'CHANGE_FIELD', summary: 'Change Meeting title to "X".',
      scope: { fieldId: 'meeting.title', label: 'Meeting title', currentValue: 'Weekly Sync', findingMessage: null },
      executable: true, usesModel: false, help: [],
    })
    vi.mocked(executeAssist).mockRejectedValue(new ApiRequestError(412, { status: 412, title: 'Stale', detail: 'moved', code: 'STALE', correlationId: 'c', fields: [], recoveryActions: [] }))
    const wrapper = await openAssist()
    await ask(wrapper, 'change meeting title to X')
    const loadsBefore = vi.mocked(getDocument).mock.calls.length
    await wrapper.findAll('button').find((button) => button.text() === 'Do it')!.trigger('click')
    await flushPromises()
    expect(vi.mocked(getDocument).mock.calls.length).toBe(loadsBefore + 1)
    expect(wrapper.find('.assist-composer [role="alert"]').text()).toContain('was reloaded')
  })
})

describe('WorkspaceView keeps typed text across a save', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    vi.mocked(getDocument).mockReset().mockResolvedValue(DOCUMENT)
    vi.mocked(getTemplateVersion).mockReset().mockResolvedValue(MINUTES_TEMPLATE_VERSION)
    vi.mocked(listDocumentSources).mockReset().mockResolvedValue([])
    vi.mocked(listGenerationRuns).mockReset().mockResolvedValue([])
    vi.mocked(listTemplateVersionRules).mockReset().mockResolvedValue([])
    vi.mocked(getLatestCompilation).mockReset().mockRejectedValue(new ApiRequestError(404, undefined))
    vi.mocked(getCapabilities).mockReset().mockRejectedValue(new Error('none'))
    vi.mocked(patchDocumentContent).mockReset()
    window.matchMedia = vi.fn().mockReturnValue({ matches: false }) as unknown as typeof window.matchMedia
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not lose or replace text typed while a save is in flight, and keeps the input mounted', async () => {
    const wrapper = await mountWorkspaceViewAttached()
    try {
      const titleInput = () => wrapper.find('[id="edit-meeting.title"]')
      const inputElementBefore = titleInput().element

      let finishSave: (value: DocumentRevisionResponse) => void = () => {}
      vi.mocked(patchDocumentContent).mockImplementation(() => new Promise((resolve) => { finishSave = resolve }))
      vi.mocked(getDocument).mockResolvedValue({
        ...DOCUMENT,
        currentRevisionId: 2,
        currentRevision: {
          ...DOCUMENT.currentRevision,
          id: 2,
          revisionNumber: 2,
          fields: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Priya Rao, Alex', evidenceSourceSpanIds: [], fieldState: { ...ITEM_STATE } } },
        },
      })

      ;(titleInput().element as HTMLInputElement).focus()
      vi.useFakeTimers()
      await titleInput().setValue('Priya Rao, Alex')
      await vi.advanceTimersByTimeAsync(2_600)
      vi.useRealTimers()
      expect(patchDocumentContent).toHaveBeenCalledTimes(1)

      // The save is still in flight; the person keeps typing.
      await titleInput().setValue('Priya Rao, Alex Chen, Jose Nunez')
      finishSave({ ...DOCUMENT.currentRevision, id: 2, revisionNumber: 2 })
      await flushPromises()

      expect((titleInput().element as HTMLInputElement).value).toBe('Priya Rao, Alex Chen, Jose Nunez')
      expect(titleInput().element).toBe(inputElementBefore)
      expect(document.activeElement).toBe(inputElementBefore)
      expect(wrapper.text()).toContain('Unsaved changes')
      expect(wrapper.text()).not.toContain('Loading document')
    } finally {
      wrapper.unmount()
    }
  })

  it('takes the server\'s copy into a field that was not touched during the save', async () => {
    const wrapper = await mountWorkspaceView()
    vi.mocked(patchDocumentContent).mockResolvedValue({ ...DOCUMENT.currentRevision, id: 2, revisionNumber: 2 })
    vi.mocked(getDocument).mockResolvedValue({
      ...DOCUMENT,
      currentRevisionId: 2,
      currentRevision: {
        ...DOCUMENT.currentRevision,
        id: 2,
        revisionNumber: 2,
        fields: { 'meeting.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Trimmed by the server', evidenceSourceSpanIds: [], fieldState: { ...ITEM_STATE } } },
      },
    })
    await wrapper.find('[id="edit-meeting.title"]').setValue('  Trimmed by the server  ')
    await wrapper.find('form.field-list').trigger('submit')
    await flushPromises()

    expect((wrapper.find('[id="edit-meeting.title"]').element as HTMLInputElement).value).toBe('Trimmed by the server')
    expect(wrapper.text()).toContain('Saved.')
    expect(wrapper.text()).not.toContain('Unsaved changes')
    expect(vi.mocked(patchDocumentContent).mock.calls[0]![2].editReason).toBe('Edited in the workspace.')
  })

  it('says when a queued run has had no worker claim it for half a minute', async () => {
    vi.mocked(listDocumentSources).mockResolvedValue([{ id: 3, artifactId: 5, displayFilename: 'minutes.txt', kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' }])
    vi.mocked(startExtraction).mockReset().mockResolvedValue({ commandId: 'c', jobId: 42, operation: 'start', status: 'ACCEPTED', acceptedAt: '2026-03-01T00:00:00Z' })
    vi.mocked(getJob).mockReset().mockResolvedValue({ ...jobResponse('QUEUED'), attemptCount: 0 })
    const wrapper = await mountWorkspaceView()
    await wrapper.findAll('[role="tab"]').find((tab) => tab.text() === 'Assist')!.trigger('click')

    vi.useFakeTimers()
    await wrapper.findAll('button').find((button) => button.text() === 'Try grounded extraction')!.trigger('click')
    await vi.advanceTimersByTimeAsync(5_000)
    expect(wrapper.text()).not.toContain('No worker has picked this run up yet')
    await vi.advanceTimersByTimeAsync(30_000)
    vi.useRealTimers()
    await flushPromises()
    expect(wrapper.text()).toContain('No worker has picked this run up yet')
  })
})
