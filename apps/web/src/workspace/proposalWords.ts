import type { FieldStateResponse, FieldValueResponse, PatchProposalResponse, QuestionResponse } from '@/api/client'
import { formatDateLikeExport } from '@/workspace/layout'

/*
 * What the chat says about the values Brownie proposes and then fills in: the table of what a reading found, the
 * one change a request would make, the rows it left out, and the list of what is still to review. The words and
 * the grouping live here so the table and the list agree with each other, and so they can be checked without a page.
 */

type ProposedField = PatchProposalResponse['proposedValues'][string]
type SkippedRow = PatchProposalResponse['skippedRepeatedItems'][number]
type LabelOf = (fieldId: string) => string

/** A small label beside a value. `tone` is the `.tag--*` look it is drawn in (neutral is the plain tag). */
export interface StateTag {
  text: string
  tone: 'neutral' | 'cocoa' | 'honey' | 'success' | 'error'
  icon: 'quote' | 'pencil' | 'check' | 'close-circle' | 'help-circle' | null
}

const SOURCE_CITED: StateTag = { text: 'Source cited', tone: 'neutral', icon: 'quote' }
const YOUR_ANSWER: StateTag = { text: 'Your answer', tone: 'cocoa', icon: 'pencil' }

/** "a", "a and b", "a, b and c". */
function listWords(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

/** "a", "a or b", "a, b or c". */
function orWords(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} or ${items[items.length - 1]}`
}

/** A stored value the way the person reads it: dates the way the export prints them. */
function shown(value: string, type: 'TEXT' | 'DATE'): string {
  return type === 'DATE' ? formatDateLikeExport(value) : value
}

// ---- What a reading found --------------------------------------------------------------------------------------

/** The answers the person gave to a run's questions, by the field each was about. */
export function answersByField(questions: readonly QuestionResponse[]): Map<string, string> {
  const answers = new Map<string, string>()
  for (const question of questions) {
    const answer = question.status === 'ANSWERED' ? question.answerValue?.trim() : undefined
    if (answer) answers.set(question.fieldId, answer)
  }
  return answers
}

/** "You answered 2 questions: Location and Organization.", or null when none was answered. */
export function answeredWords(questions: readonly QuestionResponse[], labelOf: LabelOf): string | null {
  const answered = questions.filter((question) => question.status === 'ANSWERED')
  if (answered.length === 0) return null
  const names = listWords(answered.map((question) => labelOf(question.fieldId)))
  return `You answered ${answered.length === 1 ? '1 question' : `${answered.length} questions`}: ${names}.`
}

/**
 * The words of each answer a question offers. Where one of them is what the document holds now, each answer says where
 * it comes from, so a choice between the document and the source can be made from the choices alone: the one the
 * document holds, and the ones that cite a passage of the source. Where none is the document's, the answers are only
 * their values, since saying "from the source" of every one of them would tell nothing.
 */
export function choiceWords(question: QuestionResponse, documentValue: string | null | undefined): string[] {
  const held = (documentValue ?? '').trim()
  const inDocument = question.candidates.map((candidate) => held !== '' && candidate.value.trim() === held)
  if (!inDocument.includes(true)) return question.candidates.map((candidate) => candidate.value)
  return question.candidates.map((candidate, index) => {
    if (inDocument[index]) return `${candidate.value}, in the document now`
    return (candidate.evidenceSpanIds?.length ?? 0) > 0 ? `${candidate.value}, from the source` : candidate.value
  })
}

/**
 * Where a proposed value came from, as far as the proposal itself says: it cites a passage of the source, or it is the
 * person's own answer to a question. A proposal that says neither gets no label rather than a guess.
 */
export function proposedSource(field: ProposedField, answeredByPerson: boolean): StateTag | null {
  if ((field.evidenceSpanIds?.length ?? 0) > 0) return SOURCE_CITED
  return answeredByPerson ? YOUR_ANSWER : null
}

export interface ProposalRow {
  key: string
  label: string
  /** The value, or for a table how many rows it holds. */
  value: string
  /** For a table: each row on a line of its own, its cells in the order of the columns. */
  lines: string[] | null
  /** For a table: the columns, in the order the cells of each line follow, to be shown above the lines. */
  columns: string | null
  source: StateTag | null
}

/** The fields in the order the page draws them; a field it does not draw goes after those, in the order given. */
function inPageOrder<T>(entries: readonly (readonly [string, T])[], order: readonly string[]): (readonly [string, T])[] {
  const rank = (fieldId: string) => {
    const at = order.indexOf(fieldId)
    return at < 0 ? order.length : at
  }
  return [...entries].sort((left, right) => rank(left[0]) - rank(right[0]))
}

/**
 * One row of the table of what Brownie found for each fill spot it has a value for, in the order the page draws them. A
 * table's columns share one row, since the page draws them as one table and counts it as one fill spot: the row says
 * what every column holds, a line to each row of the table.
 */
export function proposalRows(
  proposal: PatchProposalResponse,
  labelOf: LabelOf,
  answers: ReadonlyMap<string, string>,
  order: readonly string[] = [],
): ProposalRow[] {
  const found = inPageOrder(Object.entries(proposal.proposedValues), order)
  const rows: ProposalRow[] = []
  const columns: { fieldId: string; field: ProposedField; values: string[] }[] = []
  let tableAt = -1
  for (const [fieldId, field] of found) {
    if (field.cardinality === 'REPEATED') {
      if (tableAt < 0) tableAt = rows.length
      columns.push({ fieldId, field, values: (field.values ?? []).map((value) => shown(value, field.type)) })
      continue
    }
    const value = field.value ?? ''
    rows.push({
      key: fieldId,
      label: labelOf(fieldId),
      value: shown(value, field.type),
      lines: null,
      columns: null,
      source: proposedSource(field, answers.get(fieldId) === value.trim()),
    })
  }
  const count = Math.max(0, ...columns.map((column) => column.values.length))
  if (count > 0) {
    rows.splice(tableAt, 0, {
      key: 'table-rows',
      label: 'Table rows',
      value: count === 1 ? '1 row' : `${count} rows`,
      lines: Array.from({ length: count }, (_, index) => columns.map((column) => column.values[index] ?? '').join(' · ')),
      columns: columns.map((column) => labelOf(column.fieldId)).join(' · '),
      // The row is as well supported as its least supported column.
      source: columns.every((column) => (column.field.evidenceSpanIds?.length ?? 0) > 0) ? SOURCE_CITED : null,
    })
  }
  return rows
}

/** The line about rows a reading found but could not fill in whole: what was missing, and that the person adds them. */
export function leftOutWords(skipped: readonly SkippedRow[], labelOf: LabelOf): { lead: string; items: string[] } | null {
  if (skipped.length === 0) return null
  const missing = (row: SkippedRow) => orWords(row.unresolvedFieldIds.map((fieldId) => labelOf(fieldId).toLowerCase()))
  const named = (row: SkippedRow) => (row.description ?? '').trim().replace(/[.!?]+$/, '')
  if (skipped.length === 1) {
    const row = skipped[0]!
    const gap = missing(row)
    const what = named(row)
    const lead = `Left out 1 row${gap ? ` with no ${gap}` : ''}${what ? `: ${what}` : ''}. Fill it in yourself before exporting.`
    return { lead, items: [] }
  }
  return {
    lead: `Left out ${skipped.length} rows. Fill them in yourself before exporting:`,
    items: skipped.map((row) => {
      const gap = missing(row)
      const what = named(row) || `Row ${row.itemIndex + 1}`
      return gap ? `${what} (no ${gap})` : what
    }),
  }
}

// ---- A change asked for in the chat ------------------------------------------------------------------------------

export interface ProposalChange {
  fieldId: string
  label: string
  /** What the document holds now; empty when nothing is filled in. */
  before: string
  after: string
  /** What approving does to the value's review, when that is worth saying. */
  note: string
}

/**
 * The one value a request would change, set beside what the document holds now. Null when the proposal is about
 * more than one value or about a table, which the table of values draws instead.
 */
export function proposalChange(
  proposal: PatchProposalResponse,
  fields: Readonly<Record<string, FieldValueResponse>>,
  labelOf: LabelOf,
): ProposalChange | null {
  const entries = Object.entries(proposal.proposedValues)
  if (entries.length !== 1) return null
  const [fieldId, proposed] = entries[0]!
  if (proposed.cardinality !== 'SCALAR') return null
  const held = fields[fieldId]
  const label = labelOf(fieldId)
  return {
    fieldId,
    label,
    before: shown(held?.value ?? '', proposed.type),
    after: shown(proposed.value ?? '', proposed.type),
    // A locked value is left as it is, whatever is approved; any other value goes back to unreviewed when it changes.
    note:
      held?.fieldState?.lock === 'EXPLICITLY_LOCKED'
        ? `${label} is locked, so approving leaves it as it is.`
        : `Approving marks ${label} as not reviewed, so you review it again.`,
  }
}

// ---- What is left to review --------------------------------------------------------------------------------------

type ReviewState = FieldStateResponse['review']

const REVIEW_TAGS: Record<ReviewState, StateTag> = {
  UNREVIEWED: { text: 'Not reviewed', tone: 'neutral', icon: null },
  ACCEPTED: { text: 'Accepted', tone: 'success', icon: 'check' },
  REJECTED: { text: 'Rejected', tone: 'error', icon: 'close-circle' },
  NEEDS_CLARIFICATION: { text: 'Needs clarification', tone: 'honey', icon: 'help-circle' },
}

/**
 * Where a value that is now in the document came from, from the state the document keeps for it. The person's own
 * answer to a question is theirs to name: the document only knows it cites nothing.
 */
export function filledSource(state: FieldStateResponse | null | undefined, answeredByPerson: boolean): StateTag | null {
  if (!state) return answeredByPerson ? YOUR_ANSWER : null
  switch (state.evidenceSupport) {
    case 'DIRECT':
      return SOURCE_CITED
    case 'TRANSFORMED':
      return { text: 'Source cited, reworded', tone: 'honey', icon: 'quote' }
    case 'AMBIGUOUS':
      return { text: 'Evidence unclear', tone: 'honey', icon: null }
    case 'UNSUPPORTED':
      return { text: 'Not in the source', tone: 'honey', icon: null }
    default:
      if (answeredByPerson) return YOUR_ANSWER
      return state.authorship === 'USER_AUTHORED'
        ? { text: 'Typed by you', tone: 'neutral', icon: 'pencil' }
        : { text: 'No source cited', tone: 'neutral', icon: null }
  }
}

export interface ReviewEntry {
  key: string
  /** Where Go to leads: the field, or the first column of a table. */
  fieldId: string
  /** Every field the entry stands for; a table's columns are one entry. */
  fieldIds: string[]
  label: string
  source: StateTag | null
  review: ReviewState
  reviewTag: StateTag
}

/** A table is reviewed as a whole: accepted once every row is, and as unsure as its least sure row. */
function reviewOfRows(states: readonly (FieldStateResponse | null)[]): ReviewState {
  const reviews = states.map((state) => state?.review ?? 'UNREVIEWED')
  if (reviews.length > 0 && reviews.every((review) => review === 'ACCEPTED')) return 'ACCEPTED'
  if (reviews.includes('REJECTED')) return 'REJECTED'
  if (reviews.includes('NEEDS_CLARIFICATION')) return 'NEEDS_CLARIFICATION'
  return 'UNREVIEWED'
}

/**
 * The fill spots a proposal filled in, in the order the page draws them, each with where its value came from and
 * whether it has been reviewed, as the document now says. A value that has since left the document is not listed; a
 * table's columns are listed once, where its first column is.
 */
export function reviewEntries(
  filledIds: readonly string[],
  fields: Readonly<Record<string, FieldValueResponse>>,
  labelOf: LabelOf,
  answers: ReadonlyMap<string, string>,
  order: readonly string[] = [],
): ReviewEntry[] {
  const entries: ReviewEntry[] = []
  const columns: string[] = []
  let tableAt = -1
  for (const [fieldId] of inPageOrder(
    filledIds.map((id) => [id, id] as const),
    order,
  )) {
    const field = fields[fieldId]
    if (!field) continue
    if (field.cardinality === 'REPEATED') {
      if (tableAt < 0) tableAt = entries.length
      columns.push(fieldId)
      continue
    }
    const state = field.fieldState ?? null
    const review = state?.review ?? 'UNREVIEWED'
    entries.push({
      key: fieldId,
      fieldId,
      fieldIds: [fieldId],
      label: labelOf(fieldId),
      source: filledSource(state, answers.has(fieldId)),
      review,
      reviewTag: REVIEW_TAGS[review],
    })
  }
  if (columns.length > 0) {
    const states = columns.flatMap((fieldId) => fields[fieldId]?.itemFieldStates ?? [])
    const rows = Math.max(0, ...columns.map((fieldId) => fields[fieldId]?.values?.length ?? 0))
    const review = reviewOfRows(states)
    entries.splice(tableAt, 0, {
      key: 'table-rows',
      fieldId: columns[0]!,
      fieldIds: columns,
      label: `Table, ${rows === 1 ? '1 row' : `${rows} rows`}`,
      source: filledSource(states[0], false),
      review,
      reviewTag: REVIEW_TAGS[review],
    })
  }
  return entries
}
