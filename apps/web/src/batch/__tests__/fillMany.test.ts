import { describe, expect, it } from 'vitest'
import {
  acceptSkipSentence,
  autoMatchColumn,
  batchStepSentence,
  defaultChoices,
  defaultTitleColumn,
  exportOutcomeSentence,
  findingWords,
  normalizedKey,
  previewDocuments,
  refusedSpreadsheetSentence,
  repeatedSpots,
  rowProblemSentence,
  stepNext,
  STEP_NO_PROGRESS,
  tableUnreadableSentence,
  unmappedRequired,
  type FieldChoice,
} from '@/batch/fillMany'
import { ApiRequestError, type ApiError, type FieldDefinitionResponse, type TableReadingResponse } from '@/api/client'

/** A field definition as the template version answers it. */
function field(overrides: Partial<FieldDefinitionResponse>): FieldDefinitionResponse {
  return {
    fieldId: 'field',
    type: 'TEXT',
    cardinality: 'SCALAR',
    requiredness: 'OPTIONAL',
    bindingKind: 'CONTENT_CONTROL_TAG',
    ...overrides,
  }
}

function columns(names: string[]): TableReadingResponse['columns'] {
  return names.map((name, index) => ({
    index,
    letter: String.fromCharCode(65 + index),
    name,
    inferredType: 'TEXT' as const,
    blankCount: 0,
  }))
}

function reading(overrides: Partial<TableReadingResponse>): TableReadingResponse {
  return {
    artifactId: 9,
    format: 'CSV',
    readerVersion: 'csv-1',
    sheetNames: [],
    sheet: null,
    hasHeaderRow: true,
    ambiguousDates: false,
    columns: columns(['Member name', 'Award', 'Date awarded', 'Citation notes']),
    rowCount: 3,
    blankRowCount: 0,
    previewRows: [],
    notices: [],
    maxRows: 500,
    ...overrides,
  }
}

function row(rowNumber: number, cells: { display: string; date?: string | null; problem?: 'FORMULA_WITHOUT_RESULT' | 'ERROR_VALUE' | null }[]): TableReadingResponse['previewRows'][number] {
  return {
    rowNumber,
    blank: cells.every((cell) => cell.display.trim() === ''),
    cells: cells.map((cell) => ({ display: cell.display, date: cell.date ?? null, problem: cell.problem ?? null })),
  }
}

function problem(detail: Partial<ApiError>): ApiError {
  return { status: 422, title: 't', code: 'TABLE_UNREADABLE', correlationId: 'c', fields: [], recoveryActions: [], ...detail } as ApiError
}

describe('normalizedKey', () => {
  it('compares case-blind, keeping Unicode letters and digits', () => {
    expect(normalizedKey('Member name')).toBe('membername')
    expect(normalizedKey('  Member  Name! ')).toBe('membername')
    expect(normalizedKey('Họ tên')).toBe('họtên')
    expect(normalizedKey('Fecha de expedición')).toBe('fechadeexpedición')
    // Composed and decomposed accents meet at the same key.
    expect(normalizedKey('café')).toBe(normalizedKey('café'.normalize('NFD')))
    // Letters that differ stay different: accents are part of the letter.
    expect(normalizedKey('tên')).not.toBe(normalizedKey('ten'))
  })
})

describe('autoMatchColumn', () => {
  const cols = columns(['Member name', 'Award', 'Date awarded', 'Citation notes'])

  it('matches a column whose header is the spot label, normalized', () => {
    const match = autoMatchColumn(field({ fieldId: 'member.name', label: 'Member name' }), cols)
    expect(match).toBe(0)
  })

  it('matches case and punctuation blind, but nothing else', () => {
    expect(autoMatchColumn(field({ fieldId: 'x', label: '  Member  Name! ' }), cols)).toBe(0)
    expect(autoMatchColumn(field({ fieldId: 'x', label: 'Members name' }), cols)).toBeNull()
    expect(autoMatchColumn(field({ fieldId: 'x', label: 'Name of member' }), cols)).toBeNull()
  })

  it('falls back to the last segment of the field id when the label matches nothing', () => {
    expect(autoMatchColumn(field({ fieldId: 'certificate.award', label: 'Prize' }), cols)).toBe(1)
  })

  it('never guesses from a part of a header', () => {
    expect(autoMatchColumn(field({ fieldId: 'a.award', label: 'Award' }), columns(['Awarded on', 'Notes']))).toBeNull()
  })

  it('matches headers and labels written in another script', () => {
    const match = autoMatchColumn(field({ fieldId: 'full.name', label: 'Họ tên' }), columns(['Họ tên', 'Club']))
    expect(match).toBe(0)
  })
})

describe('defaultChoices and unmappedRequired', () => {
  const fields = [
    field({ fieldId: 'name', label: 'Member name', requiredness: 'REQUIRED' }),
    field({ fieldId: 'date', label: 'Date awarded', type: 'DATE' }),
    field({ fieldId: 'items', cardinality: 'REPEATED', requiredness: 'REQUIRED' }),
  ]

  it('auto-matches only scalar spots and leaves a repeated one out entirely', () => {
    const choices = defaultChoices(fields, columns(['Member name', 'Award', 'Date awarded']))
    expect(choices.size).toBe(2)
    expect(choices.get('name')?.column).toBe(0)
    expect(choices.get('date')?.column).toBe(2)
    expect(choices.has('items')).toBe(false)
  })

  it('names the required scalar spots that still have no column', () => {
    const choices = defaultChoices(fields, columns(['Award']))
    expect(unmappedRequired(fields, choices).map((f) => f.fieldId)).toEqual(['name'])
  })

  it('marks a required repeated spot as blocking', () => {
    const repeated = repeatedSpots(fields)
    expect(repeated).toHaveLength(1)
    expect(repeated[0]!.blocking).toBe(true)
  })
})

describe('defaultTitleColumn', () => {
  it('defaults to the column mapped to the first required TEXT spot', () => {
    const fields = [
      field({ fieldId: 'club', label: 'Club' }),
      field({ fieldId: 'name', label: 'Member name', requiredness: 'REQUIRED' }),
    ]
    const choices = new Map<string, FieldChoice>([
      ['club', { column: 2, mode: 'VALUE' }],
      ['name', { column: 0, mode: 'VALUE' }],
    ])
    expect(defaultTitleColumn(fields, choices)).toBe(0)
  })
})

describe('previewDocuments', () => {
  const fields = [
    field({ fieldId: 'name', label: 'Member name', requiredness: 'REQUIRED' }),
    field({ fieldId: 'award', label: 'Award' }),
    field({ fieldId: 'date', label: 'Date awarded', type: 'DATE' }),
    field({ fieldId: 'notes', label: 'Citation notes' }),
  ]

  const table = reading({
    previewRows: [
      row(2, [{ display: 'Amara' }, { display: 'Star' }, { display: '2025-01-01', date: '2025-01-01' }, { display: ' did well ' }]),
      row(3, [{ display: '' }, { display: '' }, { display: '' }, { display: '' }]),
      row(4, [{ display: 'Ben' }, { display: 'Pin' }, { display: '03/04/2025' }, { display: '=2+2' }]),
      row(5, [{ display: 'Cee' }, { display: 'Cup' }, { display: '2025-03-05', date: '2025-03-05' }, { display: '#N/A', problem: 'ERROR_VALUE' }]),
      row(6, [{ display: 'Dee' }, { display: 'D' }, { display: '2025-04-06', date: '2025-04-06' }, { display: 'x' }]),
      row(7, [{ display: 'Ee' }, { display: 'E' }, { display: '2025-05-07', date: '2025-05-07' }, { display: 'y' }]),
      row(8, [{ display: 'Ff' }, { display: 'F' }, { display: '2025-06-08', date: '2025-06-08' }, { display: 'z' }]),
    ],
  })

  function choices(overrides: Record<string, FieldChoice> = {}): Map<string, FieldChoice> {
    return new Map<string, FieldChoice>([
      ['name', { column: 0, mode: 'VALUE' }],
      ['award', { column: 1, mode: 'VALUE' }],
      ['date', { column: 2, mode: 'VALUE' }],
      ['notes', { column: 3, mode: 'VALUE' }],
      ...Object.entries(overrides),
    ])
  }

  it('shows the first five non-blank rows as the documents would read them', () => {
    const docs = previewDocuments(table, fields, choices(), 0, 'Certificate')
    expect(docs).toHaveLength(5)
    expect(docs.map((d) => d.rowNumber)).toEqual([2, 4, 5, 6, 7])
    // The title is the one the server gives the document: the template's name with the title cell.
    expect(docs[0]!.title).toBe('Certificate, Amara')
  })

  it('trims text and writes a date as a long date or "Not a date"', () => {
    const docs = previewDocuments(table, fields, choices(), 0, 'Certificate')
    const second = docs[1]!
    const dateCell = second.cells.find((c) => c.fieldId === 'date')!
    expect(dateCell.notADate).toBe(true)
    expect(dateCell.display).toBe('Not a date')
    const first = docs[0]!
    expect(first.cells.find((c) => c.fieldId === 'date')!.display).toContain('2025')
    expect(first.cells.find((c) => c.fieldId === 'date')!.display).toContain('January')
    expect(first.cells.find((c) => c.fieldId === 'notes')!.display).toBe('did well')
  })

  it('marks a cell problem as a problem, not a value', () => {
    const docs = previewDocuments(table, fields, choices(), 0, 'Certificate')
    const problemCell = docs[2]!.cells.find((c) => c.fieldId === 'notes')!
    expect(problemCell.problem).toBe('ERROR_VALUE')
  })

  it('names a document after its row number when the title cell is empty or unset', () => {
    const docs = previewDocuments(table, fields, choices(), null, 'Certificate')
    expect(docs[0]!.title).toBe('Certificate, row 2')
  })

  it('reads a COMPOSE mapping as written-from notes, not the value itself', () => {
    const docs = previewDocuments(table, fields, choices({ notes: { column: 3, mode: 'COMPOSE' } }), 0, 'Certificate')
    const notes = docs[0]!.cells.find((c) => c.fieldId === 'notes')!
    expect(notes.display).toContain('Brownie writes it')
  })
})

describe('tableUnreadableSentence', () => {
  it('words a document as a document, not a table', () => {
    expect(tableUnreadableSentence(problem({ reason: 'NOT_A_SPREADSHEET' }), 'form.docx')).toBe(
      'This is a document, not a spreadsheet. Choose a .xlsx or .csv file.',
    )
  })

  it('names the row a malformed table stopped at', () => {
    const sentence = tableUnreadableSentence(
      { ...problem({ reason: 'CELL_TOO_LONG' }), rowNumber: 47 } as ApiError,
      'members.csv',
    )
    expect(sentence).toContain('row 47')
  })

  it('has a plain sentence for every reason', () => {
    for (const reason of ['NOT_A_TABLE', 'EMPTY', 'TOO_MANY_ROWS', 'TOO_MANY_COLUMNS', 'CELL_TOO_LONG', 'SHEET_NOT_FOUND', 'NOT_UTF8', 'NOT_A_SPREADSHEET', 'DAMAGED']) {
      const sentence = tableUnreadableSentence(problem({ reason }), 'x.csv')
      expect(sentence.length).toBeGreaterThan(20)
      expect(sentence).not.toContain(reason)
    }
  })

  it('words a refused spreadsheet kind as the kind that cannot be read', () => {
    expect(refusedSpreadsheetSentence()).toBe(
      "This kind of spreadsheet can't be read. Save it as an Excel workbook (.xlsx) or as CSV UTF-8 and choose it again.",
    )
  })
})

describe('batchStepSentence', () => {
  const error = (code: string) => new ApiRequestError(409, problem({ code }))

  it('says a trashed template stops new documents, not the batch', () => {
    expect(batchStepSentence(error('TEMPLATE_TRASHED'))).toContain('Restore it from the Trash Bin')
  })

  it('says a changed source or reader means a new batch', () => {
    expect(batchStepSentence(error('BATCH_SOURCE_CHANGED'))).toContain('Start a new batch from the same file')
    expect(batchStepSentence(error('BATCH_READER_CHANGED'))).toContain('Start a new batch from the same file')
  })

  it('says compose not offered in words', () => {
    expect(batchStepSentence(error('BATCH_COMPOSE_NOT_OFFERED'))).toContain('does not offer')
  })

  it('says an unready export in words', () => {
    expect(batchStepSentence(error('BATCH_EXPORT_NOT_READY'))).toContain('still working')
  })

  it('returns null for a code it does not own', () => {
    expect(batchStepSentence(error('SOMETHING_ELSE'))).toBeNull()
    expect(batchStepSentence(new Error('x'))).toBeNull()
  })
})

describe('acceptSkipSentence and exportOutcomeSentence', () => {
  const noFields = () => null
  const labels = (fieldId: string) => (fieldId === 'member.name' ? 'Member name' : null)

  it('words every accept skip code', () => {
    for (const code of ['SUGGESTION_WAITING', 'AI_COMPOSED', 'BLOCKING', 'HAS_PROBLEMS', 'NOT_CREATED', 'DOCUMENT_GONE', 'ALREADY_ACCEPTED']) {
      expect(acceptSkipSentence(code).length).toBeGreaterThan(10)
    }
  })

  it('words a left-out export item with its detail, and never shows a raw code or field id', () => {
    expect(exportOutcomeSentence('BLOCKED', 'MISSING_REQUIRED_FIELD:member.name', labels)).toContain(
      'Member name is required and empty',
    )
    expect(exportOutcomeSentence('CHANGED', 'CHANGED_SINCE_APPROVAL', noFields)).toContain('changed')
    expect(exportOutcomeSentence('SUGGESTION_WAITING', null, noFields)).toContain('suggestion')
    for (const outcome of ['BLOCKED', 'FAILED']) {
      expect(exportOutcomeSentence(outcome, 'EXPORT_FAILED', noFields)).not.toContain('EXPORT_FAILED')
    }
  })

  it('words every FAILED detail a person can act on', () => {
    expect(exportOutcomeSentence('FAILED', 'EXPORT_FAILED', noFields)).toBe('The export failed: the export did not finish; try again.')
    expect(exportOutcomeSentence('FAILED', 'ARTIFACT_INTEGRITY', noFields)).toBe(
      'The export failed: a stored file no longer matched what was checked, so it was not exported.',
    )
    expect(exportOutcomeSentence('FAILED', 'EXPORT_NOT_APPROVED', noFields)).toBe(
      'The export failed: the approval did not take; try again.',
    )
    expect(exportOutcomeSentence('FAILED', 'SOMETHING_NEW', noFields)).toBe('The export failed.')
  })
})

describe('findingWords', () => {
  const labels = (fieldId: string) => (fieldId === 'member.name' ? 'Member name' : null)

  it('names the place by its label from the pinned version', () => {
    expect(findingWords('MISSING_REQUIRED_FIELD:member.name', labels)).toBe('Member name is required and empty')
    expect(findingWords('TEXT_LENGTH_EXCEEDED:member.name', labels)).toBe('Member name is longer than the template allows')
    expect(findingWords('ITEM_COUNT_EXCEEDED:member.name', labels)).toBe('Member name has more rows than the template allows')
    expect(findingWords('FIELD_CONTENT_NOT_IN_OUTPUT:member.name', labels)).toBe('Member name did not appear in the filled file')
    expect(findingWords('FIXED_FIELD_OVERFLOW:member.name', labels)).toBe('Member name does not fit its box')
    expect(findingWords('UNSUPPORTED_CHARACTER:member.name', labels)).toBe('Member name has a character the PDF cannot show')
  })

  it('words the findings that carry no place, and every unknown code safely', () => {
    const none = () => null
    expect(findingWords('MISSING_REQUIRED_FIELD', none)).toBe('a required place is empty')
    expect(findingWords('CHANGE_OUTSIDE_FILL_SPOTS', none)).toBe('the page changed outside its fill spots')
    expect(findingWords('PROTECTED_REGION_MODIFIED', none)).toBe('a part of the form that must not change was changed')
    expect(findingWords('LAYOUT_PROTECTED_REGION_CHANGE', none)).toBe('a part of the form that must not change was changed')
    expect(findingWords('PACKAGE_INTEGRITY_FAILURE', none)).toBe('the Word file did not pass a safety check')
    expect(findingWords('INVALID_EVIDENCE_REFERENCE', none)).toBe('a cited source is no longer there')
    const unknown = findingWords('FIELD_REQUIRED:name', none)
    expect(unknown).toBe('something in the document blocks export; open it to see what')
    expect(unknown).not.toContain('FIELD_REQUIRED')
    expect(unknown).not.toContain('name')
  })
})

describe('stepNext', () => {
  it('is done when nothing is left', () => {
    expect(stepNext({ processed: 3, remaining: 0 })).toEqual({ kind: 'done' })
  })

  it('asks again at once when the step moved rows', () => {
    expect(stepNext({ processed: 3, remaining: 7 })).toEqual({ kind: 'again' })
  })

  it('waits the server\'s own retryAfterMs where one is named', () => {
    expect(stepNext({ processed: 3, remaining: 7, retryAfterMs: 4000 })).toEqual({ kind: 'wait', ms: 4000 })
    expect(stepNext({ processed: 0, remaining: 7, retryAfterMs: 4000 })).toEqual({ kind: 'wait', ms: 4000 })
  })

  it('stops a step that did nothing and named no wait, rather than spinning', () => {
    expect(stepNext({ processed: 0, remaining: 7 })).toEqual({ kind: 'stuck' })
    expect(stepNext({ processed: 0, remaining: 7, retryAfterMs: null })).toEqual({ kind: 'stuck' })
    expect(STEP_NO_PROGRESS).toContain('could not make progress')
  })
})

describe('rowProblemSentence', () => {
  const cols = [{ index: 2, letter: 'C' }]

  it('names the field, the column letter and the problem', () => {
    const sentence = rowProblemSentence(
      { code: 'NOT_A_DATE', fieldId: 'award.date', column: 2 },
      (id) => (id === 'award.date' ? 'Date awarded' : (id ?? 'A cell')),
      cols,
    )
    expect(sentence).toBe("Date awarded (column C) wasn't a date.")
  })
})
