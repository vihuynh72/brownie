import { describe, expect, it } from 'vitest'
import type { FieldStateResponse, FieldValueResponse, PatchProposalResponse, QuestionResponse } from '@/api/client'
import { fieldStateWords } from '@/workspace/layout'
import {
  answeredWords,
  answersByField,
  choiceWords,
  filledSource,
  leftOutWords,
  proposalChange,
  proposalRows,
  proposedSource,
  reviewEntries,
} from '@/workspace/proposalWords'

type Proposed = PatchProposalResponse['proposedValues'][string]

const LABELS: Record<string, string> = {
  title: 'Meeting title',
  date: 'Date',
  decisions: 'Decisions',
  owner: 'Owner',
  task: 'Task',
  due: 'Due',
}
const labelOf = (fieldId: string) => LABELS[fieldId] ?? fieldId

const scalar = (value: string, evidenceSpanIds: number[] = [1], type: 'TEXT' | 'DATE' | 'CHECKBOX' = 'TEXT'): Proposed => ({
  type,
  cardinality: 'SCALAR',
  value,
  values: null,
  evidenceSpanIds,
})
const column = (values: string[], evidenceSpanIds: number[] = [1], type: 'TEXT' | 'DATE' = 'TEXT'): Proposed => ({
  type,
  cardinality: 'REPEATED',
  value: null,
  values,
  evidenceSpanIds,
})

function proposalOf(
  proposedValues: PatchProposalResponse['proposedValues'],
  skippedRepeatedItems: PatchProposalResponse['skippedRepeatedItems'] = [],
): PatchProposalResponse {
  return {
    id: 1,
    documentId: 1,
    baseRevisionId: 1,
    proposedValues,
    status: 'PROPOSED',
    createdAt: '2026-10-07T00:00:00Z',
    proposedRepeatedItemCount: 0,
    skippedRepeatedItems,
  }
}

const question = (fieldId: string, status: 'OPEN' | 'ANSWERED', answerValue: string | null = null): QuestionResponse => ({
  id: 1,
  fieldId,
  reason: 'MISSING_REQUIRED',
  candidates: [],
  status,
  answerValue,
  createdAt: '2026-10-07T00:00:00Z',
})

const state = (overrides: Partial<FieldStateResponse> = {}): FieldStateResponse => ({
  authorship: 'AI_COMPOSED',
  evidenceSupport: 'DIRECT',
  validation: 'NOT_RUN',
  review: 'UNREVIEWED',
  lock: 'EDITABLE',
  ...overrides,
})

const held = (value: string, fieldState: FieldStateResponse | null = state()): FieldValueResponse => ({
  type: 'TEXT',
  cardinality: 'SCALAR',
  value,
  values: null,
  evidenceSourceSpanIds: [],
  fieldState,
})

const heldRows = (values: string[], itemFieldStates: (FieldStateResponse | null)[]): FieldValueResponse => ({
  type: 'TEXT',
  cardinality: 'REPEATED',
  value: null,
  values,
  evidenceSourceSpanIds: [],
  fieldState: null,
  itemFieldStates,
})

describe('the answers to a reading’s questions', () => {
  it('keeps only answered questions, trimmed, by field', () => {
    const answers = answersByField([question('title', 'ANSWERED', '  Fall kickoff '), question('date', 'OPEN'), question('owner', 'ANSWERED', '   ')])

    expect([...answers]).toEqual([['title', 'Fall kickoff']])
  })

  it('says how many were answered, naming the fill spots', () => {
    expect(answeredWords([question('title', 'ANSWERED', 'x'), question('date', 'ANSWERED', 'y'), question('owner', 'OPEN')], labelOf)).toBe(
      'You answered 2 questions: Meeting title and Date.',
    )
    expect(answeredWords([question('title', 'ANSWERED', 'x')], labelOf)).toBe('You answered 1 question: Meeting title.')
    expect(answeredWords([question('title', 'OPEN')], labelOf)).toBeNull()
  })
})

describe('the answers a question offers', () => {
  const asked = (candidates: QuestionResponse['candidates'], reason: QuestionResponse['reason'] = 'CONFLICT'): QuestionResponse => ({
    ...question('title', 'OPEN'),
    reason,
    candidates,
  })

  it('says which answer the document holds and which the source gives, when it is a choice between the two', () => {
    const conflict = asked([
      { value: 'Harborview Robotics Club', evidenceSpanIds: [] },
      { value: 'Harborview High School Robotics Club', evidenceSpanIds: [4] },
    ])

    expect(choiceWords(conflict, 'Harborview Robotics Club')).toEqual([
      'Harborview Robotics Club, in the document now',
      'Harborview High School Robotics Club, from the source',
    ])
  })

  it('finds the answer the document holds wherever it stands in the list, and ignores spaces around the document’s value', () => {
    const conflict = asked([
      { value: 'Fall kickoff', evidenceSpanIds: [2] },
      { value: 'Old title', evidenceSpanIds: [] },
    ])

    expect(choiceWords(conflict, '  Old title ')).toEqual(['Fall kickoff, from the source', 'Old title, in the document now'])
  })

  it('leaves an answer that is not the document’s and cites nothing as it is', () => {
    const conflict = asked([
      { value: 'Old title', evidenceSpanIds: [] },
      { value: 'A guess', evidenceSpanIds: [] },
    ])

    expect(choiceWords(conflict, 'Old title')).toEqual(['Old title, in the document now', 'A guess'])
  })

  it('offers only the values when none of them is what the document holds', () => {
    const missing = asked(
      [
        { value: 'Room 204', evidenceSpanIds: [1] },
        { value: 'Room 205', evidenceSpanIds: [2] },
      ],
      'MISSING_REQUIRED',
    )

    expect(choiceWords(missing, null)).toEqual(['Room 204', 'Room 205'])
    expect(choiceWords(missing, undefined)).toEqual(['Room 204', 'Room 205'])
    expect(choiceWords(missing, '   ')).toEqual(['Room 204', 'Room 205'])
    expect(choiceWords(missing, 'Somewhere else')).toEqual(['Room 204', 'Room 205'])
    expect(choiceWords(asked([]), 'Room 204')).toEqual([])
  })
})

describe('where a proposed value came from', () => {
  it('cites the source when the proposal cites a passage, and says the person answered when it cites nothing and they did', () => {
    expect(proposedSource(scalar('x', [3]), false)?.text).toBe('Source cited')
    expect(proposedSource(scalar('x', [3]), true)?.text).toBe('Source cited')
    expect(proposedSource(scalar('x', []), true)).toMatchObject({ text: 'Your answer', tone: 'cocoa', icon: 'pencil' })
  })

  it('says nothing when the proposal says nothing', () => {
    expect(proposedSource(scalar('x', []), false)).toBeNull()
  })
})

describe('the table of what a reading found', () => {
  it('has a row for each fill spot, dates as the export prints them, and a label for where each value came from', () => {
    const rows = proposalRows(
      proposalOf({ title: scalar('Fall kickoff'), date: scalar('2026-10-06', [2], 'DATE'), decisions: scalar('Order the kit', []) }),
      labelOf,
      new Map([['decisions', 'Order the kit']]),
    )

    expect(rows.map((row) => [row.label, row.value, row.source?.text ?? null])).toEqual([
      ['Meeting title', 'Fall kickoff', 'Source cited'],
      ['Date', 'October 6, 2026', 'Source cited'],
      ['Decisions', 'Order the kit', 'Your answer'],
    ])
  })

  it('reads a checkbox answer as Checked or Not checked', () => {
    const rows = proposalRows(proposalOf({ news: scalar('checked', [], 'CHECKBOX'), quiet: scalar('unchecked', [], 'CHECKBOX') }), labelOf, new Map())
    expect(rows[0]!.value).toBe('Checked')
    expect(rows[1]!.value).toBe('Not checked')
  })

  it('does not call a value the person’s answer when it is not the answer they gave', () => {
    const rows = proposalRows(proposalOf({ title: scalar('Fall kickoff', []) }), labelOf, new Map([['title', 'Something else']]))

    expect(rows[0]!.source).toBeNull()
  })

  it('puts a table’s columns in one row, one line per row, however many columns there are', () => {
    const rows = proposalRows(
      proposalOf({
        title: scalar('Fall kickoff'),
        owner: column(['Theo Park', 'Ana Reyes']),
        task: column(['Get quotes', 'Book the room']),
        due: column(['2026-10-13', '2026-10-09'], [1], 'DATE'),
      }),
      labelOf,
      new Map(),
    )

    expect(rows).toHaveLength(2)
    expect(rows[1]).toMatchObject({
      label: 'Table rows',
      value: '2 rows',
      columns: 'Owner · Task · Due',
      lines: ['Theo Park · Get quotes · October 13, 2026', 'Ana Reyes · Book the room · October 9, 2026'],
    })
    expect(rows[1]!.source?.text).toBe('Source cited')
  })

  it('follows the order the page draws the fill spots in, with a table where its first column is and what the page does not draw last', () => {
    const rows = proposalRows(
      proposalOf({
        decisions: scalar('Order the kit'),
        elsewhere: scalar('Nowhere on the page'),
        task: column(['Quotes']),
        title: scalar('Fall kickoff'),
        owner: column(['Theo']),
      }),
      labelOf,
      new Map(),
      ['title', 'owner', 'task', 'decisions'],
    )

    expect(rows.map((row) => row.label)).toEqual(['Meeting title', 'Table rows', 'Decisions', 'elsewhere'])
    expect(rows[1]!.columns).toBe('Owner · Task')
  })

  it('labels a table only when every column cites the source, and leaves out a table with no rows', () => {
    const partial = proposalRows(proposalOf({ owner: column(['Theo'], [1]), task: column(['Quotes'], []) }), labelOf, new Map())
    expect(partial[0]!.source).toBeNull()
    expect(proposalRows(proposalOf({ owner: column([]) }), labelOf, new Map())).toEqual([])
    expect(proposalRows(proposalOf({ owner: column(['Theo']) }), labelOf, new Map())[0]!.value).toBe('1 row')
  })
})

describe('rows a reading left out', () => {
  it('says what one row was missing and what it was about, and that the person fills it in', () => {
    const left = leftOutWords([{ itemIndex: 2, unresolvedFieldIds: ['owner', 'due'], description: 'Update the club website' }], labelOf)

    expect(left).toEqual({ lead: 'Left out 1 row with no owner or due: Update the club website. Fill it in yourself before exporting.', items: [] })
  })

  it('does not double a full stop, and copes with a row that has no description or no named gap', () => {
    expect(leftOutWords([{ itemIndex: 0, unresolvedFieldIds: ['due'], description: 'Order batteries.' }], labelOf)?.lead).toBe(
      'Left out 1 row with no due: Order batteries. Fill it in yourself before exporting.',
    )
    expect(leftOutWords([{ itemIndex: 0, unresolvedFieldIds: [], description: null }], labelOf)?.lead).toBe(
      'Left out 1 row. Fill it in yourself before exporting.',
    )
  })

  it('lists each row when more than one was left out', () => {
    const left = leftOutWords(
      [
        { itemIndex: 0, unresolvedFieldIds: ['owner'], description: 'Update the website' },
        { itemIndex: 3, unresolvedFieldIds: ['owner', 'task', 'due'], description: null },
      ],
      labelOf,
    )

    expect(left).toEqual({
      lead: 'Left out 2 rows. Fill them in yourself before exporting:',
      items: ['Update the website (no owner)', 'Row 4 (no owner, task or due)'],
    })
  })

  it('has nothing to say when no row was left out', () => {
    expect(leftOutWords([], labelOf)).toBeNull()
  })
})

describe('a change asked for in the chat', () => {
  it('sets the new value beside the one the document holds, and says the value will need reviewing again', () => {
    const change = proposalChange(proposalOf({ decisions: scalar('Order the kit by Oct 20.', []) }), { decisions: held('Order the new kit before October 20.') }, labelOf)

    expect(change).toEqual({
      fieldId: 'decisions',
      label: 'Decisions',
      before: 'Order the new kit before October 20.',
      after: 'Order the kit by Oct 20.',
      note: 'Approving marks Decisions as not reviewed, so you review it again.',
    })
  })

  it('says a locked value stays as it is, and has an empty before for a value not filled in yet', () => {
    const locked = proposalChange(
      proposalOf({ title: scalar('New title', []) }),
      { title: held('Old title', state({ lock: 'EXPLICITLY_LOCKED' })) },
      labelOf,
    )
    expect(locked?.note).toBe('Meeting title is locked, so approving leaves it as it is.')

    expect(proposalChange(proposalOf({ title: scalar('New title', []) }), {}, labelOf)?.before).toBe('')
  })

  it('is about one value only: a table or several values are drawn as the table of values', () => {
    expect(proposalChange(proposalOf({ owner: column(['Theo']) }), {}, labelOf)).toBeNull()
    expect(proposalChange(proposalOf({ title: scalar('a'), date: scalar('b') }), {}, labelOf)).toBeNull()
  })
})

describe('where a value in the document came from', () => {
  it('reads the document’s own state', () => {
    expect(filledSource(state({ evidenceSupport: 'DIRECT' }), false)?.text).toBe('Source cited')
    expect(filledSource(state({ evidenceSupport: 'TRANSFORMED' }), false)).toMatchObject({ text: 'Source cited, reworded', tone: 'honey' })
    expect(filledSource(state({ evidenceSupport: 'AMBIGUOUS' }), false)?.text).toBe('Evidence unclear')
    expect(filledSource(state({ evidenceSupport: 'UNSUPPORTED' }), false)?.text).toBe('Not in the source')
  })

  it('names a value that cites nothing for the person who gave it, and otherwise says it cites nothing', () => {
    expect(filledSource(state({ evidenceSupport: 'MISSING' }), true)?.text).toBe('Your answer')
    expect(filledSource(state({ evidenceSupport: 'MISSING' }), false)?.text).toBe('No source cited')
    expect(filledSource(state({ evidenceSupport: 'MISSING', authorship: 'USER_AUTHORED' }), false)?.text).toBe('Typed by you')
    expect(filledSource(null, true)?.text).toBe('Your answer')
    expect(filledSource(null, false)).toBeNull()
  })

  // The page words a value's state in one place; the labels here must not drift from it.
  it('uses the same words as the page does for a value’s state', () => {
    for (const evidenceSupport of ['DIRECT', 'TRANSFORMED', 'AMBIGUOUS', 'UNSUPPORTED'] as const) {
      const text = filledSource(state({ evidenceSupport }), false)!.text
      expect(fieldStateWords(state({ evidenceSupport }))).toContain(text)
    }
  })
})

describe('what is left to review', () => {
  const fields = {
    title: held('Fall kickoff'),
    date: held('2026-10-06', state({ review: 'ACCEPTED' })),
    decisions: held('Order the kit', state({ evidenceSupport: 'TRANSFORMED' })),
    owner: heldRows(['Theo', 'Ana'], [state(), state()]),
    task: heldRows(['Quotes', 'Room'], [state(), state()]),
  }

  it('lists each fill spot once, with its table in one entry where its first column was', () => {
    const entries = reviewEntries(['title', 'owner', 'task', 'date', 'decisions'], fields, labelOf, new Map())

    expect(entries.map((entry) => [entry.label, entry.source?.text, entry.reviewTag.text])).toEqual([
      ['Meeting title', 'Source cited', 'Not reviewed'],
      ['Table, 2 rows', 'Source cited', 'Not reviewed'],
      ['Date', 'Source cited', 'Accepted'],
      ['Decisions', 'Source cited, reworded', 'Not reviewed'],
    ])
    expect(entries[1]).toMatchObject({ fieldId: 'owner', fieldIds: ['owner', 'task'] })
  })

  it('lists them in the order the page draws them', () => {
    const entries = reviewEntries(['decisions', 'owner', 'title', 'date'], fields, labelOf, new Map(), ['title', 'date', 'owner', 'decisions'])

    expect(entries.map((entry) => entry.label)).toEqual(['Meeting title', 'Date', 'Table, 2 rows', 'Decisions'])
  })

  it('says a table is accepted only when every row is, and as unsure as its least sure row', () => {
    const rowsWith = (reviews: FieldStateResponse['review'][]) =>
      reviewEntries(['owner'], { owner: heldRows(['a', 'b'], reviews.map((review) => state({ review }))) }, labelOf, new Map())[0]!.review

    expect(rowsWith(['ACCEPTED', 'ACCEPTED'])).toBe('ACCEPTED')
    expect(rowsWith(['ACCEPTED', 'UNREVIEWED'])).toBe('UNREVIEWED')
    expect(rowsWith(['ACCEPTED', 'NEEDS_CLARIFICATION'])).toBe('NEEDS_CLARIFICATION')
    expect(rowsWith(['NEEDS_CLARIFICATION', 'REJECTED'])).toBe('REJECTED')
  })

  it('names the person’s own answers, and leaves out a value no longer in the document', () => {
    const entries = reviewEntries(
      ['title', 'gone'],
      { title: held('Room 204', state({ evidenceSupport: 'MISSING' })) },
      labelOf,
      new Map([['title', 'Room 204']]),
    )

    expect(entries).toHaveLength(1)
    expect(entries[0]!.source).toMatchObject({ text: 'Your answer', tone: 'cocoa' })
  })

  it('tones the review word, with a glyph for every state but not reviewed, so no state is told by colour alone', () => {
    const tags = (['UNREVIEWED', 'ACCEPTED', 'REJECTED', 'NEEDS_CLARIFICATION'] as const).map(
      (review) => reviewEntries(['title'], { title: held('x', state({ review })) }, labelOf, new Map())[0]!.reviewTag,
    )

    expect(tags.map((tag) => tag.text)).toEqual(['Not reviewed', 'Accepted', 'Rejected', 'Needs clarification'])
    expect(tags.map((tag) => tag.icon)).toEqual([null, 'check', 'close-circle', 'help-circle'])
    for (const review of ['UNREVIEWED', 'ACCEPTED', 'REJECTED', 'NEEDS_CLARIFICATION'] as const) {
      expect(fieldStateWords(state({ review }))).toContain(tags[['UNREVIEWED', 'ACCEPTED', 'REJECTED', 'NEEDS_CLARIFICATION'].indexOf(review)]!.text)
    }
  })
})
