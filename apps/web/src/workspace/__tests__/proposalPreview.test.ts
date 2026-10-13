import { describe, expect, it } from 'vitest'
import type { PatchProposalResponse } from '@/api/client'
import { countPreviewSpots, previewDraftsFrom, type PreviewRules } from '@/workspace/proposalPreview'

type Proposed = PatchProposalResponse['proposedValues'][string]

const scalar = (value: string, type: 'TEXT' | 'DATE' = 'TEXT'): Proposed => ({ type, cardinality: 'SCALAR', value, values: null, evidenceSpanIds: [] })
const rows = (values: string[], type: 'TEXT' | 'DATE' = 'TEXT'): Proposed => ({ type, cardinality: 'REPEATED', value: null, values, evidenceSpanIds: [] })

function proposalOf(proposedValues: PatchProposalResponse['proposedValues']): PatchProposalResponse {
  return {
    id: 1,
    documentId: 1,
    baseRevisionId: 1,
    proposedValues,
    status: 'PROPOSED',
    createdAt: '2026-10-07T00:00:00Z',
    proposedRepeatedItemCount: 0,
    skippedRepeatedItems: [],
  }
}

const PAGE = new Set(['title', 'date', 'task', 'owner'])

function rules(overrides: Partial<PreviewRules> = {}): PreviewRules {
  return { fieldIds: PAGE, held: () => false, shown: () => undefined, ...overrides }
}

describe('previewDraftsFrom', () => {
  it('lays a proposal out like the page keeps its drafts: a text per field, a list of texts per repeated field', () => {
    const drafts = previewDraftsFrom(
      proposalOf({ title: scalar('Fall kickoff'), date: scalar('2026-10-06', 'DATE'), task: rows(['Order kit', 'Book room']), owner: rows(['Theo', 'Ana']) }),
      rules(),
    )

    // Dates stay as stored; the spot draws them the way the export does.
    expect(drafts).toEqual({ title: 'Fall kickoff', date: '2026-10-06', task: ['Order kit', 'Book room'], owner: ['Theo', 'Ana'] })
  })

  it('leaves out a field the page has no spot for', () => {
    expect(previewDraftsFrom(proposalOf({ elsewhere: scalar('Nowhere to go') }), rules())).toEqual({})
  })

  it('leaves out what approving would leave alone: a locked field, or one typed into since the proposal was made', () => {
    const drafts = previewDraftsFrom(
      proposalOf({ title: scalar('From the proposal'), date: scalar('2026-10-06', 'DATE') }),
      rules({ held: (fieldId) => fieldId === 'title' }),
    )

    expect(drafts).toEqual({ date: '2026-10-06' })
  })

  it('says whether a field is a table column when it asks if the field would be left alone', () => {
    const asked: [string, boolean][] = []
    previewDraftsFrom(
      proposalOf({ title: scalar('A title'), task: rows(['A']) }),
      rules({
        held: (fieldId, repeated) => {
          asked.push([fieldId, repeated])
          return repeated
        },
      }),
    )

    expect(asked).toEqual([
      ['title', false],
      ['task', true],
    ])
  })

  it('leaves out a value the document already holds, so only what is not in it yet is drawn as a preview', () => {
    const drafts = previewDraftsFrom(
      proposalOf({ title: scalar('Same title'), date: scalar('2026-10-06', 'DATE'), task: rows(['A', 'B']) }),
      rules({ shown: (fieldId) => ({ title: ' Same title ', task: ['A', 'B'] })[fieldId] }),
    )

    expect(drafts).toEqual({ date: '2026-10-06' })
  })

  it('draws a list that differs from the one on the page, even by one row', () => {
    const drafts = previewDraftsFrom(proposalOf({ task: rows(['A', 'B', 'C']) }), rules({ shown: () => ['A', 'B'] }))

    expect(drafts).toEqual({ task: ['A', 'B', 'C'] })
  })

  it('leaves out a blank value, which would only draw an empty ghost', () => {
    expect(previewDraftsFrom(proposalOf({ title: scalar('   '), task: rows([]) }), rules())).toEqual({})
  })

  it('does not touch the proposal it reads', () => {
    const values = ['A', 'B']
    const proposal = proposalOf({ task: rows(values) })

    previewDraftsFrom(proposal, rules())['task'] = ['changed']

    expect(proposal.proposedValues['task']!.values).toEqual(['A', 'B'])
  })
})

describe('countPreviewSpots', () => {
  it('counts each field once, and a table of rows as one spot however many columns it has', () => {
    expect(countPreviewSpots({ title: 'a', date: 'b', location: 'c', task: ['x', 'y', 'z'], owner: ['p', 'q', 'r'] })).toBe(4)
  })

  it('is zero for no preview', () => {
    expect(countPreviewSpots(null)).toBe(0)
    expect(countPreviewSpots({})).toBe(0)
  })
})
