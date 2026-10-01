import { describe, expect, it } from 'vitest'
import type {
  FieldStateResponse,
  TemplateLayoutBlockResponse,
  TemplateLayoutInlineResponse,
  TemplateLayoutResponse,
  TemplateLayoutStyleResponse,
} from '@/api/client'
import {
  DATE_DISPLAY_EXAMPLE,
  buildFallbackModel,
  buildPageModel,
  cssFontFamily,
  describeStyle,
  dominantFillSpotStyle,
  fieldStateWords,
  fillSpotStyle,
  formatDateLikeExport,
  hasLineBreak,
  labelFor,
  normalizeLineBreaks,
  pageBaseHalfPoints,
  pageSheetCss,
  stateFromAssist,
  stateNeedsAttention,
  styleToCss,
  type EditableField,
  type PageBlock,
  type PageParagraph,
  type PageSpot,
  type PageTable,
} from '@/workspace/layout'

const BODY: TemplateLayoutStyleResponse = { fontFamily: 'Liberation Sans', fontSizeHalfPoints: 22, colorHex: '000000' }
const LABEL: TemplateLayoutStyleResponse = { ...BODY, bold: true }

function text(value: string, style: TemplateLayoutStyleResponse | null = BODY): TemplateLayoutInlineResponse {
  return { kind: 'TEXT', text: value, style }
}

function fillSpot(fieldId: string, placeholder: string | null = `[${fieldId}]`, style: TemplateLayoutStyleResponse | null = BODY): TemplateLayoutInlineResponse {
  return { kind: 'FILL_SPOT', fieldId, placeholder, style }
}

function paragraph(inlines: TemplateLayoutInlineResponse[], extra: Partial<TemplateLayoutBlockResponse> = {}): TemplateLayoutBlockResponse {
  return { kind: 'PARAGRAPH', repeating: false, alignment: null, listLevel: null, inlines, ...extra }
}

function table(rows: Array<{ repeating?: boolean; cells: TemplateLayoutInlineResponse[][] }>): TemplateLayoutBlockResponse {
  return {
    kind: 'TABLE',
    repeating: false,
    rows: rows.map((row) => ({ repeating: row.repeating ?? false, cells: row.cells.map((inlines) => ({ blocks: [paragraph(inlines)] })) })),
  }
}

function layoutOf(main: TemplateLayoutBlockResponse[], extra: Partial<TemplateLayoutResponse> = {}): TemplateLayoutResponse {
  return {
    templateId: 1,
    versionId: 2,
    parserVersion: 'brownie-docx-graph-v2+poi-5.5.1',
    parts: [{ kind: 'MAIN_DOCUMENT', blocks: main }],
    unplacedFieldIds: [],
    ...extra,
  }
}

const FIELDS: EditableField[] = [
  { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'REQUIRED' },
  { fieldId: 'meeting.date', type: 'DATE', cardinality: 'SCALAR', requiredness: 'REQUIRED' },
  { fieldId: 'action.item.task', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL' },
  { fieldId: 'action.item.due', type: 'DATE', cardinality: 'REPEATED', requiredness: 'OPTIONAL' },
]

function paragraphs(blocks: PageBlock[]): PageParagraph[] {
  return blocks.filter((block): block is PageParagraph => block.kind === 'paragraph')
}

function spotsOf(inlines: PageParagraph['inlines']): PageSpot[] {
  return inlines.filter((inline): inline is PageSpot => inline.kind === 'spot')
}

describe('labelFor', () => {
  it('turns a stable field id into words a person reads', () => {
    expect(labelFor('action.item.due')).toBe('Action item due')
    expect(labelFor('meeting_title')).toBe('Meeting title')
    expect(labelFor('meeting-date')).toBe('Meeting date')
    expect(labelFor('')).toBe('')
  })
})

describe('styleToCss', () => {
  it('draws nothing for a run without a style', () => {
    expect(styleToCss(null, 22)).toEqual({})
    expect(styleToCss(undefined, 22)).toEqual({})
    expect(styleToCss({}, 22)).toEqual({})
  })

  it('maps weight, slant, underline, family, size and colour', () => {
    expect(styleToCss({ bold: true, italic: true, underline: true, fontFamily: 'Liberation Sans', fontSizeHalfPoints: 32, colorHex: 'A1B2C3' }, 22)).toEqual({
      fontWeight: '700',
      fontStyle: 'italic',
      textDecorationLine: 'underline',
      fontFamily: '"Liberation Sans", sans-serif',
      fontSize: '1.4545em',
      color: '#a1b2c3',
    })
  })

  it('leaves a property unset when the template set it off or never set it', () => {
    expect(styleToCss({ bold: false, italic: false, underline: false, fontFamily: null, fontSizeHalfPoints: null, colorHex: null }, 22)).toEqual({})
  })

  it('sizes in em against the page base, falling back to 11 pt when no base is known', () => {
    expect(styleToCss({ fontSizeHalfPoints: 22 }, 22).fontSize).toBe('1em')
    expect(styleToCss({ fontSizeHalfPoints: 24 }, 24).fontSize).toBe('1em')
    expect(styleToCss({ fontSizeHalfPoints: 33 }, null).fontSize).toBe('1.5em')
    expect(styleToCss({ fontSizeHalfPoints: 33 }, 0).fontSize).toBe('1.5em')
    expect(styleToCss({ fontSizeHalfPoints: 11 }, 22).fontSize).toBe('0.5em')
  })

  it('bounds an extreme size and ignores one that is not a size', () => {
    expect(styleToCss({ fontSizeHalfPoints: 3000 }, 22).fontSize).toBe('8em')
    expect(styleToCss({ fontSizeHalfPoints: 1 }, 22).fontSize).toBe('0.25em')
    expect(styleToCss({ fontSizeHalfPoints: 0 }, 22).fontSize).toBeUndefined()
    expect(styleToCss({ fontSizeHalfPoints: -4 }, 22).fontSize).toBeUndefined()
    expect(styleToCss({ fontSizeHalfPoints: 99999 }, 22).fontSize).toBeUndefined()
    expect(styleToCss({ fontSizeHalfPoints: Number.NaN }, 22).fontSize).toBeUndefined()
  })

  it('takes only a six-digit hex colour', () => {
    expect(styleToCss({ colorHex: '000000' }, 22).color).toBe('#000000')
    expect(styleToCss({ colorHex: 'auto' }, 22).color).toBeUndefined()
    expect(styleToCss({ colorHex: 'FFF' }, 22).color).toBeUndefined()
    expect(styleToCss({ colorHex: '#000000' }, 22).color).toBeUndefined()
    expect(styleToCss({ colorHex: '12345G' }, 22).color).toBeUndefined()
  })

  it('never lets a template value carry a second declaration or a URL', () => {
    const hostile = styleToCss(
      { fontFamily: 'x; background: url(https://example.com/a.png)', colorHex: '000000;color:red', fontSizeHalfPoints: 22 },
      22,
    )
    expect(hostile).toEqual({ fontSize: '1em' })
    for (const family of ['Arial"; x', "Arial'", 'url(a)', 'A\\B', 'A/B', 'A,B']) {
      expect(cssFontFamily(family)).toBeNull()
    }
    for (const value of Object.values(styleToCss({ ...LABEL, italic: true, underline: true }, 22))) {
      expect(value).not.toContain(';')
      expect(value).not.toContain('url(')
    }
  })
})

describe('cssFontFamily', () => {
  it('quotes the family and adds a generic fallback that suits it', () => {
    expect(cssFontFamily('Liberation Sans')).toBe('"Liberation Sans", sans-serif')
    expect(cssFontFamily('Times New Roman')).toBe('"Times New Roman", serif')
    expect(cssFontFamily('Liberation Serif')).toBe('"Liberation Serif", serif')
    expect(cssFontFamily('Courier New')).toBe('"Courier New", monospace')
    expect(cssFontFamily('Noto Sans Mono')).toBe('"Noto Sans Mono", monospace')
    expect(cssFontFamily('Calibri')).toBe('"Calibri", sans-serif')
    expect(cssFontFamily('Noto Serif CJK-JP')).toBe('"Noto Serif CJK-JP", serif')
  })

  it('tidies spacing, allows letters beyond ASCII, and refuses empty or overlong names', () => {
    expect(cssFontFamily('  Liberation   Sans ')).toBe('"Liberation Sans", sans-serif')
    expect(cssFontFamily('Liberation\nSans')).toBe('"Liberation Sans", sans-serif')
    expect(cssFontFamily('Sch\u00f6nschrift')).toBe('"Sch\u00f6nschrift", sans-serif')
    expect(cssFontFamily('')).toBeNull()
    expect(cssFontFamily('   ')).toBeNull()
    expect(cssFontFamily(null)).toBeNull()
    expect(cssFontFamily('A'.repeat(65))).toBeNull()
  })
})

describe('describeStyle', () => {
  it('describes the fill spot style of the built-in templates as the Rules card shows it', () => {
    expect(describeStyle(BODY)).toEqual({ font: 'Liberation Sans', size: '11 pt', weight: 'Regular', italic: false, underline: false })
  })

  it('reads bold, italic and underline, and half sizes', () => {
    expect(describeStyle({ bold: true, italic: true, underline: true, fontSizeHalfPoints: 21 })).toEqual({
      font: null,
      size: '10.5 pt',
      weight: 'Bold',
      italic: true,
      underline: true,
    })
  })

  it('says Regular and nothing else for a style it knows nothing about', () => {
    expect(describeStyle(null)).toEqual({ font: null, size: null, weight: 'Regular', italic: false, underline: false })
    expect(describeStyle({ bold: false, fontFamily: '  ', fontSizeHalfPoints: 0 })).toEqual({
      font: null,
      size: null,
      weight: 'Regular',
      italic: false,
      underline: false,
    })
  })
})

describe('fill spot and page styles', () => {
  const heading: TemplateLayoutStyleResponse = { ...LABEL, fontSizeHalfPoints: 32 }
  const quoted: TemplateLayoutStyleResponse = { ...BODY, italic: true }
  const layout = layoutOf(
    [
      paragraph([text('Meeting Minutes', heading)]),
      paragraph([text('Title: ', LABEL), fillSpot('meeting.title', '[meeting title]', quoted)]),
      paragraph([text('Date: ', LABEL), fillSpot('meeting.date')]),
      table([{ cells: [[fillSpot('action.item.task')], [fillSpot('action.item.due')]] }]),
    ],
    {
      parts: [
        { kind: 'HEADER', blocks: [paragraph([text('Header', { ...BODY, fontSizeHalfPoints: 40 }), fillSpot('meeting.title', null, quoted)])] },
      ],
    },
  )
  // The header comes first in `parts` here on purpose: order in the response decides "first", and the
  // main document is what sets the page's base size.
  layout.parts.push({
    kind: 'MAIN_DOCUMENT',
    blocks: [
      paragraph([text('Meeting Minutes', heading)]),
      paragraph([text('Title: ', LABEL), fillSpot('meeting.title', '[meeting title]', quoted)]),
      paragraph([text('Date: ', LABEL), fillSpot('meeting.date')]),
      table([{ cells: [[fillSpot('action.item.task')], [fillSpot('action.item.due')]] }]),
    ],
  })

  it('finds the style most fill spots share, counting table cells, and the earliest on a tie', () => {
    expect(dominantFillSpotStyle(layout)).toEqual(BODY)
    const tie = layoutOf([paragraph([fillSpot('a', null, quoted), fillSpot('b', null, BODY)])])
    expect(dominantFillSpotStyle(tie)).toEqual(quoted)
  })

  it('has no dominant style without fill spots that carry one', () => {
    expect(dominantFillSpotStyle(null)).toBeNull()
    expect(dominantFillSpotStyle(layoutOf([paragraph([text('Only text')])]))).toBeNull()
    expect(dominantFillSpotStyle(layoutOf([paragraph([fillSpot('a', null, null)])]))).toBeNull()
  })

  it('gives a field the style of its first fill spot in reading order', () => {
    expect(fillSpotStyle(layout, 'meeting.title')).toEqual(quoted)
    expect(fillSpotStyle(layout, 'action.item.due')).toEqual(BODY)
    expect(fillSpotStyle(layout, 'missing')).toBeNull()
    expect(fillSpotStyle(null, 'meeting.title')).toBeNull()
  })

  it('takes the page base size from the main document only, defaulting to 11 pt', () => {
    expect(pageBaseHalfPoints(layout)).toBe(22)
    expect(pageBaseHalfPoints(layoutOf([paragraph([text('a', { fontSizeHalfPoints: 24 }), text('b', { fontSizeHalfPoints: 24 }), fillSpot('c')])]))).toBe(24)
    expect(pageBaseHalfPoints(layoutOf([paragraph([text('a', null)])]))).toBe(22)
    expect(pageBaseHalfPoints(null)).toBe(22)
    expect(pageBaseHalfPoints({ ...layout, parts: undefined as unknown as [] })).toBe(22)
  })

  it('sets the sheet in the template font at a base size that draws 11 pt as 1rem', () => {
    expect(pageSheetCss(layout)).toEqual({ fontFamily: '"Liberation Sans", sans-serif', fontSize: '1rem' })
    const twelvePoint = layoutOf([paragraph([text('Body', { fontFamily: 'Georgia', fontSizeHalfPoints: 24 })])])
    expect(pageSheetCss(twelvePoint)).toEqual({ fontFamily: '"Georgia", serif', fontSize: '1.0909rem' })
    expect(pageSheetCss(layoutOf([paragraph([text('Body', null)])]))).toEqual({ fontSize: '1rem' })
    expect(pageSheetCss(null)).toEqual({})
  })
})

describe('formatDateLikeExport', () => {
  it('prints a stored date the way the exported document does', () => {
    expect(formatDateLikeExport('2026-09-28')).toBe(DATE_DISPLAY_EXAMPLE)
    expect(formatDateLikeExport('2026-03-05')).toBe('March 5, 2026')
    expect(formatDateLikeExport('2026-12-31')).toBe('December 31, 2026')
    expect(formatDateLikeExport('0999-01-01')).toBe('January 1, 0999')
    expect(formatDateLikeExport('0000-01-01')).toBe('January 1, 0001')
  })

  it('knows leap years', () => {
    expect(formatDateLikeExport('2024-02-29')).toBe('February 29, 2024')
    expect(formatDateLikeExport('2000-02-29')).toBe('February 29, 2000')
    expect(formatDateLikeExport('1900-02-29')).toBe('1900-02-29')
    expect(formatDateLikeExport('2026-02-29')).toBe('2026-02-29')
  })

  it('returns anything that is not a calendar date unchanged', () => {
    for (const value of ['', 'tomorrow', '2026-9-28', '2026-13-01', '2026-00-10', '2026-04-31', '2026-01-00', '20260928']) {
      expect(formatDateLikeExport(value)).toBe(value)
    }
  })
})

describe('field states', () => {
  const state = (overrides: Partial<FieldStateResponse> = {}): FieldStateResponse => ({
    authorship: 'AI_COMPOSED',
    evidenceSupport: 'DIRECT',
    validation: 'NOT_RUN',
    review: 'UNREVIEWED',
    lock: 'EDITABLE',
    ...overrides,
  })

  it('says where a value came from and what has happened to it', () => {
    expect(fieldStateWords(state())).toEqual(['From Brownie', 'Source cited', 'Not reviewed'])
    expect(fieldStateWords(state({ validation: 'BLOCKING', review: 'REJECTED', lock: 'EXPLICITLY_LOCKED' }))).toEqual([
      'From Brownie',
      'Source cited',
      'Blocks export',
      'Rejected',
      'Locked',
    ])
  })

  it('leaves out what only restates the obvious', () => {
    expect(fieldStateWords(state({ authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING' }))).toEqual(['Typed by you', 'Not reviewed'])
    expect(fieldStateWords(state({ authorship: 'IMPORTED', evidenceSupport: 'MISSING' }))).toEqual(['Imported', 'No source cited', 'Not reviewed'])
    expect(fieldStateWords(null)).toEqual([])
  })

  it('leaves out a value it has no words for rather than showing the server constant', () => {
    const unknown = state({ authorship: 'SOMETHING_NEW' as FieldStateResponse['authorship'], review: 'ESCALATED' as FieldStateResponse['review'] })
    expect(fieldStateWords(unknown)).toEqual(['Source cited'])
  })

  it('marks what needs another look and what came from Assist', () => {
    expect(stateNeedsAttention(state({ validation: 'BLOCKING' }))).toBe(true)
    expect(stateNeedsAttention(state({ review: 'REJECTED' }))).toBe(true)
    expect(stateNeedsAttention(state({ review: 'NEEDS_CLARIFICATION' }))).toBe(true)
    expect(stateNeedsAttention(state({ validation: 'WARNING', review: 'ACCEPTED' }))).toBe(false)
    expect(stateNeedsAttention(null)).toBe(false)
    expect(stateFromAssist(state())).toBe(true)
    expect(stateFromAssist(state({ authorship: 'MIXED' }))).toBe(true)
    expect(stateFromAssist(state({ authorship: 'USER_AUTHORED' }))).toBe(false)
    expect(stateFromAssist(null)).toBe(false)
  })
})

describe('line breaks', () => {
  it('folds each run of line breaks, with the spaces around it, into one space', () => {
    expect(normalizeLineBreaks('Line one\nLine two')).toBe('Line one Line two')
    expect(normalizeLineBreaks('a\r\n\r\nb')).toBe('a b')
    expect(normalizeLineBreaks('a  \n\t  b')).toBe('a b')
    expect(normalizeLineBreaks('a\rb\u000bc\u000cd')).toBe('a b c d')
    expect(normalizeLineBreaks('a\u2028b\u2029c')).toBe('a b c')
    expect(normalizeLineBreaks('  keeps  inner  spaces  ')).toBe('  keeps  inner  spaces  ')
  })

  it('knows when a text holds a line break', () => {
    expect(hasLineBreak('one line')).toBe(false)
    expect(hasLineBreak('two\nlines')).toBe(true)
    expect(hasLineBreak('word\u000bbreak')).toBe(true)
  })
})

describe('buildPageModel', () => {
  it('turns fill spots of scalar fields into spots with the ids "go to field" looks for', () => {
    const model = buildPageModel(
      layoutOf([paragraph([text('Title: ', LABEL), fillSpot('meeting.title', '  [meeting title]  ')]), paragraph([fillSpot('meeting.date', '')])]),
      FIELDS.slice(0, 2),
      0,
    )
    const [first, second] = paragraphs(model.main[0]!.blocks)
    expect(first!.inlines[0]).toMatchObject({ kind: 'text', text: 'Title: ', style: LABEL })
    expect(spotsOf(first!.inlines)[0]).toMatchObject({ fieldId: 'meeting.title', rowIndex: null, inputId: 'edit-meeting.title', placeholder: '[meeting title]' })
    expect(spotsOf(second!.inlines)[0]).toMatchObject({ inputId: 'edit-meeting.date', placeholder: null })
    expect(model.list).toBeNull()
    expect(model.spots.map((spot) => spot.inputId)).toEqual(['edit-meeting.title', 'edit-meeting.date'])
  })

  it('gives the first place a value appears the plain id and numbers the others, in reading order', () => {
    const layout = layoutOf([paragraph([fillSpot('meeting.title')]), paragraph([fillSpot('meeting.title')])], {
      parts: [{ kind: 'HEADER', blocks: [paragraph([fillSpot('meeting.title')])] }],
    })
    layout.parts.push(
      { kind: 'MAIN_DOCUMENT', blocks: [paragraph([fillSpot('meeting.title')]), paragraph([fillSpot('meeting.title')])] },
      { kind: 'FOOTER', blocks: [paragraph([fillSpot('meeting.title')])] },
    )
    const model = buildPageModel(layout, FIELDS.slice(0, 1), 0)
    expect(model.spots.map((spot) => spot.inputId)).toEqual([
      'edit-meeting.title',
      'edit-meeting.title--2',
      'edit-meeting.title--3',
      'edit-meeting.title--4',
    ])
    expect(model.headers).toHaveLength(1)
    expect(model.footers).toHaveLength(1)
  })

  it('keeps every character of text, drops empty text, and keeps images and blank lines', () => {
    const model = buildPageModel(
      layoutOf([paragraph([text('  spaced   out  '), text(''), { kind: 'IMAGE' }]), paragraph([]), paragraph(null as unknown as [])]),
      [],
      0,
    )
    const [first, blank, missing] = paragraphs(model.main[0]!.blocks)
    expect(first!.inlines.map((inline) => inline.kind)).toEqual(['text', 'image'])
    expect(first!.inlines[0]).toMatchObject({ text: '  spaced   out  ' })
    expect(blank!.inlines).toEqual([])
    expect(missing!.inlines).toEqual([])
  })

  it('draws a fill spot this page cannot edit as the template text it keeps', () => {
    const model = buildPageModel(layoutOf([paragraph([fillSpot('unknown.field', '[unknown]'), fillSpot('other.unknown', null)])]), FIELDS, 0)
    expect(paragraphs(model.main[0]!.blocks)[0]!.inlines).toEqual([
      { kind: 'text', key: 'm0/b0/i0', text: '[unknown]', style: BODY },
    ])
  })

  it('normalizes alignment and list levels', () => {
    const model = buildPageModel(
      layoutOf([
        paragraph([text('a')], { alignment: 'CENTER', listLevel: 0 }),
        paragraph([text('b')], { alignment: 'DISTRIBUTE' as 'CENTER', listLevel: -1 }),
        paragraph([text('c')], { alignment: 'JUSTIFY', listLevel: 12 }),
        paragraph([text('d')], { alignment: undefined, listLevel: 1.5 }),
      ]),
      [],
      0,
    )
    expect(paragraphs(model.main[0]!.blocks).map((block) => [block.alignment, block.listLevel])).toEqual([
      ['CENTER', 0],
      [null, null],
      ['JUSTIFY', 8],
      [null, null],
    ])
  })

  describe('a repeating table row', () => {
    const layout = layoutOf([
      paragraph([text('Action Items', LABEL)]),
      table([
        { cells: [[text('Task', LABEL)], [text('Due date', LABEL)]] },
        { repeating: true, cells: [[fillSpot('action.item.task', '[task]')], [fillSpot('action.item.due', '[due date]')]] },
      ]),
      paragraph([text('After the table')]),
    ])

    it('is drawn once per row, each row editing its own item, with Add row right after the table', () => {
      const model = buildPageModel(layout, FIELDS, 2)
      const blocks = model.main[0]!.blocks
      expect(blocks.map((block) => block.kind)).toEqual(['paragraph', 'table', 'add-row', 'paragraph'])
      const rows = (blocks[1] as PageTable).rows
      expect(rows).toHaveLength(3)
      const rowSpots = rows.slice(1).map((row) => row.cells.flatMap((cell) => spotsOf(cell.paragraphs[0]!.inlines)))
      expect(rowSpots.map((spots) => spots.map((spot) => [spot.fieldId, spot.rowIndex, spot.inputId]))).toEqual([
        [
          ['action.item.task', 0, 'edit-action.item.task-0'],
          ['action.item.due', 0, 'edit-action.item.due-0'],
        ],
        [
          ['action.item.task', 1, 'edit-action.item.task-1'],
          ['action.item.due', 1, 'edit-action.item.due-1'],
        ],
      ])
      expect(new Set(rows.map((row) => row.key)).size).toBe(3)
      // Both repeated fields have their place in the rows; only the scalar fields, with no place here, are listed.
      expect(model.list?.columns).toEqual([])
      expect(model.list?.scalars.map((spot) => spot.fieldId)).toEqual(['meeting.title', 'meeting.date'])
    })

    it('stands in with one "No rows yet." row across its columns when there are no rows', () => {
      const model = buildPageModel(layout, FIELDS, 0)
      const rows = (model.main[0]!.blocks[1] as PageTable).rows
      expect(rows).toHaveLength(2)
      expect(rows[1]).toMatchObject({ cells: [], noRowsColumns: 2 })
      expect(model.main[0]!.blocks.filter((block) => block.kind === 'add-row')).toHaveLength(1)
      // The repeated fields still have their place with no rows to draw: they are not listed apart.
      expect(model.list?.columns).toEqual([])
      expect(model.list?.addRow).toBe(false)
    })

    it('is an ordinary row when the document has no repeated fields to fill it', () => {
      const model = buildPageModel(layout, FIELDS.slice(0, 2), 3)
      const rows = (model.main[0]!.blocks[1] as PageTable).rows
      expect(rows).toHaveLength(2)
      expect(rows[1]!.cells[0]!.paragraphs[0]!.inlines).toEqual([{ kind: 'text', key: 'm0/b1/r1/c0/b0/i0', text: '[task]', style: BODY }])
      expect(model.main[0]!.blocks.some((block) => block.kind === 'add-row')).toBe(false)
    })
  })

  it('repeats a repeating paragraph, and draws a repeated field placed elsewhere as its template text', () => {
    const layout = layoutOf([
      paragraph([text('Owner of all: '), fillSpot('action.item.task', '[task]')]),
      paragraph([text('Task: '), fillSpot('action.item.task', '[task]'), text('   Due: '), fillSpot('action.item.due', '[due date]')], {
        repeating: true,
        listLevel: 0,
      }),
    ])
    const model = buildPageModel(layout, FIELDS, 2)
    const blocks = model.main[0]!.blocks
    expect(blocks.map((block) => block.kind)).toEqual(['paragraph', 'paragraph', 'paragraph', 'add-row'])
    expect(paragraphs(blocks)[0]!.inlines.map((inline) => inline.kind)).toEqual(['text', 'text'])
    expect(spotsOf(paragraphs(blocks)[2]!.inlines).map((spot) => spot.inputId)).toEqual(['edit-action.item.task-1', 'edit-action.item.due-1'])
    expect(paragraphs(blocks)[1]!.listLevel).toBe(0)

    const empty = buildPageModel(layout, FIELDS, 0).main[0]!.blocks
    expect(empty.map((block) => block.kind)).toEqual(['paragraph', 'paragraph', 'add-row'])
    expect(paragraphs(empty)[1]).toMatchObject({ noRows: true, inlines: [] })
  })

  it("only clones the main document's region, and places one Add row however many regions there are", () => {
    const repeating = paragraph([fillSpot('action.item.task')], { repeating: true })
    const layout = layoutOf([repeating, repeating], { parts: [{ kind: 'HEADER', blocks: [repeating] }] })
    layout.parts.push({ kind: 'MAIN_DOCUMENT', blocks: [repeating, repeating] })
    const model = buildPageModel(layout, FIELDS, 1)
    expect(paragraphs(model.headers[0]!.blocks)[0]!.inlines[0]).toMatchObject({ kind: 'text', text: '[action.item.task]' })
    expect(model.main[0]!.blocks.filter((block) => block.kind === 'add-row')).toHaveLength(1)
  })

  it('repeats a repeating paragraph inside a table cell and puts Add row after that table', () => {
    const layout = layoutOf([
      {
        kind: 'TABLE',
        repeating: false,
        rows: [
          {
            repeating: false,
            cells: [{ blocks: [paragraph([fillSpot('action.item.task')], { repeating: true }), { kind: 'TABLE', repeating: false, rows: [{ repeating: false, cells: [{ blocks: [paragraph([text('nested')])] }] }] }] }],
          },
        ],
      },
    ])
    const model = buildPageModel(layout, FIELDS, 2)
    const cell = (model.main[0]!.blocks[0] as PageTable).rows[0]!.cells[0]!
    expect(cell.paragraphs.map((block) => block.inlines[0]?.kind)).toEqual(['spot', 'spot', 'text'])
    expect(model.main[0]!.blocks.map((block) => block.kind)).toEqual(['table', 'add-row'])
  })

  it('lists every field left without a place to edit it, after the main document', () => {
    const fields: EditableField[] = [
      ...FIELDS,
      { fieldId: 'meeting.location', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
      { fieldId: 'meeting.notes', type: 'TEXT', cardinality: 'SCALAR', requiredness: null },
    ]
    const layout = layoutOf([paragraph([fillSpot('meeting.title'), fillSpot('meeting.date'), fillSpot('action.item.task')])], {
      unplacedFieldIds: ['meeting.location', 'not.a.field'],
    })
    layout.parts.push({ kind: 'FOOTER', blocks: [paragraph([fillSpot('meeting.date')])] })
    const model = buildPageModel(layout, fields, 1)
    expect(model.list?.scalars.map((spot) => [spot.fieldId, spot.inputId])).toEqual([
      ['meeting.location', 'edit-meeting.location'],
      ['meeting.notes', 'edit-meeting.notes'],
    ])
    expect(model.list?.columns).toEqual(['action.item.task', 'action.item.due'])
    expect(model.list?.rows.map((row) => row.map((spot) => spot.inputId))).toEqual([['edit-action.item.task-0', 'edit-action.item.due-0']])
    // No repeating region on the page, so the list carries the one Add row.
    expect(model.list?.addRow).toBe(true)
    // Reading order: main, the list, then the footer.
    expect(model.spots.map((spot) => spot.inputId)).toEqual([
      'edit-meeting.title',
      'edit-meeting.date',
      'edit-meeting.location',
      'edit-meeting.notes',
      'edit-action.item.task-0',
      'edit-action.item.due-0',
      'edit-meeting.date--2',
    ])
  })

  it('keeps Add row by the rows on the page when only some repeated fields are listed apart', () => {
    const layout = layoutOf([table([{ repeating: true, cells: [[fillSpot('action.item.task')]] }])])
    const model = buildPageModel(layout, FIELDS, 1)
    expect(model.list?.columns).toEqual(['action.item.due'])
    expect(model.list?.addRow).toBe(false)
    expect(model.main[0]!.blocks.map((block) => block.kind)).toEqual(['table', 'add-row'])
  })

  it('reads a layout with parts or lists missing as empty', () => {
    const bare = { templateId: 1, versionId: 2, parserVersion: 'x' } as TemplateLayoutResponse
    const model = buildPageModel(bare, FIELDS.slice(0, 1), 0)
    expect(model.main).toEqual([])
    expect(model.list?.scalars.map((spot) => spot.inputId)).toEqual(['edit-meeting.title'])
    const tableWithoutRows = buildPageModel(layoutOf([{ kind: 'TABLE', repeating: false }]), [], 0)
    expect((tableWithoutRows.main[0]!.blocks[0] as PageTable).rows).toEqual([])
  })
})

describe('buildFallbackModel', () => {
  it('lists scalar fields as lines and repeated fields as a rows table with Add row', () => {
    const model = buildFallbackModel(FIELDS, 2)
    expect(model.headers).toEqual([])
    expect(model.main).toEqual([])
    expect(model.list?.scalars.map((spot) => spot.inputId)).toEqual(['edit-meeting.title', 'edit-meeting.date'])
    expect(model.list?.columns).toEqual(['action.item.task', 'action.item.due'])
    expect(model.list?.rows.map((row) => row.map((spot) => spot.inputId))).toEqual([
      ['edit-action.item.task-0', 'edit-action.item.due-0'],
      ['edit-action.item.task-1', 'edit-action.item.due-1'],
    ])
    expect(model.list?.addRow).toBe(true)
    expect(model.spots).toHaveLength(6)
  })

  it('has no Add row without repeated fields and no list without fields', () => {
    expect(buildFallbackModel(FIELDS.slice(0, 2), 3).list).toMatchObject({ columns: [], rows: [], addRow: false })
    expect(buildFallbackModel([], 0).list).toBeNull()
    expect(buildFallbackModel(FIELDS, -1).list?.rows).toEqual([])
  })
})
