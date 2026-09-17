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
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    extractArtifact: vi.fn(),
    attachSource: vi.fn(),
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

import {
  ApiRequestError,
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  approveExport,
  artifactDownloadUrl,
  attachSource,
  completeUpload,
  exportDocument,
  extractArtifact,
  getDocument,
  getDocumentRevision,
  getExtractionResult,
  getGenerationQuestions,
  getJob,
  getLatestExportApproval,
  getLatestExportReceipt,
  getLatestValidation,
  listDocumentRevisions,
  recordReviewDecision,
  resumeGeneration,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
  validateDocument,
} from '@/api/client'
import type { ExportApprovalResponse, ExportReceiptResponse, ValidationManifestResponse } from '@/api/client'
import { axe } from '@/test/axe'
import { documentHandoffState, type DocumentHandoff } from '@/router/handoff'

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
  vi.mocked(attachSource).mockResolvedValue({ id: 3, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z' })

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
    expect(wrapper.text()).toContain('meeting.title')
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

    expect(wrapper.text()).toContain('Source #3 attached.')
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
    expect(wrapper.text()).toContain('No sources attached yet.')
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
    expect(wrapper.text()).toContain('No sources attached yet.')
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

    expect(wrapper.text()).toContain('UNREVIEWED')
    expect(wrapper.text()).toContain('EDITABLE')

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
    expect(wrapper.text()).toContain('ACCEPTED')

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
    expect(wrapper.text()).toContain('EXPLICITLY_LOCKED')
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
    expect(wrapper.text()).toContain('meeting.title')
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
    expect(wrapper.text()).toContain('Cannot export -- unresolved blocking findings')
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

    expect(wrapper.text()).toContain('This document changed since you last validated it -- validate again.')
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

    const unchangedRow = wrapper.findAll('.compare-row').find((row) => row.text().includes('meeting.title'))
    const changedRow = wrapper.findAll('.compare-row').find((row) => row.text().includes('meeting.location'))
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
    vi.mocked(attachSource).mockReset().mockResolvedValue({ id: 3, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z' })
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
