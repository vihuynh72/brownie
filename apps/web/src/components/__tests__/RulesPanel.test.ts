import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import RulesPanel from '@/components/RulesPanel.vue'
import { axe } from '@/test/axe'
import type { RuleResponse } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listRules: vi.fn(),
    proposeRule: vi.fn(),
    acceptRule: vi.fn(),
    rejectRule: vi.fn(),
  }
})

import { listRules, acceptRule, rejectRule } from '@/api/client'

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

const FIELDS = [{ fieldId: 'meeting.title', cardinality: 'SCALAR' as const }]

describe('RulesPanel', () => {
  beforeEach(() => {
    vi.mocked(listRules).mockReset()
    vi.mocked(acceptRule).mockReset()
    vi.mocked(rejectRule).mockReset()
  })

  it("gives each rule row's Accept/Reject buttons an accessible name naming which rule they act on", async () => {
    vi.mocked(listRules).mockResolvedValue([RULE_REQUIRED_FIELDS, RULE_MAX_TEXT_LENGTH])
    const wrapper = mount(RulesPanel, { props: { workspaceId: 7, templateId: 42, fields: FIELDS } })
    await flushPromises()

    // Every proposed rule's row has an "Accept" and a "Reject" button that read identically out of
    // context -- the accessible name has to say which rule each one decides.
    const acceptButtons = wrapper.findAll('button').filter((b) => b.text() === 'Accept')
    expect(acceptButtons.map((b) => b.attributes('aria-label'))).toEqual([
      'Accept rule: Require: meeting.title',
      'Accept rule: meeting.title: at most 500 characters',
    ])

    const rejectButtons = wrapper.findAll('button').filter((b) => b.text() === 'Reject')
    expect(rejectButtons.map((b) => b.attributes('aria-label'))).toEqual([
      'Reject rule: Require: meeting.title',
      'Reject rule: meeting.title: at most 500 characters',
    ])
  })

  it('has no automatically-detectable accessibility violations with proposed rules listed', async () => {
    vi.mocked(listRules).mockResolvedValue([RULE_REQUIRED_FIELDS, RULE_MAX_TEXT_LENGTH])
    const wrapper = mount(RulesPanel, { props: { workspaceId: 7, templateId: 42, fields: FIELDS } })
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
