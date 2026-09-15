import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { useSessionStore } from '@/stores/session'
import type { DocumentResponse, JobResponse, QuestionResponse } from '@/api/client'

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
  }
})

import {
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  attachSource,
  completeUpload,
  extractArtifact,
  getDocument,
  getExtractionResult,
  getGenerationQuestions,
  getJob,
  recordReviewDecision,
  resumeGeneration,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
} from '@/api/client'

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
    await wrapper.find('button.button--primary').trigger('click')
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
      proposedValues: { 'meeting.title': { type: 'TEXT', value: 'Executive Committee Sync', evidenceSpanIds: [] } },
      status: 'PROPOSED',
      createdAt: '2026-03-01T00:00:00Z',
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
