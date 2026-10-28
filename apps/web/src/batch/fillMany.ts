import {
  ApiRequestError,
  type ApiError,
  type BatchRowResponse,
  type FieldDefinitionResponse,
  type TableReadingResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'
import { fieldLabel } from '@/workspace/layout'

/**
 * The words and decisions the fill-from-a-spreadsheet flow shares: how a
 * column is guessed for a fill spot, how the first rows would read as
 * documents, and what every refusal the server can give means to the
 * person in front of it. All of it is plain functions on plain values so
 * the flow's screens stay views over this file, and the wording is tested
 * beside it rather than through a rendered page.
 */

// ---- Reading a file as a table --------------------------------------------------------------

export type TableColumn = TableReadingResponse['columns'][number]
export type TableRow = TableReadingResponse['previewRows'][number]
export type TableCell = TableRow['cells'][number]

/** "B", for "column B" in a notice; a column beyond the letters reads by its number. */
export function columnLetter(column: TableColumn): string {
  return column.letter
}

/**
 * The key a header and a field name are compared by: NFC, then lowercase,
 * then Unicode letters and digits only, so "Họ tên" keeps every letter
 * and "Date awarded" and "award date" never touch while "Award date" and
 * "Award date!" do.
 */
export function normalizedKey(text: string): string {
  return text.normalize('NFC').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')
}

/** The last piece of a field id: "award.date" reads as "date" -- the word a column is most likely named. */
export function lastSegment(fieldId: string): string {
  const dot = fieldId.lastIndexOf('.')
  return dot === -1 ? fieldId : fieldId.slice(dot + 1)
}

/**
 * The column a spot fills from before anyone chooses: the column whose
 * normalized header is the spot's normalized label, else the one whose
 * header is the normalized last segment of the field id. Nothing fuzzier
 * -- a guess that is merely close is a mapping nobody checked.
 */
export function autoMatchColumn(
  field: { fieldId: string; label?: string | null },
  columns: readonly TableColumn[],
): number | null {
  const labelKey = normalizedKey(fieldLabel(field))
  if (labelKey) {
    const byLabel = columns.find((column) => normalizedKey(column.name) === labelKey)
    if (byLabel) return byLabel.index
  }
  const segmentKey = normalizedKey(lastSegment(field.fieldId))
  if (segmentKey) {
    const bySegment = columns.find((column) => normalizedKey(column.name) === segmentKey)
    if (bySegment) return bySegment.index
  }
  return null
}

// ---- Mapping and the preview ------------------------------------------------------------------

/** What one SCALAR fill spot is filled from: a column as a value, or the same column as notes for the model. */
export type MappingMode = 'VALUE' | 'COMPOSE'

export interface FieldChoice {
  /** The chosen column's index; null is the select's "Don't fill". */
  column: number | null
  mode: MappingMode
}

/**
 * The first guess at the whole mapping: every SCALAR spot auto-matched,
 * anything unmatched left at "Don't fill" for the person to set.
 */
export function defaultChoices(
  fields: readonly FieldDefinitionResponse[],
  columns: readonly TableColumn[],
): Map<string, FieldChoice> {
  const choices = new Map<string, FieldChoice>()
  for (const field of fields) {
    if (field.cardinality !== 'SCALAR') continue
    choices.set(field.fieldId, { column: autoMatchColumn(field, columns), mode: 'VALUE' })
  }
  return choices
}

/**
 * The column documents are named after when nobody has chosen one: the
 * column the first required TEXT spot is mapped to, else the first column
 * any spot is mapped to.
 */
export function defaultTitleColumn(
  fields: readonly FieldDefinitionResponse[],
  choices: ReadonlyMap<string, FieldChoice>,
): number | null {
  const firstRequired = fields.find(
    (field) => field.cardinality === 'SCALAR' && field.requiredness === 'REQUIRED' && field.type === 'TEXT',
  )
  if (firstRequired) {
    const column = choices.get(firstRequired.fieldId)?.column
    if (column != null) return column
  }
  for (const field of fields) {
    if (field.cardinality !== 'SCALAR') continue
    const column = choices.get(field.fieldId)?.column
    if (column != null) return column
  }
  return null
}

/** A scalar spot that must be filled but has no column; the "Make" button waits on none being left. */
export function unmappedRequired(
  fields: readonly FieldDefinitionResponse[],
  choices: ReadonlyMap<string, FieldChoice>,
): FieldDefinitionResponse[] {
  return fields.filter(
    (field) =>
      field.cardinality === 'SCALAR' && field.requiredness === 'REQUIRED' && choices.get(field.fieldId)?.column == null,
  )
}

/** A repeated spot can never come from one spreadsheet row; a required one keeps the whole flow blocked. */
export function repeatedSpots(fields: readonly FieldDefinitionResponse[]): {
  field: FieldDefinitionResponse
  blocking: boolean
}[] {
  return fields
    .filter((field) => field.cardinality === 'REPEATED')
    .map((field) => ({ field, blocking: field.requiredness === 'REQUIRED' }))
}

// ---- The first rows as they would read ---------------------------------------------------------

export interface PreviewCell {
  fieldId: string
  /** The text the document would hold, or the reason it would not. */
  display: string
  /** The cell had a problem the read kept. */
  problem: 'FORMULA_WITHOUT_RESULT' | 'ERROR_VALUE' | null
  /** A DATE spot whose cell was not a date. */
  notADate: boolean
  /** A CHECKBOX spot whose cell was not a yes or a no. */
  notACheckbox: boolean
  /** Nothing fills this spot in this row. */
  blank: boolean
}

export interface PreviewDocument {
  rowNumber: number
  title: string
  cells: PreviewCell[]
}

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' })

/** A spreadsheet cell's yes or no: "yes" / "x" / "checked" mean checked, "no" / "false" / "unchecked" mean not. */
function checkboxAnswer(text: string): boolean | null {
  const normalized = text.trim().toLowerCase()
  if (normalized === 'yes' || normalized === 'y' || normalized === 'true' || normalized === 'x' || normalized === 'on' || normalized === '1' || normalized === 'checked') {
    return true
  }
  if (normalized === 'no' || normalized === 'n' || normalized === 'false' || normalized === 'off' || normalized === '0' || normalized === 'unchecked') {
    return false
  }
  return null
}

/**
 * The first five non-blank rows as the documents would read: VALUE
 * mappings write the cell (trimmed text, a long date or "Not a date"),
 * COMPOSE mappings ask to be written from the column's notes. A document's
 * title is the one the server will give it: the template's name with the
 * title column's cell, or the row's place when that cell is empty.
 */
export function previewDocuments(
  reading: TableReadingResponse,
  fields: readonly FieldDefinitionResponse[],
  choices: ReadonlyMap<string, FieldChoice>,
  titleColumn: number | null,
  templateName: string,
): PreviewDocument[] {
  const documents: PreviewDocument[] = []
  for (const row of reading.previewRows) {
    if (documents.length >= 5) break
    if (row.blank) continue
    const titleCell = titleColumn == null ? null : (row.cells[titleColumn]?.display.trim() ?? '')
    const cells: PreviewCell[] = []
    for (const field of fields) {
      if (field.cardinality !== 'SCALAR') continue
      const choice = choices.get(field.fieldId)
      const column = choice?.column
      if (column == null) continue
      const cell = row.cells[column]
      const problem = cell?.problem ?? null
      if (choice?.mode === 'COMPOSE') {
        const notes = cell?.display.trim() ?? ''
        cells.push({
          fieldId: field.fieldId,
          display: notes ? `Brownie writes it from: “${notes}”` : 'Brownie writes it; the notes cell is empty',
          problem,
          notADate: false,
          notACheckbox: false,
          blank: !notes,
        })
        continue
      }
      const text = cell?.display.trim() ?? ''
      const notADate = field.type === 'DATE' && text !== '' && cell?.date == null && problem == null
      const checkbox = field.type === 'CHECKBOX' ? checkboxAnswer(text) : null
      const notACheckbox = field.type === 'CHECKBOX' && text !== '' && checkbox == null && problem == null
      cells.push({
        fieldId: field.fieldId,
        display:
          problem != null
            ? cellProblemWords(problem)
            : text === ''
              ? ''
              : field.type === 'DATE'
                ? cell?.date != null
                  ? longDate.format(new Date(`${cell.date}T00:00:00`))
                  : 'Not a date'
                : field.type === 'CHECKBOX'
                  ? checkbox != null
                    ? (checkbox ? 'Checked' : 'Not checked')
                    : 'Not a yes or no'
                  : text,
        problem,
        notADate,
        notACheckbox,
        blank: text === '',
      })
    }
    // The same name the server gives the document: the template's name, the
    // title cell, or "row N" where that cell is blank.
    documents.push({
      rowNumber: row.rowNumber,
      title: `${templateName}, ${titleCell || `row ${row.rowNumber}`}`,
      cells,
    })
  }
  return documents
}

function cellProblemWords(problem: 'FORMULA_WITHOUT_RESULT' | 'ERROR_VALUE'): string {
  return problem === 'FORMULA_WITHOUT_RESULT' ? 'A formula with no saved result' : 'An error value'
}

// ---- What the server answered with, in words ---------------------------------------------------

/**
 * Every TABLE_UNREADABLE reason as its own sentence, with the row number
 * the read stopped at where the answer gives one. `what` names the file
 * as the person chose it.
 */
export function tableUnreadableSentence(problem: ApiError | undefined, fileName: string): string {
  const reason = problem?.reason
  // The problem's rowNumber is sent for this route's refusals alone, so it sits outside the shared Error schema.
  const row = (problem as { rowNumber?: number } | undefined)?.rowNumber
  const atRow = row != null ? ` at row ${row}` : ''
  switch (reason) {
    case 'NOT_A_TABLE':
      return `This file is not a table Brownie can read${atRow}: its rows do not line up. Open it, save it as an Excel workbook (.xlsx) or as CSV UTF-8, and choose it again.`
    case 'EMPTY':
      return 'This file has no data rows to fill from.'
    case 'TOO_MANY_ROWS':
      return 'This table has more rows than one batch can fill. Split it into smaller files and make a batch from each.'
    case 'TOO_MANY_COLUMNS':
      return 'This table has more columns than a batch can read. Save it with fewer columns and choose it again.'
    case 'CELL_TOO_LONG':
      return `A cell${atRow} is longer than a batch can hold. Shorten it in the file and choose it again.`
    case 'SHEET_NOT_FOUND':
      return 'This workbook does not have the sheet that was asked for. Choose the file again and pick a sheet it has.'
    case 'NOT_UTF8':
      return `"${fileName}" is not text Brownie can trust to read as written. Open it and save it as CSV UTF-8, then choose it again.`
    case 'NOT_A_SPREADSHEET':
      return 'This is a document, not a spreadsheet. Choose a .xlsx or .csv file.'
    case 'DAMAGED':
      return 'Brownie could not read this file. It may be damaged: open it, save it again, and choose it again.'
    default:
      return `Brownie could not read "${fileName}" as a table. Save it as an Excel workbook (.xlsx) or as CSV UTF-8 and choose it again.`
  }
}

/** A file the upload refused as a spreadsheet kind no batch can read (.xls, .ods, Numbers, .xlsm). */
export function refusedSpreadsheetSentence(): string {
  return "This kind of spreadsheet can't be read. Save it as an Excel workbook (.xlsx) or as CSV UTF-8 and choose it again."
}

/**
 * What a failed batch step means and what to do, where the code carries
 * the answer; null for anything a page should ask `describeCommonFailure`
 * or its own fallback about.
 */
export function batchStepSentence(error: unknown): string | null {
  if (!(error instanceof ApiRequestError)) return null
  if (error.routeMissing) return describeCommonFailure(error, 'fill from a spreadsheet')
  const code = error.problem?.code
  switch (code) {
    case 'TEMPLATE_TRASHED':
      return 'This template is in the Trash Bin. Restore it from the Trash Bin to continue making documents.'
    case 'BATCH_SOURCE_CHANGED':
    case 'BATCH_READER_CHANGED':
      return 'The file this batch was made from no longer reads the same way. Start a new batch from the same file.'
    case 'BATCH_COMPOSE_NOT_OFFERED':
      return 'This Brownie does not offer writing field values with the model. Fill every place from a column instead.'
    case 'BATCH_EXPORT_NOT_READY':
      return 'The export is still working, or has nothing to download yet. Wait for it to finish, then download again.'
    case 'BATCH_MAX_ROWS':
      return 'This table has more rows than one batch can fill. Split it into smaller files and make a batch from each.'
    default:
      return null
  }
}

/** BATCH_BUSY is not an error at all: another step is holding the workspace's lease, so the caller waits and asks again. */
export function batchBusy(error: unknown): boolean {
  return error instanceof ApiRequestError && error.problem?.code === 'BATCH_BUSY'
}

// ---- A row's state, for the batch page -----------------------------------------------------------

const PROBLEM_WORDS: Record<string, string> = {
  NOT_A_DATE: "wasn't a date",
  FORMULA_WITHOUT_RESULT: 'is a formula with no saved result',
  ERROR_VALUE: 'is an error value',
  CONTENT_REFUSED: 'could not be filled',
  RESPONSE_UNREADABLE: 'got an answer the server could not read',
  NOTHING_COMPOSED: 'had nothing in its notes Brownie could write from',
  MODEL_UNAVAILABLE: 'could not reach the model',
  DOCUMENT_GONE: 'lost its document',
}

/**
 * One row problem in words: "Date awarded (column C) wasn't a date". The
 * column letter comes from the table's columns where one is known.
 */
export function rowProblemSentence(
  problem: NonNullable<BatchRowResponse['problems']>[number],
  fieldName: (fieldId: string | null | undefined) => string,
  columns: readonly { index: number; letter: string }[],
): string {
  const field = problem.fieldId != null ? fieldName(problem.fieldId) : 'A cell'
  const column = problem.column != null ? columns.find((candidate) => candidate.index === problem.column) : undefined
  const where = column ? ` (column ${column.letter})` : ''
  return `${field}${where} ${PROBLEM_WORDS[problem.code] ?? 'had a problem'}.`
}

/** Why an accept step left a row out, in words. */
export function acceptSkipSentence(code: string | null | undefined): string {
  switch (code) {
    case 'SUGGESTION_WAITING':
      return 'A suggestion is waiting for a person to look at.'
    case 'AI_COMPOSED':
      return 'Text Brownie wrote is still waiting to be accepted on the document.'
    case 'BLOCKING':
      return 'Something blocks the row: a required place is still empty, or a check found one.'
    case 'HAS_PROBLEMS':
      return 'The row filled with a problem; it can be accepted once the document is edited.'
    case 'NOT_CREATED':
      return 'The document has not been made yet.'
    case 'DOCUMENT_GONE':
      return 'The document is not there.'
    case 'ALREADY_ACCEPTED':
      return 'Already accepted.'
    default:
      return 'Could not be accepted.'
  }
}

/** Why an export item was left out of the archive, in words. */
export function exportOutcomeSentence(
  outcome: string,
  detail: string | null | undefined,
  fieldName: (fieldId: string) => string | null,
): string {
  switch (outcome) {
    case 'BLOCKED':
      return detail ? `Blocks export: ${findingWords(detail, fieldName)}` : 'Something in the document blocks export.'
    case 'SUGGESTION_WAITING':
      return 'A suggestion is waiting for a person to look at.'
    case 'CHANGED':
      return 'The document changed after you approved the export; check it and export again.'
    case 'NOT_CREATED':
      return 'The document was never made.'
    case 'DOCUMENT_GONE':
      return 'The document is not there.'
    case 'FAILED':
      return failedDetailWords(detail)
    default:
      return 'Left out of the export.'
  }
}

const FAILED_DETAIL_WORDS: Record<string, string> = {
  EXPORT_FAILED: 'the export did not finish; try again',
  ARTIFACT_INTEGRITY: 'a stored file no longer matched what was checked, so it was not exported',
  EXPORT_NOT_APPROVED: 'the approval did not take; try again',
}

/** A FAILED item's detail code in words; a code never reaches the page. */
function failedDetailWords(detail: string | null | undefined): string {
  const words = detail != null ? FAILED_DETAIL_WORDS[detail] : undefined
  return words !== undefined ? `The export failed: ${words}.` : 'The export failed.'
}

/**
 * One finding detail ("MISSING_REQUIRED_FIELD:member.name") in words, the
 * field's own label where the pinned version gives one. No raw code or
 * field id ever reaches the page.
 */
export function findingWords(detail: string, fieldName: (fieldId: string) => string | null): string {
  const [code = '', fieldId] = detail.split(':', 2)
  const label = fieldId ? fieldName(fieldId) : null
  switch (code) {
    case 'MISSING_REQUIRED_FIELD':
      return label ? `${label} is required and empty` : 'a required place is empty'
    case 'TEXT_LENGTH_EXCEEDED':
      return label ? `${label} is longer than the template allows` : 'a value is longer than the template allows'
    case 'ITEM_COUNT_EXCEEDED':
      return label ? `${label} has more rows than the template allows` : 'a place has more rows than the template allows'
    case 'FIELD_CONTENT_NOT_IN_OUTPUT':
      return label ? `${label} did not appear in the filled file` : 'a value did not appear in the filled file'
    case 'FIXED_FIELD_OVERFLOW':
      return label ? `${label} does not fit its box` : 'a value does not fit its box'
    case 'UNSUPPORTED_CHARACTER':
      return label ? `${label} has a character the PDF cannot show` : 'a value has a character the PDF cannot show'
    case 'CHANGE_OUTSIDE_FILL_SPOTS':
      return 'the page changed outside its fill spots'
    case 'PROTECTED_REGION_MODIFIED':
    case 'LAYOUT_PROTECTED_REGION_CHANGE':
      return 'a part of the form that must not change was changed'
    case 'PACKAGE_INTEGRITY_FAILURE':
      return 'the Word file did not pass a safety check'
    case 'INVALID_EVIDENCE_REFERENCE':
      return 'a cited source is no longer there'
    default:
      return 'something in the document blocks export; open it to see what'
  }
}

// ---- A bounded step's answer, for both pages ---------------------------------------------------

/** The parts of a step answer the loop reads. */
export interface StepAnswer {
  processed: number
  remaining: number
  retryAfterMs?: number | null
}

/** What one step answer tells a loop to do next. */
export type StepNext = { kind: 'done' } | { kind: 'again' } | { kind: 'wait'; ms: number } | { kind: 'stuck' }

/**
 * Reads one step answer: done when nothing is left; wait the server's own
 * `retryAfterMs` where it names one (render capacity ran out, not the
 * step); stopped when it did nothing and named no wait, which only spins
 * if asked again at once.
 */
export function stepNext(step: StepAnswer): StepNext {
  if (step.remaining <= 0) return { kind: 'done' }
  const wait = step.retryAfterMs
  if (wait != null && wait > 0) return { kind: 'wait', ms: wait }
  if (step.processed <= 0) return { kind: 'stuck' }
  return { kind: 'again' }
}

/** What a stuck step is told as: the rows are the place to look. */
export const STEP_NO_PROGRESS =
  'Brownie could not make progress on the remaining rows; open them to see why.'
