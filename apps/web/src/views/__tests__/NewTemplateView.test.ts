import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import NewTemplateView from '@/views/NewTemplateView.vue'
import { useSessionStore } from '@/stores/session'
import { ApiRequestError, type RuleResponse } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    extractArtifact: vi.fn(),
    createTemplateDraft: vi.fn(),
    getDraftCandidateBindings: vi.fn(),
    replaceDraftBindings: vi.fn(),
    activateTemplateVersion: vi.fn(),
    listRules: vi.fn(),
    proposeRule: vi.fn(),
    acceptRule: vi.fn(),
    rejectRule: vi.fn(),
  }
})

import {
  activateTemplateVersion,
  acceptRule,
  allocateUpload,
  completeUpload,
  createTemplateDraft,
  extractArtifact,
  getDraftCandidateBindings,
  listRules,
  proposeRule,
  rejectRule,
  replaceDraftBindings,
  uploadArtifactContent,
} from '@/api/client'

async function mountWithRouter() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/documents/new', component: { template: '<div />' } },
      { path: '/templates/new', component: NewTemplateView },
    ],
  })
  router.push('/templates/new')
  await router.isReady()
  return mount(NewTemplateView, { global: { plugins: [router] } })
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

const RULE_REQUIRED_FIELDS: RuleResponse = {
  id: 101,
  templateId: 42,
  templateVersionId: 2,
  category: 'VALIDATION',
  scope: { kind: 'WHOLE_TEMPLATE' },
  payload: { kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.title'] },
  status: 'PROPOSED',
  humanExplanation: 'Needed for every meeting',
  authorUserId: 1,
  createdAt: '2026-03-01T00:10:00Z',
}

const RULE_MAX_TEXT_LENGTH: RuleResponse = {
  id: 102,
  templateId: 42,
  templateVersionId: 2,
  category: 'VALIDATION',
  scope: { kind: 'WHOLE_TEMPLATE' },
  payload: { kind: 'MAX_TEXT_LENGTH', fieldId: 'meeting.title', maxCharacters: 500 },
  status: 'PROPOSED',
  humanExplanation: null,
  authorUserId: 1,
  createdAt: '2026-03-01T00:11:00Z',
}

/** Drives the wizard up through "Save fields" so the rules stage (and RulesPanel) is on screen, for tests that only care about the rules stage itself. */
async function advanceToRulesStage() {
  const wrapper = await mountWithRouter()
  await wrapper.find('#display-name').setValue('Club Bylaws Minutes')

  vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'bylaws.docx' })
  vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'bylaws.docx' })
  vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'bylaws.docx' })
  vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
  vi.mocked(createTemplateDraft).mockResolvedValue({
    template: { id: 42, displayName: 'Club Bylaws Minutes', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-03-01T00:00:00Z' },
    draftVersion: {
      id: 1, templateId: 42, versionNumber: 1, sourceArtifactId: 5, extractionVersionId: 9, status: 'DRAFT', fields: [],
      createdAt: '2026-03-01T00:00:00Z', activatedAt: null,
    },
  })
  vi.mocked(getDraftCandidateBindings).mockResolvedValue({
    candidates: [{ fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', contentControlTag: 'meeting.title' }],
    ambiguousContentControlTags: [],
  })

  const input = wrapper.find('#template-source')
  const file = new File(['docx bytes'], 'bylaws.docx')
  Object.defineProperty(input.element, 'files', { value: [file] })
  await input.trigger('change')
  await flushPromises()

  vi.mocked(replaceDraftBindings).mockResolvedValue({
    id: 2, templateId: 42, versionNumber: 2, sourceArtifactId: 5, extractionVersionId: 9, status: 'DRAFT',
    fields: [], createdAt: '2026-03-01T00:00:00Z', activatedAt: null,
  })
  vi.mocked(listRules).mockResolvedValueOnce([])
  const saveFieldsButton = wrapper.findAll('button').find((b) => b.text() === 'Save fields')
  await saveFieldsButton?.trigger('click')
  await flushPromises()

  return wrapper
}

describe('NewTemplateView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(allocateUpload).mockReset()
    vi.mocked(uploadArtifactContent).mockReset()
    vi.mocked(completeUpload).mockReset()
    vi.mocked(extractArtifact).mockReset()
    vi.mocked(createTemplateDraft).mockReset()
    vi.mocked(getDraftCandidateBindings).mockReset()
    vi.mocked(replaceDraftBindings).mockReset()
    vi.mocked(activateTemplateVersion).mockReset()
    vi.mocked(listRules).mockReset()
    vi.mocked(proposeRule).mockReset()
    vi.mocked(acceptRule).mockReset()
    vi.mocked(rejectRule).mockReset()
  })

  function authenticate() {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    return session
  }

  it('walks upload through candidate bindings, saving fields, proposing/deciding rules, and activation', async () => {
    authenticate()
    const wrapper = await mountWithRouter()

    await wrapper.find('#display-name').setValue('Club Bylaws Minutes')

    vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'bylaws.docx' })
    vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'bylaws.docx' })
    vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'bylaws.docx' })
    vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
    vi.mocked(createTemplateDraft).mockResolvedValue({
      template: { id: 42, displayName: 'Club Bylaws Minutes', status: 'DRAFT', currentActiveVersionId: null, createdAt: '2026-03-01T00:00:00Z' },
      draftVersion: {
        id: 1, templateId: 42, versionNumber: 1, sourceArtifactId: 5, extractionVersionId: 9, status: 'DRAFT', fields: [],
        createdAt: '2026-03-01T00:00:00Z', activatedAt: null,
      },
    })
    vi.mocked(getDraftCandidateBindings).mockResolvedValue({
      candidates: [
        { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', contentControlTag: 'meeting.title' },
        { fieldId: 'meeting.date', type: 'DATE', cardinality: 'SCALAR', contentControlTag: 'meeting.date' },
      ],
      ambiguousContentControlTags: ['meeting.duplicate'],
    })

    const input = wrapper.find('#template-source')
    const file = new File(['docx bytes'], 'bylaws.docx')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(createTemplateDraft).toHaveBeenCalledWith(7, 'Club Bylaws Minutes', 5)
    expect(wrapper.text()).toContain('meeting.duplicate')
    const fieldIdInput = wrapper.find('input[placeholder="field.id"]')
    expect((fieldIdInput.element as HTMLInputElement).value).toBe('meeting.title')

    // -- Save fields (no longer combined with activation) --
    vi.mocked(replaceDraftBindings).mockResolvedValue({
      id: 2, templateId: 42, versionNumber: 2, sourceArtifactId: 5, extractionVersionId: 9, status: 'DRAFT',
      fields: [], createdAt: '2026-03-01T00:00:00Z', activatedAt: null,
    })
    vi.mocked(listRules).mockResolvedValueOnce([])

    const saveFieldsButton = wrapper.findAll('button').find((b) => b.text() === 'Save fields')
    await saveFieldsButton?.trigger('click')
    await flushPromises()

    expect(replaceDraftBindings).toHaveBeenCalledWith(7, 42, 1, [
      { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'meeting.title' } },
      { fieldId: 'meeting.date', type: 'DATE', cardinality: 'SCALAR', requiredness: 'OPTIONAL', binding: { kind: 'CONTENT_CONTROL_TAG', tag: 'meeting.date' } },
    ])
    expect(activateTemplateVersion).not.toHaveBeenCalled()
    expect(listRules).toHaveBeenCalledWith(7, 42)
    expect(wrapper.text()).toContain('No rules proposed yet.')

    // -- Propose rule #1: REQUIRED_FIELDS (the form's default kind), with an explanation --
    vi.mocked(proposeRule).mockResolvedValueOnce(RULE_REQUIRED_FIELDS)
    vi.mocked(listRules).mockResolvedValueOnce([RULE_REQUIRED_FIELDS])

    await wrapper.find('input[type="checkbox"][value="meeting.title"]').setValue(true)
    await wrapper.find('#rule-explanation').setValue('Needed for every meeting')
    let proposeButton = wrapper.findAll('button').find((b) => b.text() === 'Propose rule')
    expect(proposeButton?.attributes('disabled')).toBeUndefined()
    await proposeButton?.trigger('click')
    await flushPromises()

    expect(proposeRule).toHaveBeenNthCalledWith(
      1, 7, 42,
      { kind: 'WHOLE_TEMPLATE' },
      { kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.title'] },
      'Needed for every meeting',
    )
    expect(wrapper.text()).toContain('Require: meeting.title')
    expect(wrapper.text()).toContain('Needed for every meeting')

    // -- Propose rule #2: switch kind to MAX_TEXT_LENGTH, proving the dynamic per-kind fields actually switch --
    vi.mocked(proposeRule).mockResolvedValueOnce(RULE_MAX_TEXT_LENGTH)
    vi.mocked(listRules).mockResolvedValueOnce([RULE_REQUIRED_FIELDS, RULE_MAX_TEXT_LENGTH])

    await wrapper.find('#rule-payload-kind').setValue('MAX_TEXT_LENGTH')
    await wrapper.find('#rule-single-field').setValue('meeting.title')
    await wrapper.find('#rule-max-characters').setValue('500')
    proposeButton = wrapper.findAll('button').find((b) => b.text() === 'Propose rule')
    await proposeButton?.trigger('click')
    await flushPromises()

    expect(proposeRule).toHaveBeenNthCalledWith(
      2, 7, 42,
      { kind: 'WHOLE_TEMPLATE' },
      { kind: 'MAX_TEXT_LENGTH', fieldId: 'meeting.title', maxCharacters: 500 },
      undefined,
    )
    expect(wrapper.text()).toContain('meeting.title: at most 500 characters')

    // -- Decide both proposed rules --
    vi.mocked(acceptRule).mockResolvedValue({ ...RULE_REQUIRED_FIELDS, status: 'ACCEPTED' })
    vi.mocked(rejectRule).mockResolvedValue({ ...RULE_MAX_TEXT_LENGTH, status: 'REJECTED' })
    vi.mocked(listRules).mockResolvedValueOnce([{ ...RULE_REQUIRED_FIELDS, status: 'ACCEPTED' }, RULE_MAX_TEXT_LENGTH])
    vi.mocked(listRules).mockResolvedValueOnce([{ ...RULE_REQUIRED_FIELDS, status: 'ACCEPTED' }, { ...RULE_MAX_TEXT_LENGTH, status: 'REJECTED' }])

    const ruleRows = wrapper.findAll('.rule-row')
    const requiredFieldsRow = ruleRows.find((row) => row.text().includes('Require: meeting.title'))
    const maxLengthRow = ruleRows.find((row) => row.text().includes('at most 500 characters'))

    await requiredFieldsRow?.findAll('button').find((b) => b.text() === 'Accept')?.trigger('click')
    await flushPromises()
    expect(acceptRule).toHaveBeenCalledWith(7, 42, 101)

    await maxLengthRow?.findAll('button').find((b) => b.text() === 'Reject')?.trigger('click')
    await flushPromises()
    expect(rejectRule).toHaveBeenCalledWith(7, 42, 102)

    expect(wrapper.text()).toContain('ACCEPTED')
    expect(wrapper.text()).toContain('REJECTED')

    // -- Activate, now a separate step from saving fields --
    vi.mocked(activateTemplateVersion).mockResolvedValue({
      id: 2, templateId: 42, versionNumber: 2, sourceArtifactId: 5, extractionVersionId: 9, status: 'ACTIVATED',
      fields: [], createdAt: '2026-03-01T00:00:00Z', activatedAt: '2026-03-01T00:05:00Z',
    })

    const activateButton = wrapper.findAll('button').find((b) => b.text() === 'Activate template')
    await activateButton?.trigger('click')
    await flushPromises()

    expect(activateTemplateVersion).toHaveBeenCalledWith(7, 42, 2)
    expect(wrapper.text()).toContain('is now active and ready to use')
  })

  it('shows the itemized validation problems when proposing a rule is rejected as RULE_VALIDATION_FAILED', async () => {
    authenticate()
    const wrapper = await advanceToRulesStage()

    vi.mocked(proposeRule).mockRejectedValueOnce(
      new ApiRequestError(422, {
        status: 422,
        title: 'Unprocessable Entity',
        detail: 'One or more proposed rules no longer apply to this draft. See the problems below.',
        code: 'RULE_VALIDATION_FAILED',
        correlationId: 'corr-1',
        fields: [],
        recoveryActions: [],
        problems: [{ reason: 'UNKNOWN_FIELD', detail: 'unknown field "meeting.title"' }],
      }),
    )

    await wrapper.find('input[type="checkbox"][value="meeting.title"]').setValue(true)
    const proposeButton = wrapper.findAll('button').find((b) => b.text() === 'Propose rule')
    await proposeButton?.trigger('click')
    await flushPromises()

    const alert = wrapper.find('.field-error[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('See the problems below')
    expect(alert.text()).toContain('unknown field "meeting.title"')
    // Submitting recovers rather than getting stuck on "Proposing...".
    expect(wrapper.findAll('button').find((b) => b.text() === 'Propose rule')?.attributes('disabled')).toBeUndefined()
    expect(listRules).toHaveBeenCalledTimes(1)
  })

  it('shows a list error, rather than going silently stale, when deciding a rule fails', async () => {
    authenticate()
    const wrapper = await advanceToRulesStage()

    vi.mocked(listRules).mockResolvedValueOnce([RULE_REQUIRED_FIELDS])
    vi.mocked(proposeRule).mockResolvedValueOnce(RULE_REQUIRED_FIELDS)
    await wrapper.find('input[type="checkbox"][value="meeting.title"]').setValue(true)
    await wrapper.findAll('button').find((b) => b.text() === 'Propose rule')?.trigger('click')
    await flushPromises()

    vi.mocked(acceptRule).mockRejectedValueOnce(
      new ApiRequestError(409, {
        status: 409,
        title: 'Conflict',
        detail: 'This rule is no longer PROPOSED.',
        code: 'RULE_NOT_DECIDABLE',
        correlationId: 'corr-2',
        fields: [],
        recoveryActions: [],
      }),
    )

    const requiredFieldsRow = wrapper.findAll('.rule-row').find((row) => row.text().includes('Require: meeting.title'))
    await requiredFieldsRow?.findAll('button').find((b) => b.text() === 'Accept')?.trigger('click')
    await flushPromises()

    const alert = wrapper.find('.field-error[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('This rule is no longer PROPOSED.')
    // The rule row is still there, unchanged, rather than the list silently going stale.
    expect(wrapper.text()).toContain('Require: meeting.title')
    expect(listRules).toHaveBeenCalledTimes(2)
  })
})
